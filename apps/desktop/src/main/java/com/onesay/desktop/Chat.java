package com.onesay.desktop;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 「对话」：只有一个对话。先按规则认指令（锁屏、截图…）直接执行；认不出再交给大模型。
 * 上下文自动截断：只把最近、总字数不超过 CONTEXT_CHARS 的消息发给模型。
 * 记忆自动更新：每 MEMORY_EVERY 轮或发生截断后，让模型把旧记忆和最近对话合成新记忆（≤ MEMORY_CHARS 字）。
 * 两端互通：记忆带 updated_at，与手机同步时新者胜（见 Agent 的 memory_sync）。
 */
public final class Chat {
    public static final int CONTEXT_CHARS = 12000;
    public static final int MEMORY_CHARS = 1200;
    public static final int MEMORY_EVERY = 4;
    private final Config cfg;
    private final Store store;
    private final Actions actions;
    private final Supplier<List<String>> shortcuts;
    private final Consumer<String> log;
    private final AtomicBoolean memBusy = new AtomicBoolean();
    private int turnsSinceMemory = 0;
    /** 协议 v2：给手机下令（App 里接到 Agent.callPeer）；null = 没接。 */
    public volatile java.util.function.BiFunction<String, Map<String, Object>, Actions.Result> phone;
    private boolean truncatedSinceMemory = false;
    /**
     * [chat-agent-1118] 「对话」执行任务用的电脑能力（App 接到 {@link Agent#runLocal}：同一条安全链）。null = 没接（只能聊天）。
     */
    public volatile ChatAgent.Exec pcExec;
    /** 测试用：换掉大模型（null = 按设置连 {@link Llm}）。 */
    volatile ChatAgent.Brain brain;
    /** 正在跑的任务（界面「停止」用）。 */
    final ChatAgent agent = new ChatAgent();
    private volatile boolean running;
    /** [approvals-1118] 当前 / 上一个任务的 id 和任务卡消息 id（「继续」接着同一个任务、同一张卡）。 */
    volatile String taskId = "", taskCard = "";
    /** 新任务开始（App 接到 PcGuard.taskStarted：上一个任务的批准不带过来）。 */
    public volatile Consumer<String> onTaskStart = t -> { };
    /** [overnight-1119] 任务结束（至少执行过一步）：(状态, 标题 + 答复)，App 发「任务完成」通知。 */
    public volatile java.util.function.BiConsumer<String, String> onTaskEnd = (st, t) -> { };
    /** 任务进行中发来、还没交给模型的消息 id（先进先出，和 agent.inbox 对应）。 */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> queuedIds = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Object queueLock = new Object();
    /** [approvals-1118] 「本地应用 / 网页」选择（任务卡上点，不是审批）。 */
    public final OpenTarget.Choices choices = new OpenTarget.Choices();
    /** 名字 → 这台电脑上能打开的本地应用显示名（App 接到 LocalLaunch.displayName）；null = 没有。 */
    public volatile java.util.function.Function<String, String> appResolver = n -> null;
    /** 「继续」算接着上一件事：上一条助手回复是模型 / 任务的，且不超过这么久。 */
    static final long CONTINUE_WINDOW_MS = 60L * 60 * 1000;
    /** [phone-tools-1118] 现在连着的配对设备（设备 id → 通道名；App 接到 Agent.peers）和最近常连过的手机 id。 */
    public volatile Supplier<Map<String, String>> phones = TreeMap::new;
    public volatile Supplier<String> lastPhone = () -> "";
    /** 工作区、用户目录（写进系统提示）。 */
    volatile String workspace = "", home = System.getProperty("user.home", "");

    public Chat(Config cfg, Store store, Actions actions, Supplier<List<String>> shortcuts, Consumer<String> log) {
        this.cfg = cfg;
        this.store = store;
        this.actions = actions;
        this.shortcuts = shortcuts;
        this.log = log;
        agent.preset = () -> ChatPolicy.Preset.of(cfg.chatPreset);
        agent.progressGuard = true;   // [preview-1120 真机综合测试 2] 同类步骤空转防护
        agent.wrapUpOnStop = true;    // [preview-1120 真机综合测试 2] 被拒 / 空转停下时收尾：查到什么、用户自己怎么做
        agent.delivered = x -> {
            String id = queuedIds.poll();
            if (id != null) store.update("chat", id, Map.of("queued", false));
        };
    }

    private Map<String, Object> add(String role, String text, String via) {
        Map<String, Object> m = new TreeMap<>();
        m.put("role", role);
        m.put("text", text);
        m.put("at", System.currentTimeMillis());
        if (via != null) m.put("via", via);
        return store.add("chat", m, Store.MAX_CHAT);
    }

    /**
     * 处理一句话（打字或语音转来的），返回助手回复那一条。
     * [approvals-1118] 任务进行中发来的不再卡住：立刻记进对话（标「已收到，下一步处理」）并排进任务，下一步之前交给模型；「停」直接停。
     */
    public Map<String, Object> send(String text, String via) { return send(text, via, List.of()); }

    Store store() { return store; }

    // ------------------------------------------------------------------ [overnight-1119] 手机交给电脑的任务（pc_task）

    /** 发来任务的手机 → 这台手机身份下的执行（App 接到 {@link Agent#runAsPeer}）。 */
    public volatile java.util.function.Function<String, ChatAgent.Exec> peerExec;
    /** [overnight-1119] 正在跑的那一步的进度（下载 / 发文件；没有 = null）。 */
    public volatile Map<String, Object> live;

    /** 界面「取消」：停掉正在下的 / 正在传的（任务本身接着往下，模型会看到「已取消」）。 */
    public boolean cancelTransfer() {
        java.util.concurrent.atomic.AtomicBoolean t = stepCancel;
        if (live == null || t == null) return false;
        t.set(true); // [preview-1120 A-01] 只停这一步自己的，不碰界面上别的传输
        return true;
    }

    /** [preview-1120 AUDIT A-01] 正在跑的那一步自己的取消开关（下载 / 发文件 / 命令都看它）。 */
    volatile java.util.concurrent.atomic.AtomicBoolean stepCancel;

    /** [overnight-1119] 发文件到手机（App 接上）。 */
    public volatile FileXfer xfer;
    /** 正在跑的任务是哪台手机交来的（空 = 本机对话）。 */
    volatile String origin = "";
    /** 手机任务结束：(手机, 任务卡)。App 把结果推回手机。 */
    public volatile java.util.function.BiConsumer<String, Map<String, Object>> onPhoneTaskEnd = (p, c) -> { };

    /** 手机交来一个目标：立刻回「收到了」，后台按这台手机的权限做；做完把结果推回手机（手机也能用 pc_task_status 查）。 */
    public Actions.Result startPhoneTask(String goal, String from) {
        String g = goal == null ? "" : goal.trim();
        if (g.isEmpty()) return Actions.Result.fail("没说要电脑做什么");
        if (g.length() > 1000) g = g.substring(0, 1000);
        if (brain == null && !Llm.from(cfg).ready()) return Actions.Result.fail("电脑上的 OneSay 没配大模型（设置 › 模型与密钥），没法替手机做任务；单条指令（锁屏、截图、打开网址…）照常能用");
        if (peerExec == null) return Actions.Result.fail("电脑联动没接好");
        synchronized (queueLock) { if (running) return Actions.Result.fail("电脑正在做另一个任务，做完再发（或者先在电脑上点停止）"); }
        final String goalF = g;
        Thread t = new Thread(() -> {
            origin = from;
            try {
                Map<String, Object> card = handle(goalF, "phone", true);
                Map<String, Object> c = card == null ? null : store.find("chat", String.valueOf(card.get("id")));
                try { onPhoneTaskEnd.accept(from, c == null ? card : c); } catch (RuntimeException ignored) { }
            } finally {
                origin = "";
            }
        }, "onesay-phone-task");
        t.setDaemon(true);
        t.start();
        Map<String, Object> d = new TreeMap<>();
        d.put("goal", g);
        return Actions.Result.ok("电脑收到了，开始做：" + ChatAgent.clip(g, 60) + "。做完会把结果发回手机（也可以问「电脑任务做完了吗」）", d);
    }

