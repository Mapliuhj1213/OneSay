package com.onesay.desktop;

import java.util.Map;
import java.util.TreeMap;

/**
 * [rel-1123-b] 电脑「手机」页离线时写清原因（纯逻辑，时间由调用方传）。按最近一次见闻判断：
 * <ul>
 *   <li>手机用一次性连接来过、但没有常连 → 手机上没开「接受电脑指令」；</li>
 *   <li>有手机拿签名不对 / 证书不对 / 不加密的握手来连，被拒 → 配对失效（各给一句怎么办）；</li>
 *   <li>常连刚断 → 说几点断的，多半换了网络 / 省电，会自己连回来；</li>
 *   <li>刚用「找附近的电脑」配好、还没常连 → 手机上打开「接受电脑指令」；</li>
 *   <li>什么都没见到 → 多半不在同一个网络（或手机上的 OneSay 没在跑）；电脑自己没局域网地址 / 端口没开起来，先说电脑这边。</li>
 * </ul>
 */
public final class OfflineWhy {

    private OfflineWhy() {}

    /** 一次性连接来过多久内算「手机连过来了」。手机「电脑联动」页开着时每 10 秒来一次。 */
    public static final long ONE_SHOT_MS = 3 * 60_000;
    /** 被拒的握手 / 常连断开 / 刚配好 多久内还拿来解释。 */
    public static final long RECENT_MS = 10 * 60_000;

    public static final String RX_OFF = "rx_off", PAIR_BAD = "pair_bad", PIN = "pin", PLAIN = "plain", DROPPED = "dropped",
            JUST_PAIRED = "just_paired", NEVER = "never", UNREACHABLE = "unreachable", PC_NO_LAN = "pc_no_lan", LAN_OFF = "lan_off";

    public static final class Why {
        public final String code, text;

        Why(String code, String text) { this.code = code; this.text = text; }

        public Map<String, Object> view() {
            Map<String, Object> m = new TreeMap<>();
            m.put("code", code);
            m.put("text", text);
            return m;
        }

        @Override public String toString() { return code + " " + text; }
    }

    static long newest(Map<String, Long> m, String prefer) {
        if (prefer != null && !prefer.isEmpty() && m.containsKey(prefer)) return m.get(prefer);
        long t = -1;
        for (long v : m.values()) t = Math.max(t, v);
        return t;
    }

    static String hm(long t) {
        return java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0).toString();
    }

    public static Why of(boolean running, String bindError, boolean pcNoLan, String phoneLast, LanServer.Seen seen, long now) {
        if (!running) return new Why(LAN_OFF, bindError == null || bindError.isEmpty() ? "电脑的局域网服务没开，手机找不到电脑：重启 OneSay 试试" : bindError + "：重启 OneSay，或在设置里换个端口");
        if (pcNoLan) return new Why(PC_NO_LAN, "电脑现在没连上局域网（没有 Wi-Fi 或网线地址），手机找不到它：先把电脑连上网");
        long one = newest(seen.oneShot, phoneLast), drop = newest(seen.dropped, phoneLast), paired = newest(seen.paired, null), ref = seen.refusedAt;
        boolean oneOk = one >= 0 && now - one <= ONE_SHOT_MS;
        boolean refOk = ref > 0 && now - ref <= RECENT_MS;
        boolean dropOk = drop >= 0 && now - drop <= RECENT_MS;
        boolean pairOk = paired >= 0 && now - paired <= RECENT_MS;
        // 最近的那件事说了算
        long best = -1;
        String pick = "";
        if (oneOk && one > best) { best = one; pick = RX_OFF; }
        if (refOk && ref > best) { best = ref; pick = "ref"; }
        if (dropOk && drop > best) { best = drop; pick = DROPPED; }
        if (pairOk && paired > best) { best = paired; pick = JUST_PAIRED; }
        switch (pick) {
            case RX_OFF:
                return new Why(RX_OFF, "手机连过来了，但没开「接受电脑指令」，所以不在线：在手机 OneSay 的「设置 › 连接 › 电脑联动」里打开它");
            case JUST_PAIRED:
                return new Why(JUST_PAIRED, "刚配对好。手机上打开「接受电脑指令」（设置 › 连接 › 电脑联动）就会一直在线");
            case DROPPED:
                return new Why(DROPPED, "手机 " + hm(drop) + " 断开了：多半是换了网络、锁屏省电或离开了家里的 Wi-Fi，回来后会自己连上");
            case "ref":
                if (seen.refusedCode == 4003) return new Why(PIN, "有手机来连，但它的证书和电脑记住的不一样（手机重装或清过数据？），配对失效了：在手机上重新配对，或在电脑设置里点「忘掉手机证书」");
                if (seen.refusedCode == 4002) return new Why(PLAIN, "有手机用不加密的旧配对来连，电脑不收：在手机上用新的二维码或「找附近的电脑」重新配对");
                return new Why(PAIR_BAD, "有手机拿旧的配对来连，被电脑拒了，配对失效（电脑换过钥匙，或手机上存的是另一台电脑）：在手机上重新配对就好");
            default:
                break;
        }
        if (phoneLast == null || phoneLast.isEmpty())
            return new Why(NEVER, "还没有手机连过这台电脑：在手机上扫电脑的二维码，或在手机上点「找附近的电脑」配对");
        return new Why(UNREACHABLE, "手机没连过来：多半不在同一个网络（手机和电脑要连同一个 Wi-Fi，或都开着 Tailscale），也可能手机上的 OneSay 没在运行、或「接受电脑指令」没开");
    }
}
