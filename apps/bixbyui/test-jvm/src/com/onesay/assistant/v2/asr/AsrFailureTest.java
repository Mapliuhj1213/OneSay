package com.onesay.assistant.v2.asr;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.kernel.KernelWiring;
import com.onesay.assistant.v2.ui.ErrorStatePolicy;
import com.onesay.assistant.v2.ui.console.ConsoleStatus;
import com.onesay.assistant.v2.ui.wake.WakeCopy;
import com.onesay.assistant.v2.ui.wake.WakeStage;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * r2/p0 P0-2（modaudit M2-1 + M2-2 + M27-1）：语音识别 Key 缺失 / 失效不再只显示「没听清」；超时 / 服务端 / 限流 / DNS 与「没听清」分开；
 * 控制台首页提醒条用上 Key 配置状态与录音权限。
 */
public class AsrFailureTest {

    static final String WS = "app/src/com/onesay/assistant/v2/wake/WakeService.java";
    static final String FSS = "app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java";

    // ------------------------------------------------------------------ 分型（通道真实错误串）

    @Test public void classifiesRealChannelErrors() {
        assertEquals(AsrFailure.Kind.AUTH, AsrFailure.classify("DashScope 鉴权失败(HTTP 401)：API Key 无效或无权访问该模型 —— {\"code\":\"InvalidApiKey\"}"));
        assertEquals(AsrFailure.Kind.AUTH, AsrFailure.classify("DashScope 鉴权失败(HTTP 403)：API Key 无效或无权访问该模型 —— x"));
        assertEquals(AsrFailure.Kind.AUTH, AsrFailure.classify("AUTH: HTTP 401 {\"message\":\"Unauthorized\"}"));
        assertEquals(AsrFailure.Kind.AUTH, AsrFailure.classify("无法获取 Token: AccessKey 不存在"));
        assertEquals(AsrFailure.Kind.RATE_LIMIT, AsrFailure.classify("DashScope HTTP 429：{\"code\":\"Throttling.RateQuota\"}"));
        assertEquals(AsrFailure.Kind.SERVER, AsrFailure.classify("DashScope HTTP 500：internal error"));
        assertEquals(AsrFailure.Kind.SERVER, AsrFailure.classify("HTTP 503: Service Unavailable"));
        assertEquals(AsrFailure.Kind.SERVER, AsrFailure.classify("DashScope HTTP 400：bad request"));
        assertEquals(AsrFailure.Kind.TIMEOUT, AsrFailure.classify("SocketTimeoutException: timeout"));
        assertEquals("IPv6 建连超时（R14 已知）", AsrFailure.Kind.TIMEOUT, AsrFailure.classify("SocketTimeoutException: failed to connect to dashscope.aliyuncs.com/2408:4005::1 (port 443) after 3000ms"));
        assertEquals(AsrFailure.Kind.TIMEOUT, AsrFailure.classify("网络异常: SocketTimeoutException: Read timed out"));
        assertEquals("DNS", AsrFailure.Kind.NETWORK, AsrFailure.classify("UnknownHostException: Unable to resolve host \"dashscope.aliyuncs.com\": No address associated with hostname"));
        assertEquals(AsrFailure.Kind.NETWORK, AsrFailure.classify("ConnectException: Failed to connect to /1.2.3.4:443"));
        assertEquals(AsrFailure.Kind.NETWORK, AsrFailure.classify("SSLHandshakeException: Connection closed by peer"));
        assertEquals(AsrFailure.Kind.NETWORK, AsrFailure.classify("网络异常: IOException: unexpected end of stream"));
        assertEquals(AsrFailure.Kind.NOT_CONFIGURED, AsrFailure.classify("配置不完整（缺 AppKey 或凭据）"));
        assertEquals(AsrFailure.Kind.NOT_CONFIGURED, AsrFailure.classify("云端 ASR 未启用"));
        // 只有这些才是「没听清」
        assertEquals(AsrFailure.Kind.NO_SPEECH, AsrFailure.classify(""));
        assertEquals(AsrFailure.Kind.NO_SPEECH, AsrFailure.classify(null));
        assertEquals(AsrFailure.Kind.NO_SPEECH, AsrFailure.classify("DashScope 返回为空（可能是纯静音或非语音音频）"));
        assertEquals(AsrFailure.Kind.NO_SPEECH, AsrFailure.classify("近空音频幻觉已丢弃（音频 0s / 转写 40 字）"));
        assertEquals(AsrFailure.Kind.NO_SPEECH, AsrFailure.classify("提示词回吐幻觉已丢弃"));
        assertEquals(AsrFailure.Kind.NO_SPEECH, AsrFailure.classify("识别成功但文本为空（可能是静音或纯噪声）"));
        assertEquals("认不出的失败不甩给用户的说法", AsrFailure.Kind.SERVER, AsrFailure.classify("JSONException: Value <html> of type java.lang.String"));
    }

