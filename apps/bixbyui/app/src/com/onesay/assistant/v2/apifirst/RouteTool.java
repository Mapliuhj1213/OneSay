package com.onesay.assistant.v2.apifirst;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ★ feat/api-first：给 AgentLoop 的工具 {@code route.open}（wire 名 {@code route__open}）——把一个意图交给 {@link ApiFirstRouter}
 * 按「系统接口 → 已配置接口 → App 深链 / 浏览器 → 点界面」选路执行。纯 Java（工具 JSON / 参数解析 / 系统 Intent 参数换算），JVM 可测。
 */
public final class RouteTool {

    private RouteTool() {}

    public static final String SIGN = "route.open";

    private static volatile Capability sCap;

    /** 门禁 / 日志用的能力描述（不进注册表、不进技能缓存）。打开页面 / 设闹钟这一级：设备控制、风险 0，与 app.launch 同级。 */
    public static Capability capability() {
        if (sCap == null) {
            sCap = new Capability.Builder(SIGN, "按接口优先办一件事（系统接口 / 已配置接口 / App 深链 / 点界面兜底）")
                    .domain("agent").category("agent")
                    .sideEffect(SideEffect.DEVICE_CONTROL).risk(0, false)
                    .executor("custom")
                    .schema(new ParamsSchema())
                    .build();
        }
        return sCap;
    }

    public static boolean isSign(String signOrWire) {
        if (signOrWire == null) return false;
        String s = signOrWire.indexOf("__") >= 0 ? CapabilityRegistry.toolNameToSign(signOrWire) : signOrWire;
        return SIGN.equals(s);
    }

    /** 工具说明（短）：何时用、付款只到页面为止。 */
    public static final String DESC = "按「系统接口 > 已配置接口 > App 深链 / 浏览器链接 > 点界面」的顺序替用户办一件事，自动选路并在失败时回落。"
            + "适合：设闹钟 / 计时器、加日程、找联系人、打开某个设置页、查天气、联网搜索、搜商品、查火车车次、导航 / 搜周边，"
            + "以及订火车票、点外卖、买东西（这三类只打开到预订 / 下单页，付款必须用户自己点，结果里的 handoff 要原样告诉用户）。"
            + "返回 route（走了哪一档）、trace（每一步走了哪条路、为什么）、data（接口拿到的结果）、booking_links（预订入口）。";

