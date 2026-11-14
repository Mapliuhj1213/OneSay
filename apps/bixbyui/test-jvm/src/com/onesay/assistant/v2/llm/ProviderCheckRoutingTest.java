package com.onesay.assistant.v2.llm;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

/** priv-llm：「测试」按钮（注入录制回复，不联网）与主对话路由的纯逻辑。 */
public class ProviderCheckRoutingTest {

    static final class Fake implements ProviderHttp.Transport {
        WireRequest req;
        String key;
        int status = 200;
        String body = "";
        IOException fail;

        @Override public ProviderHttp.Response exec(WireRequest r, String k, int c, int rd) throws IOException {
            req = r;
            key = k;
            if (fail != null) throw fail;
            return new ProviderHttp.Response(status, body);
        }
    }

    @After public void reset() {
        ProviderRouting.set(null);
    }

    @Test public void checkSendsKeyOnlyThroughHeaderSlot() throws Exception {
        Fake f = new Fake();
        f.body = ProviderAdaptersTest.fx("anthropic_models.json");
        ProviderErrors.Verdict v = ProviderCheck.run(ProviderKind.ANTHROPIC, "https://api.anthropic.com/v1/messages", "claude-sonnet-4-5", " sk-ant-FAKE ", f);
        assertTrue(v.toString(), v.ok());
        assertEquals("https://api.anthropic.com/v1/models", f.req.url);
        assertEquals("Key 去掉首尾空白", "sk-ant-FAKE", f.key);
        assertFalse("请求对象里没有 Key", f.req.toString().contains("FAKE"));
        assertFalse(f.req.url.contains("FAKE"));
    }

    @Test public void checkVerdicts() throws Exception {
        Fake f = new Fake();
        assertEquals(ProviderErrors.Kind.AUTH, ProviderCheck.run(ProviderKind.GEMINI, "https://generativelanguage.googleapis.com", "", "", f).kind);
        assertNull("没 Key 不发请求", f.req);
        ProviderErrors.Verdict bad = ProviderCheck.run(ProviderKind.OPENAI_COMPAT, "http://evil.example.com", "m", "k", f);
        assertFalse(bad.ok());
        assertTrue(bad.message.contains("https"));
        assertNull(f.req);
        assertFalse(ProviderCheck.run(ProviderKind.OPENAI_COMPAT, "https://api.deepseek.com", "a b", "k", f).ok());
        f.status = 302;
        ProviderErrors.Verdict redir = ProviderCheck.run(ProviderKind.OPENAI_COMPAT, "https://api.deepseek.com", "deepseek-flash", "k", f);
        assertFalse(redir.ok());
        assertTrue(redir.message.contains("跳转"));
        f.status = 401;
        f.body = ProviderAdaptersTest.fx("openai_error_401.json");
        assertEquals(ProviderErrors.Kind.AUTH, ProviderCheck.run(ProviderKind.OPENAI_COMPAT, "https://api.deepseek.com", "deepseek-flash", "k", f).kind);
        f.fail = new java.net.UnknownHostException("api.deepseek.com");
        ProviderErrors.Verdict net = ProviderCheck.run(ProviderKind.OPENAI_COMPAT, "https://api.deepseek.com", "deepseek-flash", "k", f);
        assertEquals(ProviderErrors.Kind.NETWORK, net.kind);
        assertFalse("网络错误不带主机名等原文", net.message.contains("api.deepseek.com"));
    }

    @Test public void routeForRules() {
        ProviderRoute.KeySource k = new ProviderRoute.KeySource() { public String key() { return " k1 "; } };
        assertNull("DeepSeek = 原通道", ProviderStore.routeFor(ProviderPreset.get("deepseek"), "https://api.deepseek.com", "deepseek-flash", k));
        assertNull("没选", ProviderStore.routeFor(null, null, null, k));
        assertNull("没填模型 → 原通道", ProviderStore.routeFor(ProviderPreset.get("anthropic"), "https://api.anthropic.com", "", k));
        assertNull("地址不合法 → 原通道", ProviderStore.routeFor(ProviderPreset.get("custom_openai"), "", "m", k));
        ProviderRoute r = ProviderStore.routeFor(ProviderPreset.get("gemini"), "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash", k);
        assertNotNull(r);
        assertEquals("https://generativelanguage.googleapis.com", r.base);
        assertEquals("k1", r.key());
        assertFalse(r.toString().contains("k1"));
        ProviderRouting.set(r);
        assertEquals("k1", ProviderRouting.mainKeyOverride());
        assertTrue(ProviderRouting.allowsHost("https://generativelanguage.googleapis.com/v1beta/models/x:generateContent"));
        assertFalse("别的主机不放行", ProviderRouting.allowsHost("https://evil.example.com/v1beta"));
        assertFalse("http 不放行", ProviderRouting.allowsHost("http://generativelanguage.googleapis.com/x"));
        ProviderRouting.set(null);
        assertNull(ProviderRouting.mainKeyOverride());
        assertFalse(ProviderRouting.allowsHost("https://generativelanguage.googleapis.com/x"));
    }

    /** 给 net 包的 LlmClient 路由测试开本机假服务器（http://127.0.0.1）。 */
    public static void allowLoopback(boolean on) {
        ProviderUrls.sTestAllowLoopback = on;
    }
}
