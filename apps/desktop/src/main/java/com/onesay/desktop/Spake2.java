package com.onesay.desktop;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * [rel-1123 合并] 固定配对码用的 SPAKE2（口令认证密钥交换）。自己写的，结构照 RFC 9382（SPAKE2），群用 RFC 3526 的 2048 位 MODP 群（group 14，安全素数 p = 2q + 1，
 * g = 2 生成 q 阶子群）。手机端 {@code pclink.PcSpake2} 是同一份算法（逐字对应，两边测试锁同一组已知答案）。
 *
 * <p>为什么用它：4 位配对码只有一万种。直接发码、发码的哈希、或者用码加密点东西发出去，旁听的人 / 冒充的电脑都能离线把一万种试一遍。
 * SPAKE2 里每一方发出的只是 g^x·M^w（w 由配对码算出、x 是一次性随机数），没有码也算不出同一把会话密钥；
 * 冒充的一方每连一次只能「猜一个码」（在线猜），所以电脑端限次（{@link LanPair.Lock}）就能挡住暴力试。
 *
 * <ul>
 *   <li>A = 手机（发起方）：X = g^x · M^w；B = 电脑：Y = g^y · N^w。</li>
 *   <li>A：K = (Y · N^-w)^x；B：K = (X · M^-w)^y。对方的元素先查在 q 阶子群里（1 &lt; Y &lt; p-1 且 Y^q = 1），不在就拒。</li>
 *   <li>TT = 带长度前缀拼接（上下文、手机 id、电脑 id、两边证书指纹、盐、轮数、X、Y、K、w），h = SHA-256(TT)；
 *       确认码 cA = HMAC(HMAC(h,"confirm-A"), h)、cB = HMAC(HMAC(h,"confirm-B"), h)，比较用定长比较。证书指纹进了 TT，所以中间人（两边看到的证书不一样）对不上。</li>
 *   <li>M、N 由公开的种子串哈希到群里再平方（没人知道它们相对 g 的离散对数）。</li>
 *   <li>w = PBKDF2-HMAC-SHA256(配对码, 盐, 轮数) mod q；电脑只存 w（在 Windows 凭据管理器里），不存码本身。</li>
 *   <li>用到 w 的幂运算做指数盲化（指数加上随机倍数的 q），减少按耗时猜 w 的可能。</li>
 * </ul>
 */
public final class Spake2 {

    private Spake2() {}

    static final BigInteger P = new BigInteger("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF", 16);
    static final BigInteger Q = P.subtract(BigInteger.ONE).shiftRight(1);
    static final BigInteger G = BigInteger.valueOf(2);
    static final BigInteger M = toGroup("OneSay SPAKE2 M v1"), N = toGroup("OneSay SPAKE2 N v1");
    public static final int ELEM_BYTES = 256, W_BYTES = 32, SALT_BYTES = 16;
    public static final int ITER_DEFAULT = 60_000, ITER_MIN = 10_000, ITER_MAX = 300_000;
    static final String CONTEXT = "OneSay-pair-spake2-v1";
    private static final SecureRandom RNG = new SecureRandom();

    /** 公开种子 → 群里的一个元素：SHA-256 计数器展开到 288 字节、mod p、再平方（落进 q 阶子群）。 */
    static BigInteger toGroup(String seed) {
        byte[] out = new byte[288];
        for (int i = 0; i < 9; i++) System.arraycopy(sha(cat(seed.getBytes(StandardCharsets.UTF_8), new byte[]{(byte) i})), 0, out, i * 32, 32);
        BigInteger r = new BigInteger(1, out).mod(P).modPow(BigInteger.valueOf(2), P);
        if (r.compareTo(BigInteger.ONE) <= 0) throw new IllegalStateException("bad seed");
        return r;
    }

