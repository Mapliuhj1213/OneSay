package com.onesay.assistant.v2.timeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ★ feat/api-first（2026-11-11）：对话里每次回答上方那一行可折叠的「执行时间轴」的数据模型。纯 Java，JVM 可测。
 *
 * <p><b>不另起记录</b>：只消费已有的事件 —— AgentLoop 的步骤事件（{@code StepEvents.onToolStep} 每次工具调用开始、
 * 失败 / 整理结论的 note）、本分支补的步骤结果（{@code StepResultEvents}，带失败原因）、确认卡（宿主 onRequireConfirm 的挂起 / 裁决）、
 * 停止键与结局。内存里随这一轮走，冻结进对话历史；不落盘、不写新日志。
 *
 * <p>展示规则（思路借鉴魅族 Aicy 的执行痕迹，闭源，只借思路）：
 * <ul>
 *   <li>执行中：标题「正在调用 X」，默认展开；展开后竖线列出每步 —— ✓ 已调用 X（同一工具连续调用合并成「X 2次」）、
 *       进行中灰点、失败 ✗ 带原因、确认卡也算一步；</li>
 *   <li>完成：自动折叠成「已完成」（失败「没完成」、停止「已停止」），点开还能看每一步。</li>
 * </ul>
 */
public final class ExecTimeline {

    public enum State { RUNNING, DONE, FAILED, WAITING, STOPPED }

    public enum Phase { IDLE, THINKING, RUNNING, WAITING, DONE, FAILED, STOPPED }

    /** 时间轴上的一行。 */
    public static final class Row {
        public final String label;
        public int count = 1;
        public State state;
        public String reason = "";
        public String detail = "";
        public final boolean approval;
        /** ★ borrow-1112：说明行（等待期间画面上出现 / 消失了什么、已切换到备用模型），不是一次工具调用。 */
        public final boolean info;

        Row(String label, State state, boolean approval) {
            this(label, state, approval, false);
        }

        Row(String label, State state, boolean approval, boolean info) {
            this.label = label;
            this.state = state;
            this.approval = approval;
            this.info = info;
        }

        /** 这一行的字（不含前面的 ✓ / ✗ / 灰点，那是图标）。 */
        public String text() {
            if (info) return label;
            if (approval) {
                if (state == State.WAITING) return "等你确认：" + label;
                if (state == State.FAILED) return "没批准：" + label;
                if (state == State.STOPPED) return "已停止：" + label;
                return "已确认：" + label;
            }
            switch (state) {
                case RUNNING:
                    return "正在调用 " + label + (count > 1 ? "（第" + count + "次）" : "");
                case FAILED:
                    return label + " 失败" + (reason.length() > 0 ? "：" + reason : "");
                case STOPPED:
                    return label + " 已停止";
                default:
                    return "已调用 " + label + (count > 1 ? " " + count + "次" : "");
            }
        }

        /** 图标：✓ 已完成 / ✗ 失败 / ● 进行中（灰点）/ ? 等确认 / ■ 停止。 */
        public String icon() {
            if (info) return "·";
            switch (state) {
                case DONE: return "✓";
                case FAILED: return "✗";
                case WAITING: return "?";
                case STOPPED: return "■";
                default: return "●";
            }
        }
    }

    private final List<Row> mRows = new ArrayList<Row>();
    private Phase mPhase = Phase.IDLE;
    private boolean mExpanded = true;
    private boolean mUserToggled;

    // ------------------------------------------------------------------ 事件

    /** 工具调用开始（StepEvents.onToolStep(text, counts=true)）。 */
    public synchronized void toolStart(String stepText) {
        if (terminal()) return;
        String label = labelOf(stepText);
        if (label.length() == 0) return;
        Row cur = running();
        if (cur != null) cur.state = State.DONE;   // 上一步没报失败 = 成功
        Row last = last();
        if (last != null && !last.approval && !last.info && last.state == State.DONE && last.label.equals(label)) {
            last.count++;
            last.state = State.RUNNING;
        } else {
            mRows.add(new Row(label, State.RUNNING, false));
        }
        mPhase = Phase.RUNNING;
    }

