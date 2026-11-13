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
 * [lane C 第 2 项] 常用城市（Android 侧）：手动设置 → 默认天气 App 屏幕上的城市（{@link WeatherAppCity}，用户点了才读）→ 定位反查 → 空。
 * 裁决规则见 {@link HomeCityPolicy}。
 *
 * <p>线程：{@link #resolve} 可能走网络，<b>只能在后台线程调</b>（天气问句检索本来就在后台线程）。
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
            String sys = WeatherAppCity.cached(app);   // 默认天气 App 屏幕上读到的城市（用户在控制台触发过才有）
            List<String> list = new ArrayList<String>();
            if (sys.length() > 0) list.add(sys);
            boolean granted = locationGranted(app);
            String loc = "";
            if (sys.length() == 0 && granted) loc = locationCity(app);
            HomeCityPolicy.Result r = HomeCityPolicy.decide(m, sys, loc, granted);
            sp.edit().putString(K_CITY, r.city).putString(K_SOURCE, r.source.name()).putLong(K_AT, now)
                    .putString(K_LIST, join(list)).apply();
            Log.i(TAG, "[CITY] 常用城市 → " + r.source + (granted ? "" : "（定位未授权）"));
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