    @Test public void copyAndChips() {
        for (AsrFailure.Kind k : AsrFailure.Kind.values()) {
            String line = AsrFailure.line(k, true);
            assertNotNull(line);
            if (k != AsrFailure.Kind.NO_SPEECH) {
                assertFalse(k + " 不能说没听清", AsrFailure.badge(k).contains("没听清") || line.contains("没听清"));
            }
            assertFalse("不用工程词", line.contains("唤醒") || line.contains("指令") || line.contains("..."));
        }
        assertEquals("语音识别未配置", AsrFailure.badge(AsrFailure.Kind.NOT_CONFIGURED));
        assertEquals(Collections.singletonList("去配置"), AsrFailure.chips(AsrFailure.Kind.NOT_CONFIGURED, false));
        assertEquals(Collections.singletonList("去配置"), AsrFailure.chips(AsrFailure.Kind.AUTH, true));
        assertTrue(AsrFailure.needsSetup(AsrFailure.Kind.AUTH) && AsrFailure.needsSetup(AsrFailure.Kind.NOT_CONFIGURED));
        for (AsrFailure.Kind k : Arrays.asList(AsrFailure.Kind.TIMEOUT, AsrFailure.Kind.RATE_LIMIT, AsrFailure.Kind.SERVER, AsrFailure.Kind.NETWORK)) {
            assertTrue(AsrFailure.retryable(k));
            assertEquals(Collections.singletonList("重试"), AsrFailure.chips(k, true));
            assertTrue("录音不在 → 没有重试芯片", AsrFailure.chips(k, false).isEmpty());
            assertTrue(AsrFailure.line(k, true).contains("重试"));
            assertFalse(AsrFailure.line(k, false).contains("重试"));
        }
        assertFalse(AsrFailure.retryable(AsrFailure.Kind.AUTH));
        assertFalse("没听清不重试（同一段录音结果一样）", AsrFailure.retryable(AsrFailure.Kind.NO_SPEECH));
        assertEquals("[ASR-FAIL] TIMEOUT", AsrFailure.logTag(AsrFailure.Kind.TIMEOUT));
    }

    @Test public void retryGate() {
        assertTrue(AsrFailure.mayRetry(true, 7, 7, false, false));
        assertFalse("会话已换（HIDE / 新唤醒 / ⌨）", AsrFailure.mayRetry(true, 7, 8, false, false));
        assertFalse("本窗已执行过指令", AsrFailure.mayRetry(true, 7, 7, true, false));
        assertFalse("录音不在", AsrFailure.mayRetry(false, 7, 7, false, false));
        assertFalse("最多重试一次", AsrFailure.mayRetry(true, 7, 7, false, true));
        assertFalse(AsrFailure.mayRetry(true, -1, -1, false, false));
    }

    @Test public void keyFingerprintNeverStoresKey() {
        String fp = AsrFailure.fingerprint("sk-abcdef1234567890");
        assertEquals(12, fp.length());
        assertFalse(fp.contains("abcdef1234"));
        assertEquals(fp, AsrFailure.fingerprint(" sk-abcdef1234567890 "));
        assertTrue(AsrFailure.keyStillInvalid(fp, AsrFailure.fingerprint("sk-abcdef1234567890")));
        assertFalse("换了 Key → 不再报失效", AsrFailure.keyStillInvalid(fp, AsrFailure.fingerprint("sk-new")));
        assertFalse(AsrFailure.keyStillInvalid("", AsrFailure.fingerprint("sk-x")));
        assertEquals("", AsrFailure.fingerprint(""));
    }

