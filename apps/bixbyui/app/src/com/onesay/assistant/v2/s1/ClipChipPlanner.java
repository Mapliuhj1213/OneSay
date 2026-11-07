package com.onesay.assistant.v2.s1;

import com.onesay.assistant.v2.vision.screenref.EntityExtractor;
import com.onesay.assistant.v2.vision.screenref.ScreenEntity;
import com.onesay.assistant.v2.vision.screenref.UiDumpParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * [s1] 剪贴板地址芯片：「新复制的文字 → 要不要弹芯片、弹哪几个」的裁决（纯 Java，JVM 可测）。
 *
 * <h3>忽略（不弹、不记）</h3>
 * <ol>
 *   <li>空 / 只有空白；超长（&gt; {@link #MAX_LEN} 字，多半是整篇文章）；</li>
 *   <li><b>本 App 自己写的</b>（识屏复制、取件码浮卡复制）—— 防回环；</li>
 *   <li><b>重复</b>：与上一次处理过的内容相同且在 {@link #DUP_WINDOW_MS} 内（系统对一次复制有时回调两次）；</li>
 *   <li><b>敏感</b>（{@link #sensitiveReason}）：银行卡 / 身份证（复用识屏的 Luhn + 身份证打码规则）、验证码、
 *       密码类关键词、形似密码的无空格混合串、API 密钥（sk-… / AKIA…）、纯 4–8 位数字（多半是验证码）；</li>
 *   <li>抽不出可操作的实体（地址 / 电话 / 链接 / 快递单号）。</li>
 * </ol>
 * <h3>芯片</h3>
 * 地址 →「导航」「打车」；电话 →「拨打」；链接 →「打开」；快递单号 →「查快递」。最多 {@link #MAX_CHIPS} 个，地址优先。
 * 芯片只是<b>建议</b>：点了之后走与识屏芯片相同的 ExecutionGate → 确认卡 → 执行器链路（拨打照常弹黄卡）。
 */
public final class ClipChipPlanner {

    private ClipChipPlanner() {}

    public static final int MAX_LEN = 300;
    public static final long DUP_WINDOW_MS = 60000L;
    public static final int MAX_CHIPS = 3;

    public enum ChipKind {
        NAV("导航"), TAXI("打车"), CALL("拨打"), OPEN_URL("打开"), TRACK("查快递");

        public final String label;

        ChipKind(String label) {
            this.label = label;
        }
    }

    public static final class Chip {
        public final ChipKind kind;
        public final ScreenEntity entity;

        Chip(ChipKind kind, ScreenEntity entity) {
            this.kind = kind;
            this.entity = entity;
        }

        public String label() {
            return kind.label;
        }

        @Override
        public String toString() {
            return kind.label + "(" + entity.value + ")";
        }
    }

    public static final class Decision {
        public final boolean show;
        /** 忽略原因（show=false 时）；show=true 时为 null。 */
        public final String reason;
        public final List<Chip> chips;
        /** 抽到的可操作实体（只有通过敏感过滤的才会给出，供 {@link ClipboardMemory#remember}）。 */
        public final List<ScreenEntity> entities;
        /** 芯片卡片上的标题，例如「复制了地址：深圳市南山区…」。 */
        public final String title;

        Decision(boolean show, String reason, List<Chip> chips, List<ScreenEntity> entities, String title) {
            this.show = show;
            this.reason = reason;
            this.chips = chips == null ? Collections.<Chip>emptyList() : chips;
            this.entities = entities == null ? Collections.<ScreenEntity>emptyList() : entities;
            this.title = title;
        }

        static Decision ignore(String reason) {
            return new Decision(false, reason, null, null, null);
        }

        @Override
        public String toString() {
            return show ? "SHOW " + chips : "IGNORE(" + reason + ")";
        }
    }

    private static final Pattern OTP_WORDS = Pattern.compile(
            "(?i)验证码|校验码|动态码|动态密码|短信码|安全码|一次性密码|otp|verification\\s*code|passcode");
    private static final Pattern SECRET_WORDS = Pattern.compile(
            "(?i)密码|口令|支付码|付款码|私钥|助记词|cvv|cvc|pin码|password|passwd|pwd|secret|token");
    private static final Pattern API_KEY = Pattern.compile(
            "(?<![A-Za-z0-9])(?:sk-[A-Za-z0-9_\\-]{16,}|AKIA[0-9A-Z]{12,}|LTAI[A-Za-z0-9]{12,}|ghp_[A-Za-z0-9]{20,}|xox[bp]-[A-Za-z0-9\\-]{10,})");
    private static final Pattern PURE_SHORT_DIGITS = Pattern.compile("^\\d{4,8}$");
    private static final Pattern URLISH = Pattern.compile("(?i)^(?:https?://|www\\.)|^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fa5]");

    /** 敏感原因；不敏感返回 null。 */
    public static String sensitiveReason(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.length() == 0) return null;
        String masked = UiDumpParser.maskSecrets(t);
        if (masked != null && !masked.equals(t)) return "银行卡/身份证号";
        if (OTP_WORDS.matcher(t).find()) return "验证码";
        if (API_KEY.matcher(t).find()) return "密钥";
        if (SECRET_WORDS.matcher(t).find()) return "密码类内容";
        if (PURE_SHORT_DIGITS.matcher(t).matches()) return "疑似验证码（纯数字）";
        if (looksLikePassword(t)) return "疑似密码";
        return null;
    }

    /** 无空格、无中文、8–64 位，且含大写/小写/数字/符号中至少 3 类（网址、邮箱除外）→ 形似密码。 */
    static boolean looksLikePassword(String t) {
        if (t.length() < 8 || t.length() > 64) return false;
        if (t.indexOf(' ') >= 0 || t.indexOf('\n') >= 0 || CJK.matcher(t).find()) return false;
        if (URLISH.matcher(t).find()) return false;
        boolean lo = false, up = false, dg = false, sy = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= 'a' && c <= 'z') lo = true;
            else if (c >= 'A' && c <= 'Z') up = true;
            else if (c >= '0' && c <= '9') dg = true;
            else sy = true;
        }
        int classes = (lo ? 1 : 0) + (up ? 1 : 0) + (dg ? 1 : 0) + (sy ? 1 : 0);
        return classes >= 3;
    }

    /**
     * 主入口。
     *
     * @param text      新的剪贴板文字
     * @param ownWrite  是否本 App 刚写的（{@link ClipboardMemory#consumeOwnWrite}）
     * @param lastText  上一次<b>处理过</b>的剪贴板文字（可为 null）
     * @param lastAt    上一次处理的时刻
     */
    public static Decision plan(String text, boolean ownWrite, String lastText, long lastAt, long now, TimeZone tz) {
        if (text == null || text.trim().length() == 0) return Decision.ignore("空内容");
        String t = text.trim();
        if (ownWrite) return Decision.ignore("本 App 写入");
        if (lastText != null && lastText.trim().equals(t) && now - lastAt >= 0 && now - lastAt <= DUP_WINDOW_MS) {
            return Decision.ignore("重复复制");
        }
        if (t.length() > MAX_LEN) return Decision.ignore("内容过长");
        String sens = sensitiveReason(t);
        if (sens != null) return Decision.ignore("敏感：" + sens);

        List<String> lines = new ArrayList<String>();
        for (String l : t.split("[\\r\\n]+")) if (l.trim().length() > 0) lines.add(l.trim());
        List<ScreenEntity> all = EntityExtractor.extractFromTexts(lines, now, tz);
        List<ScreenEntity> useful = new ArrayList<ScreenEntity>();
        for (ScreenEntity e : all) {
            if (e.confidence < 0.5) continue;
            if (e.type == ScreenEntity.Type.ADDRESS || e.type == ScreenEntity.Type.PHONE
                    || e.type == ScreenEntity.Type.URL || e.type == ScreenEntity.Type.TRACKING) {
                useful.add(e);
            }
        }
        if (useful.isEmpty()) return Decision.ignore("无可操作实体");

        List<Chip> chips = new ArrayList<Chip>();
        ScreenEntity addr = first(useful, ScreenEntity.Type.ADDRESS);
        ScreenEntity phone = first(useful, ScreenEntity.Type.PHONE);
        ScreenEntity url = first(useful, ScreenEntity.Type.URL);
        ScreenEntity track = first(useful, ScreenEntity.Type.TRACKING);
        if (addr != null) {
            chips.add(new Chip(ChipKind.NAV, addr));
            chips.add(new Chip(ChipKind.TAXI, addr));
        }
        if (phone != null && chips.size() < MAX_CHIPS) chips.add(new Chip(ChipKind.CALL, phone));
        if (track != null && chips.size() < MAX_CHIPS) chips.add(new Chip(ChipKind.TRACK, track));
        if (url != null && chips.size() < MAX_CHIPS) chips.add(new Chip(ChipKind.OPEN_URL, url));

        ScreenEntity head = chips.get(0).entity;
        String title = "复制了" + head.kindLabel() + "：" + head.shortDisplay(18);
        return new Decision(true, null, chips, useful, title);
    }

    private static ScreenEntity first(List<ScreenEntity> list, ScreenEntity.Type t) {
        for (ScreenEntity e : list) if (e.type == t) return e;
        return null;
    }
}
