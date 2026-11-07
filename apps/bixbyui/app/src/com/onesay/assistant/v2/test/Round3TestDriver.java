package com.onesay.assistant.v2.test;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ConsentPolicy;
import com.onesay.assistant.v2.router.TriageDecider;
import com.onesay.assistant.v2.s2.ImplicitNote;
import com.onesay.assistant.v2.s2.S2Clock;
import com.onesay.assistant.v2.s2.S2Engine;
import com.onesay.assistant.v2.s2.S2Hooks;
import com.onesay.assistant.v2.s2.S2Settings;
import com.onesay.assistant.v2.ui.ActionChipsView;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.ui.FloatingSheetView;
import com.onesay.assistant.v2.wake.WakeService;

/**
 * 真机验收调试驱动（<b>只在 debug 包</b>；TestRunReceiver 受签名级权限保护，adb 需 su）。
 *
 * <pre>
 * R=com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver
 * su -c "am broadcast -n $R --es round3 reject_red"        # #1 红卡点「取消」：0.6 s 时应是「已拒绝」卡、面板在；3.6 s 时面板已收起
 * su -c "am broadcast -n $R --es round3 reject_yellow"     # #1 黄卡点「拒绝」：同上
 * su -c "am broadcast -n $R --es round3 reject_then_card"  # #1 S1 不变量：拒绝后 1 s 内又弹出一张确认卡 → 到点不收起、新卡还在
 * su -c "am broadcast -n $R --es round3 judge --es text '北京在华北'"  # #2 只判定（不写库、不执行）：ImplicitNote / 分诊 / S2 结论
 * su -c "am broadcast -n $R --es round3 undo_chip"         # #2 点面板上的第一个动作芯片（记下后的「撤销」）
 * su -c "am broadcast -n $R --es round3 cold_status"       # #3 冷启动闸门：面板服务是否在 / 是否就绪；WakeService 是否在 / 麦克风是否开录
 * </pre>
 * 日志 tag：Round3TestDriver（判定行以 [R3] 开头，自动判定行 [R3-PASS] / [R3-FAIL]）。
 */
final class Round3TestDriver {

    private static final String TAG = "Round3TestDriver";

    private Round3TestDriver() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void handle(final Context ctx, Intent intent) {
        final String cmd = intent.getStringExtra("round3");
        final String text = intent.getStringExtra("text");
        try {
            dispatch(ctx, cmd == null ? "cold_status" : cmd.trim(), text);
        } catch (Throwable t) {
            Log.e(TAG, "[R3] " + cmd + " 异常: " + t.getMessage(), t);
        }
    }

    private static void dispatch(final Context ctx, String cmd, final String text) {
        if ("reject_red".equals(cmd) || "reject_yellow".equals(cmd) || "reject_then_card".equals(cmd)) {
            reject(ctx, cmd);
        } else if ("judge".equals(cmd)) {
            judge(ctx, text);
        } else if ("undo_chip".equals(cmd)) {
            MAIN.post(new Runnable() {
                @Override public void run() {
                    FloatingSheetService svc = FloatingSheetService.getInstance();
                    FloatingSheetView v = svc != null ? svc.getSheetView() : null;
                    View chips = v != null ? v.findViewWithTag(ActionChipsView.TAG_IN_SHEET) : null;
                    if (chips instanceof ViewGroup && ((ViewGroup) chips).getChildCount() > 0
                            && ((ViewGroup) chips).getChildAt(0) instanceof ViewGroup
                            && ((ViewGroup) ((ViewGroup) chips).getChildAt(0)).getChildCount() > 0
                            && chips.getVisibility() == View.VISIBLE) {
                        View first = ((ViewGroup) ((ViewGroup) chips).getChildAt(0)).getChildAt(0);
                        Log.i(TAG, "[R3] undo_chip 点击芯片: " + first.getContentDescription());
                        first.performClick();
                    } else {
                        Log.w(TAG, "[R3-FAIL] undo_chip 面板上没有可点的动作芯片");
                    }
                }
            });
        } else {
            FloatingSheetService svc = FloatingSheetService.getInstance();
            Log.i(TAG, "[R3] cold_status panelService=" + (svc != null) + " panelReady=" + (svc != null && svc.isColdReady())
                    + " wakeService=" + WakeService.isServiceRunning() + " micReady=" + WakeService.isMicReady()
                    + " s2=" + S2Settings.dump(ctx));
        }
    }

