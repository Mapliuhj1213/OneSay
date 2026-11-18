package com.onesay.assistant.v2.executor.backend;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.executor.ControlFeedback;
import com.onesay.assistant.v2.executor.ShellExecutor;
import com.onesay.assistant.v2.executor.ShellRunner;
import com.onesay.assistant.v2.router.ReadOnlyAnswer;
import com.onesay.assistant.v2.ui.wake.WakeStage.Stage;
import com.onesay.assistant.v2.ui.wake.WindowFlowPolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** ★ fix-1118：c32 亮度按实际满格换算百分比；「默认浏览器是什么」不带 > / |；短结果卡不拉满。 */
public class Fix1118BrightBrowserCardTest {

    static final class Fake implements ShellRunner {
        final Map<String, String> out = new LinkedHashMap<String, String>();
        final List<String> ran = new ArrayList<String>();
        @Override public ShellExecutor.ShellResponse execute(String c) {
            ran.add(c);
            if (c.startsWith("settings put system screen_brightness ")) {
                out.put(ShellPrograms.BRIGHTNESS_GET, c.substring("settings put system screen_brightness ".length()));
                return new ShellExecutor.ShellResponse(0, "", false, false);
            }
            String o = out.get(c);
            return o == null ? new ShellExecutor.ShellResponse(1, "", false, false) : new ShellExecutor.ShellResponse(0, o, false, false);
        }
    }

    @After public void reset() { BrightnessScale.frameworkMaxOverride = null; }

    static String run(ShellPrograms.Program p, Fake f) { return p.run(f, new HashMap<String, String>()).output; }

    @Test public void aospScaleUnchanged() {
        BrightnessScale.frameworkMaxOverride = 255;
        Fake f = new Fake(); f.out.put(ShellPrograms.BRIGHTNESS_GET, "128\n");
        String o = run(ShellPrograms.forSign("settings.brightness.get"), f);
        assertEquals("50%（128/255）", o);
        assertEquals("当前屏幕亮度 50%。", ReadOnlyAnswer.renderRaw("settings.brightness.get", o));
        assertFalse("不超 255 不跑 dumpsys", f.ran.contains(BrightnessScale.DUMPSYS_POWER));
    }

    @Test public void vendorScaleFromDumpsys() {
        BrightnessScale.frameworkMaxOverride = 255;
        Fake f = new Fake();
        f.out.put(ShellPrograms.BRIGHTNESS_GET, "5968");
        f.out.put(BrightnessScale.DUMPSYS_POWER, "POWER MANAGER\n  mScreenBrightnessMinimum=1\n  mScreenBrightnessMaximum=1.0\n  mScreenBrightnessSettingMaximum=8191\n");
        String o = run(ShellPrograms.forSign("settings.brightness.get"), f);
        assertEquals("73%（5968/8191）", o);
        assertEquals("当前屏幕亮度 73%。", ReadOnlyAnswer.renderRaw("settings.brightness.get", o));
    }

    @Test public void vendorScaleGuessedWhenUnknown() {
        BrightnessScale.frameworkMaxOverride = 255;
        Fake f = new Fake(); f.out.put(ShellPrograms.BRIGHTNESS_GET, "5968");   // 真机 Meizu 21 Pro 读数；dumpsys 没给整数满格
        String o = run(ShellPrograms.forSign("settings.brightness.get"), f);
        assertEquals("约 73%（5968/8191）", o);
        assertEquals("当前屏幕亮度约 73%。", ReadOnlyAnswer.renderRaw("settings.brightness.get", o));
        assertFalse(o.contains("5968") && ReadOnlyAnswer.renderRaw("settings.brightness.get", o).contains("5968"));
        assertEquals(1023, BrightnessScale.guessMax(300));
        assertEquals(4095, BrightnessScale.guessMax(4095));
    }

