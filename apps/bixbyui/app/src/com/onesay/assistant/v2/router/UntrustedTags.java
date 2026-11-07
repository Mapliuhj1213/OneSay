package com.onesay.assistant.v2.router;

import java.util.regex.Pattern;

/**
 * ★ audit-J 6（用户 11-04 19:2x 反馈）：问本地问题时回答显示成「&lt;不可信数据&gt;…&lt;/不可信数据&gt;」——
 * AgentLoop / L2 把带污点的历史用 {@link AgentLoop#UNTRUSTED_OPEN} / {@link AgentLoop#UNTRUSTED_CLOSE} 包起来，模型照抄了标签。
 * 所有面向用户的出口（流式字幕、终稿、TTS、ask.reply、回喂会话的 assistant 文本）统一过这里。纯函数，JVM 可测。
 */
public final class UntrustedTags {

    private UntrustedTags() {}

    /** 完整标签及空白 / 全角括号变体：&lt;不可信数据&gt;、&lt;/不可信数据&gt;、&lt; 不可信数据 &gt;、&lt;不可信数据 /&gt;、＜不可信数据＞。 */
    static final Pattern TAG = Pattern.compile("[<＜]\\s*/?\\s*不\\s*可\\s*信\\s*数\\s*据\\s*/?\\s*[>＞]");
    static final String WORD = "不可信数据";

    public static boolean has(String s) {
        return s != null && TAG.matcher(s).find();
    }

    /** 终稿：去掉全部标签（不留多余空行）。 */
    public static String strip(String s) {
        if (s == null || s.length() == 0) return s;
        if (s.indexOf('<') < 0 && s.indexOf('＜') < 0) return s;
        String t = TAG.matcher(s).replaceAll("");
        if (t.equals(s)) return s;
        return t.replaceAll("^\\s+", "").replaceAll("\\n{3,}", "\n\n").replaceAll("\\s+$", "");
    }

    /**
     * 流式（累计文本）：先去完整标签；结尾若是「可能还没收完的标签前缀」（「&lt;」「&lt;/不可」「&lt;不可信数据 」…）先扣住不出，
     * 下一帧凑齐了再判定 —— 标签被拆在两块里也不会漏出半截。
     */
    public static String stripStreaming(String acc) {
        if (acc == null || acc.length() == 0) return acc;
        String s = strip(acc);
        int lt = Math.max(s.lastIndexOf('<'), s.lastIndexOf('＜'));
        if (lt < 0) return s;
        String tail = s.substring(lt);
        if (tail.indexOf('>') >= 0 || tail.indexOf('＞') >= 0) return s;
        if (tail.length() > 16) return s;   // 太长不可能是标签
        if (couldBeTagPrefix(tail)) return s.substring(0, lt).replaceAll("\\s+$", "");
        return s;
    }

    /** tail 以 &lt; 开头、无 &gt;：去掉空白和斜杠后，剩下的是「不可信数据」的前缀（含空）。 */
    static boolean couldBeTagPrefix(String tail) {
        String core = tail.substring(1).replaceAll("[\\s/]", "");
        return WORD.startsWith(core);
    }
}