    // ------------------------------------------------------------------ #1 拒绝终态

    private static void reject(final Context ctx, final String cmd) {
        FloatingSheetService.show(ctx, null);
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                final FloatingSheetService svc = FloatingSheetService.getInstance();
                final FloatingSheetView v = svc != null ? svc.getSheetView() : null;
                if (v == null) {
                    Log.w(TAG, "[R3-FAIL] " + cmd + " 面板服务不在");
                    return;
                }
                boolean red = !"reject_yellow".equals(cmd);
                // 用一个不会真的执行的能力做卡片（拒绝路径永远不执行）
                Capability cap = CapabilityRegistry.getInstance().describe(red ? "sys.wipe" : "connectivity.dataroam.off");
                if (cap == null) {
                    Log.w(TAG, "[R3-FAIL] " + cmd + " 找不到测试能力");
                    return;
                }
                v.showConfirmCard(cap, new org.json.JSONObject(), false, red ? ConsentPolicy.TIER_RED : ConsentPolicy.TIER_YELLOW, null);
                Log.i(TAG, "[R3] " + cmd + " 已弹出" + (red ? "红" : "黄") + "卡，模拟点击「" + (red ? "取消" : "拒绝") + "」");
                if (red) v.getBtnCancel().performClick();
                else v.getBtnReject().performClick();
                if ("reject_then_card".equals(cmd)) {
                    MAIN.postDelayed(new Runnable() {
                        @Override public void run() {
                            Capability y = CapabilityRegistry.getInstance().describe("connectivity.dataroam.off");
                            if (y != null) v.showConfirmCard(y, new org.json.JSONObject(), false, ConsentPolicy.TIER_YELLOW, null);
                            Log.i(TAG, "[R3] reject_then_card 拒绝后 1 s 又弹出一张黄卡（未决）");
                        }
                    }, 1000);
                }
                MAIN.postDelayed(new Runnable() {
                    @Override public void run() {
                        boolean attached = v.getParent() != null && v.getVisibility() == View.VISIBLE;
                        boolean rejected = v.isRejectedShowing();
                        Log.i(TAG, "[R3] " + cmd + " t+0.6s attached=" + attached + " rejectedCard=" + rejected);
                        if (!"reject_then_card".equals(cmd)) {
                            Log.i(TAG, (attached && rejected ? "[R3-PASS] " : "[R3-FAIL] ") + cmd + " 拒绝后显示「已拒绝」且面板未立即收起");
                        }
                    }
                }, 600);
                MAIN.postDelayed(new Runnable() {
                    @Override public void run() {
                        boolean attached = v.getParent() != null && v.getVisibility() == View.VISIBLE;
                        if ("reject_then_card".equals(cmd)) {
                            boolean pending = v.isConfirmShowing();
                            Log.i(TAG, (attached && pending ? "[R3-PASS] " : "[R3-FAIL] ") + cmd
                                    + " 新卡未决时到点不收起（attached=" + attached + " confirmPending=" + pending + "）");
                            v.hideConfirmCard();
                            FloatingSheetService.hide(ctx);
                        } else {
                            Log.i(TAG, (!attached ? "[R3-PASS] " : "[R3-FAIL] ") + cmd + " 约 2.5 s 后回中性态并收起（t+3.6s attached=" + attached + "）");
                        }
                    }
                }, 3600);
            }
        }, 900);
    }

    // ------------------------------------------------------------------ #2 免前缀记忆：只判定

    private static void judge(final Context ctx, final String text) {
        if (text == null) {
            Log.w(TAG, "[R3] judge 需要 --es text");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                ImplicitNote.Hit h = ImplicitNote.detect(text);
                TriageDecider.Decision d = TriageDecider.decide(text, TriageDecider.Signals.offline(), true, S2Settings.implicitEnabled(ctx));
                S2Engine.Result r = S2Hooks.selfTestEngine(ctx).handle(text, S2Clock.now(), S2Settings.flags(ctx));
                boolean stored = r != null && (r.type == S2Engine.Type.NOTE_ADD || r.type == S2Engine.Type.NOTE_REFUSED);
                Log.i(TAG, "[R3] judge 「" + text + "」 implicit=" + h + " triage=" + d.label + "(" + d.rule + ") s2="
                        + (r == null ? "放行" : r.type.name()) + " stored=" + stored);
            }
        }, "Round3Judge").start();
    }
}
