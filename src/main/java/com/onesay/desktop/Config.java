package com.onesay.desktop;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 本机配置：Windows 在 %APPDATA%\OneSay\，其他系统在 ~/.config/onesay/。
 * 令牌和配对密钥只存这里，不进日志。
 */
public final class Config {
    public int lanPort = LanServer.DEFAULT_PORT; // 局域网直连端口（UDP 发现用 +1）；0 = 关闭局域网
    public String lanHosts = "";      // 对方的局域网地址提示 ip:port,...（配对串带来）
    public String lanLast = "";       // 上次连上的地址
    public boolean pttEnabled = true; // 长按空格说话（Windows，需识别 Key）
    public String pttSkip = "mstsc.exe"; // 前台是这些程序时不抢空格，逗号分隔
    public String asrKey = "";        // 百炼 DashScope API Key（自带）
    public String asrBase = "";       // 空 = 默认北京地域
    public String relayUrl = "";      // 可选插件：https://onesay.example.com
    public String relayToken = "";
    public String deviceId = "";      // 本机 id
    public String kind = "pc";
    public String peers = "";         // 允许下令的设备 id，逗号分隔；空 = 任意持有配对密钥的设备
    public byte[] pairKey = new byte[0];
    public String filesDir = "";      // 允许取文件的文件夹
    public String scriptsDir = "";    // 电脑端快捷指令文件夹
    public String apps = "";          // 允许按名字打开的程序：名字=路径;名字=路径
    public String asrMode = "cloud";  // 旧版的识别方式（只为兼容旧配置 / 旧版本读）；现在以 asrPref 为准
    public String asrPref = "auto";   // 识别：auto 先看云端（默认，云端不可用自动回落本机离线）/ cloud 仅云端 / local 仅本机（AsrRoute.Pref）
    public String llmProvider = "deepseek"; // deepseek / bailian / custom（OpenAI 兼容）
    public String llmKey = "";        // 大模型 API Key（自带，只存本机）
    public String llmModel = "";      // 空 = 服务商默认
    public String llmBase = "";       // custom 时的接口地址，如 https://example.com/v1
    // ---- 协议 v2（0.5.0）----
    public String tlsKey = "";        // 本机 TLS 私钥（PKCS8，base64url）；第一次运行生成，之后不变（换了手机要重新扫码）
    public String tlsCert = "";       // 本机自签证书（DER，base64url）
    public String peerFp = "";        // 下令端：配对串里对方电脑的证书指纹（空 = 旧版配对串，走明文 v1）
    public String pins = "";          // 记住的对方设备证书指纹：设备id=指纹,...（首次 TLS 连上时记，换钥匙清空）
    public boolean lanPlain = true;   // 还接受旧版（v1，明文）手机；已经用 TLS 连过的设备不再接受明文
    public String phoneLast = "";     // 最近一台常连（hold）的手机 id，电脑→手机下令时找它
    public String pcLevel = "auto";   // 旧版的三档（read_only / auto / full）：只为兼容旧配置和旧手机；现在以下面两轴为准
    public String pcSandbox = "workspace_write"; // 沙箱档：read_only / workspace_write（默认）/ full_access（LinkPolicy.Sandbox）
    public String pcApproval = "untrusted";      // 审批策略：untrusted 分类（默认）/ on_request 按需 / never 从不（LinkPolicy.Approval）
    public boolean pluginEnabled = true; // 安全插件（默认开）：关掉只关可关项；永不提权 / 需管理员只生成脚本 / 禁止自授权三项关不掉
    public boolean pluginEncoded = true; // 插件里的「编码 / 隐藏命令（-enc、FromBase64String、iex）」拦截，单独一项
    public String netAllow = "";      // 沙箱默认断网：命令可以访问的域名白名单，逗号分隔
    public String shortcutApprovals = ""; // 手机触发快捷指令「以后直接执行」的批准表：id=内容哈希,...（LinkPolicy.parseApprovals）
    public String trusted = "";       // 手机让电脑打开网址时的信任域名，逗号分隔
    public String workspaceDir = "";  // 空 = %USERPROFILE%\OneSay\workspace（中文用户名退到 %ProgramData%\OneSay\workspace）

