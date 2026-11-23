package com.onesay.desktop;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * [always-on-1120] 网络唤醒（Wake-on-LAN）：魔术包、本机网卡、只读的「能不能被唤醒」检查。
 *
 * <p>电脑自己关着时发不了包，所以真正发包的是局域网里的另一台设备（手机，或常开小机器上的 {@link WakeAgent}）；
 * 电脑端只负责：列网卡和 MAC、检查（只读，不改任何设置）、把 MAC 和广播地址登记到常驻节点。
 *
 * <p>魔术包 = 6 个 0xFF + 目标 MAC 重复 16 次（102 字节），UDP 发到广播地址的 9 端口（7 也常见）。
 */
public final class WakeOnLan {
    private WakeOnLan() {}

    /** "AA:BB:CC:DD:EE:FF" / "aa-bb-.." / "aabbccddeeff" → 6 字节；不合法 → null。全 0、广播地址、组播位置位的不收。 */
    public static byte[] parseMac(String s) {
        if (s == null) return null;
        String h = s.trim().replaceAll("[:\\-. ]", "");
        if (!h.matches("[0-9A-Fa-f]{12}")) return null;
        byte[] b = new byte[6];
        for (int i = 0; i < 6; i++) b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        boolean zero = true, ff = true;
        for (byte x : b) { zero &= x == 0; ff &= x == (byte) 0xFF; }
        if (zero || ff || (b[0] & 1) == 1) return null;
        return b;
    }