    // ------------------------------------------------------------------ 面板策略：未配置 / 失效不自动收起；可重试留够时间

    @Test public void errorPolicyForAsrCards() {
        ErrorStatePolicy.Plan setup = ErrorStatePolicy.plan(ErrorStatePolicy.Source.ASR_NEEDS_SETUP, true, false, false, false);
        assertTrue(setup.apply);
        assertEquals("不在 2.5 s 后收起", -1, setup.hideAfterMs);
        assertEquals("也不复位成中性态（文案留着）", -1, setup.neutralAfterMs);
        ErrorStatePolicy.Plan retry = ErrorStatePolicy.plan(ErrorStatePolicy.Source.ASR_RETRYABLE, true, false, false, false);
        assertEquals(-1, retry.neutralAfterMs);
        assertTrue("给时间点「重试」", retry.hideAfterMs > ErrorStatePolicy.ERROR_VISIBLE_MS);
        assertFalse("面板不在屏上照样不弹", ErrorStatePolicy.plan(ErrorStatePolicy.Source.ASR_NEEDS_SETUP, false, false, false, false).apply);
        assertEquals("确认卡未决优先", -1, ErrorStatePolicy.plan(ErrorStatePolicy.Source.ASR_RETRYABLE, true, true, false, false).hideAfterMs);
        assertEquals("老来源不变", ErrorStatePolicy.ERROR_VISIBLE_MS,
                ErrorStatePolicy.plan(ErrorStatePolicy.Source.NO_SPEECH_NEW_WAKE, true, false, false, false).hideAfterMs);
    }

    @Test public void newCardShowsAsrLineNotNoSpeech() {
        for (AsrFailure.Kind k : AsrFailure.Kind.values()) {
            if (k == AsrFailure.Kind.NO_SPEECH) continue;
            String b = AsrFailure.badge(k);
            assertEquals(b + " 是错误态（不能因为含「识别」被当成正在想）", WakeStage.Event.ERROR, WakeStage.classifyBadge(b));
            String line = WakeCopy.errorLine(b);
            assertNotEquals(WakeCopy.NO_SPEECH_LINE, line);
            assertFalse(line.contains("没听清"));
        }
        assertEquals("识别中照旧是 THINK", WakeStage.Event.THINK, WakeStage.classifyBadge("识别中"));
        assertEquals(WakeStage.Event.THINK, WakeStage.classifyBadge("云端识别中"));
        assertEquals(WakeCopy.NO_SPEECH_LINE, WakeCopy.errorLine("没听清"));
        assertEquals(WakeCopy.OFFLINE_LINE, WakeCopy.errorLine(WakeCopy.BADGE_OFFLINE));
    }

    // ------------------------------------------------------------------ 控制台：缺哪个显示哪个，不再「随时待命」

    static ConsoleStatus.Snapshot healthy() {
        ConsoleStatus.Snapshot s = new ConsoleStatus.Snapshot();
        s.wakeRunning = true;
        s.micReady = true;
        s.rootOk = true;
        s.daemonProbe = "ALIVE\nMOD\n# Bixby key takeover daemon - v8.1 x\nEND\n";
        s.llmConfigured = true;
        s.asrConfigured = true;
        s.asrKeyInvalid = false;
        s.micPermission = true;
        s.done = true;
        return s;
    }

