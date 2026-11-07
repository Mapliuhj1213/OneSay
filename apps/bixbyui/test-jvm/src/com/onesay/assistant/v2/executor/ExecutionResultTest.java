package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ExecutionResultTest {

    private static Capability cap(String sign) {
        return new Capability.Builder(sign, "测试能力").build();
    }

    @Test public void defaultIsUnverified() {
        ExecutionResult r = new ExecutionResult(true, ErrorCode.OK,
                (ExecutionResult.Verification) null, "ok", null, null, cap("a"), null);
        assertEquals(ExecutionResult.Verification.UNVERIFIED, r.verification);
        assertFalse(r.verified);
    }

    @Test public void legacyBooleanMapping() {
        assertEquals(ExecutionResult.Verification.VERIFIED,
                ExecutionResult.success(cap("a"), null, "x", true, "").verification);
        ExecutionResult u = ExecutionResult.success(cap("a"), null, "x", false, "");
        assertEquals(ExecutionResult.Verification.UNVERIFIED, u.verification);
        assertFalse(u.verified);
        ExecutionResult vf = ExecutionResult.failed(ErrorCode.VERIFY_FAILED, "未生效", "d", cap("a"), null);
        assertEquals(ExecutionResult.Verification.FAILED, vf.verification);
        ExecutionResult ef = ExecutionResult.failed(ErrorCode.EXEC_FAILED, "失败", "d", cap("a"), null);
        assertEquals(ExecutionResult.Verification.UNVERIFIED, ef.verification);
        // 旧 7 参构造
        ExecutionResult old = new ExecutionResult(true, ErrorCode.OK, true, "m", null, cap("a"), null);
        assertTrue(old.verified);
        assertEquals("", old.detail);
    }

    @Test public void observationCarriesCodeAndHint() throws Exception {
        ExecutionResult r = ExecutionResult.failed(ErrorCode.NOT_CONFIGURED, "邮件还没配置",
                "mail not configured", MailBridge.NOT_CONFIGURED_HINT, cap("mail.inbox.latest"), new JSONObject());
        JSONObject o = new JSONObject(r.toModelObservation());
        assertFalse(o.getBoolean("ok"));
        assertEquals("NOT_CONFIGURED", o.getString("code"));
        assertEquals("UNVERIFIED", o.getString("verified"));
        assertFalse(o.getBoolean("retryable"));
        assertTrue(o.getString("hint").contains("QQ邮箱需开启IMAP并使用授权码"));
        assertEquals("mail not configured", o.getString("detail"));
    }

    @Test public void observationDefaultHintAndTruncation() throws Exception {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1000; i++) big.append('x');
        ExecutionResult r = ExecutionResult.failed(ErrorCode.CHANNEL_TIMEOUT, "超时", big.toString(), cap("a"), null);
        JSONObject o = new JSONObject(r.toModelObservation());
        assertEquals(ErrorCode.CHANNEL_TIMEOUT.defaultHint, o.getString("hint"));
        assertTrue(o.getBoolean("retryable"));
        assertEquals(ExecutionResult.OBS_DETAIL_MAX, o.getString("detail").length());
    }

    @Test public void successObservationIsLean() throws Exception {
        ExecutionResult r = ExecutionResult.success(cap("a"), null, "已打开蓝牙", ExecutionResult.Verification.VERIFIED, "1");
        JSONObject o = new JSONObject(r.toModelObservation());
        assertTrue(o.getBoolean("ok"));
        assertEquals("OK", o.getString("code"));
        assertEquals("VERIFIED", o.getString("verified"));
        assertFalse(o.has("detail"));
        assertFalse(o.has("retryable"));
        assertFalse(o.has("hint"));
    }

    @Test public void everyNonOkCodeHasHint() {
        for (ErrorCode c : ErrorCode.values()) {
            if (c == ErrorCode.OK) continue;
            assertTrue(c.name(), c.defaultHint.length() > 0);
        }
    }
}
