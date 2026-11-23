package com.onesay.assistant.v2.assistrole;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.assist.AssistRole;
import com.onesay.assistant.v2.assist.AssistRole.Step;
import com.onesay.assistant.v2.assist.AssistTriggerGate;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** ★ feat/assist-role：注册为系统默认助理 —— 唤起防重、跳转降级顺序、是否默认的判断、文案、接线。 */
public class AssistRoleTest {

    private static final String PKG = "com.onesay.assistant";
    private static final String VIS = PKG + "/com.onesay.assistant.v2.assist.OneSayVoiceInteractionService";

    static String src(String rel) {
        try {
            java.io.File f = new java.io.File(System.getProperty("bixby.root", "."), rel);
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private AssistTriggerGate.Window w;

    @Before public void setUp() {
        w = new AssistTriggerGate.Window();
    }

    // ---------------------------------------------------------------- 防重

    @Test public void onShowThenOnHandleAssistFiresOnce() {
        AssistTriggerGate.Once o = new AssistTriggerGate.Once();
        assertTrue("onShow 先到：唤起", o.tryFire(w, 10000L));
        assertFalse("同一次显示的 onHandleAssist：不再唤起", o.tryFire(w, 10200L));
        assertFalse("再来一次也不唤起", o.tryFire(w, 20000L));
        assertTrue(o.fired());
    }

    @Test public void onHandleAssistAloneStillFires() {
        AssistTriggerGate.Once o = new AssistTriggerGate.Once();
        assertTrue("个别系统只给 onHandleAssist：照样唤起一次", o.tryFire(w, 5000L));
    }

    @Test public void secondShowInsideWindowDropped() {
        assertTrue(new AssistTriggerGate.Once().tryFire(w, 1000L));
        assertFalse("1.5 秒内又来一次 onShow（新 session）：丢掉", new AssistTriggerGate.Once().tryFire(w, 1000L + AssistTriggerGate.WINDOW_MS - 1));
        assertTrue("过了时间窗是新的一次按键", new AssistTriggerGate.Once().tryFire(w, 1000L + AssistTriggerGate.WINDOW_MS + 1));
    }

    @Test public void keyguardAndSessionShareWindow() {
        AssistTriggerGate.Once keyguard = new AssistTriggerGate.Once(), session = new AssistTriggerGate.Once();
        assertTrue("锁屏回调先到", keyguard.tryFire(w, 50000L));
        assertFalse("同一次长按的 session onShow 被挡", session.tryFire(w, 50300L));
    }

    @Test public void droppedAttemptsDoNotExtendWindow() {
        assertTrue(new AssistTriggerGate.Once().tryFire(w, 0L + 100L));
        assertFalse(new AssistTriggerGate.Once().tryFire(w, 1400L));
        assertTrue("被挡的那次不刷新时间：从第一次起算 1.5 秒后就放行", new AssistTriggerGate.Once().tryFire(w, 1700L));
    }

    @Test public void firstEverAdmitted() {
        assertTrue("开机后第一次（elapsedRealtime 很小）也放行", new AssistTriggerGate.Once().tryFire(w, 0L));
        w.reset();
        assertTrue(new AssistTriggerGate.Once().tryFire(w, 3L));
    }

    @Test public void sharedWindowExists() {
        assertTrue(AssistTriggerGate.SHARED != null);
        assertTrue(AssistTriggerGate.WINDOW_MS >= 1000L && AssistTriggerGate.WINDOW_MS <= 3000L);
    }

    // ---------------------------------------------------------------- 跳转降级顺序

    @Test public void planLowVersionSkipsRole() {
        assertEquals(Arrays.asList(Step.VOICE_INPUT, Step.DEFAULT_APPS, Step.APP_DETAILS), AssistRole.plan(26, true, false));
        assertEquals("Android 9 也没有 RoleManager", Arrays.asList(Step.VOICE_INPUT, Step.DEFAULT_APPS, Step.APP_DETAILS), AssistRole.plan(28, true, false));
        assertEquals(Arrays.asList(Step.VOICE_INPUT, Step.DEFAULT_APPS, Step.APP_DETAILS), AssistRole.plan(24, false, false));
    }

    @Test public void planQPlusTriesRoleFirst() {
        assertEquals(Arrays.asList(Step.ROLE_REQUEST, Step.VOICE_INPUT, Step.DEFAULT_APPS, Step.APP_DETAILS), AssistRole.plan(29, true, false));
        assertEquals(Step.ROLE_REQUEST, AssistRole.plan(34, true, false).get(0));
    }

    @Test public void planRoleUnavailableOrHeld() {
        assertEquals("系统没有助理角色 → 直接去设置页", Step.VOICE_INPUT, AssistRole.plan(34, false, false).get(0));
        assertEquals("已经是了 → 不再请求，点了就是去看看", Step.VOICE_INPUT, AssistRole.plan(34, true, true).get(0));
    }

    @Test public void afterRoleContinuesWithRest() {
        List<Step> p = AssistRole.plan(33, true, false);
        assertEquals(Arrays.asList(Step.VOICE_INPUT, Step.DEFAULT_APPS, Step.APP_DETAILS), AssistRole.after(p, Step.ROLE_REQUEST));
        assertEquals(Collections.<Step>emptyList(), AssistRole.after(AssistRole.plan(28, false, false), Step.ROLE_REQUEST));
        assertEquals(Collections.<Step>emptyList(), AssistRole.after(null, Step.ROLE_REQUEST));
        assertEquals(Collections.<Step>emptyList(), AssistRole.after(p, Step.APP_DETAILS));
    }

    @Test public void bounceDetection() {
        assertTrue("请求后 300ms 回来、没设上 = 系统没给弹窗", AssistRole.bounced(10000L, 10300L, false));
        assertTrue(AssistRole.bounced(10000L, 10000L + AssistRole.BOUNCE_MS, false));
        assertFalse("设上了", AssistRole.bounced(10000L, 10300L, true));
        assertFalse("看了弹窗自己取消（慢）", AssistRole.bounced(10000L, 10000L + AssistRole.BOUNCE_MS + 1, false));
        assertFalse("没请求过", AssistRole.bounced(0L, 10300L, false));
        assertFalse("时间不对", AssistRole.bounced(10000L, 9000L, false));
    }

    // ---------------------------------------------------------------- 是不是默认助理

    @Test public void roleResultWins() {
        assertEquals(Boolean.TRUE, AssistRole.isDefault(Boolean.TRUE, null, null, PKG));
        assertEquals("角色说不是，以角色为准（设置项可能是旧值）", Boolean.FALSE, AssistRole.isDefault(Boolean.FALSE, VIS, VIS, PKG));
    }

    @Test public void settingsFallback() {
        assertEquals(Boolean.TRUE, AssistRole.isDefault(null, VIS, null, PKG));
        assertEquals(Boolean.TRUE, AssistRole.isDefault(null, "", VIS, PKG));
        assertEquals(Boolean.FALSE, AssistRole.isDefault(null, "", "", PKG));
        assertEquals(Boolean.FALSE, AssistRole.isDefault(null, "com.other.app/.Vis", null, PKG));
        assertNull("什么都读不到 → 看不出来", AssistRole.isDefault(null, null, null, PKG));
    }

    @Test public void debugPackageNotConfused() {
        String dbg = "com.onesay.assistant.debug/com.onesay.assistant.v2.assist.OneSayVoiceInteractionService";
        assertFalse("正式版不把调试包认成自己", AssistRole.ownedBy(dbg, PKG));
        assertTrue(AssistRole.ownedBy(dbg, "com.onesay.assistant.debug"));
        assertFalse("调试包不把正式版认成自己", AssistRole.ownedBy(VIS, "com.onesay.assistant.debug"));
        assertFalse(AssistRole.ownedBy("com.onesay.assistant", PKG));
        assertFalse(AssistRole.ownedBy("/x", PKG));
        assertFalse(AssistRole.ownedBy(VIS, ""));
        assertTrue(AssistRole.ownedBy("  " + VIS + " ", PKG));
    }

    // ---------------------------------------------------------------- 文案

    @Test public void copyIsPlainAndClean() {
        String[] bad = {"Bixby", "bixby", "三星", "小布", "Gemini", "Google", "谷歌", "小爱", "小艺", "Siri", "啥", "咋", "呗",
                "ROLE_", "android.", "RoleManager", "Intent", "VOICE_INPUT", "null"};
        StringBuilder all = new StringBuilder(AssistRole.ROW_TITLE).append('|').append(AssistRole.ALL_FAILED);
        for (Boolean b : new Boolean[]{Boolean.TRUE, Boolean.FALSE, null}) all.append('|').append(AssistRole.rowSub(b)).append('|').append(AssistRole.rowAction(b));
        for (Step s : Step.values()) all.append('|').append(AssistRole.hint(s));
        String t = all.toString().replace("|null", "");   // ROLE_REQUEST 没有提示
        for (String w : bad) assertFalse("用户可见文字里有「" + w + "」：" + t, t.contains(w));
        assertEquals("设为默认助理", AssistRole.ROW_TITLE);
        assertTrue(AssistRole.rowSub(Boolean.TRUE).startsWith("已经是"));
        assertTrue(AssistRole.rowSub(Boolean.FALSE).startsWith("现在还不是"));
        assertTrue(AssistRole.rowSub(null).startsWith("看不出来"));
        assertEquals("去设置", AssistRole.rowAction(Boolean.FALSE));
        assertNull(AssistRole.hint(Step.ROLE_REQUEST));
    }

    // ---------------------------------------------------------------- 接线（读源码）

    @Test public void sessionReusesWakeCardEntryWithGate() {
        String s = src("app/src/com/onesay/assistant/v2/assist/OneSaySession.java");
        int show = s.indexOf("public void onShow("), assist = s.indexOf("public void onHandleAssist(");
        assertTrue(s.indexOf("summonOnce(", show) > show && s.indexOf("summonOnce(", show) < assist);
        assertTrue("onHandleAssist 也兜一次", s.indexOf("if (mWakeMode) summonOnce(\"onHandleAssist\")", assist) > assist);
        assertTrue(s.contains("mOnce.tryFire(AssistTriggerGate.SHARED"));
        assertTrue("复用快捷开关 / 小部件同一条唤起路径", s.contains("Entries.summonVoice(c, AssistEntryPolicy.SRC_SYSTEM)"));
        assertTrue("入口关了不唤起", s.contains("Entries.canFire(c, BuiltinEntries.ASSISTANT)"));
        assertTrue("仍然收起自己", s.contains("hide();"));
        assertFalse("不再直接 requestWake（统一走 Entries）", s.contains("WakeService.requestWake("));
        String vis = src("app/src/com/onesay/assistant/v2/assist/OneSayVoiceInteractionService.java");
        int kg = vis.indexOf("public void onLaunchVoiceAssistFromKeyguard()");
        assertTrue(vis.indexOf("AssistTriggerGate.SHARED", kg) > kg);
        assertTrue(vis.indexOf("summonVoice(this, AssistEntryPolicy.SRC_KEYGUARD)", kg) > kg);
        String e = src("app/src/com/onesay/assistant/v2/entry/Entries.java");
        assertTrue(e.contains("public static void summonVoice(Context ctx, String source)"));
        assertTrue("快捷开关 / 小部件也走同一个方法", e.contains("            summonVoice(app, source);"));
    }

    @Test public void manifestAndResources() {
        String m = src("app/AndroidManifest.xml");
        assertTrue("没改 targetSdk", m.contains("android:targetSdkVersion=\"28\""));
        int vis = m.indexOf("android:name=\"com.onesay.assistant.v2.assist.OneSayVoiceInteractionService\"");
        assertTrue(m.indexOf("android:permission=\"android.permission.BIND_VOICE_INTERACTION\"", vis) > vis);
        int ss = m.indexOf("android:name=\"com.onesay.assistant.v2.assist.OneSaySessionService\"");
        assertTrue(m.substring(ss, m.indexOf("/>", ss)).contains("android:permission=\"android.permission.BIND_VOICE_INTERACTION\""));
        int legacy = m.indexOf("android:name=\"com.onesay.assistant.v2.assist.OneSayRecognitionService\"");
        String lb = m.substring(legacy, m.indexOf("</service>", legacy));
        assertTrue(lb.contains("android:enabled=\"@bool/onesay_rs_legacy\""));
        assertFalse("低版本别的 App 直接绑，不能加权限", lb.contains("android:permission="));
        int v31 = m.indexOf("android:name=\"com.onesay.assistant.v2.assist.OneSayRecognitionServiceV31\"");
        assertTrue(v31 > 0);
        String nb = m.substring(v31, m.indexOf("</service>", v31));
        assertTrue(nb.contains("android:permission=\"android.permission.BIND_RECOGNITION_SERVICE\""));
        assertTrue(nb.contains("android:enabled=\"@bool/onesay_rs_v31\""));
        assertTrue(nb.contains("android.speech.RecognitionService") && nb.contains("android:name=\"android.speech\""));
        String b = src("app/res/values/onesay_assist.xml"), b31 = src("app/res/values-v31/onesay_assist.xml");
        assertTrue(b.contains("<bool name=\"onesay_rs_legacy\">true</bool>") && b.contains("<bool name=\"onesay_rs_v31\">false</bool>"));
        assertTrue("12+ 正好反过来，任何版本只开一个", b31.contains("<bool name=\"onesay_rs_legacy\">false</bool>") && b31.contains("<bool name=\"onesay_rs_v31\">true</bool>"));
        String x = src("app/res/xml/onesay_voice_interaction.xml"), x31 = src("app/res/xml-v31/onesay_voice_interaction.xml");
        assertTrue(x.contains("android:recognitionService=\"com.onesay.assistant.v2.assist.OneSayRecognitionService\""));
        assertTrue(x31.contains("android:recognitionService=\"com.onesay.assistant.v2.assist.OneSayRecognitionServiceV31\""));
        for (String xx : new String[]{x, x31}) {
            assertTrue(xx.contains("android:supportsAssist=\"true\""));
            assertTrue(xx.contains("android:sessionService=\"com.onesay.assistant.v2.assist.OneSaySessionService\""));
            assertTrue(xx.contains("android:supportsLaunchVoiceAssistFromKeyguard=\"true\""));
        }
        String bl = src("build_linux.sh");
        assertTrue("没改编译 SDK", bl.contains("platforms/android-28/android.jar") && bl.contains("--target-sdk-version 28"));
    }

    @Test public void settingsRowAndFallbackChain() {
        String h = src("app/src/com/onesay/assistant/v2/assist/AssistRoleHost.java");
        assertTrue("角色请求要 ForResult（普通 startActivity 系统认不出是谁在请求）", h.contains("a.startActivityForResult(it, REQ_ROLE)"));
        assertTrue("反射，不依赖高版本 SDK", h.contains("getMethod(\"createRequestRoleIntent\", String.class)") && !h.contains("import android.app.role"));
        assertTrue(h.contains("Settings.ACTION_VOICE_INPUT_SETTINGS") && h.contains("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")
                && h.contains("Settings.ACTION_APPLICATION_DETAILS_SETTINGS"));
        assertTrue("打开前先看系统有没有这一页", h.contains("resolveActivity(a.getPackageManager()) == null"));
        String d = src("app/src/com/onesay/assistant/v2/ui/console/DesignPages.java");
        int hub = d.indexOf("class AdvancedHubPage");
        int row = d.indexOf("AssistRole.ROW_TITLE", hub);
        assertTrue("「高级 › 设备」里有这一行", row > d.indexOf("k.group(col, \"设备\")", hub) && row < d.indexOf("\"设备能力与执行后端\"", hub));
        assertTrue(d.contains("AssistRoleHost.isDefault(c.act)"));
        String c = src("app/src/com/onesay/assistant/v2/ui/console/Console.java");
        int r = c.indexOf("public void onResume()");
        assertTrue("回到控制台时接着降级", c.indexOf("AssistRoleHost.resumeIfBounced(", r) > r);
        String p = src("app/src/com/onesay/assistant/v2/ui/console/PermSetupPage.java");
        assertTrue("权限配置那一项用同一套顺序", p.contains("c.openAssistRole();"));
    }
}
