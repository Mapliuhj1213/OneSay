package com.onesay.clock.vendor.samsung;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.onesay.clock.L;
import com.onesay.clock.content.Weather;
import com.onesay.clock.core.WeatherCode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 默认天气来源（0.1.1 起默认开，设置「使用手机天气」）：先走三星天气的 ContentProvider（不需要 root），被拒再 root 读三星天气库（com.sec.android.daemonapp/databases/WeatherClock），与锁屏显示的城市一致。
 * 和 v1 的区别：用 `su -c cat` 把库流进 App 自己创建的文件（SELinux 标签天然正确，不会留下 root 属主的文件），
 * 每个文件 8 秒超时；打开前关 mmap（防 WAL 撕裂 SIGBUS）。任何失败 → null（回落 open-meteo）。
 */
/** 可选厂商适配（三星天气，需 root）：开关 X_ROOT_WEATHER 默认关；通用路径不依赖本类。 */
public final class SamsungWeather {
    private SamsungWeather() { }

    /** 三星天气每 3 小时自动刷新；超过 6 小时没更新视为过期，回落网络天气。 */
    static final long MAX_AGE_MS = 6 * 3600_000L;
    static final String SRC = "/data/data/com.sec.android.daemonapp/databases/WeatherClock";

    static final String AUTH = "content://com.sec.android.daemonapp.ap.accuweather.provider/";
    static final String COLS = "COL_WEATHER_NAME, COL_WEATHER_CURRENT_TEMP, COL_WEATHER_HIGH_TEMP, COL_WEATHER_LOW_TEMP,"
            + " COL_WEATHER_WEATHER_TEXT, COL_WEATHER_DAY_PRECIPITATION_PROBABILITY, COL_WEATHER_UPDATE_TIME, COL_WEATHER_ICON_NUM";

    /** 先走三星天气自己的 ContentProvider（不需要 root）；被拒或没数据再 root 读库。都不行 → null。 */
    public static Weather.Facts read(Context c) {
        Weather.Facts f = viaProvider(c);
        if (f != null) return f;
        return viaDb(c);
    }

    /** 三星天气 ContentProvider：settings（温标、当前所选城市）+ weatherinfo。 */
    static Weather.Facts viaProvider(Context c) {
        android.content.ContentResolver cr = c.getContentResolver();
        Cursor st = null, w = null;
        try {
            String sel = null;
            int scale = 1;
            st = cr.query(android.net.Uri.parse(AUTH + "settings"), new String[]{"COL_SETTING_LAST_SEL_LOCATION", "COL_SETTING_TEMP_SCALE"}, null, null, null);
            if (st != null && st.moveToFirst()) { if (!st.isNull(0)) sel = st.getString(0); if (!st.isNull(1)) scale = st.getInt(1); }
            String[] cols = COLS.split(",\\s*");
            String[] withKey = new String[cols.length + 2];
            System.arraycopy(cols, 0, withKey, 0, cols.length);
            withKey[cols.length] = "COL_WEATHER_KEY"; withKey[cols.length + 1] = "COL_WEATHER_ORDER";
            w = cr.query(android.net.Uri.parse(AUTH + "weatherinfo"), withKey, null, null, null);
            if (w == null) { L.w(c, "WX samsung-provider 无结果"); return null; }
            int pick = -1, bestOrder = Integer.MAX_VALUE;
            while (w.moveToNext()) {
                String k = w.isNull(cols.length) ? "" : w.getString(cols.length);
                int ord = w.isNull(cols.length + 1) ? 999 : w.getInt(cols.length + 1);
                if (sel != null && sel.equals(k)) { pick = w.getPosition(); break; }
                if (ord < bestOrder) { bestOrder = ord; pick = w.getPosition(); }
            }
            if (pick < 0 || !w.moveToPosition(pick)) { L.w(c, "WX samsung-provider 没有城市"); return null; }
            String pickKey = w.isNull(cols.length) ? null : w.getString(cols.length);
            Weather.Facts f = parse(c, w, scale, "samsung-provider");
            if (f != null) providerLatLon(cr, pickKey, f);
            return f;
        } catch (SecurityException e) {
            L.w(c, "WX samsung-provider 被拒（" + e.getClass().getSimpleName() + "），改 root 读库");
            return null;
        } catch (Throwable t) {
            L.w(c, "WX samsung-provider 失败：" + t.getClass().getSimpleName());
            return null;
        } finally {
            if (st != null) try { st.close(); } catch (Throwable ignored) { }
            if (w != null) try { w.close(); } catch (Throwable ignored) { }
        }
    }

