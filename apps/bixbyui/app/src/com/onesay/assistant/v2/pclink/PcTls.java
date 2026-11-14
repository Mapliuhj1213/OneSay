package com.onesay.assistant.v2.pclink;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * [pclink v2] 传输加密：每台设备第一次运行时生成自己的 EC P-256 钥匙和自签证书（不靠任何 CA），
 * 指纹 = SHA-256(证书 DER)，base64url 无填充（43 字符），配对串里带电脑的指纹。
 *
 * <ul>
 *   <li>手机连电脑：TLS，手机只认指纹等于配对串里那一个的证书（钉死，不信任系统 CA，不查主机名）；</li>
 *   <li>双向：电脑要求对方出示证书；对方证书指纹写在签名的 hello 里（cfp），电脑核对后按设备 id 记住（首次信任，
 *       之后不一致就拒，换钥匙才清）；</li>
 *   <li>TLS 只是外层，里面每条指令照旧 HMAC 签名 + 时间窗 + 一次性编号。</li>
 * </ul>
 * 指纹的做法参考 LocalSend（Apache-2.0）协议说明里「HTTPS 时指纹 = 证书 SHA-256」的思路；代码是自己写的。
 * [pclink v2] 与电脑端 {@code com.onesay.desktop.Tls}（0.5.0）同一份算法（证书 DER 结构、指纹编码），互通测试会比对。
 * Android 8（API 26）可用：EC P-256、SHA256withECDSA、TLS 1.2；纯 Java，JVM 可测。
 */
public final class PcTls {
    private static final SecureRandom RNG = new SecureRandom();
    /** ecdsa-with-SHA256 1.2.840.10045.4.3.2 */
    private static final byte[] OID_ECDSA_SHA256 = {0x06, 0x08, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x04, 0x03, 0x02};
    /** commonName 2.5.4.3 */
    private static final byte[] OID_CN = {0x06, 0x03, 0x55, 0x04, 0x03};

    public final PrivateKey key;
    public final X509Certificate cert;
    public final String fp;

    private PcTls(PrivateKey key, X509Certificate cert) {
        this.key = key;
        this.cert = cert;
        this.fp = fingerprint(cert);
    }

