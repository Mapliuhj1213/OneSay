package com.onesay.assistant.v2.pclink;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * [overnight-1119] 手机 ⇄ 电脑互传文件（协议指令 {@code file_put}，与电脑端 {@code FileXfer} 同一套，纯 Java 7，JVM 可测）：
 * <pre>
 *   file_put {xfer, name, size, seq, total, b64, sha256(只在最后一块)}   每块 ≤ {@link #CHUNK} 字节
 * </pre>
 * 收：按顺序追加到暂存目录里的临时文件（重发同一块幂等，乱序要求重发），最后一块到了核对大小和 SHA-256，对上才交给 {@link Sink}
 * 落盘（Android：「下载 / OneSay」，重名加「 (2)」，不覆盖、不打开、不安装），对不上删掉并如实说。上限 1 GB；10 分钟没动静丢掉。
 * 发：先算整份 SHA-256，再一块一块调 {@link Caller}（每块失败重试一次），进度回调给通知栏。
 */
public final class PcFileRx {

    public static final int CHUNK = 512 * 1024;
    public static final long MAX_SIZE = 1024L * 1024 * 1024;
    public static final long IDLE_MS = 10 * 60000L;
    /** 电脑发来的文件在手机上放在「下载 / OneSay」。 */
    public static final String PHONE_DIR = "Download/OneSay";

    /** 收完、校验过的文件交给它落盘；返回给人看的位置（失败抛异常）。 */
    public interface Sink {
        String place(File verified, String name) throws IOException;
    }

    /** 往电脑发一块（Android：PcLink.runNow）。 */
    public interface Caller {
        PcLanClient.Result call(String cmd, Map<String, Object> args);
    }

    /** 发送进度（已发字节、总字节）；返回 false = 取消。 */
    public interface Progress {
        boolean tick(long done, long total);
    }

    static final class State {
        final String id, name, peer;
        final long size;
        final File part;
        long done, updated;
        int nextSeq;
        String status = "running", error = "", path = "";
        final MessageDigest md;

        State(String id, String name, long size, File part, String peer) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.part = part;
            this.peer = peer;
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            updated = System.currentTimeMillis();
        }
    }

    private final File staging;
    private final Sink sink;
    private final Map<String, State> all = new HashMap<String, State>();

    public PcFileRx(File staging, Sink sink) {
        this.staging = staging;
        this.sink = sink;
    }

    /** 默认落盘：挪进某个目录（测试 / 低版本兜底用）。 */
    public static Sink dirSink(final File dir) {
        return new Sink() {
            @Override public String place(File verified, String name) throws IOException {
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("建不了文件夹");
                File fin = unique(dir, name);
                if (!verified.renameTo(fin)) {
                    copy(verified, fin);
                    //noinspection ResultOfMethodCallIgnored
                    verified.delete();
                }
                return fin.getAbsolutePath();
            }
        };
    }

    /** 收一块。返回 ok / text / data(received, done, path, sha256, expect)。 */
    public synchronized PcLanClient.Result receive(Map<String, Object> a, String peer) {
        gc();
        String id = str(a, "xfer");
        if (!id.matches("[A-Za-z0-9_-]{4,64}")) return res(false, "传输编号不对", null);
        long size = num(a, "size", -1);
        int seq = (int) num(a, "seq", -1), total = (int) num(a, "total", -1);
        if (size < 0 || size > MAX_SIZE) return res(false, "文件太大（超过 1 GB）或大小不对", null);
        if (seq < 0 || total <= 0 || seq >= total || total > MAX_SIZE / CHUNK + 2) return res(false, "分块编号不对", null);
        byte[] chunk;
        try {
            chunk = PcB64.decode(str(a, "b64"));
        } catch (RuntimeException e) {
            return res(false, "这一块读不出来", null);
        }
        if (chunk == null) return res(false, "这一块读不出来", null);
        if (chunk.length > CHUNK) return res(false, "这一块太大", null);
        State s = all.get(id);
        try {
            if (s == null) {
                if (seq != 0) return res(false, "缺了开头，请重发", one("expect", 0L));
                if (!staging.isDirectory() && !staging.mkdirs()) return res(false, "手机上建不了暂存文件夹", null);
                File part = new File(staging, "." + id + ".part");
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                s = new State(id, safeName(str(a, "name")), size, part, peer == null ? "" : peer);
                all.put(id, s);
            }
            if (!"running".equals(s.status)) return "done".equals(s.status) && seq == total - 1 ? doneResult(s, "收过了：" + new File(s.path).getName()) : res(false, "这个传输已经结束", null);
            if (seq < s.nextSeq) return res(true, "这一块收过了", one("received", s.done)); // 重发：幂等
            if (seq > s.nextSeq) return res(false, "顺序不对，要第 " + (s.nextSeq + 1) + " 块", one("expect", (long) s.nextSeq));
            if (s.done + chunk.length > s.size) return res(false, fail(s, "收到的比说好的大小多"), null);
            OutputStream o = new FileOutputStream(s.part, true);
            try {
                o.write(chunk);
            } finally {
                o.close();
            }
            s.md.update(chunk);
            s.done += chunk.length;
            s.nextSeq++;
            s.updated = System.currentTimeMillis();
            if (seq < total - 1) return res(true, "收到第 " + (seq + 1) + " / " + total + " 块", one("received", s.done));
            if (s.done != s.size) return res(false, fail(s, "大小对不上（收到 " + s.done + "，应为 " + s.size + "）"), null);
            String want = str(a, "sha256").toLowerCase(Locale.ROOT);
            String got = hex(s.md.digest());
            if (want.length() > 0 && !want.equals(got)) return res(false, fail(s, "校验不对（文件在路上坏了），请重发"), null);
            s.path = sink.place(s.part, s.name);
            s.status = "done";
            return doneResult(s, "收好了：" + s.name + "（" + human(s.size) + "）");
        } catch (Exception e) {
            String why = "写不进去：" + e.getClass().getSimpleName();
            if (s != null) fail(s, why);
            return res(false, why, null);
        }
    }

    PcLanClient.Result doneResult(State s, String text) {
        Map<String, Object> d = one("received", s.done);
        d.put("done", Boolean.TRUE);
        d.put("path", s.path);
        d.put("name", s.name);
        return res(true, text, d);
    }

    /** 最近收完的（给通知 / 控制台）：名字 → 位置。 */
    public synchronized List<String[]> finished() {
        List<State> l = new ArrayList<State>(all.values());
        Collections.sort(l, new Comparator<State>() {
            @Override public int compare(State x, State y) { return Long.compare(y.updated, x.updated); }
        });
        List<String[]> out = new ArrayList<String[]>();
        for (State s : l) if ("done".equals(s.status)) out.add(new String[]{s.name, s.path, s.peer});
        return out;
    }

    String fail(State s, String why) {
        s.status = "failed";
        s.error = why;
        s.updated = System.currentTimeMillis();
        //noinspection ResultOfMethodCallIgnored
        s.part.delete();
        return why;
    }

    void gc() {
        long now = System.currentTimeMillis();
        for (State s : new ArrayList<State>(all.values())) {
            if ("running".equals(s.status) && now - s.updated > IDLE_MS) fail(s, "很久没收到后面的部分，已放弃");
            if (!"running".equals(s.status) && now - s.updated > 3600000L) all.remove(s.id);
        }
    }

    // ================================================================== 发

    /**
     * 发一个文件到电脑（电脑放在 OneSay 工作区「来自手机」）。阻塞，调用方放后台线程。
     */
    public static PcLanClient.Result send(File f, String name, Caller call, Progress p) {
        if (f == null || !f.isFile()) return res(false, "文件不存在", null);
        long size = f.length();
        if (size > MAX_SIZE) return res(false, "文件太大（超过 1 GB）", null);
        String nm = safeName(name == null || name.length() == 0 ? f.getName() : name);
        String sha;
        try {
            sha = sha256(f);
        } catch (IOException e) {
            return res(false, "读不了文件：" + e.getClass().getSimpleName(), null);
        }
        String id = "p" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1e6), 36);
        int total = (int) Math.max(1, (size + CHUNK - 1) / CHUNK);
        PcLanClient.Result last = null;
        long done = 0;
        try {
            InputStream in = new FileInputStream(f);
            try {
                if (p != null) p.tick(0, size);
                for (int seq = 0; seq < total; seq++) {
                    byte[] buf = readUpTo(in, CHUNK);
                    Map<String, Object> a = new TreeMap<String, Object>();
                    a.put("xfer", id);
                    a.put("name", nm);
                    a.put("size", size);
                    a.put("seq", (long) seq);
                    a.put("total", (long) total);
                    a.put("b64", PcB64.encode(buf));
                    if (seq == total - 1) a.put("sha256", sha);
                    PcLanClient.Result r = call.call("file_put", a);
                    if (r == null || !r.ok) r = call.call("file_put", a); // 重试一次（对端对重发的块是幂等的）
                    if (r == null || !r.ok) {
                        String why = r == null ? "电脑没回应" : r.text;
                        return new PcLanClient.Result(false, "发到第 " + (seq + 1) + " / " + total + " 块失败：" + why, r == null ? null : r.data, r == null ? "" : r.fail);
                    }
                    done += buf.length;
                    last = r;
                    if (p != null && !p.tick(done, size) && seq < total - 1) return res(false, "已取消（发了 " + human(done) + " / " + human(size) + "）", null);
                }
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return res(false, "读文件出错：" + e.getClass().getSimpleName(), null);
        }
        Map<String, Object> d = new TreeMap<String, Object>();
        Object where = last == null ? null : last.data.get("path");
        d.put("path", where == null ? "" : String.valueOf(where));
        d.put("size", size);
        d.put("sha256", sha);
        return res(true, "已发到电脑：" + nm + "（" + human(size) + "），在电脑 OneSay 工作区「来自手机」里", d);
    }

    static byte[] readUpTo(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int k = in.read(b, off, n - off);
            if (k < 0) break;
            off += k;
        }
        if (off == n) return b;
        byte[] x = new byte[off];
        System.arraycopy(b, 0, x, 0, off);
        return x;
    }

    public static String sha256(File f) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("no sha-256");
        }
        InputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        } finally {
            in.close();
        }
        return hex(md.digest());
    }

    // ================================================================== 小工具

    public static String safeName(String n) {
        String s = n == null ? "" : n.replaceAll(".*[\\\\/]", "").replaceAll("[\\p{Cntrl}:*?\"<>|]", "_").trim();
        if (s.length() == 0 || s.equals(".") || s.equals("..")) s = "文件";
        if (s.startsWith(".")) s = "_" + s.substring(1); // 不落成隐藏文件
        if (s.length() > 120) s = s.substring(s.length() - 120);
        return s;
    }

    public static File unique(File dir, String name) {
        File p = new File(dir, name);
        if (!p.exists()) return p;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            File q = new File(dir, base + " (" + i + ")" + ext);
            if (!q.exists()) return q;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    static void copy(File from, File to) throws IOException {
        InputStream in = new FileInputStream(from);
        try {
            OutputStream o = new FileOutputStream(to);
            try {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
            } finally {
                o.close();
            }
        } finally {
            in.close();
        }
    }

    public static String human(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", n / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", n / 1073741824.0);
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    static PcLanClient.Result res(boolean ok, String text, Map<String, Object> d) {
        return new PcLanClient.Result(ok, text, d, "");
    }

    static Map<String, Object> one(String k, Object v) {
        Map<String, Object> m = new TreeMap<String, Object>();
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
        try {
            return v == null ? def : Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