    private transient Tls tls;

    public final Path file;

    public Config(Path file) { this.file = file; }

    public static Path defaultDir() {
        String appdata = System.getenv("APPDATA");
        if (appdata != null && !appdata.isEmpty()) return Paths.get(appdata, "OneSay");
        return Paths.get(System.getProperty("user.home"), ".config", "onesay");
    }

    public static Config load(Path file) throws IOException {
        Config c = new Config(file);
        if (!Files.exists(file)) return c;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(r); }
        try { c.lanPort = Integer.parseInt(p.getProperty("lan_port", String.valueOf(LanServer.DEFAULT_PORT)).trim()); } catch (NumberFormatException e) { c.lanPort = LanServer.DEFAULT_PORT; }
        c.lanHosts = p.getProperty("lan_hosts", "").trim();
        c.lanLast = p.getProperty("lan_last", "").trim();
        c.pttEnabled = !"off".equalsIgnoreCase(p.getProperty("ptt", "on").trim());
        c.pttSkip = p.getProperty("ptt_skip", "mstsc.exe").trim();
        c.asrKey = p.getProperty("asr_key", "").trim();
        c.asrBase = p.getProperty("asr_base", "").trim();
        c.relayUrl = p.getProperty("relay_url", "").trim();
        c.relayToken = p.getProperty("relay_token", "").trim();
        c.deviceId = p.getProperty("device_id", "").trim();
        c.kind = p.getProperty("kind", "pc").trim();
        c.peers = p.getProperty("peers", "").trim();
        String k = p.getProperty("pair_key", "").trim();
        c.pairKey = k.isEmpty() ? new byte[0] : Base64.getUrlDecoder().decode(k);
        c.filesDir = p.getProperty("files_dir", "").trim();
        c.scriptsDir = p.getProperty("scripts_dir", "").trim();
        c.apps = p.getProperty("apps", "").trim();
        c.asrMode = p.getProperty("asr_mode", "cloud").trim();
        // 新键 asr_pref；旧配置只有 asr_mode 时：明确选过「本机离线」的保持仅本机，其余（云端 / 让手机识别，后者从没做出来）改成默认「先看云端」
        String asrPrefRaw = p.getProperty("asr_pref");
        c.asrPref = asrPrefRaw != null ? AsrRoute.Pref.of(asrPrefRaw).id : "local".equals(c.asrMode) ? AsrRoute.Pref.LOCAL.id : AsrRoute.Pref.AUTO.id;
        c.llmProvider = p.getProperty("llm_provider", "deepseek").trim();
        c.llmKey = p.getProperty("llm_key", "").trim();
        c.llmModel = p.getProperty("llm_model", "").trim();
        c.llmBase = p.getProperty("llm_base", "").trim();
        c.tlsKey = p.getProperty("tls_key", "").trim();
        c.tlsCert = p.getProperty("tls_cert", "").trim();
        c.peerFp = p.getProperty("peer_fp", "").trim();
        c.pins = p.getProperty("lan_pins", "").trim();
        c.lanPlain = !"off".equalsIgnoreCase(p.getProperty("lan_plain", "on").trim());
        c.phoneLast = p.getProperty("phone_last", "").trim();
        c.pcLevel = LinkPolicy.Level.of(p.getProperty("pc_level", "auto")).id;
        // 两轴：新配置直接读；旧配置只有 pc_level，按 LinkPolicy.Mode.ofLegacy 映射（只读 / 自动 / 完全访问 → 两轴）
        LinkPolicy.Mode legacy = LinkPolicy.Mode.ofLegacy(LinkPolicy.Level.of(c.pcLevel));
        String sb = p.getProperty("pc_sandbox", "").trim(), ap = p.getProperty("pc_approval", "").trim();
        c.pcSandbox = sb.isEmpty() ? legacy.sandbox.id : LinkPolicy.Sandbox.of(sb).id;
        c.pcApproval = ap.isEmpty() ? legacy.approval.id : LinkPolicy.Approval.of(ap).id;
        c.pcLevel = LinkPolicy.Mode.of(c.pcSandbox, c.pcApproval).legacy().id;
        c.pluginEnabled = !"off".equalsIgnoreCase(p.getProperty("plugin_enabled", "on").trim());
        c.pluginEncoded = !"off".equalsIgnoreCase(p.getProperty("plugin_encoded", "on").trim());
        c.netAllow = String.join(",", LinkPolicy.parseDomains(p.getProperty("net_allow", "")));
        c.trusted = p.getProperty("trusted_domains", "").trim();
        c.shortcutApprovals = LinkPolicy.formatApprovals(LinkPolicy.parseApprovals(p.getProperty("shortcut_approvals", "")));
        c.workspaceDir = p.getProperty("workspace_dir", "").trim();
        return c;
    }

    public void save() throws IOException {
        Files.createDirectories(file.getParent());
        Properties p = new Properties();
        p.setProperty("lan_port", String.valueOf(lanPort));
        p.setProperty("lan_hosts", lanHosts);
        p.setProperty("lan_last", lanLast);
        p.setProperty("ptt", pttEnabled ? "on" : "off");
        p.setProperty("ptt_skip", pttSkip);
        p.setProperty("asr_key", asrKey);
        p.setProperty("asr_base", asrBase);
        p.setProperty("relay_url", relayUrl);
        p.setProperty("relay_token", relayToken);
        p.setProperty("device_id", deviceId);
        p.setProperty("kind", kind);
        p.setProperty("peers", peers);
        p.setProperty("pair_key", Base64.getUrlEncoder().withoutPadding().encodeToString(pairKey));
        p.setProperty("files_dir", filesDir);
        p.setProperty("scripts_dir", scriptsDir);
        p.setProperty("apps", apps);
        p.setProperty("asr_pref", AsrRoute.Pref.of(asrPref).id);
        p.setProperty("asr_mode", AsrRoute.Pref.of(asrPref) == AsrRoute.Pref.LOCAL ? "local" : "cloud"); // 旧版本读这个
        p.setProperty("pc_level", mode().legacy().id); // 旧版本读这个
        p.setProperty("pc_sandbox", mode().sandbox.id);
        p.setProperty("pc_approval", mode().approval.id);
        p.setProperty("plugin_enabled", pluginEnabled ? "on" : "off");
        p.setProperty("plugin_encoded", pluginEncoded ? "on" : "off");
        p.setProperty("net_allow", netAllow);
        p.setProperty("trusted_domains", trusted);
        p.setProperty("shortcut_approvals", shortcutApprovals);
        p.setProperty("workspace_dir", workspaceDir);
        p.setProperty("llm_provider", llmProvider);
        p.setProperty("llm_key", llmKey);
        p.setProperty("llm_model", llmModel);
        p.setProperty("llm_base", llmBase);
        p.setProperty("tls_key", tlsKey);
        p.setProperty("tls_cert", tlsCert);
        p.setProperty("peer_fp", peerFp);
        p.setProperty("lan_pins", pins);
        p.setProperty("lan_plain", lanPlain ? "on" : "off");
        p.setProperty("phone_last", phoneLast);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { p.store(w, "OneSay desktop - contains secrets, do not share"); }
        try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException | IOException ignored) { }
    }

    public boolean ready() {
        return !deviceId.isEmpty() && pairKey.length >= 32 && (lanEnabled() || relayEnabled());
    }

    /** 两轴（沙箱档 × 审批策略）。 */
    public LinkPolicy.Mode mode() {
        LinkPolicy.Mode m = LinkPolicy.Mode.of(pcSandbox, pcApproval);
        // 旧代码直接改 pcLevel（和两轴对不上）时，按旧三档映射
        LinkPolicy.Level l = LinkPolicy.Level.of(pcLevel);
        return m.legacy() == l ? m : LinkPolicy.Mode.ofLegacy(l);
    }

    /** 最接近的旧三档（只给旧界面 / 旧手机显示）。 */
    public LinkPolicy.Level level() { return mode().legacy(); }

    /** 用旧三档设置：两轴跟着映射（旧界面 / 旧接口 pc_level 用）。 */
    public void setLegacyLevel(String v) {
        LinkPolicy.Mode m = LinkPolicy.Mode.ofLegacy(LinkPolicy.Level.of(v));
        pcSandbox = m.sandbox.id;
        pcApproval = m.approval.id;
        pcLevel = m.legacy().id;
    }

    public void setMode(LinkPolicy.Mode m) {
        pcSandbox = m.sandbox.id;
        pcApproval = m.approval.id;
        pcLevel = m.legacy().id;
    }

    /** 远程指令能写的工作区（不自动建）。Windows：%USERPROFILE%\OneSay\workspace，用户目录非 ASCII 时用 %ProgramData%；其他系统 ~/OneSay/workspace。 */
    public Path workspace() {
        if (!workspaceDir.isEmpty()) return Paths.get(workspaceDir);
        String up = System.getenv("USERPROFILE");
        if (up != null && !up.isEmpty()) return Paths.get(LinkPolicy.pcWorkspaceDir(up, System.getenv("ProgramData")));
        return Paths.get(System.getProperty("user.home"), "OneSay", "workspace");
    }

    public boolean lanEnabled() { return lanPort > 0 && lanPort < 65535; }

    /** 中转是可选插件：地址和令牌都有才启用。 */
    public boolean relayEnabled() { return !relayUrl.isEmpty() && relayToken.length() >= 24; }

    public URI wsUri() {
        String base = relayUrl.replaceAll("/+$", "");
        if (base.startsWith("https://")) base = "wss://" + base.substring(8);
        else if (base.startsWith("http://")) base = "ws://" + base.substring(7);
        return URI.create(base + "/v1/ws?device=" + enc(deviceId) + "&kind=" + enc(kind));
    }

    public Map<String, String> appMap() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String part : apps.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) m.put(part.substring(0, eq).trim().toLowerCase(), part.substring(eq + 1).trim());
        }
        return m;
    }

    public boolean peerAllowed(String from) {
        if (peers.isEmpty()) return true;
        for (String p : peers.split(",")) if (p.trim().equals(from)) return true;
        return false;
    }

    /**
     * 配对串 v2：onesay://pair?key=..&peer=<本机 id>&lan=ip:port,..[&relay=..&token=..]&fp=<证书指纹>&v=2
     * v1 串没有 fp / v（仍然接受，走明文）。整串等于钥匙，只在自己设备间传。中转部分可选。
     */
    public String pairingString() {
        StringBuilder b = new StringBuilder("onesay://pair?key=").append(Base64.getUrlEncoder().withoutPadding().encodeToString(pairKey))
                .append("&peer=").append(enc(deviceId));
        if (lanEnabled()) {
            StringBuilder l = new StringBuilder();
            for (String ip : LanClient.localAddresses()) l.append(l.length() == 0 ? "" : ",").append(ip).append(':').append(lanPort);
            if (l.length() == 0) l.append("0.0.0.0:").append(lanPort); // 没有局域网地址也要带端口
            b.append("&lan=").append(enc(l.toString()));
        }
        if (relayEnabled()) b.append("&relay=").append(enc(relayUrl)).append("&token=").append(enc(relayToken));
        // v2：带本机证书指纹（旧版手机 / 电脑会忽略不认识的参数，照旧走明文 v1）
        if (lanEnabled()) b.append("&fp=").append(tls().fp).append("&v=2");
        return b.toString();
    }

    /** 从配对串导入：返回对方设备 id。本机 id 由调用方设置。 */
    public String importPairing(String s) {
        if (!s.startsWith("onesay://pair?")) throw new IllegalArgumentException("不是 OneSay 配对串");
        Map<String, String> q = new LinkedHashMap<>();
        for (String kv : s.substring("onesay://pair?".length()).split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) q.put(kv.substring(0, eq), URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
        }
        String relay = q.getOrDefault("relay", ""), token = q.getOrDefault("token", ""), key = q.getOrDefault("key", ""), peer = q.getOrDefault("peer", "");
        String lan = q.getOrDefault("lan", "");
        String fp = q.getOrDefault("fp", ""), ver = q.getOrDefault("v", "1");
        int v;
        try { v = Integer.parseInt(ver.trim()); } catch (NumberFormatException e) { throw new IllegalArgumentException("配对串版本不对"); }
        if (v < 1) throw new IllegalArgumentException("配对串版本不对");
        if (v > 2) throw new IllegalArgumentException("配对串来自更新的版本（v" + v + "），请先升级本机");
        if (!fp.isEmpty() && !Tls.validFp(fp)) throw new IllegalArgumentException("配对串里的证书指纹不对");
        if (v >= 2 && fp.isEmpty() && !lan.isEmpty()) throw new IllegalArgumentException("配对串缺证书指纹");
        if (key.isEmpty() || peer.isEmpty() || !peer.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("配对串不完整");
        if (!relay.isEmpty() && token.length() < 24) throw new IllegalArgumentException("配对串里的中转令牌不完整");
        if (relay.isEmpty() && lan.isEmpty()) throw new IllegalArgumentException("配对串既没有局域网也没有中转");
        byte[] k = Base64.getUrlDecoder().decode(key);
        if (k.length < 32) throw new IllegalArgumentException("配对密钥太短");
        StringBuilder hosts = new StringBuilder();
        int port = 0;
        for (String part : lan.split(",")) {
            part = part.trim();
            int c = part.lastIndexOf(':');
            if (c <= 0) continue;
            try { port = Integer.parseInt(part.substring(c + 1)); } catch (NumberFormatException e) { continue; }
            if (!part.startsWith("0.0.0.0:")) hosts.append(hosts.length() == 0 ? "" : ",").append(part);
        }
        lanPort = lan.isEmpty() ? 0 : (port > 0 ? port : LanServer.DEFAULT_PORT);
        lanHosts = hosts.toString();
        lanLast = "";
        relayUrl = relay;
        relayToken = token;
        pairKey = k;
        peerFp = fp;
        return peer;
    }

    /** 本机 TLS 证书（没有就生成并保存）。 */
    public synchronized Tls tls() {
        if (tls == null) tls = Tls.load(tlsKey, tlsCert);
        if (tls == null) {
            tls = Tls.generate("onesay-" + (deviceId.isEmpty() ? "device" : deviceId));
            tlsKey = tls.keyB64();
            tlsCert = tls.certB64();
            try { if (file != null) save(); } catch (IOException ignored) { }
        }
        return tls;
    }

    /** 记住的设备证书指纹；没有返回 null。 */
    public synchronized String pinOf(String device) {
        for (String part : pins.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(device)) return part.substring(eq + 1);
        }
        return null;
    }

    public synchronized void pin(String device, String fp) {
        StringBuilder b = new StringBuilder();
        for (String part : pins.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0 || part.substring(0, eq).equals(device)) continue;
            b.append(b.length() == 0 ? "" : ",").append(part);
        }
        if (fp != null) b.append(b.length() == 0 ? "" : ",").append(device).append('=').append(fp);
        pins = b.toString();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
