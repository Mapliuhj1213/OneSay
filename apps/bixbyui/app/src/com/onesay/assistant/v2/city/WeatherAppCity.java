package com.onesay.assistant.v2.city;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.util.Log;

import com.onesay.assistant.v2.a11y.A11yBackend;
import com.onesay.assistant.v2.fastscreen.FsTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 从用户手机上的天气 App 读当前城市（通用做法，不碰任何私有数据库、不写死厂商包名）：
 * 在桌面应用里找名字含「天气 / Weather」的 App（用户也可以在控制台指定包名），打开它，经 OneSay 无障碍服务读首屏文字，
 * 用 {@link HomeCityPolicy#cityFromScreen} 认出城市，回到原界面，结果缓存。只在用户点「从天气 App 读城市」时执行。后台线程调用。
 */
public final class WeatherAppCity {
    private WeatherAppCity() {}

    static final String TAG = "HomeCity";
    static final String K_CITY = "screen_city", K_PKG = "weather_pkg";

    public static String cached(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(HomeCityProvider.PREF, Context.MODE_PRIVATE).getString(K_CITY, "");
    }

    public static void setPackage(Context ctx, String pkg) {
        ctx.getApplicationContext().getSharedPreferences(HomeCityProvider.PREF, Context.MODE_PRIVATE).edit().putString(K_PKG, pkg == null ? "" : pkg).apply();
    }

    /** 桌面应用里名字像天气 App 的（用户指定的优先）。 */
    public static String weatherPackage(Context ctx) {
        Context app = ctx.getApplicationContext();
        String set = app.getSharedPreferences(HomeCityProvider.PREF, Context.MODE_PRIVATE).getString(K_PKG, "");
        if (set.length() > 0) return set;
        try {
            PackageManager pm = app.getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> l = pm.queryIntentActivities(i, 0);
            List<String> labels = new ArrayList<String>();
            for (ResolveInfo r : l) labels.add(String.valueOf(r.loadLabel(pm)));
            int k = pickWeatherApp(labels);
            return k >= 0 ? l.get(k).activityInfo.packageName : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 名字含「天气」/「weather」的第一个（纯函数）。 */
    public static int pickWeatherApp(List<String> labels) {
        if (labels == null) return -1;
        for (int i = 0; i < labels.size(); i++) {
            String s = labels.get(i) == null ? "" : labels.get(i).toLowerCase(Locale.ROOT);
            if (s.contains("天气") || s.contains("weather")) return i;
        }
        return -1;
    }

    /** 打开天气 App 读城市。返回给用户看的一句话。 */
    public static String capture(Context ctx) {
        Context app = ctx.getApplicationContext();
        A11yBackend a = A11yBackend.instance();
        if (a == null) return "要先在系统「无障碍」里打开「OneSay 读屏与按键唤醒」，才能读天气 App 上的城市。";
        String pkg = weatherPackage(app);
        if (pkg == null) return "没找到天气 App。可以手动设置常用城市。";
        try {
            Intent i = app.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return "天气 App 打不开。";
            app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            return "天气 App 打不开。";
        }
        SystemClock.sleep(2500);
        FsTree t = a.readTree(400);
        List<String> texts = new ArrayList<String>();
        if (t != null && pkg.equals(t.pkg)) {
            for (FsTree.Node n : t.nodes) {
                String s = n.text.length() > 0 ? n.text : n.desc;
                if (s.length() > 0) texts.add(s);
            }
        }
        a.globalBack();
        String city = HomeCityPolicy.cityFromScreen(texts);
        Log.i(TAG, "[CITY] 天气 App 屏幕读城市: " + (city.length() > 0 ? "已认出" : "没认出") + "（" + texts.size() + " 段文字）");
        if (city.length() == 0) return "天气 App 上没认出城市，可以手动设置常用城市。";
        app.getSharedPreferences(HomeCityProvider.PREF, Context.MODE_PRIVATE).edit().putString(K_CITY, city).putLong("at", 0L).apply();
        return "已从天气 App 读到城市：" + city;
    }
}
