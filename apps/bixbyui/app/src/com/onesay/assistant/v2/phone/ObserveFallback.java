package com.onesay.assistant.v2.phone;

/**
 * [fix-1123-dexec B7] 读屏助手（Shizuku / root）读到的树不完整时退回无障碍读屏（纯逻辑，JVM 可测）。
 *
 * <p>真机（魅族，执行模式）：助手 243 次读屏 partial、只 5 次 ok，智能体说「控件树只有状态栏图标」；同一页不在执行模式时读得全。
 * 判断：护罩 / 灵动岛这些 OneSay 悬浮窗盖上后，助手读到的是最上层窗口（包名为空或是 OneSay 自己）；无障碍读的是被操作 App 的窗口。
 * 规则：助手这次的树「可疑」（质量不是 ok，或包名为空 / 是 OneSay 自己）且无障碍连着 → 再用无障碍读一次，更好就用无障碍的。</p>
 */
public final class ObserveFallback {

    private ObserveFallback() {}

    /** 助手这次读到的树值不值得再用无障碍读一次。 */
    /** [exec-1124 B] 执行模式下读到的元素少于这个数就记一条日志。 */
    public static final int THIN_ELEMENTS = 8;

    /**
     * [exec-1124 B] 执行模式（护罩 / 光晕挂着）下这次读屏明显过少：元素不到 {@link #THIN_ELEMENTS} 个，或者跳过的「看不见」节点
     * 比读到的多两倍以上（多半是被悬浮窗盖住、被系统标成看不见了）。只用来记日志，不改读屏行为。纯函数。
     */
    public static boolean thinInExec(boolean execMode, int elements, int hidden) {
        if (!execMode) return false;
        return elements < THIN_ELEMENTS || hidden >= 10 && hidden > elements * 2;
    }

    public static boolean suspect(String quality, String pkg, String selfPkg) {
        if (!UiTree.Q_OK.equals(quality)) return true;
        if (pkg == null || pkg.length() == 0) return true;
        return isSelf(pkg, selfPkg);
    }

    static boolean isSelf(String pkg, String selfPkg) {
        if (pkg == null) return false;
        return (selfPkg != null && selfPkg.equals(pkg)) || pkg.startsWith("com.onesay.");
    }

    static int rank(String q) {
        if (UiTree.Q_OK.equals(q)) return 2;
        if (UiTree.Q_PARTIAL.equals(q)) return 1;
        return 0;
    }

    /** 无障碍这份是不是更好：质量更高；同质量时包名不是自己、元素更多。 */
    public static boolean preferA11y(String hq, String hpkg, int hels, String aq, String apkg, int aels, String selfPkg) {
        if (aq == null || aels <= 0) return false;
        if (isSelf(apkg, selfPkg) && !isSelf(hpkg, selfPkg)) return false;
        int hr = rank(hq), ar = rank(aq);
        if (ar != hr) return ar > hr;
        boolean hSelf = hpkg == null || hpkg.length() == 0 || isSelf(hpkg, selfPkg);
        boolean aSelf = apkg == null || apkg.length() == 0 || isSelf(apkg, selfPkg);
        if (hSelf != aSelf) return !aSelf;
        return aels > hels;
    }
}
