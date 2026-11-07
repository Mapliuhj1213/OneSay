package com.onesay.assistant.v2.rain;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

import com.onesay.assistant.v2.LocalDefaults;
import com.onesay.assistant.v2.kernel.FeatureSwitch;
import com.onesay.assistant.v2.net.HttpClient;
import com.onesay.assistant.v2.security.SecretPrefs;

import java.io.File;
import java.util.Locale;

/**
 * [rain] 分钟级降水的 Android 侧：配置（Key / Host / 开关）、粗定位、联网拉取、缓存。纯逻辑在 {@link RainAnalysis} / {@link RainVoice} / {@link RainPolicy}。
 *
 * <h3>配置与能力探测</h3>
 * <ul>
 *   <li>API Key：只存设备，{@code bixby_rain/qweather_key} 经 {@link SecretPrefs} 存 Keystore 密文（ks1）；在控制台「功能 › 分钟级降水」里填；</li>
 *   <li>API Host：{@link LocalDefaults#QWEATHER_HOST}（local-config，本账号私有，不进 git），控制台可改（同样加密存）；</li>
 *   <li>Key 或 Host 缺一个 → {@link #unavailableReason} 给出原因，语音如实说、岛不跑、控制台置灰写原因；</li>
 *   <li>岛提醒开关 {@code bixby_rain/island_alerts}，出厂 = {@link LocalDefaults#RAIN_ALERTS_DEFAULT}（开源 false / 上机版 true）；kill-file {@code rain.off} 全关。</li>
 * </ul>
 *
 * <h3>定位（省电）</h3>
 * 只用粗定位、不主动开 GPS：① 已授权时取各 provider 的最后已知位置（6 h 内）；② 三星系统天气里选中城市的经纬度（root 只读，缓存 12 h）。
 * 经纬度一律四舍五入到两位小数（约 1 km）再发出去；日志里不打坐标、不打 Host、不打 Key。
 *
 * <h3>测试注入</h3>
 * {@code /data/local/tmp/bixby_rain_fake.json}（只有 shell / root 能写；App 只按固定文件名读）存在时，拉取直接用它的内容（和风原样格式），
 * 不联网、不需要 Key——上机冒烟用，收尾删掉。日志标 {@code [RAIN] 测试注入}。
 */
public final class RainService {

    private static final String TAG = "Rain";
    public static final String PREFS = "bixby_rain";
    public static final String K_KEY = "qweather_key";
    public static final String K_HOST = "qweather_host";
    public static final String K_ALERTS = "island_alerts";
    public static final String KILL = "rain.off";
    public static final String FAKE_FILE = "bixby_rain_fake.json";

    public static final FeatureSwitch ALERTS = new FeatureSwitch(PREFS, K_ALERTS, LocalDefaults.RAIN_ALERTS_DEFAULT, KILL);

    private RainService() {}

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String key(Context c) {
        return SecretPrefs.get(sp(c), K_KEY).trim();
    }

    public static void setKey(Context c, String v) {
        SharedPreferences.Editor e = sp(c).edit();
        SecretPrefs.put(e, K_KEY, v == null ? "" : v.trim());
        e.apply();
    }

    public static String host(Context c) {
        String h = SecretPrefs.get(sp(c), K_HOST).trim();
        if (h.length() == 0) h = LocalDefaults.QWEATHER_HOST;
        return cleanHost(h);
    }

    public static void setHost(Context c, String v) {
        SharedPreferences.Editor e = sp(c).edit();
        SecretPrefs.put(e, K_HOST, cleanHost(v));
        e.apply();
    }

    /** 「https://xxx.re.qweatherapi.com/」→「xxx.re.qweatherapi.com」；不像域名 → ""。 */
    public static String cleanHost(String v) {
        if (v == null) return "";
        String h = v.trim().replaceFirst("^(?i)https?://", "");
        int k = h.indexOf('/');
        if (k >= 0) h = h.substring(0, k);
        return h.matches("[A-Za-z0-9.-]{3,253}") && h.contains(".") ? h.toLowerCase(Locale.ROOT) : "";
    }

    public static boolean killed(Context c) {
        return FeatureSwitch.killFilePresent(c, KILL);
    }

