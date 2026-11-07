package com.onesay.assistant.v2.jobs;

import java.util.Set;

/**
 * 权限卡的勾选规则（2026-10-23，用户原始需求「逐项勾选才入库」）。纯 Java，JVM 可测；界面在 {@code ui.PermissionCardActivity}。
 * <ul>
 *   <li>可以预先授权的项<b>默认都不勾</b>——用户自己逐项勾；</li>
 *   <li>卡上有可预先授权的项时，至少勾一项「确认授权」才可用；卡上没有可勾的项（任务不需要额外权限 / 只有每次确认的项）时直接可用；</li>
 *   <li>只勾一部分照现有逻辑：没授权的步骤到点进入「等你确认」（{@link PermissionGuard}）；</li>
 *   <li>按钮的状态要看得出来：不可用时变灰 + 文案「先勾选要授权的工具」，可用时写明已选几项，下面一行说明没勾的会怎样。</li>
 * </ul>
 */
public final class PermissionCardPolicy {

    private PermissionCardPolicy() {}

    /** 按钮可用 / 不可用时的不透明度（不可用 = 明显变灰）。 */
    public static final float ALPHA_ON = 1f, ALPHA_OFF = 0.38f;

    /** 每一项打开卡片时的初始勾选：一律不勾。 */
    public static boolean defaultChecked(Perm p) {
        return false;
    }

    static int checkedCount(Set<Perm> grantable, Set<Perm> checked) {
        int n = 0;
        if (grantable == null || checked == null) return 0;
        for (Perm p : checked) if (grantable.contains(p)) n++;
        return n;
    }

    /** 「确认授权」可不可以点。 */
    public static boolean confirmEnabled(Set<Perm> grantable, Set<Perm> checked) {
        if (grantable == null || grantable.isEmpty()) return true;
        return checkedCount(grantable, checked) > 0;
    }

    /** 按钮文案。 */
    public static String confirmLabel(Set<Perm> grantable, Set<Perm> checked) {
        if (grantable == null || grantable.isEmpty()) return "确认创建";
        int k = checkedCount(grantable, checked), n = grantable.size();
        if (k == 0) return "先勾选要授权的工具";
        return "确认授权（已选 " + k + "/" + n + "）";
    }

    /** 按钮下面的一行说明。 */
    public static String hint(Set<Perm> grantable, Set<Perm> checked) {
        if (grantable == null || grantable.isEmpty()) return "这条任务没有需要预先授权的工具。";
        int k = checkedCount(grantable, checked), n = grantable.size();
        if (k == 0) return "默认都不勾。至少勾选一项，「确认授权」才可以点。";
        if (k < n) return "没勾的 " + (n - k) + " 项不会预先授权：任务用到时会停下来，在「等你确认」里等你。";
        return "已全部勾选：这些工具任务运行时可以直接用。";
    }
}
