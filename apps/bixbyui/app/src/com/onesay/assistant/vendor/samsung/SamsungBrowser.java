package com.onesay.assistant.vendor.samsung;

import com.onesay.assistant.v2.errand.ErrandSettings;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ lane C3-4：升级链最后一档 —— 用三星浏览器（Samsung Internet）打开链接，交给用户。<b>只发 Intent，不做任何 GUI 点击。</b>
 *
 * <h3>「默认桌面模式」怎么落地（要上机确认）</h3>
 * 三星官方开发者文档只给了 Custom Tabs / 包名（com.sec.android.app.sbrowser），<b>没有公开的「以桌面版打开」Intent 参数</b>
 * （https://developer.samsung.com/internet/android/web-developer-guide.html ）；桌面版是浏览器自己的「桌面版网页」开关。所以：
 * <ol>
 *   <li>Intent 本身：ACTION_VIEW + 包名 + NEW_TASK + BROWSABLE + {@code com.android.browser.application_id}（同一来源复用一个标签页）
 *       + {@code create_new_tab}；</li>
 *   <li>桌面模式参数：做成<b>可配置的额外 extras</b>（prefs {@code bixby_errand / samsung_desktop_extras}，格式 {@code key=bool;key2=bool}），
 *       <b>默认空</b> —— 上机从 sbrowser 的 APK 里找到真实参数名、验证有效后再填，不猜；</li>
 *   <li>在此之前，「默认桌面版」靠三星浏览器自身设置（菜单 →「桌面版网页」/ 设置里的默认桌面版，视版本而定）—— 列在上机确认项里。</li>
 * </ol>
 * 已安装的正式版优先，其次 Beta 版；都没有 → 本档不可用（升级链跳过，结果卡如实说明）。
 */
/** 可选厂商适配（三星）：只在装了三星浏览器且开关打开时使用；默认关。通用路径不依赖本类。 */
public final class SamsungBrowser {

    private SamsungBrowser() {}

    static final String TAG = "Errand/SBrowser";
    public static final String PKG = "com.sec.android.app.sbrowser";
    public static final String PKG_BETA = "com.sec.android.app.sbrowser.beta";
    public static final String[] PKGS = {PKG, PKG_BETA};

    /** 标准浏览器 extra：同一 application_id 的链接复用同一个标签页（AOSP Browser.EXTRA_APPLICATION_ID）。 */
    public static final String EXTRA_APP_ID = "com.android.browser.application_id";
    public static final String EXTRA_NEW_TAB = "create_new_tab";

    public static final String PREF_DESKTOP_EXTRAS = "samsung_desktop_extras";

    /** ★ LC-4：收工文案。只有发出的 Intent 真的带了桌面模式参数（{@link Spec#desktop}）才说「桌面版」。 */
    public static final String NOTE_DESKTOP = "已在三星浏览器（桌面版）打开，请你接着看";
    public static final String NOTE_PLAIN = "已在三星浏览器打开，请你接着看";

    public static String handoffNote(boolean desktop) {
        return desktop ? NOTE_DESKTOP : NOTE_PLAIN;
    }

    /** ★ LC-4 规则用到控制台：开关说明只有配了桌面模式参数（至少一个为 true）才写「桌面版」。 */
    public static final String TOGGLE_DESKTOP = "只帮你打开链接（桌面版），不替你点";
    public static final String TOGGLE_PLAIN = "只帮你打开链接，不替你点";

    public static String toggleNote(boolean desktop) {
        return desktop ? TOGGLE_DESKTOP : TOGGLE_PLAIN;
    }

    /** 配置串里有没有生效的桌面模式参数（与 {@link Spec#desktop} 同一口径）。 */
    public static boolean desktopConfigured(String desktopKv) {
        return parseExtras(desktopKv).containsValue(Boolean.TRUE);
    }

