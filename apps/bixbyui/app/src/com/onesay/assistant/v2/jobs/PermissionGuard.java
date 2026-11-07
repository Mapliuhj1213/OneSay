package com.onesay.assistant.v2.jobs;

import com.onesay.assistant.v2.capability.SideEffect;

/**
 * 运行时权限闸（lane-a 第 3 项）。纯 Java，JVM 可测。
 *
 * <ul>
 *   <li>不需要权限的调用（纯回答）→ ALLOW；</li>
 *   <li>发出邮件 / 付款 / 红卡（{@link Perm#preGrantable} = false）→ CONFIRM_EACH_TIME：哪怕授权表里写了也不算，必须本次在卡片上确认
 *       （{@code confirmedThisRun} = 本次运行里用户刚在卡上点了允许）；</li>
 *   <li>需要的权限不在授权里 → DENY_UNGRANTED：<b>不执行</b>，任务停在「等确认」并在岛上提示；</li>
 *   <li>没有授权记录（旧任务 / 改动后指纹不符）→ DENY_NO_GRANT：同样不执行，要重新授权。</li>
 * </ul>
 */
public final class PermissionGuard {

    private PermissionGuard() {}

    public enum Verdict { ALLOW, CONFIRM_EACH_TIME, DENY_UNGRANTED, DENY_NO_GRANT }

    /**
     * ★ feat/shortcuts：无人值守回调（S3Runner）提供的「只过闸、不执行」入口。快捷指令的内部动作不经 UnifiedExecutor，
     * 执行前先调它：返回非 null = 被拦下（没授权 → 停在等确认；要每次确认 / 红卡 → 岛上没允许），不执行这一步。
     */
    public interface Preflight {
        com.onesay.assistant.v2.executor.ExecutionResult preflight(com.onesay.assistant.v2.capability.Capability cap, org.json.JSONObject args,
                                                          com.onesay.assistant.v2.executor.ExecutionGate.ConfirmToken token);
    }

    public static final class Decision {
        public final Verdict verdict;
        public final Perm perm;

        public Decision(Verdict v, Perm p) {
            verdict = v;
            perm = p;
        }

        public boolean runs() {
            return verdict == Verdict.ALLOW;
        }

        @Override public String toString() {
            return verdict + (perm == null ? "" : "(" + perm.name() + ")");
        }
    }

    /**
     * @param grant            这条任务的授权（null = 没有 / 已失效）
     * @param confirmedThisRun 本次运行里，用户已在卡片上对这一步点了允许
     */
    public static Decision check(String sign, SideEffect effect, TaskGrant grant, boolean confirmedThisRun) {
        Perm p = PermissionAnalyzer.forTool(sign, effect);
        if (p == null) return new Decision(Verdict.ALLOW, null);
        if (!p.preGrantable) {
            return new Decision(confirmedThisRun ? Verdict.ALLOW : Verdict.CONFIRM_EACH_TIME, p);
        }
        if (grant == null) return new Decision(Verdict.DENY_NO_GRANT, p);
        return new Decision(grant.granted.contains(p) ? Verdict.ALLOW : Verdict.DENY_UNGRANTED, p);
    }

    /** 岛上 / 通知里的提示文字。 */
    public static String hint(Decision d, String taskTitle) {
        String t = taskTitle == null || taskTitle.length() == 0 ? "定时任务" : "「" + taskTitle + "」";
        switch (d.verdict) {
            case DENY_UNGRANTED: return t + "要用「" + d.perm.label + "」，但创建时没授权，已暂停，点开重新授权";
            case DENY_NO_GRANT: return t + "需要重新授权才能运行（任务改过或是旧任务）";
            case CONFIRM_EACH_TIME: return t + "要「" + d.perm.label + "」，这类操作每次都要你确认";
            default: return t;
        }
    }
}
