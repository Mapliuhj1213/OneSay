package com.onesay.desktop;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 双击 OneSay.exe 进来的地方：首次自动生成配置，常驻托盘，界面用 Edge 应用窗口打开本机页面。
 * 关掉窗口程序不退出，托盘「退出」才退出。
 */
public final class App {
    final Config cfg;
    final Store store;
    final Deque<String> recent = new ArrayDeque<>();
    final Consumer<String> log;
    Agent agent;
    Voice voice;
    Chat chat;
    Notify notify;
    Actions actions;
    UiServer ui;

    App(Config cfg) {
        this.cfg = cfg;
        Path dir = cfg.file.getParent() == null ? Config.defaultDir() : cfg.file.getParent();
        this.store = new Store(dir.resolve("state.json"));
        Path logFile = dir.resolve("onesay.log");
        this.log = s -> {
            String line = LocalTime.now().withNano(0) + " " + s;
            synchronized (recent) { recent.addLast(line); while (recent.size() > 80) recent.removeFirst(); }
            System.out.println(line);
            try {
                Files.createDirectories(dir);
                if (Files.exists(logFile) && Files.size(logFile) > 2_000_000) Files.move(logFile, dir.resolve("onesay.old.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) { }
        };
    }

    /** 没配置过就用默认值生成：本机名取电脑名、只开局域网、新钥匙。 */
    static void autoSetup(Config cfg) throws IOException {
        if (cfg.ready()) return;
        if (cfg.deviceId.isEmpty()) cfg.deviceId = Main.defaultId("pc-");
        if (!cfg.lanEnabled() && !cfg.relayEnabled()) cfg.lanPort = LanServer.DEFAULT_PORT;
        if (cfg.pairKey.length < 32) cfg.pairKey = Protocol.newKey();
        cfg.kind = "pc";
        cfg.save();
    }

    public static void launch(Config cfg, boolean showWindow) throws Exception {
        // 已经开着一个：只把窗口叫出来
        if (ping(UiServer.DEFAULT_PORT)) {
            if (showWindow) openWindow("http://127.0.0.1:" + UiServer.DEFAULT_PORT + "/");
            return;
        }
        autoSetup(cfg);
        App app = new App(cfg);
        app.start();
        if (showWindow) openWindow(app.ui.url());
        Thread.currentThread().join();
    }

    static boolean ping(int port) {
        try {
            HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(600)).build()
                    .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ping")).timeout(Duration.ofSeconds(1)).build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().equals("onesay");
        } catch (Exception e) {
            return false;
        }
    }

    void start() throws Exception {
        actions = "1".equals(System.getenv("ONESAY_TEST_ACTIONS")) ? new Main.TestActions() : new DesktopActions(cfg);
        notify = new Notify(store, log);
        chat = new Chat(cfg, store, actions, this::shortcuts, log);
        agent = new Agent(cfg, actions, log);
        agent.local.put("notify", a -> {
            notify.post(str(a, "kind", "app"), str(a, "title", ""), str(a, "text", ""), str(a, "_from", ""));
            return Actions.Result.ok("已在电脑上提醒");
        });
        agent.local.put("memory_sync", a -> {
            Object at = a.get("updated_at");
            Map<String, Object> m = store.mergeMemory(a.get("text") == null ? null : String.valueOf(a.get("text")), at instanceof Number ? ((Number) at).longValue() : 0L);
            return Actions.Result.ok("记忆已同步", m);
        });
        agent.start();
        voice = new Voice(cfg, actions, log);
        voice.fallback = text -> String.valueOf(chat.send(text, "voice").get("text"));
        voice.start();
        ui = new UiServer();
        routes();
        if (!ui.start(UiServer.DEFAULT_PORT) && !ui.start(0)) throw new IOException("界面端口打不开");
        log.accept("界面 " + ui.url());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { voice.stop(); agent.stop(); ui.stop(); }));
        if (!GraphicsEnvironment.isHeadless() && SystemTray.isSupported()) tray();
    }