    static final String[] LL_COLS = {"COL_WEATHER_KEY", "COL_WEATHER_LATITUDE", "COL_WEATHER_LONGITUDE"};

    /**
     * 0.1.6：所选城市的经纬度（给和风分钟级降水用，城市级精度）。单独查一次、任何异常都吞掉——provider 不认这两列也不影响天气本身。
     * 和 OneSay HomeCityProvider.sysWeatherLatLon 读的是同一张表的同两列（那边走 root 库已真机验证；provider 是否暴露这两列未验证）。
     */
    static void providerLatLon(android.content.ContentResolver cr, String key, Weather.Facts f) {
        Cursor ll = null;
        try {
            ll = cr.query(android.net.Uri.parse(AUTH + "weatherinfo"), LL_COLS, null, null, null);
            pickLatLon(ll, key, f);
        } catch (Throwable ignored) {
        } finally {
            if (ll != null) try { ll.close(); } catch (Throwable ignored) { }
        }
    }

    /** 游标列 = LL_COLS；key 对上的那行优先，否则第一行可用的。 */
    static void pickLatLon(Cursor ll, String key, Weather.Facts f) {
        if (ll == null) return;
        double la = Double.NaN, lo = Double.NaN;
        while (ll.moveToNext()) {
            String k = ll.isNull(0) ? null : ll.getString(0);
            if (ll.isNull(1) || ll.isNull(2)) continue;
            double a, b;
            try { a = Double.parseDouble(ll.getString(1).trim()); b = Double.parseDouble(ll.getString(2).trim()); } catch (Throwable bad) { continue; }
            if (a == 0 && b == 0) continue;
            boolean hit = key != null && key.equals(k);
            if (hit || Double.isNaN(la)) { la = a; lo = b; }
            if (hit) break;
        }
        if (!Double.isNaN(la)) { f.lat = la; f.lon = lo; }
    }

    /** 当前行（列顺序同 COLS）→ Facts；过期/缺字段 → null。 */
    static Weather.Facts parse(Context c, Cursor cur, int scale, String src) {
        Weather.Facts f = new Weather.Facts();
        f.city = cur.isNull(0) ? "" : cur.getString(0);
        f.temp = WeatherCode.celsius(cur.isNull(1) ? Double.NaN : cur.getDouble(1), scale);
        f.tmax = WeatherCode.celsius(cur.isNull(2) ? Double.NaN : cur.getDouble(2), scale);
        f.tmin = WeatherCode.celsius(cur.isNull(3) ? Double.NaN : cur.getDouble(3), scale);
        f.desc = cur.isNull(4) ? "" : cur.getString(4);
        f.rain = cur.isNull(5) ? -1 : cur.getInt(5);
        long up = cur.isNull(6) ? 0 : cur.getLong(6);
        int icon = cur.isNull(7) ? -1 : cur.getInt(7);
        f.key = WeatherCode.fromSamsung(f.desc, icon);
        f.src = src;
        f.at = System.currentTimeMillis();
        long age = up == 0 ? 0 : System.currentTimeMillis() - up;
        f.ageMin = age / 60000;
        f.ok = !Double.isNaN(f.temp) && f.desc.length() > 0 && age < MAX_AGE_MS;
        if (!f.ok) L.w(c, "WX " + src + " 不可用 temp=" + f.temp + " desc=" + f.desc + " ageMin=" + f.ageMin);
        return f.ok ? f : null;
    }

