package com.onesay.assistant.v2.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.ui.console.Console;

/**
 * OneSay 控制台（主入口 Activity）。
 *
 * <p>★ R12 控制台重设计：本类只当宿主 —— 初始化能力表、拉起 WakeService（验收 IN-3 靠 {@code am start MainActivity} 拉起服务，
 * 这一点不能变）、处理岛 / 语音带来的 {@code page=quicknote} 深链。界面全部在 {@code ui/console}：
 * 首页 → 计划（计划 App / 本机计划页）/ 功能与权限 / 记忆 / 灵动岛 / 语音 / 模型与密钥 / 关于 /（开发者）的页面栈，<b>不加 Activity、不用 Fragment、不改 Manifest</b>。
 * 各功能的开关由插件经 {@link com.onesay.assistant.v2.kernel.ConsoleHost} 画（内核 addConsolePage / addConsoleCards）。
 */
public class MainActivity extends Activity implements com.onesay.assistant.v2.quicknote.ui.QuickNotePage.Host {

    private Console mConsole;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ★ r2/input：控制台里的输入框（记一记搜索、事件任务、各类设置对话框）弹键盘时窗口压缩到键盘上方，内容可滚动 ——
        //   Manifest 没写 windowSoftInputMode（默认「未指定」，系统按内容猜 pan / resize，pan 会把输入框顶出屏幕上沿）。在代码里设，不改 Manifest。
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        // 先把手写能力与自动挖掘的系统接口都加载进注册表，再构造任何消费者
        CapabilityRegistry.getInstance().init(getApplicationContext());
        L2LLMCompiler compiler = new L2LLMCompiler(this);

        ensureWakeServiceStarted();

        mConsole = new Console(this, compiler);
        setContentView(mConsole.root());
        ImeInsetPadder.attach(this, mConsole.root());   // ★ integrate：沉浸式全屏下 adjustResize 不生效时手动让位
        mConsole.start();
        handlePage(getIntent());   // ★ R10：岛上结果卡 / 语音「打开记一记」带 page=quicknote 冷启动
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mConsole != null) mConsole.onResume();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mConsole != null) mConsole.onStop();
    }

    /**
     * 唤醒修复：<b>推到本轮主线程消息之后</b>再 startForegroundService。
     * <p>startForegroundService 一调用系统就开始计时，而服务的 onCreate / onStartCommand 也跑在这条主线程上 ——
     * 旧实现在 onCreate 中途就调，计时期间主线程还要建控制台、插件、首帧（重装后第一次冷启动 dex 没优化，要好几秒），
     * 11-08 真机因此 ANR + 崩溃。推迟后从调用到服务 startForeground 之间主线程上只剩服务自己的创建消息。
     * 调用点仍在 onCreate 里、mConsole.start() 之前（IN-3：am start MainActivity 照样拉起服务）。
     */
    private void ensureWakeServiceStarted() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    Intent it = new Intent(MainActivity.this, com.onesay.assistant.v2.wake.WakeService.class);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(it);
                    } else {
                        startService(it);
                    }
                } catch (Throwable t) {
                    android.util.Log.w("MainActivity", "启动 WakeService 异常: " + t.getMessage());
                }
            }
        });
    }

    // ------------------------------------------------------------------ ★ R10 记一记页（QuickNotePage，不新增 Activity）

    @Override public void showPage(View v) {
        mConsole.showForeign(v);
    }

    @Override public void closePage() {
        mConsole.closeForeign();
    }

    @Override public void openQuickNoteSettings() {
        mConsole.openPage(com.onesay.assistant.v2.kernel.ConsoleHost.PAGE_QUICKNOTE, "记一记设置");
    }

    @Override public void onBackPressed() {
        if (mConsole != null && mConsole.back()) return;
        super.onBackPressed();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePage(intent);
    }

    /** 岛 / 语音带过来的 page extra：quicknote（可带 id）→ 记一记列表 / 详情；其余照旧停在控制台。 */
    private void handlePage(Intent it) {
        if (it == null) return;
        String page = it.getStringExtra("page");
        if (ConsoleDeepLink.isConsoleLink(page) && mConsole != null) {   // ★：岛上提醒「立即查看 / 回调历史」→ 控制台细项页
            // r2/fixes 8：MainActivity 导出，外部 App 也能带 page=console:<id>；只认白名单页，其余打开首页
            // 白名单之外再看来源口令——只有本 App 自己发的深链（带进程内口令）直达；
            //   外部 App 拉起敏感配置页（asr.key）要页内再确认，其它页回首页。
            String id = ConsoleDeepLink.allowedPage(page);
            if (id == null) {
                android.util.Log.w("MainActivity", "[DEEPLINK] 不在白名单的控制台页，打开首页");
                mConsole.nav.popToRoot();
                return;
            }
            final ConsoleDeepLink.Decision d = ConsoleDeepLink.decide(page, it.getStringExtra(ConsoleDeepLink.EXTRA_TOKEN));
            it.removeExtra(ConsoleDeepLink.EXTRA_TOKEN);   // 用过即弃，不随 Intent 留在任务栈里
            final String title = ConsoleDeepLink.safeTitle(it.getStringExtra("title"));
            if (d.action == ConsoleDeepLink.Action.OPEN) {
                mConsole.openPage(d.id, title);
                return;
            }
            mConsole.nav.popToRoot();
            if (d.action == ConsoleDeepLink.Action.CONFIRM) {
                android.util.Log.w("MainActivity", "[DEEPLINK] 外部深链请求敏感页，页内再确认");
                mConsole.confirm("打开「" + (title.length() > 0 ? title : "设置") + "」？",
                        "有其他应用请求打开这个设置页。如果不是你刚刚点的，请取消。", "打开", new Runnable() {
                            @Override public void run() {
                                mConsole.openPage(d.id, title);
                            }
                        });
            } else {
                android.util.Log.w("MainActivity", "[DEEPLINK] 外部深链（无口令），打开首页");
            }
            return;
        }
        if (!"quicknote".equals(page)) return;
        long id = it.getLongExtra("id", -1L);
        com.onesay.assistant.v2.quicknote.ui.QuickNotePage.open(this, id);
    }
}
