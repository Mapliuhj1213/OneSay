package com.onesay.assistant.v2.phone;

/**
 * [fix-1123-dexec B40] 代操作先看现状再动手（纯逻辑，JVM 可测）。
 *
 * <p>真机（魅族）：无障碍已开、美团已经在前台（之前停在拼好饭页），代操作还是按头开始走（重新拉起、回首页、一步步点），最后没完成。
 * 现在：①目标 App 已经在前台就不重新拉起（拉起可能把它带回首页）；②给模型的每一轮都带一句「开局现状」：前台是谁、在哪一页，
 * 要它先对照任务看这一屏，已经满足的步骤跳过，从当前位置接着走，每步后判断任务是不是已经完成。</p>
 *
 * <p>另：同一轮排查的结论（用户 19:17 怀疑护罩挡了代操作自己的点按）：属实，见 {@link com.onesay.assistant.v2.exec.ShieldHoles}。</p>
 */
public final class StateFirst {

    private StateFirst() {}

    /** 目标 App 已经在前台 → 不重新拉起。 */
    public static boolean skipLaunch(String foregroundPkg, String pkg) {
        return foregroundPkg != null && pkg != null && pkg.length() > 0 && foregroundPkg.equals(pkg);
    }

    /** 给模型的「开局现状」一句（screen 为空 = 读不到，不写）。 */
    public static String note(UiTree screen, boolean launchSkipped) {
        if (screen == null) return "";
        String pkg = screen.pkg == null || screen.pkg.length() == 0 ? "（没读到包名）" : screen.pkg;
        StringBuilder b = new StringBuilder("开局现状：前台是 ").append(pkg);
        if (screen.activity != null && screen.activity.length() > 0) b.append("（页面 ").append(shortAct(screen.activity)).append("）");
        if (launchSkipped) b.append("，它本来就在前台，没有重新打开");
        b.append("。先对照任务看当前这一屏和上面的开关状态：已经做到的步骤直接跳过，从当前页面接着做，不要回首页从头来；")
                .append("每做完一步都对照任务判断是不是已经完成，完成了就直接结束。");
        return b.toString();
    }

    static String shortAct(String act) {
        int k = act.lastIndexOf('.');
        return k >= 0 && k < act.length() - 1 ? act.substring(k + 1) : act;
    }
}