    /** 追加到本轮 tools 末尾（同名已存在就跳过）。 */
    public static void append(JSONArray tools) {
        if (tools == null) return;
        String wire = CapabilityRegistry.signToToolName(SIGN);
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.optJSONObject(i);
            JSONObject fn = t == null ? null : t.optJSONObject("function");
            if (fn != null && wire.equals(fn.optString("name"))) return;
        }
        try {
            JSONObject props = new JSONObject();
            JSONArray kinds = new JSONArray();
            for (String k : ApiIntent.KINDS) kinds.put(k);
            props.put("intent", new JSONObject().put("type", "string").put("enum", kinds)
                    .put("description", "要办的事：alarm.set 设闹钟 / timer.set 计时器 / calendar.add 加日程 / contact.find 找联系人 / "
                            + "settings.open 打开设置页 / weather.now 天气 / web.search 搜索 / goods.search 搜商品 / train.query 查车次 / "
                            + "nav.to 导航 / poi.nearby 搜周边 / train.book 订火车票 / food.order 点外卖 / shop.buy 买东西"));
            putStr(props, "q", "关键词（搜索、搜商品、搜周边、外卖的店或菜）");
            putStr(props, "dest", "目的地（导航）");
            putStr(props, "from", "出发城市或车站（火车）");
            putStr(props, "to", "到达城市或车站（火车）");
            putStr(props, "date", "日期 yyyy-MM-dd（火车、日程）");
            putStr(props, "city", "城市（天气）");
            putStr(props, "time", "时间 HH:mm（闹钟、日程开始）");
            putStr(props, "end_time", "日程结束时间 HH:mm，可不填");
            putStr(props, "minutes", "计时器分钟数");
            putStr(props, "title", "日程标题 / 闹钟备注");
            putStr(props, "name", "联系人名字");
            putStr(props, "page", "设置页：wifi / bluetooth / location / display / sound / battery / apps / notification / data，不填 = 设置首页");
            putStr(props, "app", "用户点名要用的 App（如 高德地图、京东），不填 = 自动选");
            JSONObject params = new JSONObject();
            params.put("type", "object");
            params.put("properties", props);
            params.put("required", new JSONArray().put("intent"));
            JSONObject fn = new JSONObject();
            fn.put("name", wire);
            fn.put("description", DESC);
            fn.put("parameters", params);
            tools.put(new JSONObject().put("type", "function").put("function", fn));
        } catch (Exception ignored) {
        }
    }

    private static void putStr(JSONObject props, String name, String desc) throws Exception {
        props.put(name, new JSONObject().put("type", "string").put("description", desc));
    }

    static final String[] SLOTS = {"q", "dest", "from", "to", "date", "city", "time", "end_time", "minutes", "title", "name", "page", "app"};

    /** 工具参数 → 意图。 */
    public static ApiIntent parse(JSONObject args) {
        if (args == null) return new ApiIntent("", null);
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (String s : SLOTS) {
            Object v = args.opt(s);
            if (v != null && v != JSONObject.NULL) m.put(s, String.valueOf(v));
        }
        return new ApiIntent(args.optString("intent", "").trim().toLowerCase(Locale.ROOT), m);
    }

    // ------------------------------------------------------------------ 系统接口参数换算（纯函数，Android 侧照用）

    /** 设置页名字 → android.provider.Settings 的 action 字符串。 */
    public static String settingsAction(String page) {
        String p = page == null ? "" : page.trim().toLowerCase(Locale.ROOT);
        if (p.contains("wifi") || p.contains("无线") || p.contains("wlan")) return "android.settings.WIFI_SETTINGS";
        if (p.contains("bluetooth") || p.contains("蓝牙")) return "android.settings.BLUETOOTH_SETTINGS";
        if (p.contains("location") || p.contains("定位") || p.contains("位置")) return "android.settings.LOCATION_SOURCE_SETTINGS";
        if (p.contains("display") || p.contains("显示") || p.contains("亮度")) return "android.settings.DISPLAY_SETTINGS";
        if (p.contains("sound") || p.contains("声音") || p.contains("铃声")) return "android.settings.SOUND_SETTINGS";
        if (p.contains("battery") || p.contains("电池") || p.contains("省电")) return "android.settings.BATTERY_SAVER_SETTINGS";
        if (p.contains("notification") || p.contains("通知")) return "android.settings.NOTIFICATION_SETTINGS";   // 注：部分机型只有按应用的通知页
        if (p.contains("data") || p.contains("流量") || p.contains("移动网络")) return "android.settings.DATA_ROAMING_SETTINGS";
        if (p.contains("app") || p.contains("应用")) return "android.settings.APPLICATION_SETTINGS";
        return "android.settings.SETTINGS";
    }

    /** 「7:30」「07:30」「7点半」「19点」→ {时, 分}；认不出 → null。 */
    public static int[] parseTime(String s) {
        if (s == null) return null;
        String t = s.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{1,2})[:：](\\d{1,2})$").matcher(t);
        if (m.find()) return hm(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        m = java.util.regex.Pattern.compile("^(\\d{1,2})点(半|(\\d{1,2})分?)?$").matcher(t);
        if (m.find()) {
            int h = Integer.parseInt(m.group(1));
            int mi = m.group(2) == null ? 0 : "半".equals(m.group(2)) ? 30 : Integer.parseInt(m.group(3));
            return hm(h, mi);
        }
        return null;
    }

    private static int[] hm(int h, int m) {
        if (h < 0 || h > 23 || m < 0 || m > 59) return null;
        return new int[]{h, m};
    }

    /** 计时器秒数（「5」「5分钟」「1.5」→ 秒）；认不出或 ≤0 → -1。 */
    public static int timerSeconds(String minutes) {
        if (minutes == null) return -1;
        String t = minutes.trim().replace("分钟", "").replace("分", "");
        try {
            double d = Double.parseDouble(t);
            int s = (int) Math.round(d * 60);
            return s > 0 && s <= 24 * 3600 ? s : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 日程开始 / 结束（Asia/Shanghai 所在的本机时区）毫秒；date 缺 → 今天；time 缺 → -1。 */
    public static long epochMillis(String date, String time, java.util.TimeZone tz, long now) {
        int[] hm = parseTime(time);
        if (hm == null) return -1;
        java.util.Calendar c = java.util.Calendar.getInstance(tz, Locale.ROOT);
        c.setTimeInMillis(now);
        if (date != null && date.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
            String[] p = date.split("-");
            c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
        }
        c.set(java.util.Calendar.HOUR_OF_DAY, hm[0]);
        c.set(java.util.Calendar.MINUTE, hm[1]);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
