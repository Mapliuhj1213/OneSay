package com.onesay.plan.core;

/**
 * Plan 数据接口（PlanProvider）的门卫（纯 Java）。Provider 每次读写都问它，参数由 Android 层查好再传进来。
 *
 * <p>开源版规则：<b>只认与 Plan 同一证书签名的调用方</b>。
 * <ol>
 *   <li>自己（同 uid）→ 放行；</li>
 *   <li>持有 signature 级权限 {@link Contract#PERMISSION}（Android 只授予同证书的包）→ 放行；</li>
 *   <li>调用方与 Plan 签名一致（PackageManager.checkSignatures == SIGNATURE_MATCH，兜底「先装调用方、后装 Plan」
 *       时 Android 不补授 signature 权限的情况）→ 放行；</li>
 *   <li>root（uid 0）与 adb shell（uid 2000）不再有特殊通道，一律拒绝；</li>
 *   <li>其他一律拒绝（SecurityException）。</li>
 * </ol>
 * 不内置任何证书指纹：谁用自己的 keystore 把三个 App 签成同一证书，谁的三个 App 就能互通。
 */
public final class AccessPolicy {
    private AccessPolicy() {}

    public static final int ROOT_UID = 0, SHELL_UID = 2000;

    /** 门卫判定结果；{@link #DENIED} 以外都放行。 */
    public enum Who { SELF, PERMISSION, SAME_SIGNER, DENIED }

    /**
     * 判定一次调用。
     *
     * @param callingUid      Binder 调用方 uid
     * @param ownUid          Plan 自己的 uid
     * @param holdsPermission 调用方是否持有 {@link Contract#PERMISSION}
     * @param sameSigner      调用方是否与 Plan 同证书签名（checkSignatures 结果）
     */
    public static Who decide(int callingUid, int ownUid, boolean holdsPermission, boolean sameSigner) {
        if (callingUid == ownUid) return Who.SELF;
        if (callingUid == ROOT_UID || callingUid == SHELL_UID) return Who.DENIED;   // 无 root / adb 调试通道
        if (holdsPermission) return Who.PERMISSION;
        if (sameSigner) return Who.SAME_SIGNER;
        return Who.DENIED;
    }

    /** 是否放行。 */
    public static boolean allowed(Who w) { return w != Who.DENIED; }

    /** OneSay 的包名前缀（release com.onesay.assistant / debug com.onesay.assistant.debug）。 */
    public static final String BIXBYUI_PKG_PREFIX = "com.onesay.assistant";

    /** 插入行时没给 source 的默认值：自己界面 ui / OneSay bixbyui / 其他同签名 App other。 */
    public static String source(Who who, String pkg) {
        if (who == Who.SELF) return "ui";
        return pkg != null && pkg.startsWith(BIXBYUI_PKG_PREFIX) ? "bixbyui" : "other";
    }

    /** runs 表 source：调用方包名是 OneSay 记 bixbyui，否则记 root（历史取值，数据格式的一部分）。 */
    public static String runSource(String byPkg) {
        return byPkg != null && byPkg.startsWith(BIXBYUI_PKG_PREFIX) ? "bixbyui" : "root";
    }
}
