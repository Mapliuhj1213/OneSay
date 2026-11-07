package com.onesay.assistant.v2.conn.mail;

import com.onesay.assistant.v2.conn.ConnResult;
import com.onesay.assistant.v2.conn.ConnTool;
import com.onesay.assistant.v2.conn.Connector;
import com.onesay.assistant.v2.conn.ToolEffect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * [conn/mail] 邮件连接器（id = mail）：把 Gmail 与 IMAP 两路统一成一个 {@link MailService}，并以 6 个工具交给 AgentLoop。
 *
 * <table>
 *   <tr><th>工具</th><th>等级</th><th>说明</th></tr>
 *   <tr><td>conn.mail.unread_important</td><td>READ</td><td>未读重要邮件</td></tr>
 *   <tr><td>conn.mail.search</td><td>READ</td><td>搜索</td></tr>
 *   <tr><td>conn.mail.create_draft</td><td>DRAFT</td><td>由模型按用户意图起草；只存草稿，不发送，不弹卡</td></tr>
 *   <tr><td>conn.mail.update_draft</td><td>DRAFT</td><td>重新起草（之前的确认作废）</td></tr>
 *   <tr><td>conn.mail.delete_draft</td><td>DRAFT</td><td>只能删本 App 建的草稿</td></tr>
 *   <tr><td>conn.mail.send_draft</td><td>COMMS</td><td>黄卡；不认「始终允许」；只经 {@link MailSendTarget} + {@link MailSendGuard} 执行</td></tr>
 * </table>
 *
 * <p>选路：account 参数指定就用指定的；不指定 = 自动（Gmail 就绪且 {@link NetPolicy} 认为连得上 Google 就先 Gmail，
 * Gmail 连不上 / 授权失效时读操作和新建草稿退到 IMAP，并在结果里说明）。已有草稿永远回到建它的那个邮箱。
 */
public final class MailConnector implements Connector, MailService {

    public static final String ID = "mail";
    public static final String T_UNREAD = "unread_important";
    public static final String T_SEARCH = "search";
    public static final String T_CREATE = "create_draft";
    public static final String T_UPDATE = "update_draft";
    public static final String T_DELETE = "delete_draft";
    public static final String T_SEND = "send_draft";

    /** 网络策略（第 4 项可达性探测接这里）：要不要先试 Google；Google 调用成功 / 连不上时回报。 */
    public interface NetPolicy {
        boolean tryGoogle();

        void reportGoogle(ConnResult r);

        /** 连不上 Google 时给用户的一句话（可达返回 ""）。 */
        String notice();
    }

    public static final NetPolicy ALWAYS_TRY = new NetPolicy() {
        @Override public boolean tryGoogle() { return true; }
        @Override public void reportGoogle(ConnResult r) { }
        @Override public String notice() { return ""; }
    };

    private final MailAccount mGmail;
    private final MailAccount mImap;
    private final DraftLedger mLedger;
    private final NetPolicy mNet;

    public MailConnector(MailAccount gmail, MailAccount imap, DraftLedger ledger, NetPolicy net) {
        mGmail = gmail;
        mImap = imap;
        mLedger = ledger != null ? ledger : new DraftLedger(null);
        mNet = net != null ? net : ALWAYS_TRY;
    }

    public DraftLedger ledger() { return mLedger; }

    @Override public String id() { return ID; }
    @Override public String title() { return "邮件（Gmail / IMAP）"; }
    @Override public boolean exposeToAgent() { return true; }

    @Override
    public boolean isReady() {
        return ready(mGmail) || ready(mImap);
    }

    static boolean ready(MailAccount a) {
        return a != null && a.isReady();
    }

    @Override public ConnResult refreshTools() { return ConnResult.ok("本地定义工具"); }

