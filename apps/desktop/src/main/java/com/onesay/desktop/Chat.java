package com.onesay.desktop;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    /** 任务进行中发来、还没交给模型的消息 id（先进先出，和 agent.inbox 对应）。 */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> queuedIds = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Object queueLock = new Object();
    /** 「继续」算接着上一件事：上一条助手回复是模型 / 任务的，且不超过这么久。 */
    static final long CONTINUE_WINDOW_MS = 60L * 60 * 1000;
    /** 工作区、用户目录（写进系统提示）。 */
    volatile String workspace = "", home = System.getProperty("user.home", "");

    public Chat(Config cfg, Store store, Actions actions, Supplier<List<String>> shortcuts, Consumer<String> log) {
        this.cfg = cfg;
        this.store = store;
        this.actions = actions;
        this.shortcuts = shortcuts;
        this.log = log;
        agent.preset = () -> ChatPolicy.Preset.of(cfg.chatPreset);
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
    public Map<String, Object> send(String text, String via) {
        text = text == null ? "" : text.trim();
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
                Map<String, Object> m = store.add("chat", u, Store.MAX_CHAT);
                queuedIds.add(String.valueOf(m.get("id")));
                agent.inbox.add(text);
                if (ChatAgent.stopWord(text)) agent.cancel.set(true);
                return m;
            }
        }
        return handle(text, via, true);
    }

    private synchronized Map<String, Object> handle(String text, String via, boolean addUser) {
        if (addUser) add("user", text, via);

        Llm llm0 = Llm.from(cfg);
        boolean modelOk = brain != null || llm0.ready();
        boolean tasks = modelOk && pcExec != null;
        // [chat-agent-1118] 「继续」接着上一件事（上一条助手回复是模型 / 任务的）：交给模型，不再被当成「播放 / 暂停」媒体键
        boolean cont = modelOk && Intent.isContinuation(text) && continuing();
        Intent it = cont ? null : Intent.parse(text, cfg.appMap(), shortcuts.get(), tasks);
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
                    o = agent.run(context(), b, pcExec, new CardSteps(card));
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
                Map<String, Object> patch = new TreeMap<>();
                patch.put("state", o.status);
                patch.put("answer", reply);
                patch.put("ended_at", System.currentTimeMillis());
                store.update("chat", card, patch);
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

    /** 开始（或「继续」接着）一个任务：返回任务卡消息 id。 */
    String startTask(boolean cont) {
        Map<String, Object> old = cont && !taskCard.isEmpty() ? store.find("chat", taskCard) : null;
        if (old != null) {
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
                        y.put("state", Boolean.TRUE.equals(ev.get("ok")) ? "ok" : "fail");
                        steps.set(i, y);
                        st = null;
                        break;
                    }
                }
                if (st != null) {
                    st.put("kind", "step");
                    st.putAll(ev);
                    st.put("state", Boolean.TRUE.equals(ev.get("ok")) ? "ok" : "fail");
                    steps.add(st);
                }
            }
            while (steps.size() > 200) steps.remove(0);
            store.update("chat", card, Map.of("steps", steps));
        }
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
                    .append(s.equals("ok") ? "成功" : s.equals("fail") ? "失败" : "没做完").append("：").append(x.getOrDefault("summary", "")).append('\n');
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
            b.append(s.equals("ok") ? " ✓ " : s.equals("fail") ? " ✗ " : " … ").append(x.getOrDefault("summary", "")).append('\n');
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
            return ChatAgent.systemPrompt(now, ws, home, mem, ChatPolicy.Preset.of(cfg.chatPreset));
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

    /** 最近的消息，总字数不超过 CONTEXT_CHARS；指令类往返不进上下文。 */
    List<Map<String, Object>> context() {
        List<Map<String, Object>> all = store.list("chat");
        List<Map<String, Object>> picked = new ArrayList<>();
        int used = 0;
        boolean cut = false, skipUser = false;
        for (int i = all.size() - 1; i >= 0; i--) {
            Map<String, Object> m = all.get(i);
            boolean user = "user".equals(m.get("role"));
            if (user && skipUser) { skipUser = false; continue; } // 指令那句不进上下文
            if (!user && ("cmd".equals(m.get("via")) || "error".equals(m.get("via")))) { skipUser = true; continue; }
            skipUser = false;
            if ("system".equals(m.get("role"))) continue;
            if ("task".equals(m.get("via")) && "running".equals(m.get("state"))) continue; // 正在跑的这张卡：步骤在循环里，不重复给
            if ("task".equals(m.get("via"))) {
                // [approvals-1118] 任务卡：真实执行过的步骤作为「程序记录」给模型（不放进助手的嘴里，免得模型学着把步骤当文字写），再给最后的答复
                String rec = cardRecord(m), ans = String.valueOf(m.getOrDefault("answer", ""));
                if (used + rec.length() + ans.length() > CONTEXT_CHARS && !picked.isEmpty()) { cut = true; break; }
                used += rec.length() + ans.length();
                if (!ans.isBlank()) picked.add(0, Llm.msg("assistant", ans));
                if (!rec.isEmpty()) picked.add(0, Llm.msg("user", rec));
                continue;
            }
            String t = String.valueOf(m.get("text"));
            if (used + t.length() > CONTEXT_CHARS && !picked.isEmpty()) { cut = true; break; }
            used += t.length();
            picked.add(0, Llm.msg("assistant".equals(m.get("role")) ? "assistant" : "user", t));
        }
        if (cut) truncatedSinceMemory = true;
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(Llm.msg("system", systemPrompt()));
        out.addAll(picked);
        return out;
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
