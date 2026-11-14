package com.onesay.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 本机数据：计划（待办、日程、票、取件、自动任务）、通知、对话、记忆，存成一个 state.json。
 * 计划与记忆以后和手机双向同步；同步规则：整条记录按 updated_at 新者胜。
 */
public final class Store {
    public static final int MAX_CHAT = 400;      // 对话最多留这么多条（只影响显示，模型上下文另有截断）
    public static final int MAX_NOTES = 200;     // 通知最多留这么多条
    private final Path file;
    private Map<String, Object> root;

    public Store(Path file) {
        this.file = file;
        Map<String, Object> r = null;
        try { if (Files.exists(file)) r = Json.obj(Files.readString(file, StandardCharsets.UTF_8)); } catch (Exception ignored) { }
        root = r == null ? new TreeMap<>() : r;
        for (String k : List.of("todos", "events", "tickets", "pickups", "autos", "notes", "chat")) if (!(root.get(k) instanceof List)) root.put(k, new ArrayList<>());
        if (!(root.get("memory") instanceof Map)) { Map<String, Object> m = new TreeMap<>(); m.put("text", ""); m.put("updated_at", 0L); root.put("memory", m); }
    }

    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> list(String k) { return (List<Map<String, Object>>) root.get(k); }

    /** 给界面用的完整副本。 */
    public synchronized Map<String, Object> snapshot() { return Json.obj(Json.stringify(root)); }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.stringify(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try { Files.writeString(file, Json.stringify(root), StandardCharsets.UTF_8); } catch (IOException ignored) { }
        }
    }

    public synchronized Map<String, Object> add(String k, Map<String, Object> item, int max) {
        Map<String, Object> m = new TreeMap<>(item);
        m.putIfAbsent("id", Protocol.newId());
        m.put("updated_at", System.currentTimeMillis());
        List<Map<String, Object>> l = list(k);
        l.add(m);
        while (max > 0 && l.size() > max) l.remove(0);
        save();
        return m;
    }

    public synchronized Map<String, Object> find(String k, String id) {
        for (Map<String, Object> m : list(k)) if (id.equals(m.get("id"))) return m;
        return null;
    }

    public synchronized boolean update(String k, String id, Map<String, Object> patch) {
        Map<String, Object> m = find(k, id);
        if (m == null) return false;
        m.putAll(patch);
        m.put("updated_at", System.currentTimeMillis());
        save();
        return true;
    }

    public synchronized boolean remove(String k, String id) {
        boolean r = list(k).removeIf(m -> id.equals(m.get("id")));
        if (r) save();
        return r;
    }

    public synchronized void clear(String k) { list(k).clear(); save(); }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> memory() { return new TreeMap<>((Map<String, Object>) root.get("memory")); }

    public synchronized void setMemory(String text, long at) {
        Map<String, Object> m = new TreeMap<>();
        m.put("text", text == null ? "" : text);
        m.put("updated_at", at);
        root.put("memory", m);
        save();
    }

    /** 两端记忆合并：新者胜。返回合并后的记忆。 */
    public synchronized Map<String, Object> mergeMemory(String text, long at) {
        long mine = ((Number) memory().getOrDefault("updated_at", 0L)).longValue();
        if (at > mine && text != null) setMemory(text, at);
        return memory();
    }
}
