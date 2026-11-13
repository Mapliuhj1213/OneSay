package com.onesay.assistant.v2.errand;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跑腿阶梯最后一档（{@link ResearchLadder.Tier#BROWSER}）：用<b>用户的默认浏览器</b>打开链接交给用户。只发 Intent，不做 GUI 点击。
 *
 * <ul>
 *   <li>默认浏览器 = 系统对 {@code ACTION_VIEW https://} 的默认处理者（{@code resolveActivity(MATCH_DEFAULT_ONLY)}）；
 *       没设默认（解析到选择器 / ResolverActivity）就不指定包名，交给系统弹选择器；</li>
 *   <li>标准浏览器 extra：{@code com.android.browser.application_id}（同一来源复用一个标签页）+ {@code create_new_tab}；</li>
 *   <li>「以桌面版打开」没有跨浏览器的公开 Intent 参数：做成<b>可配置</b>的布尔 extras（prefs {@code bixby_errand / desktop_extras}，
 *       格式 {@code key=true;key2=1}，默认空），只有真的带上了才在文案里说「桌面版」。需要桌面版而浏览器不认参数时，
 *       上一档内置 WebView（默认 PC UA）就是通用的桌面版浏览；</li>
 * </ul>
 */
public final class DefaultBrowser {

    private DefaultBrowser() {}

    static final String TAG = "Errand/Browser";

    public static final String EXTRA_APP_ID = "com.android.browser.application_id";
    public static final String EXTRA_NEW_TAB = "create_new_tab";
    public static final String PREF_DESKTOP_EXTRAS = "desktop_extras";

    public static final String NOTE = "已在浏览器打开，请你接着看";
    public static final String NOTE_DESKTOP = "已在浏览器（桌面版）打开，请你接着看";
    public static final String TOGGLE_NOTE = "用你的默认浏览器打开链接，不替你点";
    public static final String TOGGLE_NOTE_DESKTOP = "用你的默认浏览器打开链接（桌面版），不替你点";

    public static String handoffNote(boolean desktop) {
        return desktop ? NOTE_DESKTOP : NOTE;
    }

    public static String toggleNote(boolean desktop) {
        return desktop ? TOGGLE_NOTE_DESKTOP : TOGGLE_NOTE;
    }

    /** 只接受 http(s)；返回规范后的 url，不合法返回 null（纯函数）。 */
    public static String checkUrl(String url) {
        if (url == null) return null;
        String u = url.trim();
        String low = u.toLowerCase(java.util.Locale.ROOT);
        return low.startsWith("http://") || low.startsWith("https://") ? u : null;
    }

    /** Intent 描述（纯 Java，JVM 可测）。 */
    public static final class Spec {
        public final String action = Intent.ACTION_VIEW;
        public final String url;
        /** 默认浏览器包名；null = 不指定（系统选择器）。 */
        public final String pkg;
        public final Map<String, Object> extras;
        /** 是否真的带了（至少一个为 true 的）桌面版参数。 */
        public final boolean desktop;

        Spec(String url, String pkg, Map<String, Object> extras, boolean desktop) {
            this.url = url;
            this.pkg = pkg;
            this.extras = Collections.unmodifiableMap(extras);
            this.desktop = desktop;
        }

        @Override public String toString() {
            return "VIEW " + url + " → " + (pkg == null ? "(选择器)" : pkg) + " " + extras;
        }
    }

    public static Spec spec(String url, String pkg, String callerPkg, String desktopKv) {
        String u = checkUrl(url);
        if (u == null) return null;
        Map<String, Object> ex = new LinkedHashMap<String, Object>();
        if (callerPkg != null && callerPkg.length() > 0) ex.put(EXTRA_APP_ID, callerPkg);
        ex.put(EXTRA_NEW_TAB, Boolean.TRUE);
        Map<String, Object> desk = parseExtras(desktopKv);
        ex.putAll(desk);
        return new Spec(u, pkg, ex, desk.containsValue(Boolean.TRUE));
    }

    /** {@code a=true;b=1;c=false} → {a:true,b:true,c:false}；只收布尔值，键只允许 [A-Za-z0-9._-]。 */
    public static Map<String, Object> parseExtras(String kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (kv == null) return m;
        for (String part : kv.split(";")) {
            int k = part.indexOf('=');
            if (k <= 0) continue;
            String key = part.substring(0, k).trim();
            String v = part.substring(k + 1).trim().toLowerCase(java.util.Locale.ROOT);
            if (!key.matches("[A-Za-z0-9._\\-]{1,80}")) continue;
            if (v.equals("true") || v.equals("1")) m.put(key, Boolean.TRUE);
            else if (v.equals("false") || v.equals("0")) m.put(key, Boolean.FALSE);
        }
        return m;
    }

    public static boolean desktopConfigured(String desktopKv) {
        return parseExtras(desktopKv).containsValue(Boolean.TRUE);
    }

    public static boolean desktopConfigured(Context ctx) {
        try {
            return desktopConfigured(ctx.getSharedPreferences(ErrandSettings.PREF, Context.MODE_PRIVATE).getString(PREF_DESKTOP_EXTRAS, ""));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 解析结果是不是「真的默认浏览器」（不是选择器 / 系统 ResolverActivity / android 自身）。纯函数。 */
    public static String defaultPkgFrom(String resolvedPkg, String resolvedActivity) {
        if (resolvedPkg == null || resolvedPkg.length() == 0 || "android".equals(resolvedPkg)) return null;
        if (resolvedActivity != null && resolvedActivity.contains("ResolverActivity")) return null;
        return resolvedPkg;
    }

    /** 用户的默认浏览器包名；没设默认返回 null。 */
    public static String defaultPackage(Context ctx) {
        try {
            ResolveInfo ri = ctx.getPackageManager().resolveActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                    .addCategory(Intent.CATEGORY_BROWSABLE), PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null || ri.activityInfo == null) return null;
            return defaultPkgFrom(ri.activityInfo.packageName, ri.activityInfo.name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 是否有 App 能打开网页链接。 */
    public static boolean available(Context ctx) {
        try {
            return toIntent(spec("https://example.com", null, null, null)).resolveActivity(ctx.getPackageManager()) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static Intent toIntent(Spec s) {
        Intent i = new Intent(s.action, Uri.parse(s.url));
        if (s.pkg != null) i.setPackage(s.pkg);
        i.addCategory(Intent.CATEGORY_BROWSABLE);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        for (Map.Entry<String, Object> e : s.extras.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean) i.putExtra(e.getKey(), ((Boolean) v).booleanValue());
            else i.putExtra(e.getKey(), String.valueOf(v));
        }
        return i;
    }

    /** 用默认浏览器打开（任意线程）。返回是否成功。 */
    public static boolean open(Context ctx, String url) {
        return launch(ctx, url) != null;
    }

    /** 打开并返回实际发出的 Intent 描述（失败 null），调用方据此决定文案说不说「桌面版」。 */
    public static Spec launch(Context ctx, String url) {
        String kv = "";
        try {
            kv = ctx.getSharedPreferences(ErrandSettings.PREF, Context.MODE_PRIVATE).getString(PREF_DESKTOP_EXTRAS, "");
        } catch (Throwable ignored) {
        }
        Spec s = spec(url, defaultPackage(ctx), ctx.getPackageName(), kv);
        if (s == null) return null;
        try {
            ctx.getApplicationContext().startActivity(toIntent(s));
            Log.i(TAG, "已用默认浏览器打开 desktop=" + s.desktop);
            return s;
        } catch (Throwable t) {
            if (s.pkg != null) {   // 指定包失败（被停用等）→ 不指定包再试一次
                try {
                    Spec any = spec(url, null, ctx.getPackageName(), kv);
                    ctx.getApplicationContext().startActivity(toIntent(any));
                    return any;
                } catch (Throwable ignored) {
                }
            }
            Log.w(TAG, "默认浏览器打开失败: " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** 上机确认用：列出等价的 am start 命令（不执行）。 */
    public static List<String> amCommand(Spec s) {
        List<String> c = new ArrayList<String>();
        if (s == null) return c;
        Collections.addAll(c, "am", "start", "-a", s.action, "-c", Intent.CATEGORY_BROWSABLE, "-d", s.url);
        if (s.pkg != null) Collections.addAll(c, "-p", s.pkg);
        for (Map.Entry<String, Object> e : s.extras.entrySet()) {
            Collections.addAll(c, e.getValue() instanceof Boolean ? "--ez" : "--es", e.getKey(), String.valueOf(e.getValue()));
        }
        return c;
    }
}