    /** 工具调用结束（本分支补的 StepResultEvents）。ok=false 时 reason 是失败原因。 */
    public synchronized void toolResult(boolean ok, String reasonOrDetail) {
        Row cur = running();
        if (cur == null) return;
        String r = reasonOrDetail == null ? "" : reasonOrDetail.trim();
        if (ok) {
            cur.state = State.DONE;
            if (r.length() > 0) cur.detail = r;
            return;
        }
        if (cur.count > 1) {   // 合并行里最后一次失败 → 拆出来单独一行，前面几次仍算成功
            cur.count--;
            cur.state = State.DONE;
            cur = new Row(cur.label, State.FAILED, false);
            mRows.add(cur);
        }
        cur.state = State.FAILED;
        cur.reason = shorten(r.length() > 0 ? r : "没成功");
    }

    /** 只改当前一行的提示（StepEvents.onToolStep(text, counts=false)）：「搜索失败：X」这种失败行如实记成失败。 */
    public synchronized void note(String text) {
        if (text == null) return;
        Row cur = running();
        if (cur == null) return;
        int k = text.indexOf('：');
        String head = k > 0 ? text.substring(0, k) : text;
        if (head.endsWith("失败") || text.endsWith("没成功")) {
            if (cur.state == State.RUNNING) toolResult(false, "");
        }
    }

    /** 等模型（思考中）。 */
    public synchronized void thinking(boolean on) {
        if (terminal()) return;
        if (on && running() == null && waiting() == null) mPhase = Phase.THINKING;
        else if (!on && mPhase == Phase.THINKING) mPhase = mRows.isEmpty() ? Phase.IDLE : Phase.RUNNING;
    }

    /** 确认卡弹出（审批也算一步）。 */
    public synchronized void approval(String title) {
        if (terminal()) return;
        String t = title == null || title.trim().length() == 0 ? "这一步" : shorten(title.trim());
        mRows.add(new Row(t, State.WAITING, true));
        mPhase = Phase.WAITING;   // 确认卡在某个工具执行前弹出：那一行留着「进行中」，批了接着跑
    }

    /** 确认卡有了裁决。 */
    public synchronized void approvalResolved(boolean allowed) {
        Row w = waiting();
        if (w == null) return;
        w.state = allowed ? State.DONE : State.FAILED;
        if (!terminal()) mPhase = Phase.RUNNING;
    }

    /**
     * ★ borrow-1112：补一行说明（「等待期间：出现「加载完成」」「已切换到备用模型」）。不改当前进行中的那一行，
     * 连着两次一样的只记一次，一轮最多 {@link #MAX_INFO} 行（多了不再加，免得刷屏）。
     */
    public synchronized void info(String text) {
        if (terminal() || text == null) return;
        String t = shorten(text.trim(), 60);
        if (t.length() == 0) return;
        int n = 0;
        for (Row r : mRows) if (r.info) n++;
        if (n >= MAX_INFO) return;
        Row last = last();
        if (last != null && last.info && last.label.equals(t)) return;
        mRows.add(new Row(t, State.DONE, false, true));
        if (mPhase == Phase.IDLE) mPhase = Phase.RUNNING;
    }

    public static final int MAX_INFO = 12;

    /** 停止键：进行中的一律记成已停止，时间轴最后多一行「已停止」。 */
    public synchronized void stopped() {
        if (mPhase == Phase.STOPPED) return;
        for (Row r : mRows) if (!r.info && (r.state == State.RUNNING || r.state == State.WAITING)) r.state = State.STOPPED;
        mPhase = Phase.STOPPED;
        if (!mUserToggled) mExpanded = false;
    }

