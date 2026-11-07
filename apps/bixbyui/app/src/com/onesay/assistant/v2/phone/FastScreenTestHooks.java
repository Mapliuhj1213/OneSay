package com.onesay.assistant.v2.phone;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import com.onesay.assistant.v2.exec.ExecModeController;
import com.onesay.assistant.v2.fastscreen.FastScreen;
import com.onesay.assistant.v2.island.IslandView;

import java.util.Arrays;

/**
 * ★ r2/fast-screen：debug 包的真机测试广播（经 TestRunReceiver，受签名级权限保护，adb 需 su 发）。
 * <pre>
 *  --es fs ping|bench|capture|capture_fallback|release|quit|status
 *  --es phone "&lt;任务&gt;" [--es phone_pkg &lt;包名&gt;] [--ez phone_legacy true] [--ez phone_exec false]
 *  --es exec takeover|resume|stop|allow|deny|supplement:&lt;文字&gt;|inject:x,y|state
 * </pre>
 * 结果打 TAG=FastScreenTest，并写 files/fs_test.txt / phone_last.txt。
 */
public final class FastScreenTestHooks {

    private FastScreenTestHooks() {}

    private static final String TAG = "FastScreenTest";

    public static boolean handle(final Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        final String fs = intent.getStringExtra("fs");
        final String phone = intent.getStringExtra("phone");
        final String exec = intent.getStringExtra("exec");
        if (fs == null && phone == null && exec == null) return false;
        if (exec != null) {
            execCmd(app, exec);
            return true;
        }
        if (phone != null) {
            final String pkg = intent.getStringExtra("phone_pkg");
            final boolean legacy = intent.getBooleanExtra("phone_legacy", false);
            final boolean em = intent.getBooleanExtra("phone_exec", true);
            final String script = intent.getStringExtra("phone_script");   // fast-screen-v2：脚本化 planner，步骤用 ||| 分隔
            final ScriptPhonePlanner sp = script == null ? null : new ScriptPhonePlanner(script);
            com.onesay.assistant.v2.util.Threads.start("PhoneTest", new Runnable() {
                @Override public void run() {
                    PhoneAgent.Result r = PhoneHooks.runBlocking(app, phone, pkg, legacy, em, sp);
                    String line = "PHONE_RESULT status=" + r.status + " calls=" + r.modelCalls + " actions=" + r.actions + " batches=" + r.batches
                            + " ms=" + r.ms + " answer=" + r.answer + " reason=" + r.reason;
                    Log.i(TAG, line);
                    StringBuilder b = new StringBuilder(line).append('\n');
                    for (String t : r.trace) b.append(t).append('\n');
                    if (sp != null) for (String u : sp.users) b.append("---- prompt\n").append(u).append('\n');
                    write(app, "phone_trace.txt", b.toString());
                }
            });
            return true;
        }
        com.onesay.assistant.v2.util.Threads.start("FsTest", new Runnable() {
            @Override public void run() {
                String out = fsCmd(app, fs);
                Log.i(TAG, "FS_RESULT " + fs + " " + out);
                write(app, "fs_test.txt", fs + " " + out + "\n");
            }
        });
        return true;
    }

    static String fsCmd(Context app, String cmd) {
        FastScreen f = FastScreen.get(app);
        if (cmd.equals("ping")) {
            long t0 = SystemClock.elapsedRealtime();
            boolean ok = f.ping();
            return "ok=" + ok + " " + (SystemClock.elapsedRealtime() - t0) + "ms " + f.status();
        }
        if (cmd.equals("status")) return f.status();
        if (cmd.equals("render")) {   // fast-screen-v2：当前界面给模型看的样子（写 files/fs_render.txt）
            UiTree u = new FastScreenDriver(app).observe(new UiTree.Ids());
            String r = u == null ? "null" : u.render(200);
            write(app, "fs_render.txt", "----\n" + r);
            return u == null ? "null" : "els=" + u.els.size() + " q=" + u.quality() + " ms=" + u.readMs;
        }
        if (cmd.equals("release")) {
            f.releaseForFallback();
            return "released";
        }
        if (cmd.equals("quit")) {
            f.quit();
            return "quit";
        }
        if (cmd.startsWith("bench")) {   // bench[:n]
            int n = cmd.indexOf(':') > 0 ? Integer.parseInt(cmd.substring(cmd.indexOf(':') + 1)) : 20;
            long[] d = new long[n];
            String pkg = "";
            int els = 0;
            for (int i = 0; i < n; i++) {
                long t0 = SystemClock.elapsedRealtime();
                com.onesay.assistant.v2.fastscreen.FsTree t = f.tree();
                d[i] = SystemClock.elapsedRealtime() - t0;
                if (t != null) {
                    pkg = t.pkg;
                    els = t.nodes.size();
                }
            }
            return "tree(App 侧往返含 JSON) " + stats(d) + " pkg=" + pkg + " nodes=" + els;
        }
        if (cmd.equals("capture") || cmd.equals("capture_fallback")) {
            boolean fb = cmd.equals("capture_fallback");
            android.content.SharedPreferences sp = app.getSharedPreferences(FastScreen.PREF, Context.MODE_PRIVATE);
            boolean was = sp.getBoolean(FastScreen.KEY_ENABLED, true);
            if (fb) {
                f.releaseForFallback();
                sp.edit().putBoolean(FastScreen.KEY_ENABLED, false).commit();
            }
            try {
                long t0 = SystemClock.elapsedRealtime();
                com.onesay.assistant.v2.vision.screenref.ScreenContext sc = com.onesay.assistant.v2.vision.screenref.ScreenContextCapture.captureSync(app);
                long ms = SystemClock.elapsedRealtime() - t0;
                return (fb ? "回退路径" : "快速路径") + " 识屏 " + ms + "ms source=" + (sc == null ? "null" : sc.source + " app=" + sc.pkg + "/" + sc.activity
                        + " texts=" + sc.texts.size() + " entities=" + sc.entities.size());
            } finally {
                if (fb) sp.edit().putBoolean(FastScreen.KEY_ENABLED, was).commit();
            }
        }
        return "unknown";
    }

