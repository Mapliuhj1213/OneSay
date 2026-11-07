package com.onesay.assistant.v2.conn.mail;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import javax.mail.Address;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.search.AndTerm;
import javax.mail.search.BodyTerm;
import javax.mail.search.FlagTerm;
import javax.mail.search.FromStringTerm;
import javax.mail.search.HeaderTerm;
import javax.mail.search.OrTerm;
import javax.mail.search.SearchTerm;
import javax.mail.search.SubjectTerm;

/**
 * [conn/mail] {@link ImapOps} 的 JavaMail 实现（vendor/mail 里的 android-mail 1.6.5）。
 * 凭据沿用现有「邮箱（IMAP）配置」（bixby_mail_cfg，授权码 Keystore 加密），SMTP 服务器按服务商预设推导（{@link Config#smtpFor}）。
 * 证书与主机名<b>永远严格校验</b>（与 MailBridge P0-7 一致），超时 10 s / 15 s。日志不打任何主题 / 地址 / 正文。
 */
public final class JavaMailImapOps implements ImapOps {

    /** 连接配置（不可变；pass 只在内存）。 */
    public static final class Config {
        public final String imapHost;
        public final int imapPort;
        public final String smtpHost;
        public final int smtpPort;
        /** true = SMTPS（465 直连 TLS）；false = 587 STARTTLS（强制）。 */
        public final boolean smtpSsl;
        public final String user;
        final String pass;
        public final String socksHost;
        public final int socksPort;

        public Config(String imapHost, int imapPort, String smtpHost, int smtpPort, boolean smtpSsl, String user, String pass,
                      String socksHost, int socksPort) {
            this.imapHost = imapHost;
            this.imapPort = imapPort;
            this.smtpHost = smtpHost;
            this.smtpPort = smtpPort;
            this.smtpSsl = smtpSsl;
            this.user = user;
            this.pass = pass;
            this.socksHost = socksHost;
            this.socksPort = socksPort;
        }

        /** 由 IMAP 服务器推 SMTP：qq/163/126/gmail/exmail/icloud → smtp.* 465；outlook/office365 → smtp.office365.com 587。 */
        public static String[] smtpFor(String imapHost) {
            String h = imapHost == null ? "" : imapHost.trim().toLowerCase(java.util.Locale.ROOT);
            if (h.contains("outlook") || h.contains("office365") || h.contains("hotmail")) return new String[]{"smtp.office365.com", "587", "starttls"};
            if (h.equals("imap.mail.me.com")) return new String[]{"smtp.mail.me.com", "587", "starttls"};
            if (h.startsWith("imap.")) return new String[]{"smtp." + h.substring(5), "465", "ssl"};
            if (h.startsWith("imap-")) return new String[]{"smtp-" + h.substring(5), "465", "ssl"};
            return new String[]{h, "465", "ssl"};
        }
    }

    private final Config mC;

    public JavaMailImapOps(Config c) {
        mC = c;
    }

    Properties imapProps() {
        Properties p = new Properties();
        p.put("mail.store.protocol", "imaps");
        p.put("mail.imaps.host", mC.imapHost);
        p.put("mail.imaps.port", String.valueOf(mC.imapPort));
        p.put("mail.imaps.ssl.enable", "true");
        p.put("mail.imaps.ssl.checkserveridentity", "true");
        p.put("mail.imaps.connectiontimeout", "10000");
        p.put("mail.imaps.timeout", "15000");
        p.put("mail.mime.multipart.ignoremissingboundaryparameter", "true");
        p.put("mail.mime.multipart.ignoreexistingboundaryparameter", "true");
        p.put("mail.mime.multipart.ignoremissingendboundary", "true");
        if (mC.socksHost != null && mC.socksHost.length() > 0) {
            p.put("mail.imaps.socks.host", mC.socksHost);
            p.put("mail.imaps.socks.port", String.valueOf(mC.socksPort));
        }
        return p;
    }

    Properties smtpProps() {
        Properties p = new Properties();
        String proto = mC.smtpSsl ? "smtps" : "smtp";
        p.put("mail.transport.protocol", proto);
        p.put("mail." + proto + ".host", mC.smtpHost);
        p.put("mail." + proto + ".port", String.valueOf(mC.smtpPort));
        p.put("mail." + proto + ".auth", "true");
        p.put("mail." + proto + ".connectiontimeout", "10000");
        p.put("mail." + proto + ".timeout", "15000");
        p.put("mail." + proto + ".writetimeout", "15000");
        p.put("mail." + proto + ".ssl.checkserveridentity", "true");
        if (mC.smtpSsl) {
            p.put("mail.smtps.ssl.enable", "true");
        } else {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");   // 不给明文降级
        }
        if (mC.socksHost != null && mC.socksHost.length() > 0) {
            p.put("mail." + proto + ".socks.host", mC.socksHost);
            p.put("mail." + proto + ".socks.port", String.valueOf(mC.socksPort));
        }
        return p;
    }