    @Test public void stepScalesWithMaxAndFeedbackIsPercent() {
        BrightnessScale.frameworkMaxOverride = 255;
        Fake f = new Fake();
        f.out.put(ShellPrograms.BRIGHTNESS_GET, "5968");
        f.out.put(BrightnessScale.DUMPSYS_POWER, "mScreenBrightnessSettingMaximum=8191");
        String up = run(ShellPrograms.forSign("settings.brightness.up"), f);
        assertEquals((5968 + 1285) + "/8191", up);   // 40/255 × 8191 ≈ 1285，不再被夹到 255（以前会一下暗到 3%）
        assertEquals("亮度调高到 89%", ControlFeedback.refine("settings.brightness.up", "7253", up));
        f.out.put(ShellPrograms.BRIGHTNESS_GET, "8000");
        assertEquals("亮度已经最高了（100%）", ControlFeedback.refine("settings.brightness.up", "8191", run(ShellPrograms.forSign("settings.brightness.up"), f)));
        BrightnessScale.frameworkMaxOverride = 255;
        Fake g = new Fake(); g.out.put(ShellPrograms.BRIGHTNESS_GET, "100");
        assertEquals("255 刻度照旧只回数字", "60", run(ShellPrograms.forSign("settings.brightness.down"), g));
    }

    @Test public void browserCheckIsSingleWhitelistSafeCommand() {
        Fake f = new Fake();
        f.out.put(ShellPrograms.RESOLVE_BROWSER, "priority=0 preferredOrder=0 match=0x208000\ncom.android.chrome/com.google.android.apps.chrome.IntentDispatcher\n");
        ShellPrograms.Program p = ShellPrograms.forSign("browser.default.check");
        assertNotNull(p);
        assertEquals("Chrome（com.android.chrome）", run(p, f));
        assertEquals(1, f.ran.size());
        for (String c : f.ran) { assertFalse(c.contains(">")); assertFalse(c.contains("|")); assertFalse(c.contains(";")); }
        f.out.put(ShellPrograms.RESOLVE_BROWSER, "android/com.android.internal.app.ResolverActivity");
        assertEquals("没设默认浏览器（打开链接时会让你选）", run(p, f));
        f.out.put(ShellPrograms.RESOLVE_BROWSER, "com.example.web/.Main");
        assertEquals("com.example.web", run(p, f));
        f.out.put(ShellPrograms.RESOLVE_BROWSER, "No activity found");
        assertEquals("没查到（可能没装浏览器）", run(p, f));
    }

    @Test public void browserCheckTemplateHasNoRedirect() {
        com.onesay.assistant.v2.capability.CapabilityRegistry reg = new com.onesay.assistant.v2.capability.CapabilityRegistry();
        com.onesay.assistant.v2.capability.BuiltinCapabilities.registerAll(reg);
        String cmd = reg.describe("browser.default.check").cmdTemplate;
        assertFalse(cmd, cmd.contains(">"));
        assertFalse(cmd, cmd.contains("|"));
    }

    @Test public void shortResultCardWraps() {
        assertTrue(WindowFlowPolicy.shortResult("当前屏幕亮度约 73%。", false));
        assertFalse("富文本照旧拉满", WindowFlowPolicy.shortResult("当前屏幕亮度约 73%。", true));
        StringBuilder lng = new StringBuilder(); for (int i = 0; i < 70; i++) lng.append('字');
        assertFalse(WindowFlowPolicy.shortResult(lng, false));
        assertFalse(WindowFlowPolicy.shortResult("一\n二\n三\n四", false));
        assertFalse(WindowFlowPolicy.shortResult(null, false));
        assertFalse(WindowFlowPolicy.cardFull(Stage.RESULT, true));
        assertTrue(WindowFlowPolicy.cardFull(Stage.RESULT, false));
        assertTrue("确认卡不受影响", WindowFlowPolicy.cardFull(Stage.CONFIRM, true));
        assertTrue(WindowFlowPolicy.cardFull(Stage.ANSWERING, true));
        assertEquals(WindowFlowPolicy.TouchArea.CARD, WindowFlowPolicy.touchArea(true, Stage.RESULT, true));
        assertEquals(WindowFlowPolicy.TouchArea.WINDOW, WindowFlowPolicy.touchArea(true, Stage.RESULT, false));
    }
}
