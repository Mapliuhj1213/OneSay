package com.onesay.assistant.v2.brief;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 接收 OneSay Clock 的 {@code com.onesay.clock.BRIEF_STATE}（Manifest 静态登记，exported —— 跨 App 广播必须导出）。
 *
 * <p>收到后：功能关 → 直接丢；解析 → IGNORE 丢；<b>来源校验</b>（{@link BriefState#verifySource}：经 root 守护到 v2 私有目录里
 * 对账 state.json 的属主 uid 与内容）不过 → 丢并记日志；通过 → 上岛。root 调用在后台线程（goAsync），不卡主线程。
 *
 * <p>安全修复 IPC-6（pentest-20261024/ipc.md §6）：DETAIL 的 l2 / l3 文案不再直接取广播 extra 上岛 / 进大模型，
 * 改为再经 root 读 v2 私有目录的 brief.json（属主 uid 对账）取 news[index]，广播 l2/l3 只做一致性对账
 * （{@link BriefDetail#reconcileTitle}），不符 / 读不到 → 丢弃。
 * 给 v2 声明两包共享的 signature 权限不可行：signature 权限要求两包同一把签名私钥，而 v2 与 OneSay 各自签名、v2 仓库本轮不改。
 */
public class BriefStateReceiver extends BroadcastReceiver {

    private static final String TAG = "BriefState";

    @Override public void onReceive(final Context context, final Intent intent) {
        if (context == null || intent == null) return;
        final Context app = context.getApplicationContext();
        final BriefState.Event ev;
        try {
            ev = BriefState.parse(intent.getAction(), intent.getStringExtra("state"), intent.getStringExtra("detail"));
        } catch (Throwable t) {
            Log.w(TAG, "[BRIEF] extras 读不出，丢弃: " + t);
            return;
        }
        if (!com.onesay.assistant.v2.kernel.plugins.BriefPlugin.SWITCH.on(app)) {
            // 2026-10-23（lane-a B-9）：功能关着（含 kill-file）也要把岛上旧的简报撤掉 —— 例如 kill-file 期间 v2 发来的 DONE。
            // 只撤不上岛；撤的是本插件自己的活动，伪造广播最多让岛上的简报提前消失，所以不做来源校验。
            if (ev != null) BriefIsland.onEventWhileOff(ev);
            Log.i(TAG, "[BRIEF] 功能已关，丢弃");
            return;
        }
        final long now = System.currentTimeMillis();
        // lane-a 第 2 项：DETAIL 回执（我们发过 DETAIL 才认，防 v2 的调试 DETAIL / 伪造广播白白调大模型）→ 来源校验后展开
        final boolean detail = ev != null && "DETAIL".equals(ev.rawState);
        if (detail && !BriefIsland.consumeDetailRequest(now)) {
            Log.i(TAG, "[BRIEF] 收到 DETAIL 但最近没有发过「详细说说」，忽略");
            return;
        }
        final String l2 = detail ? intent.getStringExtra("l2") : null;
        final String l3 = detail ? intent.getStringExtra("l3") : null;
        if (ev == null || (ev.phase == BriefState.Phase.IGNORE && !detail)) return;
        final PendingResult pr = goAsync();
        com.onesay.assistant.v2.util.Threads.start("brief-state", new Runnable() {
            @Override public void run() {
                try {
                    int uid = senderUid(app);
                    // [companion] 先经时钟伴侣连接取 state.json（signature 权限双向保护，不要 root）；连接不通再试 root 守护
                    String out = uid >= 0 && !detail ? com.onesay.assistant.v2.companion.CompanionHooks.briefStateViaLink(app) : null;
                    if (out == null && uid >= 0) {
                        com.onesay.assistant.v2.executor.ShellExecutor.ShellResponse r =
                                new com.onesay.assistant.v2.executor.ShellExecutor(app).execute(BriefState.VERIFY_CMD);
                        if (r != null && r.isSuccess()) out = r.output;
                    }
                    String why = BriefState.verifySource(out, uid, ev, now);
                    if (why != null) {
                        Log.w(TAG, "[BRIEF] 来源校验未通过，丢弃 " + ev + "：" + why);
                        return;
                    }
                    if (detail) {
                        int index = BriefDetail.indexOf(ev.detail);
                        String bj = BriefDetail.readOwnedBriefJson(app);   // 常量命令 VERIFY_CMD，不拼广播内容
                        BriefDetail.Title t = BriefDetail.reconcileTitle(index, l2, l3, bj, uid);
                        if (t.title == null) {
                            Log.w(TAG, "[BRIEF] DETAIL 标题对账未通过，丢弃 " + ev + "：" + t.reason);
                            return;
                        }
                        BriefIsland.touch(now);
                        BriefDetail.run(app, index, t.title);
                        return;
                    }
                    BriefIsland.apply(app, ev);
                } catch (Throwable t) {
                    Log.w(TAG, "[BRIEF] 处理失败: " + t);
                } finally {
                    try { pr.finish(); } catch (Throwable ignored) { }
                }
            }
        });
    }

    static int senderUid(Context c) {
        try {
            return c.getPackageManager().getApplicationInfo(BriefState.SENDER_PKG, 0).uid;
        } catch (Throwable t) {
            return -1;
        }
    }
}
