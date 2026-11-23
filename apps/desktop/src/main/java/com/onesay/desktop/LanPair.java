package com.onesay.desktop;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * [rel-1123-b] 免扫码配对（电脑一侧的纯逻辑，{@link LanServer} 用它；JVM 可测）。
 *
 * <p>流程（都在局域网 TLS 连接里，和平常的常连同一个端口）：
 * <ol>
 *   <li>手机在局域网里广播 {@code {"t":"pprobe","v":1}}（端口 = 局域网端口 + 1），电脑开着「允许新手机配对」时回 {@code {"t":"pinv","v":1,"port"}}；
 *       电脑每分钟也往组播 / 广播发一条同样的 {@code pinv}。<b>都不带电脑的名字、id、证书</b>，只说「这里有台 OneSay 电脑可以配对」。</li>
 *   <li>手机 TLS 连上（这时还不认识电脑证书：先记下看到的指纹），首帧 {@code pair_req}：本机 id、手机名字、
 *       {@code commit = H(nPhone)}（先承诺自己的随机数，不先给出来）；</li>
 *   <li>电脑回 {@code pair_chal}（电脑的随机数 nPc）；手机再给出 nPhone（{@code pair_reveal}），电脑核对承诺；</li>
 *   <li>两边各自用「手机证书指纹 + 电脑证书指纹 + nPhone + nPc」算出同一个 4 位核对码（{@link #code}）：手机屏幕上显示，
 *       电脑确认框里也显示。中间要是有人冒充（两边看到的证书不一样），两边的码就对不上；因为手机先承诺随机数，冒充的一方也没法凑出一样的码。</li>
 *   <li>电脑弹「允许这台手机连接吗」：只认电脑本机鼠标 / 键盘（{@link PcGuard.Confirms} + {@link ConfirmGate}：0.7 秒防误点、焦点默认在拒绝、
 *       关窗 / Esc / 超时 / 失焦都算拒绝，语音 / 远程 / 手机上点都不算批准）；</li>
 *   <li>允许 → 电脑记住这台手机的证书指纹，回 {@code pair_ok}（带平常二维码里的那串配对串，在 TLS 里）；手机核对配对串里的证书指纹就是刚才 TLS 看到的那张，
 *       存下配对，之后照旧走签名的 announce 自动重连。拒绝 / 超时 → {@code pair_no}。</li>
 * </ol>
 * 防刷：只收私有局域网 / Tailscale 地址（{@link #privateAddr}）；同一时间只弹一个配对框；同一地址 10 秒内只收一次；一分钟最多 6 次；
 * 同一地址被拒 3 次后 10 分钟不收（{@link Limiter}）。扫码配对照旧保留。
 */
public final class LanPair {

    private LanPair() {}

    public static final int CODE_DIGITS = 4;
    /** 电脑确认框等多久（和别的确认框一样 60 秒）。 */
    public static final long ASK_MS = LinkPolicy.CONFIRM_TIMEOUT_MS;
    /** 手机给出随机数最多等多久。 */
    public static final int REVEAL_MS = 15_000;
    /** 确认框标题里的「来源」：不是合法设备 id（有中文），手机冒充不了，也不会被发到哪台手机上去批。 */
    public static final String FROM = "新手机配对";

    /** 电脑上弹确认框（阻塞）：返回 {@link PcGuard#ALLOW} / {@link PcGuard#DENY} / {@link PcGuard#TIMEOUT}。 */
    public interface Asker {
        /** replaces = 这个设备 id 以前配对过、证书和这次不一样（允许的话旧的那台要重新配对）。 */
        int ask(String phoneName, String phoneId, String code, String ip, boolean replaces, long timeoutMs);
    }

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

    /** 手机的配对探测 → 电脑回的邀请；不是探测 / 关着配对 / 不是私有地址返回 null。 */
    public static String answerPairProbe(boolean open, int port, String text, InetAddress from) {
        if (!open || !privateAddr(from) || text == null || text.length() > 512) return null;
        Map<String, Object> m;
        try { m = Json.obj(text); } catch (Exception e) { return null; }
        if (!"pprobe".equals(m.get("t"))) return null;
        return inviteText(port);
    }

    /** 电脑的配对邀请：只有类型、版本、端口。 */
    public static String inviteText(int port) {
        Map<String, Object> r = new TreeMap<>();
        r.put("t", "pinv");
        r.put("v", 1L);
        r.put("port", (long) port);
        return Json.stringify(r);
    }

    // ------------------------------------------------------------------ 核对码

    static byte[] sha(String s) {
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** 手机对自己随机数的承诺：base64url(SHA-256("onesay-pair-commit\n" + n))。 */
    public static String commit(String n) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha("onesay-pair-commit\n" + n));
    }

    /** 4 位核对码：SHA-256(版本、手机证书指纹、电脑证书指纹、两边随机数) 的前 4 字节 mod 10000，左补零。两边算法一样。 */
    public static String code(String phoneFp, String pcFp, String nPhone, String nPc) {
        byte[] h = sha("onesay-pair-v1\n" + phoneFp + "\n" + pcFp + "\n" + nPhone + "\n" + nPc);
        long v = ((h[0] & 0xFFL) << 24) | ((h[1] & 0xFFL) << 16) | ((h[2] & 0xFFL) << 8) | (h[3] & 0xFFL);
        String s = Long.toString(v % 10000L);
        while (s.length() < CODE_DIGITS) s = "0" + s;
        return s;
    }

    /** 随机数格式：base64url 16–64 位。 */
    public static boolean validNonce(Object n) {
        return n instanceof String && ((String) n).matches("[A-Za-z0-9_-]{16,64}");
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

    /** 确认框里的那段话。 */
    public static String detail(String name, String id, String code, String ip) { return detail(name, id, code, ip, false); }

    public static String detail(String name, String id, String code, String ip, boolean replaces) {
        return "允许这台手机连这台电脑吗？\n手机：" + name + "（" + id + "，地址 " + ip + "）\n核对码：" + code
                + (replaces ? "\n注意：这个名字以前配对过另一台手机（证书不一样）。允许的话，以前那台要重新配对。" : "")
                + "\n先看手机屏幕上的核对码，和这里一样才点「允许」；不认识这台手机、或者码不一样，就点「拒绝」。"
                + "\n允许以后，这台手机能给电脑发指令、电脑也能找它（手机上的各项开关照旧管着）。";
    }

    // ------------------------------------------------------------------ 防刷

    /** 配对请求的门：同时只一个、同一地址 10 秒一次、一分钟最多 6 次、同一地址被拒 3 次后 10 分钟不收。时间由调用方传（测试用假时钟）。 */
    public static final class Limiter {
        public static final long PER_IP_GAP_MS = 10_000, WINDOW_MS = 60_000, BLOCK_MS = 10 * 60_000;
        public static final int WINDOW_MAX = 6, DENY_MAX = 3;
        public static final String BUSY = "busy", TOO_FAST = "rate", BLOCKED = "blocked";

        private boolean pending;
        private final Map<String, Long> lastAt = new HashMap<>();
        private final Deque<Long> window = new ArrayDeque<>();
        private final Map<String, Deque<Long>> denies = new HashMap<>();

        /** 能不能收这条请求：能 = null（并占住「同时只一个」），不能 = 原因码。 */
        public synchronized String admit(String ip, long now) {
            Deque<Long> d = denies.get(ip);
            if (d != null) {
                while (!d.isEmpty() && now - d.peekFirst() > BLOCK_MS) d.pollFirst();
                if (d.size() >= DENY_MAX) return BLOCKED;
            }
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

        /** 用户在电脑上点了拒绝 / 没理（超时）：记一笔，3 次后这个地址 10 分钟不收。 */
        public synchronized void denied(String ip, long now) {
            denies.computeIfAbsent(ip, k -> new ArrayDeque<>()).addLast(now);
            if (denies.size() > 256) denies.clear();
        }

        public synchronized boolean pending() { return pending; }
    }

    /** pair_no 的原因 → 手机上给人看的话（手机端也有一份同样的说法）。 */
    public static String whyZh(String why) {
        switch (why == null ? "" : why) {
            case "denied": return "电脑上点了拒绝";
            case "timeout": return "电脑上没人点（60 秒没确认）";
            case "closed": return "电脑上关着「允许新手机配对」";
            case Limiter.BUSY: return "电脑正在处理另一台手机的配对，等一会再试";
            case Limiter.TOO_FAST: return "试得太快了，等 10 秒再试";
            case Limiter.BLOCKED: return "这台手机被拒绝太多次，10 分钟后再试";
            case "addr": return "只接受同一个局域网或 Tailscale 里的手机";
            case "tls": return "要用加密连接配对，请更新手机上的 OneSay";
            case "bad": return "配对请求不完整";
            default: return "电脑没同意配对";
        }
    }
}