    /** 配对码 → w（32 字节）。 */
    public static byte[] w(String code, byte[] salt, int iter) {
        if (code == null || salt == null || iter < ITER_MIN || iter > ITER_MAX) throw new IllegalArgumentException("参数不对");
        try {
            PBEKeySpec spec = new PBEKeySpec(code.toCharArray(), salt, iter, W_BYTES * 8);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] salt() {
        byte[] s = new byte[SALT_BYTES];
        RNG.nextBytes(s);
        return s;
    }

    static BigInteger scalar(byte[] w) {
        BigInteger s = new BigInteger(1, w).mod(Q);
        return s.signum() == 0 ? BigInteger.ONE : s;
    }

    /** base^e，指数加随机倍数的 q（base 必须在 q 阶子群里）。 */
    static BigInteger powBlind(BigInteger base, BigInteger e) {
        byte[] r = new byte[8];
        RNG.nextBytes(r);
        return base.modPow(e.add(Q.multiply(new BigInteger(1, r))), P);
    }

    static BigInteger randomScalar() {
        while (true) {
            BigInteger x = new BigInteger(Q.bitLength() + 64, RNG).mod(Q);
            if (x.signum() > 0) return x;
        }
    }

    /** 对方发来的元素：定长、1 &lt; v &lt; p-1、在 q 阶子群里；不对返回 null。 */
    static BigInteger element(byte[] b) {
        if (b == null || b.length != ELEM_BYTES) return null;
        BigInteger v = new BigInteger(1, b);
        if (v.compareTo(BigInteger.ONE) <= 0 || v.compareTo(P.subtract(BigInteger.ONE)) >= 0) return null;
        if (!v.modPow(Q, P).equals(BigInteger.ONE)) return null;
        return v;
    }

    static byte[] fixed(BigInteger v) {
        byte[] b = v.toByteArray(), out = new byte[ELEM_BYTES];
        int n = Math.min(b.length, ELEM_BYTES);
        System.arraycopy(b, b.length - n, out, ELEM_BYTES - n, n);
        return out;
    }

    /** 证书指纹统一写法：只留十六进制字符、小写（两边写法不一样也算同一张）。 */
    public static String normFp(String fp) {
        return fp == null ? "" : fp.replaceAll("[^0-9A-Fa-f]", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** 会话的公开上下文（两边必须一样，否则对不上）。 */
    public static final class Ctx {
        final String phoneId, pcId, phoneFp, pcFp;
        final byte[] salt;
        final int iter;

        public Ctx(String phoneId, String pcId, String phoneFp, String pcFp, byte[] salt, int iter) {
            this.phoneId = phoneId == null ? "" : phoneId;
            this.pcId = pcId == null ? "" : pcId;
            this.phoneFp = normFp(phoneFp);
            this.pcFp = normFp(pcFp);
            this.salt = salt == null ? new byte[0] : salt.clone();
            this.iter = iter;
        }
    }

    /** 两边算出的确认码；cA 手机发、cB 电脑发。 */
    public static final class Keys {
        public final byte[] cA, cB, h;

        Keys(byte[] cA, byte[] cB, byte[] h) {
            this.cA = cA;
            this.cB = cB;
            this.h = h;
        }
    }

    /** 一方。用一次就丢。 */
    public static final class Party {
        final boolean isA;
        final BigInteger w, x, mine;
        final byte[] wBytes;

        /** isA = 手机（发起方）。 */
        public Party(boolean isA, byte[] wBytes) {
            this(isA, wBytes, randomScalar());
        }

        /** 测试用：固定随机数（已知答案测试）。 */
        Party(boolean isA, byte[] wBytes, BigInteger x) {
            if (wBytes == null || wBytes.length != W_BYTES) throw new IllegalArgumentException("w 不对");
            this.isA = isA;
            this.wBytes = wBytes.clone();
            this.w = scalar(wBytes);
            this.x = x.mod(Q);
            this.mine = G.modPow(this.x, P).multiply(powBlind(isA ? M : N, w)).mod(P);
        }

        /** 发给对方的那一条（256 字节）。 */
        public byte[] message() { return fixed(mine); }

        /** 收到对方那一条：算确认码；对方的元素不对返回 null。 */
        public Keys finish(byte[] peer, Ctx c) {
            BigInteger other = element(peer);
            if (other == null || c == null) return null;
            BigInteger unblind = powBlind(isA ? N : M, Q.subtract(w)); // (N 或 M)^-w
            BigInteger k = other.multiply(unblind).mod(P).modPow(x, P);
            if (k.compareTo(BigInteger.ONE) <= 0) return null;
            byte[] xa = isA ? message() : fixed(other), yb = isA ? fixed(other) : message();
            byte[] tt = lp(CONTEXT.getBytes(StandardCharsets.UTF_8), utf(c.phoneId), utf(c.pcId), utf(c.phoneFp), utf(c.pcFp), c.salt,
                    utf(Integer.toString(c.iter)), xa, yb, fixed(k), fixed(w));
            byte[] h = sha(tt);
            return new Keys(hmac(hmac(h, utf("confirm-A")), h), hmac(hmac(h, utf("confirm-B")), h), h);
        }
    }

    /** 定长比较。 */
    public static boolean same(byte[] a, byte[] b) {
        return a != null && b != null && MessageDigest.isEqual(a, b);
    }

    // ------------------------------------------------------------------ 小工具

    static byte[] utf(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static byte[] cat(byte[] a, byte[] b) {
        byte[] o = new byte[a.length + b.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    /** 每段前面 8 字节小端长度（RFC 9382 的写法）。 */
    static byte[] lp(byte[]... parts) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) {
            long n = p.length;
            for (int i = 0; i < 8; i++) o.write((int) (n >>> (8 * i)) & 0xFF);
            o.write(p, 0, p.length);
        }
        return o.toByteArray();
    }

    static byte[] sha(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
