package com.onesay.desktop;

import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.Console;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Main {
    static final String VERSION = "0.2.0";

    public static void main(String[] argv) throws Exception {
        List<String> a = new ArrayList<>(List.of(argv));
        Path cfgFile = Config.defaultDir().resolve("desktop.properties");
        int ci = a.indexOf("--config");
        if (ci >= 0 && ci + 1 < a.size()) { cfgFile = Paths.get(a.get(ci + 1)); a.remove(ci + 1); a.remove(ci); }
        String sub = a.isEmpty() ? "run" : a.remove(0);
        Config cfg = Config.load(cfgFile);
        switch (sub) {
            case "setup": setup(cfg); break;
            case "pair": pair(cfg); break;
            case "join": join(cfg, a); break;
            case "run": run(cfg, a.contains("--console")); break;
            case "send": System.exit(send(cfg, a)); break;
            case "autostart": autostart(a.isEmpty() ? "" : a.get(0)); break;
            case "version": System.out.println("OneSay Desktop " + VERSION); break;
            default: usage();
        }
    }

    static void usage() {
        System.out.println(String.join("\n",
                "OneSay Desktop " + VERSION,
                "  setup                    首次设置：本机名、局域网端口、可选中转，生成配对密钥",
                "  pair                     显示配对串（给手机或另一台电脑导入；整串等于钥匙）",
                "  join <配对串> [--id 名字]  在下令端导入配对串",
                "  run [--console]          常驻（有图形界面时进系统托盘）",
                "  send <设备> <指令> [键=值 ...] [--out 文件夹]",
                "  autostart on|off         开机自启（Windows）",
                "  通用：--config <文件>     指定配置文件"));
    }

    static String ask(String prompt, String def) throws Exception {
        System.out.print(prompt + (def.isEmpty() ? "" : " [" + def + "]") + "：");
        String s = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        s = s == null ? "" : s.trim();
        return s.isEmpty() ? def : s;
    }

    static String defaultId(String prefix) {
        String h;
        try { h = InetAddress.getLocalHost().getHostName(); } catch (Exception e) { h = "host"; }
        h = h.replaceAll("[^A-Za-z0-9._-]", "-").toLowerCase();
        return (prefix + h).substring(0, Math.min(64, prefix.length() + h.length()));
    }

    static void setup(Config cfg) throws Exception {
        cfg.deviceId = ask("本机名（只用字母数字.-_）", cfg.deviceId.isEmpty() ? defaultId("pc-") : cfg.deviceId);
        if (!cfg.deviceId.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("本机名不合法");
        String port = ask("局域网端口（0 = 不用局域网）", String.valueOf(cfg.lanPort));
        cfg.lanPort = Integer.parseInt(port.trim());
        if (cfg.lanPort != 0 && (cfg.lanPort < 1024 || cfg.lanPort > 65534)) throw new IllegalArgumentException("端口要在 1024–65534");
        System.out.println("中转是可选插件：不在同一个 Wi-Fi 也要能控制时才需要。留空 = 只用局域网。");
        String url = ask("中转地址（如 https://onesay.example.com，输 - 清除）", cfg.relayUrl);
        if (url.equals("-")) { cfg.relayUrl = ""; cfg.relayToken = ""; }
        else if (!url.isEmpty()) {
            cfg.relayUrl = url;
            Console con = System.console();
            String tok = con != null ? new String(con.readPassword("中转令牌（输入不显示，回车保留原值）：")) : ask("中转令牌", "");
            if (!tok.isBlank()) cfg.relayToken = tok.trim();
            if (cfg.relayToken.length() < 24) throw new IllegalArgumentException("令牌太短（至少 24 位）");
        }
        if (!cfg.lanEnabled() && !cfg.relayEnabled()) throw new IllegalArgumentException("局域网和中转至少开一个");
        if (cfg.pairKey.length < 32) cfg.pairKey = Protocol.newKey();
        cfg.kind = "pc";
        cfg.filesDir = ask("允许取文件的文件夹（留空=不开）", cfg.filesDir);
        cfg.scriptsDir = ask("快捷指令脚本文件夹（留空=不开）", cfg.scriptsDir);
        cfg.save();
        System.out.println("已保存到 " + cfg.file + "\n下一步：run 常驻；pair 拿配对串，在下令端 join。"
                + (cfg.lanEnabled() ? "\n首次 run 时 Windows 防火墙会问是否允许 Java 联网：勾「专用网络」。" : ""));
    }

    static void pair(Config cfg) {
        if (!cfg.ready()) { System.out.println("还没设置，先运行 setup"); return; }
        if (cfg.lanEnabled() && LanClient.localAddresses().isEmpty()) System.out.println("注意：没找到局域网地址，配对串里只带端口，下令端靠广播找。");
        System.out.println("配对串（整串等于钥匙，只在自己的设备之间传，不要发到群里或网上）：\n" + cfg.pairingString());
    }

    static void join(Config cfg, List<String> a) throws Exception {
        if (a.isEmpty()) { System.out.println("用法：join <配对串> [--id 名字]"); return; }
        String peer = cfg.importPairing(a.get(0));
        int ii = a.indexOf("--id");
        cfg.deviceId = ii >= 0 && ii + 1 < a.size() ? a.get(ii + 1) : (cfg.deviceId.isEmpty() ? defaultId("cli-") : cfg.deviceId);
        cfg.kind = "other";
        cfg.save();
        System.out.println("已配对。本机名 " + cfg.deviceId + "，可下令给 " + peer + "，例如：send " + peer + " screenshot");
    }

    static int send(Config cfg, List<String> a) throws Exception {
        if (!cfg.ready()) { System.out.println("还没配对，先运行 join"); return 3; }
        if (a.size() < 2) { usage(); return 64; }
        Path out = Paths.get(".");
        int oi = a.indexOf("--out");
        if (oi >= 0 && oi + 1 < a.size()) { out = Paths.get(a.get(oi + 1)); a.remove(oi + 1); a.remove(oi); }
        String to = a.get(0), cmd = a.get(1);
        Map<String, Object> args = new TreeMap<>();
        for (String kv : a.subList(2, a.size())) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = kv.substring(0, eq), v = kv.substring(eq + 1);
            if (v.equals("true") || v.equals("false")) args.put(k, Boolean.valueOf(v));
            else if (v.matches("-?\\d{1,15}")) args.put(k, Long.parseLong(v));
            else args.put(k, v);
        }
        return Cli.send(cfg, to, cmd, args, out, 60);
    }

    static void run(Config cfg, boolean console) throws Exception {
        if (!cfg.ready()) { System.out.println("还没设置，先运行 setup"); return; }
        Actions actions = "1".equals(System.getenv("ONESAY_TEST_ACTIONS")) ? new TestActions() : new DesktopActions(cfg);
        Agent agent = new Agent(cfg, actions, s -> System.out.println("[" + java.time.LocalTime.now().withNano(0) + "] " + s));
        agent.start();
        Runtime.getRuntime().addShutdownHook(new Thread(agent::stop));
        if (!console && !GraphicsEnvironment.isHeadless() && SystemTray.isSupported()) tray(cfg, agent);
        Thread.currentThread().join();
    }

    static void tray(Config cfg, Agent agent) throws Exception {
        PopupMenu menu = new PopupMenu();
        MenuItem st = new MenuItem("OneSay：" + agent.status);
        st.setEnabled(false);
        MenuItem copy = new MenuItem("复制配对串");
        copy.addActionListener(e -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(cfg.pairingString()), null));
        MenuItem quit = new MenuItem("退出");
        quit.addActionListener(e -> { agent.stop(); System.exit(0); });
        menu.add(st);
        menu.addSeparator();
        menu.add(copy);
        menu.add(quit);
        TrayIcon icon = new TrayIcon(dot(true), "OneSay " + cfg.deviceId, menu);
        icon.setImageAutoSize(true);
        SystemTray.getSystemTray().add(icon);
        new Thread(() -> {
            boolean last = false;
            while (true) {
                boolean on = agent.connected();
                st.setLabel("OneSay：" + agent.status);
                if (on != last) { icon.setImage(dot(on)); last = on; }
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
        }, "onesay-tray").start();
    }

    static Image dot(boolean on) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(on ? new java.awt.Color(0x2E, 0xB8, 0x5C) : new java.awt.Color(0x99, 0x99, 0x99));
        g.fillOval(1, 1, 14, 14);
        g.dispose();
        return img;
    }

    static void autostart(String onOff) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) { System.out.println("仅 Windows 支持"); return; }
        String jar = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
        String javaw = Paths.get(System.getProperty("java.home"), "bin", "javaw.exe").toString();
        String key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
        ProcessBuilder pb = onOff.equals("on")
                ? new ProcessBuilder("reg", "add", key, "/v", "OneSay", "/t", "REG_SZ", "/d", "\"" + javaw + "\" -jar \"" + jar + "\" run", "/f")
                : onOff.equals("off") ? new ProcessBuilder("reg", "delete", key, "/v", "OneSay", "/f") : null;
        if (pb == null) { System.out.println("用法：autostart on|off"); return; }
        int code = pb.inheritIO().start().waitFor();
        System.out.println(code == 0 ? "已" + (onOff.equals("on") ? "开启" : "关闭") + "开机自启" : "失败（" + code + "）");
    }

    /** 测试用：不碰真机，截图返回一张生成的图。 */
    static final class TestActions implements Actions {
        @Override public List<String> capabilities() { return List.of("ping", "screenshot", "echo"); }

        @Override
        public Result run(String cmd, Map<String, Object> args) throws Exception {
            switch (cmd) {
                case "ping": return Result.ok("在线");
                case "echo": return Result.ok("echo", DesktopActions.map("args", args));
                case "screenshot": {
                    BufferedImage img = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
                    var g = img.createGraphics();
                    g.setColor(java.awt.Color.ORANGE);
                    g.fillRect(100, 100, 800, 400);
                    g.dispose();
                    return Result.ok("截图 1920×1080", DesktopActions.imageData(img));
                }
                default: return Result.fail("不支持的指令：" + cmd);
            }
        }
    }
}
