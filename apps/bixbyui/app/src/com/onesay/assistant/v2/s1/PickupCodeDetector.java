package com.onesay.assistant.v2.s1;

import com.onesay.assistant.v2.vision.screenref.EntityExtractor;
import com.onesay.assistant.v2.vision.screenref.ScreenEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [s1] #10 从短信正文里识别<b>取件码 / 取餐码</b>（纯 Java，JVM 可测）。复用识屏的 {@link EntityExtractor}（CODE 实体）。
 *
 * <h3>与验证码（OTP）的区分 —— 验证码绝不出浮卡</h3>
 * <ol>
 *   <li>只收 {@code subLabel} 为「取件码 / 取餐码」的 CODE 实体；EntityExtractor 把「验证码/校验码/动态码」标为「验证码」，
 *       「兑换码/券码/核销码」标为「券码」，这些一律不收；</li>
 *   <li>整条短信带验证码特征（验证码/校验码/动态码/动态密码/OTP/登录/注册/支付/转账/银行/密码/请勿泄露…）
 *       而<b>没有</b>取件语境（取件/驿站/丰巢/快递柜/包裹/取餐/外卖…）→ 整条丢弃；</li>
 *   <li>同时有两种语境（「取件码 1234，验证码 5678」）→ 只收取件码那一个。</li>
 * </ol>
 * 驿站：优先取短信里的「XX驿站/丰巢柜/快递柜/自提点/取餐台…」名称，其次取地址实体，再次取签名【…】。
 */
public final class PickupCodeDetector {

    private PickupCodeDetector() {}

    /** 一条识别结果。 */
    public static final class Pickup {
        public final String code;
        /** 「取件码」或「取餐码」。 */
        public final String kind;
        /** 驿站 / 门店 / 地址（可为 null）。 */
        public final String station;

        public Pickup(String code, String kind, String station) {
            this.code = code;
            this.kind = kind;
            this.station = station;
        }

        /** 去重键：类型 + 码。 */
        public String key() {
            return kind + "|" + code;
        }

        @Override
        public String toString() {
            return kind + "(" + code + (station != null ? " @" + station : "") + ")";
        }
    }

    static final Pattern OTP_CONTEXT = Pattern.compile(
            "验证码|校验码|动态码|动态密码|安全码|短信码|OTP|otp|verification|登录|登陆|注册|支付|付款|转账|银行|密码|请勿泄露|切勿告知|勿告诉他人|不要告诉");
    static final Pattern PICKUP_CONTEXT = Pattern.compile(
            "取件|取货|提货|开箱|驿站|丰巢|快递柜|自提|代收点|包裹|快递|菜鸟|兔喜|妈妈驿站|取餐|外卖|餐品|出餐|叫号|凭码");
    private static final Pattern STATION = Pattern.compile(
            "([\\u4e00-\\u9fa5A-Za-z0-9（）()·\\-]{2,24}?(?:驿站|丰巢柜|丰巢智能柜|丰巢|快递柜|智能柜|自提点|代收点|服务站|取餐台|取餐柜|出餐口|门店))");
    private static final Pattern SIGNATURE = Pattern.compile("^\\s*[【\\[]([^】\\]]{2,16})[】\\]]|[【\\[]([^】\\]]{2,16})[】\\]]\\s*$");
    private static final Pattern STATION_STRIP = Pattern.compile("^(?:您的|你的|包裹|快递|已到|已到达|到达|放至|放在|存放于|存放在|已放入|已存入|请到|请至|到|至|在|于)+");

    /** 一条短信 → 取件/取餐码列表（去重，保持出现顺序）。null/空 → 空列表。 */
    public static List<Pickup> detect(String body, long nowMs, TimeZone tz) {
        if (body == null) return Collections.emptyList();
        String s = body.trim();
        if (s.length() == 0 || s.length() > 1000) return Collections.emptyList();
        boolean pickupCtx = PICKUP_CONTEXT.matcher(s).find();
        boolean otpCtx = OTP_CONTEXT.matcher(s).find();
        if (otpCtx && !pickupCtx) return Collections.emptyList();

        List<String> lines = new ArrayList<String>();
        lines.add(s);
        List<ScreenEntity> ents = EntityExtractor.extractFromTexts(lines, nowMs, tz);
        String station = stationOf(s, ents);
        List<Pickup> out = new ArrayList<Pickup>();
        Set<String> seen = new LinkedHashSet<String>();
        for (ScreenEntity e : ents) {
            if (e.type != ScreenEntity.Type.CODE) continue;
            String kind = e.subLabel;
            if (!"取件码".equals(kind) && !"取餐码".equals(kind)) continue;   // 验证码 / 券码 / 入园码 不出卡
            if (e.value == null || e.value.length() < 3) continue;
            Pickup p = new Pickup(e.value, kind, station);
            if (seen.add(p.key())) out.add(p);
        }
        return out;
    }

    /** 是否「看起来是验证码短信」（单测与日志用）。 */
    public static boolean looksLikeOtp(String body) {
        if (body == null) return false;
        return OTP_CONTEXT.matcher(body).find() && !PICKUP_CONTEXT.matcher(body).find();
    }

    static String stationOf(String s, List<ScreenEntity> ents) {
        Matcher m = STATION.matcher(s);
        while (m.find()) {
            String st = STATION_STRIP.matcher(m.group(1)).replaceAll("").trim();
            if (st.length() >= 2 && !st.matches("^(?:驿站|丰巢|快递柜|门店)$")) return cut(st);
        }
        for (ScreenEntity e : ents) {
            if (e.type == ScreenEntity.Type.ADDRESS && e.value != null) return cut(e.value);
        }
        Matcher g = SIGNATURE.matcher(s);
        if (g.find()) {
            String sig = g.group(1) != null ? g.group(1) : g.group(2);
            if (sig != null) return cut(sig.trim());
        }
        return null;
    }

    private static String cut(String v) {
        return v.length() > 24 ? v.substring(0, 23) + "…" : v;
    }
}
