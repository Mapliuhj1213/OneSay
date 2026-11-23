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
import java.util.LinkedHashMap;
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
    Media media;
    /** 设置页「下载」区块里工具的状态（算 SHA-256 较慢，不每 2.5 秒算：启动、安装完、打开区块时刷新）。 */
    volatile Map<String, Object> mediaToolsView = new TreeMap<>();
    volatile String mediaInstall = "";

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
        if (!cfg.lanEnabled() && !cfg.relayEnabled() && !cfg.lanOffByUser) cfg.lanPort = LanServer.DEFAULT_PORT; // [fix-1123-a] 自己写了 lan_port=0 的不改回去
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
            if (showWindow && !askShow(up)) openWindow("http://127.0.0.1:" + up + "/");   // ★ fix-1118 SEC L2：页面要入口钥匙，交给正在运行的那个开窗口
            return;
        }
        // ★ exe-handoff-1118：直接 javaw -jar 启动、但本机装了加载同一个 jar 的 OneSay.exe → 交给它（窗口属于 OneSay.exe，不落到 Edge）
        if (ExeHandoff.tryHandoff(!showWindow)) return;
        autoSetup(cfg);
        cfg.tls(); // 协议 v2：第一次运行生成本机证书
        App app = new App(cfg);
        app.start();
        if (showWindow) openWindow(app.ui.url());
        Thread.currentThread().join();
    }

    /** ★ fix-1118 SEC L2：叫正在运行的 OneSay 自己开窗口。 */
    static boolean askShow(int port) {
        try {
            HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(600)).build()
                    .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/show")).timeout(Duration.ofSeconds(2))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
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

    /** [overnight-1119] 手机 ⇄ 电脑互传文件。 */
    final FileXfer xfer = new FileXfer();

    /**
     * [overnight-1119] 双端连通：手机交任务给电脑（pc_task / pc_task_status，结果推回手机）、手机发文件到电脑（file_put，落在工作区「来自手机」），
     * 电脑发文件到手机（界面按钮 / 对话工具，分块 file_put）。
     */
    void setupLinks1119() {
        chat.peerExec = from -> (cmd, args) -> agent.runAsPeer(from, cmd, args);
        chat.xfer = xfer;
        agent.local.put("pc_task", a -> chat.startPhoneTask(str(a, "goal", ""), str(a, "_from", "")));
        agent.local.put("pc_task_status", a -> chat.phoneTaskStatus(str(a, "_from", "")));
        agent.local.put("file_put", a -> {
            Map<String, Object> r = xfer.receive(cfg.workspace().resolve(FileXfer.DIR_FROM_PHONE), a, str(a, "_from", ""));
            return new Actions.Result(Boolean.TRUE.equals(r.get("ok")), String.valueOf(r.get("text")), r);
        });
        xfer.onDone = st -> notify.post("task", "收到手机发来的文件", st.name + "（" + FileXfer.human(st.size) + "）→ " + st.path, st.peer, Outcomes.DONE); // [preview-1120] 带状态
        chat.onPhoneTaskEnd = (from, card) -> {
            if (card == null || from == null || from.isEmpty()) return;
            Map<String, Object> n = new TreeMap<>();
            n.put("title", "电脑任务" + Outcomes.zh(String.valueOf(card.getOrDefault("state", ""))));
            n.put("text", Chat.phoneTaskLine(card));
            try {
                Actions.Result r = agent.callPeer(from, "notify", n, 4000, 8000).get(14, java.util.concurrent.TimeUnit.SECONDS);
                log.accept("手机任务结果" + (r.ok ? "已推回手机 " : "没推到手机（手机可以自己查）：" + r.text + " ") + from);
            } catch (Exception e) {
                log.accept("手机任务结果没推到手机（手机可以自己查）：" + from);
            }
        };
    }

    /** [overnight-1119] 托盘图标（有确认卡等着时只改它的提示文字，不弹通知）。 */
    volatile TrayIcon trayIcon;

    void trayHint(String text) {
        TrayIcon t = trayIcon;
        if (t == null) return;
        String want = text == null ? Flavor.name() + " " + cfg.deviceId : text;
        try { if (!want.equals(t.getToolTip())) t.setToolTip(want); } catch (Exception ignored) { }
    }

    /** 在电脑上弹系统选文件框（发到手机用）。 */
    java.io.File pickFile() {
        if (java.awt.GraphicsEnvironment.isHeadless()) return null;
        java.io.File[] out = new java.io.File[1];
        try {
            java.awt.EventQueue.invokeAndWait(() -> {
                java.awt.FileDialog d = new java.awt.FileDialog((java.awt.Frame) null, "选一个文件发到手机", java.awt.FileDialog.LOAD);
                d.setAlwaysOnTop(true);
                d.setVisible(true);
                if (d.getFile() != null) out[0] = new java.io.File(d.getDirectory(), d.getFile());
                d.dispose();
            });
        } catch (Exception e) {
            return null;
        }
        return out[0];
    }

    /** [preview-1120] MCP 连接器（配置目录 mcp.json 记住选过的目录）。 */
    McpHub mcp;

    // ---------- [always-on-1120] 常驻分工：定时 / 提醒 / 查网页可声明执行位置；电脑离线时需电脑的任务排队，上线后补跑或过期；网络唤醒 ----------
    AlwaysOnService aon;
    volatile boolean relayUp;
    volatile Map<String, Object> wolCheck;
    volatile long wolCheckedAt;
    volatile List<Map<String, Object>> nicCache;
    volatile long nicAt;

    void setupAlwaysOn() {
        aon = new AlwaysOnService(Config.defaultDir().resolve("always-on.json"), System::currentTimeMillis, log);
        aon.load();
        aonCfg();
        aon.notifier = (t, x, from) -> notify.post("reminder", t, x, from);
        aon.runner = this::aonRun;
        RelayClient rc = agent.relay();
        if (rc != null) aon.port = new AlwaysOnService.Port() {
            @Override public boolean up() { return rc.isConnected(); }
            @Override public void send(Map<String, Object> f) { rc.send(Json.stringify(f)); }
        };
        agent.nodeHook = aon::onFrame;
        agent.onLinkState = (l, c) -> {
            if (!(l instanceof RelayClient)) return;
            boolean was = relayUp;
            relayUp = c;
            if (c && !was) aon.onNodeUp(); // 中转连上：整份同步交给节点的任务和唤醒设置
        };
        aon.start(); // 先补一轮：关机期间错过的按策略补跑或过期
    }

    void aonCfg() {
        aon.pcId = cfg.deviceId;
        aon.pairKey = cfg.pairKey;
        aon.nodeConfigured = cfg.relayEnabled();
        aon.wolEnabled = cfg.wolEnabled;
        aon.wolAuto = cfg.wolAuto;
        aon.wolMac = cfg.wolMac;
        aon.wolBcast = cfg.wolBcast;
    }

    /** 到点真正执行：提醒 → 电脑通知；快捷指令 → 和对话同一条审批链（需要批准时弹在窗口里，没人点按没批准处理）。 */
    Object[] aonRun(AlwaysOn.Job j, long due, boolean late) {
        String from = j.where == AlwaysOn.Where.NODE ? "常驻节点" : "本机";
        switch (j.action) {
            case NOTIFY:
                notify.post("reminder", (late ? "补提醒：" : "") + j.title, (j.text.isEmpty() ? "到点了" : j.text) + (late ? "\n（电脑离线时到点：" + AlwaysOnService.hm(due) + "）" : ""), from);
                return new Object[] {AlwaysOn.St.DONE, "已提醒"};
            case SHORTCUT: {
                Map<String, Object> a = new TreeMap<>();
                a.put("name", j.target);
                Actions.Result r = agent.runLocal("shortcut_run", a);
                if (r != null && r.ok) return new Object[] {AlwaysOn.St.DONE, "快捷指令「" + j.target + "」已运行"};
                String why = r == null ? "" : String.valueOf(r.data == null ? "" : r.data.getOrDefault("reason", ""));
                String text = r == null ? "没有结果" : r.text;
                return new Object[] {why.contains("confirm") || why.contains("approval") ? AlwaysOn.St.NEEDS_USER : AlwaysOn.St.FAILED, text};
            }
            default:
                return new Object[] {AlwaysOn.St.FAILED, "查网页只在常驻节点上做"};
        }
    }

    List<Map<String, Object>> nics() {
        long now = System.currentTimeMillis();
        if (nicCache == null || now - nicAt > 60_000) { nicCache = WakeOnLan.nics(); nicAt = now; }
        return nicCache;
    }

    Map<String, Object> aonView() {
        Map<String, Object> v = aon == null ? new TreeMap<>() : aon.view();
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("enabled", cfg.wolEnabled);
        w.put("auto", cfg.wolAuto);
        w.put("mac", cfg.wolMac);
        w.put("bcast", cfg.wolBcast);
        w.put("nics", new ArrayList<Object>(nics()));
        w.put("check", wolCheck == null ? Map.of() : wolCheck);
        w.put("checked_at", wolCheckedAt);
        w.put("windows", System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
        v.put("wol", w);
        return v;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> aonApi(Map<String, Object> b) {
        String op = str(b, "op", "");
        switch (op) {
            case "add": {
                if (!(b.get("job") instanceof Map)) return fail("缺任务");
                Map<String, Object> jm = new TreeMap<>((Map<String, Object>) b.get("job"));
                jm.remove("id");
                jm.remove("seen");
                AlwaysOn.Job j = AlwaysOn.Job.from(jm);
                if (AlwaysOn.Action.of(jm.get("action")) == null) return fail("选做什么");
                String bad = aon.add(j);
                return bad == null ? ok("已添加：" + j.title + "（" + j.where.zh + "）") : fail(bad);
            }
            case "del": { String bad = aon.remove(str(b, "id", "")); return bad == null ? ok("已删除") : fail(bad); }
            case "toggle": { String bad = aon.toggle(str(b, "id", ""), "true".equals(str(b, "on", ""))); return bad == null ? ok("已" + ("true".equals(str(b, "on", "")) ? "打开" : "暂停")) : fail(bad); }
            case "clear": aon.clearItems(); return ok("记录已清空");
            case "wol_check": {
                boolean win = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
                wolCheck = WakeOnLan.verdict(win ? WakeOnLan.runWindowsCheck() : null, win);
                wolCheckedAt = System.currentTimeMillis();
                nicAt = 0;
                Map<String, Object> m = ok(String.valueOf(wolCheck.get("summary")));
                m.put("check", wolCheck);
                return m;
            }
            case "wol_use": {
                String mac = WakeOnLan.normMac(str(b, "mac", ""));
                if (mac.isEmpty()) return fail("MAC 不对");
                String bc = str(b, "bcast", "");
                cfg.wolMac = mac;
                cfg.wolBcast = WakeOnLan.ipv4(bc) ? bc : "";
                try { cfg.save(); } catch (IOException e) { return fail("保存失败"); }
                aonCfg();
                aon.wakeChanged();
                return ok("唤醒用这块网卡：" + mac);
            }
            default: return fail("不认识的操作");
        }
    }

    /** [preview-1120] 当前外观：mode（system / light / dark）、主题包（找不到就当没选）。 */
    Map<String, Object> appearance() {
        Map<String, Object> a = new TreeMap<>();
        a.put("mode", Themes.mode(cfg.theme));
        a.put("pack", "");
        if (!cfg.themePack.isEmpty()) {
            for (Object o : Themes.list(Config.defaultDir().resolve("themes"))) {
                @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) o;
                if (cfg.themePack.equals(p.get("id"))) { a.put("pack", p.get("id")); a.put("pack_name", p.get("name")); a.put("pack_mode", p.get("mode")); a.put("tokens", p.get("tokens")); }
            }
        }
        return a;
    }

    /**
     * [preview-1120] 系统选择框（在 OneSay 进程里弹，置顶）。kind：folder / exe / json / file / folder_or_json。没界面（测试）返回 null。
     * Swing 的 JFileChooser 能选文件夹（AWT 的 FileDialog 在 Windows 上选不了）；用系统外观。
     */
    java.io.File pick(String kind, String title, String start) {
        if (java.awt.GraphicsEnvironment.isHeadless()) return null;
        java.io.File[] out = new java.io.File[1];
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                try { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
                javax.swing.JFileChooser ch = new javax.swing.JFileChooser(start == null || start.isBlank() ? null : new java.io.File(start));
                ch.setDialogTitle(title == null || title.isBlank() ? (kind.startsWith("folder") ? "选文件夹" : "选文件") : title);
                switch (kind) {
                    case "folder": ch.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY); break;
                    case "folder_or_json":
                        ch.setFileSelectionMode(javax.swing.JFileChooser.FILES_AND_DIRECTORIES);
                        ch.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("文件夹或 MCP 配置（.json）", "json"));
                        break;
                    case "exe": ch.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("程序（.exe）", "exe")); break;
                    case "json": ch.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("主题包（.json）", "json")); break;
                    default: break;
                }
                javax.swing.JFrame owner = new javax.swing.JFrame();
                owner.setUndecorated(true);
                owner.setAlwaysOnTop(true);
                owner.setLocationRelativeTo(null);
                owner.setVisible(true);
                try {
                    if (ch.showDialog(owner, "选择") == javax.swing.JFileChooser.APPROVE_OPTION) out[0] = ch.getSelectedFile();
                } finally {
                    owner.dispose();
                }
            });
        } catch (Exception e) {
            return null;
        }
        return out[0];
    }

    /** [rel-1123 合并] 免扫码配对码存在哪（Windows：凭据管理器；别的系统 / 测试：内存）。 */
    final SecretStore pairSecrets = SecretStore.system();
    static final String PAIR_CODE_NAME = SecretStore.name("pair", "nearby-code");

    void setupPairCode() {
        LanServer ls = agent.lan();
        if (ls == null) return;
        ls.onPaired = (name, id) -> notify.post("app", "新手机配对好了：" + name, "手机「" + name + "」用你设的配对码连上了这台电脑。不是你配的就去「设置 › 连接」改配对码、在手机列表里删掉它。", id);
        if (!cfg.pairCodeSet) return;
        Thread t = new Thread(() -> { // 读凭据库要起一次 PowerShell，别卡启动
            LanPair.Verifier v = LanPair.Verifier.parse(pairSecrets.get(PAIR_CODE_NAME));
            if (v != null) { ls.pairCode = v; return; }
            cfg.pairCodeSet = false;
            boolean was = cfg.pairInvite;
            cfg.pairInvite = false;
            try { cfg.save(); } catch (IOException ignored) { }
            if (was) log.accept("免扫码配对的配对码读不回来了（凭据库里没有），先关上「允许新手机配对」：请在「设置 › 连接」重新设一个");
        }, "onesay-pair-code");
        t.setDaemon(true);
        t.start();
    }

    /** 免扫码配对的状态（界面 / 配对接口回给界面）。 */
    Map<String, Object> pairView() {
        LanServer ls = agent.lan();
        Map<String, Object> m = new TreeMap<>();
        boolean code = ls != null && ls.pairCode != null;
        m.put("invite", cfg.pairInvite && code);
        m.put("code_set", code);
        m.put("lan", ls != null);
        m.put("name", ls == null ? LanPair.hostName() : ls.pcName);
        long now = System.currentTimeMillis();
        if (ls != null && ls.pairLock.locked(now)) m.put("locked_until", ls.pairLock.until());
        if (ls != null) m.put("left", (long) ls.pairLock.left());
        Map<String, Object> last = ls == null ? null : ls.pairLast;
        if (last != null && now - ((Number) last.get("at")).longValue() < 15 * 60_000L) m.put("last", last);
        return m;
    }

    void start() throws Exception {
        actions = "1".equals(System.getenv("ONESAY_TEST_ACTIONS")) ? new Main.TestActions() : new DesktopActions(cfg);
        notify = new Notify(store, log);
        chat = new Chat(cfg, store, actions, this::shortcuts, log);
        agent = new Agent(cfg, actions, log);
        setupPairCode(); // [rel-1123 合并] 免扫码配对：读回配对码（凭据库）、配好了发通知
        agent.local.put("notify", a -> {
            notify.post(str(a, "kind", "app"), str(a, "title", ""), str(a, "text", ""), str(a, "_from", ""));
            return Actions.Result.ok("已在电脑上提醒");
        });
        agent.local.put("memory_sync", a -> {
            Object at = a.get("updated_at");
            Map<String, Object> m = store.mergeMemory(a.get("text") == null ? null : String.valueOf(a.get("text")), at instanceof Number ? ((Number) at).longValue() : 0L);
            return Actions.Result.ok("记忆已同步", m);
        });
        // [preview-1120] MCP 连接器：读记住的目录，后台启动（不挡启动）；工具交给对话，调用走审批
        mcp = new McpHub(Config.defaultDir().resolve("mcp.json"), log);
        mcp.load();
        PcGuard.MCP = mcp;
        setupWorkflow(); // [workflow-1120] 工作流 / 技能 / 万能 API 连接器 / OAuth
        ChatAgent.extraTools = () -> { List<Map<String, Object>> l = new ArrayList<>(mcp.toolDefs()); l.addAll(WfApi.toolDefs()); return l; };
        ChatAgent.mcpName = mcp::describe;
        Runtime.getRuntime().addShutdownHook(new Thread(mcp::shutdown, "onesay-mcp-stop")); // 退出时把 stdio 服务器子进程一起结束
        new Thread(mcp::startAll, "onesay-mcp-start") {{ setDaemon(true); }}.start();
        setupMedia();
        setupMirror();
        setupWhitelist();
        // [overnight-1119] 审批不进通知（列表和 Windows 通知中心都不进，也不弹托盘气泡）：卡片在对话的任务卡 / 窗口里点；
        // 有卡等着时只改托盘图标的提示文字，点击次数记进「设置 › 权限审批记录」
        agent.confirms.onNew = d -> trayHint(d != null && d.startsWith(ChatAgent.FROM) ? "OneSay · 有一步等你批准（回到窗口里点）" : "OneSay · 手机请求等你确认（回到窗口里点）");
        agent.guard.stats = (type, field) -> store.bumpPerm(type, field);
        int purged = store.purgeApprovalNotes();
        if (purged > 0) log.accept("通知列表里清掉了 " + purged + " 条旧版本留下的审批记录（审批不进通知）");
        agent.guard.notify = t -> { log.accept("权限：" + t); agent.guard.logPerm(t, "提醒", ""); };
        setupAlwaysOn(); // [always-on-1120] 要在通道启动前挂好：中转一连上就要同步任务、收排队
        chat.reminders = new ChatReminders(aon, d -> agent.guard.confirmLow(ChatAgent.FROM, "reminder", d), System::currentTimeMillis, null); // [fix-1120 晚] 对话里建提醒：存在常驻分工里，默认电脑本地
        // [fix-1123-a] 对话里一句话改 OneSay 设置 / 跳页：改设置走同一条审批链（confirmLow），真正改用设置页同一个接口；跳页由界面轮询 state.ui_nav 时执行
        chat.appControl = new AppControl(d -> agent.guard.confirmLow(ChatAgent.FROM, "app_setting", d), this::applySetting, this::settingsView,
                () -> Themes.list(Config.defaultDir().resolve("themes")), this::navTo);
        agent.start();
        chat.phone = (cmd, args) -> callPhone(cmd, args);
        setupLinks1119();
        chat.phones = agent::peers;             // [phone-tools-1118] 对话的运行环境里写连着哪些手机
        chat.lastPhone = () -> cfg.phoneLast;
        // [rel-1123-b] 离线写原因；交给手机的目标等手机回传结果（没人在等时结果回来发一条电脑通知）
        chat.phoneWhy = () -> { OfflineWhy.Why w = agent.offlineWhy(); return w == null ? "" : w.text; };
        chat.phoneGoal = (gid, goal) -> { String to = agent.phoneTarget(""); agent.goalStarted(to == null ? "" : to, gid, goal); return agent.goalFuture(gid); };
        chat.phoneGoalUnwait = agent::goalUnwait;
        agent.onGoalDone = (g, waited) -> {
            if (waited) return;
            notify.post("task", "手机任务" + Humanize.goalState(g.state) + "：" + Notify.clip(g.goal, 40), g.text, g.device, g.ok() ? Outcomes.DONE : Outcomes.FAILED);
        };
        chat.onTaskEnd = (st, t) -> { String[] p2 = t.split("\n", 2); notify.post("task", "任务" + Outcomes.titleZh(st) + "：" + p2[0], p2.length > 1 ? p2[1] : "", "", st); }; // [preview-1120] 带上状态，标签不再一律「任务完成」 // [overnight-1119]
        chat.onTaskStart = agent.guard::taskStarted; // [approvals-1118] 新任务开始：上一个任务的「允许本次任务」不带过来
        chat.pcExec = agent::runLocal;
        chat.appResolver = n -> LocalLaunch.displayName(n, cfg.appMap(), System.getenv()); // [approvals-1118] 打开前先找本地应用 // [chat-agent-1118] 「对话」能执行任务：同一条安全链（插件 → 矩阵 → 电脑上确认 → 凭证）
        chat.workspace = cfg.workspace().toString();
        asr = new AsrRoute(cfg);
        asr.warmUp(); // 启动时先看云端能不能用（后台，不阻塞）
        voice = new Voice(cfg, actions, log, asr);
        voice.fallback = text -> String.valueOf(chat.send(text, "voice").get("text"));
        voice.approvalPending = () -> !agent.confirms.list().isEmpty();
        voice.start();
        ui = new UiServer("1".equals(System.getenv("ONESAY_TEST_ACTIONS")) ? System.getenv("ONESAY_TEST_LAUNCH_KEY") : null);   // ★ fix-1118 SEC L2：测试才可预设入口钥匙
        routes();
        if (!ui.start(uiPort()) && !ui.start(0)) throw new IOException("界面端口打不开");
        ui.onShow = () -> openWindow(ui.url());
        log.accept("界面 " + ui.baseUrl());   // ★ fix-1118 SEC L2：入口钥匙不进日志
        Host.ready(ui.url()); // OneSay.exe 托管时：记下地址，再次双击 OneSay.exe 就叫出窗口
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { voice.stop(); agent.stop(); ui.stop(); media.shutdown(); mirror.shutdown(); }));
        if (!GraphicsEnvironment.isHeadless() && SystemTray.isSupported()) tray();
        new Thread(App::ensureShortcuts, "onesay-shortcuts").start();
    }

    // ---------- [workflow-1120] 工作流 / 技能 / 万能 API 连接器 ----------
    WfApi wf;

    /** 数据放预览版 / 正式版各自的数据目录；key 进 Windows 凭据管理器；审批接现有确认卡（只有允许 / 拒绝，不能记住）。 */
    void setupWorkflow() {
        wf = new WfApi(Config.defaultDir(), SecretStore.system(), log);
        PcGuard.WF = wf;
        wf.llm = (sys, prompt, temp, conn, tmo) -> {
            Llm l = Llm.from(cfg);
            if (!l.ready()) throw new IllegalStateException("没配置大模型（设置 › 模型与密钥）");
            List<Map<String, Object>> msgs = new ArrayList<>();
            if (sys != null && !sys.isBlank()) msgs.add(Llm.msg("system", sys));
            msgs.add(Llm.msg("user", prompt));
            return l.chat(msgs, temp, tmo);
        };
        wf.mcp = new WfApi.McpPort() {
            @Override public List<Map<String, Object>> toolDefs() { return mcp.toolDefs(); }
            @Override public boolean readOnly(String fn) { return mcp.readOnlyFn(fn); }
            @Override public String describe(String fn) { return mcp.describe(fn); }
            @Override public Actions.Result call(String fn, Map<String, Object> args) { return mcp.call(fn, args); }
        };
        wf.approver((title, detail, risk) -> askPlain("workflow", detail));
        wf.skillApprover = (title, detail) -> askPlain("skill_install", detail);
        wf.oauth.opener = url -> { try { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url)); } catch (Exception ignored) { } };
    }

    /** 弹一张只有「允许 / 拒绝」的确认卡（4 参数 ask：不给「记住」「允许本次任务」）。 */
    boolean askPlain(String cmd, String detail) {
        PcGuard.Confirmer c = agent.guard.confirmer;
        if (c == null) return false;
        try { return c.ask("工作流", cmd, detail, 60_000) == PcGuard.ALLOW; } catch (Throwable t) { return false; }
    }

    // ---------- [app-whitelist-1116] 应用白名单 ----------
    WhitelistStore whitelist;
    /** 上次同步过的常连手机（新连上的手机先同步一次）。 */
    final java.util.Set<String> wlSynced = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 白名单：存在配置目录；判定接进 PcGuard；手机发来的 whitelist_sync 在这里合并；手机常连上来 / 本机改了就推一次。 */
    void setupWhitelist() {
        Path dir = cfg.file.getParent() == null ? Config.defaultDir() : cfg.file.getParent();
        whitelist = new WhitelistStore(dir);
        whitelist.device = cfg.deviceId;
        agent.guard.whitelist = whitelist;
        agent.local.put("whitelist_sync", a -> whitelistSyncIn(whitelist, a, null, log)); // [overnight-1119] 「要你确认」类不进通知
        whitelist.onChange = () -> new Thread(this::whitelistPush, "onesay-wl-push").start();
        Thread t = new Thread(() -> {
            while (true) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                try {
                    java.util.Set<String> now = new java.util.HashSet<>();
                    for (Link l : agent.links()) if (l instanceof LanServer) for (String c : ((LanServer) l).clients()) if (((LanServer) l).held(c)) now.add(c);
                    wlSynced.retainAll(now);
                    for (String c : now) if (wlSynced.add(c)) whitelistPush(c);
                } catch (RuntimeException ignored) { }
            }
        }, "onesay-wl-sync");
        t.setDaemon(true);
        t.start();
    }

    /** 手机发来整份白名单：合并、提醒对方新加的、回本机整份（对方再合并，两边就一样了）。 */
    static Actions.Result whitelistSyncIn(WhitelistStore w, Map<String, Object> a, Consumer<String> tell, Consumer<String> log) {
        String doc = str(a, "doc", "");
        String from = str(a, "_from", "");
        try {
            List<AppWhitelist.Entry> added = w.merge(doc, from);
            if (!added.isEmpty()) {
                StringBuilder b = new StringBuilder();
                for (AppWhitelist.Entry e : added) b.append(b.length() == 0 ? "" : "、").append(e.name.isEmpty() ? e.id : e.name);
                String t = "手机加了 " + added.size() + " 个白名单应用：" + b + "。要在电脑上确认一次才生效（设置 › 应用白名单 › 确认生效，也能删）";
                log.accept(t);
                if (tell != null) tell.accept(t);
            }
            Map<String, Object> d = new TreeMap<>();
            d.put("doc", w.json());
            return Actions.Result.ok("白名单已同步", d);
        } catch (IllegalStateException e) {
            return Actions.Result.fail("电脑上的白名单文件读坏了，先在电脑设置里清空重建");
        } catch (IllegalArgumentException e) {
            return Actions.Result.fail("白名单格式不对（" + e.getMessage() + "）");
        }
    }

    void whitelistPush() {
        String d = agent.phoneTarget("");
        if (d != null) whitelistPush(d);
    }

    /** 推给常连着的手机；手机回它的整份，合并进来。手机不在线就不推（下次连上时 setupWhitelist 的线程会推）。 */
    void whitelistPush(String device) {
        if (whitelist == null || whitelist.broken()) return;
        try {
            Map<String, Object> args = new TreeMap<>();
            args.put("doc", whitelist.json());
            Actions.Result r = agent.callPeer(device, "whitelist_sync", args, 0, 15000).get(20, java.util.concurrent.TimeUnit.SECONDS);
            if (r.ok && r.data != null && r.data.get("doc") != null) {
                List<AppWhitelist.Entry> added = whitelist.merge(String.valueOf(r.data.get("doc")), device);
                if (!added.isEmpty()) {
                    String t = "手机上有 " + added.size() + " 个白名单应用同步过来了，要在电脑上确认一次才生效（设置 › 应用白名单 › 确认生效，也能删）";
                    log.accept(t);
                }
                log.accept("白名单已和手机 " + device + " 同步");
            } else if (!r.ok) {
                wlSynced.remove(device);
                log.accept("白名单没同步到手机：" + r.text);
            }
        } catch (Exception e) {
            wlSynced.remove(device);
        }
    }

    /** 界面「应用白名单」：看 / 加（界面先弹过风险确认）/ 删 / 读坏了清空重建 / 最近审计。只能在电脑界面上用，远程改不了。 */
    static Map<String, Object> whitelistApi(WhitelistStore w, Map<String, Object> b, Consumer<String> log) {
        String op = str(b, "op", "list");
        String platform = str(b, "platform", AppWhitelist.WINDOWS), id = str(b, "id", "");
        Map<String, Object> m;
        switch (op) {
            case "list": m = ok(""); break;
            case "risk": {
                String why = AppWhitelist.refuseReason(platform, id);
                if (why != null) return fail(why);
                m = ok(AppWhitelist.riskText(platform, id, str(b, "name", "")));
                m.put("norm", AppWhitelist.normId(platform, id));
                break;
            }
            case "add": {
                if (!Boolean.TRUE.equals(b.get("confirmed"))) return fail("要先在确认框里看过风险说明并点「确定」");
                String why = w.add(platform, id, str(b, "name", ""));
                if (why != null) return fail(why);
                log.accept("应用白名单加了 " + platform + ":" + AppWhitelist.normId(platform, id));
                m = ok("已加进白名单，会同步到手机");
                break;
            }
            case "confirm": { // [app-whitelist-1116 规则调整] 手机同步来的，在电脑上确认一次才生效（界面先弹风险说明）
                if (!Boolean.TRUE.equals(b.get("confirmed"))) return fail("要先在确认框里看过风险说明并点「确认生效」");
                String why = w.confirm(platform, id);
                if (why != null) return fail(why);
                log.accept("应用白名单确认生效 " + platform + ":" + AppWhitelist.normId(platform, id));
                m = ok("已生效");
                break;
            }
            case "remove":
                if (!w.remove(platform, id)) return fail("白名单里没有这个");
                log.accept("应用白名单删了 " + platform + ":" + AppWhitelist.normId(platform, id));
                m = ok("已删除，会同步到手机");
                break;
            case "reset":
                w.reset();
                m = ok("已清空重建");
                break;
            default:
                return fail("不认识的操作");
        }
        m.put("whitelist", w.view());
        m.put("broken", w.broken());
        m.put("audit", w.recentAudit(30));
        return m;
    }

    // ---------- phone-mirror M1：手机投屏（官方 scrcpy；只响应电脑界面上的点击 / 拖拽，不给 agent 用） ----------
    PhoneMirror mirror;

    void setupMirror() {
        MirrorTools mt = MirrorTools.forThisPc();
        mirror = new PhoneMirror(mt, mt.dir().resolveSibling("phone-drop"), log);
        mirror.uhid = () -> "uhid".equals(cfg.mirrorKeyboard);
        mirror.onEvent = e -> {
            if ("needs_auth".equals(e.kind)) notify.post("pc", "手机投屏", "在手机上点「允许 USB 调试」，勾上「始终允许这台电脑」", "");
            else if ("authorized".equals(e.kind)) notify.post("pc", "手机投屏", e.zh + "，在 OneSay「手机投屏」里点「投屏」", "");
        };
        mirror.start();
    }

    Map<String, Object> mirrorApi(Map<String, Object> b) {
        String op = str(b, "op", "state"), serial = str(b, "serial", "");
        switch (op) {
            case "install": { String e = mirror.installTools(); return e == null ? ok("开始安装") : fail(e); }
            case "mirror": { String e = mirror.mirror(serial); return e == null ? ok("正在打开投屏窗口…") : fail(e); }
            case "open_app": { String e = mirror.openApp(serial, str(b, "package", "")); return e == null ? ok("正在打开 App 窗口…") : fail(e); }
            case "close": return mirror.close(serial, str(b, "package", "")) ? ok("已关掉") : fail("没有开着的窗口");
            case "apps": {
                try {
                    Map<String, Object> m = ok("");
                    m.put("apps", new ArrayList<Object>(mirror.apps(serial)));
                    return m;
                } catch (Exception e) {
                    return fail(MediaTools.msg(e));
                }
            }
            case "push_instead": return mirror.pushInstead(str(b, "id", ""));
            case "state": { Map<String, Object> m = ok(""); m.put("mirror", mirror.view()); return m; }
            default: return fail("不认识的操作");
        }
    }

    // ---------- 链接下载 ----------
    /** 链接下载：工具在 %LOCALAPPDATA%\OneSay\tools，Cookie 在配置目录 cookies\，文件下到 %USERPROFILE%\OneSay\Downloads。手机指令走 PcGuard 后到这里。 */
    void setupMedia() {
        Path dir = cfg.file.getParent() == null ? Config.defaultDir() : cfg.file.getParent();
        media = new Media(MediaTools.forThisPc(), new MediaCookies(dir.resolve("cookies")), Media.defaultOutDir(cfg.mediaDir), log);
        media.onFinish = t -> {
            if (t.state == Media.State.DONE) notify.post("task", "下载完成", (t.title.isEmpty() ? t.url : t.title) + " → " + t.file, t.from, Outcomes.DONE);
            else if (t.state == Media.State.FAILED) notify.post("task", "下载失败", (t.title.isEmpty() ? t.url : t.title) + "：" + t.error, t.from, Outcomes.FAILED); // [preview-1120] 以前标签也是「任务完成」
        };
        agent.guard.mediaToolsMissing = media.tools::missing;
        agent.local.put("media_get", media::remoteGet);
        agent.local.put("media_status", media::remoteStatus);
        agent.local.put("media_fetch", media::remoteFetch);
        agent.local.put("media_cancel", media::remoteCancel);
        new Thread(() -> mediaToolsView = media.tools.view(), "onesay-media-tools").start();
    }

    /** 界面「下载」区块的接口（本机口令保护）。 */
    Map<String, Object> mediaApi(Map<String, Object> b) {
        return mediaApi(media, b, log, this);
    }

    static Map<String, Object> mediaApi(Media media, Map<String, Object> b, Consumer<String> log, App app) {
        String op = str(b, "op", "state");
        switch (op) {
            case "tools": {
                Map<String, Object> m = ok("");
                Map<String, Object> v = media.tools.view();
                if (app != null) app.mediaToolsView = v;
                m.put("tools", v);
                return m;
            }
            case "install": {
                if (app == null) return fail("没有界面");
                if (!app.mediaInstall.isEmpty() && !app.mediaInstall.startsWith("已") && !app.mediaInstall.startsWith("失败")) return fail("正在安装");
                boolean force = Boolean.TRUE.equals(b.get("force"));
                app.mediaInstall = "正在下载…";
                new Thread(() -> {
                    try {
                        List<String> done = media.tools.install(force, (what, d, t) -> app.mediaInstall = "正在下载 " + what + " " + MediaArgs.mb(d) + (t > 0 ? " / " + MediaArgs.mb(t) : ""));
                        app.mediaInstall = done.isEmpty() ? "已是装好的，不用重装" : "已安装 " + String.join("、", done) + "（已核对官方 SHA-256）";
                        log.accept("下载工具：" + app.mediaInstall);
                    } catch (Exception e) {
                        app.mediaInstall = "失败：" + MediaTools.msg(e);
                        log.accept("下载工具安装失败：" + MediaTools.msg(e));
                    }
                    app.mediaToolsView = media.tools.view();
                }, "onesay-media-install").start();
                return ok("开始安装");
            }
            case "check_update": {
                Map<String, Object> m = ok("");
                m.put("updates", media.tools.checkUpdates());
                return m;
            }
            case "preview": {
                String url = MediaArgs.tidy(str(b, "url", ""));
                if (url == null) return fail("没找到 http / https 链接");
                try {
                    Media.Info in = media.preview(url, str(b, "quality", "best"), str(b, "audio", ""));
                    Map<String, Object> m = ok(in.title);
                    m.put("url", url);
                    m.put("title", in.title);
                    m.put("duration", MediaArgs.duration(in.duration));
                    m.put("seconds", in.duration);
                    m.put("size", MediaArgs.mb(in.size));
                    m.put("bytes", in.size);
                    return m;
                } catch (RuntimeException e) {
                    Map<String, Object> f = fail(MediaTools.msg(e));
                    f.put("url", url);
                    return f;
                }
            }
            case "download": {
                String url = MediaArgs.tidy(str(b, "url", ""));
                if (url == null) return fail("没找到 http / https 链接");
                if (media.tools.missing()) return fail("先在上面安装下载工具");
                Media.Info in = null;
                if (b.get("title") != null) {
                    in = new Media.Info();
                    in.title = str(b, "title", "");
                    in.duration = b.get("seconds") instanceof Number ? ((Number) b.get("seconds")).doubleValue() : 0;
                    in.size = b.get("bytes") instanceof Number ? ((Number) b.get("bytes")).longValue() : 0;
                }
                try {
                    Media.Task t = media.enqueue(url, b.get("quality"), b.get("audio"), "", false, in);
                    Map<String, Object> m = ok("已加入下载队列（#" + t.id + "）");
                    m.put("task", media.view(t));
                    return m;
                } catch (IllegalArgumentException e) {
                    return fail(e.getMessage());
                }
            }
            case "cancel":
                return media.cancel(Media.intOf(b.get("id"))) ? ok("已取消") : fail("没有这个进行中的任务");
            case "cookie_import": {
                String err = media.cookies.save(str(b, "site", ""), str(b, "text", ""));
                if (err != null) return fail(err);
                log.accept("导入了 " + MediaCookies.site(str(b, "site", "")) + " 的 Cookie（内容不记日志）");
                Map<String, Object> m = ok("已导入，只有这个账户能读");
                m.put("cookies", media.cookies.list());
                return m;
            }
            case "cookie_delete": {
                if (!media.cookies.delete(str(b, "site", ""))) return fail("没有这个站点的 Cookie");
                log.accept("删除了 " + MediaCookies.site(str(b, "site", "")) + " 的 Cookie");
                Map<String, Object> m = ok("已删除");
                m.put("cookies", media.cookies.list());
                return m;
            }
            case "open_dir":
            case "reveal": {
                Path target = media.outDir();
                if (op.equals("reveal")) {
                    Media.Task t = media.task(Media.intOf(b.get("id")));
                    if (t == null || t.file.isEmpty()) return fail("还没有文件");
                    target = Paths.get(t.file);
                }
                try {
                    Files.createDirectories(media.outDir());
                    if (isWindows()) {
                        if (op.equals("reveal")) new ProcessBuilder("explorer.exe", "/select,", target.toString()).start();
                        else new ProcessBuilder("explorer.exe", target.toString()).start();
                    } else if (Desktop.isDesktopSupported()) Desktop.getDesktop().open((op.equals("reveal") ? target.getParent() : target).toFile());
                    else return fail("这台电脑打不开文件夹：" + target);
                    return ok("已打开");
                } catch (Exception e) {
                    return fail("打不开：" + target);
                }
            }
            case "state":
                break;
            default:
                return fail("不认识的操作");
        }
        Map<String, Object> m = ok("");
        m.put("tasks", media.views());
        m.put("cookies", media.cookies.list());
        m.put("dir", media.outDir().toString());
        return m;
    }

    /** 每次刷新状态带上的「下载」区块数据（不算 SHA-256）。 */
    Map<String, Object> mediaState() {
        Map<String, Object> m = new TreeMap<>();
        m.put("tools", mediaToolsView);
        m.put("install", mediaInstall);
        m.put("tasks", media.views());
        m.put("cookies", media.cookies.list());
        m.put("dir", media.outDir().toString());
        m.put("quality", MediaArgs.quality(cfg.mediaQuality));
        return m;
    }

    // ---------- 开始菜单与桌面快捷方式（用 exe 启动时） ----------
    /**
     * 开始菜单的每次都核对（exe 搬了位置会跟着改）；桌面的只建一次，用户删了不再建。
     * OneSay.exe 托管时由启动器写（带 AppUserModelID {@link Host#AUMID}，任务栏分组和固定图标对得上）；
     * 不是托管时退回 PowerShell（WScript.Shell 写不了 AppUserModelID）。
     */
    static void ensureShortcuts() {
        String exe = System.getProperty("onesay.exe", "");
        if (!isWindows() || exe.isEmpty() || !new File(exe).isFile()) return;
        // [preview-1120] 预览版不自己建快捷方式：开始菜单 / 桌面的 OneSay.lnk 是正式版的，覆盖了就指到预览版去了。
        // 预览版的开始菜单项由 install-preview.ps1 建（名字「OneSay Preview」），卸载脚本删。
        if (Flavor.PREVIEW) return;
        String appdata = System.getenv("APPDATA");
        if (appdata == null) return;
        Path start = Paths.get(appdata, "Microsoft", "Windows", "Start Menu", "Programs", "OneSay.lnk");
        Path marker = Config.defaultDir().resolve("desktop-shortcut.done");
        boolean desk = !Files.exists(marker);
        if (Host.present()) {
            boolean ok = Host.shortcut(start.toString(), exe);
            String d = desk ? Host.desktopDir() : null;
            if (ok && desk && d != null && Host.shortcut(Paths.get(d, "OneSay.lnk").toString(), exe)) {
                try { Files.createDirectories(marker.getParent()); Files.writeString(marker, "1"); } catch (Exception ignored) { }
            }
            if (ok) return;
        }
        StringBuilder ps = new StringBuilder("$w=New-Object -ComObject WScript.Shell;");
        ps.append("function mk($p){$s=$w.CreateShortcut($p);$s.TargetPath=").append(psq(exe))
          .append(";$s.WorkingDirectory=").append(psq(new File(exe).getParent()))
          .append(";$s.IconLocation=").append(psq(exe + ",0")).append(";$s.Description='OneSay';$s.Save()};");
        ps.append("mk ").append(psq(start.toString())).append(";");
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

    /** [fix-1123-a] 设置页的保存接口（对话改设置也走它，校验和副作用完全一样）。 */
    volatile java.util.function.Function<Map<String, Object>, Object> settingsFn;
    /** [fix-1123-a] 对话要界面跳到哪一页：{seq, page, anchor, at}；界面看到新的 seq 就跳。 */
    final Map<String, Object> uiNav = new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.concurrent.atomic.AtomicLong navSeq = new java.util.concurrent.atomic.AtomicLong();

    @SuppressWarnings("unchecked")
    Map<String, Object> applySetting(String key, String value) {
        java.util.function.Function<Map<String, Object>, Object> f = settingsFn;
        if (f == null) return fail("设置接口还没好");
        Map<String, Object> b = new TreeMap<>();
        b.put(key, value);
        Object r = f.apply(b);
        return r instanceof Map ? (Map<String, Object>) r : fail("没有结果");
    }

    /** [fix-1123-a] 剪贴板文字是一个 http(s) 网址（一行、没有空格、≤ 2000 字）才算。 */
    static String clipUrl(String t) {
        if (t == null) return "";
        String u = t.trim();
        return u.length() <= 2000 && u.matches("(?i)https?://[^\\s<>\"]+") ? u : "";
    }

    void navTo(String[] pa) {
        uiNav.put("page", pa[0]);
        uiNav.put("anchor", pa.length > 1 && pa[1] != null ? pa[1] : "");
        uiNav.put("at", System.currentTimeMillis());
        uiNav.put("seq", navSeq.incrementAndGet());
    }

    Map<String, Object> settingsView() {
        Map<String, Object> s = new TreeMap<>();
        s.put("device_id", cfg.deviceId);
        s.put("lan_port", (long) cfg.lanPort);
        s.put("lan_env_off", "off".equalsIgnoreCase(String.valueOf(System.getenv("ONESAY_LAN")))); // [preview-1120 真机] ONESAY_LAN=off 时设置页别再显示「开」
        s.put("relay", cfg.relayEnabled());
        s.put("relay_url", cfg.relayUrl);
        s.put("ptt", cfg.pttEnabled);
        s.put("ptt_skip", cfg.pttSkip);
        s.put("asr_mode", cfg.asrMode);
        s.put("asr_pref", AsrRoute.Pref.of(cfg.asrPref).id);
        s.put("asr_key_set", !cfg.asrKey.isEmpty());
        s.put("llm_provider", cfg.llmProvider);
        s.put("llm_key_set", !cfg.llmKey.isEmpty());
        s.put("vision_key_set", !cfg.visionKey.isEmpty()); // [devtools-1118] Key 本身不回界面
        s.put("vision_base", cfg.visionBase);
        s.put("vision_model", cfg.visionModel);
        DeviceTools.Vision vz = DeviceTools.Vision.from(cfg);
        s.put("vision_source", vz == null ? "" : vz.source + " · " + vz.model);
        s.put("adb_path", cfg.adbPath);
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
        s.put("chat_preset", ChatPolicy.Preset.of(cfg.chatPreset).id);
        s.put("chat_timeout_min", (long) cfg.chatTimeoutMin);
        s.put("media_quality", MediaArgs.quality(cfg.mediaQuality));
        s.put("mirror_keyboard", "uhid".equals(cfg.mirrorKeyboard) ? "uhid" : "sdk");
        s.put("theme", Themes.mode(cfg.theme));
        s.put("theme_pack", cfg.themePack);
        s.put("wol_enabled", cfg.wolEnabled);
        s.put("wol_auto", cfg.wolAuto);
        return s;
    }

    void routes() {
        ui.on("state", b -> {
            agent.confirms.uiAlive(); // 界面还活着：确认框显示后界面 8 秒没动静按拒绝
            Map<String, Object> s = store.snapshot();
            s.put("version", Flavor.version());
            s.put("flavor", Flavor.PREVIEW ? "preview" : "release");
            s.put("device", cfg.deviceId);
            s.put("status", agent.status);
            s.put("voice", voice.status);
            s.putAll(asr.view()); // 识别：设置（先看云端 / 仅云端 / 仅本机）+ 当前用的是哪个 + 原因
            s.put("peers", new TreeMap<String, Object>(agent.peers()));
            s.put("settings", settingsView());
            s.put("appearance", appearance()); // [preview-1120] 主题插件：当前模式 + 主题包的颜色
            Map<String, Object> mv = mcp.view(); // [preview-1120] MCP 连接器：每个服务器的状态、工具数、错误
            s.put("mcp", mv);
            s.put("caps", new ArrayList<Object>(actions.capabilities()));
            s.put("shortcuts", new ArrayList<Object>(shortcuts()));
            s.put("lan_ips", new ArrayList<Object>(LanClient.localAddresses()));
            String ph = agent.phoneTarget("");
            s.put("phone", ph == null ? "" : ph);
            OfflineWhy.Why why = agent.offlineWhy(); // [rel-1123-b] 没有手机在线时写原因（{code, text}）；有在线的不给
            if (why != null) s.put("phone_why", why.view());
            s.put("pair_invite", cfg.pairInvite && agent.lan() != null && agent.lan().pairCode != null); // [rel-1123-b] 免扫码配对开没开（[rel-1123 合并] 没设码 = 没开）
            s.put("pair", pairView()); // [rel-1123 合并] 设没设码、锁没锁、最近一次配对的进展、电脑名字
            Agent.Goal lg = agent.lastGoal();
            if (lg != null) s.put("phone_goal", lg.view()); // [rel-1123-b] 最近一次交给手机的目标和它回传的结果
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
            s.put("chat_running", chat.running());
            s.put("shortcut_policy", agent.guard.shortcutView());
            s.put("shortcut_approvals", agent.guard.approvalView());
            s.put("remembered", agent.guard.rememberedView());
            s.put("chat_rules", ChatPolicy.view(agent.guard.chatRules()));
            s.put("perm_log", agent.guard.permLogView());
            s.put("perm_stats", PermStats.view(store.permStats()));
            s.put("xfers", xfer.view()); // [overnight-1119] 文件互传进度
            Map<String, Object> lv = chat.live;
            if (lv != null) s.put("progress", lv); // [overnight-1119] 任务卡上的下载 / 传输进度条
            s.put("xfer_dir", cfg.workspace().resolve(FileXfer.DIR_FROM_PHONE).toString()); // [overnight-1119] 权限审批记录：按类型 允许 x 次 / 拒绝 x 次
            s.put("grants", agent.guard.grantView());
            s.put("chat_preset", ChatPolicy.Preset.of(cfg.chatPreset).id);
            s.put("choices", chat.choices.list());
            s.put("habits", OpenTarget.parseHabits(cfg.openHabits));
            if (whitelist != null) { s.put("whitelist", whitelist.view()); s.put("whitelist_broken", whitelist.broken()); }
            s.put("workspace", cfg.workspace().toString());
            s.put("media", mediaState());
            s.put("mirror", mirror.view());
            s.put("aon", aonView()); // [always-on-1120] 常驻与唤醒
            if (!uiNav.isEmpty()) s.put("ui_nav", new TreeMap<String, Object>(uiNav)); // [fix-1123-a] 对话要求跳页
            synchronized (recent) { s.put("log", new ArrayList<Object>(recent)); }
            return s;
        });
        ui.on("chat", b -> {
            List<String> att = new ArrayList<>();
            if (b.get("attachments") instanceof List) for (Object o : (List<?>) b.get("attachments")) att.add(String.valueOf(o));
            return chat.send(str(b, "text", ""), "text", att);
        });
        ui.on("attach", b -> chat.attach(str(b, "name", ""), str(b, "b64", ""))); // [overnight-1119] 粘贴截图 / 拖进来 / 附件按钮
        // [preview-1120] 主题插件：列出 / 导入 / 删除主题包（外观模式和当前包走 settings 的 theme / theme_pack）
        ui.on("theme", b -> {
            String op = str(b, "op", "list");
            Path dir = Config.defaultDir().resolve("themes");
            try {
                switch (op) {
                    case "import": {
                        Map<String, Object> m = Themes.importPack(dir, str(b, "json", ""));
                        Map<String, Object> r = ok("导入了主题「" + m.get("name") + "」");
                        r.put("pack", m);
                        return r;
                    }
                    case "remove": {
                        String id = str(b, "id", "");
                        if (!Themes.remove(dir, id)) return fail("删不了（自带的主题不能删，或者没有这个）");
                        if (id.equals(cfg.themePack)) { cfg.themePack = ""; cfg.save(); }
                        return ok("删掉了");
                    }
                    default: {
                        Map<String, Object> r = ok("");
                        r.put("packs", Themes.list(dir));
                        return r;
                    }
                }
            } catch (IllegalArgumentException e) {
                return fail(e.getMessage());
            } catch (IOException e) {
                return fail("存不下：" + e.getClass().getSimpleName());
            }
        });
        // [preview-1120] 系统选择框：文件夹 / 程序（.exe）/ 文件。设置页里需要路径的地方都用它，不用手敲
        ui.on("pick", b -> {
            String kind = str(b, "kind", "folder");
            java.io.File f = pick(kind, str(b, "title", ""), str(b, "start", ""));
            if (f == null) return fail("没选");
            Map<String, Object> r = ok(f.getAbsolutePath());
            r.put("path", f.getAbsolutePath());
            return r;
        });
        // [preview-1120] MCP 连接器
        ui.on("mcp", b -> {
            String op = str(b, "op", "list");
            switch (op) {
                case "add": return mcp.addSource(str(b, "path", ""));
                case "pick": {
                    java.io.File f = pick("folder_or_json", "选 MCP 服务器所在的文件夹（或 MCP 配置文件）", "");
                    if (f == null) return fail("没选");
                    return mcp.addSource(f.getAbsolutePath());
                }
                case "enable": return mcp.setEnabled(str(b, "id", ""), !"false".equals(str(b, "on", "true")));
                case "restart": return mcp.restart(str(b, "id", ""));
                case "remove": return mcp.remove(str(b, "id", ""));
                case "rescan": return mcp.rescan(str(b, "source", ""));
                case "try": {
                    // 界面「试一下」：只许服务器声明只读的工具（有副作用的请在对话里用，走确认卡）
                    String fn = str(b, "fn", "");
                    if (!mcp.readOnlyFn(fn)) return fail("这个工具没声明只读，可能有副作用：请在对话里让助手用它（会弹确认卡）");
                    @SuppressWarnings("unchecked") Map<String, Object> args = b.get("arguments") instanceof Map ? (Map<String, Object>) b.get("arguments") : new TreeMap<>();
                    Actions.Result r = mcp.call(fn, args);
                    Map<String, Object> m = r.ok ? ok(r.text) : fail(r.text);
                    if (r.data != null) m.put("output", r.data.get("output"));
                    log.accept("MCP 试用 " + mcp.describe(fn) + "：" + (r.ok ? "成功" : "失败"));
                    return m;
                }
                default: {
                    Map<String, Object> r = ok("");
                    r.putAll(mcp.view());
                    return r;
                }
            }
        });
        // [overnight-1119] 文件互传：看进度、选文件发到手机、打开「来自手机」文件夹
        ui.on("xfer", b -> {
            String op = str(b, "op", "list");
            if (op.equals("pick_send")) {
                String phone = agent.phoneTarget("");
                if (phone == null || phone.isEmpty() || !agent.peers().containsKey(phone)) return fail("手机没连着（先在手机上打开 OneSay，确认电脑联动在线）");
                java.io.File f = pickFile();
                if (f == null) return fail("没选文件");
                new Thread(() -> {
                    Actions.Result r = xfer.send(f, phone, this::callPhone);
                    log.accept("发文件到手机：" + (r.ok ? "成功 " : "失败 ") + f.getName());
                    notify.post("task", r.ok ? "文件已发到手机" : "文件没发到手机", r.text, phone, r.ok ? Outcomes.DONE : Outcomes.FAILED);
                }, "onesay-xfer-send").start();
                return ok("开始发：" + f.getName() + "（" + FileXfer.human(f.length()) + "），进度看下面");
            }
            if (op.equals("send_path")) {
                // 电脑上一个已知路径发到手机（拖进传输区 / 对话里点「发到手机」用；同样分块、进度在这里看）
                String phone = agent.phoneTarget("");
                if (phone == null || phone.isEmpty() || !agent.peers().containsKey(phone)) return fail("手机没连着（先在手机上打开 OneSay，确认电脑联动在线）");
                java.io.File f = new java.io.File(str(b, "path", ""));
                if (!f.isFile()) return fail("电脑上没有这个文件");
                new Thread(() -> {
                    Actions.Result r = xfer.send(f, phone, this::callPhone);
                    log.accept("发文件到手机：" + (r.ok ? "成功 " : "失败 ") + f.getName());
                    notify.post("task", r.ok ? "文件已发到手机" : "文件没发到手机", r.text, phone, r.ok ? Outcomes.DONE : Outcomes.FAILED);
                }, "onesay-xfer-send").start();
                return ok("开始发：" + f.getName() + "（" + FileXfer.human(f.length()) + "），进度看下面");
            }
            if (op.equals("open_dir")) {
                try {
                    java.nio.file.Path d = cfg.workspace().resolve(FileXfer.DIR_FROM_PHONE);
                    Files.createDirectories(d);
                    SafeExec.start(new ProcessBuilder("explorer.exe", d.toString()));
                    return ok("已打开");
                } catch (Exception e) { return fail("打不开：" + e.getClass().getSimpleName()); }
            }
            Map<String, Object> m = ok("传输");
            m.put("xfers", xfer.view());
            m.put("dir", cfg.workspace().resolve(FileXfer.DIR_FROM_PHONE).toString());
            return m;
        });
        ui.on("chat_clear", b -> { if (chat.running()) return fail("任务还在做，先点「停止」"); store.clear("chat"); chat.clearArchive(); return ok("已清空对话，记忆保留"); });
        // [longctx-1123] 新对话：开干净的一段（前面的消息留在上面，长期记忆照旧）
        ui.on("chat_new", b -> { Actions.Result r = chat.newChat(); return r.ok ? ok(r.text) : fail(r.text); });
        ui.on("chat_stop", b -> { boolean again = chat.running() && chat.stopping(); return chat.stop() ? ok(again ? "直接结束，不再收尾" : "正在停下：中断当前这一步，再简短收尾（再点一次停止就直接结束）") : ok("没有在做的任务"); });
        ui.on("dl_cancel", b -> chat.cancelTransfer() ? ok("已取消，任务会接着往下") : ok("现在没有在下载 / 传的")); // [overnight-1119]
        ui.on("artifact", b -> chat.artifact(str(b, "path", ""))); // [devtools-1118] 任务卡里的截图 / 文件（只给对话里登记过的路径）
        ui.on("memory", b -> { store.setMemory(str(b, "text", ""), System.currentTimeMillis()); return ok("记忆已保存"); });
        ui.on("memory_del", b -> { Actions.Result r = chat.deleteMemoryLine(str(b, "line", "")); return r.ok ? ok(r.text) : fail(r.text); }); // [fix-1123-c] 记忆页删一条
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
            // [rel-1123 合并] 「手机」页「交给手机去做」：手机收下目标后也登记，结果回传后 state.phone_goal 里能看到（和对话交出去的同一套）
            if (cmd.equals("operate") && r.ok && r.data != null && r.data.get("goal_id") != null) {
                String gid = String.valueOf(r.data.get("goal_id"));
                if (gid.matches("[A-Za-z0-9_-]{4,64}")) {
                    String to = agent.phoneTarget("");
                    agent.goalStarted(to == null ? "" : to, gid, str(args, "goal", ""));
                }
            }
            Map<String, Object> m = r.ok ? ok(cmd.equals("status") ? Chat.statusLine(r) : r.text) : fail(PhoneCmds.withReason(r));
            if (r.data != null) m.put("data", new TreeMap<String, Object>(r.data));
            return m;
        });
        // [fix-1123-a] 「手机上打开网址」预填：只在电脑剪贴板里是一个 http(s) 网址时回这个网址，别的内容不回界面
        ui.on("clip_url", b -> {
            try {
                Actions.Result c = actions.run("clipboard_get", Map.of());
                String t = c.ok && c.data != null && c.data.get("text") != null ? String.valueOf(c.data.get("text")).trim() : "";
                Map<String, Object> m = ok("");
                m.put("url", clipUrl(t));
                return m;
            } catch (Exception e) {
                Map<String, Object> m = ok("");
                m.put("url", "");
                return m;
            }
        });
        ui.on("confirm", b -> confirmApi(agent.confirms, b, log));
        ui.on("whitelist", b -> whitelistApi(whitelist, b, log));
        ui.on("media", this::mediaApi);
        ui.on("mirror", this::mirrorApi);   // phone-mirror M1：只给电脑界面用（本机口令保护），不进 agent.local
        ui.onUpload("mirror_drop", (q, size, in) -> mirror.drop(q.getOrDefault("serial", ""), q.getOrDefault("name", ""), size, in));
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
        // [approvals-1118] 本机对话：前缀规则（以后以 … 开头的命令不用问）看 / 撤销；任务 / 运行级批准撤销；权限记录
        ui.on("chat_rules", b -> {
            String op = str(b, "op", "list");
            if (op.equals("revoke")) {
                String k = str(b, "key", "");
                if (!agent.guard.forgetRule(k)) return fail("没有这条规则");
                agent.guard.logPerm(ChatPolicy.keyZh(k), "撤销", "");
                log.accept("撤销了对话前缀规则：" + k);
            } else if (op.equals("revoke_grants")) {
                agent.guard.revokeGrants();
                agent.guard.logPerm("「允许本次任务 / 这次运行都允许」", "撤销", "");
            } else if (op.equals("clear_stats")) {
                store.clearPermStats();
                agent.guard.logPerm("权限审批记录的次数", "清零", "");
            } else if (!op.equals("list")) return fail("不认识的操作");
            Map<String, Object> m = ok(op.equals("list") ? "对话规则" : op.equals("clear_stats") ? "已清零" : "已撤销，下次会再问你");
            m.put("perm_stats", PermStats.view(store.permStats()));
            m.put("chat_rules", ChatPolicy.view(agent.guard.chatRules()));
            m.put("grants", agent.guard.grantView());
            m.put("perm_log", agent.guard.permLogView());
            return m;
        });
        // [approvals-1118] 任务卡上「本地应用 / 网页」的选择（不是审批）：只收界面上的真实点击
        ui.on("choice", b -> {
            Object id = b.get("id");
            if (!(id instanceof Number)) return fail("没有这个选择");
            if (!Boolean.TRUE.equals(b.get("trusted"))) return fail("只认界面上的点击");
            return chat.choices.answer(((Number) id).intValue(), str(b, "pick", ""), !Boolean.FALSE.equals(b.get("remember"))) ? ok("好") : fail("已经过期");
        });
        // 打开习惯：看 / 改 / 删
        ui.on("habits", b -> {
            String op = str(b, "op", "list"), k = OpenTarget.norm(str(b, "key", ""));
            Map<String, Map<String, Object>> h = OpenTarget.parseHabits(cfg.openHabits);
            if (op.equals("remove")) { if (h.remove(k) == null) return fail("没有这条习惯"); }
            else if (op.equals("set")) {
                String c = str(b, "choice", "");
                if (!h.containsKey(k) || !(c.equals("app") || c.equals("web"))) return fail("改不了");
                h.get(k).put("choice", c);
            } else if (!op.equals("list")) return fail("不认识的操作");
            if (!op.equals("list")) {
                cfg.openHabits = OpenTarget.formatHabits(h);
                try { cfg.save(); } catch (IOException e) { return fail("保存失败"); }
                agent.guard.logPerm("打开习惯「" + k + "」", op.equals("remove") ? "删除" : "改成" + ("web".equals(str(b, "choice", "")) ? "网页" : "本地应用"), "");
            }
            Map<String, Object> m = ok("打开习惯");
            m.put("habits", h);
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
            // [rel-1123-b] 免扫码配对：查看 / 打开 / 关上「允许同一局域网里的新手机发起配对」（配对请求本身走 confirms，在右下角确认框里点）
            // [rel-1123 合并] 免扫码配对：先设 4 位配对码才能打开；码只以 PBKDF2 结果存进凭据库，界面上不再显示
            String pop = str(b, "op", "");
            if (pop.equals("invite") || pop.equals("code") || pop.equals("code_off") || pop.equals("unlock")) {
                LanServer ls = agent.lan();
                if (ls == null) return fail("局域网直连没开（配置里 lan_port=0 或 ONESAY_LAN=off），用不了免扫码配对");
                String text;
                if (pop.equals("code")) {
                    String code = str(b, "code", "").trim();
                    String weak = LanPair.weakCode(code);
                    if (weak != null) return fail(weak);
                    LanPair.Verifier v = LanPair.Verifier.of(code);
                    if (!pairSecrets.put(PAIR_CODE_NAME, v.format())) return fail("配对码没存上：" + java.util.Objects.toString(pairSecrets.lastProblem(), "凭据库写不进去"));
                    boolean had = ls.pairCode != null;
                    ls.pairCode = v;
                    ls.pairUnlock();
                    cfg.pairCodeSet = true;
                    if (Boolean.TRUE.equals(b.get("on"))) cfg.pairInvite = true;
                    try { cfg.save(); } catch (IOException e) { return fail("保存失败"); }
                    log.accept(had ? "免扫码配对的配对码改好了（以后的新手机用新码；已经配好的手机不受影响）" : "设好了免扫码配对的配对码");
                    text = (had ? "配对码改好了。" : "配对码设好了。") + (cfg.pairInvite ? "手机上点「找附近的电脑」，选「" + ls.pcName + "」，输这 4 位就能配对" : "");
                } else if (pop.equals("code_off")) {
                    pairSecrets.delete(PAIR_CODE_NAME);
                    ls.pairCode = null;
                    ls.pairUnlock();
                    cfg.pairCodeSet = false;
                    cfg.pairInvite = false;
                    try { cfg.save(); } catch (IOException e) { return fail("保存失败"); }
                    log.accept("删掉了免扫码配对的配对码，「允许新手机配对」也关上了（扫码配对照旧能用）");
                    text = "配对码删掉了，「允许新手机配对」也关上了（扫码配对照旧能用）";
                } else if (pop.equals("unlock")) {
                    ls.pairUnlock();
                    log.accept("在电脑上解锁了免扫码配对（输错次数清零）");
                    text = "解锁了，输错次数清零";
                } else {
                    Object on = b.get("on");
                    if (Boolean.TRUE.equals(on) && ls.pairCode == null) {
                        Map<String, Object> m = fail("先设一个 4 位配对码，才能打开「允许新手机配对」");
                        m.put("need_code", true);
                        m.putAll(pairView());
                        return m;
                    }
                    if (on instanceof Boolean && (Boolean) on != cfg.pairInvite) {
                        cfg.pairInvite = (Boolean) on;
                        try { cfg.save(); } catch (IOException e) { return fail("保存失败"); }
                        log.accept(cfg.pairInvite ? "打开了「允许新手机配对」（手机要输对你设的配对码）" : "关上了「允许新手机配对」（扫码配对照旧能用）");
                    }
                    text = cfg.pairInvite ? "同一个网络里的手机点「找附近的电脑」就能找到「" + ls.pcName + "」，输对你设的配对码就配上" : "「允许新手机配对」关着：同一个网络里的手机找不到这台电脑（扫码配对照旧能用）";
                }
                Map<String, Object> m = ok(text);
                m.putAll(pairView());
                m.put("lan_ips", new ArrayList<Object>(LanClient.localAddresses()));
                m.put("port", (long) cfg.lanPort);
                return m;
            }
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
        ui.on("aon", this::aonApi); // [always-on-1120]
        ui.on("wf", b -> wf.handle(b)); // [workflow-1120] 工作流页
        ui.on("settings", settingsFn = b -> {
            boolean restartVoice = false, aonTouched = false;
            for (Map.Entry<String, Object> e : b.entrySet()) {
                String v = String.valueOf(e.getValue()).trim();
                switch (e.getKey()) {
                    case "llm_provider": if (List.of("deepseek", "bailian", "custom").contains(v)) cfg.llmProvider = v; break;
                    case "llm_key": if (v.equals("-")) cfg.llmKey = ""; else if (!v.isEmpty()) cfg.llmKey = v; break;
                    case "llm_model": cfg.llmModel = v; break;
                    case "llm_base": if (baseOk(v)) cfg.llmBase = v; else return fail("接口地址要用 https（本机的 http://127.0.0.1 / localhost 也行）"); break;
                    case "asr_mode": if (List.of("local", "phone", "cloud").contains(v)) cfg.asrMode = v; break; // 旧接口，只存不用
                    case "asr_pref": if (List.of("auto", "cloud", "local").contains(v)) { cfg.asrPref = v; asr.invalidate(); restartVoice = true; log.accept("识别 → " + AsrRoute.Pref.of(v).zh); } break;
                    case "asr_key": if (v.equals("-")) cfg.asrKey = ""; else if (!v.isEmpty()) cfg.asrKey = v; asr.invalidate(); restartVoice = true; break;
                    case "ptt": cfg.pttEnabled = v.equals("true"); restartVoice = true; break;
                    case "ptt_skip": cfg.pttSkip = v; restartVoice = true; break;
                    // [preview-1120] 路径类设置：界面用系统选择框选；这里再核一次，填错了明说（以前什么都收，到用的时候才发现不对）
                    case "files_dir": { String bad = dirProblem(v); if (bad != null) return fail("共享文件夹：" + bad); cfg.filesDir = v; break; }
                    case "scripts_dir": { String bad = dirProblem(v); if (bad != null) return fail("快捷指令文件夹：" + bad); cfg.scriptsDir = v; break; }
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
                    case "chat_preset": {
                        ChatPolicy.Preset np = ChatPolicy.Preset.of(v);
                        if (!np.id.equals(cfg.chatPreset)) {
                            cfg.chatPreset = np.id;
                            agent.guard.revokeGrants(); // 改预设：之前的任务 / 运行级批准作废
                            agent.guard.logPerm("对话权限预设 → " + np.zh, "设置", "");
                            log.accept("对话权限预设 → " + np.zh);
                        }
                        break;
                    }
                    case "chat_timeout_min": try { cfg.chatTimeoutMin = Math.max(5, Math.min(1440, Integer.parseInt(v))); } catch (NumberFormatException nfe) { return fail("时长写分钟数"); } break;
                    case "autostart": autostart(v.equals("true")); break;
                    case "media_quality": cfg.mediaQuality = MediaArgs.quality(v); break;
                    case "vision_key": if (v.equals("-")) cfg.visionKey = ""; else if (!v.isEmpty()) cfg.visionKey = v; break; // [devtools-1118]
                    case "vision_base": if (baseOk(v)) cfg.visionBase = v; else return fail("看图模型接口地址要用 https（本机的 http://127.0.0.1 / localhost 也行）"); break;
                    case "vision_model": cfg.visionModel = v; break;
                    case "adb_path": {
                        if (!v.isEmpty() && (!new File(v).isFile() || !new File(v).getName().toLowerCase(java.util.Locale.ROOT).matches("adb(\\.exe)?"))) return fail("adb 程序要选 adb.exe 这个文件（没找到或不是 adb）");
                        cfg.adbPath = v;
                        break;
                    }
                    case "theme": cfg.theme = Themes.mode(v); break; // [preview-1120] 外观
                    case "theme_pack": if (v.matches("[a-z0-9-]{0,40}")) cfg.themePack = v; else return fail("主题包 id 不对"); break;
                    case "wol_enabled": { // [always-on-1120] 网络唤醒：打开时没选过网卡就用第一块连着的有线网卡
                        cfg.wolEnabled = v.equals("true");
                        if (!cfg.wolEnabled) cfg.wolAuto = false;
                        if (cfg.wolEnabled && cfg.wolMac.isEmpty()) for (Map<String, Object> n : nics())
                            if (!Boolean.TRUE.equals(n.get("wireless")) && !String.valueOf(n.get("ip")).isEmpty()) { cfg.wolMac = String.valueOf(n.get("mac")); cfg.wolBcast = String.valueOf(n.get("bcast")); break; }
                        aonTouched = true;
                        log.accept("网络唤醒 → " + (cfg.wolEnabled ? "开" : "关"));
                        break;
                    }
                    case "wol_auto":
                        if (v.equals("true") && !cfg.wolEnabled) return fail("先打开网络唤醒");
                        if (v.equals("true") && cfg.wolMac.isEmpty()) return fail("先选唤醒用的网卡");
                        cfg.wolAuto = v.equals("true");
                        aonTouched = true;
                        log.accept("预先授权自动唤醒 → " + (cfg.wolAuto ? "开" : "关"));
                        break;
                    case "mirror_keyboard": cfg.mirrorKeyboard = "uhid".equals(v) ? "uhid" : "sdk"; log.accept("投屏键盘 → " + ("uhid".equals(v) ? "物理键盘（UHID）" : "普通键盘")); break;
                    default: break;
                }
            }
            try { cfg.save(); } catch (IOException e) { return fail("保存失败"); }
            if (aonTouched && aon != null) { aonCfg(); aon.wakeChanged(); }
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
        String scope = allow ? str(b, "scope", "") : "";
        ConfirmGate.Input in = Boolean.TRUE.equals(b.get("trusted")) ? ConfirmGate.Input.of(b.get("input")) : ConfirmGate.Input.UNKNOWN;
        ConfirmGate.Answer a = cf.answer(((Number) id).intValue(), allow, always, remember, scope, in);
        switch (a) {
            case ACCEPTED:
                log.accept((allow ? (always ? "允许（以后直接执行）" : remember ? "允许（以后这类不用问）" : "允许") : "拒绝") + "了一条远程请求（" + (in.local() ? (in == ConfirmGate.Input.KEYBOARD ? "键盘" : "鼠标") : "界面") + "）");
                return ok(allow ? ("task".equals(scope) ? "已允许本次任务" : "session".equals(scope) ? "这次运行都允许" : always ? "已允许，以后直接执行" : remember ? "已允许，以后这类不用问" : "已允许一次") : "已拒绝");
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
            Process p = new ProcessBuilder("reg", "query", RUN_KEY, "/v", Flavor.runValue()).redirectErrorStream(true).start();
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
            ProcessBuilder pb = on ? new ProcessBuilder("reg", "add", RUN_KEY, "/v", Flavor.runValue(), "/t", "REG_SZ", "/d", cmd, "/f")
                    : new ProcessBuilder("reg", "delete", RUN_KEY, "/v", Flavor.runValue(), "/f");
            Process p = pb.redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor();
        } catch (Exception ignored) { }
    }

    // ---------- 窗口与托盘 ----------
    /** ★ exe-handoff-1118：Edge 后备窗口的地址带这个片段，页面据此在标题和顶部写明「Edge 后备窗口」（片段不发给服务端）。 */
    static final String EDGE_FALLBACK_MARK = "#edge-fallback";

    /** Edge 后备窗口打开的地址：原地址加 {@link #EDGE_FALLBACK_MARK}（已有片段就替换）。 */
    static String edgeFallbackUrl(String url) {
        int h = url.indexOf('#');
        return (h >= 0 ? url.substring(0, h) : url) + EDGE_FALLBACK_MARK;
    }

    /**
     * OneSay.exe 托管时用它自己进程里的窗口（WebView2，窗口属于 OneSay.exe）——正常情况；直接用 java 启动时
     * {@link ExeHandoff} 会先交给 OneSay.exe。都不行（没装 OneSay.exe / 装的 jar 不同 / 没有 WebView2 运行时）才是
     * 最后的后备：Edge 应用窗口（标明「Edge 后备窗口」）；没有 Edge 就用默认浏览器。
     */
    static void openWindow(String url) {
        if (Host.open(url)) return;
        if (isWindows()) {
            System.err.println("[window] 后备：Edge 应用窗口（不是 OneSay.exe 托管，窗口会算在 Edge 名下）");
            String edgeUrl = edgeFallbackUrl(url);
            List<String> cands = new ArrayList<>();
            for (String env : List.of("ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA")) {
                String base = System.getenv(env);
                if (base != null) cands.add(base + "\\Microsoft\\Edge\\Application\\msedge.exe");
            }
            for (String c : cands) {
                if (!new File(c).isFile()) continue;
                try {
                    String profile = Config.defaultDir().resolve("window").toString();
                    new ProcessBuilder(c, "--app=" + edgeUrl, "--user-data-dir=" + profile, "--window-size=1200,800", "--no-first-run", "--no-default-browser-check").start();
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
        TrayIcon icon = new TrayIcon(trayImage(true), Flavor.name() + " " + cfg.deviceId, menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> openWindow(ui.url()));
        SystemTray.getSystemTray().add(icon);
        notify.attach(icon);
        trayIcon = icon;
        new Thread(() -> {
            boolean last = true;
            while (true) {
                if (agent.confirms.list().isEmpty()) trayHint(null); // [overnight-1119] 没卡等着了：提示文字复原
                boolean on = !agent.peers().isEmpty();
                st.setLabel(on ? "手机在线" : "手机离线");
                if (on != last) { icon.setImage(trayImage(on)); last = on; }
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
        }, "onesay-tray").start();
    }

    /**
     * [preview-1120 AUDIT A-07] 模型 / 看图接口地址：空、https，或者真正的本机 http。以前用 startsWith("http://localhost")，
     * 「http://localhost.example.com」「http://127.0.0.1.nip.io」也算本机，Key 会用明文发到别人的服务器。
     */
    static boolean baseOk(String v) {
        if (v == null || v.isEmpty()) return true;
        try {
            java.net.URI u = new java.net.URI(v);
            String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
            String h = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
            if (h.isEmpty() || u.getUserInfo() != null) return false;
            if (sc.equals("https")) return true;
            return sc.equals("http") && (h.equals("127.0.0.1") || h.equals("localhost") || h.equals("[::1]") || h.equals("::1"));
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    /** [preview-1120] 文件夹设置：空 = 不开；否则要是绝对路径、真的存在、是文件夹。返回问题（null = 没问题）。 */
    static String dirProblem(String v) {
        if (v == null || v.isEmpty()) return null;
        File f = new File(v);
        if (!f.isAbsolute()) return "要完整路径（点「选择…」从电脑上选）";
        if (!f.exists()) return "「" + v + "」不存在";
        if (!f.isDirectory()) return "「" + v + "」不是文件夹";
        return null;
    }
}