    public static String fmtMac(byte[] b) {
        if (b == null || b.length != 6) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) { if (i > 0) sb.append(':'); sb.append(String.format(Locale.ROOT, "%02X", b[i] & 0xFF)); }
        return sb.toString();
    }

    public static String normMac(String s) { return fmtMac(parseMac(s)); }

    /** 魔术包（不带 SecureOn 密码）。 */
    public static byte[] magicPacket(byte[] mac) {
        if (mac == null || mac.length != 6) throw new IllegalArgumentException("MAC 要 6 字节");
        byte[] p = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) p[i] = (byte) 0xFF;
        for (int r = 0; r < 16; r++) System.arraycopy(mac, 0, p, 6 + r * 6, 6);
        return p;
    }

    /** 收到的一包是不是给这个 MAC 的魔术包（测试和唤醒代理自检用）。 */
    public static boolean isMagicFor(byte[] p, int len, byte[] mac) {
        if (p == null || mac == null || len < 102) return false;
        for (int i = 0; i < 6; i++) if (p[i] != (byte) 0xFF) return false;
        for (int r = 0; r < 16; r++) for (int i = 0; i < 6; i++) if (p[6 + r * 6 + i] != mac[i]) return false;
        return true;
    }

    /** 广播地址只收 IPv4 字面量（不解析域名）。 */
    public static boolean ipv4(String s) {
        if (s == null || !s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        for (String p : s.split("\\.")) if (Integer.parseInt(p) > 255) return false;
        return true;
    }

    /** 发 times 次（间隔 100 毫秒）到 bcast:port；返回发出的包数。 */
    public static int send(byte[] mac, String bcast, int port, int times) throws Exception {
        if (!ipv4(bcast)) throw new IllegalArgumentException("广播地址不对：" + bcast);
        if (port != 7 && port != 9 && !(port > 1024 && port < 65536)) throw new IllegalArgumentException("端口只用 7 / 9（测试可用高端口）");
        byte[] p = magicPacket(mac);
        int n = 0;
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            InetAddress to = InetAddress.getByName(bcast);
            for (int i = 0; i < Math.max(1, Math.min(times, 5)); i++) {
                s.send(new DatagramPacket(p, p.length, to, port));
                n++;
                if (i + 1 < times) Thread.sleep(100);
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ 本机网卡（Java 自带，跨平台）

    static boolean looksWireless(String name, String desc) {
        String s = (name + " " + desc).toLowerCase(Locale.ROOT);
        return s.matches(".*(wi-?fi|wlan|wireless|802\\.11|无线).*");
    }

    static boolean looksVirtual(String name, String desc) {
        String s = (name + " " + desc).toLowerCase(Locale.ROOT);
        return s.matches(".*(virtual|vmware|virtualbox|hyper-v|vethernet|docker|wsl|tap-|tun|loopback|bluetooth|蓝牙|vpn|wireguard|tailscale|zerotier|npcap|teredo|isatap|pseudo).*")
                || s.matches("^(docker|br-|veth|virbr|lo|tun|tap|utun|awdl|llw|zt|tailscale|wg)\\d*.*");
    }

    /** 本机物理网卡：名字、说明、MAC、IPv4、广播地址、有线 / 无线、是否连着。虚拟网卡 / 回环 / 没 MAC 的不列。 */
    public static List<Map<String, Object>> nics() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (ni.isLoopback() || ni.isVirtual() || ni.isPointToPoint()) continue;
                    byte[] hw = ni.getHardwareAddress();
                    if (hw == null || hw.length != 6 || parseMac(fmtMac(hw)) == null) continue;
                    String name = ni.getName(), desc = ni.getDisplayName() == null ? "" : ni.getDisplayName();
                    if (looksVirtual(name, desc)) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", name);
                    m.put("desc", desc);
                    m.put("mac", fmtMac(hw));
                    String ip = "", bc = "";
                    for (InterfaceAddress a : ni.getInterfaceAddresses()) {
                        if (a.getAddress() instanceof Inet4Address) {
                            ip = a.getAddress().getHostAddress();
                            if (a.getBroadcast() != null) bc = a.getBroadcast().getHostAddress();
                            else bc = broadcastOf(ip, a.getNetworkPrefixLength());
                            break;
                        }
                    }
                    m.put("ip", ip);
                    m.put("bcast", bc);
                    m.put("wireless", looksWireless(name, desc));
                    m.put("up", ni.isUp());
                    out.add(m);
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        out.sort((a, b) -> Boolean.compare(Boolean.TRUE.equals(b.get("up")) && !String.valueOf(b.get("ip")).isEmpty(), Boolean.TRUE.equals(a.get("up")) && !String.valueOf(a.get("ip")).isEmpty()));
        return out;
    }

    static String broadcastOf(String ip, int prefix) {
        if (!ipv4(ip) || prefix < 0 || prefix > 32) return "";
        String[] p = ip.split("\\.");
        long v = 0;
        for (String x : p) v = (v << 8) | Integer.parseInt(x);
        long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        long b = (v & mask) | (~mask & 0xFFFFFFFFL);
        return ((b >> 24) & 255) + "." + ((b >> 16) & 255) + "." + ((b >> 8) & 255) + "." + (b & 255);
    }

    // ------------------------------------------------------------------ Windows 只读检查

    /** [fix-1123-a] 结果行的开头：只认这一行（进度、警告、CLIXML 都不算）。 */
    static final String MARK = "ONESAY-WOL:";

    /**
     * 只读的 PowerShell：网卡电源管理（WakeOnMagicPacket）、「允许此设备唤醒计算机」（powercfg /devicequery wake_armed）、
     * 网卡高级属性里和唤醒有关的几项、快速启动（HiberbootEnabled）。只有 Get-* / powercfg 查询，不改任何设置。
     */
    static final String CHECK_PS = String.join("\n",
            "$ErrorActionPreference='SilentlyContinue'",
            "$ProgressPreference='SilentlyContinue'",
            "try { [Console]::OutputEncoding=[Text.Encoding]::UTF8 } catch { }",
            "$armed = @(powercfg /devicequery wake_armed)",
            "$fs = (Get-ItemProperty 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Power' -Name HiberbootEnabled).HiberbootEnabled",
            "$list = foreach ($a in @(Get-NetAdapter -Physical)) {",
            "  $pm = Get-NetAdapterPowerManagement -Name $a.Name",
            "  $adv = @(Get-NetAdapterAdvancedProperty -Name $a.Name | Where-Object { $_.RegistryKeyword -match 'WakeOnMagicPacket|WakeFromS5|S5WakeOnLan|WakeOnLan|EnablePME' } | ForEach-Object { @{k=[string]$_.RegistryKeyword; d=[string]$_.DisplayName; v=[string]$_.DisplayValue} })",
            "  @{ name=[string]$a.Name; desc=[string]$a.InterfaceDescription; mac=[string]$a.MacAddress; media=[string]$a.PhysicalMediaType; status=[string]$a.Status; magic=[string]$pm.WakeOnMagicPacket; armed=[bool]($armed -contains $a.InterfaceDescription); adv=$adv }",
            "}",
            "'" + MARK + "' + (@{ fast_startup=$fs; nics=@($list) } | ConvertTo-Json -Depth 4 -Compress)");

    /**
     * 跑只读检查（只在 Windows 上；15 秒超时）。返回原始 JSON 文本，失败返回 null。
     * <p>[fix-1123-a] 真机 1121：输出被重定向时 PowerShell 把模块加载进度写成 {@code #< CLIXML} 到 stderr，以前和 stdout 合并后从第一个「{」截，
     * 截出来的不是 JSON → 显示「PowerShell 没有输出」。改法同 4860769（凭据库）：丢掉 stderr、关进度、只认 {@link #MARK} 开头的结果行。
     */
    public static String runWindowsCheck() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return null;
        try {
            String enc = Base64.getEncoder().encodeToString(CHECK_PS.getBytes(StandardCharsets.UTF_16LE));
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", enc)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            p.getOutputStream().close();
            byte[] out = p.getInputStream().readNBytes(256 * 1024);
            if (!p.waitFor(15, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            return resultJson(new String(out, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** 从检查脚本的输出里取结果行的 JSON（最后一条 {@link #MARK} 开头的行）；没有返回 null。 */
    static String resultJson(String out) {
        if (out == null) return null;
        String[] ls = out.split("\\r?\\n");
        for (int i = ls.length - 1; i >= 0; i--) {
            String l = ls[i].trim();
            if (l.startsWith("\uFEFF")) l = l.substring(1);
            if (l.startsWith(MARK)) {
                String j = l.substring(MARK.length()).trim();
                return j.startsWith("{") ? j : null;
            }
        }
        return null;
    }

    /**
     * 把检查结果变成给人看的结论（每块网卡一条）：verdict = ok 可以 / warn 要改设置 / bad 不行 / unknown 看不出。
     * 没法从系统里读的（BIOS / UEFI 里的唤醒开关、ErP）只给提示，不假装知道。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> verdict(String json, boolean windows) {
        Map<String, Object> r = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        r.put("nics", rows);
        r.put("bios", "BIOS / UEFI 里的唤醒开关读不到：开机进 BIOS，打开「Wake on LAN」或「Power On By PCI-E / PME」，并关掉「ErP / 深度节能」。");
        if (!windows) { r.put("summary", "只在 Windows 上检查；这台系统请自己确认网卡和 BIOS 支持网络唤醒"); r.put("ok", false); return r; }
        Map<String, Object> m;
        try { m = json == null ? null : Json.obj(json); } catch (Exception e) { m = null; }
        if (m == null) { r.put("summary", "检查没跑成（PowerShell 没有输出）"); r.put("ok", false); return r; }
        Object fs = m.get("fast_startup");
        boolean fast = fs != null && "1".equals(String.valueOf(fs).replaceAll("\\.0$", ""));
        r.put("fast_startup", fast);
        Object ns = m.get("nics");
        List<Object> list = ns instanceof List ? (List<Object>) ns : ns instanceof Map ? List.of(ns) : List.of();
        boolean anyOk = false;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> n = (Map<String, Object>) o;
            String name = AlwaysOn.str(n.get("name")), desc = AlwaysOn.str(n.get("desc"));
            boolean wireless = looksWireless(name, desc) || AlwaysOn.str(n.get("media")).contains("802.11") || AlwaysOn.str(n.get("media")).toLowerCase(Locale.ROOT).contains("wireless");
            String magic = AlwaysOn.str(n.get("magic"));
            boolean armed = Boolean.TRUE.equals(n.get("armed"));
            List<String> notes = new ArrayList<>();
            String v;
            if (magic.equalsIgnoreCase("Unsupported")) { v = "bad"; notes.add("网卡不支持魔术包唤醒"); }
            else if (magic.equalsIgnoreCase("Enabled") && armed) { v = "ok"; notes.add("魔术包唤醒：开"); notes.add("允许此设备唤醒计算机：开"); }
            else if (magic.equalsIgnoreCase("Enabled")) { v = "warn"; notes.add("魔术包唤醒：开"); notes.add("「允许此设备唤醒计算机」没开（设备管理器 › 这块网卡 › 电源管理）"); }
            else if (magic.equalsIgnoreCase("Disabled")) { v = "warn"; notes.add("魔术包唤醒：关（设备管理器 › 这块网卡 › 电源管理里勾「只允许幻数据包唤醒计算机」）"); if (!armed) notes.add("允许此设备唤醒计算机：关"); }
            else { v = "unknown"; notes.add("看不出网卡是否支持（驱动没报）"); }
            if (n.get("adv") instanceof List) for (Object a : (List<Object>) n.get("adv")) if (a instanceof Map) {
                Map<String, Object> am = (Map<String, Object>) a;
                String k = AlwaysOn.str(am.get("k")), dv = AlwaysOn.str(am.get("v"));
                if (k.matches("(?i).*(S5|WakeFromS5).*") && dv.matches("(?i)(disabled|off|关闭|已禁用)")) { notes.add("网卡高级属性「" + AlwaysOn.str(am.get("d")) + "」是关的：关机（S5）后唤不醒，只能从睡眠唤醒"); if (v.equals("ok")) v = "warn"; }
            }
            if (wireless) { notes.add("这是无线网卡：大多数只能从睡眠唤醒，关机后唤醒请用网线"); if (v.equals("ok")) v = "warn"; }
            if (!"Up".equalsIgnoreCase(AlwaysOn.str(n.get("status"))) && !AlwaysOn.str(n.get("status")).isEmpty()) notes.add("现在没连着（" + AlwaysOn.str(n.get("status")) + "）");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("desc", desc);
            row.put("mac", normMac(AlwaysOn.str(n.get("mac"))));
            row.put("wireless", wireless);
            row.put("verdict", v);
            row.put("notes", notes);
            rows.add(row);
            anyOk |= v.equals("ok") && !wireless;
        }
        String sum;
        if (rows.isEmpty()) sum = "没找到物理网卡";
        else if (anyOk) sum = fast ? "网卡可以唤醒；「快速启动」开着，关机后可能唤不醒（睡眠可以）" : "网卡设置可以唤醒；还要确认 BIOS 里打开了网络唤醒";
        else sum = "网卡设置还不能唤醒，按下面的提示改（只读检查，OneSay 不替你改）";
        if (fast) r.put("fast_note", "「快速启动」开着：关机其实是半休眠，很多网卡这时不响应魔术包。控制面板 › 电源选项 › 选择电源按钮的功能 › 取消「启用快速启动」，或改用睡眠。");
        r.put("summary", sum);
        r.put("ok", anyOk);
        return r;
    }
}