    @Override
    public List<ConnTool> tools() {
        List<ConnTool> l = new ArrayList<ConnTool>();
        if (!isReady()) return l;
        String acct = "account（可选）：gmail / imap，不填自动选（Gmail 连不上会自动改用 IMAP）";
        l.add(tool(T_UNREAD, "查未读的重要邮件（Gmail 用重要标记；IMAP 用加星 / 高优先级，没有就列最新未读）。", ToolEffect.READ,
                props("max", "integer", "最多几封，默认 5，上限 10", "account", "string", acct), null));
        l.add(tool(T_SEARCH, "按关键词搜索邮件（Gmail 支持 from: subject: newer_than: 等语法）。", ToolEffect.READ,
                props("query", "string", "搜索词", "max", "integer", "最多几封，默认 5", "account", "string", acct), new String[]{"query"}));
        l.add(tool(T_CREATE, "新建一封邮件草稿（只保存，不发送）。subject 和 body 必须由你根据用户的意图撰写成一封完整、得体的邮件"
                        + "（称呼、正文、落款齐全），不要照抄用户口述的原话；用户没给收件人邮箱就先问。返回 draft_id 和草稿内容，"
                        + "请把草稿念给 / 展示给用户看；用户要发送时再调用 conn.mail.send_draft（会弹确认卡）。", ToolEffect.DRAFT,
                props("to", "string", "收件人邮箱，多个用逗号分隔", "cc", "string", "抄送（可选）", "subject", "string", "你起草的主题",
                        "body", "string", "你起草的完整正文", "account", "string", acct), new String[]{"to", "subject", "body"}));
        l.add(tool(T_UPDATE, "用户要修改草稿时，按新的意图重新起草整封邮件并覆盖（同样不要照抄原话）。之前对这封草稿的确认会作废。", ToolEffect.DRAFT,
                props("draft_id", "string", "create_draft 返回的 draft_id", "to", "string", "收件人", "cc", "string", "抄送（可选）",
                        "subject", "string", "主题", "body", "string", "完整正文"), new String[]{"draft_id", "to", "subject", "body"}));
        l.add(tool(T_DELETE, "删除一封本助手建的草稿（用户说不发了 / 取消时）。", ToolEffect.DRAFT,
                props("draft_id", "string", "draft_id"), new String[]{"draft_id"}));
        l.add(tool(T_SEND, "发送一封已建好的草稿。只接受 draft_id；执行前一定会弹确认卡给用户看收件人、主题和正文，用户确认后才发出。"
                        + "只有用户明确要求发送时才调用。", ToolEffect.COMMS,
                props("draft_id", "string", "create_draft 返回的 draft_id"), new String[]{"draft_id"}));
        return l;
    }

