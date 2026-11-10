package com.onesay.desktop;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
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
import java.util.Base64;
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
    AsrRoute asr;
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

    /** 界面端口：默认 47830；测试 / 多开可用 -Donesay.ui.port 或环境变量 ONESAY_UI_PORT 指定（0 = 随机）。 */
    static int uiPort() {
        String p = System.getProperty("onesay.ui.port", System.getenv("ONESAY_UI_PORT"));
        try { return p == null || p.isEmpty() ? UiServer.DEFAULT_PORT : Integer.parseInt(p.trim()); } catch (NumberFormatException e) { return UiServer.DEFAULT_PORT; }
    }

    public static void launch(Config cfg, boolean showWindow) throws Exception {
        // 已经开着一个：只把窗口叫出来
        int up = uiPort();
        if (up > 0 && ping(up)) {
            if (showWindow) openWindow("http://127.0.0.1:" + up + "/");
            return;
        }
        autoSetup(cfg);
        cfg.tls(); // 协议 v2：第一次运行生成本机证书
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
        agent.confirms.onNew = d -> notify.post("pc", "手机请求，需要你确认", d + "（在 OneSay 窗口里用鼠标 / 键盘点，或在已配对手机屏幕上的确认卡上点；60 秒不点、关窗口、按 Esc 都算拒绝，说话不算批准）", "");
        agent.guard.notify = t -> notify.post("pc", "远程指令", t, "");
        agent.start();
        chat.phone = (cmd, args) -> callPhone(cmd, args);
        asr = new AsrRoute(cfg);
        asr.warmUp(); // 启动时先看云端能不能用（后台，不阻塞）
        voice = new Voice(cfg, actions, log, asr);
        voice.fallback = text -> String.valueOf(chat.send(text, "voice").get("text"));
        voice.approvalPending = () -> !agent.confirms.list().isEmpty();
        voice.start();
        ui = new UiServer();
        routes();
        if (!ui.start(uiPort()) && !ui.start(0)) throw new IOException("界面端口打不开");
        log.accept("界面 " + ui.url());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { voice.stop(); agent.stop(); ui.stop(); }));
        if (!GraphicsEnvironment.isHeadless() && SystemTray.isSupported()) tray();
        new Thread(App::ensureShortcuts, "onesay-shortcuts").start();
    }

    // ---------- 开始菜单与桌面快捷方式（用 exe 启动时） ----------
    /** 开始菜单的每次都核对（exe 搬了位置会跟着改）；桌面的只建一次，用户删了不再建。 */
    static void ensureShortcuts() {
        String exe = System.getProperty("onesay.exe", "");
        if (!isWindows() || exe.isEmpty() || !new File(exe).isFile()) return;
        String appdata = System.getenv("APPDATA");
        if (appdata == null) return;
        Path start = Paths.get(appdata, "Microsoft", "Windows", "Start Menu", "Programs", "OneSay.lnk");
        Path marker = Config.defaultDir().resolve("desktop-shortcut.done");
        StringBuilder ps = new StringBuilder("$w=New-Object -ComObject WScript.Shell;");
        ps.append("function mk($p){$s=$w.CreateShortcut($p);$s.TargetPath=").append(psq(exe))
          .append(";$s.WorkingDirectory=").append(psq(new File(exe).getParent()))
          .append(";$s.IconLocation=").append(psq(exe + ",0")).append(";$s.Description='OneSay';$s.Save()};");
        ps.append("mk ").append(psq(start.toString())).append(";");
        boolean desk = !Files.exists(marker);
        if (desk) ps.append("mk (Join-Path ([Environment]::GetFolderPath('Desktop')) 'OneSay.lnk');");
        try {
            String enc = Base64.getEncoder().encodeToString(ps.toString().getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", enc)
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() == 0 && desk) Files.writeString(marker, "1");
        } catch (Exception ignored) { }
    }

    /** 协议 v2：给手机下令（同步，最多十几秒）。 */
    Actions.Result callPhone(String cmd, Map<String, Object> args) {
        String bad = PhoneCmds.check(cmd, args);
        if (bad != null) return Actions.Result.fail(bad);
        try {
            return agent.callPeer(agent.phoneTarget(""), cmd, args, 4000, PhoneCmds.timeoutMs(cmd)).get(PhoneCmds.timeoutMs(cmd) + 6000, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return Actions.Result.fail("手机没有回应");
        }
    }

    static String psq(String s) { return "'" + s.replace("'", "''") + "'"; }

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
        s.put("asr_pref", AsrRoute.Pref.of(cfg.asrPref).id);
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
        s.put("pc_level", cfg.level().id);
        s.put("pc_sandbox", cfg.mode().sandbox.id);
        s.put("pc_approval", cfg.mode().approval.id);
        s.put("plugin_enabled", cfg.pluginEnabled);
        s.put("plugin_encoded", cfg.pluginEncoded);
        s.put("net_allow", cfg.netAllow);
        s.put("trusted_domains", cfg.trusted);
        return s;
    }

    void routes() {
        ui.on("state", b -> {
            agent.confirms.uiAlive(); // 界面还活着：确认框显示后界面 8 秒没动静按拒绝
            Map<String, Object> s = store.snapshot();
            s.put("version", Main.VERSION);
            s.put("device", cfg.deviceId);
            s.put("status", agent.status);
            s.put("voice", voice.status);
            s.putAll(asr.view()); // 识别：设置（先看云端 / 仅云端 / 仅本机）+ 当前用的是哪个 + 原因
            s.put("peers", new TreeMap<String, Object>(agent.peers()));
            s.put("settings", settingsView());
            s.put("caps", new ArrayList<Object>(actions.capabilities()));
            s.put("shortcuts", new ArrayList<Object>(shortcuts()));
            s.put("lan_ips", new ArrayList<Object>(LanClient.localAddresses()));
            String ph = agent.phoneTarget("");
            s.put("phone", ph == null ? "" : ph);
            s.put("tls_fp", Tls.shortFp(cfg.tls().fp));
            // link-v2 执行级别：手机的（只读显示，在手机上改）、电脑自己的（设置页改）、电脑上待确认的远程请求
            LinkPolicy.Level pl = agent.phoneLevel.isEmpty() ? null : LinkPolicy.Level.of(agent.phoneLevel);
            s.put("phone_level", pl == null ? "" : pl.id);
            s.put("phone_level_zh", pl == null ? "" : pl.zh);
            s.put("pc_level", cfg.level().id);
            s.put("pc_sandbox", cfg.mode().sandbox.id);
            s.put("pc_approval", cfg.mode().approval.id);
            s.put("plugin_enabled", cfg.pluginEnabled);
            s.put("confirms", agent.confirms.list());
            s.put("shortcut_policy", agent.guard.shortcutView());
            s.put("shortcut_approvals", agent.guard.approvalView());
            s.put("remembered", agent.guard.rememberedView());
            s.put("workspace", cfg.workspace().toString());
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
            String cmd = str(b, "cmd", "");
            @SuppressWarnings("unchecked")
            Map<String, Object> args = b.get("args") instanceof Map ? new TreeMap<>((Map<String, Object>) b.get("args")) : new TreeMap<>();
            if (cmd.equals("clipboard_set") && !args.containsKey("text")) {
                try {
                    Actions.Result c = actions.run("clipboard_get", Map.of());
                    Object t = c.ok && c.data != null ? c.data.get("text") : null;
                    if (t == null || String.valueOf(t).isEmpty()) return fail("电脑剪贴板里没有文字");
                    args.put("text", String.valueOf(t));
                } catch (Exception e) {
                    return fail("读不到电脑剪贴板");
                }
            }
            if (!PhoneCmds.ALL.contains(cmd)) return fail("手机还不支持这个（" + cmd + "）");
            Actions.Result r = callPhone(cmd, args);
            Map<String, Object> m = r.ok ? ok(cmd.equals("status") ? Chat.statusLine(r) : r.text) : fail(PhoneCmds.withReason(r));
            if (r.data != null) m.put("data", new TreeMap<String, Object>(r.data));
            return m;
        });
        ui.on("confirm", b -> confirmApi(agent.confirms, b, log));
        // 「以后这类不用问」记住的动作类型：看 / 撤销（只能在电脑界面上；远程改不了）
        ui.on("remembered", b -> {
            String op = str(b, "op", "list");
            if (op.equals("revoke")) {
                String k = str(b, "kind", "");
                if (!agent.guard.forget(k)) return fail("没有这一类");
                log.accept("撤销了「以后这类不用问」：" + k);
            } else if (!op.equals("list")) return fail("不认识的操作");
            Map<String, Object> m = ok(op.equals("revoke") ? "已撤销，下次这类会再问你" : "记住的类型");
            m.put("remembered", agent.guard.rememberedView());
            return m;
        });
        // 快捷指令批准表：看 / 撤销（批准只能在手机触发时的确认里点「以后直接执行」）
        ui.on("shortcuts", b -> {
            String op = str(b, "op", "list");
            if (op.equals("revoke")) {
                String id = str(b, "id", "");
                if (!agent.guard.revoke(id)) return fail("没有这条批准");
                log.accept("撤销了快捷指令批准：" + id);
                Map<String, Object> m = ok("已撤销，下次从手机触发会再问你");
                m.put("approvals", agent.guard.approvalView());
                return m;
            }
            if (!op.equals("list")) return fail("不认识的操作");
            Map<String, Object> m = ok("快捷指令批准");
            m.put("shortcuts", agent.guard.shortcutView());
            m.put("approvals", agent.guard.approvalView());
            return m;
        });
        ui.on("pairing", b -> {
            if (str(b, "op", "").equals("unpin")) {
                cfg.pins = "";
                try { cfg.save(); } catch (IOException e) { return fail("保存失败"); }
                log.accept("已忘掉手机证书，下次加密连上时重新记住");
                return ok("已忘掉手机证书");
            }
            if (str(b, "op", "").equals("rekey")) {
                cfg.pairKey = Protocol.newKey();
                cfg.pins = "";
                try { cfg.save(); } catch (IOException e) { return fail("保存失败"); }
                log.accept("已换新钥匙，旧配对全部失效");
            }
            Map<String, Object> m = ok("配对串");
            m.put("pairing", cfg.pairingString());
            m.put("fp", Tls.shortFp(cfg.tls().fp));
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
                    case "asr_mode": if (List.of("local", "phone", "cloud").contains(v)) cfg.asrMode = v; break; // 旧接口，只存不用
                    case "asr_pref": if (List.of("auto", "cloud", "local").contains(v)) { cfg.asrPref = v; asr.invalidate(); restartVoice = true; log.accept("识别 → " + AsrRoute.Pref.of(v).zh); } break;
                    case "asr_key": if (v.equals("-")) cfg.asrKey = ""; else if (!v.isEmpty()) cfg.asrKey = v; asr.invalidate(); restartVoice = true; break;
                    case "ptt": cfg.pttEnabled = v.equals("true"); restartVoice = true; break;
                    case "ptt_skip": cfg.pttSkip = v; restartVoice = true; break;
                    case "files_dir": cfg.filesDir = v; break;
                    case "scripts_dir": cfg.scriptsDir = v; break;
                    case "apps": cfg.apps = v; break;
                    case "pc_level": cfg.setLegacyLevel(v); log.accept("电脑档位 → " + cfg.mode()); break; // 旧接口：三档映射到两轴
                    case "pc_sandbox": cfg.setMode(new LinkPolicy.Mode(LinkPolicy.Sandbox.of(v), cfg.mode().approval)); log.accept("沙箱档 → " + cfg.pcSandbox); break;
                    case "pc_approval": cfg.setMode(new LinkPolicy.Mode(cfg.mode().sandbox, LinkPolicy.Approval.of(v))); log.accept("审批策略 → " + cfg.pcApproval); break;
                    case "plugin_enabled": {
                        String err = pluginSwitch(cfg, v, b.get("plugin_off_confirm"));
                        if (err != null) return fail(err);
                        log.accept("安全插件 → " + (cfg.pluginEnabled ? "开" : "关（永不提权、需管理员只生成脚本、禁止自授权仍然生效）"));
                        break;
                    }
                    case "plugin_off_confirm": break;
                    case "plugin_encoded": cfg.pluginEncoded = v.equals("true"); log.accept("编码 / 隐藏命令拦截 → " + (cfg.pluginEncoded ? "开" : "关")); break;
                    case "net_allow": cfg.netAllow = String.join(",", LinkPolicy.parseDomains(v)); break;
                    case "trusted_domains": cfg.trusted = String.join(",", LinkPolicy.parseDomains(v)); break;
                    case "autostart": autostart(v.equals("true")); break;
                    default: break;
                }
            }
            try { cfg.save(); } catch (IOException e) { return fail("保存失败"); }
            if (restartVoice) {
                voice.stop();
                Voice nv = new Voice(cfg, actions, log, asr);
                nv.fallback = voice.fallback;
                nv.approvalPending = voice.approvalPending;
                voice = nv;
                voice.start();
                asr.warmUp();
            }
            Map<String, Object> m = ok("已保存");
            m.put("settings", settingsView());
            return m;
        });
    }

    /**
     * 界面的确认框接口（本机口令保护）。op：
     * <ul>
     *   <li>seen {id, focused}：确认框画出来了（从这时起算 0.7 秒防误点）；</li>
     *   <li>window {focused}：窗口拿到 / 失去焦点（失焦后没人点按拒绝；拿回焦点重新等 0.7 秒）；</li>
     *   <li>closed：窗口要关了 —— 所有在等的请求按拒绝；</li>
     *   <li>默认（回答）{id, allow, always, remember, input, trusted}：remember = 勾了「以后这类不用问」；允许只认本机鼠标 / 键盘（input = mouse / keyboard 且 trusted = true，
     *       即浏览器标记的真实用户输入），其余回 voice_approval_blocked 且请求照旧等；拒绝从哪来都收。</li>
     * </ul>
     */
    static Map<String, Object> confirmApi(PcGuard.Confirms cf, Map<String, Object> b, java.util.function.Consumer<String> log) {
        String op = str(b, "op", "answer");
        Object id = b.get("id");
        switch (op) {
            case "seen":
                if (!(id instanceof Number) || !cf.seen(((Number) id).intValue(), Boolean.TRUE.equals(b.get("focused")))) return fail("请求已经过期");
                Map<String, Object> m = ok("");
                m.put("arm_ms", ConfirmGate.ARM_MS);
                return m;
            case "window":
                cf.window(Boolean.TRUE.equals(b.get("focused")));
                return ok("");
            case "closed": {
                int n = cf.closeAll();
                if (n > 0) log.accept("确认窗口关了，" + n + " 条远程请求按拒绝处理");
                return ok(n > 0 ? "已拒绝" : "");
            }
            case "answer":
                break;
            default:
                return fail("不认识的操作");
        }
        if (!(id instanceof Number)) return fail("没有这条请求");
        boolean allow = Boolean.TRUE.equals(b.get("allow"));
        boolean always = allow && Boolean.TRUE.equals(b.get("always"));
        boolean remember = allow && !always && Boolean.TRUE.equals(b.get("remember"));
        ConfirmGate.Input in = Boolean.TRUE.equals(b.get("trusted")) ? ConfirmGate.Input.of(b.get("input")) : ConfirmGate.Input.UNKNOWN;
        ConfirmGate.Answer a = cf.answer(((Number) id).intValue(), allow, always, remember, in);
        switch (a) {
            case ACCEPTED:
                log.accept((allow ? (always ? "允许（以后直接执行）" : remember ? "允许（以后这类不用问）" : "允许") : "拒绝") + "了一条远程请求（" + (in.local() ? (in == ConfirmGate.Input.KEYBOARD ? "键盘" : "鼠标") : "界面") + "）");
                return ok(allow ? (always ? "已允许，以后直接执行" : remember ? "已允许，以后这类不用问" : "已允许一次") : "已拒绝");
            case NOT_LOCAL: {
                log.accept("一条不是本机鼠标 / 键盘的「允许」没算数：" + LinkPolicy.R_VOICE_APPROVAL_BLOCKED);
                Map<String, Object> f = fail(LinkPolicy.reasonZh(LinkPolicy.R_VOICE_APPROVAL_BLOCKED));
                f.put("reason", LinkPolicy.R_VOICE_APPROVAL_BLOCKED);
                return f;
            }
            case NOT_SHOWN:
            case TOO_EARLY: {
                Map<String, Object> f = fail("「允许」还没解锁（确认框出现 / 窗口切回来后要等一下），再点一次");
                f.put("reason", "not_armed");
                return f;
            }
            default:
                return fail("请求已经过期");
        }
    }

    /**
     * 安全插件开关（只在电脑界面上能改，界面接口要本机口令）：打开直接开；关掉要二次确认（界面上点两次后带 plugin_off_confirm=true）。
     * 返回 null 表示改了，否则是给人看的原因。关掉只关可关项，三条硬规则仍然生效。
     */
    static String pluginSwitch(Config cfg, String v, Object confirm) {
        if ("true".equals(v)) { cfg.pluginEnabled = true; return null; }
        if (!Boolean.TRUE.equals(confirm)) return "关掉安全插件要再确认一次";
        cfg.pluginEnabled = false;
        return null;
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

    /** 托盘图标用 One 图标，手机离线时变灰。 */
    static Image trayImage(boolean online) {
        try (var in = App.class.getClassLoader().getResourceAsStream("onesay/ui/icon64.png")) {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(in);
            if (online) return img;
            java.awt.image.BufferedImage g = new java.awt.image.BufferedImage(img.getWidth(), img.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) {
                int c = img.getRGB(x, y), a = c >>> 24, r = (c >> 16) & 255, gg = (c >> 8) & 255, b = c & 255;
                int l = (r * 30 + gg * 59 + b * 11) / 100;
                g.setRGB(x, y, (a << 24) | (l << 16) | (l << 8) | l);
            }
            return g;
        } catch (Exception e) {
            return Main.dot(online);
        }
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
        TrayIcon icon = new TrayIcon(trayImage(true), "OneSay " + cfg.deviceId, menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> openWindow(ui.url()));
        SystemTray.getSystemTray().add(icon);
        notify.attach(icon);
        new Thread(() -> {
            boolean last = true;
            while (true) {
                boolean on = !agent.peers().isEmpty();
                st.setLabel(on ? "手机在线" : "手机离线");
                if (on != last) { icon.setImage(trayImage(on)); last = on; }
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
        }, "onesay-tray").start();
    }
}
