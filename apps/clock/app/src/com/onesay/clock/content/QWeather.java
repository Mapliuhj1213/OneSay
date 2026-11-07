package com.onesay.clock.content;

import android.content.Context;

import com.onesay.clock.BuildMode;
import com.onesay.clock.L;
import com.onesay.clock.Prefs;
import com.onesay.clock.core.RainLine;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * 和风天气：降雨提醒句的数据（0.1.6）。两个请求并行，各自超时，整体不超过调用方给的预算：
 * <ul>
 *   <li>分钟级降水 {@code GET https://{API Host}/v7/minutely/5m?location=lon,lat}</li>
 *   <li>逐小时预报 {@code GET https://{API Host}/v7/weather/24h?location=lon,lat}；v7 失败（官方标注「即将弃用」）再试一次新版
 *       {@code GET https://{API Host}/weather/v1/hourly/{lat}/{lon}?hours=24}</li>
 * </ul>
 * 认证：请求头 {@code X-QW-Api-Key}（官方 API KEY 方式）；API Host 是账号私有域名，在设置里填（同 OneSay 做法，不进代码 / 日志）。
 * 经纬度两位小数（约 1 km，官方上限）。日志不打 key / host / 坐标。
 *
 * <p>调试注入（只有 debug 包；release 包 BuildMode.RELEASE 时完全不看）：files/rain_fake_minutely.json 存在就不联网、不需要 key，
 * 直接用它（和风原样格式，时间整体平移到闹钟时刻）；files/rain_fake_hourly.json 可选（按整天平移到今天）。内容写 FAIL = 模拟失败。
 * TEST_CLEAR 会删掉这两个文件。
 */
public final class QWeather {
    private QWeather() { }

    public static final String FAKE_MIN = "rain_fake_minutely.json", FAKE_HOUR = "rain_fake_hourly.json";
    static final int CONNECT_MS = 4000, READ_MS = 5000;

    public static final class Outcome {
        public RainLine.Result result;
        public String why = "";      // 失败 / 跳过原因（日志用，不含 key/host/坐标）
        public boolean fake;
        public long ms;
    }

    static File fakeFile(Context c, String n) { return new File(c.getFilesDir(), n); }

    public static boolean fakePresent(Context c) { return !BuildMode.RELEASE && fakeFile(c, FAKE_MIN).isFile(); }

    /**
     * @param ll       {lat, lon}；null = 没有坐标 → 不说
     * @param alarmAt  判定时刻（闹钟时刻）
     * @param budgetMs 最多等多久（超时 = 不说）
     */
    public static Outcome fetch(final Context c, double[] ll, long alarmAt, long budgetMs) {
        long t0 = System.currentTimeMillis();
        Outcome o = new Outcome();
        TimeZone tz = TimeZone.getDefault();
        try {
            if (fakePresent(c)) {
                o.fake = true;
                String m = read(fakeFile(c, FAKE_MIN));
                File hf = fakeFile(c, FAKE_HOUR);
                String h = hf.isFile() ? read(hf) : null;
                List<RainLine.Pt> pts = "FAIL".equals(m.trim()) ? null : RainLine.rebase(RainLine.parseMinutely(m), alarmAt);
                List<RainLine.Hr> hrs = h == null || "FAIL".equals(h.trim()) ? null : RainLine.shiftToDay(RainLine.parseHourly(h), alarmAt, tz);
                o.result = RainLine.decide(pts, hrs, alarmAt, tz);
                o.why = pts == null ? "fake_minutely_fail" : "";
                return o;
            }
            Prefs p = new Prefs(c);
            final String key = p.qweatherKey(), host = p.qweatherHost();
            if (key.length() == 0 || host.length() == 0) { o.why = key.length() == 0 ? "no_key" : "no_host"; return o; }
            if (ll == null) { o.why = "no_location"; return o; }
            final String loc = RainLine.locParam(ll[0], ll[1]);
            final String lat = String.format(java.util.Locale.US, "%.2f", ll[0]), lon = String.format(java.util.Locale.US, "%.2f", ll[1]);
            ExecutorService ex = Executors.newFixedThreadPool(2);
            try {
                Future<String> fm = ex.submit(new Callable<String>() {
                    public String call() throws Exception { return get("https://" + host + "/v7/minutely/5m?location=" + loc, key); }
                });
                Future<String> fh = ex.submit(new Callable<String>() {
                    public String call() throws Exception {
                        try {
                            String v7 = get("https://" + host + "/v7/weather/24h?location=" + loc, key);
                            if (RainLine.parseHourly(v7) != null) return v7;
                        } catch (Exception ignored) { }
                        return get("https://" + host + "/weather/v1/hourly/" + lat + "/" + lon + "?hours=24", key);
                    }
                });
                long end = t0 + budgetMs;
                String m = waitFor(fm, end);
                List<RainLine.Pt> pts = m == null ? null : RainLine.parseMinutely(m);
                if (pts == null) { o.why = m == null ? "minutely_fail" : "minutely_bad"; o.result = RainLine.decide(null, null, alarmAt, tz); return o; }
                RainLine.Result early = RainLine.decide(pts, null, alarmAt, tz);
                if (!"NONE".equals(early.kind)) { o.result = early; return o; }   // 两小时内有雨：不用等逐小时
                String h = waitFor(fh, end);
                List<RainLine.Hr> hrs = h == null ? null : RainLine.parseHourly(h);
                if (hrs == null) o.why = "hourly_fail";
                o.result = RainLine.decide(pts, hrs, alarmAt, tz);
                return o;
            } finally {
                ex.shutdownNow();
            }
        } catch (Throwable t) {
            o.why = "error_" + t.getClass().getSimpleName();
            o.result = null;
            return o;
        } finally {
            o.ms = System.currentTimeMillis() - t0;
            L.i(c, "RAIN " + (o.fake ? "测试注入 " : "") + (o.result == null ? "NONE" : o.result.kind) + (o.why.length() == 0 ? "" : " why=" + o.why) + " " + o.ms + "ms");
        }
    }

    static String waitFor(Future<String> f, long end) {
        try { return f.get(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS); }
        catch (Exception e) { f.cancel(true); return null; }
    }

    /** GET + X-QW-Api-Key；和风默认 gzip，自己按 Content-Encoding 解。非 2xx → 异常（不带地址）。 */
    static String get(String url, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setInstanceFollowRedirects(false);   // 不跟随重定向：Key 头只发往配置的主机
            c.setRequestProperty("X-QW-Api-Key", key);
            c.setRequestProperty("Accept-Encoding", "gzip");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
            return new String(Http.readAll(in), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static String read(File f) throws Exception { return new String(Http.readAll(new FileInputStream(f)), "UTF-8"); }
}
