package com.onesay.assistant.v2.sysux;

import com.onesay.assistant.v2.assist.AssistEntryPolicy;
import com.onesay.assistant.v2.assist.AssistNodeFlattener;
import com.onesay.assistant.v2.vision.screenref.ScreenContext;
import com.onesay.assistant.v2.vision.screenref.UiNode;
import com.onesay.assistant.v2.wake.WakePromptPolicy;
import com.onesay.assistant.v2.wake.WakePromptPolicy.Kind;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.*;

/** ★ r2/sysux 第二阶段：默认数字助理（混合式）—— 入口判定、AssistStructure 展平与隐私规则、三件套声明。 */
public class SysuxAssistTest {

    private static final String WS = SysuxWiringTest.src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
    private static final String SRH = SysuxWiringTest.src("app/src/com/onesay/assistant/v2/vision/screenref/ScreenRefHooks.java");
    private static final String MF = SysuxWiringTest.src("app/AndroidManifest.xml");
    private static final String VIX = SysuxWiringTest.src("app/res/xml/onesay_voice_interaction.xml");

    // ---------------- P2 默认数字助理 ----------------

    @Test public void assistOnShow() {
        assertEquals(AssistEntryPolicy.OnShow.WAKE, AssistEntryPolicy.onShow(null));
        assertEquals(AssistEntryPolicy.OnShow.WAKE, AssistEntryPolicy.onShow(""));
        assertEquals("自己要上下文时不再唤醒（防自激）", AssistEntryPolicy.OnShow.CONTEXT_ONLY, AssistEntryPolicy.onShow("key-file"));
        assertEquals(Kind.ASSIST, WakePromptPolicy.kindOf(AssistEntryPolicy.SRC_SYSTEM));
        assertEquals(Kind.ASSIST, WakePromptPolicy.kindOf(AssistEntryPolicy.SRC_KEYGUARD));
    }

    @Test public void contextOnWakeRules() {
        assertTrue(AssistEntryPolicy.wantContextOnWake(true, true, false, "key-file"));
        assertTrue(AssistEntryPolicy.wantContextOnWake(true, true, false, "voice"));
        assertFalse("不是默认助理", AssistEntryPolicy.wantContextOnWake(true, false, false, "voice"));
        assertFalse("锁屏", AssistEntryPolicy.wantContextOnWake(true, true, true, "voice"));
        assertFalse("开关关", AssistEntryPolicy.wantContextOnWake(false, true, false, "voice"));
        assertFalse("系统入口自带上下文", AssistEntryPolicy.wantContextOnWake(true, true, false, "assist-system"));
        assertFalse("续听沿用缓存", AssistEntryPolicy.wantContextOnWake(true, true, false, "followup-tts"));
        assertFalse("✦ 语音沿用缓存", AssistEntryPolicy.wantContextOnWake(true, true, false, "voice-ui"));
    }

    @Test public void freshForWake() {
        assertTrue(AssistEntryPolicy.freshForWake(1000, 900, 1100, 5000));
        assertFalse("上一次唤醒的", AssistEntryPolicy.freshForWake(800, 900, 1100, 5000));
        assertFalse("过期", AssistEntryPolicy.freshForWake(1000, 900, 9000, 5000));
    }

    @Test public void passwordInputTypes() {
        assertTrue(AssistNodeFlattener.isPasswordInputType(0x81));    // text|password
        assertTrue(AssistNodeFlattener.isPasswordInputType(0xe1));    // text|webPassword
        assertTrue(AssistNodeFlattener.isPasswordInputType(0x91));    // text|visiblePassword
        assertTrue(AssistNodeFlattener.isPasswordInputType(0x12));    // number|numberPassword
        assertFalse(AssistNodeFlattener.isPasswordInputType(0x01));
        assertFalse(AssistNodeFlattener.isPasswordInputType(0x21));   // email
        assertFalse(AssistNodeFlattener.isPasswordInputType(0));
    }

    private static AssistNodeFlattener.Raw raw(String t, String d, String id, boolean pw, boolean vis, int l, int top, int r, int b) {
        return new AssistNodeFlattener.Raw(t, d, id, "android.widget.TextView", "com.tencent.mm", pw, false, vis, l, top, r, b);
    }