    static String str(Map<String, Object> a, String k, String def) {
        Object v = a.get(k);
        return v == null ? def : String.valueOf(v);
    }

    List<String> shortcuts() {
        List<String> out = new ArrayList<>();
        if (cfg.scriptsDir.isEmpty()) return out;
        try (var s = Files.list(Paths.get(cfg.scriptsDir))) {
            s.sorted().forEach(p -> {
                String n = p.getFileName().toString();
                int dot = n.lastIndexOf('.');
                String ext = dot < 0 ? "" : n.substring(dot).toLowerCase();
                if (ext.equals(".ps1") || ext.equals(".bat") || ext.equals(".cmd")) out.add(n.substring(0, dot));
            });
        } catch (Exception ignored) { }
        return out;
    }

    static Map<String, Object> ok(String text) {
        Map<String, Object> m = new TreeMap<>();
        m.put("ok", true);
        m.put("text", text);
        return m;
    }

    static Map<String, Object> fail(String text) {
        Map<String, Object> m = ok(text);
        m.put("ok", false);
        return m;
    }

    Map<String, Object> settingsView() {
        Map<String, Object> s = new TreeMap<>();
        s.put("device_id", cfg.deviceId);
        s.put("lan_port", (long) cfg.lanPort);
        s.put("relay", cfg.relayEnabled());
        s.put("relay_url", cfg.relayUrl);
        s.put("ptt", cfg.pttEnabled);
        s.put("ptt_skip", cfg.pttSkip);
        s.put("asr_mode", cfg.asrMode);
        s.put("asr_key_set", !cfg.asrKey.isEmpty());
        s.put("llm_provider", cfg.llmProvider);
        s.put("llm_key_set", !cfg.llmKey.isEmpty());
        s.put("llm_model", cfg.llmModel);
        s.put("llm_model_default", Llm.defaultModel(cfg.llmProvider));
        s.put("llm_base", cfg.llmBase);
        s.put("files_dir", cfg.filesDir);
        s.put("scripts_dir", cfg.scriptsDir);
        s.put("apps", cfg.apps);
        s.put("autostart", autostartOn());
        return s;
    }

