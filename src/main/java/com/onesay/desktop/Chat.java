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

    public Chat(Config cfg, Store store, Actions actions, Supplier<List<String>> shortcuts, Consumer<String> log) {
        this.cfg = cfg;
        this.store = store;
        this.actions = actions;
        this.shortcuts = shortcuts;
        this.log = log;
    }

    private Map<String, Object> add(String role, String text, String via) {
        Map<String, Object> m = new TreeMap<>();
        m.put("role", role);
        m.put("text", text);
        m.put("at", System.currentTimeMillis());
        if (via != null) m.put("via", via);
        return store.add("chat", m, Store.MAX_CHAT);
    }

    /** 处理一句话（打字或语音转来的），返回助手回复那一条。 */
    public synchronized Map<String, Object> send(String text, String via) {
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) return add("assistant", "没收到内容", null);
        if (text.length() > 4000) text = text.substring(0, 4000);
        add("user", text, via);

        Intent it = Intent.parse(text, cfg.appMap(), shortcuts.get());
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
        Llm llm = Llm.from(cfg);
        if (!llm.ready()) return add("assistant", understood ? it.say : "没认出这是哪条指令。在设置里填上大模型 Key 后，什么都可以聊。", null);

        try {
            String reply = llm.chat(context(), 0.7, 60);
            Map<String, Object> m = add("assistant", reply, null);
            turnsSinceMemory++;
            maybeUpdateMemory(llm);
            return m;
        } catch (Exception e) {
            return add("assistant", e.getMessage() == null ? "大模型出错" : e.getMessage(), "error");
        }
    }

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
            if (!r.ok) return r.text;
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
        String mem = String.valueOf(store.memory().getOrDefault("text", ""));
        return "你是 OneSay，运行在用户 Windows 电脑上的助手，和用户手机上的 OneSay 是同一个助手、共用记忆。"
                + "用简体中文回答，先给结论，简短直接。现在是 " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEEE", java.util.Locale.CHINA)) + "。"
                + "你不能直接操作电脑；像锁屏、截图、音量、打开某个程序这类固定指令由程序自己识别执行，用户说了却没执行时，提示他换成更直接的说法。"
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
            if ("cmd".equals(m.get("via")) || "error".equals(m.get("via"))) continue;
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
