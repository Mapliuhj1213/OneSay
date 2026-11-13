package com.onesay.assistant.v2.ui.console;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.ui.VoiceprintEnrollController;

/**
 * [console R12] 控制台本体：主题、页面栈、给插件的 {@link ConsoleHost}、各页面共用的动作（授权、白名单、密钥对话框…）。
 * MainActivity 只当宿主（照旧初始化能力表、拉起 WakeService、处理 page=quicknote 深链）。
 */
public final class Console implements ConsoleHost {

    public static final String PREF = "bixby_console";
    public static final String KEY_DEV = "dev_mode";
    public static final String KEY_THEME = "theme";
    /** 本轮的发布代号（关于页 / 首页右上）。 */
    public static final String RELEASE = "R12";

    public final Activity act;
    public final L2LLMCompiler compiler;
    public final ConsoleNav nav;
    ConsoleKit kit;
    final DevMode devGate = new DevMode();
    private HomePage mHome;
    VoiceprintEnrollController vp;

    public Console(Activity act, L2LLMCompiler compiler) {
        this.act = act;
        this.compiler = compiler;
        this.nav = new ConsoleNav(act);
        applyTheme(ConsoleTheme.of(prefs(act).getString(KEY_THEME, ConsoleTheme.MODE_DARK)));
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 当前主题（MainActivity 之外的页面——邮箱设置、记一记——也用它）。 */
    public static ConsoleTheme themeFor(Context ctx) {
        try {
            return ConsoleTheme.of(prefs(ctx).getString(KEY_THEME, ConsoleTheme.MODE_DARK));
        } catch (Throwable t) {
            return ConsoleTheme.DARK;
        }
    }

    public ConsoleKit kit() {
        return kit;
    }

    public ConsoleTheme theme() {
        return kit.t;
    }

    public View root() {
        return nav.root();
    }

    public void start() {
        mHome = new HomePage(this);
        nav.setRoot(mHome);
    }

    // ------------------------------------------------------------------ 主题

    private void applyTheme(ConsoleTheme t) {
        ConsoleTheme.setCurrent(t);
        kit = new ConsoleKit(act, t);
        styleWindow(act.getWindow(), t);
        nav.root().setBackgroundColor(t.bg);
    }

    /** 状态栏 / 导航栏跟主题走（浅色时深色图标；API 26+ 导航栏也是浅色）。不碰 Manifest。 */
    public static void styleWindow(Window w, ConsoleTheme t) {
        if (w == null) return;
        try {
            w.setStatusBarColor(t.bg);
            w.setNavigationBarColor(t.bg);
            View d = w.getDecorView();
            int f = d.getSystemUiVisibility();
            f &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | 0x10 /* LIGHT_NAVIGATION_BAR */);
            if (!t.dark) {
                f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) f |= 0x10;
            }
            d.setSystemUiVisibility(f);
        } catch (Throwable ignored) {
        }
    }

    public void setThemeMode(String mode) {
        prefs(act).edit().putString(KEY_THEME, mode).apply();
        applyTheme(ConsoleTheme.of(mode));
        nav.rebuildAll();
    }

    // ------------------------------------------------------------------ 开发者模式

    public static boolean debugBuild() {
        return com.onesay.assistant.v2.test.V2SelfTest.AVAILABLE;
    }

    @Override public boolean devMode() {
        SharedPreferences sp = prefs(act);
        Boolean pref = sp.contains(KEY_DEV) ? Boolean.valueOf(sp.getBoolean(KEY_DEV, false)) : null;
        return DevMode.isOn(debugBuild(), pref);
    }

    public void setDevMode(boolean on) {
        prefs(act).edit().putBoolean(KEY_DEV, on).apply();
    }

    // ------------------------------------------------------------------ 生命周期

    private boolean mResumedOnce;

    public void onResume() {
        com.onesay.assistant.v2.suggest.SuggestHost.permissionPoke();   // ★ fix/shot-1104：从系统权限页回来 → 截图源立刻重查存储权限
        if (!mResumedOnce) {   // 第一次 onResume 紧跟 start()（setRoot 已经刷新过）
            mResumedOnce = true;
            return;
        }
        ConsoleNav.Page p = nav.top();
        if (p != null) p.onShown();
    }

    public boolean back() {
        return nav.back();
    }

    public VoiceprintEnrollController voiceprint() {
        if (vp == null) vp = new VoiceprintEnrollController(act);
        return vp;
    }

    public void onStop() {
        // ★ 离开页面 = 声纹会话中止（录制必须看着引导文案念；没有页面就没有「停止」入口）
        if (vp != null && vp.isActive()) {
            vp.abort();
            syncScreenOn();
        }
    }

    void syncScreenOn() {
        if (vp != null && vp.isActive()) act.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else act.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ------------------------------------------------------------------ 外来页面（记一记 QuickNotePage）

    /** QuickNotePage 的 showPage：栈顶已是外来页 → 换内容；否则压栈。 */
    public void showForeign(View v) {
        ForeignPage p = new ForeignPage(v);
        if (nav.top() instanceof ForeignPage) nav.replaceTop(p);
        else nav.push(p);
    }

    public void closeForeign() {
        if (nav.top() instanceof ForeignPage) nav.pop();
    }

    /** 记一记页（QuickNotePage 自己画、自己换内容）。 */
    static final class ForeignPage extends ConsoleNav.Page {
        private final View mV;

        ForeignPage(View v) {
            mV = v;
        }

        @Override protected View create() {
            return mV;
        }

        @Override public boolean back() {
            Object tag = mV.getTag();
            return tag instanceof com.onesay.assistant.v2.quicknote.ui.QuickNotePage && ((com.onesay.assistant.v2.quicknote.ui.QuickNotePage) tag).back();
        }
    }

    // ------------------------------------------------------------------ 共用动作

    public void push(ConsoleNav.Page p) {
        nav.push(p);
    }

    public void openOverlaySettings() {
        try {
            act.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + act.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            toast("打不开悬浮窗设置：" + t.getMessage());
        }
    }

    public void openAppSettings() {
        try {
            act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            toast("打不开应用设置：" + t.getMessage());
        }
    }

    /** 电池优化白名单：只在用户点「加入」时经 root 执行（沿用 S3 的实现，绝不静默执行）。 */
    public void joinBatteryWhitelist(final Runnable after) {
        toast("正在加入电池优化白名单…");
        com.onesay.assistant.v2.util.Threads.start("console-whitelist", new Runnable() {
            @Override public void run() {
                final String msg = com.onesay.assistant.v2.s3.S3Hooks.whitelistByRoot(act);
                ui(new Runnable() {
                    @Override public void run() {
                        toast(msg);
                        if (after != null) after.run();
                    }
                });
            }
        });
    }

    public void startWakeService() {
        try {
            Intent it = new Intent(act, com.onesay.assistant.v2.wake.WakeService.class);
            if (Build.VERSION.SDK_INT >= 26) act.startForegroundService(it);
            else act.startService(it);
            toast("已请求启动唤醒服务");
        } catch (Throwable t) {
            toast("启动失败：" + t.getMessage());
        }
    }

    /**
     * DeepSeek API Key 编辑（D8）：输入框<b>永远是空的</b>（不 setText 解密后的密钥），提示里只给打码摘要；
     * 留空 = 不改；「清除」要二次确认。
     */
    public void editApiKey(final Runnable after) {
        final EditText et = new EditText(act);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String masked = KeyMask.mask(compiler.getApiKey());
        et.setHint("粘贴新的 Key（留空 = 不改）");
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        et.setTextColor(kit.t.text1);
        et.setHintTextColor(kit.t.text3);   // 真机：三星浅色对话框默认 hint 色几乎看不见
        LinearLayout box = kit.column();
        box.setPadding(kit.dp(24), kit.dp(8), kit.dp(24), 0);
        TextView cur = kit.text("当前：" + masked, ConsoleTheme.SP_SUB + 1, kit.t.text2, ConsoleKit.W_REGULAR);
        box.addView(cur);
        box.addView(et, kit.lpMatch(8, 0));
        KeyMask.secure(kit.dialog().setTitle("主模型 API Key").setView(box)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        String k = et.getText().toString();
                        if (!KeyMask.shouldSave(k)) {
                            toast("没有改动");
                            return;
                        }
                        compiler.setApiKey(k.trim());
                        KeyMask.clearClipIf(act, k);
                        toast("已保存并热加载（" + KeyMask.mask(k) + "）");
                        if (after != null) after.run();
                    }
                })
                .setNeutralButton("清除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        confirm("清除 API Key？", "清除后复杂问题和智能体都用不了，直到重新填一个。", "清除", new Runnable() {
                            @Override public void run() {
                                compiler.setApiKey("");
                                toast("已清除");
                                if (after != null) after.run();
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null).show());
    }

    /**
     * r2/p0 P0-2（M2-1 / M27-1）：语音识别（百炼 DashScope）Key 编辑 —— 与 DeepSeek Key 同规矩：输入框永远是空的，只给打码摘要；
     * 留空 = 不改；「清除」要二次确认。保存非空 Key 时同时打开云端识别开关（开关没开 = 说话一律识别不了）。
     * 入口：首页提醒条「去配置」、「模型与密钥」页「语音识别 Key」、唤醒卡片「去配置」（page=console:asr.key）。
     */
    public void editAsrKey(final Runnable after) {
        final com.onesay.assistant.v2.asr.AliyunAsrConfig cfg = new com.onesay.assistant.v2.asr.AliyunAsrConfig(act);
        final EditText et = new EditText(act);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String masked = KeyMask.mask(cfg.getDashScopeKey());
        et.setHint("粘贴百炼（DashScope）API Key（留空 = 不改）");
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        et.setTextColor(kit.t.text1);
        et.setHintTextColor(kit.t.text3);
        LinearLayout box = kit.column();
        box.setPadding(kit.dp(24), kit.dp(8), kit.dp(24), 0);
        TextView cur = kit.text("当前：" + masked + (cfg.isEnabled() ? "" : " · 云端识别未开启"), ConsoleTheme.SP_SUB + 1, kit.t.text2, ConsoleKit.W_REGULAR);
        box.addView(cur);
        box.addView(et, kit.lpMatch(8, 0));
        KeyMask.secure(kit.dialog().setTitle("语音识别 Key").setView(box)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        String k = et.getText().toString();
                        if (!KeyMask.shouldSave(k)) {
                            if (!cfg.isEnabled() && (cfg.hasDashScopeKey() || cfg.isComplete())) {
                                cfg.setEnabled(true);
                                toast("已打开云端语音识别");
                                if (after != null) after.run();
                                return;
                            }
                            toast("没有改动");
                            return;
                        }
                        cfg.setDashScopeKey(k.trim());
                        KeyMask.clearClipIf(act, k);
                        cfg.setEnabled(true);
                        toast("已保存（" + KeyMask.mask(k) + "），下次唤醒生效");
                        if (after != null) after.run();
                    }
                })
                .setNeutralButton("清除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        confirm("清除语音识别 Key？", "清除后说话就识别不了（只能打字），直到重新填一个。", "清除", new Runnable() {
                            @Override public void run() {
                                cfg.setDashScopeKey("");
                                toast("已清除");
                                if (after != null) after.run();
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null).show());
    }

    /** 首页 Hero 总开关（D1-A）：关 = 暂停，要二次确认；开 = 立即恢复。 */
    public void setPaused(final boolean paused, final Runnable after, final Runnable cancelled) {
        if (!paused) {
            com.onesay.assistant.v2.wake.AssistantPause.set(act, false);
            com.onesay.assistant.v2.wake.WakeService.refreshPauseNotification();
            toast("已恢复：按键和唤醒词重新响应");
            if (after != null) after.run();
            return;
        }
        kit.dialog().setTitle("暂停助手？")
                .setMessage("暂停后 Bixby 键（含长按记一记）和唤醒词都不响应；灵动岛、定时任务、取件码照常。\n随时在首页右上角恢复。")
                .setPositiveButton("暂停", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        com.onesay.assistant.v2.wake.AssistantPause.set(act, true);
                        com.onesay.assistant.v2.wake.WakeService.refreshPauseNotification();
                        if (after != null) after.run();
                    }
                })
                .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        if (cancelled != null) cancelled.run();
                    }
                })
                .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                    @Override public void onCancel(android.content.DialogInterface d) {
                        if (cancelled != null) cancelled.run();
                    }
                }).show();
    }

    // ------------------------------------------------------------------ ConsoleHost（给插件）

    @Override public Activity activity() {
        return act;
    }

    @Override public int dp(int v) {
        return kit.dp(v);
    }

    @Override public LinearLayout group(LinearLayout root, String title) {
        return kit.group(root, title);
    }

    @Override public Row switchRow(LinearLayout group, String title, String sub, boolean on, Toggle t) {
        return kit.switchRow(group, title, sub, on, t);
    }

    @Override public Row switchLinkRow(LinearLayout group, String title, String sub, boolean on, Toggle t, Runnable open) {
        return kit.switchLinkRow(group, title, sub, on, t, open);
    }

    @Override public Row linkRow(LinearLayout group, String title, String sub, Runnable open) {
        return kit.linkRow(group, title, sub, 0, open);
    }

    @Override public Row valueRow(LinearLayout group, String title, String sub, String value, int tone) {
        return kit.valueRow(group, title, sub, value, tone);
    }

    @Override public Row actionRow(LinearLayout group, String title, String sub, String action, Runnable onAction) {
        return kit.actionRow(group, title, sub, action, onAction);
    }

    @Override public Row mainSwitch(LinearLayout root, String title, boolean on, Toggle t) {
        return kit.mainSwitch(root, title, on, t);
    }

    @Override public TextView footer(LinearLayout root, String text) {
        return kit.footer(root, text);
    }

    @Override public void openPage(String pageId, String title) {
        if (com.onesay.assistant.v2.asr.AsrFailure.CONSOLE_PAGE.equals(pageId)) {   // r2/p0 P0-2：唤醒卡片「去配置」→「模型与密钥」页 + Key 弹窗
            nav.push(new AdvancedPage(this));
            editAsrKey(null);
            return;
        }
        nav.push(new PluginPage(this, pageId, title));
    }

    @Override public void toast(String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
    }

    @Override public void confirm(String title, String msg, String ok, Runnable yes) {
        kit.confirm(title, msg, ok, yes);
    }

    @Override public void ui(Runnable r) {
        act.runOnUiThread(r);
    }

    // ---- v1 兼容：未迁移插件的「标题 + 卡片」，画成新配色

    @Override public TextView sectionTitle(String text) {
        TextView h = kit.text(text, ConsoleTheme.SP_LABEL + 1, kit.t.text3, ConsoleKit.W_MEDIUM);
        h.setPadding(kit.dp(16), kit.dp(ConsoleTheme.GAP_GROUP), kit.dp(16), kit.dp(8));
        return h;
    }

    @Override public LinearLayout card() {
        LinearLayout l = kit.column();
        l.setBackground(kit.round(kit.t.surface, ConsoleTheme.R_CARD));
        l.setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.setLayoutParams(lp);
        return l;
    }

    @Override public Switch uxSwitch(String label, boolean on, CompoundButton.OnCheckedChangeListener l) {
        Switch sw = new Switch(act);
        sw.setText(label);
        sw.setTextColor(kit.t.text1);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, ConsoleTheme.SP_BODY + 1);
        sw.setChecked(on);
        sw.setPadding(0, kit.dp(8), 0, kit.dp(8));
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(st, new int[]{kit.t.accent, kit.t.switchOffThumb}));
        sw.setTrackTintList(new ColorStateList(st, new int[]{ConsoleTheme.alpha(kit.t.accent, 0.5f), kit.t.switchOffTrack}));
        sw.setOnCheckedChangeListener(l);
        return sw;
    }

    @Override public Button button(String text, int color) {
        Button b = new Button(act);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(kit.t.accent);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, ConsoleTheme.SP_BODY + 1);
        GradientDrawable bg = kit.round(kit.t.accentContainer, 22);
        b.setBackground(kit.ripple(bg));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, kit.dp(44));
        lp.topMargin = kit.dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    // ------------------------------------------------------------------ 页面骨架

    /** 有顶栏、可重建的普通页面：每次重新露出（从细项页返回 / 回到前台）都按最新设置重画，保留滚动位置。 */
    abstract static class BuiltPage extends ConsoleNav.Page {
        final Console c;
        final String title;
        ScrollView scroll;
        LinearLayout col;
        private boolean mShown;

        BuiltPage(Console c, String title) {
            this.c = c;
            this.title = title;
        }

        @Override protected View create() {
            col = c.kit.column();
            scroll = c.kit.scroll(col);
            fill();
            return scroll;
        }

        void fill() {
            col.removeAllViews();
            if (title != null) col.addView(c.kit.topBar(title, new Runnable() {
                @Override public void run() {
                    c.nav.back();
                }
            }, rightAction(), new Runnable() {
                @Override public void run() {
                    onRight();
                }
            }));
            build(col);
        }

        String rightAction() {
            return null;
        }

        void onRight() {
        }

        abstract void build(LinearLayout col);

        @Override public void onShown() {
            if (!mShown) {
                mShown = true;
                return;
            }
            if (scroll == null) return;
            final int y = scroll.getScrollY();
            fill();
            scroll.post(new Runnable() {
                @Override public void run() {
                    scroll.scrollTo(0, y);
                }
            });
        }

        /** 分组里一个插件都没加行 → 把分组和组标题一起拿掉。 */
        static void dropIfEmpty(LinearLayout root, LinearLayout group) {
            if (group.getChildCount() > 0) return;
            int i = root.indexOfChild(group);
            root.removeView(group);
            if (i > 0 && root.getChildAt(i - 1) instanceof TextView) root.removeViewAt(i - 1);
        }
    }

    /** 由插件画内容的页面（记忆 / 灵动岛 / 记一记设置 / 各功能细项）。 */
    static final class PluginPage extends BuiltPage {
        final String id;

        PluginPage(Console c, String id, String title) {
            super(c, title);
            this.id = id;
        }

        @Override void build(LinearLayout col) {
            FeatureKernel.get().addConsolePage(c, id, col);
        }
    }
}