    /** 任务结束（ok = 结局是 done）。进行中的行收成 ✓，没被用户手动展开过就自动折叠。 */
    public synchronized void finish(boolean ok) {
        if (mPhase == Phase.STOPPED) return;
        for (Row r : mRows) {
            if (r.state == State.RUNNING) r.state = State.DONE;
            if (r.state == State.WAITING) r.state = State.FAILED;
        }
        mPhase = ok ? Phase.DONE : Phase.FAILED;
        if (!mUserToggled) mExpanded = false;
    }

    /** 点标题行：展开 / 折叠。 */
    public synchronized boolean toggle() {
        mUserToggled = true;
        mExpanded = !mExpanded;
        return mExpanded;
    }

    // ------------------------------------------------------------------ 读

    public synchronized boolean visible() {
        return !mRows.isEmpty();
    }

    public synchronized boolean expanded() {
        return mExpanded;
    }

    public synchronized Phase phase() {
        return mPhase;
    }

    public synchronized boolean live() {
        return !terminal() && mPhase != Phase.IDLE;
    }

    /** 标题行（不含 ▲ / ▼）。 */
    public synchronized String title() {
        switch (mPhase) {
            case DONE: return "已完成";
            case FAILED: return "没完成";
            case STOPPED: return "已停止";
            case WAITING: return "等你确认";
            case THINKING: return "正在思考";
            default:
                Row r = running();
                if (r != null) return "正在调用 " + r.label;
                return mRows.isEmpty() ? "" : "正在处理";
        }
    }

    /** 标题行（带箭头：展开 ▲，折叠 ▼）。 */
    public synchronized String header() {
        String t = title();
        return t.length() == 0 ? "" : t + " " + (mExpanded ? "▲" : "▼");
    }

    /** 当前展示的行（副本）；停止时末尾补一行「已停止」。 */
    public synchronized List<String> lines() {
        List<String> out = new ArrayList<String>();
        for (Row r : mRows) out.add(r.icon() + " " + r.text());
        if (mPhase == Phase.STOPPED) out.add("■ 已停止");
        return out;
    }

    public synchronized List<Row> rows() {
        return Collections.unmodifiableList(new ArrayList<Row>(mRows));
    }

    /** 后台通知 / 胶囊用的一句话：「调用中：搜索」/「等你确认」/ 已完成。 */
    public synchronized String statusLine() {
        if (mPhase == Phase.WAITING) return "等你确认";
        Row r = running();
        if (r != null) return "调用中：" + r.label;
        if (live()) return "调用中";
        return title();
    }

    private boolean terminal() {
        return mPhase == Phase.DONE || mPhase == Phase.FAILED || mPhase == Phase.STOPPED;
    }

    private Row last() {
        return mRows.isEmpty() ? null : mRows.get(mRows.size() - 1);
    }

    private Row running() {
        for (int i = mRows.size() - 1; i >= 0; i--) {
            Row r = mRows.get(i);
            if (!r.approval && !r.info && r.state == State.RUNNING) return r;
        }
        return null;
    }

    private Row waiting() {
        for (int i = mRows.size() - 1; i >= 0; i--) {
            Row r = mRows.get(i);
            if (r.approval && r.state == State.WAITING) return r;
        }
        return null;
    }

    // ------------------------------------------------------------------ 文案

    /**
     * 步骤短句 → 工具名：「搜索：二手摩托」→「搜索」；「设闹钟：7:30」→「设闹钟」；没有冒号的整句（最多 10 字）。
     * 与灵动岛同一套短句（IslandStepText），不另造文案。
     */
    public static String labelOf(String stepText) {
        if (stepText == null) return "";
        String s = stepText.trim();
        int k = s.indexOf('：');
        if (k > 0 && k <= 6) s = s.substring(0, k);
        if (s.startsWith("正在")) s = s.substring(2);
        return shorten(s, 10);
    }

    static String shorten(String s) {
        return shorten(s, 24);
    }

    static String shorten(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