    private Store open() throws Exception {
        Session s = Session.getInstance(imapProps(), null);
        Store st = s.getStore("imaps");
        st.connect(mC.imapHost, mC.imapPort, mC.user, mC.pass);
        return st;
    }

    private static void close(Folder f, Store s) {
        try { if (f != null && f.isOpen()) f.close(false); } catch (Throwable ignored) { }
        try { if (s != null && s.isConnected()) s.close(); } catch (Throwable ignored) { }
    }

    static Folder drafts(Store st) throws Exception {
        for (String n : new String[]{"Drafts", "Draft", "草稿箱", "草稿", "[Gmail]/Drafts", "[Gmail]/草稿"}) {
            try {
                Folder f = st.getFolder(n);
                if (f != null && f.exists()) return f;
            } catch (Throwable ignored) {
            }
        }
        throw new IllegalStateException("这个邮箱里找不到草稿箱");
    }

    @Override
    public List<MailSummary> unread(int scanMax) throws Exception {
        Store st = null;
        Folder inbox = null;
        try {
            st = open();
            inbox = st.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            Message[] ms = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            List<MailSummary> out = new ArrayList<MailSummary>();
            for (int i = ms.length - 1; i >= 0 && out.size() < scanMax; i--) out.add(summary(ms[i], out.size() < 10));
            return out;
        } finally {
            close(inbox, st);
        }
    }

    @Override
    public List<MailSummary> search(String q, int max) throws Exception {
        Store st = null;
        Folder inbox = null;
        try {
            st = open();
            inbox = st.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            Message[] ms;
            try {
                ms = inbox.search(new OrTerm(new SearchTerm[]{new SubjectTerm(q), new FromStringTerm(q), new BodyTerm(q)}));
            } catch (Throwable serverSearchFailed) {
                // 部分服务器不支持 UTF-8 SEARCH：退回只在最近 200 封的主题 / 发件人里本地匹配
                int n = inbox.getMessageCount();
                Message[] recent = inbox.getMessages(Math.max(1, n - 199), Math.max(1, n));
                List<Message> hit = new ArrayList<Message>();
                for (Message m : recent) {
                    String s = String.valueOf(m.getSubject()) + " " + addr(m.getFrom());
                    if (s.contains(q)) hit.add(m);
                }
                ms = hit.toArray(new Message[0]);
            }
            List<MailSummary> out = new ArrayList<MailSummary>();
            for (int i = ms.length - 1; i >= 0 && out.size() < max; i--) out.add(summary(ms[i], true));
            return out;
        } finally {
            close(inbox, st);
        }
    }

    static MailSummary summary(Message m, boolean withSnippet) throws Exception {
        boolean flagged = m.isSet(Flags.Flag.FLAGGED);
        boolean high = false;
        String[] pr = m.getHeader("X-Priority");
        if (pr != null && pr.length > 0 && (pr[0].trim().startsWith("1") || pr[0].trim().startsWith("2"))) high = true;
        String[] imp = m.getHeader("Importance");
        if (imp != null && imp.length > 0 && imp[0].trim().equalsIgnoreCase("high")) high = true;
        Date d = m.getSentDate() != null ? m.getSentDate() : m.getReceivedDate();
        String date = d == null ? "" : new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(d);
        String id = m instanceof MimeMessage ? String.valueOf(((MimeMessage) m).getMessageID()) : String.valueOf(m.getMessageNumber());
        return new MailSummary(id, addr(m.getFrom()), m.getSubject(), date, withSnippet ? snippet(m) : "",
                !m.isSet(Flags.Flag.SEEN), flagged || high);
    }

    static String addr(Address[] a) {
        if (a == null || a.length == 0) return "";
        if (a[0] instanceof InternetAddress) {
            InternetAddress ia = (InternetAddress) a[0];
            return ia.getPersonal() != null && ia.getPersonal().length() > 0 ? ia.getPersonal() + " <" + ia.getAddress() + ">" : ia.getAddress();
        }
        return a[0].toString();
    }

    /** 正文前 160 字：text/plain 优先，HTML 去标签。读失败给空串（不影响列表）。 */
    static String snippet(Part p) {
        try {
            String t = text(p, 0);
            return t == null ? "" : t.replaceAll("\\s+", " ").trim();
        } catch (Throwable e) {
            return "";
        }
    }

    private static String text(Part p, int depth) throws Exception {
        if (depth > 5) return null;
        if (p.isMimeType("text/plain")) return String.valueOf(p.getContent());
        if (p.isMimeType("text/html")) return String.valueOf(p.getContent()).replaceAll("(?s)<style.*?</style>|<[^>]+>", " ").replace("&nbsp;", " ");
        if (p.isMimeType("multipart/*")) {
            Object c = p.getContent();
            if (!(c instanceof Multipart)) return null;
            Multipart mp = (Multipart) c;
            String html = null;
            for (int i = 0; i < mp.getCount(); i++) {
                Part bp = mp.getBodyPart(i);
                if (bp.isMimeType("text/plain")) return String.valueOf(bp.getContent());
                String sub = text(bp, depth + 1);
                if (sub != null && html == null) html = sub;
            }
            return html;
        }
        return null;
    }

