package com.onesay.assistant.v2.pclink;

/**
 * [rel-1123-b] 手机「电脑联动」页上的在线 / 离线说法（纯 Java，JVM 可测）：离线时写清原因和下一步。
 * 和电脑端 {@code OfflineWhy} 说的是同一件事的两头。
 */
public final class PcLinkText {

    private PcLinkText() {}

    public static final String OK = "ok", WARN = "warn", BAD = "bad";

    /**
     * 一次在线探测（{@code PcLink.probe}）的结果 → {值, 色调, 说明}。wifi = 手机连着 Wi-Fi / 有线；rxOn = 「接受电脑指令」开着；
     * rxUp = 常连已连上；name = 电脑名；via = 经由的地址（可空）。
     */
    public static String[] probe(PcLanClient.Result r, boolean wifi, boolean rxOn, boolean rxUp, String name, String via) {
        if (r.ok) {
            String sub = "经由局域网" + (via == null || via.length() == 0 ? "" : " " + via) + " · " + name;
            if (!rxOn) sub += "。电脑那边会显示这台手机离线：要让电脑随时找得到手机，打开下面的「接受电脑指令」";
            return new String[]{"在线", OK, sub};
        }
        if ("auth".equals(r.fail)) return new String[]{"配对失效", BAD, "电脑不认这个配对了（电脑换过钥匙，或配的是另一台电脑）：解除配对后重新配对"};
        if ("tls".equals(r.fail) || "pin".equals(r.fail)) return new String[]{"配对失效", BAD, r.text};
        if (!wifi) return new String[]{"离线", WARN, "手机没连 Wi-Fi：手机和电脑要在同一个网络里（同一个 Wi-Fi，或都开着 Tailscale）"};
        return new String[]{"离线", WARN, "没找到 " + name + "：多半不在同一个网络（要连同一个 Wi-Fi，或都开着 Tailscale），或电脑上的 OneSay 没开"};
    }

    /** 常连循环状态的说法（「接受电脑指令」那一行）。 */
    public static String rxState(String state, String detail) {
        if (PcRx.S_UP.equals(state)) return "已连上 " + detail;
        if (PcRx.S_SEARCH.equals(state)) return "正在找电脑…";
        if (PcRx.S_WAIT.equals(state)) return "没找到电脑：可能不在同一个网络，或电脑上的 OneSay 没开（电脑一开就会自己连上）";
        if (PcRx.S_NO_WIFI.equals(state)) return "手机没连 Wi-Fi，先停着（连上 Wi-Fi 会自己接着找）";
        if (PcRx.S_ERROR.equals(state)) return detail;
        return "没在运行";
    }
}