    /** root 读库（provider 不可用时）。 */
    static Weather.Facts viaDb(Context c) {
        File d = new File(c.getCacheDir(), "swx");
        d.mkdirs();
        File main = new File(d, "WeatherClock"), wal = new File(d, "WeatherClock-wal");
        new File(d, "WeatherClock-shm").delete();
        if (!cat(SRC, main, 8000)) { L.w(c, "WX samsung-db 读不到（su 拒绝/超时/无此库）"); return null; }
        cat(SRC + "-wal", wal, 8000);
        SQLiteDatabase db = null;
        Cursor cur = null;
        try {
            db = SQLiteDatabase.openDatabase(main.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
            Cursor pc = db.rawQuery("PRAGMA mmap_size=0", null);
            pc.close();
            String sel = null;
            int scale = 1;
            cur = db.rawQuery("SELECT COL_SETTING_LAST_SEL_LOCATION, COL_SETTING_TEMP_SCALE FROM TABLE_SETTING_INFO LIMIT 1", null);
            if (cur.moveToFirst()) { if (!cur.isNull(0)) sel = cur.getString(0); if (!cur.isNull(1)) scale = cur.getInt(1); }
            cur.close();
            cur = sel != null ? db.rawQuery("SELECT " + COLS + " FROM TABLE_WEATHER_INFO WHERE COL_WEATHER_KEY = ? LIMIT 1", new String[]{sel})
                    : db.rawQuery("SELECT " + COLS + " FROM TABLE_WEATHER_INFO ORDER BY COL_WEATHER_ORDER LIMIT 1", null);
            if (!cur.moveToFirst()) return null;
            Weather.Facts f = parse(c, cur, scale, "samsung-db");
            if (f != null) {
                cur.close();
                cur = null;
                try {
                    cur = sel != null ? db.rawQuery("SELECT COL_WEATHER_KEY, COL_WEATHER_LATITUDE, COL_WEATHER_LONGITUDE FROM TABLE_WEATHER_INFO WHERE COL_WEATHER_KEY = ?", new String[]{sel})
                            : db.rawQuery("SELECT COL_WEATHER_KEY, COL_WEATHER_LATITUDE, COL_WEATHER_LONGITUDE FROM TABLE_WEATHER_INFO ORDER BY COL_WEATHER_ORDER LIMIT 1", null);
                    pickLatLon(cur, sel, f);
                } catch (Throwable ignored) { }
            }
            return f;
        } catch (Throwable t) {
            L.w(c, "WX samsung-db 解析失败：" + t.getClass().getSimpleName());
            return null;
        } finally {
            if (cur != null) try { cur.close(); } catch (Throwable ignored) { }
            if (db != null) try { db.close(); } catch (Throwable ignored) { }
            main.delete(); wal.delete(); new File(d, "WeatherClock-shm").delete();
        }
    }

    /** su -c cat src → dst（App 自己写文件），超时 destroy。 */
    static boolean cat(String src, File dst, long timeoutMs) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", "cat '" + src + "'").start();
            final Process fp = p;
            final InputStream in = p.getInputStream();
            final FileOutputStream o = new FileOutputStream(dst);
            final long[] n = {0};
            Thread t = new Thread(new Runnable() {
                public void run() {
                    byte[] b = new byte[16384];
                    int k;
                    try { while ((k = in.read(b)) > 0) { o.write(b, 0, k); n[0] += k; } } catch (Exception ignored) { }
                }
            });
            t.setDaemon(true);
            t.start();
            t.join(timeoutMs);
            boolean done = !t.isAlive();
            if (!done) fp.destroy();
            t.join(300);
            o.close();
            return done && n[0] > 1024;
        } catch (Throwable e) {
            return false;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
        }
    }
}
