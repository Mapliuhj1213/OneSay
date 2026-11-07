package com.onesay.assistant.v2.city;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [lane C 第 2 项] 常用城市（Android 侧）：手动设置 → 三星系统天气城市列表 → 定位反查 → 空（旧行为）。
 * 裁决规则见 {@link HomeCityPolicy}。
 *
 * <p>线程：{@link #resolve} 可能走 root IPC 与网络，<b>只能在后台线程调</b>（天气问句检索本来就在后台线程）。
 * 结果缓存在 {@code bixby_city}（6 h；什么都没拿到时 30 min 内不重试）。
 *
 * <p>Kill-switch：{@code city.off}（文件存在 = 只用手动设置，和 R15 一样）。
 */
public final class HomeCityProvider {

    private static final String TAG = "HomeCity";
    public static final String PREF = "bixby_city";
    private static final String K_CITY = "city";
    private static final String K_SOURCE = "source";
    private static final String K_AT = "at";
    private static final String K_LIST = "sys_list";
    public static final String KILL = "city.off";

    private static final Object LOCK = new Object();

    private HomeCityProvider() {}

    /** 天气问句要补的城市（可能是空串 = 不补）。后台线程调用。 */
    public static String cityForWeather(Context ctx, String manual) {
        return resolve(ctx, manual).city;
    }

    public static HomeCityPolicy.Result resolve(Context ctx, String manual) {
        String m = HomeCityPolicy.clean(manual);
        if (m.length() > 0) return new HomeCityPolicy.Result(m, HomeCityPolicy.Source.MANUAL);
        if (ctx == null) return new HomeCityPolicy.Result("", HomeCityPolicy.Source.NONE);
        Context app = ctx.getApplicationContext();
        try {
            if (com.onesay.assistant.v2.kernel.FeatureSwitch.killFilePresent(app, KILL)) {
                return new HomeCityPolicy.Result("", HomeCityPolicy.Source.NONE);
            }
        } catch (Throwable ignored) {
        }
        synchronized (LOCK) {
            SharedPreferences sp = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            long at = sp.getLong(K_AT, 0L);
            String cached = sp.getString(K_CITY, "");
            long now = System.currentTimeMillis();
            if (!HomeCityPolicy.needResolve(m, at, cached == null || cached.length() == 0, now)) {
                HomeCityPolicy.Source src;
                try {
                    src = HomeCityPolicy.Source.valueOf(sp.getString(K_SOURCE, "NONE"));
                } catch (Throwable t) {
                    src = HomeCityPolicy.Source.NONE;
                }
                return new HomeCityPolicy.Result(cached, src);
            }
            String sys = "";
            List<String> list = readSysWeatherCities(app);
            if (!list.isEmpty()) sys = list.get(0);
            boolean granted = locationGranted(app);
            String loc = "";
            if (sys.length() == 0 && granted) loc = locationCity(app);
            HomeCityPolicy.Result r = HomeCityPolicy.decide(m, sys, loc, granted);
            sp.edit().putString(K_CITY, r.city).putString(K_SOURCE, r.source.name()).putLong(K_AT, now)
                    .putString(K_LIST, join(list)).apply();
            Log.i(TAG, "[CITY] 常用城市 → " + r.source + "（系统天气 " + list.size() + " 座" + (granted ? "" : "，定位未授权") + "）");
            return r;
        }
    }

    /** 清缓存（设置页 / 调试广播用）：下次天气问句重新读。 */
    public static void invalidate(Context ctx) {
        ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();
    }

    public static String dump(Context ctx) {
        SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return "city=" + sp.getString(K_CITY, "") + " source=" + sp.getString(K_SOURCE, "") + " at=" + sp.getLong(K_AT, 0L)
                + " sysList=" + sp.getString(K_LIST, "") + " locGranted=" + locationGranted(ctx);
    }

    // ------------------------------------------------------------------ 系统天气（root，只读）

    /** 读三星系统天气的城市列表（选中的排第一）。拿不到 → 空列表。 */
    static List<String> readSysWeatherCities(Context app) {
        return readSysWeatherCities(app, null);
    }

    // ★ feat/rain-minutely：分钟级降水的定位兜底——系统天气里选中城市（没选中 = 排第一的）的经纬度，缓存 12 h
    private static final String K_LAT = "sys_lat", K_LON = "sys_lon", K_LL_AT = "sys_ll_at";
    public static final long SYS_LL_TTL_MS = 12L * 3600000L;

    /**
     * 系统天气城市的经纬度 {lat, lon}；拿不到 → null。走 root 复制库（只读），12 h 内用缓存。<b>后台线程调</b>。
     * 只给分钟级降水用（城市级精度，比 GPS 省电得多，也不用开定位）。
     */
    public static double[] sysWeatherLatLon(Context ctx) {
        Context app = ctx.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long at = sp.getLong(K_LL_AT, 0L);
        if (at > 0 && now - at < SYS_LL_TTL_MS && sp.contains(K_LAT)) {
            return new double[] {Double.longBitsToDouble(sp.getLong(K_LAT, 0L)), Double.longBitsToDouble(sp.getLong(K_LON, 0L))};
        }
        synchronized (LOCK) {
            double[] ll = new double[] {Double.NaN, Double.NaN};
            readSysWeatherCities(app, ll);
            if (Double.isNaN(ll[0]) || Double.isNaN(ll[1]) || (ll[0] == 0 && ll[1] == 0)) return null;
            sp.edit().putLong(K_LAT, Double.doubleToLongBits(ll[0])).putLong(K_LON, Double.doubleToLongBits(ll[1])).putLong(K_LL_AT, now).apply();
            Log.i(TAG, "[CITY] 系统天气城市经纬度已缓存（12 h，城市级）");
            return ll;
        }
    }

    /** latLonOut != null 时顺带取选中城市的经纬度（{lat, lon}；取不到保持原值）。 */
    static List<String> readSysWeatherCities(Context app, double[] latLonOut) {
        List<String> out = new ArrayList<String>();
        File dir = new File(app.getFilesDir(), "wdb");
        SQLiteDatabase db = null;
        Cursor cur = null;
        try {
            if (!dir.exists() && !dir.mkdirs()) return out;
            File main = new File(dir, "WeatherClock");
            File wal = new File(dir, "WeatherClock-wal");
            File shm = new File(dir, "WeatherClock-shm");
            // 目标文件由 App 自己建（标签对），root 只往里写；-shm 删掉交给 SQLite 用 WAL 重建
            if (shm.exists()) shm.delete();
            if (!main.exists()) main.createNewFile();
            if (!wal.exists()) wal.createNewFile();
            // 2026-10-25 exec 守护 v4：先清空自己的 WAL 副本，再拆成两条单命令 cp（见 HomeCityPolicy.copySteps）
            new java.io.FileOutputStream(wal, false).close();
            java.util.List<com.onesay.assistant.v2.executor.ShellScript.Step> steps = HomeCityPolicy.copySteps(dir.getAbsolutePath());
            com.onesay.assistant.v2.executor.ShellExecutor exec = new com.onesay.assistant.v2.executor.ShellExecutor(app);
            com.onesay.assistant.v2.executor.ShellExecutor.ShellResponse r1 = com.onesay.assistant.v2.executor.ShellScript.exec(exec, steps.get(0));
            if (r1 != null && r1.isSuccess()) com.onesay.assistant.v2.executor.ShellScript.exec(exec, steps.get(1));   // WAL 可能不存在：失败不算
            com.onesay.assistant.v2.executor.ShellExecutor.ShellResponse resp = r1;
            long[] sz = new long[] {main.length(), wal.length()};
            if (resp == null || !resp.isSuccess() || sz[0] + sz[1] < HomeCityPolicy.MIN_DB_BYTES) {
                Log.w(TAG, "[CITY] 系统天气库复制失败（rc=" + (resp == null ? "?" : resp.rc) + "）");
                return out;
            }
            db = SQLiteDatabase.openDatabase(main.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            try {   // 关 mmap（防撕裂 WAL 导致 SIGBUS，见晨间播报 SysWeather）
                Cursor pc = db.rawQuery("PRAGMA mmap_size=0", null);
                if (pc != null) pc.close();
            } catch (Throwable ignored) {
            }
            String sel = null;
            try {
                cur = db.rawQuery("SELECT COL_SETTING_LAST_SEL_LOCATION FROM TABLE_SETTING_INFO LIMIT 1", null);
                if (cur.moveToFirst() && !cur.isNull(0)) sel = cur.getString(0);
            } catch (Throwable t) {
                Log.w(TAG, "[CITY] 读选中城市失败: " + t.getMessage());
            } finally {
                if (cur != null) cur.close();
                cur = null;
            }
            List<HomeCityPolicy.SysCity> rows = new ArrayList<HomeCityPolicy.SysCity>();
            cur = db.rawQuery("SELECT COL_WEATHER_KEY, COL_WEATHER_NAME, COL_WEATHER_ORDER FROM TABLE_WEATHER_INFO", null);
            while (cur.moveToNext()) {
                rows.add(new HomeCityPolicy.SysCity(cur.isNull(0) ? null : cur.getString(0),
                        cur.isNull(1) ? null : cur.getString(1), cur.isNull(2) ? Integer.MAX_VALUE : cur.getInt(2)));
            }
            out.addAll(HomeCityPolicy.orderSysCities(rows, sel));
            if (latLonOut != null && latLonOut.length >= 2) {
                if (cur != null) cur.close();
                cur = null;
                try {
                    cur = db.rawQuery("SELECT COL_WEATHER_KEY, COL_WEATHER_LATITUDE, COL_WEATHER_LONGITUDE, COL_WEATHER_ORDER FROM TABLE_WEATHER_INFO", null);
                    int bestOrder = Integer.MAX_VALUE;
                    boolean gotSel = false;
                    while (cur.moveToNext()) {
                        String key = cur.isNull(0) ? null : cur.getString(0);
                        if (cur.isNull(1) || cur.isNull(2)) continue;
                        double la, lo;
                        try {
                            la = Double.parseDouble(cur.getString(1).trim());
                            lo = Double.parseDouble(cur.getString(2).trim());
                        } catch (Throwable bad) {
                            continue;
                        }
                        int ord = cur.isNull(3) ? Integer.MAX_VALUE - 1 : cur.getInt(3);
                        boolean isSel = sel != null && sel.equals(key);
                        if (gotSel && !isSel) continue;
                        if (isSel || ord < bestOrder) {
                            latLonOut[0] = la;
                            latLonOut[1] = lo;
                            bestOrder = ord;
                            gotSel = isSel;
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "[CITY] 读系统天气经纬度失败: " + t.getClass().getSimpleName());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "[CITY] 解析系统天气库失败: " + t.getMessage());
        } finally {
            if (cur != null) try { cur.close(); } catch (Throwable ignored) { }
            if (db != null) try { db.close(); } catch (Throwable ignored) { }
        }
        return out;
    }

    // ------------------------------------------------------------------ 定位

    static boolean locationGranted(Context c) {
        try {
            return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 最后一次已知位置 → 城市。不主动开 GPS（不耗电、不等待）；没有已知位置 → 空串。 */
    static String locationCity(Context c) {
        Location best = null;
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return "";
            for (String p : new String[] {LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (SecurityException se) {
                    return "";
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return "";
        }
        if (best == null) return "";
        return reverse(c, best.getLatitude(), best.getLongitude());
    }

    public static final String K_NET_REVERSE = "net_reverse";

    /** 联网反查城市（第三方地理编码服务）是否已由用户打开。默认 false。 */
    public static boolean isNetReverseEnabled(Context c) {
        try {
            return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(K_NET_REVERSE, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setNetReverseEnabled(Context c, boolean on) {
        c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(K_NET_REVERSE, on).remove(K_AT).apply();
    }

    /** 经纬度 → 城市：系统 Geocoder（国行机多半没有后端）→ BigDataCloud → Nominatim。 */
    static String reverse(Context c, double lat, double lon) {
        try {
            if (android.location.Geocoder.isPresent()) {
                List<android.location.Address> a = new android.location.Geocoder(c, Locale.CHINA).getFromLocation(lat, lon, 1);
                if (a != null && !a.isEmpty()) {
                    String city = HomeCityPolicy.clean(a.get(0).getLocality());
                    if (city.length() == 0) city = HomeCityPolicy.clean(a.get(0).getSubAdminArea());
                    if (city.length() > 0) return city;
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "[CITY] Geocoder 不可用: " + t.getMessage());
        }
        // 联网反查（BigDataCloud / Nominatim）会把大致位置发给第三方：默认关，用户在「设备能力与功能 › 隐私与外部请求」里打开
        if (!isNetReverseEnabled(c)) return "";
        // 只发到约 1 km 精度（两位小数）：城市级反查够用，少交出位置
        String la = String.format(Locale.US, "%.2f", lat), lo = String.format(Locale.US, "%.2f", lon);
        com.onesay.assistant.v2.net.HttpClient.Response r = com.onesay.assistant.v2.net.HttpClient.execute(
                new com.onesay.assistant.v2.net.HttpClient.Request("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude="
                        + la + "&longitude=" + lo + "&localityLanguage=zh").method("GET").timeouts(3000, 4000));
        String city = r != null && r.code == 200 ? HomeCityPolicy.parseBigDataCloud(r.body) : "";
        if (city.length() > 0) return city;
        r = com.onesay.assistant.v2.net.HttpClient.execute(
                new com.onesay.assistant.v2.net.HttpClient.Request("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=10&accept-language=zh-CN&lat="
                        + la + "&lon=" + lo).method("GET").timeouts(3000, 4000).header("User-Agent", "OneSay/2 (personal device)"));
        return r != null && r.code == 200 ? HomeCityPolicy.parseNominatim(r.body) : "";
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append('|');
            sb.append(s);
        }
        return sb.toString();
    }
}
