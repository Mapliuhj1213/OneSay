package com.onesay.assistant.v2.executor;

/**
 * 单步确认卡的挂起状态（F2，从 FloatingSheetService 抽出，纯 Java 可 JVM 单测）。
 *
 * <p>旧实现：弹卡时只记原始指令 {@code mPendingConfirmQuery}，用户点「允许」后以
 * {@code executeInternal(cap, args, isConfirmed=true, …)} 执行 —— 确认与具体 (能力, 参数) 没有绑定。
 * 新实现：弹卡时同时保存 {@link ExecutionGate#evaluate} 签发的一次性令牌；
 * 「允许」= {@link ExecutionGate#approve} 后凭令牌执行，「拒绝」= {@link ExecutionGate#reject}。
 * 卡片回传的 (能力, 参数) 若与令牌不符，执行层会退回"需要确认"，绝不执行。
 *
 * <p>线程：弹卡与点卡都在主线程，但方法仍加锁，防止取消路径并发清理。
 */
public final class PendingConfirm {

    /** {@link #allow()} 的结果。 */
    public static final class Approved {
        /** 已批准的令牌；null = 没有挂起的卡或令牌已过期/失效（调用方应提示重新发起，不得执行）。 */
        public final ExecutionGate.ConfirmToken token;
        /** 弹卡时的原始指令（执行回执写回会话用），可能为 null。 */
        public final String query;

        Approved(ExecutionGate.ConfirmToken token, String query) {
            this.token = token;
            this.query = query;
        }

        public boolean ok() { return token != null; }
    }

    private ExecutionGate.ConfirmToken mToken;
    private String mQuery;

    /** 弹卡时调用：保存令牌与原始指令。若上一张卡还挂着，旧令牌作废（拒绝）。 */
    public synchronized void hold(ExecutionGate.Decision decision, String query) {
        if (mToken != null && (decision == null || decision.token != mToken)) ExecutionGate.reject(mToken);
        mToken = decision != null ? decision.token : null;
        mQuery = query;
    }

    /** 用户点「允许单次 / 始终允许」：批准并取走令牌（只能取一次）。 */
    public synchronized Approved allow() {
        ExecutionGate.ConfirmToken t = mToken;
        String q = mQuery;
        mToken = null;
        mQuery = null;
        if (t == null || !ExecutionGate.approve(t)) {
            if (t != null) ExecutionGate.reject(t);
            return new Approved(null, q);
        }
        return new Approved(t, q);
    }

    /** 用户点「拒绝」/ 卡片被收起：作废令牌。@return 原始指令（可能为 null） */
    public synchronized String reject() {
        String q = mQuery;
        if (mToken != null) ExecutionGate.reject(mToken);
        mToken = null;
        mQuery = null;
        return q;
    }

    public synchronized boolean isHolding() {
        return mToken != null;
    }
}
