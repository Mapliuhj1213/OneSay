package com.onesay.assistant.v2.conn.port;

import android.content.Context;
import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.conn.ConnHooks;
import com.onesay.assistant.v2.conn.mail.MailConnector;
import com.onesay.assistant.v2.conn.mail.MailHooks;
import com.onesay.assistant.v2.conn.mail.MailSendGuard;
import com.onesay.assistant.v2.conn.mail.MailService;
import com.onesay.assistant.v2.executor.ExecutionGate;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.executor.UnifiedExecutor;

import org.json.JSONObject;

/**
 * A↔B 合并：启动时把 {@link LaneBMailDraftAdapter} / {@link LaneBMailReadAdapter} 注册进 {@link Ports}（CallbackPlugin.onInit、
 * MailConfirmActivity.onCreate 都会调 {@link #ensure}，幂等）。
 *
 * <p>{@link #gate} 是 A 确认卡 → B 唯一发送路径的桥：A 的 {@link MailSendPermit} 已经在适配器里核对并作废之后，
 * 才用 B 的 ExecutionGate 令牌（签名 {@code conn.mail.send_draft}、参数 = 草稿号 + B 台账指纹）走 UnifiedExecutor →
 * MailSendTarget → MailSendGuard.fromReceipt → MailService.sendDraft。门禁没要求确认（= 不会留回执）时直接拒绝（失败关闭）。
 */
public final class LaneBMailPorts {

    private LaneBMailPorts() {}

    private static final String TAG = "CallbackPlugin";
    private static volatile boolean sInstalled;

    public static void ensure(Context ctx) {
        if (sInstalled || ctx == null) return;
        synchronized (LaneBMailPorts.class) {
            if (sInstalled) return;
            final Context app = ctx.getApplicationContext();
            LaneBMailDraftAdapter.ServiceProvider sp = provider(app);
            Ports.setMailDraft(new LaneBMailDraftAdapter(sp, gate(app)));
            Ports.setMailRead(new LaneBMailReadAdapter(sp));
            sInstalled = true;
            Log.i(TAG, "[MAIL-OUT] 已注册 B 线邮件适配器（LaneBMailDraftAdapter / LaneBMailReadAdapter），邮箱"
                    + (Ports.mailDraft().available() ? "已配置" : "未配置"));
        }
    }

    static LaneBMailDraftAdapter.ServiceProvider provider(final Context app) {
        return new LaneBMailDraftAdapter.ServiceProvider() {
            @Override public MailService service() {
                try {
                    if (!com.onesay.assistant.v2.conn.ConnSettings.SWITCH.on(app)) return null;
                    ConnHooks.registry(app);
                    return MailHooks.current();
                } catch (Throwable t) {
                    return null;
                }
            }

            @Override public boolean ready() {
                MailService s = service();
                return s instanceof MailConnector && ((MailConnector) s).isReady();
            }
        };
    }

    static LaneBMailDraftAdapter.Gate gate(final Context app) {
        return new LaneBMailDraftAdapter.Gate() {
            @Override public String prepare(JSONObject args) {
                return MailHooks.precheck(MailSendGuard.SIGN, args);
            }

            @Override public MailDraftPort.Result sendViaGate(JSONObject args) {
                Capability cap = ConnHooks.describe(app, MailSendGuard.SIGN);
                if (cap == null) return MailDraftPort.Result.fail("邮件连接器没就绪");
                ExecutionGate.Decision dec = ExecutionGate.evaluate(cap, args, false);
                if (!dec.needsConfirm() || dec.token == null) {
                    Log.w(TAG, "[MAIL-OUT] 发送闸没有要求确认（" + (dec.denied() ? "拒绝" : "放行") + "）→ 不发");
                    return MailDraftPort.Result.fail(dec.denied() ? dec.reason : "发送邮件必须经过确认闸");
                }
                // A 的确认卡就是这次的用户确认（许可已在适配器里核对并作废）
                if (!ExecutionGate.approve(dec.token)) return MailDraftPort.Result.fail("确认令牌失效");
                ExecutionResult r = UnifiedExecutor.getInstance(app).execute(cap, args, dec.token, false);
                Log.i(TAG, "[MAIL-OUT] B 发送路径 ok=" + r.ok + " code=" + r.code);
                return r.ok ? MailDraftPort.Result.ok(args.optString("draft_id", "")) : MailDraftPort.Result.fail(r.userMessage);
            }
        };
    }
}
