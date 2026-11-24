package com.onesay.assistant.v2.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.executor.backend.VisionBackend;
import com.onesay.assistant.v2.kernel.KernelWiring;
import com.onesay.assistant.v2.vision.VisionAvailability;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ★ fix/1123-bug2：没配识屏 Key 时反复识屏。
 *
 * <p>复现（修前）：点界面代理每个任务第一轮还把「要看画面」给模型 → 模型要截图 → 白截一次屏、白问一轮模型才发现用不了；
 * AgentLoop 照样给识屏工具，被调到先截屏再报「可重试」的失败，模型接着要。
 */
public class Bug1123VisionNoKeyTest {

    @Test public void repro_beforeFixFirstTurnStillOffersShotAndScreenshots() {
        FakeScreens.Phone p = MikaPhoneTest.settings();
        FakeScreens.Script s = new FakeScreens.Script("{\"shot\":true}", "{\"done\":\"安卓版本在关于手机里\"}");
        new PhoneAgent(p, s, new FakeScreens.Host()).run("打开设置看看安卓版本", null);
        assertEquals("不知道没 Key：白截一次", 1, p.describes);
        assertTrue("第一轮还给「要看画面」", s.systems.get(0).contains("{\"shot\":true}"));
    }

    @Test public void knownNoKeyNeverScreenshotsNorOffersShot() {
        FakeScreens.Phone p = MikaPhoneTest.settings();
        FakeScreens.Script s = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:WLAN}}]}", "{\"done\":\"已打开 WLAN\"}");
        PhoneAgent a = new PhoneAgent(p, s, new FakeScreens.Host());
        a.visionKnownOff = true;
        PhoneAgent.Result r = a.run("打开 WLAN", null);
        assertEquals(PhoneAgent.Status.DONE, r.status);
        assertEquals("一次都不截屏", 0, p.describes);
        for (String sys : s.systems) assertFalse("每一轮都不给「要看画面」", sys.contains("{\"shot\":true}"));
        assertTrue(r.trace.contains("vision off (no key)"));
    }

    @Test public void knownNoKeyModelStillAsksShotStopsAfterTwo() {
        FakeScreens.Phone p = MikaPhoneTest.settings();
        FakeScreens.Script s = new FakeScreens.Script("{\"shot\":true}", "{\"shot\":true}", "{\"shot\":true}", "{\"done\":\"不该走到这\"}");
        PhoneAgent a = new PhoneAgent(p, s, new FakeScreens.Host());
        a.visionKnownOff = true;
        PhoneAgent.Result r = a.run("打开设置看看安卓版本", null);
        assertEquals(0, p.describes);
        assertEquals(PhoneAgent.Status.FAILED, r.status);
        assertTrue(r.reason, r.reason.contains("看不了屏幕图像"));
        assertEquals("两次就停，不一直要", 2, s.systems.size());
    }

    @Test public void agentLoopDropsVisionToolWithoutKey() {
        Capability vision = new Capability.Builder(VisionAvailability.SIGN, "识屏").executor("custom").build();
        Capability launch = new Capability.Builder("app.launch", "打开应用").executor("intent").build();
        List<Capability> in = new ArrayList<Capability>(Arrays.asList(launch, vision));
        assertSame("能用原样", in, VisionAvailability.filterCandidates(in, true));
        List<Capability> out = VisionAvailability.filterCandidates(in, false);
        assertEquals(1, out.size());
        assertEquals("app.launch", out.get(0).sign);
        assertTrue("没 Context（单测假宿主）当能用，不改原行为", VisionAvailability.usable(null));
        assertTrue(VisionAvailability.keyMissing(""));
        assertTrue(VisionAvailability.keyMissing("  "));
        assertTrue(VisionAvailability.keyMissing(null));
        assertFalse(VisionAvailability.keyMissing("x"));
    }

    @Test public void visionBackendNoKeyIsNotRetryable() throws Exception {
        Capability vision = new Capability.Builder(VisionAvailability.SIGN, "识屏").executor("custom").build();
        ExecutionResult r = VisionBackend.noKey(vision, new JSONObject());
        assertFalse(r.ok);
        assertEquals(ErrorCode.NOT_CONFIGURED, r.code);
        assertFalse("不可重试", ErrorCode.NOT_CONFIGURED.retryable);
        assertTrue(r.userMessage, r.userMessage.contains("看图还没配好"));   // ★ fix/chat-vision-1124：缺的是主模型 Key
        assertFalse("大白话里不带内部 ID", r.userMessage.contains("vision.") || r.userMessage.contains("DashScope"));
        // 收尾建议说识屏，不再把人指到邮箱设置
        java.lang.reflect.Method m = Class.forName("com.onesay.assistant.v2.router.AgentLoop").getDeclaredMethod("planFor", List.class, String.class);
        m.setAccessible(true);
        Object st = com.onesay.assistant.v2.router.AgentLoop.Step.external(VisionAvailability.SIGN, new JSONObject(),
                "{\"ok\":false,\"code\":\"NOT_CONFIGURED\",\"detail\":\"vision no key\",\"message\":\"" + VisionAvailability.NO_KEY_TEXT + "\"}", false);
        List<Object> steps = new ArrayList<Object>();
        steps.add(st);
        String plan = (String) m.invoke(null, steps, "看看屏幕上写了什么");
        assertTrue(plan, plan.contains("主模型的 Key"));   // ★ fix/chat-vision-1124：看图走主模型
        assertFalse(plan, plan.contains("邮箱"));
    }

    @Test public void wiring() {
        String al = KernelWiring.src("app/src/com/onesay/assistant/v2/router/AgentLoop.java");
        assertTrue(al.contains("VisionAvailability.filterCandidates(candidates, visionOk)"));
        assertTrue(al.contains("refreshCandidates(candidates, r, regSize), visionOk)"));
        String vb = KernelWiring.src("app/src/com/onesay/assistant/v2/executor/backend/VisionBackend.java");
        int pre = vb.indexOf("VisionAvailability.usable(req.context)");
        int shot = vb.indexOf("recognizeScreenSync(");
        assertTrue("先判 Key 再截屏", pre > 0 && shot > pre);
        String ph = KernelWiring.src("app/src/com/onesay/assistant/v2/phone/PhoneHooks.java");
        assertTrue(ph.contains("agent.visionKnownOff = !legacy && !com.onesay.assistant.v2.vision.VisionAvailability.usableForPhoneAgent(app)"));
    }
}
