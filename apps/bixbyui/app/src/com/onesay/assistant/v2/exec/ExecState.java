package com.onesay.assistant.v2.exec;

/**
 * ★ r2/fast-screen：执行模式（灵动岛取代小窗）的状态机（纯 Java，JVM 可测）。
 *
 * <pre>
 *   RUNNING ──接管 / 返回键 / 主页键──▶ TAKEOVER（护罩撤掉、光晕变细边框、岛显示「继续 / 停止」）
 *   RUNNING ──补充──▶ SUPPLEMENT（同上，另弹输入框；提交 = 带着补充继续）
 *   RUNNING ──敏感步骤 / 模型提问──▶ CONFIRM（护罩保留，岛展开确认卡；允许 / 拒绝）
 *   TAKEOVER / SUPPLEMENT ──继续──▶ RUNNING（重新读屏后恢复）
 *   任意 ──停止──▶ ENDED（撤掉一切覆盖层）；RUNNING ──完成──▶ ENDED
 * </pre>
 * 护罩的规则只看状态：RUNNING / CONFIRM = 全屏护罩（吞误触）+ 光晕；TAKEOVER / SUPPLEMENT = 细边框、不拦触摸；ENDED = 都没有。
 */
public final class ExecState {

    public enum S { IDLE, RUNNING, CONFIRM, TAKEOVER, SUPPLEMENT, ENDED }

    public enum Shield { NONE, FULL, THIN }

    private S mS = S.IDLE;
    /** 接手原因（给代理主循环：takeoverRequested 取一次就清）。 */
    private String mPendingTakeover;
    private String mSupplement;
    private boolean mStop;
    /**
     * ★ fast-screen-v3 真机修正：用户在代理主循环取走接手请求<b>之前</b>就点了继续 / 提交了补充
     * （真机：补充 5 s 后提交，代理还在等上一步的条件）→ 代理随后取到「补充」时不该再暂停一次。
     */
    private boolean mResumedEarly;
    /** 统计：误触次数、按导航键接手次数。 */
    public int strayTouches, navTakeovers;

    public synchronized S state() {
        return mS;
    }

    public synchronized Shield shield() {
        switch (mS) {
            case RUNNING:
            case CONFIRM:
                return Shield.FULL;
            case TAKEOVER:
            case SUPPLEMENT:
                return Shield.THIN;
            default:
                return Shield.NONE;
        }
    }

    public synchronized boolean start() {
        if (mS != S.IDLE && mS != S.ENDED) return false;
        mS = S.RUNNING;
        mStop = false;
        mPendingTakeover = null;
        mSupplement = null;
        mResumedEarly = false;
        return true;
    }

    /** 岛上「接管」、返回键、主页键。返回 true = 状态变了（调用方更新护罩 / 岛）。 */
    public synchronized boolean takeover(String why) {
        if (mS != S.RUNNING && mS != S.CONFIRM) return false;
        if (mS == S.CONFIRM) return false;   // 确认卡开着时等用户在卡上选，不叠加接手
        mS = S.TAKEOVER;
        mPendingTakeover = why;
        mResumedEarly = false;
        return true;
    }

    public synchronized boolean navKey(String which) {
        boolean ch = takeover(which);
        if (ch) navTakeovers++;
        return ch;
    }

    public synchronized boolean supplement() {
        if (mS != S.RUNNING && mS != S.TAKEOVER) return false;
        mS = S.SUPPLEMENT;
        if (mPendingTakeover == null) mPendingTakeover = "补充";
        mResumedEarly = false;
        return true;
    }

    public synchronized void supplementText(String t) {
        mSupplement = t;
    }

    /** 代理主循环取接手请求（取后清零）。 */
    public synchronized String takePendingTakeover() {
        String t = mPendingTakeover;
        mPendingTakeover = null;
        return t;
    }

    public synchronized String takeSupplement() {
        String t = mSupplement;
        mSupplement = null;
        return t;
    }

    /** 「继续」（岛按钮 / 补充提交）。 */
    public synchronized boolean resume() {
        if (mS != S.TAKEOVER && mS != S.SUPPLEMENT) return false;
        mS = S.RUNNING;
        if (mPendingTakeover != null) mResumedEarly = true;   // 代理还没取走这次接手：它取走时直接带补充继续
        return true;
    }

    /** 代理取到接手请求时：用户是否已经点过继续（取后清零）。 */
    public synchronized boolean consumeResumedEarly() {
        boolean r = mResumedEarly;
        mResumedEarly = false;
        return r;
    }

    public synchronized boolean paused() {
        return mS == S.TAKEOVER || mS == S.SUPPLEMENT;
    }

    public synchronized boolean confirmBegin() {
        if (mS != S.RUNNING) return false;
        mS = S.CONFIRM;
        return true;
    }

    public synchronized void confirmEnd() {
        if (mS == S.CONFIRM) mS = S.RUNNING;
    }

    public synchronized void stop() {
        mStop = true;
        mS = S.ENDED;
    }

    public synchronized boolean stopped() {
        return mStop;
    }

    public synchronized void end() {
        mS = S.ENDED;
    }

    /** 护罩吞了一次误触；返回是否该提示（每 {@link #HINT_GAP_MS} 最多一次）。 */
    public static final long HINT_GAP_MS = 3000L;
    private long mLastHint = -HINT_GAP_MS;

    public synchronized boolean onStrayTouch(long nowMs) {
        strayTouches++;
        if (nowMs - mLastHint < HINT_GAP_MS) return false;
        mLastHint = nowMs;
        return true;
    }
}
