package com.onesay.assistant.v2.conn.port;

import com.onesay.assistant.v2.conn.ConnResult;
import com.onesay.assistant.v2.conn.mail.MailDraft;
import com.onesay.assistant.v2.conn.mail.MailService;

import org.json.JSONObject;

/**
 * A↔B 合并适配器：A 线外发邮件的 {@link MailDraftPort} 接到 B 线 {@link MailService}。纯 Java，JVM 可测。
 *
 * <h3>发送 = 双重核对，走 B 唯一的发送路径</h3>
 * <ol>
 *   <li>{@link Gate#prepare}：B 的 {@code MailHooks.precheck} 按草稿台账补 {@code content_hash}（草稿不在 B 台账里 → 拒绝）；</li>
 *   <li>A 核对：{@link MailSendPermit#use}(草稿号, B 台账指纹)——许可只由 A 确认卡令牌消费得来（一次性、绑草稿号 + 内容指纹）；
 *       许可为 null / 用过 / 草稿号不符 / 确认后被改过 → 不往下走；</li>
 *   <li>{@link Gate#sendViaGate}：B 的唯一发送路径 ExecutionGate 令牌（签名 conn.mail.send_draft，参数 = 草稿号 + 指纹）→
 *       UnifiedExecutor 消费令牌 → MailSendTarget → MailSendGuard.fromReceipt（再核一遍回执 / 参数摘要 / 指纹）→ MailService.sendDraft。</li>
 * </ol>
 * 本类自己<b>不调</b> {@code MailService.sendDraft}（也造不出 B 的 SendPermit）：发送只能经过 {@link Gate}。
 */
public final class LaneBMailDraftAdapter implements MailDraftPort {

    /** B 线发送闸（Android 装配见 LaneBMailPorts）。 */
    public interface Gate {
        /** 补 content_hash（B 台账）。@return null = 成功；否则给模型 / 用户的错误 JSON。 */
        String prepare(JSONObject args);

        /** 经 ExecutionGate + UnifiedExecutor 发出。 */
        Result sendViaGate(JSONObject args);
    }

    /** 当前的 B 邮件服务（注册表可能重建，所以每次现取；没有 = null）。 */
    public interface ServiceProvider {
        MailService service();

        /** 邮箱是否已配置（Gmail 授权或 IMAP 账号）。 */
        boolean ready();
    }

    private final ServiceProvider mSvc;
    private final Gate mGate;

    public LaneBMailDraftAdapter(ServiceProvider svc, Gate gate) {
        mSvc = svc;
        mGate = gate;
    }

    @Override public boolean available() {
        try {
            return mSvc != null && mSvc.service() != null && mSvc.ready();
        } catch (Throwable t) {
            return false;
        }
    }

    private MailService svc() {
        return mSvc == null ? null : mSvc.service();
    }

    static MailDraft toB(Draft d) {
        return new MailDraft(d.to, "", d.subject, d.body);
    }

    @Override public Result createDraft(Draft d) {
        MailService s = svc();
        if (s == null || !available()) return Result.fail("还没配置邮箱");
        if (d == null) return Result.fail("空草稿");
        ConnResult r = s.createDraft(toB(d), null);
        if (!r.ok()) return Result.fail(r.text);
        try {
            String id = new JSONObject(r.data).optString("draft_id", "");
            return id.length() == 0 ? Result.fail("邮件连接器没返回草稿号") : Result.ok(id);
        } catch (Exception e) {
            return Result.fail("邮件连接器返回格式不对");
        }
    }

    @Override public Result updateDraft(String draftId, Draft d) {
        MailService s = svc();
        if (s == null) return Result.fail("还没配置邮箱");
        if (d == null) return Result.fail("空草稿");
        ConnResult r = s.updateDraft(draftId, toB(d));
        return r.ok() ? Result.ok(draftId) : Result.fail(r.text);
    }

    @Override public Result deleteDraft(String draftId) {
        MailService s = svc();
        if (s == null) return Result.fail("还没配置邮箱");
        ConnResult r = s.deleteDraft(draftId);
        return r.ok() ? Result.ok(draftId) : Result.fail(r.text);
    }

    @Override public Result sendDraft(String draftId, MailSendPermit permit) {
        if (permit == null) return Result.fail("没有发送许可");
        if (draftId == null || draftId.length() == 0) return Result.fail("缺草稿号");
        if (mGate == null || !available()) return Result.fail("还没配置邮箱");
        JSONObject args = new JSONObject();
        try {
            args.put("draft_id", draftId);
        } catch (Exception e) {
            return Result.fail("参数写入失败");
        }
        String err = mGate.prepare(args);   // B 台账 → content_hash（与 A 指纹同一算法）
        if (err != null) return Result.fail(errText(err));
        String ledgerHash = args.optString("content_hash", "");
        if (ledgerHash.length() == 0) return Result.fail("草稿台账里没有内容指纹");
        String bad = permit.use(draftId, ledgerHash);   // A：确认卡令牌换来的许可，核对草稿号 + 指纹，一次性
        if (bad != null) return Result.fail(bad);
        return mGate.sendViaGate(args);                  // B：ExecutionGate 令牌 → MailSendGuard 回执 → sendDraft
    }

    static String errText(String json) {
        try {
            String e = new JSONObject(json).optString("error", "");
            return e.length() > 0 ? e : json;
        } catch (Exception ex) {
            return json;
        }
    }
}
