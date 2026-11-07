package com.onesay.assistant.v2.wake;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * OneSay 开源版：用户自定义的本地唤醒词（纯 Java，JVM 可测）。
 *
 * <ul>
 *   <li>本地唤醒词是<b>可选功能，默认关</b>；默认入口是按钮 / 默认数字助理。开源版不内置任何唤醒词。</li>
 *   <li>本版没有「任意中英文 → 音素」的端上转换器，用户每行填一条 token 序列：
 *       中文用带声调拼音的 token（与模型 tokens.txt 一致），英文用 CMU 音素（如 {@code HH EY1 W AH1 N S EY1}）；
 *       可选在行尾写 {@code @名字}。每个 token 都要在模型的 tokens.txt 里，否则给出清楚的错误。</li>
 *   <li>生成的 keywords.txt 不含注释行（sherpa-onnx 的解析器不跳过 # 行，注释会让整个 KWS 初始化失败）。</li>
 * </ul>
 */
public final class WakeWords {
    private WakeWords() {}

    public static final String PREFS = "onesay_wakeword";
    public static final String KEY_ENABLED = "enabled";
    public static final boolean ENABLED_DEFAULT = false;
    public static final String KEY_LINES = "lines";
    public static final int MAX_LINES = 8;

    /** 一行解析结果。 */
    static final class Line {
        final List<String> tokens = new ArrayList<String>();
        String name;
    }

    static List<Line> parse(String text) {
        List<Line> out = new ArrayList<Line>();
        if (text == null) return out;
        for (String raw : text.split("\n")) {
            String t = raw.trim();
            if (t.length() == 0) continue;
            Line l = new Line();
            int at = t.indexOf('@');
            if (at >= 0) {
                l.name = t.substring(at + 1).trim();
                t = t.substring(0, at).trim();
            }
            for (String tok : t.split("\\s+")) if (tok.length() > 0) l.tokens.add(tok);
            out.add(l);
        }
        return out;
    }

    /**
     * 校验用户输入；没问题返回 null，否则返回一句给人看的错误。
     *
     * @param tokens 模型 tokens.txt 里的全部 token；null = 模型还没下载，只做格式检查
     */
    public static String validate(String text, Set<String> tokens) {
        List<Line> ls = parse(text);
        if (ls.isEmpty()) return "没有填写唤醒词";
        if (ls.size() > MAX_LINES) return "最多 " + MAX_LINES + " 条";
        int i = 0;
        for (Line l : ls) {
            i++;
            if (l.tokens.isEmpty()) return "第 " + i + " 行没有 token";
            if (l.name != null && !l.name.matches("[A-Za-z0-9_]{1,32}")) return "第 " + i + " 行 @ 后的名字只能用字母、数字、下划线";
            for (String tok : l.tokens) {
                if (tok.startsWith("#") || tok.startsWith(":")) return "第 " + i + " 行不能写注释或权重（" + tok + "）";
                if (tokens != null && !tokens.contains(tok))
                    return "第 " + i + " 行的「" + tok + "」不在模型的 tokens.txt 里（中文用带声调拼音 token，英文用 CMU 音素）";
            }
        }
        return null;
    }

    /** 生成 keywords.txt 正文（无注释，每行 "tokens @NAME"）。输入须已通过 {@link #validate}。 */
    public static String keywordsFile(String text) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Line l : parse(text)) {
            n++;
            for (int k = 0; k < l.tokens.size(); k++) sb.append(k == 0 ? "" : " ").append(l.tokens.get(k));
            sb.append(" @").append(l.name != null && l.name.length() > 0 ? l.name.toUpperCase(Locale.ROOT) : "WAKE_" + n).append('\n');
        }
        return sb.toString();
    }

    /** 版本戳：词表一变就变（WakeService 据此重新落盘 keywords.txt）。 */
    public static String stamp(String base, String text) {
        return base + "-u" + Integer.toHexString(keywordsFile(text).hashCode());
    }
}
