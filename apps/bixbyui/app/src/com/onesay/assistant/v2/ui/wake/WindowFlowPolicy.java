package com.onesay.assistant.v2.ui.wake;

/**
 * [lane C · 窗口交互改造] 新唤醒界面的「窗口 ↔ 灵动岛」流转规则（纯函数，JVM 可测）。
 *
 * <p>用户 2026-10-22 的决定（替代原来的「固定高度窗口」方案）：
 * <ol>
 *   <li>回答时直接拉成<b>全屏窗口</b>；</li>
 *   <li>一旦转入执行任务（AgentLoop 工具调用 / 后台任务），<b>立刻</b>收进灵动岛；</li>
 *   <li>任务完成后<b>只在灵动岛上</b>反馈，不再自动回到全屏窗口（「查看」是用户自己点的，仍可流回卡片看全文）。</li>
 * </ol>
 *
 * <h3>窗口实现</h3>
 * 新界面的卡片窗从挂上起就是 {@code MATCH_PARENT × MATCH_PARENT}（固定全屏、透明），卡片的「紧凑 / 全屏」只是窗口<b>里面</b>
 * 卡片上沿的弹簧目标与子 View 的排布方向（{@link #cardFull}）。窗口尺寸从挂上到摘下都不变 → 内容变化只走进程内的
 * measure/layout，不再触发 WMS relayoutWindow + Surface 重新分配（这正是 R14.1 遗留的 50–100 ms 长帧来源）。
 *
 * <p>设置：{@code bixby_wake_ui/window}，{@code full}（默认）/ {@code wrap}（回退到 R14 的 WRAP_CONTENT 贴底窗，上机对比用）。
 */
public final class WindowFlowPolicy {

    private WindowFlowPolicy() {}

    public static final String KEY_WINDOW = "window";
    public static final String WINDOW_FULL = "full";
    public static final String WINDOW_WRAP = "wrap";

    /** 新流程下，任务开始到收进岛的延时：留一帧多给「好，我来处理」那一行排版（形变要拍卡片快照），人眼上是「立刻」。 */
    public static final long COLLAPSE_DELAY_NEW_MS = 150L;
    /** 旧界面（classic）保持 R12 的 6 s 自动收起。 */
    public static final long COLLAPSE_DELAY_CLASSIC_MS = 6000L;

    /** 岛上结果卡：主句 / 次行的最大字数（岛展开卡一行约 20 字，主句 2 行、次行 2–3 行）。 */
    public static final int HEADLINE_MAX = 40;
    public static final int DETAIL_MAX = 60;

    /** 卡片窗是否用固定全屏窗（新界面 + 设置不是 wrap）。旧界面一律 WRAP_CONTENT（行为不变）。 */
    public static boolean fullWindow(boolean newUi, String windowPref) {
        if (!newUi) return false;
        return windowPref == null || !WINDOW_WRAP.equalsIgnoreCase(windowPref.trim());
    }