    /** 这台手机最近交来的任务现在怎样。 */
    @SuppressWarnings("unchecked")
    public Actions.Result phoneTaskStatus(String from) {
        List<Map<String, Object>> all = store.list("chat");
        for (int i = all.size() - 1; i >= 0; i--) {
            Map<String, Object> m = all.get(i);
            if (!"task".equals(m.get("via")) || !String.valueOf(from).equals(m.get("origin"))) continue;
            return Actions.Result.ok(phoneTaskLine(m), phoneTaskData(m));
        }
        return Actions.Result.ok("这台手机还没交过任务给电脑", new TreeMap<>(Map.of("state", "none")));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> phoneTaskData(Map<String, Object> m) {
        Map<String, Object> d = new TreeMap<>();
        d.put("state", String.valueOf(m.getOrDefault("state", "")));
        d.put("state_zh", "running".equals(m.get("state")) ? "进行中" : Outcomes.zh(String.valueOf(m.getOrDefault("state", ""))));
        d.put("title", String.valueOf(m.getOrDefault("title", "")));
        d.put("answer", String.valueOf(m.getOrDefault("answer", "")));
        int n = 0;
        List<Object> lines = new ArrayList<>();
        if (m.get("steps") instanceof List) for (Object o : (List<Object>) m.get("steps")) {
            if (!(o instanceof Map) || !"step".equals(((Map<String, Object>) o).get("kind"))) continue;
            n++;
            Map<String, Object> x = (Map<String, Object>) o;
            String st = String.valueOf(x.getOrDefault("state", ""));
            if (lines.size() < 30) lines.add((st.equals("ok") ? "✓ " : st.equals("warn") ? "⚠ " : st.equals("fail") ? "✗ " : "… ") + x.getOrDefault("desc", ""));
        }
        d.put("steps", (long) n);
        d.put("step_lines", lines);
        return d;
    }

    static String phoneTaskLine(Map<String, Object> m) {
        String st = String.valueOf(m.getOrDefault("state", ""));
        String ans = String.valueOf(m.getOrDefault("answer", ""));
        if ("running".equals(st)) return "电脑还在做「" + m.getOrDefault("title", "") + "」";
        return "电脑任务「" + m.getOrDefault("title", "") + "」" + Outcomes.zh(st) + (ans.isBlank() ? "" : "：" + ChatAgent.clip(ans, 300));
    }

    // ------------------------------------------------------------------ [overnight-1119] 附件：粘贴截图 / 拖进来 / 附件按钮

    /** 单个附件最大（字节）。 */
    public static final int ATTACH_MAX = 10 * 1024 * 1024;
    /** 这次运行里收进来的附件（界面能按路径取回来显示）。 */
    final java.util.Set<String> attached = java.util.concurrent.ConcurrentHashMap.newKeySet();


    /** 收一个附件：存进工作区 inbox/，返回 {ok, path, kind(image|text|file), name}。 */
    public Map<String, Object> attach(String name, String b64) {
        Map<String, Object> out = new TreeMap<>();
        out.put("ok", false);
        byte[] data;
        try {
            data = java.util.Base64.getDecoder().decode(b64 == null ? "" : b64.replaceAll("^data:[^,]*,", ""));
        } catch (IllegalArgumentException e) {
            out.put("text", "附件读不出来");
            return out;
        }
        if (data.length == 0) { out.put("text", "附件是空的"); return out; }
        if (data.length > ATTACH_MAX) { out.put("text", "附件太大（超过 10 MB）"); return out; }
        String safe = (name == null || name.isBlank() ? "附件" : name).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (safe.length() > 80) safe = safe.substring(safe.length() - 80);
        if (!safe.contains(".") && isPng(data)) safe += ".png";
        try {
            Path dir = workspacePath().resolve("inbox");
            Files.createDirectories(dir);
            Path f = dir.resolve(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + "-" + safe);
            Files.write(f, data);
            attached.add(f.toString());
            out.put("ok", true);
            out.put("path", f.toString());
            out.put("name", safe);
            out.put("kind", isImage(f) ? "image" : isText(f) ? "text" : "file");
            out.put("size", (long) data.length);
        } catch (Exception e) {
            out.put("text", "附件存不进工作区：" + e.getClass().getSimpleName());
        }
        return out;
    }

    static boolean isPng(byte[] b) { return b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'; }

    /** 附件交给模型的说明：图片交看图模型描述（没配就 OCR 读文字），文本读内容（≤8000 字），其他只给路径。 */
    String attachNote(List<String> paths) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        for (String p : paths) {
            if (p == null || !attached.contains(p)) continue;
            Path f = java.nio.file.Paths.get(p);
            if (!Files.isRegularFile(f)) continue;
            i++;
            b.append("\n（用户附件 ").append(i).append("：").append(f.getFileName()).append("，已存到 ").append(f).append("）");
            try {
                if (isImage(f)) {
                    byte[] png = Files.readAllBytes(f);
                    Looker lk = null;
                    try { lk = looker.get(); } catch (RuntimeException ignored) { }
                    String seen = null, how;
                    if (lk != null) {
                        try { seen = lk.look(png, "用户把这张图发给你，可能是截图。"); how = "看图模型（" + lk + "）的描述"; }
                        catch (Exception e) { how = "看图模型没成功，改用 OCR"; }
                    } else how = "没配看图模型，下面是 OCR 读出的文字（只有文字，没有图标 / 颜色 / 布局）";
                    if (seen == null) {
                        try { String o = ocr.read(f); seen = o.isBlank() ? "（OCR 没读到文字）" : o; if (lk != null) how = "OCR 读出的文字"; }
                        catch (Exception e) { seen = "（看不了图：没配看图模型，系统 OCR 也不可用。可以请用户用文字说一下图里是什么）"; }
                    }
                    b.append("\n【").append(how).append("】\n").append(ChatAgent.clip(seen, 4000));
                } else if (isText(f) && Files.size(f) <= 512 * 1024) {
                    b.append("\n【文件内容】\n").append(ChatAgent.clip(new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8), 8000));
                } else {
                    b.append("（").append(Files.size(f)).append(" 字节，需要时用命令打开）");
                }
            } catch (Exception e) {
                b.append("（读不出来：").append(e.getClass().getSimpleName()).append("）");
            }
        }
        return b.toString();
    }

    public Map<String, Object> send(String text, String via, List<String> attachments) {
        text = text == null ? "" : text.trim();
        List<String> att = new ArrayList<>();
        if (attachments != null) for (String p : attachments) if (p != null && attached.contains(p) && att.size() < 6) att.add(p);
        if (text.isEmpty() && !att.isEmpty()) text = "（发来 " + att.size() + " 个附件）";
        if (!att.isEmpty()) {
            String note = attachNote(att);
            pendingAttach = new Object[] {att, note};
        }
        if (text.isEmpty()) return add("assistant", "没收到内容", null);
        if (text.length() > 4000) text = text.substring(0, 4000);
        synchronized (queueLock) {
            if (running) {
                Map<String, Object> u = new TreeMap<>();
                u.put("role", "user");
                u.put("text", text);
                u.put("at", System.currentTimeMillis());
                u.put("queued", true);
                if (via != null) u.put("via", via);
                String note = "";
                Object[] pa = pendingAttach;
                if (pa != null) { pendingAttach = null; putAttach(u, pa); note = String.valueOf(pa[1]); }
                Map<String, Object> m = store.add("chat", u, Store.MAX_CHAT);
                queuedIds.add(String.valueOf(m.get("id")));
                agent.inbox.add(text + note);
                if (ChatAgent.stopWord(text)) agent.cancel.set(true);
                return m;
            }
        }
        return handle(text, via, true);
    }

    /** 下一条用户消息带的附件（路径列表、给模型的说明）。 */
    volatile Object[] pendingAttach;

    @SuppressWarnings("unchecked")
    static void putAttach(Map<String, Object> u, Object[] pa) {
        List<String> img = new ArrayList<>(), oth = new ArrayList<>();
        for (String p : (List<String>) pa[0]) (isImage(java.nio.file.Paths.get(p)) ? img : oth).add(p);
        if (!img.isEmpty()) u.put("images", img);
        if (!oth.isEmpty()) u.put("files", oth);
        u.put("attach_note", pa[1]);
    }

    private synchronized Map<String, Object> handle(String text, String via, boolean addUser) {
        Object[] pa = pendingAttach;
        pendingAttach = null;
        if (addUser) {
            Map<String, Object> u = new TreeMap<>();
            u.put("role", "user");
            u.put("text", text);
            u.put("at", System.currentTimeMillis());
            if (via != null) u.put("via", via);
            if (pa != null) putAttach(u, pa);
            store.add("chat", u, Store.MAX_CHAT);
        }

        Llm llm0 = Llm.from(cfg);
        boolean modelOk = brain != null || llm0.ready();
        boolean tasks = modelOk && pcExec != null;
        // [chat-agent-1118] 「继续」接着上一件事（上一条助手回复是模型 / 任务的）：交给模型，不再被当成「播放 / 暂停」媒体键
        boolean cont = modelOk && Intent.isContinuation(text) && continuing();
        Intent it = cont || (pa != null && modelOk) || "phone".equals(via) ? null : Intent.parse(text, cfg.appMap(), shortcuts.get(), tasks); // 带附件的、手机交来的交给模型
        if (it == null) return answer(llm0, tasks, true);
        if (it.phoneCmd != null) return add("assistant", runPhone(it), "cmd");
        if (it.cmd != null) {
            String reply;
            try {
                Actions.Result r = actions.run(it.cmd, it.args);
                if (it.cmd.equals("screenshot") && r.ok && r.data != null) {
                    try { r = Voice.saveShot(r.data); } catch (Exception | Error e) { r = Actions.Result.fail("截到了，但存图或放剪贴板失败"); }
                }
                reply = r.ok ? it.say : r.text;
            } catch (Exception e) {
                reply = "出错：" + e.getClass().getSimpleName();
            }
            return add("assistant", reply, "cmd");
        }
        boolean understood = !(it.say.startsWith("没听懂") || it.say.startsWith("没听清"));
        if (!modelOk) return add("assistant", understood ? it.say : "没认出这是哪条指令。在设置里填上大模型 Key 后，什么都可以聊。", null);
        return answer(llm0, tasks, false);
    }

    /** 上一条助手回复是模型 / 任务的（不是固定指令、不是报错），且在 {@link #CONTINUE_WINDOW_MS} 以内。 */
    boolean continuing() {
        List<Map<String, Object>> all = store.list("chat");
        // [taskctx-1118] 有没做完的任务（停了 / 到时长 / 出错 / 没人批准…）：「继续」接着它，不受 1 小时窗口限制（一天以内）
        Map<String, Object> un = latestUnfinished(all);
        if (un != null) {
            Object at = un.get("updated_at") instanceof Number ? un.get("updated_at") : un.get("at");
            long t = at instanceof Number ? ((Number) at).longValue() : 0L;
            if (t > 0 && System.currentTimeMillis() - t <= 24L * 60 * 60 * 1000) return true;
        }
        for (int i = all.size() - 1; i >= 0; i--) {
            Map<String, Object> m = all.get(i);
            if (!"assistant".equals(m.get("role"))) continue;
            Object via = m.get("via");
            if ("cmd".equals(via)) return false;
            Object at = m.get("at");
            long t = at instanceof Number ? ((Number) at).longValue() : 0L;
            return t > 0 && System.currentTimeMillis() - t <= CONTINUE_WINDOW_MS;
        }
        return false;
    }

