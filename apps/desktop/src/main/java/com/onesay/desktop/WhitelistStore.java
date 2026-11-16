package com.onesay.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * [app-whitelist-1116] 电脑端的应用白名单：存盘（配置目录 app_whitelist.json，同步格式原样）、审计日志（whitelist_audit.log，只记类别和应用 id）、
 * 每台配对手机的「本次会话」状态（读过的隐私类别、白名单已放行步数；{@link #SESSION_MS} 没动静就重新算一次会话）。
 * 规则在 {@link ActionPolicy} / {@link AppWhitelist}（和手机逐字相同），这里只管存和记。
 */
public final class WhitelistStore {

    /** 电脑没有「任务」的概念（指令一条条从手机来），按每台手机 10 分钟没动静算一次会话结束。 */
    public static final long SESSION_MS = 10 * 60_000L;
    static final long AUDIT_MAX = 1_000_000L;

    private final Path file, audit;
    private AppWhitelist wl;
    public volatile LongSupplier clock = System::currentTimeMillis;
    /** 本机设备 id（写进条目的 origin）。 */
    public volatile String device = "pc";
    /** 白名单变了（本机加删、合并进新条目）：App 接上「推给手机」。 */
    public volatile Runnable onChange = () -> { };

    private static final class Session {
        long last;
        final Set<String> read = new LinkedHashSet<>();
        /** 本次会话从网上拉过内容（链接下载）：之后白名单程序的外发也要确认（ActionPolicy L3e）。 */
        boolean external;
        int passes;
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public WhitelistStore(Path dir) {
        this.file = dir == null ? null : dir.resolve("app_whitelist.json");
        this.audit = dir == null ? null : dir.resolve("whitelist_audit.log");
        String text = null;
        try { if (file != null && Files.exists(file)) text = Files.readString(file, StandardCharsets.UTF_8); } catch (IOException | RuntimeException e) { text = "坏"; }
        wl = AppWhitelist.load(text);
    }

    public synchronized AppWhitelist list() { return wl; }

    public synchronized boolean broken() { return wl.broken; }

    public synchronized boolean contains(String platform, String id) { return !wl.broken && wl.contains(platform, id); }

    /** 本机加（界面上用户已经在风险确认框里点了「确定」）。 */
    public synchronized String add(String platform, String id, String name) {
        if (wl.broken) return "白名单文件读坏了，先在设置里「清空重建」";
        String why = wl.add(platform, id, name, device, "pc", clock.getAsLong());
        if (why != null) return why;
        save();
        log("add", platform, AppWhitelist.normId(platform, id), "本机");
        fire();
        return null;
    }

    public synchronized boolean remove(String platform, String id) {
        if (wl.broken) return false;
        boolean ok = wl.remove(platform, id, device, "pc", clock.getAsLong());
        if (ok) {
            save();
            log("remove", platform, AppWhitelist.normId(platform, id), "本机");
            fire();
        }
        return ok;
    }

    /**
     * [app-whitelist-1116 规则调整] 手机同步来的条目在电脑上确认一次才生效：用户在电脑界面上看过风险说明、点了「确认生效」。
     * 返回 null = 成功，否则原因。确认只记在本机存盘里，不同步。
     */
    public synchronized String confirm(String platform, String id) {
        if (wl.broken) return "白名单文件读坏了，先在设置里「清空重建」";
        if (!wl.confirm(platform, id)) return wl.isConfirmed(platform, id) ? "已经生效了" : "没有这个待确认的应用";
        save();
        log("confirm", platform, AppWhitelist.normId(platform, id), "本机");
        return null;
    }

    /** 手机同步来、电脑上还没确认的。 */
    public synchronized List<AppWhitelist.Entry> pending() { return wl.broken ? new ArrayList<>() : wl.pending(); }

    /** 读坏了：清空重建（用户在界面上点的）。 */
    public synchronized void reset() {
        wl = new AppWhitelist();
        save();
        log("reset", "", "", "本机");
    }

    /** 合并对方整份；返回对方带来的新放行条目（坏文档抛 IllegalArgumentException）。本机读坏了时不合并也不往外发。 */
    public synchronized List<AppWhitelist.Entry> merge(String doc, String from) {
        if (wl.broken) throw new IllegalStateException("本机白名单读坏了");
        String before = wl.toJson(device);
        List<AppWhitelist.Entry> added = wl.merge(AppWhitelist.parse(doc));
        if (!before.equals(wl.toJson(device))) {
            save();
            for (AppWhitelist.Entry e : added) log("sync_add_pending", e.platform, e.id, from);
        }
        return added;
    }

    public synchronized String json() { return wl.toJson(device); }

    private void fire() {
        try { onChange.run(); } catch (RuntimeException ignored) { }
    }

    private void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, wl.toLocalJson(device), StandardCharsets.UTF_8); // 本机存盘带确认记录；同步用 json()（不带）
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try { Files.writeString(file, wl.toLocalJson(device), StandardCharsets.UTF_8); } catch (IOException ignored) { }
        }
    }

    // ------------------------------------------------------------------ 会话（读过的隐私 / 放行次数）

    private Session session(String peer) {
        long now = clock.getAsLong();
        Session s = sessions.computeIfAbsent(peer == null ? "" : peer, k -> new Session());
        synchronized (s) {
            if (s.last > 0 && now - s.last > SESSION_MS) {
                s.read.clear();
                s.external = false;
                s.passes = 0;
            }
            s.last = now;
        }
        return s;
    }

    /** 这台手机本次会话读到了隐私（读剪贴板、读文件、截图…的结果）。 */
    public void noteRead(String peer, Set<String> kinds) {
        if (kinds == null || kinds.isEmpty()) return;
        Session s = session(peer);
        synchronized (s) { s.read.addAll(kinds); }
    }

    /** 这台手机本次会话经电脑从网上拉过内容（链接下载）。 */
    public void noteExternal(String peer) {
        Session s = session(peer);
        synchronized (s) { s.external = true; }
    }

    public boolean readExternal(String peer) {
        Session s = session(peer);
        synchronized (s) { return s.external; }
    }

    public Set<String> readKinds(String peer) {
        Session s = session(peer);
        synchronized (s) { return new LinkedHashSet<>(s.read); }
    }

    public int passes(String peer) {
        Session s = session(peer);
        synchronized (s) { return s.passes; }
    }

    public void countPass(String peer) {
        Session s = session(peer);
        synchronized (s) { s.passes++; }
    }

    // ------------------------------------------------------------------ 审计

    /** 一次判定（白名单相关的：放行、因支付 / 隐私 / 读过隐私 / 可疑没放行）。行内只有类别和应用 id。 */
    public void audit(ActionPolicy.Facts f, ActionPolicy.Decision d, String action) {
        write(ActionPolicy.auditLine(clock.getAsLong(), "pc", f, d, action));
    }

    void log(String op, String platform, String id, String by) {
        write(PrivacyScan.scrub(clock.getAsLong() + "\tpc\t" + platform + ":" + (id == null ? "" : id) + "\t" + op + "\t" + by));
    }

    private synchronized void write(String line) {
        if (audit == null) return;
        try {
            Files.createDirectories(audit.getParent());
            if (Files.exists(audit) && Files.size(audit) > AUDIT_MAX) Files.move(audit, audit.resolveSibling("whitelist_audit.old.log"), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(audit, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    /** 最近的审计（界面看）。 */
    public List<String> recentAudit(int n) {
        List<String> out = new ArrayList<>();
        if (audit == null || !Files.exists(audit)) return out;
        try {
            List<String> all = Files.readAllLines(audit, StandardCharsets.UTF_8);
            for (int i = Math.max(0, all.size() - n); i < all.size(); i++) out.add(all.get(i));
        } catch (IOException ignored) { }
        return out;
    }

    /** 界面用：活条目（pending = 手机同步来、还没在电脑上确认，不放行）。 */
    public List<Object> view() {
        List<Object> out = new ArrayList<>();
        AppWhitelist l = list();
        for (AppWhitelist.Entry e : l.live()) {
            Map<String, Object> m = new TreeMap<>();
            m.put("platform", e.platform);
            m.put("id", e.id);
            m.put("name", e.name);
            m.put("added_at", e.addedAt);
            m.put("origin_kind", e.originKind);
            m.put("pending", !l.isConfirmed(e.platform, e.id));
            out.add(m);
        }
        return out;
    }
}
