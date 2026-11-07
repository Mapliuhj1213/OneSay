package com.onesay.assistant.v2.net;

import com.onesay.assistant.v2.asr.RealtimeAsrSession;

import org.junit.Test;

import static org.junit.Assert.*;

/** 用户自填地址（实时识别 wss / NLS https）与模型名校验。 */
public class UserEndpointTest {
    @Test public void schemes() {
        assertEquals("wss://a.example.com/x", UserEndpoint.normalize(" wss://a.example.com/x ", "wss", true));
        assertNull(UserEndpoint.normalize("ws://a.example.com/x", "wss", true));
        assertNull(UserEndpoint.normalize("https://a.example.com/x", "wss", true));
        assertNull(UserEndpoint.normalize("http://a.example.com", "https", true));
        assertNull("带用户信息", UserEndpoint.normalize("https://x@evil.com/", "https", true));
        assertNull("带片段", UserEndpoint.normalize("https://a.example.com/#x", "https", true));
        assertNull("不许查询串", UserEndpoint.normalize("https://a.example.com/?a=1", "https", false));
        assertNotNull(UserEndpoint.normalize("https://a.example.com/?a=1", "https", true));
        assertNull(UserEndpoint.normalize("", "https", true));
    }

    @Test public void ids() {
        assertTrue(UserEndpoint.validId("qwen3-asr-flash-realtime"));
        assertTrue(UserEndpoint.validId("zh"));
        assertFalse(UserEndpoint.validId("a b"));
        assertFalse(UserEndpoint.validId("x/../y"));
        assertFalse(UserEndpoint.validId(""));
    }

    @Test public void realtimeUrl() {
        assertEquals(RealtimeAsrSession.DEFAULT_URL_BASE + RealtimeAsrSession.DEFAULT_MODEL, RealtimeAsrSession.urlFor(null));
        assertEquals("wss://h.example/rt?model=m1", RealtimeAsrSession.urlFor("wss://h.example/rt", "m1"));
        assertEquals("wss://h.example/rt?k=v&model=m1", RealtimeAsrSession.urlFor("wss://h.example/rt?k=v", "m1"));
    }

    @Test public void realtimeLanguage() throws Exception {
        try {
            RealtimeAsrSession.setLanguage("en");
            assertTrue(RealtimeAsrSession.sessionUpdateJson(700, null).contains("\"language\":\"en\""));
            RealtimeAsrSession.setLanguage("");
            assertFalse("空 = 让服务端自动判断", RealtimeAsrSession.sessionUpdateJson(700, null).contains("\"language\""));
            RealtimeAsrSession.setLanguage("bad lang");
            assertEquals(RealtimeAsrSession.DEFAULT_LANGUAGE, RealtimeAsrSession.language());
        } finally {
            RealtimeAsrSession.setLanguage(null);
        }
    }

    @Test public void nlsTokenRegion() {
        assertEquals("cn-shanghai", com.onesay.assistant.v2.asr.AliyunAsrConfig.tokenRegionFor("https://nls-meta.cn-shanghai.aliyuncs.com/"));
        assertEquals("ap-southeast-1", com.onesay.assistant.v2.asr.AliyunAsrConfig.tokenRegionFor("https://nls-meta.ap-southeast-1.aliyuncs.com/"));
        assertEquals("cn-shanghai", com.onesay.assistant.v2.asr.AliyunAsrConfig.tokenRegionFor("https://proxy.example.com/token"));
        assertTrue(com.onesay.assistant.v2.asr.AliyunAsrConfig.presetAsrEndpoint("cn-beijing").contains("nls-gateway-cn-beijing"));
    }
}