    /** 读当前配置（主线程可用，SharedPreferences 已缓存）。 */
    public static boolean desktopConfigured(Context ctx) {
        try {
            return desktopConfigured(ctx.getSharedPreferences(ErrandSettings.PREF, Context.MODE_PRIVATE).getString(PREF_DESKTOP_EXTRAS, ""));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Intent 描述（纯 Java，JVM 可测）。 */
    public static final class Spec {
        public final String action = Intent.ACTION_VIEW;
        public final String url;
        public final String pkg;
        public final Map<String, Object> extras;
        /** ★ LC-4：Intent 里是否带了（至少一个为 true 的）桌面模式参数。默认配置为空 → false。 */
        public final boolean desktop;

        Spec(String url, String pkg, Map<String, Object> extras, boolean desktop) {
            this.url = url;
            this.pkg = pkg;
            this.extras = Collections.unmodifiableMap(extras);
            this.desktop = desktop;
        }

        @Override
        public String toString() {
            return "VIEW " + url + " → " + pkg + " " + extras;
        }
    }

    /**
     * @param url        只接受 http(s)；否则返回 null
     * @param pkg        已安装的三星浏览器包名
     * @param callerPkg  本 App 包名（application_id）
     * @param desktopKv  上机确认后的桌面模式参数（{@code key=true;key2=1}），可空
     */
    public static Spec spec(String url, String pkg, String callerPkg, String desktopKv) {
        if (url == null || pkg == null) return null;
        String u = url.trim();
        String low = u.toLowerCase();
        if (!(low.startsWith("http://") || low.startsWith("https://"))) return null;
        Map<String, Object> ex = new LinkedHashMap<String, Object>();
        if (callerPkg != null && callerPkg.length() > 0) ex.put(EXTRA_APP_ID, callerPkg);
        ex.put(EXTRA_NEW_TAB, Boolean.TRUE);
        Map<String, Object> desk = parseExtras(desktopKv);
        ex.putAll(desk);
        return new Spec(u, pkg, ex, desk.containsValue(Boolean.TRUE));
    }

    /** {@code a=true;b=1;c=false} → {a:true,b:true,c:false}；只收布尔值，键只允许 [A-Za-z0-9._-]。 */
    static Map<String, Object> parseExtras(String kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (kv == null) return m;
        for (String part : kv.split(";")) {
            int k = part.indexOf('=');
            if (k <= 0) continue;
            String key = part.substring(0, k).trim();
            String v = part.substring(k + 1).trim().toLowerCase();
            if (!key.matches("[A-Za-z0-9._\\-]{1,80}")) continue;
            if (v.equals("true") || v.equals("1")) m.put(key, Boolean.TRUE);
            else if (v.equals("false") || v.equals("0")) m.put(key, Boolean.FALSE);
        }
        return m;
    }

    /** 已安装且启用的三星浏览器包名（正式版优先）；没有返回 null。 */
    public static String installed(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        for (String p : PKGS) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                if (ai != null && ai.enabled) return p;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static Intent toIntent(Spec s) {
        Intent i = new Intent(s.action, Uri.parse(s.url));
        i.setPackage(s.pkg);
        i.addCategory(Intent.CATEGORY_BROWSABLE);       // 总是 BROWSABLE + NEW_TASK（2026-10-23 审计 g3 D6：原恒 true 字段内联）
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        for (Map.Entry<String, Object> e : s.extras.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean) i.putExtra(e.getKey(), ((Boolean) v).booleanValue());
            else i.putExtra(e.getKey(), String.valueOf(v));
        }
        return i;
    }

    /** 拉起（任意线程）。返回是否成功。 */
    public static boolean open(Context ctx, String url) {
        return launch(ctx, url) != null;
    }

    /** ★ LC-4：拉起并返回实际发出的 Intent 描述（失败 null），调用方据此决定文案里说不说「桌面版」。 */
    public static Spec launch(Context ctx, String url) {
        String pkg = installed(ctx);
        if (pkg == null) return null;
        String kv = ctx.getSharedPreferences(ErrandSettings.PREF, Context.MODE_PRIVATE).getString(PREF_DESKTOP_EXTRAS, "");
        Spec s = spec(url, pkg, ctx.getPackageName(), kv);
        if (s == null) return null;
        try {
            ctx.getApplicationContext().startActivity(toIntent(s));
            Log.i(TAG, "已用三星浏览器打开: " + s + " desktop=" + s.desktop);
            return s;
        } catch (Throwable t) {
            Log.w(TAG, "三星浏览器打开失败: " + t.getMessage());
            return null;
        }
    }

    /** 上机确认用：列出将要发出的 Intent（am start 等价命令，不执行）。 */
    public static List<String> amCommand(Spec s) {
        List<String> c = new ArrayList<String>();
        if (s == null) return c;
        c.add("am");
        c.add("start");
        c.add("-a");
        c.add(s.action);
        c.add("-c");
        c.add(Intent.CATEGORY_BROWSABLE);
        c.add("-d");
        c.add(s.url);
        c.add("-p");
        c.add(s.pkg);
        for (Map.Entry<String, Object> e : s.extras.entrySet()) {
            if (e.getValue() instanceof Boolean) {
                c.add("--ez");
                c.add(e.getKey());
                c.add(String.valueOf(e.getValue()));
            } else {
                c.add("--es");
                c.add(e.getKey());
                c.add(String.valueOf(e.getValue()));
            }
        }
        return c;
    }
}