    /** 交给模型：能执行任务时走 {@link ChatAgent} 循环，否则纯聊天。 */
    private Map<String, Object> answer(Llm llm, boolean tasks, boolean cont) {
        try {
            String reply;
            if (tasks) {
                ChatAgent.Brain b = brain != null ? brain : (msgs, tl) -> {
                    try {
                        return llm.turn(msgs, tl, 0.3, 120);
                    } catch (Llm.ToolsUnsupported e) {
                        // 模型服务不支持工具调用：退回纯聊天，如实说明
                        log.accept("大模型不支持工具调用，退回纯聊天");
                        List<Map<String, Object>> m2 = new ArrayList<>(msgs.size());
                        for (Map<String, Object> x : msgs) if ("system".equals(x.get("role")) || ("user".equals(x.get("role"))) || ("assistant".equals(x.get("role")) && x.get("tool_calls") == null)) m2.add(x);
                        if (!m2.isEmpty() && "system".equals(m2.get(0).get("role"))) m2.set(0, Llm.msg("system", chatOnlyPrompt()));
                        return new Llm.Turn(llm.chat(m2, 0.7, 60) + "\n\n（当前模型服务不支持工具调用，这次没法替你在电脑上执行。换成 DeepSeek / 百炼等支持工具调用的模型后可以直接做。）", List.of(), Llm.msg("assistant", ""));
                    }
                };
                synchronized (queueLock) { running = true; }
                ChatAgent.Outcome o;
                String card = startTask(cont);
                agent.timeoutMs = Math.max(5, Math.min(24 * 60, cfg.chatTimeoutMin)) * 60_000L;
                PcGuard.TASK.set(taskId);
                List<String> leftovers = new ArrayList<>();
                try {
                    String org = origin;
                    ChatAgent.Exec pe = org == null || org.isEmpty() || peerExec == null ? pcExec : peerExec.apply(org); // [overnight-1119] 手机交来的：按这台手机的权限
                    ChatAgent.Exec ex = (cmd, args) -> {
                        // [overnight-1119] 下载 / 发文件的进度：只放内存（界面每次刷新读 progress），不每 300 毫秒写一次 state.json
                        Progress.SINK.set(pg -> { Map<String, Object> x = new TreeMap<>(pg); x.put("task", taskId); x.put("at", System.currentTimeMillis()); live = x; });
                        java.util.concurrent.atomic.AtomicBoolean tok = new java.util.concurrent.atomic.AtomicBoolean(agent.cancel.get());
                        stepCancel = tok;
                        Progress.TOKEN.set(tok);
                        try { return local(pe, cmd, args); } finally { Progress.SINK.remove(); Progress.TOKEN.remove(); stepCancel = null; live = null; }
                    };
                    o = agent.run(context(), b, ex, new CardSteps(card));
                } finally {
                    PcGuard.TASK.set("");
                    synchronized (queueLock) {
                        running = false;
                        for (String x; (x = agent.inbox.poll()) != null; ) leftovers.add(x);
                    }
                }
                log.accept("对话任务结束：" + o.status + "（" + o.steps + " 步）");
                reply = o.text;
                Map<String, Object> cardNow = store.find("chat", card);
                Object stl = cardNow == null ? null : cardNow.get("steps");
                if ("error".equals(o.status) && o.steps == 0 && (!(stl instanceof List) || ((List<?>) stl).isEmpty()) && leftovers.isEmpty()) {
                    store.remove("chat", card); // 第一轮模型就出错：一条报错消息，不留空任务卡
                    taskCard = "";
                    return add("assistant", reply, "error");
                }
                // [overnight-1119] 按目标判定：被挡 / 没做成不再标绿「完成」
                @SuppressWarnings("unchecked") List<Object> stepsNow = stl instanceof List ? (List<Object>) stl : List.of();
                Outcomes.TaskEnd te = Outcomes.judgeTask(o.status, reply, stepsNow);
                reply = Humanize.text(te.text.isEmpty() ? reply : te.text); // [overnight-1119] 不露内部能力名、修病句
                Map<String, Object> patch = new TreeMap<>();
                patch.put("state", te.state);
                patch.put("answer", reply);
                patch.put("ended_at", System.currentTimeMillis());
                store.update("chat", card, patch);
                if (o.steps > 0) try { onTaskEnd.accept(te.state, String.valueOf(cardNow == null ? "" : cardNow.getOrDefault("title", "")) + "\n" + reply); } catch (RuntimeException ignored) { } // [overnight-1119] 任务完成进通知
                try { compactOldCards(); } catch (RuntimeException e) { log.accept("压缩旧任务输出失败：" + e.getClass().getSimpleName()); }
                turnsSinceMemory++;
                if (brain == null) maybeUpdateMemory(llm);
                // 任务结束时还没交给模型的话：按顺序当新消息处理（不丢）
                if (!leftovers.isEmpty()) {
                    List<String> ids = new ArrayList<>();
                    for (int i = 0; i < leftovers.size(); i++) { String id = queuedIds.poll(); if (id != null) ids.add(id); }
                    new Thread(() -> {
                        for (int i = 0; i < leftovers.size(); i++) {
                            if (i < ids.size()) store.update("chat", ids.get(i), Map.of("queued", false));
                            if (ChatAgent.stopWord(leftovers.get(i))) continue;
                            handle(leftovers.get(i), "text", false);
                        }
                    }, "onesay-chat-queued").start();
                }
                return store.find("chat", card);
            } else {
                reply = llm.chat(context(), 0.7, 60);
            }
            Map<String, Object> m = add("assistant", reply, null);
            turnsSinceMemory++;
            if (brain == null) maybeUpdateMemory(llm);
            return m;
        } catch (Exception e) {
            return add("assistant", e.getMessage() == null ? "大模型出错" : e.getMessage(), "error");
        }
    }

    /**
     * open_target：本地应用和网页都有、又没有习惯时，在任务卡上问用户选哪个（可记住）；只有一种就直接开；原话说了网页 / 网站就开网页。
     * 打开本身走 open_app / open_url（同一条安全链，低风险的不弹审批卡）。
     */
    Actions.Result openTarget(ChatAgent.Exec pe, Map<String, Object> a) throws Exception {
        String name = String.valueOf(a.getOrDefault("name", "")).trim();
        String url = String.valueOf(a.getOrDefault("url", "")).trim();
        if (url.isEmpty()) url = OpenTarget.knownUrl(name);
        if (!url.isEmpty() && !url.matches("(?i)https?://\\S+")) url = "";
        String key = OpenTarget.key(name);
        String app = OpenTarget.findApp(name, appResolver);
        Map<String, Map<String, Object>> habits = OpenTarget.parseHabits(cfg.openHabits);
        String[] d = OpenTarget.decide(key, app, url, lastUserText(), String.valueOf(a.getOrDefault("prefer", "")), habits);
        String how = habits.containsKey(key) && !"ask".equals(d[0]) ? "，按你的习惯" : "";
        if ("ask".equals(d[0])) {
            Object[] r = choices.ask(taskId, name, app, url);
            String pick = (String) r[0];
            if (pick.isEmpty()) return Actions.Result.fail("用户没选用本地应用还是网页（2 分钟没点），没打开");
            if (Boolean.TRUE.equals(r[1])) rememberHabit(key, pick, app);
            d = pick.equals("app") ? new String[] {"app", app} : new String[] {"web", url};
            how = Boolean.TRUE.equals(r[1]) ? "，你选的（已记住）" : "，你选的";
        }
        if ("none".equals(d[0])) return Actions.Result.fail("这台电脑上没找到「" + name + "」的本地应用，也不知道它的网址；知道网址就用 open_url 打开");
        Map<String, Object> args = new TreeMap<>();
        Actions.Result r;
        if ("app".equals(d[0])) {
            args.put("name", d[1]);
            r = pe.run("open_app", args);
        } else {
            args.put("url", d[1]);
            r = pe.run("open_url", args);
        }
        if (r != null && r.ok) r = new Actions.Result(true, r.text + "（" + ("app".equals(d[0]) ? "本地应用" : "网页") + how + "）", r.data);
        return r;
    }

    /** 本机对话的一步：打开 / 手机 / 截屏 / 显示文件在这里做，其余走电脑执行链（PcGuard）。 */
    Actions.Result local(ChatAgent.Exec pe, String cmd, Map<String, Object> args) throws Exception {
        if (origin != null && !origin.isEmpty()) {
            // [overnight-1119] 手机交来的任务：选择卡弹在电脑上手机看不到；安卓界面点按不走远程安全链 → 不做
            if ("ask_choice".equals(cmd)) return new Actions.Result(false, "这个任务是手机交来的，电脑上弹选择卡手机看不到：把 2–3 个候选写进答复、标【需要你操作】，让用户在手机上回话选哪个",
                    new TreeMap<>(Map.of("reason", "choice_remote")));
            if ("android_ui".equals(cmd)) return Actions.Result.fail("手机交来的任务不能替你点安卓界面（在电脑上直接说就行）");
        }
        if ("open_target".equals(cmd)) return openTarget(pe, args);
        if (cmd.startsWith("phone_")) return phoneTool(cmd, args);
        if ("screen_look".equals(cmd)) return screenLook(args);
        if ("show_file".equals(cmd)) return showFile(String.valueOf(args.get("path")));
        if ("ask_choice".equals(cmd)) return askChoice(args);
        if ("android_ui".equals(cmd)) return androidUi(args);
        Actions.Result r = pe.run(cmd, args);
        if ("file_write".equals(cmd) && r != null && r.ok) {
            // [devtools-1118] 写好的文件在任务卡里能点开看
            Path f = resolve(String.valueOf(args.get("path")));
            if (f != null && Files.isRegularFile(f)) {
                Map<String, Object> d = new TreeMap<>();
                if (r.data != null) d.putAll(r.data);
                d.put(isImage(f) ? "image" : "file", f.toString());
                r = new Actions.Result(true, r.text, d);
            }
        }
        return r;
    }