    @Test public void consoleHeroAndBanners() {
        ConsoleStatus.Snapshot s = healthy();
        assertEquals("随时待命", ConsoleStatus.hero(s).headline);
        assertTrue(ConsoleStatus.banners(s).isEmpty());

        s.asrConfigured = false;
        assertEquals("需要处理", ConsoleStatus.hero(s).word);
        assertEquals("语音识别未配置", ConsoleStatus.hero(s).headline);
        assertEquals(ConsoleStatus.Level.BAD, ConsoleStatus.hero(s).level);
        List<ConsoleStatus.Banner> b = ConsoleStatus.banners(s);
        assertEquals(ConsoleStatus.Banner.ACT_ASR_KEY, b.get(0).actionId);
        assertEquals("去配置", b.get(0).action);

        s.micPermission = false;
        assertEquals("没有录音权限 · 语音识别未配置", ConsoleStatus.hero(s).headline);
        assertEquals(ConsoleStatus.Banner.ACT_MIC, ConsoleStatus.banners(s).get(0).actionId);
        assertEquals(ConsoleStatus.Banner.ACT_ASR_KEY, ConsoleStatus.banners(s).get(1).actionId);

        s = healthy();
        s.asrKeyInvalid = true;
        assertEquals("语音识别 Key 失效", ConsoleStatus.hero(s).headline);
        assertTrue(ConsoleStatus.banners(s).get(0).text.contains("失效"));

        s = healthy();
        s.micPermission = false;
        assertEquals("没有录音权限", ConsoleStatus.hero(s).headline);

        // 未知不出提醒条、不改大卡
        s = healthy();
        s.asrConfigured = null;
        s.asrKeyInvalid = null;
        s.micPermission = null;
        assertEquals("随时待命", ConsoleStatus.hero(s).headline);
        assertTrue(ConsoleStatus.banners(s).isEmpty());

        // 唤醒服务没跑仍然最先
        s = healthy();
        s.asrConfigured = false;
        s.wakeRunning = false;
        assertEquals(ConsoleStatus.Banner.ACT_WAKE, ConsoleStatus.banners(s).get(0).actionId);
    }

    // ------------------------------------------------------------------ 接线（源码扫描）

