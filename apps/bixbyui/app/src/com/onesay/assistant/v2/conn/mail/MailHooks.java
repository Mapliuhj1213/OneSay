package com.onesay.assistant.v2.conn.mail;

import android.content.Context;

import com.onesay.assistant.v2.conn.ConnHttp;
import com.onesay.assistant.v2.conn.ConnSettings;
import com.onesay.assistant.v2.conn.ConnectorRegistry;
import com.onesay.assistant.v2.conn.Connector;
import com.onesay.assistant.v2.conn.SecretStore;
import com.onesay.assistant.v2.conn.google.GoogleHooks;
import com.onesay.assistant.v2.executor.MailBridge;

import org.json.JSONObject;

/**
 * [conn/mail] 邮件连接器的 Android 装配：Gmail（复用 Google 登录）+ IMAP/SMTP（复用现有「邮箱（IMAP）配置」的账号与授权码）。
 * 草稿台账存 bixby_conn prefs。发送目标 {@link MailSendTarget} 在这里登记到 ConnBackend。
 */
public final class MailHooks {

    private MailHooks() {}

    private static volatile boolean sTargetAdded;

    public static void install(final Context app, ConnectorRegistry reg, SecretStore secrets, ConnHttp http, MailConnector.NetPolicy net) {
        GmailAccount gmail = new GmailAccount(GoogleHooks.api(app));
        ImapAccount imap = new ImapAccount(new LazyImapOps(app), new ImapAccount.ReadyCheck() {
            @Override public boolean ready() { return MailBridge.isConfigured(app); }
        });
        DraftLedger ledger = new DraftLedger(new DraftLedger.Store() {
            @Override public String load() { return ConnSettings.prefs(app).getString(ConnSettings.KEY_MAIL_DRAFTS, "[]"); }
            @Override public void save(String json) { ConnSettings.prefs(app).edit().putString(ConnSettings.KEY_MAIL_DRAFTS, json).apply(); }
        });
        reg.register(new MailConnector(gmail, imap, ledger, net));
        if (!sTargetAdded) {
            sTargetAdded = true;
            com.onesay.assistant.v2.conn.ConnHooks.addBackendTarget(new MailSendTarget(new MailSendTarget.Provider() {
                @Override public MailConnector mail() { return current(); }
            }));
        }
    }

    /** 当前注册表里的邮件连接器（没有返回 null）。 */
    public static MailConnector current() {
        ConnectorRegistry r = com.onesay.assistant.v2.conn.ConnHooks.registryOrNull();
        Connector c = r == null ? null : r.get(MailConnector.ID);
        return c instanceof MailConnector ? (MailConnector) c : null;
    }

    /** AGENT_PRECHECK：send_draft 过闸前补 content_hash 与预览字段。不是发送工具返回 null。 */
    public static String precheck(String sign, JSONObject args) {
        if (!MailSendGuard.SIGN.equals(sign)) return null;
        MailConnector m = current();
        if (m == null) return "{\"ok\":false,\"error\":\"邮件连接器没就绪\"}";
        return m.prepareSendArgs(args);
    }

    /** 每次用时现读 IMAP 配置（用户改了授权码 / 服务器不用重启）。 */
    static final class LazyImapOps implements ImapOps {
        private final Context mApp;

        LazyImapOps(Context app) {
            mApp = app;
        }

        private ImapOps ops() {
            String host = MailBridge.host(mApp);
            String[] smtp = JavaMailImapOps.Config.smtpFor(host);
            String pass = com.onesay.assistant.v2.security.SecretPrefs.get(MailBridge.prefs(mApp), MailBridge.KEY_PASS);
            String socksHost = "";
            int socksPort = 0;
            String proxy = MailBridge.proxy(mApp);
            if (proxy != null && proxy.toLowerCase(java.util.Locale.ROOT).startsWith("socks")) {
                try {
                    java.net.URI u = new java.net.URI(proxy.trim());
                    socksHost = u.getHost();
                    socksPort = u.getPort();
                } catch (Exception ignored) {
                }
            }
            return new JavaMailImapOps(new JavaMailImapOps.Config(host, MailBridge.port(mApp), smtp[0], Integer.parseInt(smtp[1]),
                    "ssl".equals(smtp[2]), MailBridge.user(mApp), pass == null ? "" : pass.trim(), socksHost, socksPort));
        }

        @Override public java.util.List<MailSummary> unread(int scanMax) throws Exception { return ops().unread(scanMax); }
        @Override public java.util.List<MailSummary> search(String q, int max) throws Exception { return ops().search(q, max); }
        @Override public void appendDraft(MailDraft d, String key, int rev) throws Exception { ops().appendDraft(d, key, rev); }
        @Override public MailDraft readDraft(String key, int rev) throws Exception { return ops().readDraft(key, rev); }
        @Override public void smtpSend(MailDraft d) throws Exception { ops().smtpSend(d); }
        @Override public boolean deleteDraft(String key, int rev) throws Exception { return ops().deleteDraft(key, rev); }
        @Override public boolean markDraftSent(String key, int rev) throws Exception { return ops().markDraftSent(key, rev); }
    }
}