    private static ConnTool tool(String name, String desc, ToolEffect e, JSONObject props, String[] required) {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object").put("properties", props);
            if (required != null) {
                JSONArray r = new JSONArray();
                for (String s : required) r.put(s);
                schema.put("required", r);
            }
        } catch (Exception ignored) {
        }
        return new ConnTool(ID, name, desc, schema, e);
    }

    private static JSONObject props(String... t) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 2 < t.length; i += 3) o.put(t[i], new JSONObject().put("type", t[i + 1]).put("description", t[i + 2]));
        } catch (Exception ignored) {
        }
        return o;
    }

    // ------------------------------------------------------------------ Connector.call（门禁已由上层完成）

    @Override
    public ConnResult call(String toolName, JSONObject args) {
        if (args == null) args = new JSONObject();
        String acct = args.optString("account", "").trim();
        if (acct.length() == 0) acct = null;
        if (T_UNREAD.equals(toolName)) return unreadImportant(args.optInt("max", 5), acct);
        if (T_SEARCH.equals(toolName)) return search(args.optString("query", ""), args.optInt("max", 5), acct);
        if (T_CREATE.equals(toolName)) return createDraft(MailDraft.fromArgs(args), acct);
        if (T_UPDATE.equals(toolName)) return updateDraft(args.optString("draft_id", ""), MailDraft.fromArgs(args));
        if (T_DELETE.equals(toolName)) return deleteDraft(args.optString("draft_id", ""));
        if (T_SEND.equals(toolName)) {
            // 发送绝不走通用路径：只有 MailSendTarget 拿着确认卡换来的 SendPermit 才能调 sendDraft
            return ConnResult.fail(ConnResult.Code.DENIED, "发送邮件只能经确认卡执行");
        }
        return ConnResult.fail(ConnResult.Code.DENIED, "未知工具 " + toolName);
    }

    // ------------------------------------------------------------------ MailService

    /** 候选邮箱（按优先级）。 */
    List<MailAccount> route(String account) {
        List<MailAccount> l = new ArrayList<MailAccount>();
        if (MailAccount.GMAIL.equals(account)) {
            if (mGmail != null) l.add(mGmail);
            return l;
        }
        if (MailAccount.IMAP.equals(account)) {
            if (mImap != null) l.add(mImap);
            return l;
        }
        if (ready(mGmail) && mNet.tryGoogle()) l.add(mGmail);
        if (ready(mImap)) l.add(mImap);
        if (l.isEmpty() && ready(mGmail)) l.add(mGmail);   // 只有 Gmail：即使判定连不上也试一次，给出明确错误
        return l;
    }

    private interface Op {
        ConnResult run(MailAccount a);
    }

    private ConnResult withFallback(String account, Op op) {
        List<MailAccount> l = route(account);
        if (l.isEmpty()) {
            return ConnResult.fail(ConnResult.Code.NOT_CONFIGURED, account == null ? "还没配置任何邮箱（控制台：邮箱（IMAP）配置 / 连接器 › Google 登录）"
                    : "邮箱 " + account + " 没配置");
        }
        String note = "";
        // Gmail 就绪却因为「连不上 Google」被跳过：直接用 IMAP，并告诉用户为什么
        if (account == null && ready(mGmail) && !l.contains(mGmail) && mNet.notice().length() > 0) {
            note = "（" + mNet.notice() + "，已改用 IMAP 邮箱）\n";
        }
        ConnResult last = null;
        for (int i = 0; i < l.size(); i++) {
            MailAccount a = l.get(i);
            if (!a.isReady()) {
                last = ConnResult.fail(ConnResult.Code.NOT_CONFIGURED, a.kind() + " 没配置");
                continue;
            }
            ConnResult r = op.run(a);
            if (MailAccount.GMAIL.equals(a.kind())) mNet.reportGoogle(r);
            if (r.ok()) return note.length() == 0 ? r : ConnResult.ok(note + r.text, r.data);
            last = r;
            boolean more = i + 1 < l.size();
            if (!(account == null && r.shouldFallback() && more)) break;
            note = "（Gmail 暂时用不了：" + reasonShort(r) + "，已改用 IMAP 邮箱）\n";
        }
        if (last != null && (last.code == ConnResult.Code.UNREACHABLE || last.code == ConnResult.Code.TIMEOUT
                || last.code == ConnResult.Code.TLS_ERROR) && !ready(mImap)) {
            return ConnResult.fail(last.code, last.text + "。没有可退回的邮箱：可在控制台「邮箱（IMAP）配置」填一个国内邮箱（如 QQ 邮箱）作为备用");
        }
        return last;
    }

    static String reasonShort(ConnResult r) {
        switch (r.code) {
            case UNREACHABLE: case TIMEOUT: return "连不上 Google";
            case TLS_ERROR: return "Google 证书校验失败";
            case AUTH_REVOKED: return "Google 授权失效";
            case NOT_CONFIGURED: return "未登录";
            default: return r.code.name();
        }
    }

    @Override
    public ConnResult unreadImportant(final int max, String account) {
        return withFallback(account, new Op() {
            @Override public ConnResult run(MailAccount a) { return a.unreadImportant(max).result; }
        });
    }

    @Override
    public ConnResult search(final String query, final int max, String account) {
        return withFallback(account, new Op() {
            @Override public ConnResult run(MailAccount a) { return a.search(query, max).result; }
        });
    }

    @Override
    public ConnResult createDraft(final MailDraft d, String account) {
        if (d == null) return ConnResult.fail(ConnResult.Code.BAD_ARGS, "空草稿");
        String why = d.validate();
        if (why != null) return ConnResult.fail(ConnResult.Code.BAD_ARGS, why);
        final String[] made = new String[1];
        ConnResult r = withFallback(account, new Op() {
            @Override public ConnResult run(MailAccount a) {
                MailAccount.Remote rr = a.createDraft(d);
                if (!rr.result.ok()) return rr.result;
                String id = mLedger.newId();
                mLedger.put(id, a.kind(), rr.remoteId, rr.rev, d);
                made[0] = id;
                return ConnResult.ok("草稿已保存（draft_id=" + id + "，存在" + (MailAccount.GMAIL.equals(a.kind()) ? " Gmail" : "邮箱") + "草稿箱，尚未发送）。\n"
                        + d.preview(600) + "\n请把草稿内容告诉用户；用户确认要发时调用 conn.mail.send_draft（会弹确认卡）。", draftJson(id, a.kind()));
            }
        });
        return r;
    }

    @Override
    public ConnResult updateDraft(String draftId, MailDraft d) {
        DraftLedger.Entry e = mLedger.get(draftId);
        if (e == null) return ConnResult.fail(ConnResult.Code.DENIED, "没有这个草稿（只能改本助手建的、7 天内的草稿）");
        String why = d == null ? "空草稿" : d.validate();
        if (why != null) return ConnResult.fail(ConnResult.Code.BAD_ARGS, why);
        MailAccount a = byKind(e.account);
        if (!ready(a)) return ConnResult.fail(ConnResult.Code.NOT_CONFIGURED, "草稿所在的邮箱（" + e.account + "）现在不可用");
        MailAccount.Remote rr = a.updateDraft(e.remoteId, e.rev, d);
        if (MailAccount.GMAIL.equals(a.kind())) mNet.reportGoogle(rr.result);
        if (!rr.result.ok()) return rr.result;
        mLedger.put(draftId, a.kind(), rr.remoteId, rr.rev, d);
        return ConnResult.ok("草稿已重新起草（draft_id=" + draftId + "，之前的确认已作废）。\n" + d.preview(600), draftJson(draftId, a.kind()));
    }

    @Override
    public ConnResult sendDraft(String draftId, SendPermit confirm) {
        if (confirm == null) return ConnResult.fail(ConnResult.Code.DENIED, "发送邮件必须经确认卡确认");
        DraftLedger.Entry e = mLedger.get(draftId);
        if (e == null) return ConnResult.fail(ConnResult.Code.DENIED, "没有这个草稿（可能已发送、已删除或超过 7 天）");
        String why = confirm.use(draftId, e.hash);
        if (why != null) return ConnResult.fail(ConnResult.Code.DENIED, why);
        MailAccount a = byKind(e.account);
        if (!ready(a)) return ConnResult.fail(ConnResult.Code.NOT_CONFIGURED, "草稿所在的邮箱（" + e.account + "）现在不可用，没有发送");
        ConnResult r = a.sendDraft(e.remoteId, e.rev, confirm);
        if (MailAccount.GMAIL.equals(a.kind())) mNet.reportGoogle(r);
        if (!r.ok()) return r;
        mLedger.remove(draftId);
        return ConnResult.ok("邮件已发送给 " + e.to + "（主题「" + e.subject + "」）。" + r.text, draftJson(draftId, a.kind()));
    }

    @Override
    public ConnResult deleteDraft(String draftId) {
        DraftLedger.Entry e = mLedger.get(draftId);
        if (e == null) return ConnResult.fail(ConnResult.Code.DENIED, "没有这个草稿（只能删本助手建的草稿）");
        MailAccount a = byKind(e.account);
        if (!ready(a)) return ConnResult.fail(ConnResult.Code.NOT_CONFIGURED, "草稿所在的邮箱现在不可用");
        ConnResult r = a.deleteDraft(e.remoteId, e.rev);
        if (r.ok()) mLedger.remove(draftId);
        return r;
    }

    MailAccount byKind(String kind) {
        if (MailAccount.GMAIL.equals(kind)) return mGmail;
        if (MailAccount.IMAP.equals(kind)) return mImap;
        return null;
    }

    static String draftJson(String id, String kind) {
        try {
            return new JSONObject().put("draft_id", id).put("account", kind).toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 确认卡准备（AGENT_PRECHECK / A 线）

    /**
     * 发送前把确认卡需要的东西写进参数：content_hash（令牌摘要因此绑定内容）、to / subject / body_preview（卡片展示）。
     * 模型自己填的同名字段一律覆盖。@return null = 可以去过闸；否则是给模型的观测（草稿不存在等）。
     */
    public String prepareSendArgs(JSONObject args) {
        if (args == null) return "{\"error\":\"缺 draft_id\"}";
        String id = args.optString("draft_id", "");
        DraftLedger.Entry e = mLedger.get(id);
        if (e == null) return "{\"ok\":false,\"error\":\"没有这个草稿（draft_id=" + id.replace("\"", "") + "）。请先用 conn.mail.create_draft 起草\"}";
        try {
            args.put("content_hash", e.hash);
            args.put("to", e.to);
            args.put("subject", e.subject);
            args.put("body_preview", e.bodyHead);
            args.put("account", e.account);
        } catch (Exception ex) {
            return "{\"error\":\"参数写入失败\"}";
        }
        return null;
    }

    @Override
    public String status() {
        return "mail gmail=" + (mGmail == null ? "-" : ready(mGmail) ? "ready" : "off") + " imap=" + (mImap == null ? "-" : ready(mImap) ? "ready" : "off")
                + " drafts=" + mLedger.all().size();
    }
}
