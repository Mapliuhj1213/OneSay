package com.onesay.assistant.v2.summary;

import com.onesay.assistant.v2.s1.ClipChipPlanner;
import com.onesay.assistant.v2.s2.SensitiveGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * [summary] 隐私闸门（纯 Java）。<b>复用既有口径</b>，不另起一套：
 * <ul>
 *   <li>识屏 {@code UiDumpParser}：{@code password="true"} 或 id 像密码/PIN/CVV 的节点<b>整节点丢弃</b>，卡号/身份证号打码成 {@code ****1234}，
 *       两者都计入 {@code redacted}。丢弃数 = redacted − 打码行数 &gt; 0 ⇒ 屏幕上有密码类输入框 ⇒ 不摘要；</li>
 *   <li>S2 {@link SensitiveGuard}（密码 / 验证码 / 卡号 / 证件号）与 S1 {@link ClipChipPlanner#sensitiveReason}：
 *       用来判「验证码短信 / 验证码输入页 / 密码输入页」这类<b>短行</b>，以及给模型输出做最后一道擦除。</li>
 * </ul>
 * 一篇讲「如何设置强密码」的文章不会被拦：只有短的界面行（≤{@link #SHORT_LINE} 字）里出现「输入/获取/忘记 + 密码/验证码」，
 * 或「验证码」与 4–8 位数字同行，才算敏感界面。
 */
public final class SummaryGuard {

    private SummaryGuard() {}

    static final int SHORT_LINE = 24;
    private static final Pattern MASKED = Pattern.compile("\\*{4}\\d{2,4}");
    private static final Pattern CODE_WORD = Pattern.compile("验证码|校验码|动态码|动态密码|短信码|确认码|安全码|授权码");
    private static final Pattern CODE_DIGITS = Pattern.compile("(?<![0-9])\\d{4,8}(?![0-9])");
    private static final Pattern PROMPT = Pattern.compile(
            "(?:请?输入|填写|获取|发送|重新发送|重发|忘记|设置|确认|修改)(?:你的|您的|新的?|登录|支付|交易|手机)?(?:密码|验证码|支付密码|交易密码|登录密码|校验码|pin码?)"
            + "|^(?:登录密码|支付密码|交易密码|密码|验证码|短信验证码|手机验证码|pin码?)$", Pattern.CASE_INSENSITIVE);

    /**
     * @param texts    屏幕文字行（已打码）
     * @param redacted UiDumpParser 的隐私计数（丢弃节点 + 打码节点）
     * @return 给用户看的原因短语；可以摘要返回 null
     */
    public static String screenReason(List<String> texts, int redacted) {
        int masked = 0;
        if (texts != null) {
            for (String t : texts) {
                if (t != null && MASKED.matcher(t).find()) masked++;
            }
        }
        if (redacted - masked > 0) return "密码输入框";
        if (texts == null) return null;
        for (String raw : texts) {
            if (raw == null) continue;
            String t = raw.trim();
            if (t.length() == 0) continue;
            if (CODE_WORD.matcher(t).find() && CODE_DIGITS.matcher(t).find()) return "验证码";
            if (t.length() <= SHORT_LINE && PROMPT.matcher(t.replaceAll("\\s+", "")).find()) {
                return t.contains("验证") || t.contains("校验") ? "验证码输入" : "密码输入";
            }
        }
        return null;
    }

    /** 模型输出的一行是否要擦掉（模型偶尔会把验证码 / 卡号抄进要点）。 */
    public static boolean outputLineSensitive(String line) {
        if (line == null) return false;
        String t = line.trim();
        if (t.length() == 0) return false;
        if (CODE_WORD.matcher(t).find() && CODE_DIGITS.matcher(t).find()) return true;
        String r = SensitiveGuard.reason(t);
        if (r != null && (r.contains("卡号") || r.contains("证件") || r.contains("身份证"))) return true;
        // 剪贴板口径：长串数字（Luhn 卡号 / 身份证）
        String c = ClipChipPlanner.sensitiveReason(t);
        return c != null && (c.startsWith("银行卡") || c.contains("身份证"));
    }

    /** 过滤模型输出的若干行，返回保留的行；被擦掉的条数写进 removed[0]。 */
    public static List<String> scrub(List<String> lines, int[] removed) {
        List<String> out = new ArrayList<String>();
        int n = 0;
        if (lines != null) {
            for (String l : lines) {
                if (outputLineSensitive(l)) n++;
                else out.add(l);
            }
        }
        if (removed != null && removed.length > 0) removed[0] = n;
        return out;
    }
}