    void routes() {
        ui.on("state", b -> {
            Map<String, Object> s = store.snapshot();
            s.put("version", Main.VERSION);
            s.put("device", cfg.deviceId);
            s.put("status", agent.status);
            s.put("voice", voice.status);
            s.put("peers", new TreeMap<String, Object>(agent.peers()));
            s.put("settings", settingsView());
            s.put("caps", new ArrayList<Object>(actions.capabilities()));
            s.put("shortcuts", new ArrayList<Object>(shortcuts()));
            s.put("lan_ips", new ArrayList<Object>(LanClient.localAddresses()));
            synchronized (recent) { s.put("log", new ArrayList<Object>(recent)); }
            return s;
        });
        ui.on("chat", b -> chat.send(str(b, "text", ""), "text"));
        ui.on("chat_clear", b -> { store.clear("chat"); return ok("已清空对话，记忆保留"); });
        ui.on("memory", b -> { store.setMemory(str(b, "text", ""), System.currentTimeMillis()); return ok("记忆已保存"); });
        ui.on("todo", b -> {
            String op = str(b, "op", "");
            String id = str(b, "id", "");
            if (op.equals("add")) {
                String title = str(b, "title", "").trim();
                if (title.isEmpty()) return fail("写点内容");
                Map<String, Object> t = new TreeMap<>();
                t.put("title", Notify.clip(title, 200));
                t.put("due", str(b, "due", ""));
                t.put("done", false);
                store.add("todos", t, 0);
                return ok("已记下");
            }
            if (op.equals("toggle")) {
                Map<String, Object> t = store.find("todos", id);
                if (t == null) return fail("没找到");
                Map<String, Object> p = new TreeMap<>();
                p.put("done", !Boolean.TRUE.equals(t.get("done")));
                store.update("todos", id, p);
                return ok("好");
            }
            if (op.equals("del")) return store.remove("todos", id) ? ok("已删除") : fail("没找到");
            return fail("不认识的操作");
        });
        ui.on("notes", b -> {
            String op = str(b, "op", "");
            if (op.equals("clear")) { store.clear("notes"); return ok("已清空"); }
            if (op.equals("read")) {
                for (Map<String, Object> n : new ArrayList<>(store.list("notes"))) if (!Boolean.TRUE.equals(n.get("read"))) store.update("notes", String.valueOf(n.get("id")), Map.of("read", true));
                return ok("好");
            }
            if (op.equals("test")) { notify.post("pc", "通知测试", "能看到这条，说明 Windows 通知正常", cfg.deviceId); return ok("已发"); }
            return fail("不认识的操作");
        });
        ui.on("run", b -> {
            String cmd = str(b, "cmd", "");
            @SuppressWarnings("unchecked")
            Map<String, Object> args = b.get("args") instanceof Map ? (Map<String, Object>) b.get("args") : Map.of();
            if (!List.of("shortcut_run", "screenshot", "lock", "clipboard_get").contains(cmd)) return fail("界面不能直接执行这个");
            try {
                Actions.Result r = actions.run(cmd, args);
                return r.ok ? ok(cmd.equals("shortcut_run") ? "正在执行快捷指令" : r.text) : fail(r.text);
            } catch (Exception e) {
                return fail("出错：" + e.getClass().getSimpleName());
            }
        });
        ui.on("phone", b -> {
            if (agent.peers().isEmpty()) return fail("手机离线，没发出去");
            return fail("手机端还没接上这个功能（下一步做手机那边）");
        });
        ui.on("pairing", b -> {
            if (str(b, "op", "").equals("rekey")) {
                cfg.pairKey = Protocol.newKey();
                try { cfg.save(); } catch (IOException e) { return fail("保存失败"); }
                log.accept("已换新钥匙，旧配对全部失效");
            }
            Map<String, Object> m = ok("配对串");
            m.put("pairing", cfg.pairingString());
            return m;
        });
        ui.on("test_llm", b -> {
            Llm llm = Llm.from(cfg);
            if (!llm.ready()) return fail("还没填 Key" + (cfg.llmProvider.equals("custom") && cfg.llmBase.isEmpty() ? "和接口地址" : ""));
            try {
                String r = llm.chat(List.of(Llm.msg("user", "只回复两个字：正常")), 0, 20);
                return ok("连通了，模型 " + llm.model + " 回复：" + Notify.clip(r, 20));
            } catch (Exception e) {
                return fail(e.getMessage());
            }
        });
        ui.on("settings", b -> {
            boolean restartVoice = false;
            for (Map.Entry<String, Object> e : b.entrySet()) {
                String v = String.valueOf(e.getValue()).trim();
                switch (e.getKey()) {
                    case "llm_provider": if (List.of("deepseek", "bailian", "custom").contains(v)) cfg.llmProvider = v; break;
                    case "llm_key": if (v.equals("-")) cfg.llmKey = ""; else if (!v.isEmpty()) cfg.llmKey = v; break;
                    case "llm_model": cfg.llmModel = v; break;
                    case "llm_base": if (v.isEmpty() || v.startsWith("https://") || v.startsWith("http://127.0.0.1") || v.startsWith("http://localhost")) cfg.llmBase = v; else return fail("接口地址要用 https"); break;
                    case "asr_mode": if (List.of("local", "phone", "cloud").contains(v)) cfg.asrMode = v; break;
                    case "asr_key": if (v.equals("-")) cfg.asrKey = ""; else if (!v.isEmpty()) cfg.asrKey = v; restartVoice = true; break;
                    case "ptt": cfg.pttEnabled = v.equals("true"); restartVoice = true; break;
                    case "ptt_skip": cfg.pttSkip = v; restartVoice = true; break;
                    case "files_dir": cfg.filesDir = v; break;
                    case "scripts_dir": cfg.scriptsDir = v; break;
                    case "apps": cfg.apps = v; break;
                    case "autostart": autostart(v.equals("true")); break;
                    default: break;
                }
            }
            try { cfg.save(); } catch (IOException e) { return fail("保存失败"); }
            if (restartVoice) {
                voice.stop();
                Voice nv = new Voice(cfg, actions, log);
                nv.fallback = voice.fallback;
                voice = nv;
                voice.start();
            }
            Map<String, Object> m = ok("已保存");
            m.put("settings", settingsView());
            return m;
        });
    }

