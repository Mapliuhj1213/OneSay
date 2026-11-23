package com.onesay.assistant.v2.pclink;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * [rel-1123-b] 免扫码配对的手机一侧（纯 Java 7，JVM 可测；电脑一侧是 {@code LanPair} / {@code LanServer.pair}，规则见那边的说明）。
 *
 * <ol>
 *   <li>{@link #discover}：往局域网广播 / 组播发 {@code {"t":"pprobe","v":1}}（正式版 47821、预览版 47921），收电脑回的
 *       {@code {"t":"pinv","v":1,"port"}}。邀请里没有电脑的名字和身份，只知道「这个地址上有台 OneSay 电脑可以配对」；只认私有局域网 / Tailscale 地址。</li>
 *   <li>{@link #request}：TLS 连过去（先记下看到的电脑证书指纹）→ {@code pair_req}（本机 id、名字、对随机数的承诺）→ 收电脑随机数 →
 *       给出自己的随机数 → 两边算同一个 4 位核对码（{@link #code}），手机屏幕上显示 → 等电脑上点「允许」→ {@code pair_ok} 带配对串，
 *       配对串里的电脑证书指纹必须就是刚才 TLS 看到的那张，才算配好。</li>
 * </ol>
 */
public final class PcPairNearby {

    private PcPairNearby() {}

    /** 电脑端局域网端口：正式版 47820、预览版 47920（发现用 +1）。 */
    public static final int[] PORTS = {47820, 47920};
    /** 等电脑上点「允许」最多多久（电脑确认框 60 秒，多留一点路上的时间）。 */
    public static final int ASK_WAIT_MS = 80_000;

    /** 核对码出来了（UI 显示）。 */
    public interface Ui {
        void code(String code);
    }

    /** 配对结果：ok 时 pairing 是电脑给的配对串（交给 {@code PcLink.pair} 保存）。 */
    public static final class Outcome {
        public final boolean ok;
        public final String pairing, why, code;

        Outcome(boolean ok, String pairing, String why, String code) {
            this.ok = ok;
            this.pairing = pairing == null ? "" : pairing;
            this.why = why == null ? "" : why;
            this.code = code == null ? "" : code;
        }

        @Override public String toString() {
            return (ok ? "ok" : "fail " + why) + (code.length() > 0 ? " code=" + code : "");
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

    /**
     * 找同一网络里能配对的电脑：返回 TCP 地址（ip:局域网端口），可能有几台，按先回先排。extraTargets = 额外的探测目标（ip:发现端口），测试 / 已知地址用。
     */
    public static List<InetSocketAddress> discover(long budgetMs, List<InetAddress> extraBroadcasts, int[] ports, List<InetSocketAddress> extraTargets) {
        Set<InetSocketAddress> found = new LinkedHashSet<InetSocketAddress>();
        DatagramSocket ds = null;
        try {
            ds = new DatagramSocket();
            ds.setBroadcast(true);
            ds.setSoTimeout(150);
            byte[] probe = "{\"t\":\"pprobe\",\"v\":1}".getBytes(PcProtocol.UTF8);
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
                    int port = invitePort(new String(pk.getData(), pk.getOffset(), pk.getLength(), PcProtocol.UTF8));
                    if (port > 0) found.add(new InetSocketAddress(pk.getAddress(), port));
                } catch (SocketTimeoutException ignored) {
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
            // 没网 / 建不了 UDP：一台也没找到
        } finally {
            if (ds != null) ds.close();
        }
        return new ArrayList<InetSocketAddress>(found);
    }

    // ------------------------------------------------------------------ 核对码（与电脑端 LanPair 同一算法）

    static byte[] sha(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(PcProtocol.UTF8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String commit(String n) {
        return PcB64.encodeUrl(sha("onesay-pair-commit\n" + n));
    }

    public static String code(String phoneFp, String pcFp, String nPhone, String nPc) {
        byte[] h = sha("onesay-pair-v1\n" + phoneFp + "\n" + pcFp + "\n" + nPhone + "\n" + nPc);
        long v = ((h[0] & 0xFFL) << 24) | ((h[1] & 0xFFL) << 16) | ((h[2] & 0xFFL) << 8) | (h[3] & 0xFFL);
        String s = Long.toString(v % 10000L);
        while (s.length() < 4) s = "0" + s;
        return s;
    }

    static String nonce() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return PcB64.encodeUrl(b);
    }

    /** 电脑回的 pair_no 原因 → 给人看的话（与电脑端 LanPair.whyZh 一样）。 */
    public static String whyZh(String why) {
        if ("denied".equals(why)) return "电脑上点了拒绝";
        if ("timeout".equals(why)) return "电脑上没人点（60 秒没确认）";
        if ("closed".equals(why)) return "电脑上关着「允许新手机配对」";
        if ("busy".equals(why)) return "电脑正在处理另一台手机的配对，等一会再试";
        if ("rate".equals(why)) return "试得太快了，等 10 秒再试";
        if ("blocked".equals(why)) return "这台手机被拒绝太多次，10 分钟后再试";
        if ("addr".equals(why)) return "只接受同一个局域网或 Tailscale 里的手机";
        if ("tls".equals(why)) return "要用加密连接配对，请更新电脑上的 OneSay";
        if ("bad".equals(why)) return "配对请求不完整";
        return "电脑没同意配对";
    }

    // ------------------------------------------------------------------ 配对

    /** 发起配对（阻塞到电脑上点了允许 / 拒绝 / 超时）。me = 本机 id；name = 给电脑看的手机名字；tls = 本机证书（之后常连也用这张）。 */
    @SuppressWarnings("unchecked")
    public static Outcome request(InetSocketAddress a, String me, String name, PcTls tls, Ui ui, int askWaitMs) {
        if (a == null || !privateAddr(a.getAddress())) return new Outcome(false, null, "只能和同一个局域网或 Tailscale 里的电脑配对", null);
        if (tls == null || !PcPairing.validId(me)) return new Outcome(false, null, "手机上的证书还没准备好", null);
        PcWs ws;
        try {
            ws = PcWs.connect(a, me, 3000, 15000, tls.firstContactContext());
        } catch (IOException e) {
            return new Outcome(false, null, "连不上这台电脑（" + e.getClass().getSimpleName() + "）：看看电脑上的 OneSay 开着没有", null);
        }
        String code = null;
        try {
            String pcFp = ws.peerFp;
            if (!PcTls.validFp(pcFp)) return new Outcome(false, null, "电脑没出示证书，不配对", null);
            String nPhone = nonce();
            Map<String, Object> req = new TreeMap<String, Object>();
            req.put("type", "pair_req");
            req.put("v", Long.valueOf(1));
            req.put("from", me);
            req.put("name", name == null ? "" : (name.length() > 32 ? name.substring(0, 32) : name));
            req.put("commit", commit(nPhone));
            ws.sendText(PcJson.stringify(req));
            ws.setReadTimeout(15000);
            Map<String, Object> chal = next(ws);
            if (chal == null) return new Outcome(false, null, "电脑没回应", null);
            if ("pair_no".equals(chal.get("type"))) return new Outcome(false, null, whyZh(String.valueOf(chal.get("why"))), null);
            Object nPc = chal.get("n");
            if (!"pair_chal".equals(chal.get("type")) || !(nPc instanceof String) || !((String) nPc).matches("[A-Za-z0-9_-]{16,64}"))
                return new Outcome(false, null, "电脑回的不是配对应答（电脑上的 OneSay 可能太旧）", null);
            Map<String, Object> rv = new TreeMap<String, Object>();
            rv.put("type", "pair_reveal");
            rv.put("n", nPhone);
            ws.sendText(PcJson.stringify(rv));
            code = code(tls.fp, pcFp, nPhone, (String) nPc);
            if (ui != null) {
                try {
                    ui.code(code);
                } catch (Throwable ignored) {
                }
            }
            ws.setReadTimeout(Math.max(5000, askWaitMs));
            long end = System.currentTimeMillis() + Math.max(5000, askWaitMs);
            while (System.currentTimeMillis() < end) {
                Map<String, Object> f = next(ws);
                if (f == null) return new Outcome(false, null, "电脑那边断开了，没配上", code);
                Object t = f.get("type");
                if ("pair_wait".equals(t)) continue;
                if ("pair_no".equals(t)) return new Outcome(false, null, whyZh(String.valueOf(f.get("why"))), code);
                if ("pair_ok".equals(t)) {
                    String pairing = String.valueOf(f.get("pairing"));
                    PcPairing pp;
                    try {
                        pp = PcPairing.parse(pairing);
                    } catch (IllegalArgumentException e) {
                        return new Outcome(false, null, "电脑给的配对信息不对：" + e.getMessage(), code);
                    }
                    if (!PcTls.fpEquals(pp.fp, pcFp)) return new Outcome(false, null, "电脑给的配对信息和刚才连上的不是同一台电脑，没保存", code);
                    return new Outcome(true, pairing, "", code);
                }
            }
            return new Outcome(false, null, "等太久了，电脑上没人确认", code);
        } catch (SocketTimeoutException e) {
            return new Outcome(false, null, code == null ? "电脑没回应" : "等太久了，电脑上没人确认", code);
        } catch (IOException e) {
            return new Outcome(false, null, "连接断了：" + e.getClass().getSimpleName(), code);
        } finally {
            ws.close();
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
