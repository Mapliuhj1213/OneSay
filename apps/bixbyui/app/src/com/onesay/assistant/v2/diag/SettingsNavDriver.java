package com.onesay.assistant.v2.diag;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.onesay.assistant.v2.a11y.A11yBackend;
import com.onesay.assistant.v2.fastscreen.FsTree;
import com.onesay.assistant.v2.search.SearchConfig;
import com.onesay.assistant.v2.search.SearchResult;
import com.onesay.assistant.v2.search.WebSearchClient;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link SettingsNav} 计划的 Android 执行器：标准 Settings.ACTION_* 打开设置页 → 无障碍读控件树找目标项 → 报告；
 * 改动只在用户点了确认芯片后经 {@link #toggle} 执行（每次都要确认）。后台线程调用。不读也不写任何厂商私有 settings 键。
 */
public final class SettingsNavDriver {
    private SettingsNavDriver() {}

    static final String TAG = "SettingsNav";
    static final long SETTLE_MS = 1200;

    public static final class Result {
        public final boolean found;
        public final SettingsNav.Match match;
        public final String message;
        public final List<String> path;

        Result(boolean found, SettingsNav.Match match, String message, List<String> path) {
            this.found = found;
            this.match = match;
            this.message = message;
            this.path = path;
        }
    }

    /** 是否配置了联网搜索 key（用户自己的 Tavily / 博查 key）。 */
    public static boolean hasSearchKey(Context ctx) {
        try {
            SearchConfig c = WebSearchClient.getInstance(ctx).config();
            return c.isEnabled() && (nonEmpty(c.getTavilyKey()) || nonEmpty(c.getBochaKey()));
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean nonEmpty(String s) {
        return s != null && s.trim().length() > 0;
    }

    /** 找设置项并报告（不改任何东西）。 */
    public static Result locate(Context ctx, String label, String settingsAction) {
        A11yBackend a = A11yBackend.instance();
        if (a == null) return new Result(false, null, SettingsNav.NEED_A11Y, null);
        String self = ctx.getPackageName();
        List<String> webPath = null;
        for (SettingsNav.Step st : SettingsNav.plan(label, settingsAction, null, hasSearchKey(ctx))) {
            switch (st.kind) {
                case OPEN_ACTION:
                    open(ctx, st.arg);
                    break;
                case FIND: {
                    SettingsNav.Match m = findWithScroll(a, label, self);
                    if (m != null) return new Result(true, m, m.describe(label), webPath);
                    break;
                }
                case SETTINGS_SEARCH:
                    settingsSearch(a, label);
                    break;
                case WEB_LOOKUP:
                    webPath = webLookup(ctx, label);
                    Log.i(TAG, "联网查到路径: " + webPath);
                    break;
                case FOLLOW_PATH:
                    follow(a, st.path != null ? st.path : webPath, self);
                    break;
                case REPORT_NO_KEY:
                    return new Result(false, null, "在设置页里没找到「" + label + "」。" + SettingsNav.NO_KEY, null);
                case REPORT_NOT_FOUND:
                    return new Result(false, null, "按" + (webPath == null || webPath.isEmpty() ? "搜索结果" : "「" + SettingsNav.pathText(webPath) + "」")
                            + "也没找到「" + label + "」，这台手机可能没有这个功能，或者叫法不同。", webPath);
                default:
                    break;
            }
        }
        return new Result(false, null, "没找到「" + label + "」。", webPath);
    }

    /** 用户确认后切换开关：重新读树确认目标还在，再点。返回给用户看的结果。 */
    public static String toggle(Context ctx, String label) {
        A11yBackend a = A11yBackend.instance();
        if (a == null) return SettingsNav.NEED_A11Y;
        FsTree t = a.readTree(600);
        SettingsNav.Match m = SettingsNav.find(t, label, ctx.getPackageName());
        if (m == null || m.toggleNode < 0) return "屏幕上已经找不到「" + label + "」的开关了，没有改动。";
        boolean before = Boolean.TRUE.equals(m.checked);
        FsTree.Node n = t.nodes.get(m.toggleNode);
        boolean ok = a.clickNode(m.toggleNode, new int[]{n.l, n.t, n.r, n.b}, 3);
        if (!ok) ok = a.clickNode(m.clickNode, null, 3);
        SystemClock.sleep(600);
        SettingsNav.Match after = SettingsNav.find(a.readTree(600), label, ctx.getPackageName());
        if (after != null && after.checked != null && after.checked != before) {
            return "已把「" + m.matchedName + "」" + (after.checked ? "打开" : "关闭") + "。";
        }
        return ok ? "已点了「" + m.matchedName + "」，但没确认到状态变化，请看一眼屏幕。" : "没点成功，请在屏幕上手动切换。";
    }

    static void open(Context ctx, String action) {
        try {
            Intent i = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.getApplicationContext().startActivity(i);
        } catch (Throwable t) {
            try {
                ctx.getApplicationContext().startActivity(new Intent(SettingsNav.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) {
            }
        }
        SystemClock.sleep(SETTLE_MS);
    }

    static SettingsNav.Match findWithScroll(A11yBackend a, String label, String self) {
        for (int page = 0; page < 5; page++) {
            FsTree t = a.readTree(600);
            SettingsNav.Match m = SettingsNav.find(t, label, self);
            if (m != null) return m;
            int sc = SettingsNav.findScrollable(t);
            if (sc < 0) return null;
            a.scrollNode(sc, null, 1);
            SystemClock.sleep(500);
        }
        return null;
    }

    static void settingsSearch(A11yBackend a, String label) {
        FsTree t = a.readTree(600);
        int box = SettingsNav.findSearchBox(t);
        if (box < 0) return;
        a.clickNode(box, null, 3);
        SystemClock.sleep(800);
        FsTree t2 = a.readTree(600);
        int ed = SettingsNav.findEditable(t2);
        if (ed < 0) return;
        a.setText(ed, null, label);
        SystemClock.sleep(1200);
        // 点第一条匹配的搜索结果进入对应页面
        FsTree t3 = a.readTree(600);
        SettingsNav.Match m = SettingsNav.find(t3, label, null);
        if (m != null && m.labelNode != ed) {
            a.clickNode(m.clickNode, null, 3);
            SystemClock.sleep(SETTLE_MS);
        }
    }

    static List<String> webLookup(Context ctx, String label) {
        List<String> out = new ArrayList<String>();
        try {
            String q = SettingsNav.webQuery(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT, label);
            StringBuilder sb = new StringBuilder();
            for (SearchResult r : WebSearchClient.getInstance(ctx).search(q)) sb.append(r.title).append('\n').append(r.snippet).append('\n');
            out = SettingsNav.parsePath(sb.toString(), label);
        } catch (Throwable t) {
            Log.w(TAG, "联网查路径失败: " + t.getClass().getSimpleName());
        }
        return out;
    }

    static void follow(A11yBackend a, List<String> path, String self) {
        if (path == null) return;
        for (int i = 0; i < path.size() - 1; i++) {   // 最后一段是目标本身，由 FIND 接手
            String seg = path.get(i);
            SettingsNav.Match m = findWithScroll(a, seg, self);
            if (m == null) return;
            a.clickNode(m.clickNode, null, 3);
            SystemClock.sleep(SETTLE_MS);
        }
    }
}