    // ---------- 开机自启：注册表 Run 键，启动后只进托盘 ----------
    static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }

    boolean autostartOn() {
        if (!isWindows()) return false;
        try {
            Process p = new ProcessBuilder("reg", "query", RUN_KEY, "/v", "OneSay").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    void autostart(boolean on) {
        if (!isWindows()) return;
        String exe = System.getProperty("onesay.exe", "");
        String cmd;
        if (!exe.isEmpty()) cmd = "\"" + exe + "\" --tray";
        else {
            try {
                String jar = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
                cmd = "\"" + Paths.get(System.getProperty("java.home"), "bin", "javaw.exe") + "\" -jar \"" + jar + "\" app --tray";
            } catch (Exception e) { return; }
        }
        try {
            ProcessBuilder pb = on ? new ProcessBuilder("reg", "add", RUN_KEY, "/v", "OneSay", "/t", "REG_SZ", "/d", cmd, "/f")
                    : new ProcessBuilder("reg", "delete", RUN_KEY, "/v", "OneSay", "/f");
            Process p = pb.redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor();
        } catch (Exception ignored) { }
    }

    // ---------- 窗口与托盘 ----------
    /** 优先用 Edge 应用窗口（无地址栏、独立图标）；没有 Edge 就用默认浏览器。 */
    static void openWindow(String url) {
        if (isWindows()) {
            List<String> cands = new ArrayList<>();
            for (String env : List.of("ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA")) {
                String base = System.getenv(env);
                if (base != null) cands.add(base + "\\Microsoft\\Edge\\Application\\msedge.exe");
            }
            for (String c : cands) {
                if (!new File(c).isFile()) continue;
                try {
                    String profile = Config.defaultDir().resolve("window").toString();
                    new ProcessBuilder(c, "--app=" + url, "--user-data-dir=" + profile, "--window-size=1200,800", "--no-first-run", "--no-default-browser-check").start();
                    return;
                } catch (IOException ignored) { }
            }
        }
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception ignored) { }
    }

    void tray() throws Exception {
        PopupMenu menu = new PopupMenu();
        MenuItem open = new MenuItem("打开 OneSay");
        open.addActionListener(e -> openWindow(ui.url()));
        MenuItem st = new MenuItem("OneSay：" + agent.status);
        st.setEnabled(false);
        MenuItem quit = new MenuItem("退出");
        quit.addActionListener(e -> System.exit(0));
        menu.add(open);
        menu.add(st);
        menu.addSeparator();
        menu.add(quit);
        TrayIcon icon = new TrayIcon(Main.dot(true), "OneSay " + cfg.deviceId, menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> openWindow(ui.url()));
        SystemTray.getSystemTray().add(icon);
        notify.attach(icon);
        new Thread(() -> {
            boolean last = true;
            while (true) {
                boolean on = !agent.peers().isEmpty();
                st.setLabel(on ? "手机在线" : "手机离线");
                if (on != last) { icon.setImage(Main.dot(on)); last = on; }
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
        }, "onesay-tray").start();
    }
}