    /** [overnight-1119] 选择卡：在任务卡上列候选 +「都不是，我来补充」，等用户点。 */
    @SuppressWarnings("unchecked")
    Actions.Result askChoice(Map<String, Object> a) {
        List<Map<String, Object>> opts = new ArrayList<>();
        if (a.get("options") instanceof List) for (Object o : (List<Object>) a.get("options")) if (o instanceof Map) opts.add((Map<String, Object>) o);
        if (opts.isEmpty()) return Actions.Result.fail("没有候选");
        String pick = choices.askOptions(taskId, String.valueOf(a.getOrDefault("question", "")), opts);
        Map<String, Object> d = new TreeMap<>();
        if (pick.matches("o\\d")) {
            Map<String, Object> o = opts.get(Integer.parseInt(pick.substring(1)));
            d.put("picked", o.get("title"));
            return Actions.Result.ok("用户选了：" + o.get("title") + (String.valueOf(o.getOrDefault("detail", "")).isBlank() ? "" : "（" + o.get("detail") + "）") + "。就按这个做。", d);
        }
        d.put("reason", pick.equals("other") ? "choice_other" : "choice_timeout");
        return new Actions.Result(false, pick.equals("other") ? "用户说候选里都不是，要自己补充" : "选择卡没人选", d);
    }

    // ------------------------------------------------------------------ [devtools-1118] 截屏 / 看图 / 显示文件

    /** 截屏（测试换）：target = pc / android / adb 序列号 → PNG 字节和说明（[0]=byte[]，[1]=来源名）。 */
    public interface Shooter { Object[] shoot(String target) throws Exception; }

    /** 看图（测试换）：配了看图模型时用；返回 null 表示没配。 */
    public interface Looker { String look(byte[] png, String question) throws Exception; }

    public interface Reader { String read(Path png) throws Exception; }

    volatile Shooter shooter = this::shootReal;
    volatile java.util.function.Supplier<Looker> looker = this::realLooker;

    Looker realLooker() {
        DeviceTools.Vision v = DeviceTools.Vision.from(cfg);
        return v == null ? null : new Looker() {
            @Override public String look(byte[] png, String q) throws Exception { return v.look(png, q); }
            @Override public String toString() { return v.model; }
        };
    }
    volatile Reader ocr = DeviceTools::ocr;

    Path workspacePath() {
        String ws = workspace == null || workspace.isEmpty() ? String.valueOf(cfg.workspace()) : workspace;
        return java.nio.file.Paths.get(ws);
    }

    String adb() {
        return DeviceTools.findAdb(cfg.adbPath, System.getenv());
    }

    Object[] shootReal(String target) throws Exception {
        String t = target == null || target.isBlank() ? "pc" : target.trim();
        if (t.equalsIgnoreCase("pc") || t.equals("电脑")) return new Object[] {DeviceTools.png(DeviceTools.capturePc()), "pc"};
        String adb = adb();
        if (adb == null) throw new Exception("这台电脑上没找到 adb（可以在设置里填 adb 的完整路径，MuMu 的在安装目录 shell\\adb.exe）");
        Map<String, String> devs = DeviceTools.devices(adb);
        String serial = DeviceTools.pickSerial(devs, t);
        if (serial == null) throw new Exception(devs.isEmpty() ? "adb 没连着任何安卓设备（MuMu 模拟器是 127.0.0.1:16384）" : "连着好几台设备，指定一个序列号：" + devs);
        String st = DeviceTools.ensureDevice(adb, serial);
        if (!"device".equals(st)) throw new Exception("设备 " + serial + (st == null ? " 没连上" : " 状态是 " + st + (st.equals("unauthorized") ? "（要在设备上允许 USB 调试）" : "")));
        return new Object[] {DeviceTools.screencap(adb, serial), serial};
    }

