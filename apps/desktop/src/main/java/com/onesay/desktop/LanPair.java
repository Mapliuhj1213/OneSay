package com.onesay.desktop;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 免扫码配对（电脑一侧的纯逻辑，{@link LanServer} 用它；JVM 可测）。[rel-1123-b] 起头，[rel-1123 合并] 按用户定改成「固定配对码 + SPAKE2」：
 *
 * <ol>
 *   <li>用户先在电脑设置里设一个 4 位配对码，才能打开「允许新手机配对」（没设码就打不开；码只以 PBKDF2 哈希存进 Windows 凭据管理器，见 {@link Verifier}）。</li>
 *   <li>手机广播 {@code {"t":"pprobe","v":2}}（端口 = 局域网端口 + 1）。电脑开着「允许新手机配对」时回 {@code {"t":"pinv","v":2,"port","name","code"}}：
 *       name = 电脑名字（用户同意同一网络里看得到），code = 设没设配对码（开关开着但码丢了时 false，手机提示「这台电脑还没设配对码」）。
 *       不带电脑 id、证书。关着就不回。</li>
 *   <li>手机 TLS 连上（先记下看到的电脑证书指纹）→ {@code pair_req}（v=2、本机 id、名字）→ 电脑回 {@code pair_salt}（盐、轮数、电脑名字和 id）
 *       → 手机算 w、发 {@code pair_x} → 电脑记一次尝试、回 {@code pair_y} → 手机发确认码 {@code pair_confirm}
 *       → 电脑核对：对 → 记住手机证书、回 {@code pair_ok}（电脑的确认码 + 平常二维码里那串配对串）；不对 → {@code pair_no why=code}（还能试几次）。
 *       手机核对电脑的确认码（冒充的电脑算不出）和配对串里的证书就是 TLS 看到的那张，才保存。算法见 {@link Spake2}。</li>
 * </ol>
 * 防刷照旧：只收私有局域网 / Tailscale 地址；同一时间只一个；同一地址 10 秒一次；一分钟最多 6 次（{@link Limiter}）。
 * 输错限次（{@link Lock}）：连错 5 次锁 10 分钟，再错 5 次锁 1 小时，之后每错 5 次锁 24 小时；配对成功、在电脑上解锁或改码清零。
 * 扫码配对不受影响。
 */
public final class LanPair {

    private LanPair() {}

    public static final int CODE_DIGITS = 4;
    /** 配对每一步最多等多久。 */
    public static final int STEP_MS = 15_000;

    // ------------------------------------------------------------------ 地址

