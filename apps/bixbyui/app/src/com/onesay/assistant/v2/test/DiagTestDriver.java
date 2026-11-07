package com.onesay.assistant.v2.test;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.diag.DiagCapabilities;
import com.onesay.assistant.v2.diag.DiagEngine;
import com.onesay.assistant.v2.diag.DiagHooks;
import com.onesay.assistant.v2.diag.DiagKnowledge;
import com.onesay.assistant.v2.diag.DiagMatcher;
import com.onesay.assistant.v2.diag.DiagSettings;
import com.onesay.assistant.v2.executor.ExecutionGate;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.executor.ShellExecutor;
import com.onesay.assistant.v2.executor.UnifiedExecutor;
import com.onesay.assistant.v2.router.TriageDecider;
import com.onesay.assistant.v2.router.UnifiedRouter;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * [diag] 真机验收用的调试驱动（<b>只在 debug 包</b>；TestRunReceiver 受签名级权限保护，adb 需 su）。
 *
 * <pre>
 * R=com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver
 * su -c "am broadcast -n $R --es diag status"                              # 开关、撤销记录、表规模
 * su -c "am broadcast -n $R --es diag set --es kv 'enabled=0'"             # 改开关（enabled / llm）
 * su -c "am broadcast -n $R --es diag match --es text '手机耗电好快'"        # 只判定（分诊标签 + 症状 id），不读设置
 * su -c "am broadcast -n $R --es diag ask --es text '为什么口袋里屏幕总亮'"   # 真实只读诊断（读本机设置），日志打回答与芯片
 * su -c "am broadcast -n $R --es diag keys"                                # 核实键名：表内每个键在本机的值（null = 不存在）+ 三星候选键名
 * su -c "am broadcast -n $R --es diag fix --es text 'timeout_5m'"          # 模拟「点芯片 + 黄卡点允许」：门禁令牌 → UnifiedExecutor → DiagBackend
 * su -c "am broadcast -n $R --es diag undo"                                # 撤销最近一次修复（与说「撤销」同一个能力）
 * </pre>
 * 真实链路请用面板入口（验收脚本的 panel_query）。日志 tag：DiagTestDriver、Diag。
 */
final class DiagTestDriver {

    private static final String TAG = "DiagTestDriver";

    private DiagTestDriver() {}

