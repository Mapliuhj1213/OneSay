package com.onesay.clock.content;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import com.onesay.clock.L;
import com.onesay.clock.Prefs;
import com.onesay.clock.core.Flags;
import com.onesay.clock.core.Template;
import com.onesay.clock.core.WeatherCode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 天气：默认不需要 root。坐标 = 系统最近一次定位（粗略定位权限）→ 设置里选的城市 → 都没有就不念天气。
 * 数据 = open-meteo（免 key），坐标来自系统定位或用户设置的城市。
 * 每次成功写 files/wx.json（响铃时成品失效、现拼开场就用它，3 小时内有效）。
 */
public final class Weather {
    private Weather() { }

    public static final class Facts {
        public boolean ok;
        public double temp = Double.NaN, tmin = Double.NaN, tmax = Double.NaN;
        public int rain = -1;
        public String key, desc = "", city = "", src = "none";
        public long at;
        /** 源数据距上次更新（分钟；有的数据源才有）。 */
        public long ageMin = -1;
        /** 天气所在城市的经纬度（0.1.6，给和风降水用；定位 / 备用城市；没有 = NaN）。 */
        public double lat = Double.NaN, lon = Double.NaN;

        public Template.Wx toWx() {
            Template.Wx w = new Template.Wx();
            w.ok = ok; w.temp = temp; w.tmin = tmin; w.tmax = tmax; w.key = key; w.rainProb = rain;
            return w;
        }

        String toJson() throws Exception {
            JSONObject o = new JSONObject().put("ok", ok).put("rain", rain).put("key", key == null ? "" : key)
                    .put("desc", desc).put("city", city).put("src", src).put("at", at);
            if (!Double.isNaN(temp)) o.put("temp", temp);
            if (!Double.isNaN(tmin)) o.put("tmin", tmin);
            if (!Double.isNaN(tmax)) o.put("tmax", tmax);
            if (!Double.isNaN(lat) && !Double.isNaN(lon)) o.put("lat", lat).put("lon", lon);
            return o.toString();
        }

        static Facts fromJson(String s) {
            try {
                JSONObject o = new JSONObject(s);
                Facts f = new Facts();
                f.ok = o.optBoolean("ok"); f.rain = o.optInt("rain", -1);
                f.key = o.optString("key", ""); if (f.key.length() == 0) f.key = null;
                f.desc = o.optString("desc"); f.city = o.optString("city"); f.src = o.optString("src"); f.at = o.optLong("at");
                f.temp = o.has("temp") ? o.optDouble("temp") : Double.NaN;
                f.tmin = o.has("tmin") ? o.optDouble("tmin") : Double.NaN;
                f.tmax = o.has("tmax") ? o.optDouble("tmax") : Double.NaN;
                f.lat = o.has("lat") ? o.optDouble("lat") : Double.NaN;
                f.lon = o.has("lon") ? o.optDouble("lon") : Double.NaN;
                return f;
            } catch (Exception e) { return null; }
        }
    }

    public static final long CACHE_OK_MS = 3 * 3600_000L;

    public static Facts get(Context c) {
        Prefs p = new Prefs(c);
        Facts f = null;
        if (f == null) {
            double[] ll = coords(c, p);
            if (ll != null) f = openMeteo(ll[0], ll[1]);
            if (f != null && ll != null && ll.length > 2) f.city = cityName(p);
            if (f != null && ll != null) { f.lat = ll[0]; f.lon = ll[1]; }
        }
        if (f == null) { f = new Facts(); L.w(c, "WX unavailable"); }
        if (f.ok) save(c, f);
        return f;
    }

    /**
     * 和风降水用的坐标 {lat, lon}（0.1.6）：① 这次天气自带的（open-meteo 用的那个坐标）
     * → ② 系统最近定位 / 设置里的备用城市（同 open-meteo）→ 都没有 = null（降雨句不说）。
     */
    public static double[] rainCoords(Context c, Facts f) {
        if (f != null && !Double.isNaN(f.lat) && !Double.isNaN(f.lon)) return new double[]{f.lat, f.lon};
        double[] ll = coords(c, new Prefs(c));
        return ll == null ? null : new double[]{ll[0], ll[1]};
    }

    static String cityName(Prefs p) { return p.s(Flags.CITY, ""); }

    /** 坐标：[lat, lon] 来自定位；[lat, lon, 1] 来自所选城市。 */
    static double[] coords(Context c, Prefs p) {
        if (p.i(Flags.WX_MODE, Flags.WX_MODE_DEF) == 0
                && c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
                Location best = null;
                for (String pr : lm.getProviders(true)) {
                    Location l = lm.getLastKnownLocation(pr);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                }
                if (best != null && System.currentTimeMillis() - best.getTime() < 7 * 86400_000L)
                    return new double[]{best.getLatitude(), best.getLongitude()};
            } catch (SecurityException ignored) { }
        }
        String[] city = Cities.find(p.s(Flags.CITY, ""));
        if (city != null) return new double[]{Double.parseDouble(city[1]), Double.parseDouble(city[2]), 1};
        return null;
    }

    static Facts openMeteo(double lat, double lon) {
        try {
            String u = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                    + "&current_weather=true&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                    + "&timezone=auto&forecast_days=1";
            JSONObject o = new JSONObject(Http.get(u, 8000));
            Facts f = new Facts();
            JSONObject cw = o.getJSONObject("current_weather");
            f.temp = cw.optDouble("temperature", Double.NaN);
            int code = cw.optInt("weathercode", -1);
            f.key = WeatherCode.fromWmo(code);
            f.desc = "wmo" + code;
            JSONObject d = o.optJSONObject("daily");
            if (d != null) {
                f.tmax = first(d.optJSONArray("temperature_2m_max"));
                f.tmin = first(d.optJSONArray("temperature_2m_min"));
                JSONArray r = d.optJSONArray("precipitation_probability_max");
                if (r != null && r.length() > 0 && !r.isNull(0)) f.rain = r.optInt(0, -1);
            }
            f.src = "open-meteo";
            f.at = System.currentTimeMillis();
            f.ok = !Double.isNaN(f.temp);
            return f.ok ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    static double first(JSONArray a) { return a != null && a.length() > 0 && !a.isNull(0) ? a.optDouble(0, Double.NaN) : Double.NaN; }

    static void save(Context c, Facts f) {
        try {
            File tmp = new File(c.getFilesDir(), "wx.json.tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            try { o.write(f.toJson().getBytes("UTF-8")); } finally { o.close(); }
            tmp.renameTo(new File(c.getFilesDir(), "wx.json"));
        } catch (Exception ignored) { }
    }

    /** 最近一次成功的天气（超过 maxAge 返回 null）。 */
    public static Facts cached(Context c, long maxAge) {
        try {
            File f = new File(c.getFilesDir(), "wx.json");
            if (!f.isFile()) return null;
            Facts x = Facts.fromJson(new String(Http.readAll(new java.io.FileInputStream(f)), "UTF-8"));
            if (x == null || !x.ok || System.currentTimeMillis() - x.at > maxAge) return null;
            return x;
        } catch (Exception e) { return null; }
    }
}
