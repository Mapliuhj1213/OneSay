package com.onesay.assistant.v2.whitelist;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * [app-whitelist-1116] 手机端应用白名单的状态（纯 Java，JVM 可测）：存盘（App 私有目录 app_whitelist.json，同步格式原样）、
 * 审计日志（whitelist_audit.log，只记类别和应用 id，1 MB 轮转）、本机加删、合并另一端。
 * 规则在 {@link AppWhitelist} / {@link ActionPolicy}（和电脑逐字相同）。没 {@link #init} 过 = 空白名单（JVM 单测、宿主没起来时都不放行）。
 */
public final class WhitelistState {

    static final long AUDIT_MAX = 1000000L;

    private static volatile WhitelistState sInst = new WhitelistState(null, "phone");

    public static WhitelistState get() { return sInst; }

    /** 宿主启动时调（幂等：同一目录不重复读）。 */
    public static synchronized WhitelistState init(File dir, String device) {
        WhitelistState cur = sInst;
        if (cur.dir != null && cur.dir.equals(dir)) return cur;
        sInst = new WhitelistState(dir, device);
        return sInst;
    }

    /** 测试用：换一个实例。 */
    public static void setForTest(WhitelistState s) { sInst = s == null ? new WhitelistState(null, "phone") : s; }

    public interface Listener {
        /** 本机加删了（宿主接上「推给电脑」）。 */
        void changed();
    }

    public interface Clock {
        long now();
    }

    private final File dir;
    private final String device;
    private AppWhitelist wl;
    public volatile Listener listener;
    public volatile Clock clock = new Clock() {
        @Override public long now() { return System.currentTimeMillis(); }
    };

    public WhitelistState(File dir, String device) {
        this.dir = dir;
        this.device = device == null || device.length() == 0 ? "phone" : device;
        String text = null;
        if (dir != null) {
            File f = new File(dir, "app_whitelist.json");
            if (f.isFile()) {
                try {
                    text = read(f);
                } catch (IOException e) {
                    text = "坏";
                }
            }
        }
        wl = AppWhitelist.load(text);
    }

    public synchronized boolean broken() { return wl.broken; }

    public synchronized boolean contains(String platform, String id) { return !wl.broken && wl.contains(platform, id); }

    public synchronized List<AppWhitelist.Entry> live() { return wl.live(); }

    public synchronized String json() { return wl.toJson(device); }

    /** 本机加（调用方已经弹过风险确认并得到真人「确定」）。null = 成功。 */
    public String add(String platform, String id, String name) {
        String why;
        synchronized (this) {
            if (wl.broken) return "白名单文件读坏了，先「清空重建」";
            why = wl.add(platform, id, name, device, "phone", clock.now());
            if (why == null) {
                save();
                log("add", platform, AppWhitelist.normId(platform, id), "本机");
            }
        }
        if (why == null) fire();
        return why;
    }

    public boolean remove(String platform, String id) {
        boolean ok;
        synchronized (this) {
            if (wl.broken) return false;
            ok = wl.remove(platform, id, device, "phone", clock.now());
            if (ok) {
                save();
                log("remove", platform, AppWhitelist.normId(platform, id), "本机");
            }
        }
        if (ok) fire();
        return ok;
    }

    /**
     * 2026-11-16 用户定：电脑同步来的条目在手机上确认一次才生效（用户在控制台看过风险说明、点「确认生效」）。
     * null = 成功，否则原因。确认只记在本机存盘里，不同步。
     */
    public synchronized String confirm(String platform, String id) {
        if (wl.broken) return "白名单文件读坏了，先「清空重建」";
        if (!wl.confirm(platform, id)) return wl.isConfirmed(platform, id) ? "已经生效了" : "没有这个待确认的应用";
        save();
        log("confirm", platform, AppWhitelist.normId(platform, id), "本机");
        return null;
    }

    /** 电脑同步来、手机上还没确认的（不放行）。 */
    public synchronized List<AppWhitelist.Entry> pending() { return wl.broken ? new ArrayList<AppWhitelist.Entry>() : wl.pending(); }

    public synchronized boolean isConfirmed(String platform, String id) { return !wl.broken && wl.isConfirmed(platform, id); }

    public synchronized void reset() {
        wl = new AppWhitelist();
        save();
        log("reset", "", "", "本机");
    }

    /** 合并另一端整份；返回对方带来的新放行条目。坏文档抛 IllegalArgumentException，本机读坏了抛 IllegalStateException。 */
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

    private void fire() {
        Listener l = listener;
        if (l != null) try {
            l.changed();
        } catch (RuntimeException ignored) {
        }
    }

    private void save() {
        if (dir == null) return;
        try {
            dir.mkdirs();
            File tmp = new File(dir, "app_whitelist.json.tmp"), f = new File(dir, "app_whitelist.json");
            write(tmp, wl.toLocalJson(device), false);   // 本机存盘带确认记录；同步用 json()（不带）
            if (!tmp.renameTo(f)) {
                write(f, wl.toLocalJson(device), false);
                tmp.delete();
            }
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ 审计

    public void audit(ActionPolicy.Facts f, ActionPolicy.Decision d, String action) {
        line(ActionPolicy.auditLine(clock.now(), "phone", f, d, action));
    }

    void log(String op, String platform, String id, String by) {
        line(PrivacyScan.scrub(clock.now() + "\tphone\t" + platform + ":" + (id == null ? "" : id) + "\t" + op + "\t" + by));
    }

    private synchronized void line(String s) {
        if (dir == null) {
            lastLines.add(s);
            if (lastLines.size() > 50) lastLines.remove(0);
            return;
        }
        try {
            dir.mkdirs();
            File f = new File(dir, "whitelist_audit.log");
            if (f.length() > AUDIT_MAX) {
                File old = new File(dir, "whitelist_audit.old.log");
                old.delete();
                f.renameTo(old);
            }
            write(f, s + "\n", true);
        } catch (IOException ignored) {
        }
    }

    /** 没有目录时（JVM 测试）审计写在内存里。 */
    final List<String> lastLines = new ArrayList<String>();

    public synchronized List<String> recentAudit(int n) {
        List<String> all = new ArrayList<String>();
        if (dir == null) all.addAll(lastLines);
        else {
            File f = new File(dir, "whitelist_audit.log");
            if (f.isFile()) try {
                for (String s : read(f).split("\n")) if (s.length() > 0) all.add(s);
            } catch (IOException ignored) {
            }
        }
        return new ArrayList<String>(all.subList(Math.max(0, all.size() - n), all.size()));
    }

    static String read(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    static void write(File f, String s, boolean append) throws IOException {
        OutputStream o = new FileOutputStream(f, append);
        try {
            o.write(s.getBytes("UTF-8"));
        } finally {
            o.close();
        }
    }
}
