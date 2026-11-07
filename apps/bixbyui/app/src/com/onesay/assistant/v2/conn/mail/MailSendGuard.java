package com.onesay.assistant.v2.conn.mail;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ExecutionGate;

import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * [conn/mail] 发送闸：把「确认卡令牌的消费回执」换成 {@link SendPermit}。
 *
 * <h3>为什么要在 ExecutionGate 之外再加一道</h3>
 * ExecutionGate 允许旧布尔入口（非严格模式）和「始终允许」放行需确认的操作。发邮件不能有这两条路：
 * 用户的规定是「发邮件不能预先授权，每次执行都单独确认」。所以这里只认<b>令牌被消费</b>留下的回执——
 * 旧布尔、始终允许、无需确认的放行都不会留回执，拿不到回执就拿不到许可。
 *
 * <p>核对项：回执存在、签名 = {@link #SIGN}、参数摘要与本次执行参数一致（参数里含 draft_id 和 content_hash）、
 * 回执新鲜（10 s 内）、令牌 id 没用过。
 */
public final class MailSendGuard {

    private MailSendGuard() {}

    public static final String SIGN = "conn.mail.send_draft";
    static final long MAX_AGE_MS = 10000L;

    private static final Set<String> USED = new LinkedHashSet<String>();

    /** 测试注入时钟。 */
    interface Clock {
        long now();
    }

    static Clock sClock = new Clock() {
        @Override public long now() { return System.currentTimeMillis(); }
    };

    /** @return 许可；拿不到返回 null，原因写进 why。 */
    public static SendPermit fromReceipt(Capability cap, JSONObject args, StringBuilder why) {
        ExecutionGate.ConsumedReceipt r = ExecutionGate.takeConsumedReceipt();
        if (r == null) return deny(why, "没有确认卡令牌：发送邮件每次都必须经用户在确认卡上确认");
        if (cap == null || !SIGN.equals(cap.sign) || !SIGN.equals(r.sign)) return deny(why, "确认卡不是给「发送邮件」的");
        if (args == null || !r.argsDigest.equals(ExecutionGate.argsDigest(args))) return deny(why, "确认之后参数被改过");
        if (sClock.now() - r.at > MAX_AGE_MS) return deny(why, "确认已过时，请重新确认");
        String draftId = args.optString("draft_id", "");
        String hash = args.optString("content_hash", "");
        if (draftId.length() == 0 || hash.length() == 0) return deny(why, "确认参数里缺草稿号或内容指纹");
        synchronized (USED) {
            if (USED.contains(r.tokenId)) return deny(why, "这张确认卡已经用过");
            USED.add(r.tokenId);
            while (USED.size() > 200) USED.remove(USED.iterator().next());
        }
        return new SendPermit(draftId, hash, r.tokenId);
    }

    private static SendPermit deny(StringBuilder why, String msg) {
        if (why != null) why.append(msg);
        return null;
    }
}
