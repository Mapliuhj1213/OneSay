package com.onesay.assistant.v2.notice;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * · 麦克风 / 相机正在使用的指示（纯 Java，JVM 可测）。
 *
 * <p><b>Android 9 上怎么拿到「谁在用」</b>（取舍）：
 * <ul>
 *   <li>触发：公开 API 的事件回调，不轮询——{@code AudioManager.registerAudioRecordingCallback}（API 24，录音会话增减）
 *       和 {@code CameraManager.AvailabilityCallback}（相机被占用 / 释放）。没有事件时零开销。</li>
 *   <li>归属：Android 9 给普通 App 的录音配置是匿名的（没有包名），{@code AppOpsManager.startWatchingActive} 在 API 28 还是隐藏接口；
 *       所以事件来了以后经 root 守护<b>读一次</b> {@code dumpsys audio} / {@code dumpsys media.camera}，用这里的纯函数解析出包名。
 *       同一个录音会话 id 解析过就缓存，不重复 dumpsys。</li>
 *   <li>root 不可用 → 识别不了使用方 → <b>不显示</b>（OneSay 自己常驻录音做唤醒，不能把「有人在录音」都当成别人）。</li>
 * </ul>
 * <b>OneSay 自己录音 / 用相机时不提示自己</b>：{@link #OWN_PACKAGES} 里的包一律过滤。
 */
public final class PrivacyNotices {

    private PrivacyNotices() {}

    public enum Kind { MIC, CAM }

    /** 自己（release + debug）：自己在用不提示。 */
    public static final Set<String> OWN_PACKAGES = new LinkedHashSet<String>();
    static {
        OWN_PACKAGES.add("com.onesay.assistant");
        OWN_PACKAGES.add("com.onesay.assistant.debug");
    }

    /** 状态持续这么久才上岛（相机 App 预热、某些 App 一闪而过的探测不打扰）。 */
    public static final long DEBOUNCE_MS = 1500L;

    // ------------------------------------------------------------------ dumpsys 解析

    /** Android 9 AudioRecordingConfiguration.toLogFriendlyString：「session:12 -- source:MIC -- uid:10123 -- pack:com.tencent.mm -- format …」。 */
    static final Pattern AUDIO_REC = Pattern.compile("session:(\\d+)\\s*--\\s*source:(\\S+)\\s*--\\s*uid:(\\d+)\\s*--\\s*pack:([A-Za-z0-9_.]+)");
    /** Android 9 「Active Camera Clients」里的「Client Package Name: com.xxx」；Android 8 的「Client package: com.xxx」。 */
    static final Pattern CAM_CLIENT = Pattern.compile("Client [Pp]ackage(?: [Nn]ame)?:\\s*([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)");

    /** dumpsys audio → 正在录音的 会话 id → 包名（只取 RecordActivityMonitor 段；读不到返回空 map）。 */
    public static Map<Integer, String> parseAudio(String dump) {
        Map<Integer, String> m = new LinkedHashMap<Integer, String>();
        if (dump == null) return m;
        String s = dump;
        int at = s.indexOf("RecordActivityMonitor");
        if (at >= 0) s = s.substring(at);
        Matcher x = AUDIO_REC.matcher(s);
        while (x.find()) {
            try {
                m.put(Integer.valueOf(x.group(1)), x.group(4));
            } catch (NumberFormatException ignored) {
            }
        }
        return m;
    }

    /** ★ integrate 上机（三星 Note8 / Android 9）：dumpsys audio 里没有 RecordActivityMonitor 段；
     *  dumpsys media.audio_policy 的「Inputs dump → Audio session」里有「- session: N」+「- owner uid: U」。 */
    static final Pattern POLICY_SESSION = Pattern.compile("-\\s*session:\\s*(\\d+)");
    static final Pattern POLICY_UID = Pattern.compile("-\\s*owner uid:\\s*(\\d+)");

    /** dumpsys media.audio_policy → 录音会话 id → 归属 uid（只看「Inputs dump」之后；读不到返回空 map）。uid 转包名由调用方用 PackageManager 做。 */
    public static Map<Integer, Integer> parsePolicyInputs(String dump) {
        Map<Integer, Integer> m = new LinkedHashMap<Integer, Integer>();
        if (dump == null) return m;
        String s = dump;
        int at = s.indexOf("Inputs dump");
        if (at >= 0) s = s.substring(at);
        Integer session = null;
        for (String line : s.split("\n")) {
            Matcher a = POLICY_SESSION.matcher(line);
            if (a.find()) {
                try { session = Integer.valueOf(a.group(1)); } catch (NumberFormatException e) { session = null; }
                continue;
            }
            Matcher u = POLICY_UID.matcher(line);
            if (u.find() && session != null) {
                try { m.put(session, Integer.valueOf(u.group(1))); } catch (NumberFormatException ignored) { }
                session = null;
            }
        }
        return m;
    }

    /** dumpsys media.camera → 正在用相机的包名（只看「Active Camera Clients」段之后；Android 8 退回全文）。 */
    public static Set<String> parseCamera(String dump) {
        Set<String> out = new LinkedHashSet<String>();
        if (dump == null) return out;
        String s = dump;
        int at = s.indexOf("Active Camera Clients");
        if (at >= 0) {
            s = s.substring(at);
            int end = s.indexOf("\n]");
            if (end > 0) s = s.substring(0, end);
        }
        Matcher x = CAM_CLIENT.matcher(s);
        while (x.find()) out.add(x.group(1));
        return out;
    }

    /** 去掉自己和 Android 系统进程（「android」「system」「audioserver」这类非 App 名字本来就匹配不上包名格式）。 */
    public static Set<String> others(Collection<String> pkgs) {
        Set<String> s = new LinkedHashSet<String>();
        if (pkgs == null) return s;
        for (String p : pkgs) if (p != null && !OWN_PACKAGES.contains(p) && NoticeAction.safePackage(p)) s.add(p);
        return s;
    }

    // ------------------------------------------------------------------ 提醒

    public static String keyOf(Kind k, String pkg, long since) {
        return "priv:" + (k == Kind.MIC ? "mic" : "cam") + ":" + pkg + ":" + since;
    }

    public static String prefixOf(Kind k, String pkg) {
        return "priv:" + (k == Kind.MIC ? "mic" : "cam") + ":" + pkg + ":";
    }

    /** 一条「正在使用」提醒：标题「麦克风使用中 · 微信」，计时 = 已用多久；动作「应用信息」（去关权限）/「知道了」。不落库（重启后按当前状态重来）。 */
    public static IslandNotice notice(Kind k, String pkg, String label, long since) {
        String what = k == Kind.MIC ? "麦克风" : "相机";
        String app = label == null || label.trim().length() == 0 ? pkg : label.trim();
        IslandNotice n = new IslandNotice(IslandNotice.Source.PRIVACY, keyOf(k, pkg, since), IslandNotice.Priority.NORMAL,
                what + "使用中 · " + app, "「" + app + "」正在使用" + what + "。不是你打开的？点「应用信息」去关掉权限");
        n.sourceTag = "privacy";
        n.tone = k == Kind.MIC ? "mic" : "cam";
        n.chronoBase = since;
        n.persist = false;
        n.action(new NoticeAction("应用信息", NoticeAction.Kind.OPEN_APP, "settings:" + pkg));
        n.action(new NoticeAction("知道了", NoticeAction.Kind.DISMISS, ""));
        return n;
    }

    /** 两次快照的差（纯函数）：started = 新出现的包，stopped = 不在了的包。 */
    public static final class Diff {
        public final List<String> started = new ArrayList<String>();
        public final List<String> stopped = new ArrayList<String>();

        public boolean empty() {
            return started.isEmpty() && stopped.isEmpty();
        }
    }

    public static Diff diff(Set<String> before, Set<String> after) {
        Diff d = new Diff();
        Set<String> b = before == null ? new LinkedHashSet<String>() : before, a = after == null ? new LinkedHashSet<String>() : after;
        for (String p : a) if (!b.contains(p)) d.started.add(p);
        for (String p : b) if (!a.contains(p)) d.stopped.add(p);
        return d;
    }

    /**
     * 录音会话缓存（纯逻辑）：系统回调给的是匿名会话 id 列表；见过的 id 直接查表，出现没见过的 id 才需要 root dumpsys。
     * 我们自己的会话（唤醒常驻录音）解析一次后也记在表里（值是自己的包名），之后同一个会话再回调不会再 dumpsys。
     */
    public static final class SessionCache {
        private final Map<Integer, String> mById = new LinkedHashMap<Integer, String>();

        /** 这批会话里有没见过的（需要 dumpsys）。 */
        public synchronized boolean needsLookup(Collection<Integer> sessions) {
            if (sessions == null) return false;
            for (Integer s : sessions) if (s != null && !mById.containsKey(s)) return true;
            return false;
        }

        public synchronized void learn(Map<Integer, String> parsed) {
            if (parsed != null) mById.putAll(parsed);
            while (mById.size() > 64) mById.remove(mById.keySet().iterator().next());
        }

        /** 这批会话对应的包（查不到的会话忽略）。 */
        public synchronized Set<String> packagesOf(Collection<Integer> sessions) {
            Set<String> s = new LinkedHashSet<String>();
            if (sessions == null) return s;
            for (Integer id : sessions) {
                String p = id == null ? null : mById.get(id);
                if (p != null) s.add(p);
            }
            return s;
        }

        /** 这批会话里还有查不到归属的（dumpsys 之后仍未知 = root 不可用或格式不认识）。 */
        public synchronized boolean hasUnknown(Collection<Integer> sessions) {
            if (sessions == null) return false;
            for (Integer id : sessions) if (id != null && !mById.containsKey(id)) return true;
            return false;
        }
    }
}
