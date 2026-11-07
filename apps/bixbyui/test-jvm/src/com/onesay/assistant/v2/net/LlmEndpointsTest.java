package com.onesay.assistant.v2.net;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Test;

/** 用户可配置的大模型端点：只认 https、规范化、Key 只发往已配置主机。 */
public class LlmEndpointsTest {

    @After public void reset() { LlmEndpoints.setForProcess(null, null); }

    @Test public void presetsAreHttps() {
        assertTrue(LlmEndpoints.DEEPSEEK_PRESET.startsWith("https://"));
        assertTrue(LlmEndpoints.DASHSCOPE_PRESET.startsWith("https://"));
        assertEquals("https://api.deepseek.com/chat/completions", LlmEndpoints.deepseekChatUrl());
        assertEquals("https://api.deepseek.com/models", LlmEndpoints.deepseekModelsUrl());
    }

    @Test public void normalize() {
        assertEquals("https://x.example.com/v1", LlmEndpoints.normalize(" https://x.example.com/v1/ "));
        assertEquals("https://x.example.com/v1", LlmEndpoints.normalize("https://x.example.com/v1/chat/completions"));
        assertEquals("https://x.example.com", LlmEndpoints.normalize("https://x.example.com/models"));
        assertNull(LlmEndpoints.normalize("http://x.example.com"));
        assertNull(LlmEndpoints.normalize("ftp://x.example.com"));
        assertNull(LlmEndpoints.normalize("https://user:pw@x.example.com"));
        assertNull(LlmEndpoints.normalize("https://x.example.com/?k=1"));
        assertNull(LlmEndpoints.normalize("https:///nohost"));
        assertNull(LlmEndpoints.normalize(""));
    }

    @Test public void keyOnlyToConfiguredHost() {
        LlmEndpoints.setForProcess("https://llm.example.org/v1", null);
        assertEquals("https://llm.example.org/v1/chat/completions", LlmEndpoints.deepseekChatUrl());
        assertTrue(LlmEndpoints.keyAllowedFor("https://llm.example.org/v1/chat/completions"));
        assertTrue(LlmEndpoints.keyAllowedFor(LlmEndpoints.dashscopeChatUrl()));
        assertFalse("旧预置主机不再带 Key", LlmEndpoints.keyAllowedFor("https://api.deepseek.com/chat/completions"));
        assertFalse(LlmEndpoints.keyAllowedFor("http://llm.example.org/v1/chat/completions"));
        assertFalse(LlmEndpoints.keyAllowedFor("https://evil.example.net/v1/chat/completions"));
        assertFalse(LlmEndpoints.keyAllowedFor(null));
    }

    @Test public void invalidFallsBackToPreset() {
        LlmEndpoints.setForProcess("http://insecure", "nonsense");
        assertEquals(LlmEndpoints.DEEPSEEK_PRESET, LlmEndpoints.deepseekBase());
        assertEquals(LlmEndpoints.DASHSCOPE_PRESET, LlmEndpoints.dashscopeBase());
    }
}
