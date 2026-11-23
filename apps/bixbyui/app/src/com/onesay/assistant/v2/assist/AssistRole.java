package com.onesay.assistant.v2.assist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ★ feat/assist-role：「设为默认助理」的判断与跳转顺序。纯 Java，JVM 可测（Android 部分在 {@link AssistRoleHost}）。
 *
 * <p>跳转顺序（前一步打不开就试下一步）：</p>
 * <ol>
 *   <li>Android 10+ 且系统有「助理」角色、我们还没拿到：用 RoleManager 请求（反射调，不改 compileSdk）。
 *       原生系统把这个角色标成「不能由 App 请求」，请求页会立刻自己关掉 —— 回到我们这里时如果
 *       {@link #bounced} 判定是「秒回且还没设上」，自动接着打开下一步；</li>
 *   <li>系统「语音输入 / 助理」设置页（ACTION_VOICE_INPUT_SETTINGS）；</li>
 *   <li>系统「默认应用」页；</li>
 *   <li>本 App 的应用信息页（最后兜底）。</li>
 * </ol>
 */
public final class AssistRole {

    private AssistRole() {}

    /** RoleManager.ROLE_ASSISTANT 的值（API 29 起有；这里写字面量，不依赖高版本 SDK）。 */
    public static final String ROLE_ASSISTANT = "android.app.role.ASSISTANT";
    /** Context.ROLE_SERVICE。 */
    public static final String ROLE_SERVICE = "role";
    public static final int SDK_Q = 29;

    /** 请求角色后多久之内回到 App、且还没设上，算「系统直接拒了」（ms）。人真去看了弹窗再取消，一般比这慢。 */
    public static final long BOUNCE_MS = 2000L;

    public enum Step { ROLE_REQUEST, VOICE_INPUT, DEFAULT_APPS, APP_DETAILS }

    /** 跳转顺序。roleAvailable / roleHeld 拿不到时传 false。 */
    public static List<Step> plan(int sdk, boolean roleAvailable, boolean roleHeld) {
        List<Step> l = new ArrayList<Step>();
        if (sdk >= SDK_Q && roleAvailable && !roleHeld) l.add(Step.ROLE_REQUEST);
        l.add(Step.VOICE_INPUT);
        l.add(Step.DEFAULT_APPS);
        l.add(Step.APP_DETAILS);
        return Collections.unmodifiableList(l);
    }

    /** {@code step} 之后的步骤（自动接力用）；step 不在里面 → 空。 */
    public static List<Step> after(List<Step> plan, Step step) {
        int i = plan == null ? -1 : plan.indexOf(step);
        if (i < 0) return Collections.<Step>emptyList();
        return plan.subList(i + 1, plan.size());
    }

    /**
     * 当前是不是默认助理。
     * @param roleHeld Android 10+ 从 RoleManager 读到的结果（读不到 / 低版本传 null）—— 有就以它为准
     * @param visSetting  Settings.Secure voice_interaction_service（形如 包名/类名）
     * @param assistSetting Settings.Secure assistant
     * @return TRUE / FALSE；什么都读不到时 null（界面写「看不出来」）
     */
    public static Boolean isDefault(Boolean roleHeld, String visSetting, String assistSetting, String pkg) {
        if (roleHeld != null) return roleHeld;
        if (visSetting == null && assistSetting == null) return null;
        return Boolean.valueOf(ownedBy(visSetting, pkg) || ownedBy(assistSetting, pkg));
    }

    /** 「包名/类名」的包名是不是 pkg（只比包名段：com.x 不会误认 com.x.debug）。 */
    public static boolean ownedBy(String flat, String pkg) {
        if (flat == null || pkg == null || pkg.length() == 0) return false;
        String s = flat.trim();
        int slash = s.indexOf('/');
        if (slash <= 0) return false;
        return s.substring(0, slash).equals(pkg);
    }

    /** 刚请求完角色就回来了、而且还没设上 → 系统没给弹窗，接着打开下一步。 */
    public static boolean bounced(long launchedAtMs, long nowMs, boolean heldNow) {
        if (launchedAtMs <= 0L || heldNow) return false;
        long d = nowMs - launchedAtMs;
        return d >= 0L && d <= BOUNCE_MS;
    }

    // ------------------------------------------------------------------ 界面文字（大白话，不写别家名字）

    public static final String ROW_TITLE = "设为默认助理";

    public static String rowSub(Boolean isDefault) {
        if (Boolean.TRUE.equals(isDefault)) return "已经是默认助理了：用手机的助理手势（比如长按电源键、屏幕角落上滑）就能叫我";
        if (Boolean.FALSE.equals(isDefault)) return "现在还不是。设好以后，长按电源键或屏幕角落上滑就能叫我";
        return "看不出来现在是不是。点一下去系统设置里看看";
    }

    public static String rowAction(Boolean isDefault) {
        return Boolean.TRUE.equals(isDefault) ? "去看看" : "去设置";
    }

    /** 打开某一步之后给的提示（ROLE_REQUEST 是系统弹窗，不提示）。 */
    public static String hint(Step s) {
        if (s == Step.VOICE_INPUT) return "在这页找「数字助理」或「助理应用」，选 OneSay";
        if (s == Step.DEFAULT_APPS) return "在「默认应用」里找「数字助理」，选 OneSay";
        if (s == Step.APP_DETAILS) return "没找到默认助理的设置页。可以去 设置 › 应用 › 默认应用 › 数字助理，选 OneSay";
        return null;
    }

    public static final String ALL_FAILED = "没能打开系统设置。请自己去 设置 › 应用 › 默认应用 › 数字助理，选 OneSay";
}
