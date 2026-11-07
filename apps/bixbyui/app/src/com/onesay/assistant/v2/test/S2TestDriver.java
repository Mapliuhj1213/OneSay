package com.onesay.assistant.v2.test;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.onesay.assistant.v2.s2.S2Clock;
import com.onesay.assistant.v2.s2.S2Engine;
import com.onesay.assistant.v2.s2.S2Hooks;
import com.onesay.assistant.v2.s2.S2Settings;

/**
 * [s2] 真机验收用的调试驱动（<b>只在 debug 包</b>；TestRunReceiver 受签名级权限保护，adb 需 su）。
 *
 * <pre>
 * R=com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver
 * su -c "am broadcast -n $R --es s2 status"                          # 开关、条数、各意图组的默认 App、时钟偏移
 * su -c "am broadcast -n $R --es s2 set --es kv 'memory=0'"          # 改开关（memory/habit/ledger）
 * su -c "am broadcast -n $R --es s2 clock --es text '+1d'"           # 模拟「隔天」：S2 时钟 +1 天（0 = 恢复）
 * su -c "am broadcast -n $R --es s2 ask --es text '我车停哪了'"        # 只跑判定（不写库、不执行），日志打结论与回答
 * su -c "am broadcast -n $R --es s2 habit --es text 'taxi=didi*3'"   # 注入 3 次「打车用了滴滴」（clear = 清空习惯）
 * su -c "am broadcast -n $R --es s2 selftest"                        # 隔离库 s2_memory_selftest.db 跑一遍闭环
 * su -c "am broadcast -n $R --es s2 wipe"                            # 清空生产记忆库（不清习惯）
 * </pre>
 * 真实链路请用面板入口（验收脚本的 panel_query），本驱动只做「准备数据 / 改时钟 / 看状态」。日志 tag：S2TestDriver、S2。
 */
final class S2TestDriver {

    private static final String TAG = "S2TestDriver";

    private S2TestDriver() {}

