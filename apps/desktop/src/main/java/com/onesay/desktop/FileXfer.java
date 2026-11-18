package com.onesay.desktop;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * [overnight-1119] 手机 ⇄ 电脑互传文件（协议指令 file_put，两端同一套）：
 * <pre>
 *   file_put {xfer, name, size, seq, total, b64, sha256(只在最后一块)}   每块 ≤ {@link #CHUNK} 字节（base64 后 ≈ 683 KB，远小于单帧 8 MB）
 * </pre>
 * 收的一端按顺序追加到临时文件（乱序 / 重发同一块会被认出来），最后一块到了核对大小和 SHA-256，对上才挪到落盘目录（重名加「 (2)」），
 * 对不上删掉并如实说。大小上限 {@link #MAX_SIZE}；一个传输 10 分钟没动静就丢掉临时文件。
 * 电脑收到的放在 OneSay 工作区「来自手机」文件夹；电脑发出去的，手机放在「下载 / OneSay」。
 */
public final class FileXfer {
    public static final int CHUNK = 512 * 1024;
    public static final long MAX_SIZE = 1024L * 1024 * 1024; // 1 GB
    public static final long IDLE_MS = 10 * 60_000;
    public static final String DIR_FROM_PHONE = "来自手机";

    /** 一个传输的状态（界面看进度用）。 */
    public static final class State {
        public final String id, name, dir; // dir: in = 收, out = 发
        public final long size;
        public volatile long done;
        public volatile String status = "running"; // running / done / failed
        public volatile String error = "";
        public volatile String path = "";
        public volatile String peer = "";
        public volatile long updated = System.currentTimeMillis();
        final Path part;
        int nextSeq;
        MessageDigest md;

        State(String id, String name, long size, String dir, Path part) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.dir = dir;
            this.part = part;
            try { md = MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IllegalStateException(e); }
        }

        public Map<String, Object> view() {
            Map<String, Object> m = new TreeMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("dir", dir);
            m.put("size", size);
            m.put("done", done);
            m.put("percent", size <= 0 ? (status.equals("done") ? 100L : 0L) : Math.min(100L, done * 100 / size));
            m.put("status", status);
            m.put("error", error);
            m.put("path", path);
            m.put("peer", peer);
            m.put("updated", updated);
            return m;
        }
    }

    final Map<String, State> all = new ConcurrentHashMap<>();
    /** 收到一个完整文件后（界面 / 通知）。 */
    public volatile Consumer<State> onDone = s -> { };

    /** 最近的传输（新的在前，最多 20 个）。 */
    public List<Object> view() {
        List<State> l = new ArrayList<>(all.values());
        l.sort((a, b) -> Long.compare(b.updated, a.updated));
        List<Object> out = new ArrayList<>();
        for (State s : l) { if (out.size() >= 20) break; out.add(s.view()); }
        return out;
    }

    static String safeName(String n) {
        String s = n == null ? "" : n.replaceAll(".*[\\\\/]", "").replaceAll("[\\p{Cntrl}:*?\"<>|]", "_").trim();
        if (s.isEmpty() || s.equals(".") || s.equals("..")) s = "文件";
        if (s.length() > 120) s = s.substring(s.length() - 120);
        return s;
    }

    /** 落盘目录里不重名的文件。 */
    static Path unique(Path dir, String name) {
        Path p = dir.resolve(name);
        if (!Files.exists(p)) return p;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        for (int i = 2; i < 1000; i++) {
            Path q = dir.resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(q)) return q;
        }
        return dir.resolve(base + "-" + System.currentTimeMillis() + ext);
    }

    static Map<String, Object> res(boolean ok, String text, Map<String, Object> d) {
        Map<String, Object> m = d == null ? new TreeMap<>() : d;
        m.put("ok", ok);
        m.put("text", text);
        return m;
    }

    /** 收一块。返回 {ok, text, received, done?, path?}。 */
    public synchronized Map<String, Object> receive(Path destDir, Map<String, Object> a, String peer) {
        gc();
        String id = str(a, "xfer");
        if (!id.matches("[A-Za-z0-9_-]{4,64}")) return res(false, "传输编号不对", null);
        long size = num(a, "size", -1);
        int seq = (int) num(a, "seq", -1), total = (int) num(a, "total", -1);
        if (size < 0 || size > MAX_SIZE) return res(false, "文件太大（超过 1 GB）或大小不对", null);
        if (seq < 0 || total <= 0 || seq >= total || total > MAX_SIZE / CHUNK + 2) return res(false, "分块编号不对", null);
        byte[] chunk;
        try {
            chunk = Base64.getDecoder().decode(str(a, "b64"));
        } catch (IllegalArgumentException e) {
            return res(false, "这一块读不出来", null);
        }
        if (chunk.length > CHUNK) return res(false, "这一块太大", null);
        State s = all.get(id);
        try {
            if (s == null) {
                if (seq != 0) return res(false, "缺了开头，请重发", map("expect", 0L));
                Files.createDirectories(destDir);
                Path part = destDir.resolve("." + id + ".part");
                Files.deleteIfExists(part);
                s = new State(id, safeName(str(a, "name")), size, "in", part);
                s.peer = peer == null ? "" : peer;
                all.put(id, s);
            }
            if (!"running".equals(s.status)) return res(false, "这个传输已经结束", null);
            if (seq < s.nextSeq) return res(true, "这一块收过了", map("received", s.done)); // 重发：幂等
            if (seq > s.nextSeq) return res(false, "顺序不对，要第 " + (s.nextSeq + 1) + " 块", map("expect", (long) s.nextSeq));
            if (s.done + chunk.length > s.size) { fail(s, "收到的比说好的大小多"); return res(false, s.error, null); }
            try (OutputStream o = Files.newOutputStream(s.part, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) { o.write(chunk); }
            s.md.update(chunk);
            s.done += chunk.length;
            s.nextSeq++;
            s.updated = System.currentTimeMillis();
            if (seq < total - 1) return res(true, "收到第 " + (seq + 1) + " / " + total + " 块", map("received", s.done));
            // 最后一块：核对
            if (s.done != s.size) { fail(s, "大小对不上（收到 " + s.done + "，应为 " + s.size + "）"); return res(false, s.error, null); }
            String want = str(a, "sha256").toLowerCase(java.util.Locale.ROOT);
            String got = hex(s.md.digest());
            if (!want.isEmpty() && !want.equals(got)) { fail(s, "校验不对（文件在路上坏了），请重发"); return res(false, s.error, null); }
            Path fin = unique(destDir, s.name);
            Files.move(s.part, fin, StandardCopyOption.REPLACE_EXISTING);
            s.path = fin.toString();
            s.status = "done";
            try { onDone.accept(s); } catch (RuntimeException ignored) { }
            Map<String, Object> d = map("received", s.done);
            d.put("done", true);
            d.put("path", s.path);
            d.put("sha256", got);
            return res(true, "收好了：" + fin.getFileName() + "（" + human(s.size) + "）", d);
        } catch (Exception e) {
            if (s != null) fail(s, "写不进去：" + e.getClass().getSimpleName());
            return res(false, "写不进去：" + e.getClass().getSimpleName(), null);
        }
    }

    void fail(State s, String why) {
        s.status = "failed";
        s.error = why;
        s.updated = System.currentTimeMillis();
        try { if (s.part != null) Files.deleteIfExists(s.part); } catch (Exception ignored) { }
    }

    /** 太久没动静的传输：丢掉临时文件，标失败。 */
    void gc() {
        long now = System.currentTimeMillis();
        for (State s : all.values()) if ("running".equals(s.status) && "in".equals(s.dir) && now - s.updated > IDLE_MS) fail(s, "很久没收到后面的部分，已放弃");
        if (all.size() > 60) all.values().removeIf(s -> !"running".equals(s.status) && now - s.updated > 3600_000);
    }

    /**
     * 发一个文件：按块调 call("file_put", 参数)（对端的回复），每块失败重试一次；进度写进 State。
     * 返回最终结果（对端说的落盘位置在 data.path）。
     */
    public Actions.Result send(File f, String peer, BiFunction<String, Map<String, Object>, Actions.Result> call) {
        if (f == null || !f.isFile()) return Actions.Result.fail("文件不存在：" + (f == null ? "" : f.getPath()));
        long size = f.length();
        if (size > MAX_SIZE) return Actions.Result.fail("文件太大（超过 1 GB）");
        String id = "x" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1e6), 36);
        State s = new State(id, f.getName(), size, "out", null);
        s.peer = peer == null ? "" : peer;
        all.put(id, s);
        int total = (int) Math.max(1, (size + CHUNK - 1) / CHUNK);
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-256"); } catch (Exception e) { return Actions.Result.fail("算不了校验"); }
        String sha;
        try (InputStream in = Files.newInputStream(f.toPath())) {
            byte[] all2 = new byte[8192];
            int n;
            while ((n = in.read(all2)) > 0) md.update(all2, 0, n);
            sha = hex(md.digest());
        } catch (Exception e) {
            s.status = "failed";
            s.error = "读不了文件";
            return Actions.Result.fail("读不了文件：" + e.getClass().getSimpleName());
        }
        Actions.Result last = null;
        try (InputStream in = Files.newInputStream(f.toPath())) {
            for (int seq = 0; seq < total; seq++) {
                byte[] buf = in.readNBytes(CHUNK);
                Map<String, Object> a = new TreeMap<>();
                a.put("xfer", id);
                a.put("name", f.getName());
                a.put("size", size);
                a.put("seq", (long) seq);
                a.put("total", (long) total);
                a.put("b64", Base64.getEncoder().encodeToString(buf));
                if (seq == total - 1) a.put("sha256", sha);
                Actions.Result r = call.apply("file_put", a);
                if (r == null || !r.ok) r = call.apply("file_put", a); // 重试一次（对端对重发的块是幂等的）
                if (r == null || !r.ok) {
                    s.status = "failed";
                    s.error = r == null ? "对方没回应" : r.text;
                    s.updated = System.currentTimeMillis();
                    return new Actions.Result(false, "发到第 " + (seq + 1) + " / " + total + " 块失败：" + s.error, r == null ? null : r.data);
                }
                s.done += buf.length;
                s.updated = System.currentTimeMillis();
                last = r;
            }
        } catch (Exception e) {
            s.status = "failed";
            s.error = "读文件出错：" + e.getClass().getSimpleName();
            return Actions.Result.fail(s.error);
        }
        s.status = "done";
        s.path = last != null && last.data != null && last.data.get("path") != null ? String.valueOf(last.data.get("path")) : "";
        Map<String, Object> d = new TreeMap<>();
        d.put("path", s.path);
        d.put("size", size);
        d.put("sha256", sha);
        return Actions.Result.ok("已发到手机：" + f.getName() + "（" + human(size) + "）" + (s.path.isEmpty() ? "" : "，手机上存在 " + s.path), d);
    }

    public static String human(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format("%.1f MB", n / 1048576.0);
        return String.format("%.2f GB", n / 1073741824.0);
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    static Map<String, Object> map(String k, Object v) {
        Map<String, Object> m = new TreeMap<>();
        m.put(k, v);
        return m;
    }

    static String str(Map<String, Object> a, String k) {
        Object v = a == null ? null : a.get(k);
        return v == null ? "" : String.valueOf(v).trim();
    }

    static long num(Map<String, Object> a, String k, long def) {
        Object v = a == null ? null : a.get(k);
        if (v instanceof Number) return ((Number) v).longValue();
        try { return v == null ? def : Long.parseLong(String.valueOf(v).trim()); } catch (NumberFormatException e) { return def; }
    }
}
