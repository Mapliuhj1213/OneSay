package com.onesay.assistant.v2.companion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * [companion] 计划 / 时钟反查助手（有序广播 {@link CompanionContract#ACTION_QUERY}）。
 * 清单里 exported，但受 signature 级 CONTROL 权限保护（debug 包由 build_linux.sh 改名为 …debug.permission.CONTROL，
 * 计划 / 时钟清单里的 uses-permission 用占位符 ${assistantAppId} 生成，两边永远对得上）。
 * 只读：返回 link.hello / getAssistantStatus 的 JSON，没有任何写入口。
 */
public class CompanionLinkReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context c, Intent in) {
        if (c == null || in == null || !CompanionContract.ACTION_QUERY.equals(in.getAction())) return;
        String m = in.getStringExtra(CompanionContract.K_METHOD);
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        int code;
        String data;
        if (CompanionContract.M_HELLO.equals(m) || CompanionContract.F_GET_ASSISTANT_STATUS.equals(m)) {
            code = CompanionContract.RC_OK;
            data = CompanionHooks.assistantStatus(app);
            if (CompanionContract.M_HELLO.equals(m)) data = CompanionDescriptor.helloJson(app.getPackageName(), data);
        } else {
            code = CompanionContract.RC_UNKNOWN;
            data = "{}";
        }
        Log.i(CompanionLink.TAG, "[COMPANION] 对端反查 " + m + " rc=" + code);
        if (isOrderedBroadcast()) {
            setResultCode(code);
            setResultData(data);
        }
    }
}