    Actions.Result screenLook(Map<String, Object> a) {
        String target = String.valueOf(a.getOrDefault("target", "pc"));
        String q = a.get("question") == null ? "" : String.valueOf(a.get("question"));
        String goal = !q.isBlank() ? q : a.get("goal") == null ? "" : String.valueOf(a.get("goal")); // [overnight-1119] 核对用的目标
        byte[] png;
        String who;
        try {
            Object[] s = shooter.shoot(target);
            png = (byte[]) s[0];
            who = String.valueOf(s[1]);
        } catch (Exception e) {
            return Actions.Result.fail("没截到屏：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        Path f;
        try {
            f = DeviceTools.saveShot(workspacePath(), "pc".equals(who) ? "pc" : who, png);
        } catch (Exception e) {
            return Actions.Result.fail("截到了，但存不进工作区：" + e.getClass().getSimpleName());
        }
        String size = "";
        try {
            java.awt.image.BufferedImage im = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            if (im != null) size = im.getWidth() + "×" + im.getHeight();
        } catch (Exception ignored) { }
        String how, seen, note = "";
        Looker lk = null;
        try { lk = looker.get(); } catch (RuntimeException ignored) { }
        if (lk != null) {
            try {
                seen = lk.look(png, q + ShotCheck.visionAsk(goal));
                how = "看图模型（" + lk + "）的描述";
            } catch (Exception e) {
                note = "看图模型没成功（" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "），改用 OCR。";
                lk = null;
                seen = null;
                how = null;
            }
        } else {
            seen = null;
            how = null;
        }
        if (lk == null) {
            try {
                seen = ocr.read(f);
                how = "OCR 读出的文字（Windows 自带文字识别：只有文字，没有图标 / 颜色 / 布局，没配看图模型）";
                if (seen.isBlank()) seen = "（OCR 没读到文字）";
            } catch (Exception e) {
                how = "没看图";
                seen = "没配看图模型，系统 OCR 也不可用（" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "）。图已存好显示在任务卡里，可以请用户看一眼。";
            }
        }
        // [overnight-1119] 核对画面是不是目标：不符合就明说，步骤标黄，不许当证据
        ShotCheck.Result chk = goal.isBlank() ? null : lk != null ? ShotCheck.fromVision(seen) : "没看图".equals(how) ? new ShotCheck.Result(ShotCheck.Verdict.UNKNOWN, "没有看图模型，OCR 也不可用") : ShotCheck.fromOcr(goal, seen);
        Map<String, Object> d = new TreeMap<>();
        d.put("image", f.toString());
        d.put("how", how);
        d.put("output", "【" + how + "】" + note + "\n" + seen + (chk == null ? "" : "\n【" + ShotCheck.line(chk) + "】"));
        String head = "截好了（" + ("pc".equals(who) ? "这台电脑" : who) + (size.isEmpty() ? "" : "，" + size) + "），" + how.replaceAll("（.*", "");
        if (chk != null) {
            d.put("verified", chk.id());
            if (chk.verdict == ShotCheck.Verdict.MISMATCH) {
                d.put("warn", "截到的不是目标画面" + (chk.why.isEmpty() ? "" : "：" + chk.why));
                return Actions.Result.ok(head + "。但截到的不是目标画面" + (chk.why.isEmpty() ? "" : "（" + chk.why + "）") + "，不能当证据", d);
            }
            if (chk.verdict == ShotCheck.Verdict.UNKNOWN) d.put("note", "没核对上画面是不是目标" + (chk.why.isEmpty() ? "" : "：" + chk.why));
            else head += "，核对一致";
        }
        return Actions.Result.ok(head, d);
    }

    // ------------------------------------------------------------------ [devtools-1118] 安卓界面（uiautomator）

    /** 安卓设备（测试换）。 */
    public interface Droid {
        /** 要用的序列号（没给就挑唯一在线的一台）；连不上抛错并说原因。 */
        String serial(String wanted) throws Exception;
        String dump(String serial) throws Exception;
        void tap(String serial, int x, int y) throws Exception;
    }

    volatile Droid droid = new Droid() {
        @Override public String serial(String wanted) throws Exception {
            String adb = adb();
            if (adb == null) throw new Exception("这台电脑上没找到 adb（可以在设置里填 adb 的完整路径，MuMu 的在安装目录 shell\\adb.exe）");
            Map<String, String> devs = DeviceTools.devices(adb);
            String s = DeviceTools.pickSerial(devs, wanted);
            if (s == null) throw new Exception(devs.isEmpty() ? "adb 没连着任何安卓设备（MuMu 模拟器是 127.0.0.1:16384）" : "连着好几台设备，用 device 指定一个：" + devs);
            String st = DeviceTools.ensureDevice(adb, s);
            if (!"device".equals(st)) throw new Exception("设备 " + s + (st == null ? " 没连上" : " 状态是 " + st));
            return s;
        }

        @Override public String dump(String serial) throws Exception { return DeviceTools.dumpUi(adb(), serial); }

        @Override public void tap(String serial, int x, int y) throws Exception {
            DeviceTools.Run r = DeviceTools.tap(adb(), serial, x, y);
            if (r.timedOut || r.exit != 0) throw new Exception("点不了：" + LinkPolicy.oneLine(r.text()));
        }
    };

    Actions.Result androidUi(Map<String, Object> a) {
        String act = String.valueOf(a.getOrDefault("action", "dump"));
        String text = String.valueOf(a.getOrDefault("text", "")).trim();
        String then = a.get("then_tap") == null ? "" : String.valueOf(a.get("then_tap")).trim();
        if (!"dump".equals(act) && ChatPolicy.Preset.of(cfg.chatPreset) == ChatPolicy.Preset.READ_ONLY)
            return Actions.Result.fail("现在的权限预设是「只读」，只能读界面、不点；要的话在对话框上方把预设改成「默认」。");
        Droid d = droid;
        String serial;
        List<DeviceTools.Node> nodes;
        try {
            serial = d.serial(a.get("device") == null ? "" : String.valueOf(a.get("device")));
            nodes = DeviceTools.parseUi(d.dump(serial));
        } catch (Exception e) {
            return Actions.Result.fail("读不到安卓界面：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        Map<String, Object> data = new TreeMap<>();
        data.put("device", serial);
        if ("dump".equals(act)) {
            data.put("output", DeviceTools.summarize(nodes, 80));
            return Actions.Result.ok("读到 " + serial + " 的界面", data);
        }
        try {
            List<String> did = new ArrayList<>();
            if ("check_text".equals(act)) {
                DeviceTools.Node label = DeviceTools.findByText(nodes, text);
                if (label == null) return notFound(text, nodes, data);
                DeviceTools.Node cb = DeviceTools.checkboxFor(nodes, label);
                if (cb == null) { data.put("output", DeviceTools.summarize(nodes, 60)); return new Actions.Result(false, "「" + text + "」旁边没找到复选框", data); }
                if (cb.checked) did.add("「" + text + "」本来就勾着");
                else {
                    d.tap(serial, cb.cx(), cb.cy());
                    did.add("勾上了「" + text + "」（点 " + cb.cx() + "," + cb.cy() + "）");
                    try {
                        nodes = DeviceTools.parseUi(d.dump(serial));
                        DeviceTools.Node l2 = DeviceTools.findByText(nodes, text);
                        DeviceTools.Node c2 = l2 == null ? null : DeviceTools.checkboxFor(nodes, l2);
                        if (c2 != null && !c2.checked) return new Actions.Result(false, "点了「" + text + "」的复选框，但再读界面它还是没勾上", data);
                    } catch (Exception ignored) { } // 再读不到（比如弹窗已经变了）不算失败
                }
                if (then.isEmpty()) { data.put("output", String.join("\n", did)); return new Actions.Result(true, String.join("；", did), data); }
                text = then;
            }
            DeviceTools.Node label = DeviceTools.findByText(nodes, text);
            if (label == null) { if (!did.isEmpty()) data.put("done_before", String.join("；", did)); return notFound(text, nodes, data); }
            if (DeviceTools.userMustTap(label.label())) return new Actions.Result(false, "「" + label.label() + "」这类按钮（付款 / 转账 / 删除 / 卸载…）不替用户点，请用户自己点", data);
            DeviceTools.Node t = DeviceTools.tapTarget(nodes, label);
            d.tap(serial, t.cx(), t.cy());
            did.add("点了「" + label.label() + "」（" + t.cx() + "," + t.cy() + "）");
            data.put("output", String.join("\n", did));
            return new Actions.Result(true, String.join("；", did), data);
        } catch (Exception e) {
            return new Actions.Result(false, "点按出错：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), data);
        }
    }

    static Actions.Result notFound(String text, List<DeviceTools.Node> nodes, Map<String, Object> data) {
        data.put("output", DeviceTools.summarize(nodes, 60));
        return new Actions.Result(false, "界面上没找到「" + text + "」（现在界面上有的见输出）", data);
    }

    static boolean isImage(Path f) {
        return f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).matches(".*\\.(png|jpe?g|gif|webp|bmp)");
    }

    static boolean isText(Path f) {
        return f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).matches(".*\\.(txt|log|md|json|csv|xml|ps1|bat|cmd|sh|py|js|html?|css|ini|cfg|conf|yaml|yml|properties|reg)");
    }

    /** 相对路径按工作区；~ 开头按用户目录。不合法返回 null。 */
    Path resolve(String p) {
        if (p == null || p.isBlank()) return null;
        try {
            String s = p.trim();
            if (s.startsWith("~")) s = home + s.substring(1);
            Path x = java.nio.file.Paths.get(s);
            return (x.isAbsolute() ? x : workspacePath().resolve(x)).normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 可以在任务卡里显示的位置：工作区、用户目录、临时目录。 */
    boolean viewable(Path f) {
        List<Path> roots = new ArrayList<>();
        roots.add(workspacePath().toAbsolutePath().normalize());
        if (home != null && !home.isBlank()) roots.add(java.nio.file.Paths.get(home).toAbsolutePath().normalize());
        roots.add(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize());
        Path a = f.toAbsolutePath().normalize();
        for (Path r : roots) if (a.startsWith(r)) return true;
        return false;
    }

    Actions.Result showFile(String p) {
        Path f = resolve(p);
        if (f == null || !Files.isRegularFile(f)) return Actions.Result.fail("没有这个文件：" + p);
        if (!viewable(f)) return Actions.Result.fail("只显示工作区、用户目录和临时目录里的文件");
        if (!isImage(f) && !isText(f)) return Actions.Result.fail("这种文件没法在任务卡里预览（图片或文本才行）：" + f.getFileName());
        Map<String, Object> d = new TreeMap<>();
        d.put(isImage(f) ? "image" : "file", f.toString());
        return Actions.Result.ok("已在任务卡里显示：" + f.getFileName(), d);
    }

    /**
     * 界面取任务卡里的图片 / 文件（本机口令保护）。只给对话里某一步登记过的路径（截图、写好的文件、show_file），别的路径一律不给。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> artifact(String path) {
        Map<String, Object> out = new TreeMap<>();
        boolean listed = false;
        if (path != null && !path.isBlank()) for (Map<String, Object> m : store.list("chat")) {
            Object st = m.get("steps");
            if (!(st instanceof List)) continue;
            for (Object o : (List<Object>) st) if (o instanceof Map && (path.equals(((Map<String, Object>) o).get("image")) || path.equals(((Map<String, Object>) o).get("file")))) { listed = true; break; }
            if (listed) break;
        }
        out.put("ok", false);
        if (!listed && path != null) for (Map<String, Object> m : store.list("chat")) {
            for (String k : new String[] {"images", "files"}) if (m.get(k) instanceof List && ((List<Object>) m.get(k)).contains(path)) { listed = true; break; }
            if (listed) break;
        }
        if (!listed && path != null && attached.contains(path)) listed = true;
        if (!listed) { out.put("text", "这个文件不在对话记录里"); return out; }
        Path f = java.nio.file.Paths.get(path);
        try {
            if (!Files.isRegularFile(f)) { out.put("text", "文件已经不在了"); return out; }
            long n = Files.size(f);
            if (isImage(f)) {
                if (n > 20L * 1024 * 1024) { out.put("text", "图片太大（超过 20 MB）"); return out; }
                String ext = f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).replaceAll(".*\\.", "");
                out.put("mime", "image/" + (ext.equals("jpg") ? "jpeg" : ext));
                out.put("b64", java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(f)));
            } else {
                if (n > 512 * 1024) { out.put("text", "文本太长（超过 512 KB），在文件夹里打开看"); return out; }
                out.put("text_body", new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8));
            }
            out.put("name", f.getFileName().toString());
            out.put("ok", true);
        } catch (Exception e) {
            out.put("text", "读不出来：" + e.getClass().getSimpleName());
        }
        return out;
    }

    static final String PHONE_HOWTO = "连手机的办法：在手机 OneSay 的「控制台 › 电脑联动」里打开「接受电脑指令」，用「扫码配对」扫电脑 OneSay「设置 › 连接」里的配对二维码；"
            + "配好后手机和电脑在同一个局域网（或都开了中转）就会自动连上。要让电脑把目标交给手机去做，还要在手机「电脑联动」里打开「按目标操作手机」这一项。";

    /** 现在连着的手机（没有返回空表）。 */
    Map<String, String> phonesNow() {
        try {
            Map<String, String> m = phones.get();
            return m == null ? Map.of() : m;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /**
     * [phone-tools-1118] 对话里的手机工具：phone_status / phone_open_app / phone_operate 走已配对的手机联动（协议 v2，签名指令），
     * 手机上的逐项开关和确认卡照旧（支付类 App、打电话、发短信、付款、给别人发消息由手机弹卡）。
     */
    Actions.Result phoneTool(String cmd, Map<String, Object> a) {
        java.util.function.BiFunction<String, Map<String, Object>, Actions.Result> p = phone;
        if (p == null) return Actions.Result.fail("手机联动没接上（这台电脑上 OneSay 的手机联动没启动）。" + PHONE_HOWTO);
        Map<String, String> on = phonesNow();
        String last = "";
        try { last = String.valueOf(lastPhone.get()); } catch (RuntimeException ignored) { }
        if (on.isEmpty() && (last == null || last.isBlank() || "null".equals(last))) return Actions.Result.fail("现在没有手机连着这台电脑。" + PHONE_HOWTO);
        if (!"phone_status".equals(cmd) && ChatPolicy.Preset.of(cfg.chatPreset) == ChatPolicy.Preset.READ_ONLY)
            return Actions.Result.fail("现在的权限预设是「只读」，不让手机做动作；要的话在对话框上方把预设改成「默认」。");
        try {
            Actions.Result r;
            switch (cmd) {
                case "phone_status": {
                    r = p.apply("status", new TreeMap<>());
                    if (!r.ok) return offline(r);
                    Actions.Result caps = p.apply("caps", new TreeMap<>());
                    StringBuilder b = new StringBuilder(statusLine(r));
                    Map<String, Object> d = new TreeMap<>();
                    if (r.data != null) d.putAll(r.data);
                    if (caps.ok && caps.data != null && caps.data.get("caps") instanceof List) {
                        // [overnight-1119] 只给中文名（不露 ping / caps / find_phone 这类内部名）；区分「开关没开」和「手机版本太旧」
                        List<?> l = (List<?>) caps.data.get("caps");
                        List<?> known = caps.data.get("known") instanceof List ? (List<?>) caps.data.get("known") : List.of();
                        List<String> zh = Humanize.phoneCaps(l);
                        b.append("。手机上开着的：").append(zh.isEmpty() ? "（一项都没开）" : String.join("、", zh));
                        String os = Humanize.operateState(l, known);
                        if (!os.equals("on")) b.append("。").append(Humanize.operateHint(os));
                        d.put("caps_zh", zh);
                        d.put("operate", os);
                    }
                    d.put("phones", new TreeMap<>(on));
                    return Actions.Result.ok(b.toString(), d);
                }
                case "phone_open_app": {
                    Map<String, Object> x = new TreeMap<>(a);
                    if (PhoneCmds.check("open_app", x) != null) return Actions.Result.fail(PhoneCmds.check("open_app", x));
                    r = p.apply("open_app", x);
                    return r.ok ? r : offline(r, "open_app");
                }
                case "phone_send_file": {
                    // [overnight-1119] 发电脑上的文件到手机（分块，手机放在「下载 / OneSay」）
                    Path f = resolve(String.valueOf(a.getOrDefault("path", "")));
                    if (f == null || !Files.isRegularFile(f)) return Actions.Result.fail("电脑上没有这个文件：" + a.getOrDefault("path", ""));
                    FileXfer x = xfer;
                    if (x == null) return Actions.Result.fail("文件互传没接上");
                    String to = on.isEmpty() ? String.valueOf(last) : on.keySet().iterator().next();
                    Actions.Result sr = x.send(f.toFile(), to, (c2, a2) -> p.apply(c2, a2));
                    return sr.ok ? sr : offline(sr, "file_put");
                }
                case "phone_shortcuts": {
                    r = p.apply("shortcut_list", new TreeMap<>());
                    return r.ok ? r : offline(r, "shortcut_list");
                }
                case "phone_run_shortcut": {
                    Map<String, Object> x = new TreeMap<>();
                    x.put("name", String.valueOf(a.getOrDefault("name", "")).trim());
                    if (String.valueOf(x.get("name")).isEmpty()) return Actions.Result.fail("要给快捷指令名");
                    r = p.apply("shortcut_run", x);
                    return r.ok ? r : offline(r, "shortcut_run");
                }
                case "phone_operate": {
                    Map<String, Object> x = new TreeMap<>();
                    x.put("goal", String.valueOf(a.getOrDefault("goal", "")).trim());
                    String bad = PhoneCmds.check("operate", x);
                    if (bad != null) return Actions.Result.fail(bad);
                    r = p.apply("operate", x);
                    return r.ok ? r : offline(r, "operate");
                }
                default:
                    return Actions.Result.fail("不认识的手机工具：" + cmd);
            }
        } catch (Exception e) {
            return Actions.Result.fail("手机出错：" + e.getClass().getSimpleName());
        }
    }

    static Actions.Result offline(Actions.Result r) { return offline(r, ""); }

    /** [overnight-1119] 手机回的失败 → 人话 + 下一步（开关没开 / 手机版本太旧 / 不在线）。 */
    static Actions.Result offline(Actions.Result r, String cmd) {
        String t = Humanize.phoneFailure(PhoneCmds.withReason(r), cmd);
        if (t.contains("没有回应") || t.contains("还没有手机常连") || t.contains("离线")) t = t + "。" + PHONE_HOWTO;
        return new Actions.Result(false, t, r.data);
    }

    /** [phone-tools-1118] 写进系统提示的运行环境：连着哪些手机；没有就写怎么连。 */
    String runtimeLines() {
        StringBuilder b = new StringBuilder("\n\n运行环境（每轮更新）：\n");
        Map<String, String> on = phonesNow();
        if (!on.isEmpty()) {
            b.append("- 已配对、现在连着的手机 / 设备：");
            List<String> l = new ArrayList<>();
            for (Map.Entry<String, String> e : on.entrySet()) l.add(e.getKey() + "（" + e.getValue() + "）");
            b.append(String.join("、", l)).append("。用 phone_status / phone_open_app / phone_operate 操作。\n");
        } else {
            String last = "";
            try { last = String.valueOf(lastPhone.get()); } catch (RuntimeException ignored) { }
            boolean known = last != null && !last.isBlank() && !"null".equals(last);
            b.append("- 现在没有手机连着这台电脑").append(known ? "（最近连过 " + last + "，调用手机工具时会叫它连回来，等几秒）" : "").append("。用户要操作手机时，")
                    .append(known ? "先试 phone_status；连不上就" : "").append("告诉用户：").append(PHONE_HOWTO).append('\n');
        }
        return b.toString();
    }

    synchronized void rememberHabit(String key, String pick, String app) {
        Map<String, Map<String, Object>> h = OpenTarget.parseHabits(cfg.openHabits);
        Map<String, Object> m = new TreeMap<>();
        m.put("choice", pick);
        m.put("app", "app".equals(pick) && app != null ? app : "");
        m.put("at", System.currentTimeMillis());
        h.put(key, m);
        cfg.openHabits = OpenTarget.formatHabits(h);
        try { cfg.save(); } catch (Exception e) { log.accept("打开习惯没存上：" + e.getClass().getSimpleName()); }
    }

    String lastUserText() {
        List<Map<String, Object>> all = store.list("chat");
        for (int i = all.size() - 1; i >= 0; i--) if ("user".equals(all.get(i).get("role"))) return String.valueOf(all.get(i).get("text"));
        return "";
    }

    /** 开始（或「继续」接着）一个任务：返回任务卡消息 id。 */
    String startTask(boolean cont) {
        Map<String, Object> old = null;
        if (cont) {
            // [taskctx-1118] 「继续」接着最近一个没做完的任务；都做完了就接着最近那个（程序重启过也找得到）
            List<Map<String, Object>> all = store.list("chat");
            old = latestUnfinished(all);
            if (old == null && !taskCard.isEmpty()) old = store.find("chat", taskCard);
            if (old == null) old = latestTask(all);
        }
        if (old != null) {
            taskCard = String.valueOf(old.get("id"));
            Object tk = old.get("task");
            if (tk != null && !String.valueOf(tk).isEmpty() && !String.valueOf(tk).equals(taskId)) taskId = String.valueOf(tk);
            store.update("chat", taskCard, Map.of("state", "running", "answer", ""));
            return taskCard;
        }
        taskId = "t" + Long.toString(System.currentTimeMillis(), 36);
        try { onTaskStart.accept(taskId); } catch (RuntimeException ignored) { }
        String title = "";
        List<Map<String, Object>> all = store.list("chat");
        for (int i = all.size() - 1; i >= 0; i--) if ("user".equals(all.get(i).get("role"))) { title = String.valueOf(all.get(i).get("text")); break; }
        Map<String, Object> m = new TreeMap<>();
        m.put("role", "assistant");
        m.put("via", "task");
        m.put("task", taskId);
        m.put("title", title.length() > 60 ? title.substring(0, 60) + "…" : title);
        m.put("state", "running");
        if (origin != null && !origin.isEmpty()) { m.put("origin", origin); m.put("title", "来自手机：" + (title.length() > 54 ? title.substring(0, 54) + "…" : title)); } // [overnight-1119]
        m.put("steps", new ArrayList<Object>());
        m.put("text", "");
        m.put("at", System.currentTimeMillis());
        taskCard = String.valueOf(store.add("chat", m, Store.MAX_CHAT).get("id"));
        return taskCard;
    }

    /** 把一步步进展写进任务卡（界面每次刷新都读得到）。 */
    final class CardSteps implements ChatAgent.Steps {
        final String card;

        CardSteps(String card) { this.card = card; }

        @Override public void add(String text) { }

        @SuppressWarnings("unchecked")
        @Override public void step(Map<String, Object> ev) {
            Map<String, Object> c = store.find("chat", card);
            if (c == null) return;
            List<Object> steps = new ArrayList<>(c.get("steps") instanceof List ? (List<Object>) c.get("steps") : List.of());
            String phase = String.valueOf(ev.get("phase"));
            Map<String, Object> st = new TreeMap<>();
            if ("note".equals(phase)) {
                st.put("kind", "note");
                st.put("desc", ev.get("desc"));
                steps.add(st);
            } else if ("start".equals(phase)) {
                st.put("kind", "step");
                st.putAll(ev);
                st.put("state", "run");
                st.put("started", System.currentTimeMillis());
                steps.add(st);
            } else {
                // done：改最后一条同号的步骤
                for (int i = steps.size() - 1; i >= 0; i--) {
                    Map<String, Object> x = (Map<String, Object>) steps.get(i);
                    if ("step".equals(x.get("kind")) && String.valueOf(ev.get("n")).equals(String.valueOf(x.get("n")))) {
                        Map<String, Object> y = new TreeMap<>(x);
                        y.putAll(ev);
                        y.put("state", stepState(ev));
                        steps.set(i, y);
                        st = null;
                        break;
                    }
                }
                if (st != null) {
                    st.put("kind", "step");
                    st.putAll(ev);
                    st.put("state", stepState(ev));
                    steps.add(st);
                }
            }
            while (steps.size() > 200) steps.remove(0);
            store.update("chat", card, Map.of("steps", steps));
        }
    }

    /** [overnight-1119] 一步的状态：失败 fail（红）、成功但有警告 warn（黄）、成功 ok（绿）。 */
    static String stepState(Map<String, Object> ev) {
        if (!Boolean.TRUE.equals(ev.get("ok"))) return "fail";
        Object w = ev.get("warn");
        return w != null && !String.valueOf(w).isBlank() ? "warn" : "ok";
    }

    /** 任务卡里真实执行过的步骤，作为程序记录（用户角色、明确标注）。没有步骤返回空串。 */
    @SuppressWarnings("unchecked")
    static String cardRecord(Map<String, Object> m) {
        StringBuilder b = new StringBuilder();
        Object st = m.get("steps");
        int n = 0;
        if (st instanceof List) for (Object o : (List<Object>) st) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> x = (Map<String, Object>) o;
            if (!"step".equals(x.get("kind"))) continue;
            n++;
            String s = String.valueOf(x.getOrDefault("state", ""));
            b.append(n).append(". ").append(x.get("desc")).append(" —— ").append(x.get("detail")).append(" —— ")
                    .append(s.equals("ok") ? "成功" : s.equals("warn") ? "成功（有警告）" : s.equals("fail") ? "失败" : "没做完").append("：").append(x.getOrDefault("summary", "")).append('\n');
        }
        if (n == 0) return "";
        return "（OneSay 程序记录，不是用户说的话：任务「" + m.getOrDefault("title", "") + "」实际执行过的步骤，状态 " + m.getOrDefault("state", "") + "）\n" + b;
    }

    /** 任务卡给模型看的样子：每步一行（▶ 说明 ✓/✗ 结论）+ 最后的答复。 */
    @SuppressWarnings("unchecked")
    static String cardText(Map<String, Object> m) {
        StringBuilder b = new StringBuilder();
        Object st = m.get("steps");
        if (st instanceof List) for (Object o : (List<Object>) st) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> x = (Map<String, Object>) o;
            if (!"step".equals(x.get("kind"))) continue;
            b.append("▶ ").append(x.get("desc")).append("（").append(x.get("detail")).append("）");
            String s = String.valueOf(x.getOrDefault("state", ""));
            b.append(s.equals("ok") ? " ✓ " : s.equals("warn") ? " ⚠ " : s.equals("fail") ? " ✗ " : " … ").append(x.getOrDefault("summary", "")).append('\n');
        }
        Object a = m.get("answer");
        if (a != null && !String.valueOf(a).isBlank()) b.append(a);
        else b.append("（任务状态：").append(m.getOrDefault("state", "")).append("）");
        return b.toString();
    }

    /** 界面「停止」：正在跑的任务做完当前这一步就停。 */
    public boolean stop() {
        if (!running) return false;
        agent.cancel.set(true);
        java.util.concurrent.atomic.AtomicBoolean t = stepCancel; // [preview-1120 A-01/A-04] 下载 / 传输 / 正在跑的命令也停（只停这个任务自己的）
        if (t != null) t.set(true);
        choices.cancelAll();
        return true;
    }

    public boolean running() { return running; }

    /** 发给手机：剪贴板指令没带文字时取电脑剪贴板；status 把电量等拼成一句。 */
    String runPhone(Intent it) {
        java.util.function.BiFunction<String, Map<String, Object>, Actions.Result> p = phone;
        if (p == null) return "手机联动没接上";
        Map<String, Object> args = new TreeMap<>(it.args);
        try {
            if (it.phoneCmd.equals("clipboard_set") && !args.containsKey("text")) {
                Actions.Result c = actions.run("clipboard_get", Map.of());
                Object t = c.ok && c.data != null ? c.data.get("text") : null;
                if (t == null || String.valueOf(t).isEmpty()) return "电脑剪贴板里没有文字";
                args.put("text", String.valueOf(t));
            }
            Actions.Result r = p.apply(it.phoneCmd, args);
            if (!r.ok) return PhoneCmds.withReason(r);
            if (it.phoneCmd.equals("status")) return statusLine(r);
            return it.say.isEmpty() ? r.text : it.say;
        } catch (Exception e) {
            return "出错：" + e.getClass().getSimpleName();
        }
    }

    static String statusLine(Actions.Result r) {
        Map<String, Object> d = r.data == null ? Map.of() : r.data;
        StringBuilder b = new StringBuilder("手机");
        if (d.get("battery") != null) b.append("电量 ").append(d.get("battery")).append("%");
        if (Boolean.TRUE.equals(d.get("charging"))) b.append("，在充电");
        if (d.get("ringer") != null) b.append("，").append(d.get("ringer"));
        if (d.get("music_volume") != null) b.append("，媒体音量 ").append(d.get("music_volume")).append("%");
        if (Boolean.TRUE.equals(d.get("screen_on"))) b.append("，屏幕亮着");
        return b.length() > 2 ? b.toString() : r.text;
    }

    String systemPrompt() {
        if (pcExec != null) {
            String mem = String.valueOf(store.memory().getOrDefault("text", ""));
            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEEE", java.util.Locale.CHINA));
            String ws = workspace == null || workspace.isEmpty() ? String.valueOf(cfg.workspace()) : workspace;
            return ChatAgent.systemPrompt(now, ws, home, mem, ChatPolicy.Preset.of(cfg.chatPreset)) + runtimeLines();
        }
        return chatOnlyPrompt();
    }

    /** 没接电脑能力（或模型不支持工具调用）时的纯聊天提示。 */
    String chatOnlyPrompt() {
        String mem = String.valueOf(store.memory().getOrDefault("text", ""));
        return "你是 OneSay，运行在用户 Windows 电脑上的助手，和用户手机上的 OneSay 是同一个助手、共用记忆。"
                + "用简体中文回答，先给结论，简短直接。现在是 " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEEE", java.util.Locale.CHINA)) + "。"
                + "这次对话里你没有执行工具，不能替用户在电脑上执行命令；像锁屏、截图、音量、打开某个程序这类固定指令由程序自己识别执行，用户说了却没执行时，提示他换成更直接的说法。"
                + (mem.isBlank() ? "" : "\n\n关于用户的长期记忆（可能过时，以用户最新说法为准）：\n" + mem);
    }

    // ------------------------------------------------------------------ [taskctx-1118] 按任务分段的上下文

    /** 没做完的任务状态（running 是上次程序没正常收尾留下的）。 */
    static boolean unfinished(Map<String, Object> m) {
        String st = String.valueOf(m.getOrDefault("state", ""));
        return "task".equals(m.get("via")) && !Outcomes.finished(st) && !st.isEmpty();
    }

    static Map<String, Object> latestUnfinished(List<Map<String, Object>> all) {
        for (int i = all.size() - 1; i >= 0; i--) if ("task".equals(all.get(i).get("via"))) return unfinished(all.get(i)) ? all.get(i) : null;
        return null;
    }

    static Map<String, Object> latestTask(List<Map<String, Object>> all) {
        for (int i = all.size() - 1; i >= 0; i--) if ("task".equals(all.get(i).get("via"))) return all.get(i);
        return null;
    }

    /** 当前任务给模型的完整记录：每步一行（说明 —— 做了什么 —— 结果），最近几步的输出原样（截到 3000 字）。 */
    @SuppressWarnings("unchecked")
    static String fullRecord(Map<String, Object> m) {
        List<Map<String, Object>> steps = new ArrayList<>();
        Object st = m.get("steps");
        if (st instanceof List) for (Object o : (List<Object>) st) if (o instanceof Map && "step".equals(((Map<String, Object>) o).get("kind"))) steps.add((Map<String, Object>) o);
        if (steps.isEmpty()) return "";
        StringBuilder b = new StringBuilder("（OneSay 程序记录，不是用户说的话：当前任务「" + m.getOrDefault("title", "") + "」到目前为止实际执行过的步骤，状态 " + m.getOrDefault("state", "") + "。接着做时从最后一步往下，不要把做过的步骤原样再做一遍）\n");
        int from = Math.max(0, steps.size() - TASK_STEP_LINES);
        if (from > 0) b.append("（前面还有 ").append(from).append(" 步，略）\n");
        for (int i = from; i < steps.size(); i++) {
            Map<String, Object> x = steps.get(i);
            String s = String.valueOf(x.getOrDefault("state", ""));
            b.append(i + 1).append(". ").append(x.get("desc")).append(" —— ").append(x.get("detail")).append(" —— ")
                    .append(s.equals("ok") ? "成功" : s.equals("warn") ? "成功（有警告）" : s.equals("fail") ? "失败" : "没做完").append("：").append(x.getOrDefault("summary", "")).append('\n');
        }
        List<String> outs = new ArrayList<>();
        for (int i = steps.size() - 1; i >= 0 && outs.size() < TASK_OUTPUTS; i--) {
            String o = String.valueOf(steps.get(i).getOrDefault("output", "")).trim();
            if (!o.isEmpty()) outs.add(0, "[第 " + (i + 1) + " 步的输出]\n" + ChatAgent.clip(o, TASK_OUTPUT_CHARS));
        }
        if (!outs.isEmpty()) b.append("最近几步的输出（更早的只留结论，完整的在任务卡「详情」里）：\n").append(String.join("\n", outs)).append('\n');
        return b.toString();
    }

    static final int TASK_STEP_LINES = 40, TASK_OUTPUTS = 4, TASK_OUTPUT_CHARS = 3000, RELATED_TASKS = 3;

    /** 旧任务的一句摘要。 */
    @SuppressWarnings("unchecked")
    static String shortSummary(Map<String, Object> m) {
        int n = 0;
        if (m.get("steps") instanceof List) for (Object o : (List<Object>) m.get("steps")) if (o instanceof Map && "step".equals(((Map<String, Object>) o).get("kind"))) n++;
        String st = String.valueOf(m.getOrDefault("state", ""));
        String ans = LinkPolicy.oneLine(String.valueOf(m.getOrDefault("answer", "")));
        return "「" + m.getOrDefault("title", "") + "」" + (Outcomes.finished(st) ? "已完成" : "没做完（" + Outcomes.zh(st) + "）") + "，" + n + " 步" + (ans.isBlank() ? "" : "：" + ChatAgent.clip(ans, 200));
    }

    /** 两段文字的相似度（字的二元组 Jaccard，中文和英文数字都算）。 */
    static double related(String a, String b) {
        java.util.Set<String> x = grams(a), y = grams(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        int inter = 0;
        for (String g : x) if (y.contains(g)) inter++;
        return (double) inter / (x.size() + y.size() - inter);
    }

    static java.util.Set<String> grams(String s) {
        String t = s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9]", "");
        java.util.Set<String> g = new java.util.HashSet<>();
        for (int i = 0; i + 1 < t.length(); i++) g.add(t.substring(i, i + 2));
        return g;
    }

    /**
     * 给模型的上下文，按任务分段（[taskctx-1118]）：
     * <ul>
     *   <li>当前任务（「继续」接着的那张卡）完整给：所有步骤 + 最近几步的输出，所以续接时不会把做过的命令再发一遍；</li>
     *   <li>更早的任务只给一句摘要，最多 {@link #RELATED_TASKS} 个（最近的一个 + 和这次话题最像的）；</li>
     *   <li>普通聊天按时间倒着取，不超过 CONTEXT_CHARS 字，最多回溯到前两个任务为止；指令类往返不进上下文。</li>
     * </ul>
     */
    List<Map<String, Object>> context() {
        List<Map<String, Object>> all = store.list("chat");
        String cur = taskCard == null ? "" : taskCard;
        Map<String, Object> curCard = cur.isEmpty() ? null : store.find("chat", cur);
        if (curCard != null && !"running".equals(curCard.get("state"))) curCard = null; // 只有正在接着做的那张算「当前任务」
        List<Map<String, Object>> picked = new ArrayList<>();
        List<Map<String, Object>> older = new ArrayList<>();
        int used = 0, boundaries = 0;
        boolean cut = false, skipUser = false;
        String lastUser = "";
        for (int i = all.size() - 1; i >= 0; i--) {
            Map<String, Object> m = all.get(i);
            boolean user = "user".equals(m.get("role"));
            if (user && lastUser.isEmpty()) lastUser = String.valueOf(m.get("text"));
            if (user && skipUser) { skipUser = false; continue; } // 指令那句不进上下文
            if (!user && ("cmd".equals(m.get("via")) || "error".equals(m.get("via")))) { skipUser = true; continue; }
            skipUser = false;
            if ("system".equals(m.get("role"))) continue;
            if ("task".equals(m.get("via"))) {
                if (curCard != null && cur.equals(String.valueOf(m.get("id")))) {
                    // 当前任务：完整记录（程序记录放在用户角色里，不放进助手的嘴里，免得模型学着把步骤当文字写）
                    String rec = fullRecord(m), ans = String.valueOf(m.getOrDefault("answer", ""));
                    if (!ans.isBlank()) picked.add(0, Llm.msg("assistant", ans));
                    if (!rec.isEmpty()) picked.add(0, Llm.msg("user", rec));
                    continue;
                }
                if ("running".equals(m.get("state")) && m == latestTask(all) && curCard == null) continue; // 正在跑的这张：步骤在循环里
                older.add(m);
                boundaries++;
                if (boundaries == 1) {
                    // 紧挨着的上一个任务：步骤记录照旧给（简短版），它的答复也在
                    String rec = cardRecord(m), ans = String.valueOf(m.getOrDefault("answer", ""));
                    if (used + rec.length() + ans.length() > CONTEXT_CHARS && !picked.isEmpty()) { cut = true; continue; }
                    used += rec.length() + ans.length();
                    if (!ans.isBlank()) picked.add(0, Llm.msg("assistant", ans));
                    if (!rec.isEmpty()) picked.add(0, Llm.msg("user", rec));
                }
                continue;
            }
            if (boundaries >= 2) { cut = true; continue; } // 再往前的普通聊天不逐字给（旧任务只给摘要）
            String t = String.valueOf(m.get("text"));
            if (user && m.get("attach_note") != null) t = t + m.get("attach_note"); // [overnight-1119] 附件（图片描述 / OCR / 文件内容）
            if (used + t.length() > CONTEXT_CHARS && !picked.isEmpty()) { cut = true; boundaries = 2; continue; }
            used += t.length();
            picked.add(0, Llm.msg("assistant".equals(m.get("role")) ? "assistant" : "user", t));
        }
        // 更早的任务：最近一个已经在上面；再挑和这次话题最像的，最多 RELATED_TASKS 个，一句摘要
        List<Map<String, Object>> rest = older.size() > 1 ? new ArrayList<>(older.subList(1, older.size())) : new ArrayList<>();
        String topic = (curCard == null ? "" : String.valueOf(curCard.getOrDefault("title", ""))) + " " + lastUser;
        rest.sort((x, y) -> Double.compare(related(String.valueOf(y.getOrDefault("title", "")), topic), related(String.valueOf(x.getOrDefault("title", "")), topic)));
        List<String> sums = new ArrayList<>();
        for (Map<String, Object> m : rest) {
            if (sums.size() >= RELATED_TASKS - 1) break;
            if (related(String.valueOf(m.getOrDefault("title", "")), topic) <= 0.05 && !unfinished(m)) continue;
            sums.add("- " + shortSummary(m));
        }
        if (older.size() > 1) cut = true;
        if (cut) truncatedSinceMemory = true;
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(Llm.msg("system", systemPrompt()));
        if (!sums.isEmpty()) out.add(Llm.msg("user", "（OneSay 程序记录，不是用户说的话：更早的相关任务，只是摘要，供参考）\n" + String.join("\n", sums)));
        out.addAll(picked);
        return out;
    }

    /** 做完一个任务后：更早的任务卡（最近两张以外）里的命令输出压到 300 字，记录变小，界面上照样能看结论。 */
    @SuppressWarnings("unchecked")
    void compactOldCards() {
        List<Map<String, Object>> all = store.list("chat");
        int seen = 0;
        for (int i = all.size() - 1; i >= 0; i--) {
            Map<String, Object> m = all.get(i);
            if (!"task".equals(m.get("via"))) continue;
            if (++seen <= 2 || Boolean.TRUE.equals(m.get("compacted")) || !(m.get("steps") instanceof List)) continue;
            List<Object> steps = new ArrayList<>();
            for (Object o : (List<Object>) m.get("steps")) {
                if (o instanceof Map && String.valueOf(((Map<String, Object>) o).getOrDefault("output", "")).length() > 300) {
                    Map<String, Object> y = new TreeMap<>((Map<String, Object>) o);
                    y.put("output", String.valueOf(y.get("output")).substring(0, 300) + "…（较早任务的输出已压缩）");
                    steps.add(y);
                } else steps.add(o);
            }
            Map<String, Object> patch = new TreeMap<>();
            patch.put("steps", steps);
            patch.put("compacted", true);
            store.update("chat", String.valueOf(m.get("id")), patch);
        }
    }

    private void maybeUpdateMemory(Llm llm) {
        if (turnsSinceMemory < MEMORY_EVERY && !truncatedSinceMemory) return;
        if (!memBusy.compareAndSet(false, true)) return;
        turnsSinceMemory = 0;
        truncatedSinceMemory = false;
        List<Map<String, Object>> recent = new ArrayList<>(store.list("chat"));
        new Thread(() -> {
            try { updateMemory(llm, recent); } catch (Exception e) { log.accept("记忆更新失败：" + e.getMessage()); } finally { memBusy.set(false); }
        }, "onesay-memory").start();
    }

    void updateMemory(Llm llm, List<Map<String, Object>> chat) throws Exception {
        StringBuilder convo = new StringBuilder();
        int start = Math.max(0, chat.size() - 30);
        for (Map<String, Object> m : chat.subList(start, chat.size())) {
            if ("cmd".equals(m.get("via")) || "error".equals(m.get("via")) || "step".equals(m.get("via"))) continue; // 任务步骤（命令和输出）不进记忆
            if ("task".equals(m.get("via"))) { Object a = m.get("answer"); if (a == null || String.valueOf(a).isBlank()) continue; convo.append("助手：").append(a).append('\n'); continue; }
            convo.append("assistant".equals(m.get("role")) ? "助手：" : "用户：").append(m.get("text")).append('\n');
        }
        String old = String.valueOf(store.memory().getOrDefault("text", ""));
        List<Map<String, Object>> msgs = List.of(
                Llm.msg("system", "你负责维护一份关于用户的长期记忆。只记以后还用得上的事实：身份、偏好、习惯、在做的事和决定、重要的人和日期。"
                        + "不记一次性的闲聊、不记密码和 Key。旧记忆里被新对话推翻的要改掉，过时的删掉。"
                        + "输出新的完整记忆，每行一条，以「- 」开头，总共不超过 " + MEMORY_CHARS + " 字；没有值得记的就原样输出旧记忆。只输出记忆本身。"),
                Llm.msg("user", "旧记忆：\n" + (old.isBlank() ? "（空）" : old) + "\n\n最近对话：\n" + convo));
        String mem = llm.chat(msgs, 0.2, 60).trim();
        if (mem.length() > MEMORY_CHARS * 2) mem = mem.substring(0, MEMORY_CHARS * 2);
        if (!mem.isEmpty() && !mem.equals(old)) {
            store.setMemory(mem, System.currentTimeMillis());
            log.accept("记忆已更新（" + mem.length() + " 字）");
        }
    }
}