    static void handle(final Context ctx, Intent intent) {
        final String cmd = intent.getStringExtra("s2");
        final String text = intent.getStringExtra("text");
        final String kv = intent.getStringExtra("kv");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    dispatch(ctx, cmd == null ? "status" : cmd.trim(), text, kv);
                } catch (Throwable t) {
                    Log.e(TAG, "[S2-TEST] " + cmd + " 异常: " + t.getMessage(), t);
                }
            }
        }, "S2TestDriver").start();
    }

    private static void dispatch(Context ctx, String cmd, String text, String kv) {
        if (cmd.startsWith("soul")) {   // [soul] r2/soul-memory 调试：soul_status / soul_inject / soul_hits / soul_remember / soul_consolidate / soul_fts
            com.onesay.assistant.v2.soul.SoulEngine se = com.onesay.assistant.v2.soul.SoulHooks.engine(ctx);
            long now = S2Clock.now();
            if ("soul_status".equals(cmd)) {
                Log.i(TAG, "[SOUL-TEST] status " + com.onesay.assistant.v2.soul.SoulHooks.debugStatus(ctx));
            } else if ("soul_inject".equals(cmd)) {
                com.onesay.assistant.v2.soul.SoulEngine.Injection inj = se.inject(now, "locked".equals(text));
                Log.i(TAG, "[SOUL-TEST] inject " + inj.stats() + " ≈" + com.onesay.assistant.v2.soul.SoulBudget.estimateTokens(inj.prefix)
                        + " tokens sha=" + Integer.toHexString(inj.prefix.hashCode()));
            } else if ("soul_hits".equals(cmd)) {
                String b = se.hitsBlock(text, now, false, se.inject(now, false));
                Log.i(TAG, "[SOUL-TEST] hits " + (b == null ? "null" : b.length() + " 字 " + com.onesay.assistant.v2.util.LogText.u(b, 40)));
            } else if ("soul_remember".equals(cmd) && text != null) {
                com.onesay.assistant.v2.soul.SoulEngine.WriteResult w = se.remember(text, com.onesay.assistant.v2.soul.Claim.SRC_USER, text, "debug", false, "", "", "", now);
                Log.i(TAG, "[SOUL-TEST] remember ok=" + w.ok + " " + (w.claim != null ? w.claim : w.reason));
            } else if ("soul_consolidate".equals(cmd)) {
                Log.i(TAG, "[SOUL-TEST] consolidate " + com.onesay.assistant.v2.soul.SoulHooks.consolidate(ctx, true));
            } else if ("soul_fts".equals(cmd)) {
                Log.i(TAG, "[SOUL-TEST] fts " + com.onesay.assistant.v2.soul.SoulHooks.fts());
            } else {
                Log.w(TAG, "[SOUL-TEST] 未知命令 " + cmd);
            }
            return;
        }
        if ("status".equals(cmd)) {
            Log.i(TAG, "[S2-TEST] status " + S2Hooks.debugStatus(ctx));
        } else if ("set".equals(cmd) && kv != null && kv.contains("=")) {
            String[] p = kv.split("=", 2);
            boolean on = "1".equals(p[1].trim()) || "true".equalsIgnoreCase(p[1].trim());
            S2Settings.set(ctx, p[0].trim(), on);
            Log.i(TAG, "[S2-TEST] set " + p[0].trim() + "=" + on + " → " + S2Settings.dump(ctx));
        } else if ("clock".equals(cmd)) {
            Long off = S2Clock.parseOffset(text == null ? "0" : text);
            if (off == null) {
                Log.w(TAG, "[S2-TEST] clock 参数无效: " + text);
                return;
            }
            S2Clock.setOffsetMs(off.longValue());
            Log.i(TAG, "[S2-TEST] clock offsetMs=" + off + "（S2 now=" + new java.util.Date(S2Clock.now()) + "）");
        } else if ("ask".equals(cmd)) {
            S2Engine.Result r = S2Hooks.engine(ctx).handle(text, S2Clock.now(), S2Settings.flags(ctx));
            Log.i(TAG, "[S2-TEST] ask → " + r + (r != null && r.reply != null ? " | " + r.reply.replace('\n', ' ') : ""));
        } else if ("habit".equals(cmd)) {
            if ("clear".equals(text)) {
                S2Hooks.engine(ctx).habits().clearHabits();
                Log.i(TAG, "[S2-TEST] habit 已清空");
                return;
            }
            // taxi=didi*3
            String[] p = text == null ? new String[0] : text.split("=", 2);
            if (p.length != 2 || S2Hooks.engine(ctx).roles().role(p[0].trim()) == null) {
                Log.w(TAG, "[S2-TEST] habit 参数无效: " + text);
                return;
            }
            String[] q = p[1].split("\\*");
            int n = q.length > 1 ? Integer.parseInt(q[1].trim()) : 1;
            for (int i = 0; i < n; i++) S2Hooks.engine(ctx).habits().recordUse(p[0].trim(), q[0].trim(), S2Clock.now() + i);
            Log.i(TAG, "[S2-TEST] habit 注入 " + p[0] + "=" + q[0] + " ×" + n + " → " + S2Hooks.debugStatus(ctx));
        } else if ("selftest".equals(cmd)) {
            selftest(ctx);
        } else if ("wipe".equals(cmd)) {
            int n = S2Hooks.engine(ctx).store().clear(null);
            Log.i(TAG, "[S2-TEST] wipe 删除 " + n + " 条");
        } else {
            Log.w(TAG, "[S2-TEST] 未知子命令: " + cmd);
        }
    }

    /** 隔离库闭环：记一下 → 隔天回忆 → 覆盖 → 敏感拒存 → 删除 → 习惯 3 次后默认。 */
    private static void selftest(Context ctx) {
        S2Engine e = S2Hooks.selfTestEngine(ctx);
        S2Engine.Flags f = new S2Engine.Flags(true, true, false);
        long now = System.currentTimeMillis();
        int pass = 0, total = 0;
        String[][] steps = {
                {"记一下车停在 B2-17", "NOTE_ADD"},
                {"+1d我车停哪了", "RECALL"},
                {"记一下车停在 B1-05", "NOTE_ADD"},
                {"我车停哪了", "RECALL"},
                {"记一下我的银行卡号是6222020200112233445", "NOTE_REFUSED"},
                {"删掉停车那条", "NOTE_DELETE"},
                {"上次记的停车位置", "RECALL_MISS"},
                {"打车", "-"},
        };
        StringBuilder sb = new StringBuilder();
        for (String[] st : steps) {
            String q = st[0];
            if (q.startsWith("+1d")) {
                now += 86400000L;
                q = q.substring(3);
            }
            S2Engine.Result r = e.handle(q, now, f);
            String got = r == null ? "-" : r.type.name();
            total++;
            boolean ok = got.equals(st[1]);
            // 2026-10-10 真机：本机只装了一个打车 App 时，「打车」按设计单装静默直启（HABIT_APPLY「只装了一个」），
            //   不是习惯误用；原期望「-」只在装了 0 个或多个时成立 → 环境相关，这里两种都算对。
            if (!ok && "打车".equals(q) && "HABIT_APPLY".equals(got) && String.valueOf(r).contains("只装了一个")) ok = true;
            if (ok) pass++;
            sb.append("\n  ").append(ok ? "PASS " : "FAIL ").append(q).append(" → ").append(got)
                    .append(r != null && r.reply != null ? " | " + r.reply.replace('\n', ' ') : "");
            if (r != null && r.sign != null && r.sign.startsWith("s2.")) {
                S2Engine.Exec x = e.execute(r.sign, r.args, now);
                sb.append(" | exec ok=").append(x.ok).append(' ').append(x.message);
            }
        }
        for (int i = 0; i < 3; i++) e.habits().recordUse("taxi", "didi", now + i);
        S2Engine.Result r = e.handle("打车", now + 10, f);
        total++;
        boolean ok = r != null && "app.launch".equals(r.sign);
        if (ok) pass++;
        sb.append("\n  ").append(ok ? "PASS " : "FAIL ").append("打车（3 次滴滴后）→ ").append(r);
        Log.i(TAG, "[S2-TEST] selftest " + pass + "/" + total + "（隔离库 s2_memory_selftest.db）" + sb);
    }
}