    /** 新生成一对（几十毫秒）。cn 只是给人看的名字。 */
    public static PcTls generate(String cn) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), RNG);
            KeyPair kp = g.generateKeyPair();
            byte[] der = selfSigned(kp, cn, System.currentTimeMillis());
            return new PcTls(kp.getPrivate(), parseCert(der));
        } catch (Exception e) {
            throw new IllegalStateException("生成证书失败：" + e, e);
        }
    }

    /** 从存储的 base64（PKCS8 私钥 + 证书 DER）还原；坏了返回 null。 */
    public static PcTls load(String keyB64, String certB64) {
        if (keyB64 == null || certB64 == null || keyB64.isEmpty() || certB64.isEmpty()) return null;
        try {
            PrivateKey k = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(PcB64.decode(keyB64)));
            return new PcTls(k, parseCert(PcB64.decode(certB64)));
        } catch (Exception e) {
            return null;
        }
    }

    public String keyB64() { return PcB64.encodeUrl(key.getEncoded()); }

    public String certB64() {
        try { return PcB64.encodeUrl(cert.getEncoded()); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ------------------------------------------------------------------ 指纹

    public static String fingerprint(X509Certificate c) {
        try { return fingerprint(c.getEncoded()); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String fingerprint(byte[] certDer) {
        try {
            return PcB64.encodeUrl(MessageDigest.getInstance("SHA-256").digest(certDer));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 指纹格式：43 个 base64url 字符（32 字节）。 */
    public static boolean validFp(String fp) {
        if (fp == null || !fp.matches("[A-Za-z0-9_-]{43}")) return false;
        try { return PcB64.decode(fp).length == 32; } catch (IllegalArgumentException e) { return false; }
    }

    /** 给人核对用：前 8 个十六进制字符，4 位一组。 */
    public static String shortFp(String fp) {
        if (!validFp(fp)) return "";
        byte[] b = PcB64.decode(fp);
        return String.format(Locale.ROOT, "%02X%02X-%02X%02X", b[0], b[1], b[2], b[3]);
    }

    static boolean fpEquals(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(PcProtocol.ASCII), b.getBytes(PcProtocol.ASCII));
    }

    // ------------------------------------------------------------------ SSLContext

    /** 服务端（电脑）：出示自己的证书，要求对方出示任意证书（是谁由签名的 hello + 记住的指纹决定）。 */
    public SSLContext serverContext() { return context(null); }

    /** 客户端：出示自己的证书，只认指纹为 pinnedFp 的服务端。 */
    public SSLContext clientContext(String pinnedFp) {
        if (!validFp(pinnedFp)) throw new IllegalArgumentException("指纹格式不对");
        return context(pinnedFp);
    }

    private SSLContext context(String pin) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(new KeyManager[]{new OwnKey()}, new TrustManager[]{new Pin(pin)}, RNG);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private final class OwnKey extends X509ExtendedKeyManager {
        @Override public String[] getClientAliases(String t, Principal[] i) { return new String[]{"me"}; }
        @Override public String chooseClientAlias(String[] t, Principal[] i, Socket s) { return "me"; }
        @Override public String[] getServerAliases(String t, Principal[] i) { return new String[]{"me"}; }
        @Override public String chooseServerAlias(String t, Principal[] i, Socket s) { return t == null || t.startsWith("EC") ? "me" : null; }
        @Override public String chooseEngineClientAlias(String[] t, Principal[] i, SSLEngine e) { return "me"; }
        @Override public String chooseEngineServerAlias(String t, Principal[] i, SSLEngine e) { return chooseServerAlias(t, i, null); }
        @Override public X509Certificate[] getCertificateChain(String a) { return new X509Certificate[]{cert}; }
        @Override public PrivateKey getPrivateKey(String a) { return key; }
    }

    /** pin == null：接受任何对方证书（服务端收客户端证书）；否则只认这个指纹。不查主机名、不查有效期、不信系统 CA。 */
    static final class Pin extends X509ExtendedTrustManager {
        private final String pin;

        Pin(String pin) { this.pin = pin; }

        private void check(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("对方没出示证书");
            if (pin != null && !fpEquals(pin, fingerprint(chain[0]))) throw new CertificateException("证书指纹不对（不是配对的那台设备）");
        }

        @Override public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException { check(c); }
        @Override public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException { check(c); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException { check(c); }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException { check(c); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException { check(c); }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException { check(c); }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    // ------------------------------------------------------------------ 自签证书（最小 DER 编码，两端同一份算法）

    static X509Certificate parseCert(byte[] der) throws CertificateException {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    /** X.509 v3 自签证书：序列号 16 字节随机、ecdsa-with-SHA256、CN=cn、有效期 当前−1 天 ~ +20 年（不超过 2049 年底，UTCTime）。 */
    static byte[] selfSigned(KeyPair kp, String cn, long nowMs) throws Exception {
        byte[] serial = new byte[16];
        RNG.nextBytes(serial);
        serial[0] &= 0x7F;
        serial[0] |= 0x01;
        byte[] alg = seq(OID_ECDSA_SHA256);
        byte[] name = seq(set(seq(OID_CN, tlv(0x0C, cn.getBytes(PcProtocol.UTF8)))));
        long from = nowMs - 86400000L;
        long to = Math.min(nowMs + 20L * 365 * 86400000L, 2524607999000L); // 2049-12-31T23:59:59Z
        byte[] validity = seq(utc(from), utc(to));
        byte[] tbs = seq(tlv(0xA0, tlv(0x02, new byte[]{2})), tlv(0x02, serial), alg, name, validity, name, kp.getPublic().getEncoded());
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        return seq(tbs, alg, tlv(0x03, bits));
    }

    private static byte[] utc(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(new Date(ms)).getBytes(PcProtocol.ASCII));
    }

    private static byte[] seq(byte[]... parts) { return tlv(0x30, cat(parts)); }

    private static byte[] set(byte[]... parts) { return tlv(0x31, cat(parts)); }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    static byte[] tlv(int tag, byte[] v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = v.length;
        if (n < 128) o.write(n);
        else if (n < 256) { o.write(0x81); o.write(n); }
        else if (n < 65536) { o.write(0x82); o.write(n >>> 8); o.write(n & 0xFF); }
        else { o.write(0x83); o.write(n >>> 16); o.write((n >>> 8) & 0xFF); o.write(n & 0xFF); }
        o.write(v, 0, n);
        return o.toByteArray();
    }
}
