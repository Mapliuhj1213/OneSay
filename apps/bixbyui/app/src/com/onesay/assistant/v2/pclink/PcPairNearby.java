package com.onesay.assistant.v2.pclink;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 免扫码配对的手机一侧（纯 Java 7，JVM 可测；电脑一侧是 {@code LanPair} / {@code LanServer.pair}，规则见那边的说明）。
 * [rel-1123-b] 起头，[rel-1123 合并] 按用户定改成「电脑上先设 4 位配对码 → 手机选电脑、输码 → SPAKE2 核对」，不再要电脑上点允许：
 *
 * <ol>
 *   <li>{@link #discover}：往局域网广播 / 组播发 {@code {"t":"pprobe","v":2}}（正式版 47821、预览版 47921），收电脑回的
 *       {@code {"t":"pinv","v":2,"port","name","code"}}：电脑名字、设没设码。只认私有局域网 / Tailscale 地址。</li>
 *   <li>{@link #request}：TLS 连过去（先记下看到的电脑证书指纹）→ {@code pair_req} → 收盐和轮数 → 用输的码算 w（{@link PcSpake2}）→
 *       {@code pair_x} → 收 {@code pair_y} → 发确认码 → 电脑回 {@code pair_ok}（电脑的确认码 + 配对串）。电脑的确认码对得上（冒充的电脑没有码算不出）、
 *       配对串里的证书就是 TLS 看到的那张、配对串里的电脑 id 就是刚才说的那个，才算配好。码本身不发出去。</li>
 * </ol>
 */
public final class PcPairNearby {

    private PcPairNearby() {}

    /** 电脑端局域网端口：正式版 47820、预览版 47920（发现用 +1）。 */
    public static final int[] PORTS = {47820, 47920};
    /** 每一步最多等多久。 */
    public static final int STEP_MS = 15_000;

    /** 进展（UI 显示一句话）。 */
    public interface Ui {
        void step(String text);
    }

    /** 找到的一台电脑：TCP 地址、名字、设没设配对码。 */
    public static final class Found {
        public final InetSocketAddress addr;
        public final String name;
        public final boolean codeSet;

        Found(InetSocketAddress addr, String name, boolean codeSet) {
            this.addr = addr;
            this.name = name == null || name.length() == 0 ? "OneSay 电脑" : name;
            this.codeSet = codeSet;
        }

        /** 列表里显示的那一行。 */
        public String label() {
            return name + "（" + addr.getAddress().getHostAddress() + "）" + (codeSet ? "" : " · 还没设配对码");
        }

        @Override public boolean equals(Object o) { return o instanceof Found && ((Found) o).addr.equals(addr); }

        @Override public int hashCode() { return addr.hashCode(); }
    }

    /** 配对结果：ok 时 pairing 是电脑给的配对串（交给 {@code PcLink.pair} 保存）。 */
    public static final class Outcome {
        public final boolean ok;
        /** why = 给人看的原因；reason = 原因码（code 码不对 / locked 锁住 / nocode 没设码 / … / 空）；left = 码不对时还能试几次。 */
        public final String pairing, why, reason;
        public final int left;

        Outcome(boolean ok, String pairing, String why, String reason, int left) {
            this.ok = ok;
            this.pairing = pairing == null ? "" : pairing;
            this.why = why == null ? "" : why;
            this.reason = reason == null ? "" : reason;
            this.left = left;
        }

        static Outcome fail(String why) { return new Outcome(false, null, why, "", 0); }

        @Override public String toString() {
            return ok ? "ok" : "fail " + reason + " " + why;
        }
    }

    // ------------------------------------------------------------------ 发现

    /** 私有局域网 / Tailscale / 本机回环（与电脑端 LanPair.privateAddr 同一规则）。 */
    public static boolean privateAddr(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
            return b0 == 100 && b1 >= 64 && b1 <= 127;
        }
        if (a instanceof Inet6Address) return ((b[0] & 0xFF) & 0xFE) == 0xFC;
        return false;
    }

    /** 电脑邀请里的端口；不是邀请返回 -1。 */
    static int invitePort(String text) {
        try {
            Map<String, Object> m = PcJson.obj(text);
            Object port = m.get("port");
            if (!"pinv".equals(m.get("t")) || !(port instanceof Number)) return -1;
            int p = ((Number) port).intValue();
            return p > 0 && p < 65535 ? p : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 邀请 → 找到的一台电脑；不是邀请返回 null。名字去掉控制字符，最多 32 字。 */
    static Found invite(String text, InetAddress from) {
        int port = invitePort(text);
        if (port <= 0) return null;
        String name = "";
        boolean code = false;
        try {
            Map<String, Object> m = PcJson.obj(text);
            name = cleanName(m.get("name"));
            code = Boolean.TRUE.equals(m.get("code"));
        } catch (Exception ignored) {
        }
        return new Found(new InetSocketAddress(from, port), name, code);
    }

    static String cleanName(Object o) {
        String s = o == null ? "" : String.valueOf(o);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 32; i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c) || c == '<' || c == '>' || c == '"' || c == '\u202E' || c == '\u202D' || Character.getType(c) == Character.FORMAT) continue;
            b.append(c);
        }
        return b.toString().trim();
    }

    /**
     * 找同一网络里能配对的电脑：返回 TCP 地址（ip:局域网端口），可能有几台，按先回先排。extraTargets = 额外的探测目标（ip:发现端口），测试 / 已知地址用。
     */
    public static List<Found> discover(long budgetMs, List<InetAddress> extraBroadcasts, int[] ports, List<InetSocketAddress> extraTargets) {
        Set<Found> found = new LinkedHashSet<Found>();
        DatagramSocket ds = null;
        try {
            ds = new DatagramSocket();
            ds.setBroadcast(true);
            ds.setSoTimeout(150);
            byte[] probe = "{\"t\":\"pprobe\",\"v\":2}".getBytes(PcProtocol.UTF8);
            Set<InetSocketAddress> targets = new LinkedHashSet<InetSocketAddress>();
            if (extraTargets != null) targets.addAll(extraTargets);
            List<InetAddress> bc = PcLanClient.broadcasts();
            if (extraBroadcasts != null) bc.addAll(extraBroadcasts);
            for (int port : ports == null ? PORTS : ports) {
                int d = port + 1;
                try {
                    targets.add(new InetSocketAddress(InetAddress.getByName(PcLanClient.GROUP), d));
                } catch (Exception ignored) {
                }
                for (InetAddress b : bc) targets.add(new InetSocketAddress(b, d));
                try {
                    targets.add(new InetSocketAddress(InetAddress.getByName("255.255.255.255"), d));
                } catch (Exception ignored) {
                }
            }
            byte[] buf = new byte[512];
            long end = System.currentTimeMillis() + budgetMs, nextSend = 0;
            int rounds = 0;
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSend && rounds < 5) {
                    for (InetSocketAddress t : targets) {
                        try {
                            ds.send(new DatagramPacket(probe, probe.length, t));
                        } catch (Exception ignored) {
                        }
                    }
                    rounds++;
                    nextSend = System.currentTimeMillis() + 400;
                }
                try {
                    DatagramPacket pk = new DatagramPacket(buf, buf.length);
                    ds.receive(pk);
                    if (!privateAddr(pk.getAddress())) continue;
                    Found f = invite(new String(pk.getData(), pk.getOffset(), pk.getLength(), PcProtocol.UTF8), pk.getAddress());
                    if (f != null) found.add(f);
                } catch (SocketTimeoutException ignored) {
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
            // 没网 / 建不了 UDP：一台也没找到
        } finally {
            if (ds != null) ds.close();
        }
        return new ArrayList<Found>(found);
    }

    /** 电脑回的 pair_no 原因 → 给人看的话（与电脑端 LanPair.whyZh 一样）。 */
    public static String whyZh(String why, int left, long waitMin) {
        if ("code".equals(why)) return left > 0 ? "配对码不对，还能试 " + left + " 次" : "配对码不对";
        if ("locked".equals(why)) return "试错太多次，这台电脑暂停了免扫码配对" + (waitMin > 0 ? "，" + waitMin + " 分钟后再试" : "") + "（也可以在电脑上解锁）";
        if ("nocode".equals(why)) return NO_CODE;
        if ("closed".equals(why)) return "电脑上关着「允许新手机配对」";
        if ("old".equals(why)) return "两边的 OneSay 版本对不上，请都更新到最新";
        if ("busy".equals(why)) return "电脑正在处理另一台手机的配对，等一会再试";
        if ("rate".equals(why)) return "试得太快了，等 10 秒再试";
        if ("addr".equals(why)) return "只接受同一个局域网或 Tailscale 里的手机";
        if ("tls".equals(why)) return "要用加密连接配对，请更新电脑上的 OneSay";
        if ("bad".equals(why)) return "配对请求不完整";
        return "电脑没同意配对";
    }

    public static String whyZh(String why) { return whyZh(why, 0, 0); }

    /** 电脑没设配对码时的那句（用户定的说法）。 */
    public static final String NO_CODE = "这台电脑还没设配对码，请在电脑上先设一个";

    /** 输的码格式：正好 4 位数字。 */
    public static boolean validCode(String c) { return c != null && c.matches("\\d{4}"); }

    // ------------------------------------------------------------------ 配对

    /**
     * 发起配对（阻塞，几秒）。me = 本机 id；name = 给电脑看的手机名字；tls = 本机证书（之后常连也用这张）；code = 用户输的 4 位配对码（不发出去）；
     * cancel 非空且置 true 时尽快停（关连接）。
     */
    @SuppressWarnings("unchecked")
    public static Outcome request(InetSocketAddress a, String me, String name, PcTls tls, String code, Ui ui, java.util.concurrent.atomic.AtomicBoolean cancel) {
        if (a == null || !privateAddr(a.getAddress())) return Outcome.fail("只能和同一个局域网或 Tailscale 里的电脑配对");
        if (tls == null || !PcPairing.validId(me)) return Outcome.fail("手机上的证书还没准备好");
        if (!validCode(code)) return Outcome.fail("配对码要正好 4 位数字");
        step(ui, "正在连电脑…");
        PcWs ws;
        try {
            ws = PcWs.connect(a, me, 3000, STEP_MS, tls.firstContactContext());
        } catch (IOException e) {
            return Outcome.fail("连不上这台电脑（" + e.getClass().getSimpleName() + "）：看看电脑上的 OneSay 开着没有");
        }
        try {
            String pcFp = ws.peerFp;
            if (!PcTls.validFp(pcFp)) return Outcome.fail("电脑没出示证书，不配对");
            Map<String, Object> req = new TreeMap<String, Object>();
            req.put("type", "pair_req");
            req.put("v", Long.valueOf(2));
            req.put("from", me);
            req.put("name", name == null ? "" : (name.length() > 32 ? name.substring(0, 32) : name));
            ws.sendText(PcJson.stringify(req));
            ws.setReadTimeout(STEP_MS);
            Map<String, Object> st = next(ws);
            if (cancelled(cancel)) return Outcome.fail("取消了");
            Outcome no = no(st);
            if (no != null) return no;
            Object saltO = st.get("salt"), iterO = st.get("iter"), idO = st.get("id");
            byte[] salt = unb64(saltO);
            if (!"pair_salt".equals(st.get("type")) || salt == null || salt.length < 16 || salt.length > 64 || !(iterO instanceof Number) || !(idO instanceof String) || !PcPairing.validId((String) idO))
                return Outcome.fail("电脑回的不是配对应答（电脑上的 OneSay 可能太旧）");
            int iter = ((Number) iterO).intValue();
            if (iter < PcSpake2.ITER_MIN || iter > PcSpake2.ITER_MAX) return Outcome.fail("电脑回的配对参数不对，没配");
            String pcId = (String) idO;
            step(ui, "正在核对配对码…");
            byte[] w = PcSpake2.w(code, salt, iter);
            PcSpake2.Party me2 = new PcSpake2.Party(true, w);
            Map<String, Object> x = new TreeMap<String, Object>();
            x.put("type", "pair_x");
            x.put("x", PcB64.encodeUrl(me2.message()));
            ws.sendText(PcJson.stringify(x));
            Map<String, Object> yf = next(ws);
            if (cancelled(cancel)) return Outcome.fail("取消了");
            no = no(yf);
            if (no != null) return no;
            byte[] y = "pair_y".equals(yf.get("type")) ? unb64(yf.get("y")) : null;
            PcSpake2.Keys k = y == null ? null : me2.finish(y, new PcSpake2.Ctx(me, pcId, tls.fp, pcFp, salt, iter));
            if (k == null) return Outcome.fail("电脑发来的数据不对，没配");
            Map<String, Object> cf = new TreeMap<String, Object>();
            cf.put("type", "pair_confirm");
            cf.put("c", PcB64.encodeUrl(k.cA));
            ws.sendText(PcJson.stringify(cf));
            Map<String, Object> f = next(ws);
            no = no(f);
            if (no != null) return no;
            if (!"pair_ok".equals(f.get("type"))) return Outcome.fail("电脑回的不是配对结果");
            if (!PcSpake2.same(unb64(f.get("c")), k.cB)) return Outcome.fail("这台电脑没对上配对码（可能不是你的电脑），没保存");
            String pairing = String.valueOf(f.get("pairing"));
            PcPairing pp;
            try {
                pp = PcPairing.parse(pairing);
            } catch (IllegalArgumentException e) {
                return Outcome.fail("电脑给的配对信息不对：" + e.getMessage());
            }
            if (!PcTls.fpEquals(pp.fp, pcFp) || !pcId.equals(pp.peer)) return Outcome.fail("电脑给的配对信息和刚才连上的不是同一台电脑，没保存");
            step(ui, "核对通过，正在保存…");
            return new Outcome(true, pairing, "", "", 0);
        } catch (SocketTimeoutException e) {
            return Outcome.fail("电脑没回应（等了 " + (STEP_MS / 1000) + " 秒）");
        } catch (IOException e) {
            return Outcome.fail(cancelled(cancel) ? "取消了" : "连接断了：" + e.getClass().getSimpleName());
        } finally {
            ws.close();
        }
    }

    static boolean cancelled(java.util.concurrent.atomic.AtomicBoolean c) { return c != null && c.get(); }

    static void step(Ui ui, String t) {
        if (ui == null) return;
        try {
            ui.step(t);
        } catch (Throwable ignored) {
        }
    }

    /** null 帧 / pair_no → 失败结果；别的返回 null。 */
    static Outcome no(Map<String, Object> f) {
        if (f == null) return Outcome.fail("电脑那边断开了，没配上");
        if (!"pair_no".equals(f.get("type"))) return null;
        String why = String.valueOf(f.get("why"));
        int left = f.get("left") instanceof Number ? ((Number) f.get("left")).intValue() : 0;
        long wait = f.get("wait_min") instanceof Number ? ((Number) f.get("wait_min")).longValue() : 0;
        return new Outcome(false, null, whyZh(why, left, wait), why, left);
    }

    static byte[] unb64(Object o) {
        if (!(o instanceof String) || ((String) o).length() > 1024) return null;
        try {
            return PcB64.decode((String) o);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 读下一帧 JSON（跳过 pong / 坏帧）；对方关了返回 null。 */
    private static Map<String, Object> next(PcWs ws) throws IOException {
        while (true) {
            String m = ws.readText();
            if (m == null) return null;
            if ("pong".equals(m)) continue;
            try {
                return PcJson.obj(m);
            } catch (Exception e) {
                // 坏帧：再读
            }
        }
    }
}
