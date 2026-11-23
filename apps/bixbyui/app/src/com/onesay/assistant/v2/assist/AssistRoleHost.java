package com.onesay.assistant.v2.assist;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

/**
 * ★ feat/assist-role：「设为默认助理」的 Android 侧。顺序与判断都在 {@link AssistRole}。
 *
 * <p>RoleManager 全程反射（API 29 才有，工程仍按 android-28 编译，不改 compileSdk / targetSdk）。
 * 请求角色必须用 {@code startActivityForResult}：系统的请求页靠调用方包名认人，普通 startActivity 拿不到包名，会直接没反应。</p>
 */
public final class AssistRoleHost {

    private AssistRoleHost() {}

    private static final String TAG = "BixbyVIS";
    static final int REQ_ROLE = 0x0A55;

    /** 打开设置后的提示（控制台的 toast）。 */
    public interface Hint {
        void show(String text);
    }

    private static long sRoleLaunchedAt;
    private static List<AssistRole.Step> sPlan;

    /** 当前是不是默认助理（null = 看不出来）。 */
    public static Boolean isDefault(Context c) {
        if (c == null) return null;
        Boolean held = null;
        if (Build.VERSION.SDK_INT >= AssistRole.SDK_Q) {
            Object rm = roleManager(c);
            if (rm != null && Boolean.TRUE.equals(roleCall(rm, "isRoleAvailable"))) held = roleCall(rm, "isRoleHeld");
        }
        String vis = secure(c, "voice_interaction_service");
        String ast = secure(c, "assistant");
        return AssistRole.isDefault(held, vis, ast, c.getPackageName());
    }

    /** 点「设为默认助理」：按 {@link AssistRole#plan} 依次试，打开第一个能打开的。 */
    public static void open(Activity a, Hint h) {
        if (a == null) return;
        boolean avail = false, held = false;
        if (Build.VERSION.SDK_INT >= AssistRole.SDK_Q) {
            Object rm = roleManager(a);
            if (rm != null) {
                avail = Boolean.TRUE.equals(roleCall(rm, "isRoleAvailable"));
                held = avail && Boolean.TRUE.equals(roleCall(rm, "isRoleHeld"));
            }
        }
        List<AssistRole.Step> plan = AssistRole.plan(Build.VERSION.SDK_INT, avail, held);
        Log.i(TAG, "[ROLE] 设为默认助理：sdk=" + Build.VERSION.SDK_INT + " roleAvailable=" + avail + " held=" + held + " plan=" + plan);
        sPlan = plan;
        sRoleLaunchedAt = 0L;
        runFrom(a, plan, h);
    }

    /**
     * 回到控制台时调用（Console.onResume）：刚才请求角色「秒回」且还没设上 → 系统没给弹窗，接着打开下一步。
     * @return true = 已接着打开下一步
     */
    public static boolean resumeIfBounced(Activity a, Hint h) {
        long at = sRoleLaunchedAt;
        if (at <= 0L || a == null) return false;
        sRoleLaunchedAt = 0L;
        boolean heldNow = Boolean.TRUE.equals(isDefault(a));
        if (!AssistRole.bounced(at, SystemClock.elapsedRealtime(), heldNow)) {
            Log.i(TAG, "[ROLE] 请求角色后回来：" + (heldNow ? "已设上" : "用户自己关了弹窗") + "，不再往下跳");
            return false;
        }
        Log.i(TAG, "[ROLE] 请求角色秒回且没设上（系统不让 App 直接请求）→ 打开下一步");
        List<AssistRole.Step> plan = sPlan;
        if (plan == null) return false;
        runFrom(a, AssistRole.after(plan, AssistRole.Step.ROLE_REQUEST), h);
        return true;
    }

    private static void runFrom(Activity a, List<AssistRole.Step> steps, Hint h) {
        for (AssistRole.Step s : steps) {
            if (launch(a, s)) {
                Log.i(TAG, "[ROLE] 已打开 " + s);
                String tip = AssistRole.hint(s);
                if (tip != null && h != null) h.show(tip);
                return;
            }
            Log.i(TAG, "[ROLE] " + s + " 打不开，试下一步");
        }
        if (h != null) h.show(AssistRole.ALL_FAILED);
    }

    static boolean launch(Activity a, AssistRole.Step s) {
        try {
            if (s == AssistRole.Step.ROLE_REQUEST) {
                Object rm = roleManager(a);
                if (rm == null) return false;
                Method m = rm.getClass().getMethod("createRequestRoleIntent", String.class);
                Intent it = (Intent) m.invoke(rm, AssistRole.ROLE_ASSISTANT);
                if (it == null || it.resolveActivity(a.getPackageManager()) == null) return false;
                sRoleLaunchedAt = SystemClock.elapsedRealtime();
                a.startActivityForResult(it, REQ_ROLE);   // 不能加 NEW_TASK：请求页要认调用方
                return true;
            }
            Intent it;
            if (s == AssistRole.Step.VOICE_INPUT) it = new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS);
            else if (s == AssistRole.Step.DEFAULT_APPS) it = new Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS");
            else it = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName()));
            if (it.resolveActivity(a.getPackageManager()) == null) return false;
            a.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Throwable t) {
            if (s == AssistRole.Step.ROLE_REQUEST) sRoleLaunchedAt = 0L;
            Log.w(TAG, "[ROLE] 打开 " + s + " 失败: " + t);
            return false;
        }
    }

    static Object roleManager(Context c) {
        try {
            return c.getSystemService(AssistRole.ROLE_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** isRoleAvailable / isRoleHeld（参数都是 ROLE_ASSISTANT）；调不了返回 null。 */
    static Boolean roleCall(Object rm, String method) {
        try {
            Object r = rm.getClass().getMethod(method, String.class).invoke(rm, AssistRole.ROLE_ASSISTANT);
            return r instanceof Boolean ? (Boolean) r : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static String secure(Context c, String key) {
        try {
            return Settings.Secure.getString(c.getContentResolver(), key);
        } catch (Throwable t) {
            return null;
        }
    }
}