    static boolean fakePresent() {
        try {
            return new File(FeatureSwitch.TMP_ROOT, FAKE_FILE).isFile();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 能力探测：不能用的原因（能用 = null）。纯函数版见 {@link #reason(boolean, boolean, boolean)}。 */
    public static String unavailableReason(Context c) {
        if (fakePresent()) return null;
        return reason(killed(c), key(c).length() > 0, host(c).length() > 0);
    }

    public static String reason(boolean killed, boolean hasKey, boolean hasHost) {
        if (killed) return "已被停用（rain.off）";
        if (!hasHost) return "缺和风天气 API Host（开源构建不带，见 docs/RAIN-MINUTELY.md）";
        if (!hasKey) return "缺和风天气 API Key（在这里填，只存本机、加密保存）";
        return null;
    }

    public static boolean alertsOn(Context c) {
        return ALERTS.on(c) && unavailableReason(c) == null;
    }

    public static RainVoice.Fail reasonToFail(Context c) {
        if (killed(c)) return RainVoice.Fail.OFF;
        return RainVoice.Fail.NO_KEY;
    }

    // ------------------------------------------------------------------ 定位

    public static final class Loc {
        public final double lat, lon;
        public final String source;

        Loc(double lat, double lon, String source) {
            this.lat = round2(lat);
            this.lon = round2(lon);
            this.source = source;
        }

        /** 和风的 location 参数：经度,纬度（两位小数）。 */
        public String param() {
            return String.format(Locale.US, "%.2f,%.2f", lon, lat);
        }

        String cacheKey() {
            return param();
        }
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static final long LAST_KNOWN_MAX_AGE_MS = 6L * 3600000L;

    /** 粗定位；拿不到 → null。后台线程调。 */
    public static Loc locate(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (granted(app)) {
            Location best = null;
            try {
                LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) {
                    for (String p : new String[] {LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER}) {
                        try {
                            Location l = lm.getLastKnownLocation(p);
                            if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            if (best != null && System.currentTimeMillis() - best.getTime() < LAST_KNOWN_MAX_AGE_MS) {
                return new Loc(best.getLatitude(), best.getLongitude(), "last-known");
            }
        }
        try {
            double[] ll = com.onesay.assistant.v2.city.HomeCityProvider.sysWeatherLatLon(app);
            if (ll != null) return new Loc(ll[0], ll[1], "sys-weather");
        } catch (Throwable t) {
            Log.w(TAG, "[RAIN] 系统天气城市坐标读取失败: " + t.getClass().getSimpleName());
        }
        return null;
    }

    static boolean granted(Context c) {
        try {
            return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 拉取

    /** 一次拉取的结果：成功 = forecast 非空；失败 = fail 非空。 */
    public static final class Outcome {
        public final RainForecast forecast;
        public final RainVoice.Now now;
        public final RainVoice.Fail fail;
        public final String why;
        public final long fetchedAt;
        public final boolean fake;

        Outcome(RainForecast f, RainVoice.Now now, RainVoice.Fail fail, String why, long at, boolean fake) {
            this.forecast = f;
            this.now = now;
            this.fail = fail;
            this.why = why == null ? "" : why;
            this.fetchedAt = at;
            this.fake = fake;
        }

        public boolean ok() {
            return forecast != null;
        }

        /** 不是网络的错（重试也没用，退避到 60 分钟）。 */
        public boolean hard() {
            return fail == RainVoice.Fail.NO_KEY || fail == RainVoice.Fail.NO_LOCATION || fail == RainVoice.Fail.NO_COVERAGE
                    || fail == RainVoice.Fail.KEY_REJECTED || fail == RainVoice.Fail.OFF;
        }
    }

    private static final Object LOCK = new Object();
    private static volatile Outcome sLast;
    private static volatile String sLastLoc = "";
    /** 本进程累计请求数（控制台 / 状态行展示，用来核对估算）。 */
    private static volatile int sRequests;

    /** 语音用：maxAgeMs 内有同一位置的成功结果就复用（省一次请求）。 */
    public static final long VOICE_REUSE_MS = 4L * 60000L;

    public static Outcome last() {
        return sLast;
    }

    public static int requests() {
        return sRequests;
    }

    /**
     * 拉一次（后台线程）。wantNow = 同时拉实况（只有「天气怎么样」要）。reuseMs &gt; 0 时复用够新的上一次结果。
     */
    public static Outcome fetch(Context ctx, boolean wantNow, long reuseMs) {
        Context app = ctx.getApplicationContext();
        long t = System.currentTimeMillis();
        if (fakePresent()) return fake(app, t);
        String why = unavailableReason(app);
        if (why != null) return new Outcome(null, null, reasonToFail(app), why, t, false);
        Loc loc = locate(app);
        if (loc == null) return remember(new Outcome(null, null, RainVoice.Fail.NO_LOCATION, "定位不到", t, false), "");
        synchronized (LOCK) {
            Outcome prev = sLast;
            if (reuseMs > 0 && prev != null && prev.ok() && !wantNow && loc.cacheKey().equals(sLastLoc) && t - prev.fetchedAt < reuseMs) {
                return prev;
            }
        }
        String base = "https://" + host(app) + "/v7/";
        String key = key(app);
        HttpClient.Response r = HttpClient.execute(new HttpClient.Request(base + "minutely/5m?location=" + loc.param())
                .method("GET").header("X-QW-Api-Key", key).timeouts(4000, 6000));
        sRequests++;
        Outcome o;
        if (r == null || r.code < 0) {
            o = new Outcome(null, null, RainVoice.Fail.NETWORK, r == null || r.error == null ? "网络" : r.error.getClass().getSimpleName(), t, false);
        } else if (r.code != 200) {
            RainForecast.BadResponse e = RainForecast.parseError(r.code, r.body);
            RainVoice.Fail f = r.code == 401 || r.code == 403 || r.code == 429 ? RainVoice.Fail.KEY_REJECTED
                    : (e.noCoverage ? RainVoice.Fail.NO_COVERAGE : RainVoice.Fail.BAD_DATA);
            o = new Outcome(null, null, f, e.getMessage(), t, false);
        } else {
            try {
                RainForecast fc = RainForecast.parse(r.body);
                RainVoice.Now now = null;
                if (wantNow) {
                    HttpClient.Response rn = HttpClient.execute(new HttpClient.Request(base + "weather/now?location=" + loc.param())
                            .method("GET").header("X-QW-Api-Key", key).timeouts(3000, 4000));
                    sRequests++;
                    if (rn != null && rn.code == 200) now = RainVoice.Now.parse(rn.body);
                }
                o = new Outcome(fc, now, null, "", t, false);
            } catch (RainForecast.BadResponse e) {
                o = new Outcome(null, null, e.noCoverage ? RainVoice.Fail.NO_COVERAGE : RainVoice.Fail.BAD_DATA, e.getMessage(), t, false);
            }
        }
        Log.i(TAG, "[RAIN] 拉取 " + (o.ok() ? "成功 " + o.forecast.points.size() + " 点" : "失败 " + o.fail + "（" + o.why + "）")
                + " 定位=" + loc.source + " " + (r == null ? "" : r.latencyMs + " ms") + " 本进程第 " + sRequests + " 次");
        return remember(o, loc.cacheKey());
    }

    private static Outcome remember(Outcome o, String locKey) {
        synchronized (LOCK) {
            if (o.ok()) {
                sLast = o;
                sLastLoc = locKey;
            }
        }
        return o;
    }

    static Outcome fake(Context app, long t) {
        try {
            String body = com.onesay.assistant.v2.util.IoUtil.readAll(new java.io.FileInputStream(new File(FeatureSwitch.TMP_ROOT, FAKE_FILE)));
            RainForecast fc = RainForecast.parse(body);
            RainVoice.Now now = null;
            try {
                org.json.JSONObject o = new org.json.JSONObject(body);
                if (o.has("now")) now = RainVoice.Now.parse(body);
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "[RAIN] 测试注入：用 " + FAKE_FILE + "（" + fc.points.size() + " 点，不联网）");
            return remember(new Outcome(fc, now, null, "测试注入", t, true), "fake");
        } catch (Throwable e) {
            Log.w(TAG, "[RAIN] 测试注入文件读不懂: " + e.getClass().getSimpleName());
            return new Outcome(null, null, RainVoice.Fail.BAD_DATA, "测试注入文件读不懂", t, true);
        }
    }

    /** 状态行（不含 Key / Host / 坐标）。 */
    public static String status(Context c) {
        Outcome o = sLast;
        String why = unavailableReason(c);
        return "available=" + (why == null) + (why == null ? "" : "（" + why + "）") + " keyLen=" + key(c).length() + " hostSet=" + (host(c).length() > 0)
                + " alerts=" + ALERTS.on(c) + " killed=" + killed(c) + " fake=" + fakePresent() + " requests=" + sRequests
                + " last=" + (o == null ? "-" : (RainAnalysis.of(o.forecast, System.currentTimeMillis()) + " at " + o.fetchedAt));
    }
}
