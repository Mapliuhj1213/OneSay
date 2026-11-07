package com.onesay.assistant.v2.island;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [island v3] 灵动岛 / 活动的<b>唯一状态模型</b>（纯 Java，JVM 可测；Android 侧只按返回的 {@link Effect} 执行、按 {@link #drainTimers}
 * 排延时、按 {@link #view} 画）。设计稿：motion/island-v3.html；方案：island-v3-plan.md；S3：。
 *
 * <h3>活动</h3>
 * <ul>
 *   <li><b>前台任务</b>（TASK）：面板里的长任务（AgentLoop），收起后才上岛（与 v1 相同的两层状态 Sheet × Status）；</li>
 *   <li><b>后台任务</b>（AGENT，S3 定时任务到点无人值守地跑）：不经过面板，直接以紧凑运行态上岛，可以有多个；</li>
 *   <li><b>提醒</b>（REMIND，S3 提醒到点）：关键事件展开（完成 / 稍后10分钟），之后收成紧凑，1 小时没处理自动移除；</li>
 *   <li><b>取件码</b>（PICKUP，S1）；<b>待办</b>（TODO）：空闲时只在「今天到期 / 已过期的未完成待办」&gt; 0 时显示一个最小的计数块。</li>
 * </ul>
 * 同时可见最多两个活动（双活动最小态）。超过两个时按 <b>确认 &gt; 运行中 &gt; 提醒 &gt; 取件码</b> 取前两个（{@link #rank}）。
 *
 * <h3>不变量（单测锁定）</h3>
 * <ol>
 *   <li><b>确认卡永远不藏起来</b>：岛模式下待确认立即展开（{@link #CONFIRM_EXPAND_MS}=0），未裁决前点外部 / 上滑 / 横滑暂避都被拒；
 *       不画浮层模式（{@link Mode#NONE}）→ 立即挂回面板（旧行为）；</li>
 *   <li>没有在跑的任务就不能收起；功能关闭或没有浮层（{@link Mode#NONE}）时不收起，宿主走原逻辑；</li>
 *   <li>所有延时回调带序号，过期回调无效；</li>
 *   <li>全屏 / 暂避时只放行关键事件：{@link Kind#ALERT_KEY}、{@link Kind#EXP_CONFIRM}、{@link Kind#EXP_FAIL}、{@link Kind#EXP_REMIND}。</li>
 * </ol>
 */
public final class IslandModel {

    /**
     * 浮层形态：岛（悬浮窗 APPLICATION_OVERLAY）/ 没有浮层（只用面板，不收起）。
     * 2026-10-10（第 6 项）：旧悬浮胶囊 CAPSULE 已删除，island.off / 岛开关关 = NONE。
     */
    public enum Mode { ISLAND, NONE }

    public enum Sheet { IDLE, EXPANDED, COLLAPSED }

    public enum Status { NONE, RUN, WAIT, DONE, FAIL }

    public enum Act { TASK, PICKUP, AGENT, REMIND, TODO, NOTE, NOTICE }

    public enum Kind {
        HIDDEN, COMPACT_TASK, COMPACT_PICKUP, COMPACT_REMIND, COMPACT_TODO, PULSE_STEP, ALERT_KEY,
        EXP_RUN, EXP_CONFIRM, EXP_FAIL, EXP_PICKUP, EXP_REMIND, EXP_TODO, MINIMAL_DUAL,
        /** ★ R10 记一记：紧凑「记录中」流光 / 展开结果卡（带动作）。 */
        COMPACT_NOTE, EXP_NOTE,
        /** ★ 消息提醒：紧凑（队首一条 +N）/ 展开（列出全部，可忽略 / 点动作）。 */
        COMPACT_NOTICE, EXP_NOTICE,
        /** ★ arch/island-progress（C 路）：ask_user 中途问人 —— 问题 + 2–4 个选项芯片（关键事件，全屏也显示，不能随手收起）。 */
        EXP_ASK
    }

    public enum CollapseReason { AUTO, OUTSIDE_TAP, USER_BUTTON, SWIPE_UP }

    /** 宿主要执行的副作用（按列表顺序）。带目标的效果用 {@link #lastTarget()} 取目标 id。 */
    public enum Effect {
        DETACH_SHEET, ATTACH_SHEET, CANCEL_TASK, END_SESSION, RETRY_TASK,
        RESOLVE_CONFIRM_ALLOW, RESOLVE_CONFIRM_DENY,
        COPY_PICKUP, DISMISS_PICKUP,
        /** 点按岛：打开 App 对应页面（取代 v1 的「点按展开」）。 */
        OPEN_APP_TASK, OPEN_APP_PICKUP, OPEN_APP_TODO,
        /** 后台任务（目标 = lastTarget）。 */
        BG_CONFIRM_ALLOW, BG_CONFIRM_DENY, BG_STOP,
        /** 提醒（目标 = lastTarget）。 */
        REMIND_DONE, REMIND_SNOOZE,
        /** 勾销待办（目标 = lastTarget = 待办 id）。 */
        TODO_CHECK,
        /** ★ R10 记一记结果卡：点了第 n 个动作（lastTarget = 「id#n」）/ 点卡片打开详情（lastTarget = id）。 */
        NOTE_ACTION, OPEN_APP_NOTE,
        /** ★ r2/fast-screen 执行模式岛上的按钮（lastTarget = 任务 id，{@link #lastExecAction()} = 补充 / 接管 / 继续）。 */
        EXEC_ACTION,
        /** ★ 消息提醒：忽略一条（lastTarget = 提醒 id）/ 全部忽略 / 点了动作（lastTarget = 「id#n」）。 */
        NOTICE_DISMISS, NOTICE_DISMISS_ALL, NOTICE_ACTION,
        /** ★ arch/island-progress：ask_user 芯片被点（lastTarget = 问题 id，{@link #lastAskChoice()} = 选项文字）/ 超时收起（lastTarget = 问题 id）。 */
        ASK_ANSWER, ASK_TIMEOUT,
        /** ★ feat/suggest 主动建议卡（渲染复用 NOTE 结果卡）：点了第 n 个按钮（lastTarget = 「id#n」）/ 到时收起 / 上滑或横滑收起（lastTarget = id）。 */
        SUGGEST_ACTION, SUGGEST_TIMEOUT, SUGGEST_SWIPED,
        RENDER
    }

    // ------------------------------------------------------------------ 时长（ms）

    /**
     * 任务开始多久后自动收进岛。★ R14（diag C9 / P1-5）：2.5 s → 6 s —— 6 s 内就做完的短任务（读一封邮件约 4–5 s）不再
     * 「刚飞进岛又流回卡片」（去程 + 回程两段形变约 1.6–2 s，慢帧都出在这里）；真正的长任务照旧收进岛，面板→岛的手感不变。
     */
    public static final long AUTO_COLLAPSE_MS = 6000L;
    public static final long STEP_PULSE_MS = 2400L;
    public static final long KEY_ALERT_MS = 4000L;
    public static final long CONFIRM_EXPAND_MS = 0L;
    public static final long DONE_HIDE_MS = 3000L;
    public static final long FAIL_ALERT_MS = 6000L;
    public static final long FAIL_LINGER_MS = 5L * 60L * 1000L;
    public static final long COPIED_MS = 1500L;
    public static final long TUCK_MS = 60000L;
    public static final long MAX_ACTIVITY_MS = 8L * 3600L * 1000L;
    /** 提醒到点后展开停留多久（自主决定：比「接单」4 s 长，留时间点按钮），之后收成紧凑。 */
    public static final long REMIND_ALERT_MS = 8000L;
    /** 提醒收成紧凑后最多挂多久（spec：1 小时）。 */
    public static final long REMIND_LINGER_MS = 3600L * 1000L;
    /** 后台任务确认卡最长等多久（spec：10 分钟无人回应安全中止）。宿主等待时用这个值。 */
    public static final long BG_CONFIRM_TIMEOUT_MS = 10L * 60L * 1000L;
    public static final int MAX_STEPS_SHOWN = 4;
    /** ★ arch/island-progress：ask_user 默认等多久（用户默认决策：60 s，超时收起芯片，等待方拿到 no_answer）。 */
    public static final long ASK_TIMEOUT_MS = 60000L;
    public static final int ASK_MIN_OPTIONS = 2, ASK_MAX_OPTIONS = 4;
    /** ★ arch/island-progress：「懒上岛」的前台任务第几次工具调用起算多步（1 次 = 单次接口调用，不上岛）。 */
    public static final int LAZY_PROMOTE_STEPS = 2;
    /** 展开卡里最多列几个子目标（当前项附近的窗口）。 */
    public static final int MAX_SUBGOALS_SHOWN = 4;
    public static final int MAX_PICKUPS_SHOWN = 3;
    public static final int MAX_TODOS_SHOWN = 5;
    /** ★ R10 记一记结果卡停留（D5：6 s）。 */
    public static final long NOTE_CARD_MS = 6000L;
    /** ★ R11：正文长（邮件摘要）且有动作 → 结果卡多停一会儿（9 s），留时间读完再点「提醒」。 */
    public static final long NOTE_CARD_LONG_MS = 9000L;

    public static long noteCardMs(String detail, int actions) {
        return detail != null && detail.length() > 40 && actions >= 2 ? NOTE_CARD_LONG_MS : NOTE_CARD_MS;
    }
    /** 「记录中」最长挂多久（读屏卡住的兜底）。 */
    public static final long NOTE_RUN_MAX_MS = 20000L;
    /** ★ 消息提醒自动展开停留多久，之后收成紧凑「队首 +N」。 */
    public static final long NOTICE_ALERT_MS = 6000L;
    /** BUG-I2b：面板关闭时岛仍处在全屏修饰中，等「退出全屏」的宽限期。 */
    public static final long NOTICE_YIELD_FS_GRACE_MS = 3000L;
    /** 展开卡最多列几条（多出的显示「还有 N 条」）。 */
    public static final int MAX_NOTICES_SHOWN = 4;

    /** ★ 岛上的一条消息提醒（{@code notice.IslandNotice} 的展示副本）。 */
    /**：紧凑提醒要显示的文字 = 标题 + 计时（有的话；纯函数）。 */
    public static String compactNoticeText(Snapshot s, long wallNow) {
        String t = s == null || s.title == null ? "" : s.title;
        NoticeRow r = s == null || s.notices == null || s.notices.isEmpty() ? null : s.notices.get(0);
        if (r == null || r.chronoBase <= 0) return t;
        return t + " " + r.chronoText(wallNow);
    }

    /**：当前画面里有走动的计时（紧凑队首或展开卡任一行）→ 岛每秒重画一次。 */
    public static boolean noticeChronoShown(Snapshot s) {
        if (s == null || s.notices == null || s.notices.isEmpty()) return false;
        if (s.kind == Kind.COMPACT_NOTICE) return s.notices.get(0).chronoBase > 0;
        if (s.kind == Kind.EXP_NOTICE) for (NoticeRow r : s.notices) if (r.chronoBase > 0) return true;
        return false;
    }

    public static final class NoticeRow {
        public final String id, title, summary, meta;
        public final boolean high;
        public final List<String> actions;
        /**：&gt; 0 时标题后跟一个自己走的时间（countdown = false 正计时 / true 倒计时到这个时刻），岛每秒自己重画，不用每秒重发。 */
        public final long chronoBase;
        public final boolean countdown;
        /**：点的颜色提示（mic / cam / call / ok / warn；空 = 默认，high = 红）。 */
        public final String tone;
        /**：第 i 个动作点完<b>卡片不收、这一行不移除</b>（通话「静音 / 免提」）——位掩码 1 &lt;&lt; i。 */
        public final int keepOpenMask;
        /** ★ feat/rain-minutely：展开时画在摘要下面的小柱状条（每个字符 '0'–'8' 一根；空 = 不画）。 */
        public String bars = "";

        public NoticeRow(String id, String title, String summary, String meta, boolean high, List<String> actions) {
            this(id, title, summary, meta, high, actions, 0L, false, "", 0);
        }

        public NoticeRow(String id, String title, String summary, String meta, boolean high, List<String> actions,
                         long chronoBase, boolean countdown, String tone, int keepOpenMask) {
            this.id = id;
            this.title = title == null ? "" : title;
            this.summary = summary == null ? "" : summary;
            this.meta = meta == null ? "" : meta;
            this.high = high;
            List<String> a = new ArrayList<String>();
            if (actions != null) for (String x : actions) if (x != null && a.size() < 3) a.add(x);
            this.actions = Collections.unmodifiableList(a);
            this.chronoBase = chronoBase > 0 ? chronoBase : 0L;
            this.countdown = countdown && this.chronoBase > 0;
            this.tone = tone == null ? "" : tone;
            this.keepOpenMask = keepOpenMask;
        }

        /** 点的颜色类别（纯函数，岛的绘制按它取色）：mic / cam（含 call、ok）/ warn / high / default。 */
        public String dotClass() {
            if ("mic".equals(tone)) return "mic";
            if ("cam".equals(tone) || "call".equals(tone) || "ok".equals(tone)) return "cam";
            if ("warn".equals(tone)) return "warn";
            return high ? "high" : "default";
        }

        public boolean keepsOpen(int index) {
            return index >= 0 && index < 31 && (keepOpenMask & (1 << index)) != 0;
        }

        /** 计时文字（纯函数）：正计时 mm:ss / h:mm:ss；倒计时到 0 为止。没有计时 → 空串。 */
        public String chronoText(long nowMs) {
            if (chronoBase <= 0) return "";
            long ms = countdown ? chronoBase - nowMs : nowMs - chronoBase;
            if (ms < 0) ms = 0;
            long sec = (ms + (countdown ? 999L : 0L)) / 1000L;
            long h = sec / 3600L, m = (sec % 3600L) / 60L, s = sec % 60L;
            String mm = (m < 10 ? "0" : "") + m, ss = (s < 10 ? "0" : "") + s;
            return h > 0 ? h + ":" + mm + ":" + ss : mm + ":" + ss;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof NoticeRow)) return false;
            NoticeRow r = (NoticeRow) o;
            return id.equals(r.id) && title.equals(r.title) && summary.equals(r.summary) && meta.equals(r.meta) && high == r.high
                    && actions.equals(r.actions) && chronoBase == r.chronoBase && countdown == r.countdown && tone.equals(r.tone)
                    && keepOpenMask == r.keepOpenMask && String.valueOf(bars).equals(String.valueOf(r.bars));
        }

        @Override public int hashCode() {
            return id.hashCode() * 31 + title.hashCode() * 7 + summary.hashCode() + actions.hashCode();
        }
    }

    // ------------------------------------------------------------------ 数据

    public static final class Pickup {
        public final String id, kind, code, station, source;
        public final long receivedAt, expiresAt;

        public Pickup(String id, String kind, String code, String station, String source, long receivedAt, long expiresAt) {
            this.id = id;
            this.kind = kind;
            this.code = code;
            this.station = station;
            this.source = source;
            this.receivedAt = receivedAt;
            this.expiresAt = expiresAt;
        }
    }

    /** 岛上展开卡里的一条未完成待办。 */
    public static final class TodoRow {
        public final String id, text;
        public final long dueAt;

        public TodoRow(String id, String text, long dueAt) {
            this.id = id;
            this.text = text;
            this.dueAt = dueAt;
        }
    }

    /** 一条延时请求：宿主 {@code postDelayed(delayMs)} 后调 {@link #timerDue(String, int, String)}。 */
    public static final class Timer {
        public final String name;
        public final long delayMs;
        public final int seq;
        public final String target;

        Timer(String name, long delayMs, int seq, String target) {
            this.name = name;
            this.delayMs = delayMs;
            this.seq = seq;
            this.target = target;
        }

        @Override
        public String toString() {
            return name + "(" + delayMs + "ms" + (target != null ? "," + target : "") + ")";
        }
    }

    /** 后台活动：S3 后台任务或提醒。 */
    static final class Bg {
        final String id;
        final Act act;
        String title = "";
        Status status = Status.RUN;
        int stepNo;
        String step;
        String confirmTitle, confirmDetail, failReason;
        long startedAt, endedAt, at;
        int lateMin;
        int seq;
        String noteDetail, noteMeta;
        List<String> noteActions = Collections.emptyList();
        /** ★ feat/suggest：这张 NOTE 卡是主动建议卡（只在展开时可见；thumb = 左侧缩略图的本地路径，可空）。 */
        boolean suggest;
        String thumb;
        /** ★ r2/fast-screen 执行模式：岛取代小窗（红点 + 被操作 App 图标 + 步骤短词 + 进度环；展开 = 补充 / 接管 / 停止）。 */
        boolean exec, paused;
        String iconPkg;
        final long order;

        Bg(String id, Act act, long order) {
            this.id = id;
            this.act = act;
            this.order = order;
        }
    }

    /** 某一时刻岛上该画的全部内容（不可变）。 */
    public static final class Snapshot {
        public final Kind kind;
        public final Mode mode;
        /** 主活动是什么；key = 主活动的键（"task" / "pickup" / "todo" / 后台活动 id）。 */
        public final Act act;
        public final String key;
        public final boolean dual;
        public final Act bubble;
        public final String bubbleKey;
        public final Status bubbleStatus;
        public final String bubbleText;
        public final Status status;
        public final String title;
        public final long startedAt, endedAt;
        public final int stepNo;
        public final List<String> steps;
        public final String failReason, confirmTitle, confirmDetail;
        public final Pickup pickup;
        public final int pickupIndex, pickupCount;
        public final boolean copied;
        /** 轻提醒 / 关键提醒的文字。 */
        public final String pulseText, alertText;
        /** 提醒：计划时间（ms）、晚了几分钟（&gt;10 才显示）。 */
        public final long remindAt;
        public final int lateMin;
        public final List<TodoRow> todos;
        public final int todoCount;
        public final boolean fullscreen, tucked;
        /** ★ R10 记一记结果卡：正文 / 抬头（「记一记 · 取件码 · 来自 短信」）/ 动作按钮文字（≤ 3）。 */
        public String noteDetail, noteMeta;
        public List<String> noteActions = Collections.emptyList();
        /** ★ feat/suggest：主动建议卡 + 左侧缩略图（本地路径，宿主预解码进 IslandView 缓存后才画）。 */
        public boolean suggest;
        public String noteThumb;
        /** ★ r2/fast-screen 执行模式：岛取代小窗；paused = 用户接手 / 卡住暂停中；iconPkg = 正在操作的 App。 */
        public boolean exec, paused;
        public String iconPkg;
        /** ★ 消息提醒：队列（队首在前）/ 总数 / 展开卡里当前选中的那条（显示它的动作）。 */
        public List<NoticeRow> notices = Collections.emptyList();
        public int noticeCount;
        public String noticeFocus;
        /**
         * ★ arch/island-progress（C 路）：子目标进度。只在「GUI 操作中」或「子目标 ≥ 2」时 {@code progressVisible}；
         * {@code subgoals} 只在 ≥ 2 个时非空；{@code progressText} =「第 i/n 项」（纯 GUI 没有账本时为 null，只画转动的环）。
         * 工具调用次数（{@link #stepNo}）不再显示在岛上。
         */
        public List<Subgoal> subgoals = Collections.emptyList();
        public boolean progressVisible, progressFailed;
        public int progressIndex, progressTotal;
        public String progressText, progressCurrent;
        /** ★ arch/island-progress：ask_user（{@link Kind#EXP_ASK}）的问题 id / 问题 / 2–4 个选项 / 超时（ms）。 */
        public String askId, askQuestion;
        public List<String> askOptions = Collections.emptyList();
        /** ★ 11-04 午 进度反馈：前台任务正在等大模型 / 当前阶段开始时刻（{@link ProgressFeedback}）。 */
        public boolean thinking;
        public long phaseAt;
        public long askTimeoutMs;

        Snapshot(Kind kind, Mode mode, Act act, String key, boolean dual, Act bubble, String bubbleKey, Status bubbleStatus,
                 String bubbleText, Status status, String title, long startedAt, long endedAt, int stepNo, List<String> steps,
                 String failReason, String confirmTitle, String confirmDetail, Pickup pickup, int pickupIndex, int pickupCount,
                 boolean copied, String pulseText, String alertText, long remindAt, int lateMin, List<TodoRow> todos,
                 int todoCount, boolean fullscreen, boolean tucked) {
            this.kind = kind;
            this.mode = mode;
            this.act = act;
            this.key = key;
            this.dual = dual;
            this.bubble = bubble;
            this.bubbleKey = bubbleKey;
            this.bubbleStatus = bubbleStatus;
            this.bubbleText = bubbleText;
            this.status = status;
            this.title = title;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.stepNo = stepNo;
            this.steps = steps;
            this.failReason = failReason;
            this.confirmTitle = confirmTitle;
            this.confirmDetail = confirmDetail;
            this.pickup = pickup;
            this.pickupIndex = pickupIndex;
            this.pickupCount = pickupCount;
            this.copied = copied;
            this.pulseText = pulseText;
            this.alertText = alertText;
            this.remindAt = remindAt;
            this.lateMin = lateMin;
            this.todos = todos;
            this.todoCount = todoCount;
            this.fullscreen = fullscreen;
            this.tucked = tucked;
        }

        public long elapsed(long now) {
            if (startedAt <= 0) return 0L;
            long end = endedAt > 0 ? endedAt : now;
            return Math.max(0L, end - startedAt);
        }

        public boolean isExpanded() {
            return kind == Kind.EXP_RUN || kind == Kind.EXP_CONFIRM || kind == Kind.EXP_FAIL || kind == Kind.EXP_PICKUP
                    || kind == Kind.EXP_REMIND || kind == Kind.EXP_TODO || kind == Kind.ALERT_KEY || kind == Kind.EXP_NOTE
                    || kind == Kind.EXP_NOTICE || kind == Kind.EXP_ASK;
        }

        @Override
        public String toString() {
            return kind + (act != null ? "(" + act + ")" : "") + (dual ? "+" + bubble : "") + " status=" + status
                    + (pickup != null ? " pickup=" + (pickupIndex + 1) + "/" + pickupCount : "")
                    + (lateMin > 0 ? " late=" + lateMin : "") + (todoCount > 0 ? " todo=" + todoCount : "") + (noticeCount > 0 ? " notice=" + noticeCount : "")
                    + (fullscreen ? " fullscreen" : "") + (tucked ? " tucked" : "");
        }
    }

    // ------------------------------------------------------------------ 状态

    public static final String K_TASK = "task", K_PICKUP = "pickup", K_TODO = "todo", K_NOTICE = "notice";

    private Mode mMode = Mode.NONE;
    private Sheet mSheet = Sheet.IDLE;
    private Status mStatus = Status.NONE;
    private boolean mRunning, mConfirmPending, mEnabled = true, mAutoCollapse = true;
    private int mTaskSeq, mPulseSeq, mKeySeq, mTuckSeq, mCopySeq, mBgSeq;
    private long mStartedAt, mEndedAt;
    private String mTitle = "";
    private final List<String> mSteps = new ArrayList<String>();
    private int mStepNo;
    /** ★ 11-04 午 进度反馈：正在等大模型（思考中）/ 当前阶段（思考或这一步）开始的时刻。 */
    private boolean mThinking;
    private long mPhaseAt;
    private String mFailReason, mConfirmTitle, mConfirmDetail;
    private boolean mPulse;
    // ★ arch/island-progress（C 路）
    /** 前台任务是否有「上岛」资格：分诊判多步 / 混合 → 一开始就有；单步 → 懒上岛（GUI 操作 / 子目标 ≥ 2 / 第 2 次工具调用才有）。 */
    private boolean mIslandWorthy = true;
    /** 懒上岛任务的自动收起时间已到但还没资格 → 欠一次收起，拿到资格时补排。 */
    private boolean mCollapseOwed;
    private List<Subgoal> mSubgoals = Collections.emptyList();
    private String mAskId, mAskQuestion, mLastAskChoice;
    private List<String> mAskOptions = Collections.emptyList();
    private long mAskTimeoutMs = ASK_TIMEOUT_MS;
    private int mAskSeq;
    private String mKeyText;

    private final List<Pickup> mPickups = new ArrayList<Pickup>();
    private String mPickupId, mCopiedId;

    private final Map<String, Bg> mBgs = new LinkedHashMap<String, Bg>();
    private long mOrder;

    private final List<TodoRow> mTodos = new ArrayList<TodoRow>();
    private int mTodoToday;
    private boolean mTodoChip = true;

    // ★ 消息提醒
    private final List<NoticeRow> mNotices = new ArrayList<NoticeRow>();
    private String mNoticeFocus;
    private int mNoticeSeq;
    /** 当前展开的提醒卡是「自动弹出」的（到时自动收起）；用户自己点开的不自动收。 */
    private boolean mNoticeAlerting;
    /** 自动弹出的是高优先级（全屏时也放行，算关键事件）。 */
    private boolean mNoticeAlertHigh;
    /** 面板（全屏回答卡 / 聆听卡）此刻在屏幕上——宿主在面板挂上 / 摘下时告诉模型（{@link #panelShown}），投递提醒前也会再取样一次。 */
    private boolean mPanelShowing;
    /** BUG-I2：提醒展开中面板出现 → 提醒让位收回队列；面板关闭后要不要再展开一次（及当时是不是高优先级）。 */
    private boolean mNoticeYield, mNoticeYieldHigh;
    /**
     * BUG-I2b：面板关闭那一刻岛还在「全屏」修饰里（LC-5 全屏回答卡会让全屏检测判成全屏，面板摘掉后 insets / 可见性回调要稍晚
     * 才报「退出全屏」）→ 欠账先留着，{@link #NOTICE_YIELD_FS_GRACE_MS} 内退出全屏就补展开；过了宽限期还在全屏 → 作废（提醒留在队列）。
     */
    private boolean mNoticeYieldFs;
    private int mNoticeYieldFsSeq;

    /** 当前展开的活动键（null = 没展开）。 */
    private String mExp;
    private boolean mFullscreen, mTucked;
    private String mLastTarget;

    private final List<Timer> mTimers = new ArrayList<Timer>();

    private static final List<Effect> NONE = Collections.emptyList();

    // ------------------------------------------------------------------ 查询

    public synchronized Mode mode() { return mMode; }
    public synchronized Sheet sheet() { return mSheet; }
    public synchronized Status status() { return mStatus; }
    public synchronized boolean isRunning() { return mRunning; }
    public synchronized boolean isConfirmPending() { return mConfirmPending; }
    public synchronized boolean isCollapsed() { return mSheet == Sheet.COLLAPSED; }
    public synchronized int taskSeq() { return mTaskSeq; }
    public synchronized String title() { return mTitle; }
    public synchronized int stepNo() { return mStepNo; }
    public synchronized long startedAt() { return mStartedAt; }
    public synchronized boolean isFullscreen() { return mFullscreen; }
    public synchronized boolean isTucked() { return mTucked; }
    public synchronized String expandedKey() { return mExp; }
    /** 最近一个带目标的效果（BG_* / REMIND_* / TODO_CHECK）的目标 id。 */
    public synchronized String lastTarget() { return mLastTarget; }
    public synchronized int bgCount() { return mBgs.size(); }
    public synchronized boolean hasBg(String id) { return mBgs.containsKey(id); }

    public synchronized Status bgStatus(String id) {
        Bg b = mBgs.get(id);
        return b == null ? Status.NONE : b.status;
    }

    public synchronized String currentPickupId() {
        Pickup p = currentPickup();
        return p == null ? null : p.id;
    }

    /** 取走本次调用排下的延时请求（宿主在执行完效果后调用）。 */
    public synchronized List<Timer> drainTimers() {
        if (mTimers.isEmpty()) return Collections.emptyList();
        List<Timer> l = new ArrayList<Timer>(mTimers);
        mTimers.clear();
        return l;
    }

    // ------------------------------------------------------------------ 配置

    public synchronized void configure(boolean enabled, boolean autoCollapse) {
        configure(enabled, autoCollapse, AUTO_COLLAPSE_MS);
    }

    /** 任务开始到自动收进岛的延时（ms）。★ lane C：新唤醒界面 = WindowFlowPolicy.COLLAPSE_DELAY_NEW_MS（「立刻」）；旧界面 6 s。 */
    private long mCollapseDelayMs = AUTO_COLLAPSE_MS;

    public synchronized void configure(boolean enabled, boolean autoCollapse, long collapseDelayMs) {
        mEnabled = enabled;
        mAutoCollapse = autoCollapse;
        mCollapseDelayMs = Math.max(0L, collapseDelayMs);
    }

    public synchronized long collapseDelayMs() {
        return mCollapseDelayMs;
    }

    /** 空闲待办计数块开关（bixby_todo.chip）。 */
    public synchronized List<Effect> setTodoChipEnabled(boolean on) {
        if (mTodoChip == on) return NONE;
        mTodoChip = on;
        return list(Effect.RENDER);
    }

    public synchronized List<Effect> setMode(Mode m) {
        if (m == null || m == mMode) return NONE;
        mMode = m;
        List<Effect> out = new ArrayList<Effect>();
        if (m != Mode.ISLAND) {
            if (K_TASK.equals(mExp) || K_PICKUP.equals(mExp) || K_TODO.equals(mExp) || K_NOTICE.equals(mExp)) mExp = null;
        }
        if (mSheet == Sheet.COLLAPSED) {
            boolean needSheet = m == Mode.NONE || (mConfirmPending && m != Mode.ISLAND);
            if (needSheet) {
                if (mRunning) {
                    mSheet = Sheet.EXPANDED;
                    if (mStatus == Status.WAIT) mStatus = Status.RUN;
                    out.add(Effect.ATTACH_SHEET);
                } else {
                    clearTask();
                }
            } else if (mConfirmPending && m == Mode.ISLAND) {
                mStatus = Status.WAIT;
                mExp = K_TASK;
            }
        }
        out.add(0, Effect.RENDER);
        return out;
    }

    /**
     * 选择浮层形态（纯函数，v3 不再看无障碍服务）：有悬浮窗权限、岛开关开且没有 island.off → 岛；其余 → 不画浮层，只用面板
     * （2026-10-10 起没有胶囊回落）。
     */
    public static Mode chooseMode(boolean islandPref, boolean islandKilled, boolean overlayAllowed) {
        return overlayAllowed && islandPref && !islandKilled ? Mode.ISLAND : Mode.NONE;
    }

    // ------------------------------------------------------------------ 前台任务

    public synchronized List<Effect> taskStarted(long nowMs, String title) {
        return taskStarted(nowMs, title, true);
    }

    /**
     * ★ arch/island-progress：{@code islandNow} = 分诊判定这条任务一开始就该上岛（多步 / 混合）；false = 懒上岛：
     * 单次接口调用就做完的请求留在面板上，不收进岛（见 {@link #promote}）。
     */
    public synchronized List<Effect> taskStarted(long nowMs, String title, boolean islandNow) {
        mIslandWorthy = islandNow;
        mCollapseOwed = false;
        mSubgoals = Collections.emptyList();
        clearAsk();
        List<Effect> out = new ArrayList<Effect>();
        if (mSheet == Sheet.COLLAPSED || mStatus != Status.NONE) out.add(Effect.RENDER);
        mTaskSeq++;
        mRunning = true;
        mConfirmPending = false;
        mStartedAt = nowMs;
        mEndedAt = 0L;
        mTitle = title == null ? "" : title;
        mSteps.clear();
        mStepNo = 0;
        mThinking = false;
        mPhaseAt = nowMs;
        mFailReason = null;
        mConfirmTitle = null;
        mConfirmDetail = null;
        mPulse = false;
        mKeyText = null;
        mStatus = Status.RUN;
        mSheet = Sheet.EXPANDED;
        if (K_TASK.equals(mExp)) mExp = null;
        if (mEnabled && mAutoCollapse && mMode != Mode.NONE) timer("autoCollapse", mCollapseDelayMs, mTaskSeq, null);
        return out;
    }

    /** 新的一步。已收起（岛）→ 步骤轻提醒 {@link #STEP_PULSE_MS}，连续步骤重置计时。 */
    public synchronized List<Effect> step(String text) {
        if (!mRunning) return NONE;
        mStepNo++;
        mThinking = false;
        mPhaseAt = System.currentTimeMillis();
        mSteps.add(stripDoing(text));
        while (mSteps.size() > 32) mSteps.remove(0);
        if (mStepNo >= LAZY_PROMOTE_STEPS) promote();   // ★ arch/island-progress：第 2 次工具调用 = 不是单次接口调用
        if (mSheet != Sheet.COLLAPSED) return NONE;
        if (mMode == Mode.ISLAND && mStatus == Status.RUN && mExp == null) {
            mPulse = true;
            timer("pulse", STEP_PULSE_MS, ++mPulseSeq, null);
        }
        return list(Effect.RENDER);
    }

    /**
     * ★ 11-04 午 进度反馈：进入 / 离开「思考中」（等大模型）。进入时记阶段起点（超过 8 s 没新步骤 → 岛上补一句真实状态）；
     * 离开不改起点（下一步工具调用会重新记）。状态没变 → 不重画。
     */
    public synchronized List<Effect> thinking(boolean on) {
        if (!mRunning || mThinking == on) return NONE;
        mThinking = on;
        if (on) mPhaseAt = System.currentTimeMillis();
        return mSheet == Sheet.COLLAPSED ? list(Effect.RENDER) : NONE;
    }

    public synchronized boolean isThinking() { return mThinking; }

    /**
     * ★ arch/voice-sync：语音字幕（应答「收到，我来弄」/ 催一次 / 提问提示）—— 作为当前一行显示在任务行上，
     * <b>不算一步</b>（不加步数、不触发懒上岛），之后真正的一步会顶掉它。
     */
    public synchronized List<Effect> caption(String text) {
        if (!mRunning || text == null || text.trim().length() == 0) return NONE;
        // ★ 11-04 ①（用户：岛上只有套话）：已经有真实步骤 → 套话不顶掉它（语音照播）；套话只在还没有任何步骤时兜底
        if (mStepNo > 0) return NONE;
        mSteps.add(text.trim());
        while (mSteps.size() > 32) mSteps.remove(0);
        return mSheet == Sheet.COLLAPSED ? list(Effect.RENDER) : NONE;
    }

    /** ★ 11-04 ①：改写当前一行（失败如实 / 整理结论 / 卡住），不加步数、不触发懒上岛。 */
    public synchronized List<Effect> stepNote(String text) {
        if (!mRunning || text == null || text.trim().length() == 0) return NONE;
        mSteps.add(stripDoing(text));
        while (mSteps.size() > 32) mSteps.remove(0);
        return mSheet == Sheet.COLLAPSED ? list(Effect.RENDER) : NONE;
    }

    /** 关键事件（「已接单」这类）：展开 {@link #KEY_ALERT_MS}，清掉暂避。只在任务收起在岛上时有效。 */
    public synchronized List<Effect> keyUpdate(String text) {
        if (mMode != Mode.ISLAND || mSheet != Sheet.COLLAPSED || mStatus == Status.NONE) return NONE;
        mKeyText = text == null || text.trim().length() == 0 ? "有新进展" : text.trim();
        mPulse = false;
        clearTuck();
        timer("key", KEY_ALERT_MS, ++mKeySeq, null);
        return list(Effect.RENDER);
    }

    public synchronized List<Effect> autoCollapseDue(int seq) {
        if (seq != mTaskSeq) return NONE;
        if (!mIslandWorthy) {   // ★ arch/island-progress：懒上岛 → 先不收，记一笔，拿到资格时补排
            if (mRunning && mSheet == Sheet.EXPANDED) mCollapseOwed = true;
            return NONE;
        }
        return requestCollapse(CollapseReason.AUTO);
    }

    /** ★ arch/island-progress：前台任务有没有上岛资格（见 {@link #taskStarted(long, String, boolean)}）。 */
    public synchronized boolean isIslandWorthy() {
        return mIslandWorthy;
    }

    /** ★ arch/island-progress：懒上岛任务拿到资格（GUI 操作开始 / 子目标 ≥ 2 / 第 2 次工具调用）→ 欠着的自动收起补排一次。 */
    private void promote() {
        if (mIslandWorthy) return;
        mIslandWorthy = true;
        if (mCollapseOwed && mRunning && mSheet == Sheet.EXPANDED && mEnabled && mAutoCollapse && mMode != Mode.NONE) {
            timer("autoCollapse", mCollapseDelayMs, mTaskSeq, null);
        }
        mCollapseOwed = false;
    }

    public synchronized List<Effect> requestCollapse(CollapseReason reason) {
        if (!canCollapse()) return NONE;
        if (reason == CollapseReason.AUTO && !mAutoCollapse) return NONE;
        mSheet = Sheet.COLLAPSED;
        if (K_TASK.equals(mExp)) mExp = null;
        timer("maxAge", Math.max(0L, MAX_ACTIVITY_MS), mTaskSeq, null);
        return list(Effect.DETACH_SHEET, Effect.RENDER);
    }

    public synchronized boolean canCollapse() {
        return mEnabled && mMode != Mode.NONE && mRunning && !mConfirmPending && mSheet == Sheet.EXPANDED;
    }

    /** 需要确认：岛 → 立即展开确认卡（不再等 1 s）；无浮层 → 立即挂回面板。面板展开着 → 无动作。 */
    public synchronized List<Effect> confirmRequired(String title, String detail) {
        mConfirmPending = true;
        mConfirmTitle = title;
        mConfirmDetail = detail;
        if (mSheet != Sheet.COLLAPSED) return NONE;
        if (mMode == Mode.ISLAND) {
            mStatus = Status.WAIT;
            mPulse = false;
            mKeyText = null;
            mExp = K_TASK;
            clearTuck();
            return list(Effect.RENDER);
        }
        mSheet = Sheet.EXPANDED;
        return list(Effect.ATTACH_SHEET, Effect.RENDER);
    }

    public synchronized List<Effect> confirmRequired() {
        return confirmRequired(null, null);
    }

    /** 岛上确认卡的按钮：前台任务 → RESOLVE_CONFIRM_*；后台任务 → BG_CONFIRM_*（目标 = lastTarget）。 */
    public synchronized List<Effect> islandConfirm(boolean allow) {
        if (mMode != Mode.ISLAND) return NONE;
        if (K_TASK.equals(mExp) || mExp == null) {
            if (!mConfirmPending || mSheet != Sheet.COLLAPSED) return mExp == null ? bgConfirmAny(allow) : NONE;
            return list(allow ? Effect.RESOLVE_CONFIRM_ALLOW : Effect.RESOLVE_CONFIRM_DENY);
        }
        Bg b = mBgs.get(mExp);
        if (b == null || b.act != Act.AGENT || b.status != Status.WAIT) return NONE;
        mLastTarget = b.id;
        return list(allow ? Effect.BG_CONFIRM_ALLOW : Effect.BG_CONFIRM_DENY);
    }

    private List<Effect> bgConfirmAny(boolean allow) {
        for (Bg b : mBgs.values()) {
            if (b.act == Act.AGENT && b.status == Status.WAIT) {
                mLastTarget = b.id;
                return list(allow ? Effect.BG_CONFIRM_ALLOW : Effect.BG_CONFIRM_DENY);
            }
        }
        return NONE;
    }

    public synchronized List<Effect> confirmResolved() {
        mConfirmPending = false;
        // ★ lane C：「立刻进岛」模式下，确认卡挡住了收起（确认卡在全屏卡片上裁决）→ 裁决后任务还在跑就重新排一次收起
        if (mRunning && mSheet == Sheet.EXPANDED && mEnabled && mAutoCollapse && mMode != Mode.NONE
                && mCollapseDelayMs < AUTO_COLLAPSE_MS) {
            timer("autoCollapse", mCollapseDelayMs, mTaskSeq, null);
        }
        if (mStatus == Status.WAIT && mAskId == null) {
            mStatus = Status.RUN;
            if (K_TASK.equals(mExp)) mExp = null;
            return list(Effect.RENDER);
        }
        return NONE;
    }

    /** 停止键：展开的是后台任务 → BG_STOP；否则前台任务（同 v1）。 */
    public synchronized List<Effect> stop() {
        if (mExp != null && mBgs.containsKey(mExp)) {
            Bg b = mBgs.get(mExp);
            if (b.act == Act.AGENT && (b.status == Status.RUN || b.status == Status.WAIT)) {
                mLastTarget = b.id;
                return list(Effect.BG_STOP);
            }
        }
        if (!mRunning) {
            if (mSheet != Sheet.COLLAPSED) return NONE;
            clearTask();
            return list(Effect.RENDER);
        }
        boolean wasCollapsed = mSheet == Sheet.COLLAPSED;
        clearTask();
        return wasCollapsed ? list(Effect.RENDER, Effect.CANCEL_TASK) : list(Effect.CANCEL_TASK);
    }

    /**
     * 新一次唤醒：<b>前台</b>收起的任务被新指令接管 → 按取消处理（面板只有一个，同 v1）；后台任务（S3）、提醒、取件码不受影响（v3 多任务）。
     */
    public synchronized List<Effect> newWake() {
        if (mSheet != Sheet.COLLAPSED) return NONE;
        boolean running = mRunning;
        clearTask();
        return running ? list(Effect.RENDER, Effect.CANCEL_TASK) : list(Effect.RENDER);
    }

    public synchronized List<Effect> taskEnded(boolean cancelled, boolean navLaunched, boolean finished,
                                               boolean hasResultText, String reason, long nowMs) {
        boolean wasCollapsed = mSheet == Sheet.COLLAPSED;
        boolean wasRunning = mRunning;
        clearAsk();   // ★ arch/island-progress：任务收工，没答的问题作废（宿主同时让等待方拿到 no_answer）
        mRunning = false;
        mConfirmPending = false;
        mPulse = false;
        mKeyText = null;
        if (!wasCollapsed) {
            if (wasRunning) {
                mSheet = Sheet.IDLE;
                mStatus = Status.NONE;
            }
            return NONE;
        }
        mEndedAt = nowMs;
        if (cancelled) {
            clearTask();
            return list(Effect.RENDER);
        }
        if (!finished) {
            mStatus = Status.FAIL;
            mFailReason = reason == null || reason.trim().length() == 0 ? "任务处理失败" : reason.trim();
            if (mMode == Mode.ISLAND) {
                mExp = K_TASK;
                clearTuck();
                timer("failCollapse", FAIL_ALERT_MS, mTaskSeq, null);
                timer("failLinger", FAIL_LINGER_MS, mTaskSeq, null);
            } else if (K_TASK.equals(mExp)) {
                mExp = null;
            }
            return list(Effect.RENDER);
        }
        if (navLaunched || !hasResultText) {
            mStatus = Status.DONE;
            if (K_TASK.equals(mExp)) mExp = null;
            clearTuck();
            timer("doneHide", DONE_HIDE_MS, mTaskSeq, null);
            return list(Effect.RENDER);
        }
        clearTask();
        mSheet = Sheet.IDLE;
        return list(Effect.ATTACH_SHEET, Effect.RENDER);
    }

    /** [R13] 短结果卡在岛上的活动 id 前缀（宿主据此把「查看」接到「流回卡片」，而不是记一记详情）。 */
    public static final String TASK_RESULT_PREFIX = "task-result-";

    /**
     * [R13 D4=A] 收起在岛上的任务带着<b>短</b>结论收工：岛就地展开成结果卡（复用记一记结果卡的版式与计时），
     * 不再把面板挂回来；长结论 / 闹钟卡 / 拉起了界面 / 失败 / 取消仍走 {@link #taskEnded} 原规则。
     * 前置条件不满足时等价于 {@link #taskEnded}（调用方不必先判断）。
     *
     * @param headline 结果卡主句（≤2 行）
     * @param detail   次行（可空）
     * @param action   按钮文字（「查看」= 流回卡片看全文；可空 = 没有按钮）
     */
    public synchronized List<Effect> taskEndedShort(boolean cancelled, boolean navLaunched, boolean finished,
                                                    String headline, String detail, String action, String reason, long nowMs) {
        boolean wasCollapsed = mSheet == Sheet.COLLAPSED;
        boolean ok = wasCollapsed && !cancelled && !navLaunched && finished && mMode == Mode.ISLAND
                && headline != null && headline.trim().length() > 0;
        if (!ok) {
            return taskEnded(cancelled, navLaunched, finished, headline != null && headline.trim().length() > 0, reason, nowMs);
        }
        String title = mTitle;
        mRunning = false;
        mEndedAt = nowMs;
        clearTask();
        mSheet = Sheet.IDLE;
        List<String> acts = new ArrayList<String>();
        if (action != null && action.length() > 0) acts.add(action);
        String meta = title == null || title.trim().length() == 0 ? "已完成" : "已完成 · " + title.trim();
        return noteResult(TASK_RESULT_PREFIX + mTaskSeq, true, headline.trim(), detail, meta, acts, nowMs);
    }

    public synchronized List<Effect> doneHideDue(int seq) {
        if (seq != mTaskSeq || mStatus != Status.DONE) return NONE;
        clearTask();
        return list(Effect.RENDER);
    }

    public synchronized List<Effect> retry() {
        if (mStatus != Status.FAIL || mRunning) return NONE;
        clearTask();
        return list(Effect.RENDER, Effect.RETRY_TASK);
    }

    /** 失败卡「关闭」：展开的是后台失败任务 → 移除它；否则前台（END_SESSION）。 */
    public synchronized List<Effect> closeFailed() {
        if (mExp != null && mBgs.containsKey(mExp) && mBgs.get(mExp).status == Status.FAIL) {
            mBgs.remove(mExp);
            mExp = null;
            return list(Effect.RENDER);
        }
        if (mStatus != Status.FAIL || mRunning) return NONE;
        clearTask();
        return list(Effect.RENDER, Effect.END_SESSION);
    }

    // ------------------------------------------------------------------ 触摸

    /** 点按主块。岛：打开 App 对应页面（不改变视图）；非岛（只在模式刚切走、尚未挂回时出现）：挂回面板。 */
    public synchronized List<Effect> tapMain() {
        if (mMode != Mode.ISLAND) {
            if (!taskVisible() || mStatus == Status.DONE) return NONE;
            if (mStatus == Status.FAIL) {
                clearTask();
                mSheet = Sheet.IDLE;
                return list(Effect.ATTACH_SHEET, Effect.RENDER);
            }
            mSheet = Sheet.EXPANDED;
            return list(Effect.ATTACH_SHEET, Effect.RENDER);
        }
        List<String> keys = visibleKeys();
        if (keys.isEmpty()) return showTodoChip() ? list(Effect.OPEN_APP_TODO) : NONE;
        return tap(keys.get(0));
    }

    /** 点按某个活动块（双活动的副块用这个）。 */
    public synchronized List<Effect> tap(String key) {
        if (key == null || mMode != Mode.ISLAND) return NONE;
        if (K_PICKUP.equals(key)) return list(Effect.OPEN_APP_PICKUP);
        if (K_TODO.equals(key)) return list(Effect.OPEN_APP_TODO);
        if (K_NOTICE.equals(key)) return expand(K_NOTICE);   // ★：点提醒 = 展开列出全部
        Bg b = mBgs.get(key);
        if (b != null && b.exec && b.act == Act.AGENT) return expand(key);   // ★ 执行模式：点岛 = 展开「补充 / 接管 / 停止」
        if (b != null && b.act == Act.REMIND) {
            mLastTarget = b.id;
            return list(Effect.OPEN_APP_TODO);
        }
        if (b != null && b.act == Act.NOTE) {
            mLastTarget = b.id;
            return list(Effect.OPEN_APP_NOTE);
        }
        mLastTarget = b != null ? b.id : null;
        return list(Effect.OPEN_APP_TASK);
    }

    /** 长按 450ms：展开主块（空闲时的待办计数块 → 待办列表）。 */
    public synchronized List<Effect> longPress() {
        if (mMode != Mode.ISLAND) return NONE;
        List<String> keys = visibleKeys();
        if (keys.isEmpty()) return showTodoChip() ? expand(K_TODO) : NONE;
        return expand(keys.get(0));
    }

    public synchronized List<Effect> longPress(String key) {
        if (mMode != Mode.ISLAND) return NONE;
        return expand(key);
    }

    private List<Effect> expand(String key) {
        if (key == null) return NONE;
        if (mExp != null && !mExp.equals(key) && isBlockingExpanded()) return NONE;
        if (K_TASK.equals(key)) {
            if (!taskVisible() || mStatus == Status.DONE) return NONE;
        } else if (K_PICKUP.equals(key)) {
            if (mPickups.isEmpty()) return NONE;
        } else if (K_TODO.equals(key)) {
            if (!mTodoChip && mTodos.isEmpty()) return NONE;
        } else if (K_NOTICE.equals(key)) {
            if (mNotices.isEmpty()) return NONE;
            mNoticeAlerting = false;   // 用户自己点开：不自动收
            mNoticeAlertHigh = false;
        } else {
            Bg b = mBgs.get(key);
            if (b == null || (b.status == Status.DONE && b.act != Act.NOTE)) return NONE;
        }
        mExp = key;
        mPulse = false;
        clearTuck();
        return list(Effect.RENDER);
    }

    /** 展开卡收起（上滑 / 点岛外）。确认未决的确认卡不收。 */
    public synchronized List<Effect> collapseExpanded(CollapseReason reason) {
        if (mKeyText != null && mExp == null) {
            mKeyText = null;
            return list(Effect.RENDER);
        }
        if (mExp == null) return NONE;
        if (isBlockingExpanded()) return NONE;
        Bg sg = mBgs.get(mExp);
        if (sg != null && sg.suggest) {   // ★ feat/suggest：上滑 / 点岛外收起建议卡 = 忽略
            mBgs.remove(sg.id);
            mExp = null;
            mLastTarget = sg.id;
            return list(Effect.SUGGEST_SWIPED, Effect.RENDER);
        }
        if (K_NOTICE.equals(mExp)) {
            mNoticeAlerting = false;
            mNoticeAlertHigh = false;
        }
        mExp = null;
        return list(Effect.RENDER);
    }

    /** 横滑：暂避 {@link #TUCK_MS}。确认未决时拒绝。 */
    public synchronized List<Effect> tuck() {
        if (mMode != Mode.ISLAND || mTucked || anyWait()) return NONE;
        if (mExp != null && isBlockingExpanded()) return NONE;
        Bg sg = mExp == null ? null : mBgs.get(mExp);
        if (sg != null && sg.suggest) {   // ★ feat/suggest：横滑建议卡 = 忽略（不进入暂避）
            mBgs.remove(sg.id);
            mExp = null;
            mLastTarget = sg.id;
            return list(Effect.SUGGEST_SWIPED, Effect.RENDER);
        }
        mTucked = true;
        mExp = null;
        mPulse = false;
        timer("tuck", TUCK_MS, ++mTuckSeq, null);
        return list(Effect.RENDER);
    }

    /** 全屏修饰（状态栏隐藏）：只放行关键事件。进入全屏时，普通展开卡先收起。 */
    public synchronized List<Effect> setFullscreen(boolean fs) {
        if (mFullscreen == fs) return NONE;
        mFullscreen = fs;
        if (fs && mExp != null && !isBlockingExpanded() && !isAlertExpanded() && !isUserInitiated(mExp)) mExp = null;
        if (fs) mPulse = false;
        if (!fs && mNoticeYieldFs && !mPanelShowing) resumeNoticeYield();   // BUG-I2b：面板关闭后紧接着退出全屏 → 补展开
        return list(Effect.RENDER);
    }

    // ------------------------------------------------------------------ ★ arch/island-progress（C 路）：子目标进度 + ask_user

    /**
     * 任务账本（全量替换）。子目标 ≥ 2 → 前台任务上岛、岛上显示「第 i/n 项」+ 当前子目标；
     * 当前项换了且任务收在岛上 → 轻提醒一次（{@link #STEP_PULSE_MS}）。没有在跑的前台任务时忽略。
     */
    public synchronized List<Effect> subgoals(List<Subgoal> goals) {
        if (!mRunning) return NONE;
        List<Subgoal> next = Subgoal.sanitize(goals);
        if (next.equals(mSubgoals)) return NONE;
        Subgoal.Progress before = Subgoal.progress(mSubgoals, false);
        mSubgoals = next;
        if (next.size() >= 2) promote();
        if (mSheet != Sheet.COLLAPSED) return NONE;
        Subgoal.Progress after = Subgoal.progress(next, false);
        if (after.total >= 2 && (after.index != before.index || after.total != before.total)
                && mMode == Mode.ISLAND && mStatus == Status.RUN && mExp == null) {
            mPulse = true;
            timer("pulse", STEP_PULSE_MS, ++mPulseSeq, null);
        }
        return list(Effect.RENDER);
    }

    public synchronized List<Subgoal> subgoals() {
        return mSubgoals;
    }

    /** 有没有 GUI 执行模式（phone.operate）的活动在跑（含暂停）。 */
    public synchronized boolean anyGuiRunning() {
        for (Bg b : mBgs.values()) if (b.exec && (b.status == Status.RUN || b.status == Status.WAIT)) return true;
        return false;
    }

    /** 选项规整：去空白、去重、最多 {@link #ASK_MAX_OPTIONS} 个。 */
    public static List<String> normalizeOptions(List<String> options) {
        List<String> out = new ArrayList<String>();
        if (options == null) return out;
        for (String o : options) {
            if (o == null) continue;
            String t = o.trim();
            if (t.length() == 0 || out.contains(t)) continue;
            out.add(t);
            if (out.size() >= ASK_MAX_OPTIONS) break;
        }
        return out;
    }

    /** 超时规整：≤0 → 默认 60 s；限制在 5 s – 10 min。 */
    public static long normalizeAskTimeoutMs(long ms) {
        if (ms <= 0L) return ASK_TIMEOUT_MS;
        return Math.max(5000L, Math.min(10L * 60L * 1000L, ms));
    }

    /** 岛能不能接这个问题：岛形态、前台任务在跑、没有未决确认卡、选项 ≥ 2。 */
    public synchronized boolean canAskUser(List<String> options) {
        return mMode == Mode.ISLAND && mEnabled && mRunning && !mConfirmPending && normalizeOptions(options).size() >= ASK_MIN_OPTIONS;
    }

    /**
     * ask_user 上岛：前台任务收进岛（面板展开着也收：芯片只画在岛上）、立即展开问题 + 2–4 个芯片，状态 = 等你（WAIT）；
     * {@code timeoutMs} 后没人选 → {@link Effect#ASK_TIMEOUT}。接不了（见 {@link #canAskUser}）→ 无效果，宿主走别的入口。
     */
    public synchronized List<Effect> askUser(String id, String question, List<String> options, long timeoutMs) {
        if (id == null || !canAskUser(options)) return NONE;
        List<Effect> out = new ArrayList<Effect>();
        mAskId = id;
        mAskQuestion = question == null ? "" : question.trim();
        mAskOptions = Collections.unmodifiableList(normalizeOptions(options));
        mAskTimeoutMs = normalizeAskTimeoutMs(timeoutMs);
        mAskSeq++;
        mIslandWorthy = true;
        mCollapseOwed = false;
        mStatus = Status.WAIT;
        mPulse = false;
        mKeyText = null;
        clearTuck();
        if (mSheet != Sheet.COLLAPSED) {
            mSheet = Sheet.COLLAPSED;
            timer("maxAge", Math.max(0L, MAX_ACTIVITY_MS), mTaskSeq, null);
            out.add(Effect.DETACH_SHEET);
        }
        if (mExp == null || K_TASK.equals(mExp) || !isBlockingExpanded()) mExp = K_TASK;
        timer("askTimeout", mAskTimeoutMs, mAskSeq, id);
        out.add(Effect.RENDER);
        return out;
    }

    public synchronized boolean isAskPending() {
        return mAskId != null;
    }

    public synchronized String askId() {
        return mAskId;
    }

    /** 最近一次被点的芯片文字（配合 {@link Effect#ASK_ANSWER}）。 */
    public synchronized String lastAskChoice() {
        return mLastAskChoice;
    }

    /** 岛上点了第 index 个芯片（0 起）。 */
    public synchronized List<Effect> askAnswer(int index) {
        if (mAskId == null || index < 0 || index >= mAskOptions.size()) return NONE;
        mLastTarget = mAskId;
        mLastAskChoice = mAskOptions.get(index);
        clearAsk();
        return list(Effect.ASK_ANSWER, Effect.RENDER);
    }

    /** 别处（面板 / 语音 / B 路取消）已经答了或撤了这个问题 → 收起芯片（不再发 ASK_*）。 */
    public synchronized List<Effect> askResolved(String id) {
        if (mAskId == null || (id != null && !id.equals(mAskId))) return NONE;
        clearAsk();
        return list(Effect.RENDER);
    }

    private void clearAsk() {
        if (mAskId == null) return;
        mAskId = null;
        mAskQuestion = null;
        mAskOptions = Collections.emptyList();
        mAskSeq++;
        if (mStatus == Status.WAIT && !mConfirmPending) mStatus = Status.RUN;
        if (K_TASK.equals(mExp) && !mConfirmPending) mExp = null;
        promoteWaitingBg();
    }

    // ---- 文案（纯函数，单测锁定；岛上不再出现「第 N 步」）

    /** 展开运行卡 / 执行模式底部那一行：「第 i/n 项」；纯 GUI →「正在操作」；不显示进度 → 空串。 */
    public static String progressLine(Snapshot s) {
        if (s == null || !s.progressVisible) return "";
        if (s.progressText != null) return s.progressText;
        return "正在操作";
    }

    /** 轻提醒第二行：有账本 →「第 i/n 项 · 当前子目标」；否则就是当前动作短句（不带步数）。 */
    public static String pulseLine(Snapshot s) {
        String act = s == null || s.pulseText == null ? "" : s.pulseText;
        if (s == null || s.progressText == null) return act;
        String cur = s.progressCurrent != null ? s.progressCurrent : act;
        return cur.length() > 0 ? s.progressText + " · " + cur : s.progressText;
    }

    /** 失败卡次行：有账本 →「第 i/n 项没完成 · 原因」；否则只有原因。 */
    public static String failLine(Snapshot s) {
        String r = s == null || s.failReason == null ? "" : s.failReason.trim();
        if (s == null || s.progressTotal < 2) return r.length() > 0 ? r : "任务处理失败";
        String head = "第 " + s.progressIndex + "/" + s.progressTotal + " 项没完成";
        return r.length() > 0 ? head + " · " + r : head;
    }

    /** 紧凑态动作词：有账本 → 当前子目标（≤6 字）；否则当前步骤 / 标题（≤4 字，同旧）。 */
    public static String compactActionWord(Snapshot s) {
        if (s == null) return "";
        // ★ 11-04 ①：最新一行是真实工具步骤（「搜索：二手摩托车 价格」）→ 显示它（整词截断、不加省略号），账本进度点照旧在右边
        String last = s.steps.isEmpty() ? null : s.steps.get(s.steps.size() - 1);
        if (IslandStepText.isToolStep(last) || IslandStepText.CONCLUDE.equals(last)) return IslandStepText.fit(last, IslandStepText.MAX_UNITS);
        if (s.progressTotal >= 2 && s.progressCurrent != null) return shortTitle(s.progressCurrent, 6);
        return actionWord(s.steps.isEmpty() ? null : s.steps.get(s.steps.size() - 1), s.title);
    }

    /** 紧凑态右侧（失败）：有账本 →「i/n」；否则空（不再显示步数）。 */
    public static String compactFailRight(Snapshot s) {
        if (s == null || s.progressTotal < 2) return "";
        return s.progressIndex + "/" + s.progressTotal;
    }

    /** ask 卡底部提示：「60 秒内没选会自动收起」。 */
    public static String askHint(long timeoutMs) {
        long sec = Math.max(1L, normalizeAskTimeoutMs(timeoutMs) / 1000L);
        return sec % 60L == 0L ? (sec / 60L) + " 分钟内没选会自动收起" : sec + " 秒内没选会自动收起";
    }

    /** 展开卡里列哪几个子目标：当前项附近 {@link #MAX_SUBGOALS_SHOWN} 个的起始下标。 */
    public static int subgoalWindowStart(int total, int currentIndex1) {
        if (total <= MAX_SUBGOALS_SHOWN) return 0;
        int cur = Math.max(1, Math.min(total, currentIndex1)) - 1;
        int start = cur - 1;
        return Math.max(0, Math.min(total - MAX_SUBGOALS_SHOWN, start));
    }

    // ------------------------------------------------------------------ 后台任务（S3 AGENT）

    public synchronized List<Effect> bgStarted(String id, String title, long nowMs) {
        if (id == null) return NONE;
        Bg b = new Bg(id, Act.AGENT, ++mOrder);
        b.title = title == null ? "" : title;
        b.status = Status.RUN;
        b.startedAt = nowMs;
        b.seq = ++mBgSeq;
        mBgs.put(id, b);
        return list(Effect.RENDER);
    }

    public synchronized List<Effect> bgStep(String id, String text) {
        Bg b = mBgs.get(id);
        if (b == null || b.act != Act.AGENT || b.status != Status.RUN) return NONE;
        b.stepNo++;
        b.step = stripDoing(text);
        return list(Effect.RENDER);
    }

    /** 后台任务需要确认：立即展开确认卡（召回暂避），宿主最多等 {@link #BG_CONFIRM_TIMEOUT_MS}。 */
    public synchronized List<Effect> bgConfirm(String id, String title, String detail) {
        Bg b = mBgs.get(id);
        if (b == null || b.act != Act.AGENT) return NONE;
        b.status = Status.WAIT;
        b.confirmTitle = title;
        b.confirmDetail = detail;
        if (mExp == null || !isBlockingExpanded()) mExp = id;
        clearTuck();
        return list(Effect.RENDER);
    }

    public synchronized List<Effect> bgConfirmResolved(String id) {
        Bg b = mBgs.get(id);
        if (b == null || b.status != Status.WAIT) return NONE;
        b.status = Status.RUN;
        if (id.equals(mExp)) mExp = null;
        promoteWaitingBg();
        return list(Effect.RENDER);
    }

    public synchronized List<Effect> bgEnded(String id, boolean ok, String reason, long nowMs) {
        Bg b = mBgs.get(id);
        if (b == null || b.act != Act.AGENT) return NONE;
        b.endedAt = nowMs;
        b.seq = ++mBgSeq;
        if (ok) {
            b.status = Status.DONE;
            if (id.equals(mExp)) mExp = null;
            timer("bgDone", DONE_HIDE_MS, b.seq, id);
        } else {
            b.status = Status.FAIL;
            b.failReason = reason == null || reason.trim().length() == 0 ? "任务处理失败" : reason.trim();
            if (mExp == null || id.equals(mExp) || !isBlockingExpanded()) mExp = id;
            clearTuck();
            timer("bgFailCollapse", FAIL_ALERT_MS, b.seq, id);
            timer("bgFailLinger", FAIL_LINGER_MS, b.seq, id);
        }
        promoteWaitingBg();
        return list(Effect.RENDER);
    }

    // ------------------------------------------------------------------ ★ r2/fast-screen 执行模式（岛取代小窗）

    private String mLastExecAction;

    public synchronized String lastExecAction() {
        return mLastExecAction;
    }

    public synchronized List<Effect> execStarted(String id, String title, String iconPkg, long nowMs) {
        if (mRunning) promote();   // ★ arch/island-progress：GUI 操作开始 → 前台任务上岛
        List<Effect> e = bgStarted(id, title, nowMs);
        Bg b = mBgs.get(id);
        if (b != null) {
            b.exec = true;
            b.iconPkg = iconPkg;
        }
        return e;
    }

    public synchronized List<Effect> execIcon(String id, String iconPkg) {
        Bg b = mBgs.get(id);
        if (b == null || !b.exec || (iconPkg != null && iconPkg.equals(b.iconPkg))) return NONE;
        b.iconPkg = iconPkg;
        return list(Effect.RENDER);
    }

    /** 接手 / 卡住 / 预算到了：暂停并展开（岛上「继续 / 停止」）。 */
    public synchronized List<Effect> execPaused(String id, String why) {
        Bg b = mBgs.get(id);
        if (b == null || !b.exec) return NONE;
        b.paused = true;
        b.step = why;
        if (mExp == null || !isBlockingExpanded()) mExp = id;
        clearTuck();
        return list(Effect.RENDER);
    }

    public synchronized List<Effect> execResumed(String id) {
        Bg b = mBgs.get(id);
        if (b == null || !b.exec) return NONE;
        b.paused = false;
        if (id.equals(mExp)) mExp = null;
        return list(Effect.RENDER);
    }

    /** 岛上执行按钮：补充 / 接管 / 继续（停止走既有的 {@link #stop()} → BG_STOP）。 */
    public synchronized List<Effect> execAction(String action) {
        String id = mExp;
        Bg b = id == null ? null : mBgs.get(id);
        if (b == null || !b.exec) {
            for (Bg x : mBgs.values()) if (x.exec && x.act == Act.AGENT) b = x;
        }
        if (b == null) return NONE;
        mLastTarget = b.id;
        mLastExecAction = action;
        return list(Effect.EXEC_ACTION);
    }

    /** 完成：岛显示「完成」，点开是文字结果卡（复用记一记结果卡）。 */
    public synchronized List<Effect> execDone(String id, boolean ok, String title, String detail, long nowMs) {
        Bg old = mBgs.remove(id);
        String pkg = old != null ? old.iconPkg : null;
        if (id.equals(mExp)) mExp = null;
        List<Effect> e = noteResult(id, ok, title, detail, null, null, nowMs);
        Bg b = mBgs.get(id);
        if (b != null) {
            b.exec = true;
            b.iconPkg = pkg;
        }
        return e;
    }

    // ------------------------------------------------------------------ ★ R10 记一记（NOTE）

    /** 长按记一记开始：紧凑岛「记录中」流光（不展开、不出声）。 */
    public synchronized List<Effect> noteStarted(String id, String title, long nowMs) {
        if (id == null) return NONE;
        Bg b = new Bg(id, Act.NOTE, ++mOrder);
        b.title = title == null || title.length() == 0 ? "记录中" : title;
        b.status = Status.RUN;
        b.startedAt = nowMs;
        b.seq = ++mBgSeq;
        mBgs.put(id, b);
        clearTuck();
        timer("noteRunMax", NOTE_RUN_MAX_MS, b.seq, id);
        return list(Effect.RENDER);
    }

    /** 记好了 / 没记：展开结果卡 {@link #NOTE_CARD_MS}，之后移除。actions = 按钮文字（≤ 3）。 */
    public synchronized List<Effect> noteResult(String id, boolean ok, String title, String detail, String meta, List<String> actions,
                                                long nowMs) {
        if (id == null) return NONE;
        Bg b = mBgs.get(id);
        if (b == null || b.act != Act.NOTE) {
            b = new Bg(id, Act.NOTE, ++mOrder);
            b.startedAt = nowMs;
            mBgs.put(id, b);
        }
        b.status = ok ? Status.DONE : Status.FAIL;
        b.title = title == null ? "" : title;
        b.noteDetail = detail;
        b.noteMeta = meta;
        b.failReason = ok ? null : detail;
        List<String> acts = new ArrayList<String>();
        if (actions != null) for (String a : actions) if (a != null && acts.size() < 3) acts.add(a);
        b.noteActions = Collections.unmodifiableList(acts);
        b.endedAt = nowMs;
        b.seq = ++mBgSeq;
        if (mExp == null || !isBlockingExpanded()) mExp = id;
        clearTuck();
        mPulse = false;
        timer("noteHide", noteCardMs(detail, acts.size()), b.seq, id);
        return list(Effect.RENDER);
    }

    /** 结果卡上点了第 index 个动作：卡片收走，宿主执行（lastTarget = 「id#index」）。 */
    public synchronized List<Effect> noteAction(int index) {
        Bg b = mExp == null ? null : mBgs.get(mExp);
        if (b == null || b.act != Act.NOTE || index < 0 || index >= b.noteActions.size()) return NONE;
        mBgs.remove(b.id);
        mExp = null;
        mLastTarget = b.id + "#" + index;
        if (b.suggest) return list(Effect.SUGGEST_ACTION, Effect.RENDER);   // ★ feat/suggest
        return list(Effect.NOTE_ACTION, Effect.RENDER);
    }

    /** 结果卡正文被点：打开 App 里的记一记详情。 */
    public synchronized List<Effect> noteOpen() {
        Bg b = mExp == null ? null : mBgs.get(mExp);
        if (b == null || b.act != Act.NOTE) return NONE;
        if (b.suggest) {   // ★ feat/suggest：点卡片正文 = 触碰暂停计时（不执行任何动作），最长再留 SUGGEST_TOUCH_MAX_MS
            b.seq = ++mBgSeq;
            timer("suggestHide", SUGGEST_TOUCH_MAX_MS, b.seq, b.id);
            return list(Effect.RENDER);
        }
        mBgs.remove(b.id);
        mExp = null;
        mLastTarget = b.id;
        return list(Effect.OPEN_APP_NOTE, Effect.RENDER);
    }

    // ------------------------------------------------------------------ ★ feat/suggest 主动建议卡（渲染复用 NOTE 结果卡）

    /** 建议卡被触碰后最多再留多久（与剪贴板芯片「碰过就不自动消失」一致，但给个上限）。 */
    public static final long SUGGEST_TOUCH_MAX_MS = 30000L;
    public static final String SUGGEST_META = "建议";

    /**
     * 岛此刻能不能放一张建议卡（优先级最低）：岛形态、开着、非全屏、没暂避；没有前台任务 / 确认卡 / ask_user / 等确认的后台任务；
     * 没有正在展开的提醒或别的卡（已展开的是建议卡则可替换）。
     */
    public synchronized boolean canSuggest() {
        if (mMode != Mode.ISLAND || !mEnabled || mFullscreen || mTucked) return false;
        if (mRunning || mConfirmPending || mAskId != null || anyWait()) return false;
        if (mNoticeAlerting || K_NOTICE.equals(mExp)) return false;
        if (mExp != null) {
            Bg e = mBgs.get(mExp);
            if (e == null || !e.suggest) return false;
        }
        return true;
    }

    /** 放建议卡：返回 null = 不能放（宿主记 SUPPRESSED）。同时只有一张：旧的建议卡直接拿掉（引擎已记过替换）。 */
    public synchronized List<Effect> suggestShow(String id, String title, String subtitle, String thumb, List<String> actions,
                                                 long ttlMs, long nowMs) {
        if (id == null || !canSuggest()) return null;
        java.util.Iterator<Bg> it = mBgs.values().iterator();
        while (it.hasNext()) if (it.next().suggest) it.remove();
        Bg b = new Bg(id, Act.NOTE, ++mOrder);
        b.suggest = true;
        b.status = Status.DONE;
        b.title = title == null ? "" : title;
        b.noteDetail = subtitle;
        b.noteMeta = SUGGEST_META;
        b.thumb = thumb;
        List<String> acts = new ArrayList<String>();
        if (actions != null) for (String a : actions) if (a != null && acts.size() < 3) acts.add(a);
        b.noteActions = Collections.unmodifiableList(acts);
        b.startedAt = nowMs;
        b.endedAt = nowMs;
        b.seq = ++mBgSeq;
        mBgs.put(id, b);
        mExp = id;
        clearTuck();
        mPulse = false;
        timer("suggestHide", ttlMs > 0 ? ttlMs : NOTE_CARD_MS, b.seq, id);
        return list(Effect.RENDER);
    }

    /** 就地改副标题（连拍计数），不重置计时。 */
    public synchronized List<Effect> suggestUpdate(String id, String subtitle) {
        Bg b = id == null ? null : mBgs.get(id);
        if (b == null || !b.suggest) return NONE;
        b.noteDetail = subtitle;
        return list(Effect.RENDER);
    }

    /** 撤卡（截图被删 / 被替换 / 插件关闭）。 */
    public synchronized List<Effect> suggestWithdraw(String id) {
        Bg b = id == null ? null : mBgs.get(id);
        if (b == null || !b.suggest) return NONE;
        mBgs.remove(id);
        if (id.equals(mExp)) mExp = null;
        return list(Effect.RENDER);
    }

    public synchronized boolean suggestShowing(String id) {
        Bg b = id == null ? null : mBgs.get(id);
        return b != null && b.suggest;
    }

    // ------------------------------------------------------------------ 提醒（S3 REMINDER）

    /** 提醒到点：关键事件展开（完成 / 稍后10分钟），{@link #REMIND_ALERT_MS} 后收成紧凑，{@link #REMIND_LINGER_MS} 后移除。 */
    public synchronized List<Effect> remindFired(String id, String title, long atMs, int lateMin, long nowMs) {
        if (id == null) return NONE;
        Bg old = mBgs.remove(id);
        Bg b = new Bg(id, Act.REMIND, old != null ? old.order : ++mOrder);
        b.title = title == null ? "" : title;
        b.status = Status.NONE;
        b.at = atMs;
        b.lateMin = lateMin;
        b.startedAt = nowMs;
        b.seq = ++mBgSeq;
        mBgs.put(id, b);
        if (mExp == null || !isBlockingExpanded()) mExp = id;
        clearTuck();
        mPulse = false;
        timer("remindAlert", REMIND_ALERT_MS, b.seq, id);
        timer("remindExpire", REMIND_LINGER_MS, b.seq, id);
        return list(Effect.RENDER);
    }

    /** 提醒卡「完成」（展开的提醒；或指定 id）。 */
    public synchronized List<Effect> remindDone(String id) {
        Bg b = remindTarget(id);
        if (b == null) return NONE;
        mBgs.remove(b.id);
        if (b.id.equals(mExp)) mExp = null;
        mLastTarget = b.id;
        return list(Effect.REMIND_DONE, Effect.RENDER);
    }

    /** 提醒卡「稍后10分钟」：活动移除，宿主排一次 10 分钟后的再提醒。 */
    public synchronized List<Effect> remindSnooze(String id) {
        Bg b = remindTarget(id);
        if (b == null) return NONE;
        mBgs.remove(b.id);
        if (b.id.equals(mExp)) mExp = null;
        mLastTarget = b.id;
        return list(Effect.REMIND_SNOOZE, Effect.RENDER);
    }

    public synchronized List<Effect> removeBg(String id) {
        if (id == null || mBgs.remove(id) == null) return NONE;
        if (id.equals(mExp)) mExp = null;
        return list(Effect.RENDER);
    }

    private Bg remindTarget(String id) {
        if (id != null) {
            Bg b = mBgs.get(id);
            return b != null && b.act == Act.REMIND ? b : null;
        }
        if (mExp != null) {
            Bg b = mBgs.get(mExp);
            if (b != null && b.act == Act.REMIND) return b;
        }
        return null;
    }

    // ------------------------------------------------------------------ 待办

    /** 未完成待办（最近的 5 条，按截止时间从近到远）+ 今天到期/已过期的数量（空闲计数块用）。 */
    public synchronized List<Effect> setTodos(List<TodoRow> rows, int todayCount) {
        List<TodoRow> next = new ArrayList<TodoRow>();
        if (rows != null) for (TodoRow r : rows) if (r != null && next.size() < MAX_TODOS_SHOWN) next.add(r);
        boolean changed = todayCount != mTodoToday || next.size() != mTodos.size();
        if (!changed) for (int i = 0; i < next.size(); i++) if (!next.get(i).id.equals(mTodos.get(i).id)) changed = true;
        mTodos.clear();
        mTodos.addAll(next);
        mTodoToday = Math.max(0, todayCount);
        if (K_TODO.equals(mExp) && mTodos.isEmpty()) mExp = null;
        return changed ? list(Effect.RENDER) : NONE;
    }

    /** 待办列表里点一条：勾销（宿主改库后再 setTodos）。 */
    public synchronized List<Effect> todoCheck(String id) {
        if (!K_TODO.equals(mExp) || id == null) return NONE;
        for (TodoRow r : mTodos) {
            if (r.id.equals(id)) {
                mLastTarget = id;
                return list(Effect.TODO_CHECK);
            }
        }
        return NONE;
    }

    // ------------------------------------------------------------------ ★ 消息提醒（NOTICE）

    /**
     * 面板（聆听 / 全屏回答 / 确认卡）挂上或摘下的那一刻，宿主告诉模型。
     * <ul>
     *   <li>出现：提醒卡正展开（自动弹出或用户点开的都算）→ <b>立即</b>收回队列（紧凑 / 双活动角标），作废自动收起计时；
     *       记下「面板关了要恢复」。提醒不展开的时候什么都不做。</li>
     *   <li>关闭：之前让过位（或面板在屏时来过要展开的新提醒）→ 岛空闲（{@link #noticeBusy()} 为 false）且没有别的展开卡时，
     *       提醒重新自动展开 {@link #NOTICE_ALERT_MS}；岛不空闲（任务收进岛、确认卡、后台任务……）就不展开，提醒留在队列里。</li>
     * </ul>
     * 只动提醒自己的展开状态：<b>绝不</b>产生 ATTACH / DETACH_SHEET、CANCEL_TASK、END_SESSION（LC-5、全屏回答、岛上任务都不受影响，单测锁定）。
     */
    public synchronized List<Effect> panelShown(boolean on) {
        if (mPanelShowing == on) return NONE;
        mPanelShowing = on;
        if (on) {
            mNoticeYieldFs = false;   // 宽限期里面板又出现：欠账留给下一次关闭
            if (!K_NOTICE.equals(mExp)) return NONE;
            mNoticeYield = true;
            mNoticeYieldHigh = mNoticeAlertHigh;
            mNoticeAlerting = false;
            mNoticeAlertHigh = false;
            mNoticeSeq++;   // 作废还没到的 noticeAlert 计时
            mExp = null;
            return list(Effect.RENDER);
        }
        if (!mNoticeYield) return NONE;
        if (mFullscreen && !mNoticeYieldHigh && !mNotices.isEmpty() && mMode == Mode.ISLAND) {
            // BUG-I2b：全屏回答（LC-5）把岛判成全屏，面板摘掉时「退出全屏」还没到 → 欠账留着，宽限期内退出全屏再补展开
            mNoticeYieldFs = true;
            timer("noticeYieldFs", NOTICE_YIELD_FS_GRACE_MS, ++mNoticeYieldFsSeq, null);
            return NONE;
        }
        return resumeNoticeYield();
    }

    /** 面板关了，补上让位时欠的那次提醒展开（岛不空闲 / 有别的展开卡 / 全屏且非高优先级 → 不展开，提醒留在队列）。 */
    private List<Effect> resumeNoticeYield() {
        boolean high = mNoticeYieldHigh;
        mNoticeYield = false;
        mNoticeYieldHigh = false;
        mNoticeYieldFs = false;
        if (mNotices.isEmpty() || mMode != Mode.ISLAND || mExp != null || noticeBusy() || (mFullscreen && !high)) return NONE;
        mExp = K_NOTICE;
        if (noticeIndex(mNoticeFocus) < 0) mNoticeFocus = mNotices.get(0).id;
        mNoticeAlerting = true;
        mNoticeAlertHigh = high;
        if (high) clearTuck();
        mPulse = false;
        timer("noticeAlert", NOTICE_ALERT_MS, ++mNoticeSeq, null);
        return list(Effect.RENDER);
    }

    /** 面板关闭后正在等「退出全屏」补展开（单测 / dump 用）。 */
    public synchronized boolean noticeYieldWaitingFullscreen() {
        return mNoticeYieldFs;
    }

    /** 面板关了之后是否还欠一次提醒展开（单测 / dump 用）。 */
    public synchronized boolean noticeYieldPending() {
        return mNoticeYield;
    }

    /**
     * 此刻自动展开提醒会不会打断别的：面板在屏幕上（全屏回答 / 聆听）、前台任务在跑（面板里或收在岛上）、
     * 后台任务 / 记一记在跑、有确认卡、有别的展开卡、关键提醒在显示 —— 任一成立就只排队（紧凑 / 双活动角标），不展开。
     */
    public synchronized boolean noticeBusy() {
        if (mPanelShowing) return true;
        if (mSheet != Sheet.IDLE || mRunning || mConfirmPending) return true;
        if (mKeyText != null) return true;
        if (mExp != null && !K_NOTICE.equals(mExp)) return true;
        for (Bg b : mBgs.values()) {
            if (b.status == Status.WAIT) return true;
            if ((b.act == Act.AGENT || b.act == Act.NOTE) && b.status == Status.RUN) return true;
        }
        return false;
    }

    /**
     * 刷新岛上的提醒队列（rows 按队列顺序，队首 = 岛上显示的那条）。
     * alert = 中心说「有新提醒且此刻不静音」；只有 {@link #noticeBusy()} 为 false 时才真的展开 {@link #NOTICE_ALERT_MS}，
     * 否则只排进队列——<b>绝不</b>收起面板、挂回面板、取消任务或顶掉确认卡（单测锁定）。
     */
    public synchronized List<Effect> setNotices(List<NoticeRow> rows, boolean alert, boolean high) {
        List<NoticeRow> next = new ArrayList<NoticeRow>();
        if (rows != null) for (NoticeRow r : rows) if (r != null && r.id != null) next.add(r);
        boolean changed = !next.equals(mNotices);
        mNotices.clear();
        mNotices.addAll(next);
        if (mNotices.isEmpty()) {
            mNoticeFocus = null;
            if (K_NOTICE.equals(mExp)) {
                mExp = null;
                promoteWaitingBg();
            }
            mNoticeAlerting = false;
            mNoticeAlertHigh = false;
            mNoticeYield = false;
            mNoticeYieldHigh = false;
            mNoticeYieldFs = false;
            return changed ? list(Effect.RENDER) : NONE;
        }
        if (noticeIndex(mNoticeFocus) < 0) mNoticeFocus = mNotices.get(0).id;
        if (alert && mMode == Mode.ISLAND && mPanelShowing && (!mFullscreen || high)) {
            // BUG-I2：面板在屏 → 只排队；面板关了再展开一次（那时岛不空闲就不展开）
            mNoticeYield = true;
            mNoticeYieldHigh = mNoticeYieldHigh || high;
        }
        if (alert && mMode == Mode.ISLAND && !noticeBusy() && (!mFullscreen || high)) {   // 全屏只放行高优先级
            mExp = K_NOTICE;
            mNoticeFocus = mNotices.get(0).id;
            mNoticeAlerting = true;
            mNoticeAlertHigh = high;
            if (high) clearTuck();
            mPulse = false;
            timer("noticeAlert", NOTICE_ALERT_MS, ++mNoticeSeq, null);
            return list(Effect.RENDER);
        }
        return changed ? list(Effect.RENDER) : NONE;
    }

    public synchronized int noticeCount() {
        return mNotices.size();
    }

    public synchronized String noticeFocus() {
        return mNoticeFocus;
    }

    /** 展开卡里点一条：选中它（显示它的动作）。 */
    public synchronized List<Effect> noticeFocus(String id) {
        if (!K_NOTICE.equals(mExp) || noticeIndex(id) < 0) return NONE;
        mNoticeFocus = id;
        mNoticeAlerting = false;   // 用户在看：不自动收
        mNoticeAlertHigh = false;
        return list(Effect.RENDER);
    }

    /** 忽略一条（点叉 / 左滑）。id = null → 紧凑态左滑 = 队首；展开态 = 选中的那条。先在本地拿掉（手感立即），再交宿主改中心。 */
    public synchronized List<Effect> noticeDismiss(String id) {
        String target = id;
        if (target == null) target = K_NOTICE.equals(mExp) && mNoticeFocus != null ? mNoticeFocus : (mNotices.isEmpty() ? null : mNotices.get(0).id);
        int i = noticeIndex(target);
        if (i < 0) return NONE;
        mNotices.remove(i);
        if (target.equals(mNoticeFocus)) mNoticeFocus = mNotices.isEmpty() ? null : mNotices.get(0).id;
        if (mNotices.isEmpty() && K_NOTICE.equals(mExp)) {
            mExp = null;
            promoteWaitingBg();
        }
        mLastTarget = target;
        return list(Effect.NOTICE_DISMISS, Effect.RENDER);
    }

    public synchronized List<Effect> noticeDismissAll() {
        if (mNotices.isEmpty()) return NONE;
        mNotices.clear();
        mNoticeFocus = null;
        if (K_NOTICE.equals(mExp)) {
            mExp = null;
            promoteWaitingBg();
        }
        return list(Effect.NOTICE_DISMISS_ALL, Effect.RENDER);
    }

    /** 选中那条的第 index 个动作：先本地拿掉这条，宿主执行（lastTarget = 「id#index」）。 */
    public synchronized List<Effect> noticeAction(int index) {
        int i = noticeIndex(mNoticeFocus);
        if (!K_NOTICE.equals(mExp) || i < 0) return NONE;
        NoticeRow r = mNotices.get(i);
        if (index < 0 || index >= r.actions.size()) return NONE;
        if (r.keepsOpen(index)) {
            //：通话「静音 / 免提」——卡片不收、这一行留着，来源更新按钮文字后经 setNotices 刷新
            mNoticeAlerting = false;
            mLastTarget = r.id + "#" + index;
            return list(Effect.NOTICE_ACTION, Effect.RENDER);
        }
        mNotices.remove(i);
        mNoticeFocus = mNotices.isEmpty() ? null : mNotices.get(0).id;
        mNoticeAlerting = false;
        mNoticeAlertHigh = false;
        mExp = null;
        promoteWaitingBg();
        mLastTarget = r.id + "#" + index;
        return list(Effect.NOTICE_ACTION, Effect.RENDER);
    }

    private int noticeIndex(String id) {
        if (id == null) return -1;
        for (int i = 0; i < mNotices.size(); i++) if (id.equals(mNotices.get(i).id)) return i;
        return -1;
    }

    // ------------------------------------------------------------------ 取件码

    public synchronized List<Effect> setPickups(List<Pickup> active) {
        List<Pickup> next = new ArrayList<Pickup>();
        if (active != null) {
            int from = Math.max(0, active.size() - MAX_PICKUPS_SHOWN);
            for (int i = from; i < active.size(); i++) if (active.get(i) != null) next.add(active.get(i));
        }
        boolean arrived = false;
        for (Pickup p : next) if (indexOf(mPickups, p.id) < 0) arrived = true;
        boolean changed = arrived || next.size() != mPickups.size();
        mPickups.clear();
        mPickups.addAll(next);
        if (mPickups.isEmpty()) {
            mPickupId = null;
            mCopiedId = null;
            if (K_PICKUP.equals(mExp)) mExp = null;
        } else if (arrived || indexOf(mPickups, mPickupId) < 0) {
            mPickupId = mPickups.get(mPickups.size() - 1).id;
            mCopiedId = null;
        }
        return changed ? list(Effect.RENDER) : NONE;
    }

    public synchronized List<Effect> pagePickup(int delta) {
        if (!K_PICKUP.equals(mExp) || mPickups.isEmpty()) return NONE;
        int i = indexOf(mPickups, mPickupId);
        if (i < 0) i = mPickups.size() - 1;
        int j = Math.max(0, Math.min(mPickups.size() - 1, i + delta));
        if (j == i) return NONE;
        mPickupId = mPickups.get(j).id;
        mCopiedId = null;
        return list(Effect.RENDER);
    }

    public synchronized List<Effect> copyPickup() {
        if (!K_PICKUP.equals(mExp) || currentPickup() == null) return NONE;
        return list(Effect.COPY_PICKUP);
    }

    public synchronized List<Effect> markCopied(String id) {
        if (id == null || indexOf(mPickups, id) < 0) return NONE;
        mCopiedId = id;
        mCopySeq++;
        timer("copiedReset", COPIED_MS, mCopySeq, null);
        return list(Effect.RENDER);
    }

    public synchronized List<Effect> copiedResetDue(int seq) {
        if (seq != mCopySeq || mCopiedId == null) return NONE;
        mCopiedId = null;
        return list(Effect.RENDER);
    }

    public synchronized List<Effect> dismissPickup() {
        if (!K_PICKUP.equals(mExp) || currentPickup() == null) return NONE;
        return list(Effect.DISMISS_PICKUP);
    }

    // ------------------------------------------------------------------ 延时

    /** 宿主的延时回调汇点（序号过期 = 无效）。 */
    public synchronized List<Effect> timerDue(String name, int seq, String target) {
        if (name == null) return NONE;
        if ("autoCollapse".equals(name)) return autoCollapseDue(seq);
        if ("doneHide".equals(name)) return doneHideDue(seq);
        if ("copiedReset".equals(name)) return copiedResetDue(seq);
        if ("pulse".equals(name)) {
            if (seq != mPulseSeq || !mPulse) return NONE;
            mPulse = false;
            return list(Effect.RENDER);
        }
        if ("key".equals(name)) {
            if (seq != mKeySeq || mKeyText == null) return NONE;
            mKeyText = null;
            return list(Effect.RENDER);
        }
        if ("tuck".equals(name)) {
            if (seq != mTuckSeq || !mTucked) return NONE;
            mTucked = false;
            return list(Effect.RENDER);
        }
        if ("failCollapse".equals(name)) {
            if (seq != mTaskSeq || mStatus != Status.FAIL || !K_TASK.equals(mExp)) return NONE;
            mExp = null;
            return list(Effect.RENDER);
        }
        if ("failLinger".equals(name)) {
            if (seq != mTaskSeq || mStatus != Status.FAIL || mRunning) return NONE;
            clearTask();
            return list(Effect.RENDER, Effect.END_SESSION);
        }
        if ("maxAge".equals(name)) {
            if (seq != mTaskSeq || mSheet != Sheet.COLLAPSED || !mRunning) return NONE;
            mSheet = Sheet.EXPANDED;
            if (mStatus == Status.WAIT) mStatus = Status.RUN;
            if (K_TASK.equals(mExp)) mExp = null;
            return list(Effect.ATTACH_SHEET, Effect.RENDER);
        }
        if ("askTimeout".equals(name)) {   // ★ arch/island-progress：60 s 没人选 → 收起芯片，等待方拿到 no_answer
            if (seq != mAskSeq || mAskId == null || (target != null && !target.equals(mAskId))) return NONE;
            mLastTarget = mAskId;
            clearAsk();
            return list(Effect.ASK_TIMEOUT, Effect.RENDER);
        }
        if ("noticeYieldFs".equals(name)) {
            // BUG-I2b：宽限期过了还在全屏（真的在看全屏视频 / 游戏）→ 不再补展开，提醒留在队列
            if (seq != mNoticeYieldFsSeq || !mNoticeYieldFs) return NONE;
            mNoticeYieldFs = false;
            mNoticeYield = false;
            mNoticeYieldHigh = false;
            return NONE;
        }
        if ("noticeAlert".equals(name)) {
            if (seq != mNoticeSeq || !mNoticeAlerting || !K_NOTICE.equals(mExp)) return NONE;
            mNoticeAlerting = false;
            mNoticeAlertHigh = false;
            mExp = null;
            return list(Effect.RENDER);
        }
        Bg b = target == null ? null : mBgs.get(target);
        if (b == null || b.seq != seq) return NONE;
        if ("bgDone".equals(name)) {
            if (b.status != Status.DONE) return NONE;
            mBgs.remove(b.id);
            return list(Effect.RENDER);
        }
        if ("bgFailCollapse".equals(name)) {
            if (b.status != Status.FAIL || !b.id.equals(mExp)) return NONE;
            mExp = null;
            return list(Effect.RENDER);
        }
        if ("bgFailLinger".equals(name)) {
            if (b.status != Status.FAIL) return NONE;
            mBgs.remove(b.id);
            if (b.id.equals(mExp)) mExp = null;
            return list(Effect.RENDER);
        }
        if ("suggestHide".equals(name)) {   // ★ feat/suggest
            if (!b.suggest) return NONE;
            mBgs.remove(b.id);
            if (b.id.equals(mExp)) mExp = null;
            mLastTarget = b.id;
            return list(Effect.SUGGEST_TIMEOUT, Effect.RENDER);
        }
        if ("noteHide".equals(name) || "noteRunMax".equals(name)) {
            if ("noteRunMax".equals(name) && b.status != Status.RUN) return NONE;
            mBgs.remove(b.id);
            if (b.id.equals(mExp)) mExp = null;
            return list(Effect.RENDER);
        }
        if ("remindAlert".equals(name)) {
            if (!b.id.equals(mExp)) return NONE;
            mExp = null;
            return list(Effect.RENDER);
        }
        if ("remindExpire".equals(name)) {
            mBgs.remove(b.id);
            if (b.id.equals(mExp)) mExp = null;
            return list(Effect.RENDER);
        }
        return NONE;
    }

    // ------------------------------------------------------------------ 视图

    public synchronized Snapshot view() {
        if (mMode != Mode.ISLAND) {
            boolean task = taskVisible();
            return snap(task ? Kind.COMPACT_TASK : Kind.HIDDEN, task ? K_TASK : null, null);
        }
        List<String> keys = visibleKeys();
        Kind kind;
        String main;
        String second = null;
        if (mExp != null && validKey(mExp)) {
            main = mExp;
            kind = expandedKind(mExp);
        } else if (mKeyText != null && taskVisible()) {
            main = K_TASK;
            kind = Kind.ALERT_KEY;
        } else if (keys.isEmpty()) {
            main = showTodoChip() ? K_TODO : null;
            kind = main == null ? Kind.HIDDEN : Kind.COMPACT_TODO;
        } else if (keys.size() >= 2) {
            main = keys.get(0);
            second = keys.get(1);
            kind = Kind.MINIMAL_DUAL;
        } else {
            main = keys.get(0);
            kind = compactKind(main);
            if (kind == Kind.COMPACT_TASK && K_TASK.equals(main) && mPulse && mStatus == Status.RUN) kind = Kind.PULSE_STEP;
        }
        // ★ R11（真机 QQ 邮箱）：记一记是用户亲手按出来的 —— 全屏时也照常显示「记录中」和结果（Apple：用户发起的活动总是可见）；
        //   暂避（用户自己横滑藏起）仍然藏，但新的一次记一记会先取消暂避（见 noteStarted）
        boolean keyNotice = kind == Kind.EXP_NOTICE && mNoticeAlerting && mNoticeAlertHigh;
        if (!isKeyKind(kind) && !keyNotice && (mTucked || (mFullscreen && !isUserInitiated(main) && !isUserInitiated(second)))) {
            kind = Kind.HIDDEN;
            main = null;
            second = null;
        }
        return snap(kind, main, second);
    }

    private Snapshot snap(Kind kind, String main, String second) {
        Act act = actOf(main);
        Status st = Status.NONE;
        String title = "";
        long started = 0L, ended = 0L, at = 0L;
        int stepNo = 0, late = 0;
        List<String> steps = Collections.emptyList();
        String fail = null, ct = null, cd = null, pulse = null;
        if (K_TASK.equals(main)) {
            st = mStatus;
            title = mTitle;
            started = mStartedAt;
            ended = mEndedAt;
            stepNo = mStepNo;
            int from = Math.max(0, mSteps.size() - MAX_STEPS_SHOWN);
            steps = Collections.unmodifiableList(new ArrayList<String>(mSteps.subList(from, mSteps.size())));
            fail = mFailReason;
            ct = mConfirmTitle;
            cd = mConfirmDetail;
            if (!mSteps.isEmpty()) pulse = mSteps.get(mSteps.size() - 1);
        } else if (main != null && mBgs.containsKey(main)) {
            Bg b = mBgs.get(main);
            st = b.status;
            title = b.title;
            started = b.startedAt;
            ended = b.endedAt;
            stepNo = b.stepNo;
            if (b.step != null) steps = Collections.singletonList(b.step);
            fail = b.failReason;
            ct = b.confirmTitle;
            cd = b.confirmDetail;
            at = b.at;
            late = b.lateMin;
        }
        if (K_NOTICE.equals(main) && !mNotices.isEmpty()) title = mNotices.get(0).title;
        boolean pk = K_PICKUP.equals(main) || K_PICKUP.equals(second);
        Pickup cur = pk ? currentPickup() : null;
        int idx = cur == null ? -1 : indexOf(mPickups, cur.id);
        Act bub = second == null ? null : actOf(second);
        Status bst = second == null ? Status.NONE : statusOf(second);
        String btext = null;
        if (K_PICKUP.equals(second) && cur != null) btext = tail(cur.code, 4);
        else if (second != null && mBgs.containsKey(second) && mBgs.get(second).act == Act.REMIND) btext = "提醒";
        else if (second != null && mBgs.containsKey(second) && mBgs.get(second).act == Act.NOTE) btext = "记";
        else if (K_NOTICE.equals(second)) btext = mNotices.size() > 1 ? "+" + (mNotices.size() - 1) : "1";
        List<TodoRow> todos = K_TODO.equals(main)
                ? Collections.unmodifiableList(new ArrayList<TodoRow>(mTodos)) : Collections.<TodoRow>emptyList();
        Snapshot sn = new Snapshot(kind, mMode, act, main, second != null, bub, second, bst, btext, st, title, started, ended,
                stepNo, steps, fail, ct, cd, cur, idx, pk ? mPickups.size() : 0,
                cur != null && cur.id.equals(mCopiedId), pulse, K_TASK.equals(main) ? mKeyText : null, at, late, todos,
                mTodoToday, mFullscreen, mTucked);
        if (K_TASK.equals(main)) {
            sn.thinking = mThinking;
            sn.phaseAt = mPhaseAt;
        }
        Bg nb = main == null ? null : mBgs.get(main);
        if (nb != null && nb.exec) {
            sn.exec = true;
            sn.paused = nb.paused;
            sn.iconPkg = nb.iconPkg;
        }
        if (nb != null && nb.act == Act.NOTE) {
            sn.noteDetail = nb.noteDetail;
            sn.noteMeta = nb.noteMeta;
            sn.noteActions = nb.noteActions;
            sn.suggest = nb.suggest;
            sn.noteThumb = nb.thumb;
        }
        // ★ arch/island-progress：进度只在「GUI 操作中」或「子目标 ≥ 2」时显示（不再显示工具调用次数）
        Subgoal.Progress pg = null;
        if (K_TASK.equals(main)) pg = Subgoal.progress(mSubgoals, anyGuiRunning());
        else if (nb != null && nb.exec) pg = Subgoal.progress(mRunning ? mSubgoals : Collections.<Subgoal>emptyList(), true);
        if (pg != null && pg.visible) {
            sn.progressVisible = true;
            sn.progressIndex = pg.index;
            sn.progressTotal = pg.total;
            sn.progressText = pg.text;
            sn.progressCurrent = pg.current;
            sn.progressFailed = pg.failed;
            if (pg.total >= 2) sn.subgoals = mSubgoals;
        }
        if (K_TASK.equals(main) && mAskId != null) {
            sn.askId = mAskId;
            sn.askQuestion = mAskQuestion;
            sn.askOptions = mAskOptions;
            sn.askTimeoutMs = mAskTimeoutMs;
        }
        if (K_NOTICE.equals(main) || K_NOTICE.equals(second)) {
            sn.notices = Collections.unmodifiableList(new ArrayList<NoticeRow>(mNotices));
            sn.noticeCount = mNotices.size();
            sn.noticeFocus = mNoticeFocus;
        }
        return sn;
    }

    /** 可见活动的键，按优先级（确认 &gt; 运行中 &gt; 提醒 &gt; 取件码）排好；同级按出现先后，前台任务在前。 */
    synchronized List<String> visibleKeys() {
        List<String> keys = new ArrayList<String>();
        List<Integer> ranks = new ArrayList<Integer>();
        if (taskVisible()) add(keys, ranks, K_TASK, mStatus == Status.WAIT ? 0 : 1);
        for (Bg b : mBgs.values()) {
            if (b.suggest && !b.id.equals(mExp)) continue;   // ★ feat/suggest：建议卡只在展开时可见，被别的活动顶掉就不再占位
            if (b.act == Act.AGENT || b.act == Act.NOTE) add(keys, ranks, b.id, b.status == Status.WAIT ? 0 : 1);
            else add(keys, ranks, b.id, 2);
        }
        if (!mNotices.isEmpty()) add(keys, ranks, K_NOTICE, 2);   // ★：与提醒同级，排在确认 / 运行中的任务之后
        if (!mPickups.isEmpty()) add(keys, ranks, K_PICKUP, 3);
        return keys;
    }

    /** 活动优先级（数值越小越靠前）：确认 0 &gt; 运行中 1 &gt; 提醒 2 &gt; 取件码 3。 */
    public static int rank(Act act, Status status) {
        if (status == Status.WAIT) return 0;
        if (act == Act.TASK || act == Act.AGENT || act == Act.NOTE) return 1;
        if (act == Act.REMIND) return 2;
        return 3;
    }

    private static void add(List<String> keys, List<Integer> ranks, String key, int rank) {
        int i = 0;
        while (i < ranks.size() && ranks.get(i) <= rank) i++;
        keys.add(i, key);
        ranks.add(i, rank);
    }

    private boolean validKey(String key) {
        if (K_TASK.equals(key)) return taskVisible() && mStatus != Status.DONE;
        if (K_PICKUP.equals(key)) return !mPickups.isEmpty();
        if (K_TODO.equals(key)) return mTodoChip || !mTodos.isEmpty();
        if (K_NOTICE.equals(key)) return !mNotices.isEmpty();
        Bg b = mBgs.get(key);
        return b != null && (b.status != Status.DONE || b.act == Act.NOTE);
    }

    private Kind expandedKind(String key) {
        if (K_PICKUP.equals(key)) return Kind.EXP_PICKUP;
        if (K_TODO.equals(key)) return Kind.EXP_TODO;
        if (K_NOTICE.equals(key)) return Kind.EXP_NOTICE;
        Status st;
        if (K_TASK.equals(key) && mAskId != null) return Kind.EXP_ASK;
        if (K_TASK.equals(key)) st = mStatus;
        else {
            Bg b = mBgs.get(key);
            if (b.act == Act.REMIND) return Kind.EXP_REMIND;
            if (b.act == Act.NOTE) return b.status == Status.RUN ? Kind.COMPACT_NOTE : Kind.EXP_NOTE;
            st = b.status;
        }
        return st == Status.WAIT ? Kind.EXP_CONFIRM : st == Status.FAIL ? Kind.EXP_FAIL : Kind.EXP_RUN;
    }

    private Kind compactKind(String key) {
        if (K_PICKUP.equals(key)) return Kind.COMPACT_PICKUP;
        if (K_TODO.equals(key)) return Kind.COMPACT_TODO;
        if (K_NOTICE.equals(key)) return Kind.COMPACT_NOTICE;
        Bg b = mBgs.get(key);
        if (b != null && b.act == Act.REMIND) return Kind.COMPACT_REMIND;
        if (b != null && b.act == Act.NOTE) return Kind.COMPACT_NOTE;
        return Kind.COMPACT_TASK;
    }

    /** ★ R11：用户亲手触发的活动（记一记：长按 / 语音）——全屏、暂避都不藏。 */
    private boolean isUserInitiated(String key) {
        if (key == null) return false;
        Bg b = mBgs.get(key);
        // ★ fast-screen-v2：执行模式（AI 正在替你点屏幕）也是用户发起的活动 —— 岛是唯一的「接管 / 停止」入口，
        //   前台 App 全屏（三星设置搜索页、视频）时也必须可见（真机：设置搜索页被判全屏后岛整段消失）
        return b != null && (b.act == Act.NOTE || b.exec);
    }

    private static boolean isKeyKind(Kind k) {
        return k == Kind.ALERT_KEY || k == Kind.EXP_CONFIRM || k == Kind.EXP_FAIL || k == Kind.EXP_REMIND || k == Kind.EXP_ASK;
    }

    private Act actOf(String key) {
        if (key == null) return null;
        if (K_TASK.equals(key)) return Act.TASK;
        if (K_PICKUP.equals(key)) return Act.PICKUP;
        if (K_TODO.equals(key)) return Act.TODO;
        if (K_NOTICE.equals(key)) return Act.NOTICE;
        Bg b = mBgs.get(key);
        return b == null ? null : b.act;
    }

    private Status statusOf(String key) {
        if (K_TASK.equals(key)) return mStatus;
        Bg b = mBgs.get(key);
        return b == null ? Status.NONE : b.status;
    }

    /** 展开的是不能随手收起的卡：未裁决的确认卡。 */
    private boolean isBlockingExpanded() {
        if (mExp == null) return false;
        if (K_TASK.equals(mExp)) return mConfirmPending || mStatus == Status.WAIT;
        Bg b = mBgs.get(mExp);
        return b != null && b.status == Status.WAIT;
    }

    /** 展开的是关键事件（失败 / 提醒），全屏时保留。 */
    private boolean isAlertExpanded() {
        if (mExp == null) return false;
        if (K_NOTICE.equals(mExp)) return mNoticeAlerting && mNoticeAlertHigh;
        if (K_TASK.equals(mExp)) return mStatus == Status.FAIL;
        Bg b = mBgs.get(mExp);
        return b != null && (b.status == Status.FAIL || b.act == Act.REMIND);
    }

    private boolean anyWait() {
        if (mConfirmPending && mSheet == Sheet.COLLAPSED) return true;
        if (mAskId != null && mSheet == Sheet.COLLAPSED) return true;
        for (Bg b : mBgs.values()) if (b.status == Status.WAIT) return true;
        return false;
    }

    /** 还有后台任务在等确认、但没在展开：顶上来（确认卡永不藏起来）。 */
    private void promoteWaitingBg() {
        if (mExp != null && isBlockingExpanded()) return;
        if ((mConfirmPending || mAskId != null) && mSheet == Sheet.COLLAPSED && mMode == Mode.ISLAND) {
            mExp = K_TASK;
            return;
        }
        for (Bg b : mBgs.values()) {
            if (b.status == Status.WAIT) {
                mExp = b.id;
                return;
            }
        }
    }

    private boolean showTodoChip() {
        return mTodoChip && mTodoToday > 0;
    }

    private void clearTuck() {
        if (mTucked) {
            mTucked = false;
            mTuckSeq++;
        }
    }

    private boolean taskVisible() {
        return mSheet == Sheet.COLLAPSED && mStatus != Status.NONE;
    }

    private Pickup currentPickup() {
        if (mPickups.isEmpty()) return null;
        int i = indexOf(mPickups, mPickupId);
        return mPickups.get(i < 0 ? mPickups.size() - 1 : i);
    }

    private void clearTask() {
        clearAsk();
        mRunning = false;
        mConfirmPending = false;
        mStatus = Status.NONE;
        mSheet = Sheet.IDLE;
        mPulse = false;
        mKeyText = null;
        if (K_TASK.equals(mExp)) mExp = null;
        promoteWaitingBg();
    }

    private void timer(String name, long ms, int seq, String target) {
        mTimers.add(new Timer(name, ms, seq, target));
    }

    private static int indexOf(List<Pickup> l, String id) {
        if (id == null) return -1;
        for (int i = 0; i < l.size(); i++) if (id.equals(l.get(i).id)) return i;
        return -1;
    }

    private static List<Effect> list(Effect... e) {
        List<Effect> l = new ArrayList<Effect>();
        Collections.addAll(l, e);
        return l;
    }

    static String tail(String s, int n) {
        if (s == null) return "";
        String t = s.replace("-", "");
        return t.length() <= n ? t : t.substring(t.length() - n);
    }

    // =====================================================================
    // 文案纯函数
    // =====================================================================

    public static String stripDoing(String step) {
        String s = step == null ? "" : step.trim();
        if (s.startsWith("正在：")) s = s.substring(3);
        else if (s.startsWith("正在处理：")) s = s.substring(5);
        else if (s.startsWith("正在")) s = s.substring(2);
        return s.trim();
    }

    public static String fmtElapsed(long ms) {
        long s = Math.max(0L, ms / 1000L);
        long sec = s % 60L;
        return (s / 60L) + ":" + (sec < 10 ? "0" : "") + sec;
    }

    public static String stepLine(int n, String label) {
        String l = label == null ? "" : label.trim();
        if (n <= 0) return l.length() > 0 ? l : "准备中";
        return "第 " + n + " 步" + (l.length() > 0 ? " · " + l : "");
    }

    public static String shortTitle(String title, int maxChars) {
        String s = stripDoing(title);
        if (s.length() == 0) return "后台任务";
        int cut = s.length();
        String seps = "，,。.；;！!？? \n";
        for (int i = 0; i < s.length(); i++) {
            if (seps.indexOf(s.charAt(i)) >= 0) {
                cut = i;
                break;
            }
        }
        if (cut == 0) cut = s.length();
        s = s.substring(0, cut);
        if (maxChars > 0 && s.length() > maxChars) s = s.substring(0, maxChars);
        return s;
    }

    /** 紧凑态动作短词（≤4 字）：当前步骤去掉「正在」后的前 4 个字；没有步骤用标题。 */
    public static String actionWord(String step, String title) {
        String s = step == null || step.trim().length() == 0 ? title : step;
        return shortTitle(s, 4);
    }

    public static boolean isTap(float dx, float dy, long durationMs, float slopPx) {
        return Math.abs(dx) <= slopPx && Math.abs(dy) <= slopPx && durationMs <= 450L;
    }

    /** 横滑暂避判定：水平位移超过 thresholdPx 且大于垂直位移。 */
    public static boolean isSwipeSide(float dx, float dy, float thresholdPx) {
        return Math.abs(dx) > thresholdPx && Math.abs(dx) > Math.abs(dy);
    }

    /** 上滑收起判定。 */
    public static boolean isSwipeUp(float dx, float dy, float thresholdPx) {
        return -dy > thresholdPx && Math.abs(dy) > Math.abs(dx);
    }

    /** 「晚了 N 分钟」：触发比计划晚超过 10 分钟才显示（返回 0 = 不显示）。 */
    public static int lateMinutes(long plannedMs, long firedMs) {
        long late = firedMs - plannedMs;
        if (late <= 10L * 60L * 1000L) return 0;
        return (int) (late / 60000L);
    }
}
