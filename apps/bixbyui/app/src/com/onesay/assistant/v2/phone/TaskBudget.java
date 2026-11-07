package com.onesay.assistant.v2.phone;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ r2/fast-screen：任务预算（纯 Java，JVM 可测）—— 不再死卡「动作步数」，而是按：
 * <ol>
 *   <li><b>模型调用次数</b>（每次调用可以排 1–20 步，多步不多算）：默认 {@link #DEF_MODEL_CALLS}；</li>
 *   <li><b>总耗时</b>（用户接手 / 确认的时间不计）：默认 {@link #DEF_TOTAL_MS}；</li>
 *   <li><b>无进展</b>：连续 {@link #NO_PROGRESS_TURNS} 次模型回合后界面签名都没变；</li>
 *   <li><b>重复动作</b>：同一个动作（同编号同文字）在界面没变的情况下连做 {@link #REPEAT_LIMIT} 次。</li>
 * </ol>
 * 前两项到了 → 暂停问用户「还要继续吗」（继续就再给 {@link #EXTEND_CALLS} 次 / {@link #EXTEND_MS}）；
 * 后两项 → 判定卡住，暂停求助用户（不是直接失败）。
 * 另有 {@link #HARD_ACTIONS} 的总动作安全上限，只防失控，不是常规限制。
 */
public final class TaskBudget {

    public static final int DEF_MODEL_CALLS = 30;
    public static final long DEF_TOTAL_MS = 8 * 60_000L;
    public static final int NO_PROGRESS_TURNS = 3;
    public static final int REPEAT_LIMIT = 3;
    public static final int EXTEND_CALLS = 15;
    public static final long EXTEND_MS = 4 * 60_000L;
    public static final int HARD_ACTIONS = 300;
    /**
     * ★ fast-screen-v3（2026-10-31 产品决定）：无人值守（定时任务等，没人能回答「还要继续吗」）保持原来的成本 ——
     * 最多 {@link #UNATTENDED_MODEL_CALLS} 次模型调用 / {@link #UNATTENDED_TOTAL_MS}；到上限直接收尾、结果注明 {@link #UNFINISHED}，不续、不问。
     */
    public static final int UNATTENDED_MODEL_CALLS = 8;
    public static final long UNATTENDED_TOTAL_MS = 3 * 60_000L;
    /** 无人值守任务到上限收尾时，结果里带的标记。 */
    public static final String UNFINISHED = "未完成";

    public enum Verdict { OK, LIMIT, STUCK, HARD_STOP }

    public int maxCalls = DEF_MODEL_CALLS;
    public long maxMs = DEF_TOTAL_MS;
    public int calls;
    public int actions;
    public long pausedMs;
    private final long mStart;
    private final List<String> mSigs = new ArrayList<String>();
    private String mLastActionSig = "";
    private int mSameAction;
    public String reason = "";
    /** true = 无人值守预算：到上限不问用户、不续，直接收尾并注明「未完成」。 */
    public boolean unattended;

    public TaskBudget(long nowMs) {
        mStart = nowMs;
    }

    /** 有人值守：30 次调用 / 8 分钟，到上限先问用户。 */
    public static TaskBudget attended(long nowMs) {
        return new TaskBudget(nowMs);
    }

    /** 无人值守：8 次调用 / 3 分钟，到上限收尾（{@link #canAskUser} = false）。 */
    public static TaskBudget unattended(long nowMs) {
        TaskBudget b = new TaskBudget(nowMs);
        b.maxCalls = UNATTENDED_MODEL_CALLS;
        b.maxMs = UNATTENDED_TOTAL_MS;
        b.unattended = true;
        return b;
    }

    public static TaskBudget forTask(boolean unattended, long nowMs) {
        return unattended ? unattended(nowMs) : attended(nowMs);
    }

    /** 这个判定能不能停下来问用户（无人值守 / 安全上限 = 不能）。 */
    public boolean canAskUser(Verdict v) {
        return !unattended && v != Verdict.OK && v != Verdict.HARD_STOP;
    }

    /** 无人值守收尾时的原因文字：「未完成：已经问了模型 8 次」。 */
    public String unfinishedReason() {
        return UNFINISHED + "：" + reason;
    }

    public long elapsed(long nowMs) {
        return nowMs - mStart - pausedMs;
    }

    public void onModelCall() {
        calls++;
    }

    /** 每个模型回合结束时记一下界面签名。 */
    public void onTurn(String screenSig) {
        mSigs.add(screenSig == null ? "" : screenSig);
    }

    /** 每执行一步记一下（动作签名 + 动作前后界面是否变化）。 */
    public void onAction(String actionSig, boolean screenChanged) {
        actions++;
        if (!screenChanged && actionSig.equals(mLastActionSig)) mSameAction++;
        else mSameAction = screenChanged ? 0 : 1;
        mLastActionSig = actionSig;
    }

    public void extend() {
        if (unattended) return;   // 无人值守不续
        maxCalls = calls + EXTEND_CALLS;
        maxMs = maxMs + EXTEND_MS;
        mSigs.clear();
        mSameAction = 0;
    }

    /** 用户接手 / 补充后：重新计无进展。 */
    public void resetProgress() {
        mSigs.clear();
        mSameAction = 0;
        mLastActionSig = "";
    }

    public Verdict check(long nowMs) {
        if (actions >= HARD_ACTIONS) {
            reason = "已执行 " + actions + " 个动作（安全上限）";
            return Verdict.HARD_STOP;
        }
        if (mSameAction >= REPEAT_LIMIT) {
            reason = "同一个动作连做了 " + mSameAction + " 次，界面都没变化";
            return Verdict.STUCK;
        }
        int n = mSigs.size();
        if (n >= NO_PROGRESS_TURNS + 1) {
            boolean same = true;
            String last = mSigs.get(n - 1);
            for (int i = n - 1 - NO_PROGRESS_TURNS; i < n - 1; i++) same &= mSigs.get(i).equals(last);
            if (same) {
                reason = "连续 " + NO_PROGRESS_TURNS + " 轮界面没有任何变化";
                return Verdict.STUCK;
            }
        }
        if (calls >= maxCalls) {
            reason = "已经问了模型 " + calls + " 次";
            return Verdict.LIMIT;
        }
        if (elapsed(nowMs) >= maxMs) {
            reason = "已经做了 " + (elapsed(nowMs) / 1000) + " 秒";
            return Verdict.LIMIT;
        }
        return Verdict.OK;
    }

    public String info(long nowMs) {
        return "已用模型 " + calls + "/" + maxCalls + " 次、" + (elapsed(nowMs) / 1000) + "/" + (maxMs / 1000) + " 秒、动作 " + actions + " 个";
    }
}
