package com.onesay.assistant.v2.notice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * · 外卖 / 打车进度（可选，纯 Java，JVM 可测）：解析美团、饿了么、滴滴的常驻 / 进度通知，抠出「阶段 + 预计时间」。
 *
 * <p>没有官方接口（原子岛 / 焦点通知都是 App 主动接入厂商私有模板，第三方拿不到），只能读这几家发的普通通知。
 * 规则：<b>认不出阶段就返回 null，岛上什么都不显示</b>（失败要安静）；地址、骑手 / 司机电话这类不抠、不显示。
 * 车牌号保留（上车要对车，不是用户自己的隐私）。
 */
public final class ErrandParser {

    private ErrandParser() {}

    static final Map<String, String> APPS = new LinkedHashMap<String, String>();
    static {
        APPS.put("com.sankuai.meituan.takeoutnew", "美团外卖");
        APPS.put("com.sankuai.meituan", "美团");
        APPS.put("me.ele", "饿了么");
        APPS.put("com.sdu.didi.psnger", "滴滴");
    }

    public static boolean supports(String pkg) {
        return pkg != null && APPS.containsKey(pkg);
    }

    static boolean ride(String pkg) {
        return "com.sdu.didi.psnger".equals(pkg);
    }

    /** 外卖阶段：按「越往后越优先」排（同一条里同时出现「已接单」和「配送中」取配送中）。 */
    static final String[][] FOOD = {
            {"已送达", "(已送达|已经送达|送达了|订单已完成|已签收)"},
            {"骑手即将送达", "(即将送达|马上送达|快到了|已到达您附近)"},
            {"配送中", "(配送中|正在配送|骑手正在(?:赶来|送)|正在为您配送|送餐中|骑手已出发)"},
            {"骑手已取餐", "(已取餐|骑手已取货|取餐成功|已取货)"},
            {"骑手赶往商家", "(骑手已接单|骑手正赶往商家|骑手到店|骑手已到店)"},
            {"商家备餐中", "(商家已接单|备餐中|正在备餐|正在制作|出餐中|商家正在准备)"},
            {"已下单", "(已下单|待接单|等待商家接单|已支付)"},
    };

    static final String[][] RIDE = {
            {"行程结束", "(行程结束|已到达目的地|行程已完成|待支付|请支付)"},
            {"行程中", "(行程中|正在前往目的地|已上车|行程开始)"},
            {"司机已到达", "(司机已到达|已到达上车点|司机已到|请尽快上车)"},
            {"司机接驾中", "(司机正在赶来|正在赶来|接驾中|司机已接单|距您|距你|预计\\d+分钟到达)"},
            {"呼叫中", "(正在呼叫|等待应答|正在为您寻找|叫车中|排队中)"},
    };

    static final Pattern ETA_CLOCK = Pattern.compile("(?:预计|预估|约)\\s*(\\d{1,2})[:：](\\d{2})");
    static final Pattern ETA_MIN = Pattern.compile("(?:预计|约|还有|还需|大约)?\\s*(\\d{1,3})\\s*分钟");
    static final Pattern DIST = Pattern.compile("(?:距(?:您|你|离)?|还有)\\s*(\\d+(?:\\.\\d+)?)\\s*(公里|千米|km|米|m)(?![a-z])");
    /** 车牌：省份简称 + 字母 + 5–6 位（中间可能有 · 或空格）。 */
    static final Pattern PLATE = Pattern.compile("([京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼][A-Z][·•\\s]?[A-Z0-9]{5,6})");

    /** 阶段（纯函数）；认不出 → null。 */
    public static String stage(String pkg, String body) {
        if (body == null) return null;
        String[][] tbl = ride(pkg) ? RIDE : FOOD;
        for (String[] row : tbl) if (Pattern.compile(row[1]).matcher(body).find()) return row[0];
        return null;
    }

    /** 预计时间（纯函数）：「12:30」或「约 8 分钟」；没有 → 空串。 */
    public static String eta(String body) {
        if (body == null) return "";
        Matcher m = ETA_CLOCK.matcher(body);
        if (m.find()) {
            int h = Integer.parseInt(m.group(1)), mi = Integer.parseInt(m.group(2));
            if (h < 24 && mi < 60) return (h < 10 ? "0" : "") + h + ":" + m.group(2);
        }
        m = ETA_MIN.matcher(body);
        if (m.find()) {
            int v = Integer.parseInt(m.group(1));
            if (v > 0 && v < 300) return "约 " + v + " 分钟";
        }
        return "";
    }

    static boolean finished(String stage) {
        return "已送达".equals(stage) || "行程结束".equals(stage);
    }

    /** 一条通知 → 进度提醒；认不出阶段 → null（安静）。同一条通知更新时去重键不变，岛上原地更新。 */
    public static IslandNotice parse(String pkg, String label, String title, String text, String keyHash, long now) {
        if (!supports(pkg)) return null;
        String body = ((title == null ? "" : title) + " " + (text == null ? "" : text)).trim();
        String st = stage(pkg, body);
        if (st == null) return null;
        String eta = finished(st) ? "" : eta(body);
        boolean isRide = ride(pkg);
        String app = APPS.get(pkg);
        StringBuilder sum = new StringBuilder(app);
        if (eta.length() > 0) sum.append(" · ").append(eta.startsWith("约") ? eta : "预计 " + eta + (isRide ? " 到达" : " 送达"));
        if (isRide) {
            Matcher p = PLATE.matcher(body);
            if (p.find()) sum.append(" · ").append(p.group(1).replace(" ", ""));
            Matcher d = DIST.matcher(body);
            if (d.find() && !finished(st)) sum.append(" · 距 ").append(d.group(1)).append(d.group(2));
        }
        String ttl = (isRide ? "打车 · " : "外卖 · ") + st;
        if (eta.length() > 0 && !eta.startsWith("约")) ttl += " · " + eta;
        IslandNotice n = new IslandNotice(IslandNotice.Source.ERRAND, "errand:" + keyHash, IslandNotice.Priority.NORMAL, ttl, sum.toString());
        n.sourceTag = isRide ? "打车" : "外卖";
        n.tone = finished(st) ? "ok" : "";
        n.persist = false;
        n.expireAt = now + (finished(st) ? 10L * 60000L : 3L * 3600000L);
        n.action(new NoticeAction("打开", NoticeAction.Kind.OPEN_APP, pkg));
        if (finished(st)) n.action(new NoticeAction("知道了", NoticeAction.Kind.DISMISS, ""));
        return n;
    }
}
