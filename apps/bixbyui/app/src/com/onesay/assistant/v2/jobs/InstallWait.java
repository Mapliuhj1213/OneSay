package com.onesay.assistant.v2.jobs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ install-wait（PLAN-install-wait-resume，2026-11-04）：缺应用「选 → 装 → 等 → 接着干」的等待任务（纯 Java，可测）。
 * 状态：WAITING_INSTALL → RESUMING → DONE；或 CANCELLED / EXPIRED。持久化为一段 JSON（Android 侧存 SharedPreferences），进程被杀 / 重启后 {@link #fromJson} 恢复。
 * 匹配：只认候选包名，忽略 EXTRA_REPLACING（升级不算新装）。15 min 未装提醒一次，2 h 过期。
 */
public final class InstallWait {

    public static final String WAITING = "WAITING_INSTALL";
    public static final String RESUMING = "RESUMING";
    public static final String DONE = "DONE";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRED = "EXPIRED";

    public static final long REMIND_MS = 15 * 60 * 1000L;
    public static final long TTL_MS = 2 * 60 * 60 * 1000L;
    public static final long RESUME_GRACE_MS = 3000L;
    public static final long POLL_MS = 5000L;
    public static final int MAX_CANDIDATES = 4;
    public static final String[] STORE_FAIL_OPTIONS = {"重试", "换商店", "取消"};
    public static final String[] REMIND_OPTIONS = {"还要装", "不用了"};

    public static final class Job {
        public final String id;
        public final String utterance;
        public final String goal;
        public final String checkpoint;
        public final List<String> pkgs;
        public final List<String> labels;
        public final long createdAt;
        public final long deadline;
        public String state = WAITING;
        public boolean reminded;
        public String installedPkg;

        public Job(String id, String utterance, String goal, String checkpoint, List<String> pkgs, List<String> labels, long now) {
            this.id = id;
            this.utterance = utterance == null ? "" : utterance;
            this.goal = goal == null ? "" : goal;
            this.checkpoint = checkpoint == null ? "" : checkpoint;
            this.pkgs = new ArrayList<String>();
            this.labels = new ArrayList<String>();
            for (int i = 0; pkgs != null && i < pkgs.size() && this.pkgs.size() < MAX_CANDIDATES; i++) {
                String p = pkgs.get(i);
                if (p == null || p.length() == 0 || this.pkgs.contains(p)) continue;
                this.pkgs.add(p);
                this.labels.add(labels != null && i < labels.size() && labels.get(i) != null ? labels.get(i) : p);
            }
            this.createdAt = now;
            this.deadline = now + TTL_MS;
        }

        public boolean waiting() {
            return WAITING.equals(state);
        }

        public String labelOf(String pkg) {
            int i = pkgs.indexOf(pkg);
            return i >= 0 ? labels.get(i) : pkg;
        }

        public String firstLabel() {
            return labels.isEmpty() ? "应用" : labels.get(0);
        }

        /** 岛上等待态文字。 */
        public String waitingText() {
            return "等你装好 " + firstLabel() + " · 点我回来";
        }

        /** 装好后岛上文字（3 s 未取消即接续）。 */
        public String resumeText() {
            return labelOf(installedPkg) + "装好了，接着帮你" + (goal.length() > 0 ? goal : utterance);
        }

        /** 从 checkpoint 恢复 AgentLoop 用的指令：原话 + 「刚装好、首次启动」上下文。 */
        public String resumeQuery() {
            StringBuilder sb = new StringBuilder(utterance);
            sb.append("（").append(labelOf(installedPkg)).append("刚装好、首次启动；隐私协议和权限弹窗可以处理，登录/验证码问我一次，付款停下");
            if (checkpoint.length() > 0) sb.append("；已完成：").append(checkpoint);
            sb.append("）");
            return sb.toString();
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id).put("u", utterance).put("g", goal).put("c", checkpoint)
                    .put("t0", createdAt).put("dl", deadline).put("st", state).put("rm", reminded);
            if (installedPkg != null) o.put("ip", installedPkg);
            o.put("p", new JSONArray(pkgs)).put("l", new JSONArray(labels));
            return o;
        }

        static Job fromJson(JSONObject o) {
            List<String> p = strings(o.optJSONArray("p"));
            List<String> l = strings(o.optJSONArray("l"));
            long t0 = o.optLong("t0");
            Job j = new Job(o.optString("id"), o.optString("u"), o.optString("g"), o.optString("c"), p, l, t0);
            j.state = o.optString("st", WAITING);
            j.reminded = o.optBoolean("rm");
            j.installedPkg = o.has("ip") ? o.optString("ip") : null;
            return j;
        }
    }

    /** 到期检查给出的动作。 */
    public enum Tick { NONE, REMIND, EXPIRE }

    private static volatile InstallWait sLive = new InstallWait();

    /** 进程内唯一实例（Android 侧 InstallWaitHooks 从持久化恢复后 {@link #setLive}）；S3「定时任务」列表 / 删除读它。 */
    public static InstallWait live() {
        return sLive;
    }

    public static void setLive(InstallWait w) {
        if (w != null) sLive = w;
    }

    /** 「删掉 / 取消 等装 X 的任务」。 */
    public static final java.util.regex.Pattern CANCEL_Q = java.util.regex.Pattern.compile("(删|取消|不用|别).{0,6}(等待|等装|等你装|装好后|安装).{0,12}");

    private final List<Job> mJobs = new ArrayList<Job>();
    private long mSeq;

    public synchronized Job register(String utterance, String goal, String checkpoint, List<String> pkgs, List<String> labels, long now) {
        Job j = new Job("iw" + now + "_" + (++mSeq), utterance, goal, checkpoint, pkgs, labels, now);
        if (j.pkgs.isEmpty()) return null;
        mJobs.add(j);
        return j;
    }

    /** PACKAGE_ADDED：REPLACING 忽略；只认候选包名；多个等待任务各自匹配（返回全部命中的）。 */
    public synchronized List<Job> onPackageAdded(String pkg, boolean replacing, long now) {
        List<Job> hit = new ArrayList<Job>();
        if (pkg == null || replacing) return hit;
        for (Job j : mJobs) {
            if (!j.waiting() || now > j.deadline) continue;
            if (j.pkgs.contains(pkg)) {
                j.installedPkg = pkg;
                j.state = RESUMING;
                hit.add(j);
            }
        }
        return hit;
    }

    /** 轮询兜底 / 重启恢复：查 PackageManager。 */
    public interface Installed {
        boolean isInstalled(String pkg);
    }

    public synchronized List<Job> poll(Installed pm, long now) {
        List<Job> hit = new ArrayList<Job>();
        for (Job j : mJobs) {
            if (!j.waiting() || now > j.deadline) continue;
            for (String p : j.pkgs) {
                if (pm.isInstalled(p)) {
                    j.installedPkg = p;
                    j.state = RESUMING;
                    hit.add(j);
                    break;
                }
            }
        }
        return hit;
    }

    public synchronized Tick tick(Job j, long now) {
        if (!j.waiting()) return Tick.NONE;
        if (now >= j.deadline) {
            j.state = EXPIRED;
            return Tick.EXPIRE;
        }
        if (!j.reminded && now - j.createdAt >= REMIND_MS) {
            j.reminded = true;
            return Tick.REMIND;
        }
        return Tick.NONE;
    }

    /** 装好后 3 s 内点了「取消」→ CANCELLED；否则调用方接续后 {@link #done}。 */
    public synchronized boolean cancel(String id) {
        for (Job j : mJobs) {
            if (j.id.equals(id) && (j.waiting() || RESUMING.equals(j.state))) {
                j.state = CANCELLED;
                return true;
            }
        }
        return false;
    }

    /** ★ backlog #3：安装卡「不用了」—— 取消候选包与这些包相交的全部等装任务（等待中 / 刚装好待接续）。返回取消个数。 */
    public synchronized int cancelPkgs(java.util.Collection<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) return 0;
        int n = 0;
        for (Job j : mJobs) {
            if (!(j.waiting() || RESUMING.equals(j.state))) continue;
            for (String p : j.pkgs) {
                if (pkgs.contains(p)) {
                    j.state = CANCELLED;
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    public synchronized void done(String id) {
        for (Job j : mJobs) if (j.id.equals(id)) j.state = DONE;
    }

    /** 「定时任务」里删除：按序号（1 起）或按应用名 / 原话片段。 */
    private String mLastCancelQ;
    private Job mLastCancelJob;
    private long mLastCancelAt;

    /** 同一句在 10 s 内重复调用（分诊 claims 判一次、route 再执行一次）返回同一结果，不会第二次落空。 */
    public synchronized Job cancelMatching(String text) {
        long now = System.currentTimeMillis();
        if (text != null && text.equals(mLastCancelQ) && now - mLastCancelAt < 10000L) return mLastCancelJob;
        Job r = cancelMatching0(text);
        if (r != null) {
            mLastCancelQ = text;
            mLastCancelJob = r;
            mLastCancelAt = now;
        }
        return r;
    }

    private Job cancelMatching0(String text) {
        List<Job> a = active();
        if (a.isEmpty()) return null;
        String t = text == null ? "" : text.trim();
        Job pick = null;
        for (Job j : a) {
            for (String l : j.labels) if (t.length() > 0 && (t.contains(l) || l.contains(t))) pick = j;
            if (pick == null && t.length() >= 2 && j.utterance.contains(t)) pick = j;
            if (pick != null) break;
        }
        if (pick == null && (t.contains("等待") || t.contains("等装") || t.contains("安装")) && a.size() == 1) pick = a.get(0);
        if (pick != null) pick.state = CANCELLED;
        return pick;
    }

    public synchronized List<Job> active() {
        List<Job> a = new ArrayList<Job>();
        for (Job j : mJobs) if (j.waiting()) a.add(j);
        return a;
    }

    /** 「有哪些定时任务」里追加的行；没有 = ""。 */
    public synchronized String listLines(long now) {
        StringBuilder sb = new StringBuilder();
        for (Job j : active()) {
            long left = Math.max(0, (j.deadline - now) / 60000L);
            sb.append("\n· 等装好 ").append(j.firstLabel()).append(" 后接着").append(j.goal.length() > 0 ? j.goal : j.utterance)
                    .append("（还剩 ").append(left).append(" 分钟）");
        }
        return sb.toString();
    }

    /** 清掉终态（持久化前调用）。 */
    public synchronized void prune() {
        for (int i = mJobs.size() - 1; i >= 0; i--) {
            String s = mJobs.get(i).state;
            if (DONE.equals(s) || CANCELLED.equals(s) || EXPIRED.equals(s)) mJobs.remove(i);
        }
    }

    public synchronized String toJson() {
        JSONArray a = new JSONArray();
        try {
            for (Job j : mJobs) if (j.waiting() || RESUMING.equals(j.state)) a.put(j.toJson());
        } catch (Exception ignored) {
        }
        return a.toString();
    }

    /** 进程重建 / 开机后恢复：RESUMING 中断的回到 WAITING（再查一次是否已装好）。 */
    public static InstallWait fromJson(String s) {
        InstallWait w = new InstallWait();
        if (s == null || s.length() == 0) return w;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                Job j = Job.fromJson(a.getJSONObject(i));
                if (RESUMING.equals(j.state)) j.state = WAITING;
                w.mJobs.add(j);
            }
        } catch (Exception ignored) {
        }
        return w;
    }

    static List<String> strings(JSONArray a) {
        List<String> l = new ArrayList<String>();
        for (int i = 0; a != null && i < a.length(); i++) l.add(a.optString(i));
        return l;
    }
}