    /**
     * 卡片此刻是否拉满屏幕（全屏窗模式下）。作答 / 结果 / 确认 / 打字 = 全屏；聆听 / 思考 / 任务 / 出错 = 底部紧凑卡。
     * 思考不拉满：它可能马上转成任务（收进岛），先拉满再收会多一次大幅形变。
     */
    public static boolean cardFull(WakeStage.Stage st) {
        if (st == null) return false;
        switch (st) {
            case ANSWERING:
            case RESULT:
            case CONFIRM:
            case TEXT:
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ LC-1：全屏窗的可触摸区域

    /** 卡片窗此刻哪一块收触摸。 */
    public enum TouchArea {
        /** 整个窗口（wrap 窗本来就只包住卡片；全屏窗在卡片拉满的阶段）。 */
        WINDOW,
        /** 只有卡片矩形；卡片外的点击落到后面的 App（同时照旧收到 ACTION_OUTSIDE → 收起浮层）。 */
        CARD
    }

    /**
     * ★ LC-1：全屏窗在聆听 / 思考 / 任务 / 出错（底部紧凑卡）阶段只让卡片收触摸 —— 窗口尺寸不变（不 updateViewLayout，
     * 不重新分配 Surface），只改 WMS 里这个窗口的 touchable region（ViewTreeObserver 的 internal insets）。
     * 作答 / 结果 / 确认 / 打字（卡片拉满）照旧整窗可触摸：回答全屏阶段点卡片外的留白 = 收起，维持现状。
     */
    public static TouchArea touchArea(boolean fullWindow, WakeStage.Stage st) {
        if (!fullWindow) return TouchArea.WINDOW;
        return cardFull(st) ? TouchArea.WINDOW : TouchArea.CARD;
    }

    /**
     * 卡片可触摸矩形 {l, t, r, b}（窗口坐标，向外取整）。
     *
     * @param side       卡片左右留白
     * @param cardTop    卡片上沿（取弹簧<b>目标</b>，不跟动画逐帧变 → 只在状态 / 内容真变时才更新一次 region）
     * @param bottomGap  卡片下沿到窗口底的距离（留白 + 输入法让位）
     * @return null = 算不出来（窗口还没量好）→ 调用方按整窗处理，宁可多拦也不让卡片自己点不到
     */
    public static int[] cardTouchRect(float side, float cardTop, float bottomGap, int w, int h) {
        if (w <= 0 || h <= 0) return null;
        int l = (int) Math.floor(Math.max(0f, side));
        int r = (int) Math.ceil(Math.min(w, w - side));
        int b = (int) Math.ceil(Math.min(h, h - Math.max(0f, bottomGap)));
        int t = (int) Math.floor(Math.max(0f, Math.min(cardTop, b)));
        if (r <= l || b <= t) return null;
        return new int[] {l, t, r, b};
    }

    // ------------------------------------------------------------------ LC-2：打字时给输入法让位

    /**
     * ★ LC-2：卡片窗底部要让出多少像素给输入法（overlay 窗上 adjustResize 在 Android 8.0 实测不生效：窗口没被压缩，
     * 问句 / 回答 / 输入框都排在输入法下面）。所以自己排版：卡片下沿和内容整体上移到输入法上沿之上。
     *
     * @param winBottom      卡片窗底边（屏幕坐标，不含入场平移）
     * @param visBottom      当前 getWindowVisibleDisplayFrame().bottom（WMS 对这个窗口算的可见区；输入法弹出时有的版本会变小）
     * @param baseVisBottom  输入法弹出前记下的可见区底边（导航栏上沿）
     * @param imeHeight      输入法窗口可见高度（InputMethodManager 反射；拿不到 = 0）
     * @return 让位像素（0 = 不用让：输入法没弹 / 系统已经把窗口压缩到输入法上方）
     */
    public static int imeInset(int winBottom, int visBottom, int baseVisBottom, int imeHeight) {
        if (winBottom <= 0) return 0;
        // 真机：卡片窗改成可获焦的那一下，窗口会短暂盖住导航栏（底边 2094 → 2220），可见区也跟着变大 →
        // 「输入法弹出前的底边」取见过的最大值，否则会拿旧底边减输入法高度，算出一帧偏大的让位
        if (visBottom > baseVisBottom) baseVisBottom = visBottom;
        int imeTop;
        if (baseVisBottom > 0 && visBottom > 0 && visBottom < baseVisBottom - 1) {
            imeTop = visBottom;                      // 系统给了可见区：最准
        } else if (imeHeight > 0 && baseVisBottom > 0) {
            imeTop = baseVisBottom - imeHeight;      // 输入法贴在导航栏上沿之上
        } else {
            return 0;
        }
        int inset = winBottom - imeTop;
        if (inset <= 0) return 0;                    // 窗口底已经在输入法上方（adjustResize 生效 / wrap 窗被抬高）
        return Math.min(inset, winBottom * 3 / 4);   // 防御：异常值不至于把卡片挤没
    }

    // ------------------------------------------------------------------ ★ r2/input（2026-10-24）键盘盖住输入框（LC-2 复发）

    /**
     * 要不要跟踪输入法让位。LC-2 只在 {@code stage == TEXT} 时跟踪 —— 但「⌨ 打字 → 发送 → 出结果」之后卡片窗保持可获焦，
     * 输入条也还在；用户在结果页<b>直接点输入框</b>接着打字时 stage 是 RESULT，键盘弹出却没人让位（复发的根因，见 docs/r2-input.md §3）。
     * 现在：打字阶段，或者输入条可见且输入框有焦点，都跟踪。
     */
    public static boolean trackIme(WakeStage.Stage st, boolean inputVisible, boolean inputFocused) {
        if (st == WakeStage.Stage.TEXT) return true;
        return inputVisible && inputFocused;
    }

    /**
     * 输入框拿到焦点时要不要把卡片切进打字阶段（TEXT：卡片拉满、输入条贴键盘、收音停掉）。
     * 已经是 TEXT / 正在收进岛 / 在岛上 / 空闲 = 不切。
     */
    public static boolean enterTextOnFocus(WakeStage.Stage st) {
        if (st == null) return false;
        switch (st) {
            case TEXT:
            case HANDOFF:
            case ISLAND:
            case IDLE:
                return false;
            default:
                return true;
        }
    }

    /**
     * 内容滚动区的高度上限（FloatingSheetView.onMeasure）：窗口可用高度 − 固定件（输入条 / 状态行 / 内边距，内边距已含输入法让位）。
     * 以前下限一律 96dp —— 横屏 + 键盘时剩余高度不够，96dp 的内容区把输入条挤出可视区（被键盘盖住）。
     * 键盘弹着时下限降到 {@code minWithIme}（宁可内容区很矮、可以滚，也要保证输入条和发送键在键盘上方）。
     */
    /**
     * 11「回答完成之后工具栏为什么丢上面而不是下面」）：
     * 全屏窗里出结果时卡片拉满、排布改成从上往下（{@link #cardFull} → Gravity.TOP），而结果态底栏（ChatBottomBar）是根布局最后一个子 View、
     * 内容滚动区是 WRAP_CONTENT —— 底栏就紧跟在正文下面，答案短时停在屏幕中上部。
     * 规则：全屏窗 + 底栏在卡片上显示 + 卡片拉满（不含打字阶段）→ 内容区改成 (0, weight=1) 吃满剩余高度，底栏落到卡片底
     * （= 屏幕底减导航让位；键盘弹出时根布局底部内边距 + 键盘高，底栏贴键盘上沿）。聆听卡 / 思考 / 出错紧凑卡 / 打字阶段排布不变；
     * 对话窗由 ChatWindowController.enter 自己把内容区设成 weight=1（同一几何）。
     */
    public static boolean pinBarBottom(WakeStage.Stage st, boolean v2, boolean fullWindow, boolean barShown) {
        if (!v2 || !fullWindow || !barShown || st == null) return false;
        return cardFull(st) && st != WakeStage.Stage.TEXT;
    }

    /**
     * 竖直 LinearLayout 里底栏上沿的位置（窗口坐标，纯算术，JVM 锁几何）：
     * 贴底 → winH − padBottom − barBottomMargin − barH（padBottom = 卡片底留白 + 导航 / 键盘让位）；
     * 不贴底且从上往下排 → padTop + 前面固定件 + 内容区实际高度 + barTopMargin（内容区不超过剩余高度）；
     * 不贴底且贴底排（Gravity.BOTTOM）→ 同贴底。
     */
    public static int barTop(int winH, int padTop, int padBottom, int fixedAbove, int contentNatural, int barH, int barTopMargin,
                             int barBottomMargin, boolean pinned, boolean gravityTop) {
        int bottomPos = winH - padBottom - barBottomMargin - barH;
        if (pinned || !gravityTop) return bottomPos;
        int room = Math.max(0, winH - padTop - padBottom - fixedAbove - barTopMargin - barH - barBottomMargin);
        return padTop + fixedAbove + Math.min(contentNatural, room) + barTopMargin;
    }

    public static int contentCap(int avail, int chrome, int imeInset, int minNormal, int minWithIme) {
        int min = imeInset > 0 ? minWithIme : minNormal;
        return Math.max(min, avail - chrome);
    }

    /**
     * 回归护栏用的几何检查：键盘弹出后，输入条底边（窗口坐标）是否在输入法上沿之上。
     *
     * @param winTopOnScreen 窗口顶在屏幕上的 y
     * @param inputBottomInWin 输入条底边（窗口坐标）
     * @param imeTopOnScreen 输入法上沿（屏幕坐标）
     */
    public static boolean inputAboveIme(int winTopOnScreen, int inputBottomInWin, int imeTopOnScreen) {
        return winTopOnScreen + inputBottomInWin <= imeTopOnScreen;
    }

    /** 任务开始后多久收进岛。 */
    public static long collapseDelayMs(boolean newUi) {
        return newUi ? COLLAPSE_DELAY_NEW_MS : COLLAPSE_DELAY_CLASSIC_MS;
    }

    /** 收在岛上的任务收工后，是否只在岛上反馈（不自动挂回卡片）。旧界面保持「长结论挂回面板」。 */
    public static boolean islandOnlyAfterTask(boolean newUi) {
        return newUi;
    }

    /**
     * [r2/bike-chooser ⑤] 同上，但结论带着<b>卡片上才有的可点内容</b>（目前是大模型给的应用网格 app_choice）时不能只在岛上反馈：
     * 岛上只放得下一句话，网格画在卡片里 —— 只进岛 = 用户根本看不到要点的格子（C1 16b3d3b 之后的回归）。这时走「挂回卡片」。
     */
    public static boolean islandOnlyAfterTask(boolean newUi, boolean needsCard) {
        return islandOnlyAfterTask(newUi) && !needsCard;
    }

    /**
     * 岛上结果卡的 {主句, 次行}（任意长度的结论都能放下：超长截断加省略号，全文点「查看」看）。
     *
     * @param answer 结论；"alarm" = 闹钟卡标记
     * @return null = 没有可显示的结论（调用方走「对勾 1.8 s」）
     */
    public static String[] islandSummary(String answer) {
        if (answer == null) return null;
        String a = answer.trim();
        if (a.length() == 0) return null;
        if ("alarm".equals(a)) return new String[] {"闹钟已设好", "点「查看」看闹钟卡片"};
        String[] hb = IslandResultPolicy.split(a);
        String head = hb[0];
        String detail = hb[1];
        if (head == null || head.trim().length() == 0) {
            head = detail;
            detail = null;
        }
        if (head == null) return null;
        head = clip(oneLine(head), HEADLINE_MAX);
        detail = detail == null ? null : clip(oneLine(detail), DETAIL_MAX);
        if (detail != null && detail.length() == 0) detail = null;
        return new String[] {head, detail};
    }

    // ------------------------------------------------------------------ 卡片正文

    /** 卡片正文区该显示什么。 */
    public enum Body {
        /** 不显示正文（结果卡收起）。 */
        NONE,
        /** 「正在想…」占位。 */
        THINKING_PLACEHOLDER,
        /** 流式中的正文（未定稿）。 */
        STREAM,
        /** 定稿正文（首行大字）。 */
        FINAL,
        /** 「好，我来处理」任务行。 */
        TASK
    }

    /**
     * 每个状态下正文区显示什么（FloatingSheetView.v2Render 的唯一裁决点）。
     *
     * <p>★ 修复（用户报告）：生成完成后点 ⌨ 输入新内容，上一条回答被「刷新掉」—— 根因是 TEXT 状态没有正文分支，
     * 结果卡直接 GONE。现在：TEXT 状态有定稿正文就继续显示（新问题提交后 beginAskStream 才换成新一轮）。
     */
    public static Body body(WakeStage.Stage st, boolean hasBody, boolean isFinal) {
        if (st == null) return Body.NONE;
        switch (st) {
            case THINKING:
                return hasBody ? Body.STREAM : Body.THINKING_PLACEHOLDER;
            case ANSWERING:
                return Body.STREAM;
            case RESULT:
                return hasBody ? Body.FINAL : Body.NONE;
            case TASK:
                return Body.TASK;
            case CONFIRM:
            case TEXT:
                return hasBody && isFinal ? Body.FINAL : Body.NONE;
            default:
                return Body.NONE;
        }
    }

    private static String oneLine(String s) {
        return s.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s{2,}", " ").trim();
    }

    /**
     * ★ integrate（2026-10-24 上机）：Activity 自己给输入法让位的底部内边距。
     * 用户手机全局开了沉浸式（policy_control immersive.full=*），这种窗口下 adjustResize 不生效，
     * 控制台 / 跑腿接手页的底部按钮会被键盘盖住。按「内容区底边 − 可见区底边」补内边距；
     * 不到屏高 15% 的差值（导航条、手势条）当成没有键盘；adjustResize 正常生效时差值为 0，不会重复让位。
     */
    public static int activityImePad(int contentBottomOnScreen, int visibleBottom, int screenH) {
        if (screenH <= 0) return 0;
        int overlap = contentBottomOnScreen - visibleBottom;
        if (overlap < screenH * 15 / 100) return 0;
        return Math.min(overlap, screenH * 85 / 100);
    }

    private static String clip(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