    MimeMessage toMime(Session s, MailDraft d, boolean withMarkers, String key, int rev) throws Exception {
        MimeMessage m = new MimeMessage(s);
        m.setFrom(new InternetAddress(mC.user));
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(d.to, true));
        if (d.cc.length() > 0) m.setRecipients(Message.RecipientType.CC, InternetAddress.parse(d.cc, true));
        m.setSubject(d.subject, "UTF-8");
        m.setText(d.body, "UTF-8");
        m.setSentDate(new Date());
        if (withMarkers) {
            m.setHeader(H_KEY, key);
            m.setHeader(H_REV, String.valueOf(rev));
        }
        return m;
    }

    @Override
    public void appendDraft(MailDraft d, String key, int rev) throws Exception {
        Store st = null;
        Folder f = null;
        try {
            st = open();
            f = drafts(st);
            f.open(Folder.READ_WRITE);
            MimeMessage m = toMime(Session.getInstance(imapProps(), null), d, true, key, rev);
            m.setFlag(Flags.Flag.DRAFT, true);
            m.setFlag(Flags.Flag.SEEN, true);
            m.saveChanges();
            f.appendMessages(new Message[]{m});
        } finally {
            close(f, st);
        }
    }

    /** 在已打开的草稿箱里按头找（服务器 SEARCH HEADER，失败退回最近 100 封本地扫）。 */
    static Message find(Folder f, String key, int rev) throws Exception {
        Message[] ms;
        try {
            ms = f.search(new AndTerm(new HeaderTerm(H_KEY, key), new HeaderTerm(H_REV, String.valueOf(rev))));
        } catch (Throwable e) {
            int n = f.getMessageCount();
            ms = n == 0 ? new Message[0] : f.getMessages(Math.max(1, n - 99), n);
        }
        for (Message m : ms) {
            if (m.isSet(Flags.Flag.DELETED)) continue;
            String[] k = m.getHeader(H_KEY), r = m.getHeader(H_REV);
            if (k != null && k.length > 0 && key.equals(k[0].trim()) && r != null && r.length > 0 && String.valueOf(rev).equals(r[0].trim())) return m;
        }
        return null;
    }

    @Override
    public MailDraft readDraft(String key, int rev) throws Exception {
        Store st = null;
        Folder f = null;
        try {
            st = open();
            f = drafts(st);
            f.open(Folder.READ_ONLY);
            Message m = find(f, key, rev);
            if (m == null) return null;
            String body = text(m, 0);
            return new MailDraft(join(m.getRecipients(Message.RecipientType.TO)), join(m.getRecipients(Message.RecipientType.CC)),
                    m.getSubject(), body);
        } finally {
            close(f, st);
        }
    }

    static String join(Address[] a) {
        if (a == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Address x : a) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(x instanceof InternetAddress ? ((InternetAddress) x).getAddress() : x.toString());
        }
        return sb.toString();
    }

    @Override
    public void smtpSend(MailDraft d) throws Exception {
        Session s = Session.getInstance(smtpProps(), null);
        MimeMessage m = toMime(s, d, false, null, 0);
        m.saveChanges();
        Transport t = s.getTransport(mC.smtpSsl ? "smtps" : "smtp");
        try {
            t.connect(mC.smtpHost, mC.smtpPort, mC.user, mC.pass);
            t.sendMessage(m, m.getAllRecipients());
        } finally {
            try { t.close(); } catch (Throwable ignored) { }
        }
    }

    @Override
    public boolean deleteDraft(String key, int rev) throws Exception {
        Store st = null;
        Folder f = null;
        try {
            st = open();
            f = drafts(st);
            f.open(Folder.READ_WRITE);
            Message m = find(f, key, rev);
            if (m == null) return false;
            m.setFlag(Flags.Flag.DELETED, true);
            f.close(true);   // expunge
            return true;
        } finally {
            close(f, st);
        }
    }

    @Override
    public boolean markDraftSent(String key, int rev) throws Exception {
        Store st = null;
        Folder f = null;
        try {
            st = open();
            f = drafts(st);
            f.open(Folder.READ_WRITE);
            Message m = find(f, key, rev);
            if (m == null) return false;
            m.setFlag(Flags.Flag.SEEN, true);
            try {
                m.setFlags(new Flags("BixbySent"), true);
            } catch (Throwable ignored) {
                // 服务器不接受自定义关键字：只留已读
            }
            return true;
        } finally {
            close(f, st);
        }
    }
}