    static void execCmd(final Context app, String cmd) {
        ExecModeController e = ExecModeController.current();
        if (cmd.equals("state")) {
            Log.i(TAG, "EXEC_STATE " + (e == null ? "none" : e.state.state() + " shield=" + e.state.shield() + " stray=" + e.state.strayTouches
                    + " navBack=" + e.navBack + " supplementShowing=" + e.supplementShowing()));
            return;
        }
        if (e == null) {
            Log.i(TAG, "EXEC_NONE " + cmd);
            return;
        }
        if (cmd.equals("takeover")) e.onIslandAction(e.id(), IslandView.A_EXEC_TAKEOVER);
        else if (cmd.equals("resume")) e.onIslandAction(e.id(), IslandView.A_EXEC_RESUME);
        else if (cmd.equals("supplement")) e.onIslandAction(e.id(), IslandView.A_EXEC_SUPPLEMENT);
        else if (cmd.startsWith("supplement:")) {
            e.onIslandAction(e.id(), IslandView.A_EXEC_SUPPLEMENT);
            e.submitSupplement(cmd.substring("supplement:".length()));
        } else if (cmd.equals("stop")) e.onBgStop(e.id());
        else if (cmd.equals("allow")) e.onBgConfirm(e.id(), true);
        else if (cmd.equals("deny")) e.onBgConfirm(e.id(), false);
        else if (cmd.startsWith("inject:")) {   // 护罩开孔注入竞态测试：inject:x,y[,n]
            final String[] a = cmd.substring(7).split(",");
            final int x = Integer.parseInt(a[0].trim()), y = Integer.parseInt(a[1].trim()), n = a.length > 2 ? Integer.parseInt(a[2].trim()) : 1;
            final ExecModeController ec = e;
            com.onesay.assistant.v2.util.Threads.start("InjectTest", new Runnable() {
                @Override public void run() {
                    FastScreenDriver d = new FastScreenDriver(app);
                    d.setShield(ec.shield());
                    long[] ms = new long[n];
                    int ok = 0;
                    for (int i = 0; i < n; i++) {
                        long t0 = SystemClock.elapsedRealtime();
                        if (d.injectTap(x, y)) ok++;
                        ms[i] = SystemClock.elapsedRealtime() - t0;
                        SystemClock.sleep(150);
                    }
                    Log.i(TAG, "INJECT_RESULT ok=" + ok + "/" + n + " " + stats(ms) + " holes=" + ec.shield().holes + " fallbacks=" + ec.shield().fallbacks
                            + " stray=" + ec.state.strayTouches);
                }
            });
        }
        Log.i(TAG, "EXEC_CMD " + cmd + " → " + e.state.state());
    }

    static String stats(long[] d) {
        long[] s = d.clone();
        Arrays.sort(s);
        int n = s.length;
        return "n=" + n + " p50=" + s[n / 2] + "ms p95=" + s[Math.min(n - 1, (int) Math.ceil(n * 0.95) - 1)] + "ms min=" + s[0] + " max=" + s[n - 1];
    }

    static void write(Context app, String name, String s) {
        try {
            java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(app.getFilesDir(), name), true);
            o.write(s.getBytes("UTF-8"));
            o.close();
        } catch (Throwable ignored) {
        }
    }
}
