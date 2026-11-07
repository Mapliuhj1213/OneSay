package com.onesay.assistant.v2.brief;

import android.content.Context;
import android.util.Log;

/**
 * 经 root 守护向晨间播报 v2 发控制广播（{@link BriefControl#amCommand}）。后台线程执行，不阻塞调用方。
 * JVM 单测可替换 {@link #sSink} 截获命令。
 */
public final class BriefRemote {

    private BriefRemote() {}

    private static final String TAG = "BriefRemote";

    /** 命令出口（测试替换）。 */
    public interface Sink {
        /** 执行一条 root 命令；返回是否成功送出。 */
        boolean exec(Context ctx, String cmd);
    }

    static final Sink ROOT = new Sink() {
        @Override public boolean exec(Context ctx, String cmd) {
            if (ctx == null) return false;
            com.onesay.assistant.v2.executor.ShellExecutor.ShellResponse r = new com.onesay.assistant.v2.executor.ShellExecutor(ctx).execute(cmd);
            boolean ok = r != null && !r.isDown && !r.isTimeout && r.rc == 0;
            Log.i(TAG, "[BRIEF] → v2: " + cmd + (ok ? " ✓" : " ✗ " + (r == null ? "null" : r.output)));
            return ok;
        }
    };

    static volatile Sink sSink = ROOT;

    /** 测试入口：替换出口；传 null 复原。 */
    public static void setSinkForTest(Sink s) {
        sSink = s == null ? ROOT : s;
    }

    /** 同步发送（调用方已在后台线程）。 */
    public static boolean sendNow(Context ctx, BriefControl.Cmd cmd, int index) {
        try {
            return sSink.exec(ctx, BriefControl.amCommand(cmd, index));
        } catch (Throwable t) {
            Log.w(TAG, "[BRIEF] 发送失败: " + t);
            return false;
        }
    }

    /** 异步发送。 */
    public static void send(final Context ctx, final BriefControl.Cmd cmd, final int index) {
        final Context app = ctx == null ? null : ctx.getApplicationContext();
        if (sSink != ROOT) {   // 测试：同步，便于断言
            sendNow(app, cmd, index);
            return;
        }
        com.onesay.assistant.v2.util.Threads.start("brief-ctl-" + cmd.name().toLowerCase(java.util.Locale.ROOT), new Runnable() {
            @Override public void run() { sendNow(app, cmd, index); }
        });
    }
}