    static void handle(final Context ctx, Intent intent) {
        final String cmd = intent.getStringExtra("diag");
        final String text = intent.getStringExtra("text");
        final String kv = intent.getStringExtra("kv");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    dispatch(ctx, cmd == null ? "status" : cmd.trim(), text, kv);
                } catch (Throwable t) {
                    Log.e(TAG, "[DIAG-TEST] " + cmd + " 异常: " + t.getMessage(), t);
                }
            }
        }, "DiagTestDriver").start();
    }

    private static void dispatch(Context ctx, String cmd, String text, String kv) {
        if ("status".equals(cmd)) {
            Log.i(TAG, "[DIAG-TEST] status " + DiagHooks.debugStatus(ctx));
        } else if ("set".equals(cmd) && kv != null && kv.contains("=")) {
            String[] p = kv.split("=", 2);
            boolean on = "1".equals(p[1].trim()) || "true".equalsIgnoreCase(p[1].trim());
            DiagSettings.set(ctx, p[0].trim(), on);
            Log.i(TAG, "[DIAG-TEST] set " + p[0].trim() + "=" + on + " → " + DiagSettings.dump(ctx));
        } else if ("match".equals(cmd)) {
            TriageDecider.Decision d = TriageDecider.decide(text, TriageDecider.Signals.offline(), DiagSettings.enabled(ctx));
            DiagMatcher.Match m = TriageDecider.diagnoseMatch(text);
            Log.i(TAG, "[DIAG-TEST] match label=" + d.label + " rule=" + d.rule + " symptom=" + (m != null ? m.id() : "-") + " | " + text);
        } else if ("ask".equals(cmd)) {
            DiagMatcher.Match m = TriageDecider.diagnoseMatch(text);
            if (m == null) {
                Log.i(TAG, "[DIAG-TEST] ask 不是症状句: " + text);
                return;
            }
            DiagEngine.Report r = DiagHooks.diagnose(ctx, text, TriageDecider.normalize(text), m,
                    UnifiedRouter.getInstance(ctx).getL2());
            if (r == null) {
                Log.w(TAG, "[DIAG-TEST] ask 读取失败（特权通道不可用）");
                return;
            }
            Log.i(TAG, "[DIAG-TEST] ask " + r + " | " + r.text.replace('\n', ' '));
        } else if ("keys".equals(cmd)) {
            List<String> ids = new ArrayList<String>(DiagKnowledge.KEYS.keySet());
            Map<String, String> snap = DiagHooks.readSnapshot(ctx, ids);
            if (snap == null) {
                Log.w(TAG, "[DIAG-TEST] keys 读取失败（特权通道不可用）");
                return;
            }
            int present = 0;
            for (Map.Entry<String, String> e : snap.entrySet()) {
                DiagKnowledge.Key k = DiagKnowledge.key(e.getKey());
                if (e.getValue() != null) present++;
                Log.i(TAG, "[DIAG-TEST] key " + e.getKey() + "=" + e.getValue() + " verify=" + (k != null ? k.verify : "?"));
            }
            Log.i(TAG, "[DIAG-TEST] keys present " + present + "/" + snap.size());
            ShellExecutor sh = UnifiedExecutor.getInstance(ctx).getShellExecutor();
            ShellExecutor.ShellResponse r = sh.execute("for ns in system secure global; do settings list $ns 2>/dev/null"
                    + " | cut -d= -f1 | grep -iE 'pocket|accident|touch_block|intelligent|smart_stay|lift|wake|aod|blue_light|night|motion_pick|sound_off|zen'"
                    + " | sed \"s/^/$ns\\//\"; done");
            Log.i(TAG, "[DIAG-TEST] candidates " + (r.output != null ? r.output.replace('\n', ' ') : ""));
        } else if ("fix".equals(cmd)) {
            DiagKnowledge.Fix f = DiagKnowledge.fix(text);
            if (f == null || !f.writes()) {
                Log.w(TAG, "[DIAG-TEST] fix 无效: " + text);
                return;
            }
            Capability cap = DiagCapabilities.fix(f, f.label + "（调试驱动模拟点击）");
            JSONObject args = DiagCapabilities.fixArgs(f.id);
            ExecutionGate.Decision g = ExecutionGate.evaluate(cap, args, false);
            Log.i(TAG, "[DIAG-TEST] fix gate=" + g.decision + " tier=" + g.tier);
            ExecutionGate.ConfirmToken tok = g.token;
            if (tok != null) ExecutionGate.approve(tok);   // 模拟用户在黄卡上点「允许单次」
            ExecutionResult r = UnifiedExecutor.getInstance(ctx).execute(cap, args, tok, false);
            Log.i(TAG, "[DIAG-TEST] fix " + f.id + " ok=" + r.ok + " verified=" + r.verification + " msg=" + r.userMessage + " detail=" + r.detail);
        } else if ("undo".equals(cmd)) {
            Capability cap = DiagCapabilities.undo(null);
            JSONObject args = DiagCapabilities.undoArgs(text);
            ExecutionGate.Decision g = ExecutionGate.evaluate(cap, args, false);
            ExecutionResult r = UnifiedExecutor.getInstance(ctx).execute(cap, args, g.token, false);
            Log.i(TAG, "[DIAG-TEST] undo gate=" + g.decision + " ok=" + r.ok + " msg=" + r.userMessage + " detail=" + r.detail);
        } else {
            Log.w(TAG, "[DIAG-TEST] 未知子命令: " + cmd);
        }
    }
}