    @Test public void flattenAppliesSamePrivacyRulesAsUiautomator() {
        List<AssistNodeFlattener.Raw> in = new ArrayList<AssistNodeFlattener.Raw>();
        in.add(raw("张三：明天下午3点在国贸见", null, "content", false, true, 0, 100, 1080, 200));
        in.add(raw("13800138000", null, "phone", false, true, 0, 200, 1080, 300));
        in.add(raw("123456", null, "com.x:id/pay_pwd", false, true, 0, 300, 1080, 400));
        in.add(raw("secret", null, "edit", true, true, 0, 400, 1080, 500));
        in.add(raw("卡 6222021234567890128 余额", null, "card", false, true, 0, 500, 1080, 600));
        in.add(raw("看不见", null, "gone", false, false, 0, 600, 1080, 700));
        in.add(raw("零面积", null, "z", false, true, 10, 10, 10, 10));
        in.add(raw(null, null, "layout", false, true, 0, 0, 1080, 1920));
        in.add(new AssistNodeFlattener.Raw("面板", null, null, "x", "com.onesay.assistant", false, false, true, 0, 0, 10, 10));
        AssistNodeFlattener.Result r = AssistNodeFlattener.flatten(in, "com.onesay.assistant");
        assertEquals(3, r.nodes.size());
        assertEquals("密码框 + 支付码 id 丢弃、卡号打码", 3, r.redacted);
        boolean masked = false;
        for (UiNode n : r.nodes) if (n.text.contains("****")) masked = true;
        assertTrue(masked);
        for (UiNode n : r.nodes) assertFalse(n.text.contains("123456") && n.text.length() == 6);
        ScreenContext sc = ScreenContext.fromAssistNodes("com.tencent.mm", "LauncherUI", r.nodes, r.redacted,
                1700000000000L, TimeZone.getTimeZone("Asia/Shanghai"), 80);
        assertEquals(ScreenContext.SRC_ASSIST, sc.source);
        assertEquals(3, sc.texts.size());
        assertFalse(sc.entities.isEmpty());
        assertFalse(sc.needsVl);
    }

    @Test public void emptyStructureFallsBack() {
        ScreenContext sc = ScreenContext.fromAssistNodes("p", "a", new ArrayList<UiNode>(), 0, 1L, TimeZone.getDefault(), 5);
        assertEquals(ScreenContext.SRC_NONE, sc.source);
        assertTrue(sc.needsVl);
    }
    @Test public void voiceInteractionTrioDeclared() {
        assertTrue(VIX.contains("android:sessionService=\"com.onesay.assistant.v2.assist.OneSaySessionService\""));
        assertTrue("缺 recognitionService 正是 10-04 Bixby 键开浏览器的根因", VIX.contains("android:recognitionService=\"com.onesay.assistant.v2.assist.OneSayRecognitionService\""));
        assertTrue(VIX.contains("android:supportsAssist=\"true\""));
        assertTrue(VIX.contains("android:supportsLaunchVoiceAssistFromKeyguard=\"true\""));
        assertTrue(MF.contains("com.onesay.assistant.v2.assist.OneSayVoiceInteractionService"));
        assertTrue(MF.contains("android.service.voice.VoiceInteractionService"));
        assertTrue(MF.contains("@xml/onesay_voice_interaction"));
        assertTrue(MF.contains("com.onesay.assistant.v2.assist.OneSaySessionService"));
        assertTrue(MF.contains("com.onesay.assistant.v2.assist.OneSayRecognitionService"));
        assertTrue(MF.contains("android.speech.RecognitionService"));
        int vis = MF.indexOf("com.onesay.assistant.v2.assist.OneSayVoiceInteractionService");
        assertTrue("只允许系统绑定", MF.indexOf("android.permission.BIND_VOICE_INTERACTION", vis) > vis);
        assertTrue(SRH.contains("ScreenContext.SRC_ASSIST.equals(ac.source)"));
    }

    @Test public void wakeRequestsAssistContext() {
        assertTrue(WS.contains("requestAssistContext(source);"));
        assertTrue(WS.contains("AssistEntryPolicy.wantContextOnWake(opt,"));
    }
}
