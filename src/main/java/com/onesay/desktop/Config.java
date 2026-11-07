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
    public String relayUrl = "";      // 可选插件：https://onesay.example.com
    public String relayToken = "";
    public String deviceId = "";      // 本机 id
    public String kind = "pc";
    public String peers = "";         // 允许下令的设备 id，逗号分隔；空 = 任意持有配对密钥的设备
    public byte[] pairKey = new byte[0];
    public String filesDir = "";      // 允许取文件的文件夹
    public String scriptsDir = "";    // 电脑端快捷指令文件夹
    public String apps = "";          // 允许按名字打开的程序：名字=路径;名字=路径

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
        return c;
    }

    public void save() throws IOException {
        Files.createDirectories(file.getParent());
        Properties p = new Properties();
        p.setProperty("lan_port", String.valueOf(lanPort));
        p.setProperty("lan_hosts", lanHosts);
        p.setProperty("lan_last", lanLast);
        p.setProperty("relay_url", relayUrl);
        p.setProperty("relay_token", relayToken);
        p.setProperty("device_id", deviceId);
        p.setProperty("kind", kind);
        p.setProperty("peers", peers);
        p.setProperty("pair_key", Base64.getUrlEncoder().withoutPadding().encodeToString(pairKey));
        p.setProperty("files_dir", filesDir);
        p.setProperty("scripts_dir", scriptsDir);
        p.setProperty("apps", apps);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { p.store(w, "OneSay desktop - contains secrets, do not share"); }
        try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException | IOException ignored) { }
    }

    public boolean ready() {
        return !deviceId.isEmpty() && pairKey.length >= 32 && (lanEnabled() || relayEnabled());
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
     * 配对串：onesay://pair?key=..&peer=<本机 id>&lan=ip:port,..[&relay=..&token=..]
     * 整串等于钥匙，只在自己设备间传。中转部分可选。
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
        return peer;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