    static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) n++;
        return n;
    }

    @Test public void wakeServiceWiring() {
        String w = KernelWiring.src(WS);
        int tcr = w.indexOf("private boolean tryCloudRecognize(");
        String body = w.substring(tcr, w.indexOf("// ==================== ★ R14 流式识别", tcr));
        assertTrue("没配置：窗还开着就立刻收窗出卡", body.contains("if (asrNotConfigured()) {")
                && body.contains("closeCaptureAfterCloud(\"语音识别未配置\")"));
        assertFalse("不再只写日志就 return", body.contains("跳过：凭据不完整"));
        assertTrue("失败分型", body.contains("AsrFailure.classify(r.error)") && body.contains("showAsrFailure(fs, kind, myGen, canRetry)"));
        assertFalse("Key 失效不再走 2.5 s 收起的 showTerminalPrompt", body.contains("showTerminalPrompt(\"云端凭证失效"));
        assertTrue("只有空结果才没听清", body.contains("if (kind != com.onesay.assistant.v2.asr.AsrFailure.Kind.NO_SPEECH) {"));
        assertTrue("重试用同一段录音（缓冲原样，不另存副本）", body.contains("final boolean isRetry = mRetryPending;")
                && body.contains("final byte[] pcm = mPcmBuffer.snapshot();") && !w.contains("mRetryPcm"));
        assertTrue("重试那次失败不再给重试", body.contains("!isRetry && com.onesay.assistant.v2.asr.AsrFailure.retryable(kind)"));
        assertTrue("成功即丢录音", body.contains("clearRetry();   // r2/p0"));
        assertTrue("隐私闸门仍在最前", body.indexOf("[PRIVACY-REJECT]") < body.indexOf("asrNotConfigured()")
                && body.indexOf("[PRIVACY-REJECT]") < body.indexOf("final boolean isRetry"));
        // 收尾：未配置时不说没听清
        int miss = w.indexOf("private void notifyCaptureMiss(FloatingSheetService fs, int gen) {");
        String m = w.substring(miss, w.indexOf("\n    }\n", miss));
        assertTrue(m.indexOf("isStale(gen, mCaptureGen)") < m.indexOf("asrNotConfigured()")
                && m.indexOf("asrNotConfigured()") < m.indexOf("没听清，可再说一次"));
        // 重试闸门：同一会话、没执行过、最多一次
        int rl = w.indexOf("private boolean retryLastCloud() {");
        String r = w.substring(rl, w.indexOf("\n    }\n", rl));
        assertTrue(r.contains("AsrFailure.mayRetry(mPcmBuffer.size() > 0 && !mPcmBuffer.isActive(), mRetryGen, mCaptureGen, mGotCommand, mRetriedGen == mCaptureGen)"));
        assertTrue("录音缓冲只在开窗（代际号 +1）时清空 → 换会话后重试必然作废", w.indexOf("mCaptureGen++;\n") > 0
                && count(w, "mPcmBuffer.start();") == 1 && w.indexOf("mPcmBuffer.start();") > w.indexOf("private void armCaptureWindow("));
        // 隐私：留着的录音、转写都不进日志
        for (String line : w.split("\n")) {
            if (line.contains("Log.") && (line.contains("+ pcm)") || line.contains("+ pcm +") || line.contains("Arrays.toString(pcm"))) {
                fail("录音数据进了日志: " + line.trim());
            }
        }
    }

    @Test public void panelAndConsoleWiring() {
        String f = KernelWiring.src(FSS);
        int i = f.indexOf("public void showAsrFailure(");
        String s = f.substring(i, f.indexOf("private void showAsrChips(", i));
        assertTrue(s.contains("ErrorStatePolicy.Source.ASR_NEEDS_SETUP") && s.contains("ErrorStatePolicy.Source.ASR_RETRYABLE"));
        assertTrue("芯片在错误策略之后挂（策略会清推荐芯片）", s.indexOf("applyErrorPolicy(") < s.indexOf("showAsrChips("));
        assertTrue(f.contains("\"console:\" + com.onesay.assistant.v2.asr.AsrFailure.CONSOLE_PAGE"));
        assertTrue("重试走 WakeService 的同一段录音", f.contains("WakeService.retryCloudFromUi()"));
        int c = f.indexOf("private void cancelAutoHide() {");
        assertTrue("新一轮输入 / 收起时收掉识别失败芯片", f.substring(c, f.indexOf("}", c)).contains("hideAsrChips();"));
        assertTrue("只收自己的芯片", f.contains("((ActionChipsView) existing).owner == ASR_CHIPS_OWNER"));
        String con = KernelWiring.src("app/src/com/onesay/assistant/v2/ui/console/Console.java");
        int op = con.indexOf("public void openPage(String pageId, String title) {");
        assertTrue(con.indexOf("AsrFailure.CONSOLE_PAGE.equals(pageId)", op) > op && con.contains("public void editAsrKey(final Runnable after)"));
        int ek = con.indexOf("public void editAsrKey(");
        String ed = con.substring(ek, con.indexOf("/** 首页 Hero 总开关", ek));
        assertFalse("Key 输入框不回显明文", ed.contains("et.setText("));
        assertTrue(ed.contains("KeyMask.mask(cfg.getDashScopeKey())"));
        String home = KernelWiring.src("app/src/com/onesay/assistant/v2/ui/console/HomePage.java");
        assertTrue(home.contains("ConsoleStatus.Banner.ACT_ASR_KEY.equals(id)") && home.contains("ConsoleStatus.Banner.ACT_MIC.equals(id)"));
        String probe = KernelWiring.src("app/src/com/onesay/assistant/v2/ui/console/StatusProbe.java");
        assertTrue(probe.contains("s.asrConfigured = c.isEnabled() && (c.isComplete() || c.hasDashScopeKey());")
                && probe.contains("AsrHealth.isAuthFailed(app, c)") && probe.contains("b.asrKeyInvalid = a.asrKeyInvalid;"));
        String health = KernelWiring.src("app/src/com/onesay/assistant/v2/asr/AsrHealth.java");
        assertTrue("只存指纹", health.contains("AsrFailure.fingerprint(credential(c))"));
    }
}
