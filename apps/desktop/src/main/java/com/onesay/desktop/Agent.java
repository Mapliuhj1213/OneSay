package com.onesay.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** 电脑端常驻：局域网（默认）+ 中转（可选插件）收指令，验签、执行，从原通道回签名结果。 */
public final class Agent {
    private final Config cfg;
    private final Actions actions;
    private final List<Link> links = new ArrayList<>();
    private final Map<Link, String> state = new ConcurrentHashMap<>();
    private final Map<Link, Boolean> up = new ConcurrentHashMap<>();
    private final Protocol.ReplayGuard replay = new Protocol.ReplayGuard();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "onesay-exec");
        t.setDaemon(true);
        return t;
    });
    private final Consumer<String> log;
    public volatile String status = "启动中";
    /** 程序内部处理的指令（通知、记忆同步等），优先于 Actions。 */
    public final Map<String, java.util.function.Function<Map<String, Object>, Actions.Result>> local = new ConcurrentHashMap<>();
    private final java.util.Set<String> relayPeers = ConcurrentHashMap.newKeySet();
    /** link-v2：手机发来的指令先过执行级别 / 信任检查 / 工作区沙箱（电脑是严格矩阵）。 */
    public final PcGuard.Confirms confirms = new PcGuard.Confirms();
    public final PcGuard guard;
    /** 手机回复里带来的手机执行级别（只读显示，在手机上改）。 */
    public volatile String phoneLevel = "";
    /** [always-on-1120] 常驻分工的帧（aon_*，只从中转来）交给 AlwaysOnService；通道上线 / 掉线时回调（中转连上要同步任务）。 */
    public volatile java.util.function.Consumer<Map<String, Object>> nodeHook;
    public volatile java.util.function.BiConsumer<Link, Boolean> onLinkState;

    public Agent(Config cfg, Actions actions, Consumer<String> log) {
        this.cfg = cfg;
        this.actions = actions;
        this.log = log;
        this.guard = new PcGuard(cfg, confirms);
        confirms.phoneOffer = this::offerToPhone;
        confirms.phoneClose = this::closeOnPhone;
        // [preview-1120] 环境变量 ONESAY_LAN=off：这次不开局域网监听（真机测预览版时用，免得新路径的 exe 弹防火墙询问；手机离线时也用不上）
        if (cfg.lanEnabled() && !"off".equalsIgnoreCase(String.valueOf(System.getenv("ONESAY_LAN")))) add(l -> new LanServer(cfg, l));
        if (cfg.relayEnabled()) add(l -> new RelayClient(cfg, l));
        // [rel-1123 合并] 免扫码配对改成固定配对码 + SPAKE2（不再弹电脑确认框）；配对码由 App 读进 LanServer.pairCode
        for (Link l : links) if (l instanceof LanServer) ((LanServer) l).log = log;
    }

    /** 局域网服务（没开局域网返回 null）。 */
    public LanServer lan() {
        for (Link l : links) if (l instanceof LanServer) return (LanServer) l;
        return null;
    }

    /** [rel-1123-b] 没有手机在线时的原因（有在线的、或没开局域网返回 null）。 */
    public OfflineWhy.Why offlineWhy() {
        if (!relayPeers.isEmpty()) return null;
        for (Link l : links) if (l instanceof LanServer) return ((LanServer) l).offlineWhy(cfg.phoneLast, System.currentTimeMillis());
        return null;
    }

    private void add(java.util.function.Function<Link.Listener, Link> make) {
        Link[] self = new Link[1];
        self[0] = make.apply(new Link.Listener() {
            @Override public void onFrame(Map<String, Object> f) { handle(f, self[0]); }
            @Override public void onState(boolean c, String why) { changed(self[0], c, why); }
        });
        links.add(self[0]);
    }

    public void start() { for (Link l : links) l.start(); }

    public void stop() { for (Link l : links) l.stop(); exec.shutdownNow(); }

    public boolean connected() { return up.containsValue(Boolean.TRUE); }

    public List<Link> links() { return links; }

    /** 当前连着的配对设备：设备 id → 通道名。 */
    public Map<String, String> peers() {
        Map<String, String> m = new TreeMap<>();
        for (String p : relayPeers) m.put(p, "中转");
        for (Link l : links) if (l instanceof LanServer) for (String c : ((LanServer) l).clients()) m.put(c, ((LanServer) l).tls(c) ? "局域网·加密" : "局域网");
        return m;
    }

    private void changed(Link l, boolean c, String why) {
        up.put(l, c);
        state.put(l, (c ? "在线" : "离线") + "（" + why + "）");
        StringBuilder s = new StringBuilder(connected() ? "在线" : "离线");
        for (Link x : links) s.append(" · ").append(x.name()).append(Boolean.TRUE.equals(up.get(x)) ? "✓" : "✗");
        status = s.toString();
        String line = quiet.computeIfAbsent(l, k -> new LogQuiet()).filter(l.name(), c, why, System.currentTimeMillis()); // [overnight-1119] 同样的离线原因不刷屏
        if (line != null) log.accept(line);
        java.util.function.BiConsumer<Link, Boolean> h = onLinkState;
        if (h != null) try { h.accept(l, c); } catch (RuntimeException e) { log.accept("通道回调出错：" + e.getClass().getSimpleName()); }
    }

    /** [always-on-1120] 中转连接（没配中转 = null）。 */
    public RelayClient relay() { for (Link l : links) if (l instanceof RelayClient) return (RelayClient) l; return null; }

    private final Map<Link, LogQuiet> quiet = new java.util.concurrent.ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    void handle(Map<String, Object> f, Link via) {
        String type = String.valueOf(f.get("type"));
        if (type.startsWith("aon_")) { // [always-on-1120] 常驻节点的帧只认中转来的（局域网对端冒充不了节点）
            java.util.function.Consumer<Map<String, Object>> h = nodeHook;
            if (h != null && via instanceof RelayClient) h.accept(f);
            return;
        }
        if (type.equals("presence")) {
            boolean on = Boolean.TRUE.equals(f.get("online"));
            if (via instanceof RelayClient) { // [rel-1123-b] 局域网的在线以常连为准（LanServer.clients），这里只记中转的
                if (on) relayPeers.add(String.valueOf(f.get("device"))); else relayPeers.remove(String.valueOf(f.get("device")));
            }
            log.accept("设备 " + f.get("device") + (on ? " 上线" : " 离线") + "（" + via.name() + "）");
            return;
        }
        if (type.equals("devices") || type.equals("error")) {
            if (type.equals("error")) log.accept(via.name() + "报错 " + f.get("code"));
            // [always-on-1120] 旧版中转不认识常驻分工的帧，回不带 id 的 bad_frame：告诉常驻分工「中转要更新」，别一直重发
            java.util.function.Consumer<Map<String, Object>> h = nodeHook;
            if (type.equals("error") && "bad_frame".equals(f.get("code")) && f.get("id") == null && h != null && via instanceof RelayClient) {
                Map<String, Object> e = new TreeMap<>();
                e.put("type", "aon_err");
                e.put("code", "unknown");
                e.put("op", "aon");
                h.accept(e);
            }
            return;
        }
        if (!type.equals("msg") || !(f.get("body") instanceof Map)) return;
        String from = String.valueOf(f.get("from"));
        String id = String.valueOf(f.get("id"));
        Map<String, Object> body = (Map<String, Object>) f.get("body");
        if ("result".equals(body.get("kind"))) { onResult(from, id, body); return; } // 电脑 → 手机下令的回复
        if ("confirm_answer".equals(body.get("kind"))) { onConfirmAnswer(from, id, body); return; } // 手机屏幕上确认卡的回答
        if ("goal_result".equals(body.get("kind"))) { onGoalResult(from, id, body); return; } // [rel-1123-b] 电脑交给手机的目标做完了
        if (!"cmd".equals(body.get("kind"))) return;

        Protocol.Check c = Protocol.verify(cfg.pairKey, id, from, cfg.deviceId, body, System.currentTimeMillis(), replay);
        if (c != Protocol.Check.OK) {
            log.accept("拒绝来自 " + from + " 的指令：" + c);
            reply(via, from, id, false, "拒绝：" + c, null, c == Protocol.Check.BAD_SIG || c == Protocol.Check.NO_SIG);
            return;
        }
        if (!cfg.peerAllowed(from)) {
            log.accept("拒绝未授权设备 " + from);
            reply(via, from, id, false, "此设备未被允许下令", null, false);
            return;
        }
        String cmd = String.valueOf(body.get("cmd"));
        Map<String, Object> args = body.get("args") instanceof Map ? (Map<String, Object>) body.get("args") : Map.of();
        log.accept("执行 " + cmd + "（来自 " + from + "，经" + via.name() + "）");
        // 要确认的指令最多等 60 秒：放到单独线程，免得挡住后面的指令（找手机 / 状态等仍走顺序执行线程）
        Runnable job = () -> {
            Actions.Result r;
            SafeExec.REMOTE.set(Boolean.TRUE); // 远程指令：子进程永不提权；OneSay 是管理员时要降权，降不了就拒
            try {
                PcGuard.Outcome o = guard.check(from, cmd, args);
                if (o.reply != null) {
                    r = o.reply;
                    log.accept("没执行 " + cmd + "（来自 " + from + "）：" + r.data.get("reason"));
                } else if (o.pass == null || !o.pass.consume(cmd, o.args)) {
                    // 审批在执行层强制：没有判定放行时发的一次性凭证就不执行（思路借鉴 DIYgod/pinky，Apache-2.0；代码自己写的）
                    r = Actions.Result.fail("没有通过审批，不执行");
                } else if (cmd.equals("caps")) {
                    List<Object> caps = new ArrayList<>(actions.capabilities());
                    caps.addAll(new java.util.TreeSet<>(local.keySet()));
                    caps.addAll(List.of("file_write", "script_list", "script_run", "sys_exec", "level"));
                    r = Actions.Result.ok("能力清单", DesktopActions.map("caps", caps));
                } else if (local.containsKey(cmd)) {
                    Map<String, Object> a2 = new TreeMap<>(o.args);
                    a2.put("_from", from);
                    r = local.get(cmd).apply(a2);
                } else {
                    Actions.Result b = guard.builtin(cmd, o.args);
                    r = b != null ? b : actions.run(cmd, o.args);
                }
            } catch (SafeExec.Refused t) {
                Map<String, Object> d = new TreeMap<>();
                d.put("reason", LinkPolicy.R_PLUGIN_BLOCKED);
                d.put("reason_zh", LinkPolicy.reasonZh(LinkPolicy.R_PLUGIN_BLOCKED));
                r = new Actions.Result(false, t.getMessage(), d);
                log.accept("没执行 " + cmd + "（来自 " + from + "）：" + LinkPolicy.R_PLUGIN_BLOCKED);
            } catch (Throwable t) {
                r = Actions.Result.fail(cmd + " 出错：" + t.getClass().getSimpleName());
            } finally {
                SafeExec.REMOTE.set(Boolean.FALSE);
            }
            noteRead(from, cmd, r); // [app-whitelist-1116] 读到了隐私：本次会话里后面的外发即使在白名单也要确认
            reply(via, from, id, r.ok, r.text, guard.decorate(r.data), false);
        };
        LinkPolicy.Cat cat = PcGuard.category(cmd, args, null);
        if (cat == LinkPolicy.Cat.QUERY || cat == LinkPolicy.Cat.HARMLESS || cat == LinkPolicy.Cat.READ_PRIVATE || cat == LinkPolicy.Cat.UNKNOWN) exec.execute(job);
        else {
            Thread t = new Thread(job, "onesay-exec-confirm");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * [chat-agent-1118] 电脑端「对话」里模型发起的一个动作：和手机远程指令同一条链 —— 安全插件 → 两轴矩阵 / 白名单 → 电脑上的确认框
     * （只认本机鼠标 / 键盘）→ 一次性凭证 → 执行。子进程以用户自己的令牌运行，永不提权（runas / UAC 绕过照旧拦、要管理员的只生成脚本）。
     * 不按远程处理（{@link SafeExec#REMOTE}）：远程那条是防手机端被冒用，电脑没法降权就一律拒；本机对话的人就坐在电脑前，
     * 如果整个登录会话本来就是管理员（实测 desktop-o0qh8hf 的交互会话就是高完整性），一律拒等于对话什么都做不了。
     * 作为补偿：OneSay 是管理员时，只读以外的每一步确认卡都写明「会带管理员权限执行」，且不能「以后这类不用问」（{@link PcGuard#check}）。
     * 只读查询直接执行、断网改确认见 {@link PcGuard#LOCAL}。
     */
    public Actions.Result runLocal(String cmd, Map<String, Object> args) {
        Actions.Result r;
        PcGuard.LOCAL.set(Boolean.TRUE);
        SafeExec.REMOTE.set(Boolean.FALSE);
        try {
            if ("net_download".equals(cmd)) return netDownload(ChatAgent.FROM, args);
            // [approvals-1118] run_command 里的「打开网址 / 打开已装程序」改走 open_url / open_app（同一套检查，低风险的直接做）
            Object[] rw = LocalLaunch.rewrite(cmd, args);
            if (rw != null) {
                cmd = (String) rw[0];
                @SuppressWarnings("unchecked") Map<String, Object> na = (Map<String, Object>) rw[1];
                args = na;
            }
            PcGuard.Outcome o = guard.check(ChatAgent.FROM, cmd, args);
            if (o.reply != null) {
                r = o.reply;
                log.accept("对话没执行 " + cmd + "：" + (r.data == null ? "" : r.data.get("reason")));
            } else if (o.pass == null || !o.pass.consume(cmd, o.args)) {
                r = Actions.Result.fail("没有通过审批，不执行");
            } else {
                log.accept("对话执行 " + cmd);
                Actions.Result b = guard.builtin(cmd, o.args);
                r = b != null ? b : actions.run(cmd, o.args);
                if (r != null && r.ok) guard.noteCreated(o.creates); // [approvals-1118] 对话自己建的，之后改 / 删不用问
            }
        } catch (SafeExec.Refused t) {
            Map<String, Object> d = new TreeMap<>();
            d.put("reason", LinkPolicy.R_PLUGIN_BLOCKED);
            d.put("reason_zh", LinkPolicy.reasonZh(LinkPolicy.R_PLUGIN_BLOCKED));
            r = new Actions.Result(false, t.getMessage(), d);
        } catch (Throwable t) {
            r = Actions.Result.fail(cmd + " 出错：" + t.getClass().getSimpleName());
        } finally {
            SafeExec.REMOTE.set(Boolean.FALSE);
            PcGuard.LOCAL.set(Boolean.FALSE);
        }
        return r;
    }


    /**
     * [overnight-1119] 对话的「下载文件」（net_download）：按等价的 Invoke-WebRequest 命令走原来的判定，放行后由 {@link Downloader} 自己下（任务卡有进度、可取消）。
     */
    Actions.Result netDownload(String from, Map<String, Object> args) {
        String url = String.valueOf(args.getOrDefault("url", "")).trim();
        String path = String.valueOf(args.getOrDefault("path", "")).trim();
        if (url.isEmpty() || path.isEmpty()) return Actions.Result.fail("要给网址和存到哪");
        java.nio.file.Path dest = java.nio.file.Paths.get(path);
        if (!dest.isAbsolute()) dest = cfg.workspace().resolve(path).normalize();
        Map<String, Object> eq = new TreeMap<>();
        eq.put("command", Downloader.equivalentCommand(url, dest.toString()));
        PcGuard.Outcome o = guard.check(from, "sys_exec", eq);
        if (o.reply != null) return o.reply;
        if (o.pass == null || !o.pass.consume("sys_exec", o.args)) return Actions.Result.fail("没有通过审批，不执行");
        log.accept("对话下载 " + LinkPolicy.oneLine(url));
        Object t = args.get("timeout_sec");
        Actions.Result r = Downloader.download(url, dest, t instanceof Number ? ((Number) t).intValue() : 1800, guard::redirectHostOk);
        if (r.ok) guard.noteCreated(o.creates);
        return r;
    }

    /**
     * [overnight-1119] 手机交给电脑的任务（pc_task）里，模型发起的每一步：按「这台手机发来的指令」走同一条远程安全链
     * （安全插件 → 两轴矩阵 / 白名单 → 确认框（电脑上，并发到这台手机的屏幕上）→ 一次性凭证），不享受本机对话的放宽。
     * 所以手机借电脑的模型做事，权限不会比手机直接下令大。
     */
    public Actions.Result runAsPeer(String from, String cmd, Map<String, Object> args) {
        Actions.Result r;
        if (from == null || from.isEmpty() || !cfg.peerAllowed(from)) return Actions.Result.fail("这台手机没有被允许下令");
        SafeExec.REMOTE.set(Boolean.TRUE);
        PcGuard.LOCAL.set(Boolean.FALSE);
        try {
            if ("net_download".equals(cmd)) return netDownload(from, args);
            Object[] rw = LocalLaunch.rewrite(cmd, args);
            if (rw != null) {
                cmd = (String) rw[0];
                @SuppressWarnings("unchecked") Map<String, Object> na = (Map<String, Object>) rw[1];
                args = na;
            }
            PcGuard.Outcome o = guard.check(from, cmd, new TreeMap<>(args));
            if (o.reply != null) {
                r = o.reply;
                log.accept("手机任务没执行 " + cmd + "：" + (r.data == null ? "" : r.data.get("reason")));
            } else if (o.pass == null || !o.pass.consume(cmd, o.args)) {
                r = Actions.Result.fail("没有通过审批，不执行");
            } else {
                log.accept("手机任务执行 " + cmd + "（来自 " + from + "）");
                Actions.Result b = guard.builtin(cmd, o.args);
                r = b != null ? b : actions.run(cmd, o.args);
            }
        } catch (SafeExec.Refused t) {
            Map<String, Object> d = new TreeMap<>();
            d.put("reason", LinkPolicy.R_PLUGIN_BLOCKED);
            d.put("reason_zh", LinkPolicy.reasonZh(LinkPolicy.R_PLUGIN_BLOCKED));
            r = new Actions.Result(false, t.getMessage(), d);
        } catch (Throwable t) {
            r = Actions.Result.fail(cmd + " 出错：" + t.getClass().getSimpleName());
        } finally {
            SafeExec.REMOTE.set(Boolean.FALSE);
        }
        noteRead(from, cmd, r);
        return r;
    }

    /**
     * [app-whitelist-1116] 这条指令把电脑上的内容交给了手机（读剪贴板、读文件、截图）：认出隐私就记进这台手机本次会话
     * （截图电脑认不出内容，按「认不出的图片」算隐私，宁可多问）。只记类别，不记内容。
     */
    void noteRead(String from, String cmd, Actions.Result r) {
        WhitelistStore w = guard.whitelist;
        if (w == null || r == null || !r.ok) return;
        try {
            java.util.Set<String> k = new java.util.LinkedHashSet<>();
            if ("media_get".equals(cmd) || "media_fetch".equals(cmd)) w.noteExternal(from); // 从网上拉过内容：之后外发白名单也确认（ActionPolicy L3e）
            if ("screenshot".equals(cmd)) k.addAll(PrivacyScan.image(null));
            else if ("clipboard_get".equals(cmd) && r.data != null) k.addAll(PrivacyScan.text(String.valueOf(r.data.getOrDefault("text", ""))));
            else if ("file_get".equals(cmd) && r.data != null) {
                String name = String.valueOf(r.data.getOrDefault("name", "")).toLowerCase(java.util.Locale.ROOT);
                Object b = r.data.get("b64");
                if (name.matches(".*\\.(png|jpe?g|gif|bmp|webp|heic|tiff?)$")) k.addAll(PrivacyScan.image(null));
                else if (b != null) {
                    byte[] raw = java.util.Base64.getDecoder().decode(String.valueOf(b));
                    k.addAll(PrivacyScan.text(new String(raw, 0, Math.min(raw.length, 512 * 1024), java.nio.charset.StandardCharsets.UTF_8)));
                    if (name.matches(".*\\.(pdf|docx?|xlsx?|zip|rar|7z)$")) k.add(PrivacyScan.IMAGE_UNKNOWN); // 看不了内容的文件：当认不出
                }
            }
            if (!k.isEmpty()) {
                w.noteRead(from, k);
                log.accept("本次会话读到了隐私（" + PrivacyScan.zh(k) + "），之后白名单程序的外发也要确认");
            }
        } catch (RuntimeException ignored) {
            // 判不了：不影响这条指令本身
        }
    }

    // ------------------------------------------------------------------ 电脑上的确认也发到手机（手机屏幕上能批）

    /**
     * 把一条确认发给发令的那台手机（常连通道 / 中转）：签名的 confirm_offer，带一次性 token。手机界面上真人点了才回 confirm_answer；
     * 手机不在线就不发（照样只在电脑上等）。
     */
    boolean offerToPhone(PcGuard.PhoneOffer o) {
        if (o == null || o.from == null || o.from.isEmpty() || ChatAgent.FROM.equals(o.from) || !cfg.peerAllowed(o.from)) return false; // 本机对话的确认只在电脑上点
        Link via = route(o.from);
        if (via == null) return false;
        Map<String, Object> b = new TreeMap<>();
        b.put("kind", "confirm_offer");
        b.put("cid", (long) o.id);
        b.put("token", o.token);
        b.put("cmd", o.cmd);
        b.put("detail", o.detail);
        b.put("always", o.always);
        b.put("task_scope", o.task); // [rel-1123 合并] 手机卡上也给「允许本次任务」
        b.put("remember_kind", o.rememberKind);
        b.put("remember_zh", o.rememberKind.isEmpty() ? "" : LinkPolicy.kindZh(o.rememberKind));
        b.put("timeout_ms", Math.max(0, o.deadline - System.currentTimeMillis()));
        String mid = Protocol.newId();
        via.send(Protocol.envelope(o.from, mid, Protocol.seal(cfg.pairKey, mid, cfg.deviceId, o.from, b, System.currentTimeMillis())));
        log.accept("确认也发到了手机 " + o.from + "（手机屏幕上真人点击也能批）");
        return true;
    }

    /** 这条确认结束了：叫手机把卡收起来（尽力而为）。 */
    void closeOnPhone(PcGuard.PhoneOffer o) {
        Link via = o == null ? null : route(o.from);
        if (via == null) return;
        Map<String, Object> b = new TreeMap<>();
        b.put("kind", "confirm_close");
        b.put("cid", (long) o.id);
        String mid = Protocol.newId();
        via.send(Protocol.envelope(o.from, mid, Protocol.seal(cfg.pairKey, mid, cfg.deviceId, o.from, b, System.currentTimeMillis())));
    }

    /** 手机确认卡的回答：验签 + 时间窗 + 一次性编号 + 允许的设备，再交给 {@link PcGuard.Confirms#answerPhone}（规则在 ConfirmGate）。 */
    private void onConfirmAnswer(String from, String id, Map<String, Object> body) {
        Protocol.Check c = Protocol.verify(cfg.pairKey, id, from, cfg.deviceId, body, System.currentTimeMillis(), replay);
        if (c != Protocol.Check.OK || !cfg.peerAllowed(from)) {
            log.accept("手机 " + from + " 的确认回答没通过校验：" + (c != Protocol.Check.OK ? c : "设备未被允许"));
            return;
        }
        Object cid = body.get("cid"), shown = body.get("shown_ms");
        if (!(cid instanceof Number)) return;
        ConfirmGate.Answer a = confirms.answerPhone(from, ((Number) cid).intValue(), String.valueOf(body.get("token")), Boolean.TRUE.equals(body.get("allow")),
                Boolean.TRUE.equals(body.get("always")), Boolean.TRUE.equals(body.get("remember")), "task".equals(body.get("scope")) ? "task" : "", String.valueOf(body.get("input")),
                shown instanceof Number ? ((Number) shown).longValue() : 0L);
        boolean allow = Boolean.TRUE.equals(body.get("allow"));
        if (a == ConfirmGate.Answer.ACCEPTED) log.accept("手机 " + from + " 的确认卡上" + (allow ? "允许" : "拒绝") + "了一条请求（真人触摸）");
        else if (a == ConfirmGate.Answer.NOT_LOCAL) log.accept("手机 " + from + " 回的「允许」不是手机界面上的真人触摸 / token 对不上，没算数：" + LinkPolicy.R_VOICE_APPROVAL_BLOCKED);
        else log.accept("手机 " + from + " 的确认回答没算数（" + a + "）");
    }

    // ------------------------------------------------------------------ 电脑 → 手机（协议 v2）

    private final Map<String, java.util.concurrent.CompletableFuture<Actions.Result>> pending = new ConcurrentHashMap<>();
    private final Map<String, String> pendingTo = new ConcurrentHashMap<>();
    private final Protocol.ReplayGuard resultReplay = new Protocol.ReplayGuard();

    @SuppressWarnings("unchecked")
    private void onResult(String from, String id, Map<String, Object> body) {
        String re = String.valueOf(body.get("re"));
        java.util.concurrent.CompletableFuture<Actions.Result> f = pending.get(re);
        if (f == null || !from.equals(pendingTo.get(re))) return; // 不是我们发出去的，或来源不对
        Protocol.Check c = Protocol.verify(cfg.pairKey, id, from, cfg.deviceId, body, System.currentTimeMillis(), resultReplay);
        if (c != Protocol.Check.OK) {
            log.accept("手机 " + from + " 的回复没通过校验：" + c);
            f.complete(Actions.Result.fail("手机的回复没通过校验（" + c + "）"));
            return;
        }
        Map<String, Object> data = body.get("data") instanceof Map ? (Map<String, Object>) body.get("data") : new TreeMap<>();
        if (data.get("level") != null) phoneLevel = String.valueOf(data.get("level"));
        String text = body.get("text") == null ? "" : String.valueOf(body.get("text"));
        f.complete(Boolean.TRUE.equals(body.get("ok")) ? Actions.Result.ok(text, data) : new Actions.Result(false, text, data));
    }

    /** 要下令的手机：指定的 → 已连着常连通道的 → 最近一台常连的。没有返回 null。 */
    public String phoneTarget(String wanted) {
        if (wanted != null && !wanted.isEmpty()) return wanted;
        for (Link l : links) if (l instanceof LanServer) for (String c : ((LanServer) l).clients()) if (((LanServer) l).held(c)) return c;
        return cfg.phoneLast.isEmpty() ? null : cfg.phoneLast;
    }

    /**
     * 电脑给手机下一条签名指令，等签名结果。手机没连着常连通道时先发 announce（组播 + 广播）叫它连回来，最多等 waitMs。
     * 离线不排队：等不到就直接失败。
     */
    public java.util.concurrent.CompletableFuture<Actions.Result> callPeer(String device, String cmd, Map<String, Object> args, long waitMs, long timeoutMs) {
        java.util.concurrent.CompletableFuture<Actions.Result> out = new java.util.concurrent.CompletableFuture<>();
        if (device == null || device.isEmpty()) { out.complete(Actions.Result.fail("还没有手机常连过这台电脑（手机上打开「电脑联动」里的「接受电脑指令」）")); return out; }
        Thread t = new Thread(() -> {
            Link via = route(device);
            if (via == null) {
                for (Link l : links) if (l instanceof LanServer) ((LanServer) l).announce();
                long end = System.currentTimeMillis() + waitMs;
                long nextAnn = System.currentTimeMillis() + 1500;
                while (via == null && System.currentTimeMillis() < end) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                    if (System.currentTimeMillis() >= nextAnn) {
                        for (Link l : links) if (l instanceof LanServer) ((LanServer) l).announce();
                        nextAnn = System.currentTimeMillis() + 1500;
                    }
                    via = route(device);
                }
            }
            if (via == null) {
                OfflineWhy.Why w = offlineWhy(); // [rel-1123-b] 离线写原因
                out.complete(Actions.Result.fail("手机离线（局域网里没连上 " + device + "），没发出去" + (w == null ? "" : "。" + w.text)));
                return;
            }
            String id = Protocol.newId();
            pending.put(id, out);
            pendingTo.put(id, device);
            Map<String, Object> body = Protocol.command(cfg.pairKey, id, cfg.deviceId, device, cmd, args == null ? new TreeMap<>() : args, System.currentTimeMillis());
            log.accept("向手机 " + device + " 下令 " + cmd + "（经" + via.name() + "）");
            via.send(Protocol.envelope(device, id, body)).whenComplete((x, e) -> {
                if (e != null) out.complete(Actions.Result.fail("发不出去：" + e.getClass().getSimpleName()));
            });
            try {
                out.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                out.complete(Actions.Result.fail("手机没有回应（超时）"));
            } finally {
                pending.remove(id);
                pendingTo.remove(id);
            }
        }, "onesay-callpeer");
        t.setDaemon(true);
        t.start();
        return out;
    }

    // ------------------------------------------------------------------ [rel-1123-b] 电脑交给手机的目标：结果回传

    /** 一次交给手机的目标（operate）的结果。 */
    public static final class Goal {
        public final String id, device, goal;
        public volatile String state = "running", text = "";
        public volatile List<Object> steps = List.of();
        public final long at;
        public volatile long endAt;

        Goal(String id, String device, String goal, long at) { this.id = id; this.device = device; this.goal = goal; this.at = at; }

        public boolean done() { return !"running".equals(state); }

        public boolean ok() { return "done".equals(state); }

        public Map<String, Object> view() {
            Map<String, Object> m = new TreeMap<>();
            m.put("id", id);
            m.put("device", device);
            m.put("goal", goal);
            m.put("state", state);
            m.put("text", text);
            m.put("steps", new ArrayList<>(steps));
            m.put("at", at);
            m.put("end_at", endAt);
            return m;
        }
    }

    private final Map<String, Goal> goals = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Goal>(16, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Goal> e) { return size() > 50; }
    });
    private final Map<String, java.util.concurrent.CompletableFuture<Goal>> goalWait = new ConcurrentHashMap<>();
    private final Protocol.ReplayGuard goalReplay = new Protocol.ReplayGuard();
    /** 结果回来了（App 接上：没人在等的就发一条电脑通知）。参数：结果、当时有没有人在等。 */
    public volatile java.util.function.BiConsumer<Goal, Boolean> onGoalDone;

    /** 手机收下目标后登记（id 是手机回的 goal_id）。 */
    public Goal goalStarted(String device, String gid, String goal) {
        Goal g = new Goal(gid, device, goal == null ? "" : goal, System.currentTimeMillis());
        goals.put(gid, g);
        return g;
    }

    /** 等这个目标的结果（已经回来了就马上完成）。 */
    public java.util.concurrent.CompletableFuture<Goal> goalFuture(String gid) {
        Goal g = goals.get(gid);
        java.util.concurrent.CompletableFuture<Goal> f = goalWait.computeIfAbsent(gid, k -> new java.util.concurrent.CompletableFuture<>());
        if (g != null && g.done()) f.complete(g);
        return f;
    }

    public void goalUnwait(String gid) { goalWait.remove(gid); }

    /** 最近一次交给手机的目标（没有返回 null）。 */
    public Goal lastGoal() {
        synchronized (goals) {
            Goal last = null;
            for (Goal g : goals.values()) last = g;
            return last;
        }
    }

    static final java.util.Set<String> GOAL_STATES = java.util.Set.of("done", "failed", "denied", "stopped", "timeout", "interrupted", "unknown");

    @SuppressWarnings("unchecked")
    private void onGoalResult(String from, String id, Map<String, Object> body) {
        Protocol.Check c = Protocol.verify(cfg.pairKey, id, from, cfg.deviceId, body, System.currentTimeMillis(), goalReplay);
        if (c != Protocol.Check.OK || !cfg.peerAllowed(from)) {
            log.accept("手机 " + from + " 回传的任务结果没通过校验：" + (c != Protocol.Check.OK ? c : "设备未被允许"));
            return;
        }
        String gid = String.valueOf(body.get("gid"));
        if (!gid.matches("[A-Za-z0-9_-]{4,64}")) return;
        String st = String.valueOf(body.get("state"));
        if (!GOAL_STATES.contains(st)) st = "failed";
        Goal g = goals.get(gid);
        if (g == null) { // 电脑重启过 / 等过了期：照样记下（手机自己说的目标）
            g = new Goal(gid, from, Notify.clip(String.valueOf(body.getOrDefault("goal", "")), 500), System.currentTimeMillis());
            goals.put(gid, g);
        } else if (!from.equals(g.device)) {
            log.accept("任务结果来自另一台手机 " + from + "，不认");
            return;
        }
        if (g.done()) return; // 重发的同一条
        g.text = Notify.clip(String.valueOf(body.getOrDefault("text", "")), 2000);
        Object steps = body.get("steps");
        if (steps instanceof List) {
            List<Object> l = new ArrayList<>();
            for (Object o : (List<Object>) steps) if (l.size() < 12) l.add(Notify.clip(String.valueOf(o), 120));
            g.steps = l;
        }
        g.endAt = System.currentTimeMillis();
        g.state = st;
        log.accept("手机 " + from + " 回传了任务结果：" + Humanize.goalState(st));
        java.util.concurrent.CompletableFuture<Goal> f = goalWait.get(gid);
        boolean waited = f != null && !f.isDone();
        if (f != null) f.complete(g);
        java.util.function.BiConsumer<Goal, Boolean> h = onGoalDone;
        if (h != null) try { h.accept(g, waited); } catch (RuntimeException ignored) { }
    }

    /** 能到这台设备的通道：局域网常连优先，其次中转。 */
    private Link route(String device) {
        for (Link l : links) if (l instanceof LanServer && ((LanServer) l).held(device)) return l;
        if (relayPeers.contains(device)) for (Link l : links) if (l instanceof RelayClient && l.isConnected()) return l;
        return null;
    }

    private void reply(Link via, String to, String re, boolean ok, String text, Map<String, Object> data, boolean unsigned) {
        String rid = Protocol.newId();
        Map<String, Object> body;
        if (unsigned) {
            body = new TreeMap<>();
            body.put("kind", "result");
            body.put("re", re);
            body.put("ok", false);
            body.put("text", text);
        } else {
            body = Protocol.result(cfg.pairKey, rid, cfg.deviceId, to, re, ok, text, data, System.currentTimeMillis());
        }
        via.send(Protocol.envelope(to, rid, body));
    }
}
