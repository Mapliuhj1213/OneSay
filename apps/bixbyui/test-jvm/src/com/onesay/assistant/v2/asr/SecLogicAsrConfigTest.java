package com.onesay.assistant.v2.asr;

import org.junit.Test;

import static org.junit.Assert.*;

/** region 白名单 + remote_token_url 强制 https。 */
public class SecLogicAsrConfigTest {

    @Test public void region_whitelist() {
        assertTrue(AliyunAsrConfig.isValidRegion("cn-shanghai"));
        assertTrue(AliyunAsrConfig.isValidRegion("cn-beijing"));
        assertTrue(AliyunAsrConfig.isValidRegion("ap-southeast"));
        assertFalse(AliyunAsrConfig.isValidRegion("x@evil.com/#"));
        assertFalse(AliyunAsrConfig.isValidRegion("cn-shanghai.evil.com"));
        assertFalse(AliyunAsrConfig.isValidRegion("CN-SHANGHAI"));
        assertFalse(AliyunAsrConfig.isValidRegion("cn-shanghai-1"));
        assertEquals(AliyunAsrConfig.DEFAULT_REGION, AliyunAsrConfig.sanitizeRegion("x@evil.com/#"));
        assertEquals("cn-beijing", AliyunAsrConfig.sanitizeRegion(" cn-beijing "));
        assertEquals(AliyunAsrConfig.DEFAULT_REGION, AliyunAsrConfig.sanitizeRegion(""));
    }

    @Test public void remote_token_url_must_be_https() {
        assertTrue(AliyunAsrConfig.isAllowedRemoteTokenUrl("https://token.example.com/issue?k=1"));
        assertTrue(AliyunAsrConfig.isAllowedRemoteTokenUrl("HTTPS://token.example.com/"));
        assertFalse(AliyunAsrConfig.isAllowedRemoteTokenUrl("http://token.example.com/issue"));
        assertFalse(AliyunAsrConfig.isAllowedRemoteTokenUrl("file:///sdcard/x"));
        assertFalse(AliyunAsrConfig.isAllowedRemoteTokenUrl("https://"));
        assertFalse(AliyunAsrConfig.isAllowedRemoteTokenUrl(null));
    }
}