    /**
     * 只收私有局域网 / Tailscale 来的：10/8、172.16/12、192.168/16、169.254/16（链路本地）、100.64/10（Tailscale / 运营商 NAT 段）、
     * 本机回环；IPv6 的 fc00::/7（含 Tailscale fd7a:115c:a1e0::/48）、fe80::/10、::1。公网地址一律不收。
     */
    public static boolean privateAddr(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
            return b0 == 100 && b1 >= 64 && b1 <= 127; // 100.64.0.0/10
        }
        if (a instanceof Inet6Address) {
            int b0 = b[0] & 0xFF;
            if ((b0 & 0xFE) == 0xFC) return true; // fc00::/7
            // ::ffff:a.b.c.d（IPv4 映射）
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) { mapped = false; break; }
            if (mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
                try { return privateAddr(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]})); } catch (Exception e) { return false; }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 发现

    /** 手机的配对探测 → 电脑回的邀请；不是探测 / 关着配对 / 不是私有地址返回 null。codeSet = 设了配对码。 */
    public static String answerPairProbe(boolean open, boolean codeSet, int port, String name, String text, InetAddress from) {
        if (!open || !privateAddr(from) || text == null || text.length() > 512) return null;
        Map<String, Object> m;
        try { m = Json.obj(text); } catch (Exception e) { return null; }
        if (!"pprobe".equals(m.get("t"))) return null;
        return inviteText(port, name, codeSet);
    }

    /** 电脑的配对邀请：类型、版本、端口、电脑名字、设没设码。 */
    public static String inviteText(int port, String name, boolean codeSet) {
        Map<String, Object> r = new TreeMap<>();
        r.put("t", "pinv");
        r.put("v", 2L);
        r.put("port", (long) port);
        r.put("name", pcName(name));
        r.put("code", codeSet);
        return Json.stringify(r);
    }

    /** 电脑名字（给同一网络里的手机看）：去掉控制字符，最多 32 字；空的叫「OneSay 电脑」。 */
    public static String pcName(String raw) {
        String s = cleanName(raw);
        return "一台手机".equals(s) ? "OneSay 电脑" : s;
    }

    /** 这台电脑的名字：Windows 的计算机名，拿不到用主机名。 */
    public static String hostName() {
        String n = System.getenv("COMPUTERNAME");
        if (n == null || n.isBlank()) {
            try { n = InetAddress.getLocalHost().getHostName(); } catch (Exception e) { n = ""; }
        }
        return pcName(n);
    }

    /** 手机名字：去掉控制字符 / 换行 / 尖括号，最多 32 字；空的叫「一台手机」。 */
    public static String cleanName(Object o) {
        String s = o == null ? "" : String.valueOf(o);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 32; i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c) || c == '<' || c == '>' || c == '"' || c == '\u202E' || c == '\u202D' || Character.getType(c) == Character.FORMAT) continue;
            b.append(c);
        }
        String t = b.toString().trim();
        return t.isEmpty() ? "一台手机" : t;
    }

    // ------------------------------------------------------------------ 配对码

    /** 配对码格式：正好 4 位数字。 */
    public static boolean validCode(String c) { return c != null && c.matches("\\d{4}"); }

    /** 太好猜的码（四个一样、顺着 / 倒着连号）：不让设。返回原因，没问题返回 null。 */
    public static String weakCode(String c) {
        if (!validCode(c)) return "配对码要正好 4 位数字";
        boolean same = true, up = true, down = true;
        for (int i = 1; i < 4; i++) {
            int d = c.charAt(i) - c.charAt(i - 1);
            if (d != 0) same = false;
            if (d != 1) up = false;
            if (d != -1) down = false;
        }
        if (same || up || down) return "这个码太好猜了（四个一样或者连号），换一个";
        return null;
    }

    /** 电脑存的配对码：盐、轮数、w（PBKDF2 结果）。只存这个，不存码本身。 */
    public static final class Verifier {
        public final byte[] salt, w;
        public final int iter;

        public Verifier(byte[] salt, int iter, byte[] w) {
            this.salt = salt.clone();
            this.iter = iter;
            this.w = w.clone();
        }

        /** 用户设的码 → 新盐 + w。 */
        public static Verifier of(String code) {
            byte[] s = Spake2.salt();
            return new Verifier(s, Spake2.ITER_DEFAULT, Spake2.w(code, s, Spake2.ITER_DEFAULT));
        }

        /** 存进凭据库的写法：v1:轮数:盐:w（base64url）。 */
        public String format() {
            Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
            return "v1:" + iter + ":" + e.encodeToString(salt) + ":" + e.encodeToString(w);
        }

        /** 读回来；坏的返回 null。 */
        public static Verifier parse(String s) {
            if (s == null) return null;
            String[] p = s.trim().split(":");
            if (p.length != 4 || !"v1".equals(p[0])) return null;
            try {
                int it = Integer.parseInt(p[1]);
                byte[] salt = Base64.getUrlDecoder().decode(p[2]), w = Base64.getUrlDecoder().decode(p[3]);
                if (it < Spake2.ITER_MIN || it > Spake2.ITER_MAX || salt.length < 16 || w.length != Spake2.W_BYTES) return null;
                return new Verifier(salt, it, w);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ 输错限次

    /**
     * 输错限次（全电脑一个计数：对方可以换地址 / 换 id，所以不按地址算）。一次「尝试」= 电脑回了 pair_y（对方拿到了猜一次的机会），
     * 没发确认码就断开也算错一次。连错 5 次锁 10 分钟；累计 10 次锁 1 小时；之后每 5 次锁 24 小时。
     * 成功 / 在电脑上解锁 / 改码 → 清零。fails、until 存进配置（重开 OneSay 不清零）。时间由调用方传。
     */
    public static final class Lock {
        public static final int PER_ROUND = 5;
        public static final long L1 = 10 * 60_000L, L2 = 60 * 60_000L, L3 = 24 * 60 * 60_000L;
        private int fails;
        private long until;

        public Lock(int fails, long until) {
            this.fails = Math.max(0, fails);
            this.until = Math.max(0, until);
        }

        public synchronized boolean locked(long now) { return now < until; }

        public synchronized long until() { return until; }

        public synchronized int fails() { return fails; }

        /** 这一轮还能错几次。 */
        public synchronized int left() { return PER_ROUND - (fails % PER_ROUND); }

        /** 错一次；返回这次锁到什么时候（没锁返回 0）。 */
        public synchronized long fail(long now) {
            fails++;
            if (fails % PER_ROUND != 0) return 0;
            long d = fails <= PER_ROUND ? L1 : fails <= 2 * PER_ROUND ? L2 : L3;
            until = now + d;
            return until;
        }

        /** 刚才记的那次其实成功了（配对成功）：清零。 */
        public synchronized void ok() { fails = 0; until = 0; }

        public synchronized void reset() { fails = 0; until = 0; }
    }

    // ------------------------------------------------------------------ 防刷

    /** 配对请求的门：同时只一个、同一地址 10 秒一次、一分钟最多 6 次。时间由调用方传（测试用假时钟）。 */
    public static final class Limiter {
        public static final long PER_IP_GAP_MS = 10_000, WINDOW_MS = 60_000;
        public static final int WINDOW_MAX = 6;
        public static final String BUSY = "busy", TOO_FAST = "rate";

        private boolean pending;
        private final Map<String, Long> lastAt = new HashMap<>();
        private final Deque<Long> window = new ArrayDeque<>();

        /** 能不能收这条请求：能 = null（并占住「同时只一个」），不能 = 原因码。 */
        public synchronized String admit(String ip, long now) {
            if (pending) return BUSY;
            Long last = lastAt.get(ip);
            if (last != null && now - last < PER_IP_GAP_MS) return TOO_FAST;
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MS) window.pollFirst();
            if (window.size() >= WINDOW_MAX) return TOO_FAST;
            window.addLast(now);
            lastAt.put(ip, now);
            if (lastAt.size() > 256) lastAt.clear();
            pending = true;
            return null;
        }

        /** 这条请求结束了（不管结果）。 */
        public synchronized void release() { pending = false; }

        public synchronized boolean pending() { return pending; }
    }

    /** pair_no 的原因 → 给人看的话（手机端 PcPairNearby.whyZh 有一份同样的说法；left / waitMin 只有部分原因用）。 */
    public static String whyZh(String why, int left, long waitMin) {
        switch (why == null ? "" : why) {
            case "code": return left > 0 ? "配对码不对，还能试 " + left + " 次" : "配对码不对";
            case "locked": return "试错太多次，这台电脑暂停了免扫码配对" + (waitMin > 0 ? "，" + waitMin + " 分钟后再试" : "") + "（也可以在电脑上解锁）";
            case "nocode": return "这台电脑还没设配对码，请在电脑上先设一个";
            case "closed": return "电脑上关着「允许新手机配对」";
            case "old": return "两边的 OneSay 版本对不上，请都更新到最新";
            case Limiter.BUSY: return "电脑正在处理另一台手机的配对，等一会再试";
            case Limiter.TOO_FAST: return "试得太快了，等 10 秒再试";
            case "addr": return "只接受同一个局域网或 Tailscale 里的手机";
            case "tls": return "要用加密连接配对，请更新手机上的 OneSay";
            case "bad": return "配对请求不完整";
            default: return "电脑没同意配对";
        }
    }

    public static String whyZh(String why) { return whyZh(why, 0, 0); }
}
