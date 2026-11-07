package com.onesay.assistant;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.wake.WakeService;

/**
 * Bixby 按键守护进程 (hijack_daemon.sh) 兜底启动入口：
 *
 * 当手机冷启动或应用后台进程被杀死时，守护进程执行：
 *   cmd activity start-activity --user 0 -n com.onesay.assistant/.WebViewUiActivity -e ui_state listening --ez arm true
 *
 * 核心机制：
 * 1. 适配息屏解锁与亮屏 (FLAG_SHOW_WHEN_LOCKED / FLAG_TURN_SCREEN_ON)；
 * 2. 确保常驻 WakeService 启动并开窗 (armFromUi)；
 * 3. 呼出底部悬浮 Sheet 面板 (FloatingSheetService)；
 * 4. 立即 finish() 退出自身，不留全屏 Activity，保持被控应用 100% 可见。
 */
public class WebViewUiActivity extends Activity {

    private static final String TAG = "WebViewUiActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "WebViewUiActivity 触发启动 (来自 Bixby 按键守护进程冷启动/兜底通道)");
        processLaunch(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Log.i(TAG, "WebViewUiActivity 收到新 Intent (来自 Bixby 按键)");
        processLaunch(intent);
    }

    private void processLaunch(Intent intent) {
        // 1. 息屏唤醒与点亮屏幕
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );

        // 2. 保证后台 WakeService 唤醒服务存活
        Intent wakeIntent = new Intent(this, WakeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(wakeIntent);
        } else {
            startService(wakeIntent);
        }

        // 3. 请求统一唤醒入口。
        //    ★ 缺陷修复（2026-10-02）：本 Activity 与 hijack_daemon.sh 的 bixby.trigger
        //      文件通道是同一次按键的**两条并行路径**，此前各开一次窗、各播一次应答音，
        //      表现为"按一次唤醒两次"。现在统一交给 WakeService.requestWake()，
        //      由它按去抖窗丢弃重复请求；长按（-e mode conversation）只做模式升级不重播。
        // 守护 v7 在 App 进程不在时用 -e gesture long 拉起 → 不唤醒，直接交给插件（记一记）
        // ★ R12 控制台「暂停」：服务照样保活（上面已拉起），但不唤醒、不弹「启动中」面板、不交手势
        if (com.onesay.assistant.v2.wake.AssistantPause.isPaused(this)) {
            Log.i(TAG, "[PAUSE] 助手已暂停，按键冷启动只保活服务");
            finish();
            return;
        }
        if ("long".equals(intent.getStringExtra("gesture"))) {
            WakeService.dispatchKeyGesture(getApplicationContext(), "long");
            finish();
            return;
        }
        boolean conversation = "conversation".equals(intent.getStringExtra("mode"));
        WakeService.requestWake(WakeService.SRC_KEY_ACTIVITY, conversation);

        // 兜底：若常驻服务尚未就绪（冷启动场景），至少保证面板有即时反馈
        //   ★ round3 #3：此时语音引擎还在启动，唤醒已在 WakeService 里排队（开录后自动补发），
        //   提示语如实说明，别让用户以为已经在听。
        if (!WakeService.isServiceRunning() || !WakeService.isMicReady()) {
            FloatingSheetService.showListening(this, "语音引擎启动中，马上就好…");
        }

        // 4. 立即结束自身，确保不占用全屏焦点，完全暴露底层被控界面
        finish();
    }
}
