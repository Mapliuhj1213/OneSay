package com.onesay.assistant.v2.router;

import java.util.regex.Pattern;

/**
 * [exec-ground-1124] 污染（读过屏幕 / 邮件 / 网页等外部内容）跟着哪个任务走（纯 Java，JVM 可测）。
 *
 * <p>真机（2026-11-24 11:59:55）：前一个任务读过一次微信屏幕，之后用户亲口说的新任务「打开美团拼好饭……」也被整段按
 * 「含不可信外部内容」过闸，替你操作弹了红卡。改成：
 * <ul>
 *   <li>这一句是接着上一件事说的（继续 / 刚才 / 这个 / 它 / 照它说的……，或者说不清是不是新事）→ 照旧继承，外部内容仍包在
 *       不可信标签里，整个任务按污染过闸（宁可多问）；</li>
 *   <li>这一句明显是新起的一件事（开头就是「打开 / 启动 / 用 某个 App」之类，且没有回指上文的词）→ 不继承：
 *       之前读到的外部内容<b>不再放进这次的上下文</b>（换成一句「已略去」），所以这次也不按污染算。</li>
 * </ul>
 * 发送 / 付款 / 提交这类敏感动作的确认在替你操作里另有一套（SensitiveGuard / 付款最后一下本人点），与这里无关，一点不放松；
 * 新任务自己读到外部内容，照样当场变成污染。
 */
public final class TaintScope {

    private TaintScope() {}

    /** 回指上文 / 接着做：有这些词就算接续。 */
    static final Pattern REF = Pattern.compile(
            "继续|接着|刚才|刚刚|方才|上面|上一|前面|之前|上次|这个|那个|这些|那些|它|他们|这里|那里|这页|这一页|这个页面|当前(页|界面|屏幕)|屏幕上|页面上"
                    + "|里面(的|那|这)|照着|照它|按它|按照它|按上面|按刚才|结果|然后呢|接下来|下一步|再试|重试|你打开错了|不对|错了|改成|换成");

    /** 明显是新起的一件事：开头就要打开 / 启动 / 用某个 App，或者「帮我 + 打开 / 启动」。 */
    static final Pattern NEW_TASK = Pattern.compile(
            "^(?:请|麻烦|那|好|好的|嗯|行)?[，,\\s]*(?:你|帮我|给我|替我)?(?:去|再)?(?:打开|启动|进入|开一下|用|上)\\s*[\\u4e00-\\u9fa5A-Za-z]{2,}");

    /** 已略去的外部内容占位（放在历史里代替原文）。 */
    public static final String DROPPED = "（这一轮读过屏幕 / 外部内容，原文不带进新的任务）";

    /**
     * 这一句要不要继承之前的污染。
     * @return true = 接续（照旧继承）；false = 新起的任务（不继承、外部原文不进上下文）
     */
    public static boolean inherits(String query) {
        if (query == null) return true;
        String q = query.trim();
        if (q.length() == 0) return true;
        if (REF.matcher(q).find()) return true;
        return !NEW_TASK.matcher(q).find();
    }

    /** 不继承时：把长上下文尾巴里包在不可信标签里的原文去掉（换成占位）。 */
    public static String stripUntrusted(String s) {
        if (s == null || s.indexOf(AgentLoop.UNTRUSTED_OPEN) < 0) return s;
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (true) {
            int a = s.indexOf(AgentLoop.UNTRUSTED_OPEN, i);
            if (a < 0) {
                b.append(s.substring(i));
                break;
            }
            b.append(s, i, a).append(DROPPED);
            int c = s.indexOf(AgentLoop.UNTRUSTED_CLOSE, a + AgentLoop.UNTRUSTED_OPEN.length());
            if (c < 0) break;   // 没闭合：后面全丢
            i = c + AgentLoop.UNTRUSTED_CLOSE.length();
        }
        return b.toString();
    }
}
