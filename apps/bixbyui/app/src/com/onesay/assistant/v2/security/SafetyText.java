package com.onesay.assistant.v2.security;

import java.text.Normalizer;

/**
 * r2/sec-logic（LOGIC-3 / LOGIC-5 / LOGIC-7c）：安全词表匹配前的<b>强归一化</b>（纯 Java，无 android 依赖）。
 *
 * <p>渗透测试（pentest-20261024 logic §3/§5/§7）证明：靠词表 / 正则识别「下单按钮」「对外发送工具」「红词」的地方，
 * 都能用 繁体 / 全角 / 零宽字符 / 同形字 / 分词空格 / 全大写 绕过。所有这类判定先过本类再匹配：
 * <ol>
 *   <li>NFKC（全角 → 半角、兼容字形 → 标准字形、①→1 …）；</li>
 *   <li>删除零宽 / 格式类字符（U+200B-200F、U+202A-202E、U+2060-2064、U+FEFF、U+00AD、U+180E，以及一切 Character.FORMAT）；</li>
 *   <li>西里尔 / 希腊同形字 → 拉丁（Рау → pay、ѕend → send）；</li>
 *   <li>常见繁体字 → 简体（覆盖支付 / 下单 / 发信 / 系统控制相关用字；不是完整繁简转换，只为安全词表）；</li>
 *   <li>小写。</li>
 * </ol>
 * {@link #squash} 再删掉所有空白和常见分隔标点（「立即 支付」「提交·订单」「buy-now」→ 连写），给中英文词表匹配用。
 */
public final class SafetyText {

    private SafetyText() {}

    /** 繁 → 简（成对：TRAD[i] → SIMP[i]）。只收安全词表会用到的字，宁多勿少。 */
    private static final String TRAD =
            "訂單確認購買鍵結帳賬錢碼寶銀閃現額選這擊點臉紋極號貨際費車應總實雲議協並馬給開啟證優換訊電話郵發復轉報時機關閉戶"
                    + "嗎們個為過還進隱藏設網絡線動態"
                    + "傳遞送達寫刪除儲存廣傳務提醒鐘鬧護顯視圖錄聲聽讀處簽約據稱萬億價貴員單聯絡態"
                    + "闆媽匯繳歸從簡體撥呼叫帶裝載卸級類";
    private static final String SIMP =
            "订单确认购买键结账账钱码宝银闪现额选这击点脸纹极号货际费车应总实云议协并马给开启证优换讯电话邮发复转报时机关闭户"
                    + "吗们个为过还进隐藏设网络线动态"
                    + "传递送达写删除储存广传务提醒钟闹护显视图录声听读处签约据称万亿价贵员单联络态"
                    + "板妈汇缴归从简体拨呼叫带装载卸级类";

    /** 同形字（西里尔 / 希腊）→ 拉丁。 */
    private static final String HOMO =
            "аеорсухѕіјԁӏһԛԝАВЕКМНОРСТХУЅІЈԜαοιρνκτχυεΑΒΕΖΗΙΚΜΝΟΡΤΥΧ";
    private static final String LATN =
            "aeopcyxsijdlhqwABEKMHOPCTXYSIJWaoipvktxueABEZHIKMNOPTYX";

    static {
        if (TRAD.length() != SIMP.length() || HOMO.length() != LATN.length()) {
            throw new IllegalStateException("SafetyText 映射表长度不一致");
        }
    }

    /** 零宽 / 双向控制 / 软连字符等「看不见但打断匹配」的字符。 */
    public static boolean isInvisible(char c) {
        if (c >= '\u200B' && c <= '\u200F') return true;
        if (c >= '\u202A' && c <= '\u202E') return true;
        if (c >= '\u2060' && c <= '\u2064') return true;
        if (c == '\uFEFF' || c == '\u00AD' || c == '\u180E' || c == '\u034F') return true;
        return Character.getType(c) == Character.FORMAT;
    }

    /** NFKC + 去零宽 + 同形字 → 拉丁 + 繁 → 简 + 小写（保留空白）。null → ""。 */
    public static String fold(String s) {
        if (s == null || s.length() == 0) return "";
        String n;
        try {
            n = Normalizer.normalize(s, Normalizer.Form.NFKC);
        } catch (Throwable t) {
            n = s;
        }
        StringBuilder b = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (isInvisible(c)) continue;
            int h = HOMO.indexOf(c);
            if (h >= 0) c = LATN.charAt(h);
            int t = TRAD.indexOf(c);
            if (t >= 0) c = SIMP.charAt(t);
            b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    /** {@link #fold} 后再删掉所有空白与常见分隔标点：「立即 支付」「提交·订单」「Buy-Now」「SEND_EMAIL」→ 连写小写。 */
    public static String squash(String s) {
        String f = fold(s);
        StringBuilder b = new StringBuilder(f.length());
        for (int i = 0; i < f.length(); i++) {
            char c = f.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) continue;
            if ("-_·•・.,，、|/\\*~'\"`:：;；!！?？()（）[]【】<>《》".indexOf(c) >= 0) continue;
            b.append(c);
        }
        return b.toString();
    }

    /**
     * 拆词：fold 后按「非字母数字」和小写→大写边界切开（全大写 SEND_EMAIL 不再被拆成 s_e_n_d…），返回以空格分隔、首尾带空格的小写词串，
     * 如 {@code "SEND_EMAIL"} → {@code " send email "}、{@code "postMessage"} → {@code " post message "}。
     */
    public static String words(String s) {
        if (s == null) return " ";
        String n;
        try {
            n = Normalizer.normalize(s, Normalizer.Form.NFKC);
        } catch (Throwable t) {
            n = s;
        }
        StringBuilder b = new StringBuilder(" ");
        char prev = 0;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (isInvisible(c)) continue;
            int h = HOMO.indexOf(c);
            if (h >= 0) c = LATN.charAt(h);
            if (!Character.isLetterOrDigit(c)) {
                if (b.charAt(b.length() - 1) != ' ') b.append(' ');
                prev = 0;
                continue;
            }
            // camelCase 边界：小写/数字 后接 大写；以及 "HTTPServer" 这类 大写串 + 大写小写 边界
            if (Character.isUpperCase(c) && prev != 0 && (Character.isLowerCase(prev) || Character.isDigit(prev))) b.append(' ');
            else if (Character.isUpperCase(c) && prev != 0 && Character.isUpperCase(prev) && i + 1 < n.length()
                    && Character.isLowerCase(n.charAt(i + 1))) b.append(' ');
            b.append(Character.toLowerCase(c));
            prev = c;
        }
        if (b.charAt(b.length() - 1) != ' ') b.append(' ');
        return b.toString();
    }
}
