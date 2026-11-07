package com.onesay.assistant.v2.executor;

import org.junit.Test;

import java.util.Properties;

import static org.junit.Assert.*;

/** P0-7：IMAP 永远严格校验；TLS 失败不重试、不降级，并给出 TLS_ERROR。 */
public class MailBridgeSecurityTest {

    @Test public void propsNeverTrustAll() {
        Properties p = MailBridge.props("imap.qq.com", 993, "");
        assertNull("不允许出现 ssl.trust", p.getProperty("mail.imaps.ssl.trust"));
        assertEquals("true", p.getProperty("mail.imaps.ssl.checkserveridentity"));
        assertEquals("true", p.getProperty("mail.imaps.ssl.enable"));
        assertEquals("imap.qq.com", p.getProperty("mail.imaps.host"));
        Properties withProxy = MailBridge.props("imap.gmail.com", 993, "socks5://127.0.0.1:1080");
        assertNull(withProxy.getProperty("mail.imaps.ssl.trust"));
        assertEquals("127.0.0.1", withProxy.getProperty("mail.imaps.socks.host"));
    }

    @Test public void certificateFailuresAreTlsErrorsAndNotRetried() {
        Exception cert = new javax.mail.MessagingException("connect failed",
                new javax.net.ssl.SSLHandshakeException(
                        "java.security.cert.CertPathValidatorException: Trust anchor for certification path not found."));
        assertTrue(MailBridge.isTlsValidationFailure(cert));
        assertFalse("证书错误不能当链路抖动重试", MailBridge.isTransient(cert));
        assertEquals(ErrorCode.TLS_ERROR, MailBridge.classifyError(cert));

        Exception host = new javax.mail.MessagingException("Could not connect",
                new java.io.IOException("Can't verify identity of server: imap.qq.com"));
        assertEquals(ErrorCode.TLS_ERROR, MailBridge.classifyError(host));

        Exception peer = new javax.net.ssl.SSLPeerUnverifiedException("hostname not verified");
        assertEquals(ErrorCode.TLS_ERROR, MailBridge.classifyError(peer));
    }

    @Test public void plainSslDropIsStillTransient() {
        Exception drop = new javax.mail.MessagingException("x",
                new javax.net.ssl.SSLException("Connection closed by peer"));
        assertFalse(MailBridge.isTlsValidationFailure(drop));
        assertTrue(MailBridge.isTransient(drop));
        assertEquals(ErrorCode.NETWORK_ERROR, MailBridge.classifyError(drop));
    }

    @Test public void otherClassifications() {
        assertEquals(ErrorCode.AUTH_FAILED, MailBridge.classifyError(
                new javax.mail.AuthenticationFailedException("[AUTHENTICATIONFAILED] Invalid credentials")));
        assertEquals(ErrorCode.TIMEOUT, MailBridge.classifyError(
                new javax.mail.MessagingException("x", new java.net.SocketTimeoutException("connect timed out"))));
        assertEquals(ErrorCode.NETWORK_ERROR, MailBridge.classifyError(
                new javax.mail.MessagingException("x", new java.net.UnknownHostException("imap.gmail.com"))));
        assertEquals(ErrorCode.EXEC_FAILED, MailBridge.classifyError(new IllegalStateException("folder closed")));
    }

    @Test public void changingHostDropsStoredPassword() {
        assertTrue(MailBridge.mustDropStoredPassword("imap.qq.com", "evil.example.com"));
        assertFalse(MailBridge.mustDropStoredPassword("imap.qq.com", "IMAP.QQ.COM"));
        assertFalse("首次配置没有旧授权码", MailBridge.mustDropStoredPassword("", "imap.qq.com"));
        assertFalse("没给新 host = 不改 host", MailBridge.mustDropStoredPassword("imap.qq.com", null));
    }
}
