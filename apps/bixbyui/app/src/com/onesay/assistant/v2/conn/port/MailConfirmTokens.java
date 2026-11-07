package com.onesay.assistant.v2.conn.port;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 确认卡令牌（lane-a 侧，语义对齐 B 线 ExecutionGate 令牌 + {@code MailSendGuard}）。纯 Java，JVM 可测。
 *
 * <ul>
 *   <li>{@link #issue}：确认卡<b>显示某一版草稿时</b>签发，令牌绑定 草稿号 + 内容指纹（同一草稿再签发会作废旧令牌）；</li>
 *   <li>{@link #consume}：卡片「发送」按钮消费令牌 → 得到 {@link MailSendPermit}。令牌只能用一次、10 分钟过期、
 *       草稿号和<b>当前</b>指纹必须与签发时一致（「修改」重新起草后指纹变了，旧卡的令牌自动失效）；</li>
 *   <li>{@link #revoke}：修改 / 取消时作废该草稿的令牌。</li>
 * </ul>
 * 没有别的办法造出 MailSendPermit（构造器包私有，单测扫源码断言只在这里 new）。
 */
public final class MailConfirmTokens {

    private MailConfirmTokens() {}

    public static final long TTL_MS = 10 * 60 * 1000L;

    static final class Ticket {
        final String draftId;
        final String fingerprint;
        final long issuedAt;

        Ticket(String draftId, String fingerprint, long issuedAt) {
            this.draftId = draftId;
            this.fingerprint = fingerprint;
            this.issuedAt = issuedAt;
        }
    }

    private static final Map<String, Ticket> LIVE = new LinkedHashMap<String, Ticket>();

    /** 签发；草稿号 / 指纹为空返回 null。 */
    public static String issue(String draftId, String fingerprint, long now) {
        if (draftId == null || draftId.length() == 0 || fingerprint == null || fingerprint.length() == 0) return null;
        String id = newId();
        synchronized (LIVE) {
            revokeLocked(draftId);
            LIVE.put(id, new Ticket(draftId, fingerprint, now));
            while (LIVE.size() > 64) LIVE.remove(LIVE.keySet().iterator().next());
        }
        return id;
    }

    /**
     * 消费（只在确认卡「发送」按钮的路径上调用）。
     * @param currentFingerprint 现在草稿的指纹（由调用方按当前内容重新计算）
     * @return 许可；拿不到返回 null，原因写进 why
     */
    public static MailSendPermit consume(String tokenId, String draftId, String currentFingerprint, long now, StringBuilder why) {
        Ticket t;
        synchronized (LIVE) {
            t = tokenId == null ? null : LIVE.remove(tokenId);   // 无论成败，令牌都作废
        }
        if (t == null) return deny(why, "没有有效的确认卡令牌：发送邮件必须在确认卡上点「发送」");
        if (now - t.issuedAt > TTL_MS) return deny(why, "确认卡已过时，请重新确认");
        if (!t.draftId.equals(draftId)) return deny(why, "确认卡不是给这封草稿的");
        if (!t.fingerprint.equals(currentFingerprint)) return deny(why, "草稿在确认卡显示之后被改过，需要重新确认");
        return new MailSendPermit(t.draftId, t.fingerprint, tokenId);
    }

    /** 作废这封草稿的全部令牌（修改 / 取消）。 */
    public static void revoke(String draftId) {
        synchronized (LIVE) {
            revokeLocked(draftId);
        }
    }

    private static void revokeLocked(String draftId) {
        Iterator<Map.Entry<String, Ticket>> it = LIVE.entrySet().iterator();
        while (it.hasNext()) if (it.next().getValue().draftId.equals(draftId)) it.remove();
    }

    static int live() {
        synchronized (LIVE) {
            return LIVE.size();
        }
    }

    private static MailSendPermit deny(StringBuilder why, String msg) {
        if (why != null) why.append(msg);
        return null;
    }

    static String newId() {
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
