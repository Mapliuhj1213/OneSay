package com.onesay.assistant.v2.vision.screenref;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [screenref] 识屏指代自带的能力目录（纯 Java，JVM 可测）。
 *
 * <h3>为什么不注册进 {@link CapabilityRegistry}</h3>
 * <p>注册表会被 L0/L1/Top-K/L2 全链路召回；把这些能力放进去，"导航到公司"之类与屏幕无关的老指令也可能被改道，
 * 违反"功能开关之外行为不变"。因此它们只活在这里，由三个挂钩显式使用：
 * <ul>
 *   <li>AgentLoop：{@link ScreenRefHooks#appendTools} 把它们按需追加进 tools；{@link ScreenRefHooks#describe} 解析模型回传的签名；</li>
 *   <li>面板：芯片/指代消解直接拿 {@link Capability} 对象走 {@code FloatingSheetService.screenRefExecute}（与 dispatch 同一套门禁→确认卡→执行器）。</li>
 * </ul>
 * 若集成方希望它们进入普通召回，可在 CapabilityRegistry.getInstance() 里加一行 {@link #registerInto(CapabilityRegistry)}（默认不接）。
 *
 * <h3>执行方式</h3>
 * <ul>
 *   <li>{@code screen.nav.to} / {@code screen.calendar.add}：shell 模板（am start Intent），走 UnifiedExecutor；
 *       模板由 {@code executor/ShellCommand} 按 shell 上下文转义（2026-10-07 集成：替代旧 escapeShellValue）——
 *       单引号内的地址只转义 {@code '}，且处于 URL 查询值位置（{@code ?q=} / {@code ?keyword=}）时先做 UTF-8 百分号编码；
 *       日历的毫秒时间用 {@code {x:i}} 强制整数；</li>
 *   <li>{@code screen.copy} / {@code screen.open.url} / {@code screen.context}：<b>App 进程内本地执行</b>（ClipboardManager / startActivity / 读缓存），
 *       不经 root —— 复制在 shell 里没有可靠接口；打开链接走 Intent 可避免任何 shell 转义问题。</li>
 * </ul>
 *
 * <h3>副作用声明与门禁（2026-10-07 集成）</h3>
 * <ul>
 *   <li>{@code screen.context} = {@code PRIVATE_DATA_READ}：读取成功后 AgentLoop 把后续步骤标为 tainted（读屏 = 不可信内容进上下文）；</li>
 *   <li>{@code screen.nav.to / screen.calendar.add / screen.open.url} = {@code DEVICE_CONTROL}（拉起界面，与 app.launch 同类）：
 *       未污染时 risk 0 直接放行；tainted 时 ToolGate 要求红卡确认；</li>
 *   <li>{@code screen.copy} = {@code NONE}：只写剪贴板、用户可见，污点下也放行（取舍）。</li>
 * </ul>
 * 面板侧（芯片点击 / 用户亲口说的指代）按 <b>tainted=false</b> 过闸：动作由用户选定，参数取自屏幕；phone.call 等 risk=2 能力照常弹确认卡。
 */
public final class ScreenRefCapabilities {

    private ScreenRefCapabilities() {}

    public static final String SIGN_CONTEXT = "screen.context";
    public static final String SIGN_NAV = "screen.nav.to";
    public static final String SIGN_CALENDAR = "screen.calendar.add";
    public static final String SIGN_OPEN_URL = "screen.open.url";
    public static final String SIGN_COPY = "screen.copy";

    private static volatile Map<String, Capability> sCatalog;

    private static Map<String, Capability> catalog() {
        Map<String, Capability> c = sCatalog;
        if (c != null) return c;
        synchronized (ScreenRefCapabilities.class) {
            if (sCatalog != null) return sCatalog;
            Map<String, Capability> m = new LinkedHashMap<String, Capability>();
            put(m, new Capability.Builder(SIGN_CONTEXT,
                    "读取唤醒瞬间的屏幕上下文：前台应用 + 屏幕上的电话/地址/时间/链接/快递单号/取件码/邮箱/金额（用户说“这个/这里/屏幕上”时先调用）")
                    .domain("screenref").category("screen")
                    .verbs("看", "读", "识别").entities("screen")
                    .sideEffect(SideEffect.PRIVATE_DATA_READ).risk(0, false)
                    .executor("custom")
                    .schema(new ParamsSchema()
                            .addString("type", false, "")
                            .addBoolean("with_texts", false, "false"))
                    .reply("已读取屏幕上下文")
                    .build());
            put(m, new Capability.Builder(SIGN_NAV, "导航到屏幕上的地址（调起系统默认地图；无地图应用时打开高德网页版）")
                    .domain("screenref").category("map")
                    .verbs("导航", "去").entities("address")
                    .sideEffect(SideEffect.DEVICE_CONTROL).risk(0, false)
                    .executor("shell")
                    .schema(new ParamsSchema().addString("address", true, null))
                    .cmd("if am start -a android.intent.action.VIEW -d 'geo:0,0?q={address}' 2>&1 | grep -q Error; then "
                            + "am start -a android.intent.action.VIEW -d 'https://uri.amap.com/search?keyword={address}' >/dev/null 2>&1; fi; true")
                    .reply("正在导航到 {address}")
                    .build());
            put(m, new Capability.Builder(SIGN_CALENDAR, "把屏幕上的时间加入日历（打开日历新建日程页，由用户确认保存）")
                    .domain("screenref").category("calendar")
                    .verbs("添加", "提醒").entities("calendar")
                    .sideEffect(SideEffect.DEVICE_CONTROL).risk(0, false)
                    .executor("shell")
                    .schema(new ParamsSchema()
                            .addString("title", false, "屏幕日程")
                            .addString("begin_ms", true, null)
                            .addString("end_ms", false, "")
                            .addString("all_day", false, "false"))
                    .cmd("am start -a android.intent.action.INSERT -d content://com.android.calendar/events "
                            + "--es title '{title}' --el beginTime {begin_ms:i} --el endTime {end_ms:i} --ez allDay {all_day}")
                    .reply("已打开日历新建页：{title}")
                    .build());
            put(m, new Capability.Builder(SIGN_OPEN_URL, "打开屏幕上的链接或邮箱（仅 http/https/mailto）")
                    .domain("screenref").category("app")
                    .verbs("打开").entities("url")
                    .sideEffect(SideEffect.DEVICE_CONTROL).risk(0, false)
                    .executor("custom")
                    .schema(new ParamsSchema().addString("url", true, null))
                    .reply("正在打开链接")
                    .build());
            put(m, new Capability.Builder(SIGN_COPY, "把屏幕上的文字（取件码/单号/电话/地址等）复制到剪贴板")
                    .domain("screenref").category("clipboard")
                    .verbs("复制").entities("text")
                    .sideEffect(SideEffect.NONE).risk(0, false)
                    .executor("custom")
                    .schema(new ParamsSchema().addString("text", true, null).addString("kind", false, "内容"))
                    .reply("已复制{kind}")
                    .build());
            sCatalog = Collections.unmodifiableMap(m);
            return sCatalog;
        }
    }

    private static void put(Map<String, Capability> m, Capability c) {
        m.put(c.sign, c);
    }

    /** 按签名取能力（非本目录返回 null）。 */
    public static Capability get(String sign) {
        if (sign == null) return null;
        return catalog().get(sign);
    }

    public static boolean isScreenSign(String sign) {
        return sign != null && catalog().containsKey(sign);
    }

    /** 是否在 App 进程内本地执行（不经 UnifiedExecutor）。 */
    public static boolean isLocal(String sign) {
        return SIGN_CONTEXT.equals(sign) || SIGN_COPY.equals(sign) || SIGN_OPEN_URL.equals(sign);
    }

    public static List<Capability> all() {
        return new ArrayList<Capability>(catalog().values());
    }

    /** 可选：集成方若希望普通召回也能看到可执行的 screen.*（不含本地执行的三项），在注册表初始化处调用。默认未接线。 */
    public static void registerInto(CapabilityRegistry reg) {
        if (reg == null) return;
        reg.register(get(SIGN_NAV));
        reg.register(get(SIGN_CALENDAR));
    }

    /**
     * 执行前的参数兜底（AgentLoop 里模型给的参数不一定规整）：
     * 日历 begin_ms 不是数字时按中文时间解析；end_ms 缺失 = begin + 1 小时（全天 = +24h）。
     * 返回 null 表示参数无法修复。
     */
    public static org.json.JSONObject normalizeArgs(String sign, org.json.JSONObject in, long nowMs, java.util.TimeZone tz) {
        org.json.JSONObject a = in == null ? new org.json.JSONObject() : in;
        try {
            if (SIGN_CALENDAR.equals(sign)) {
                String b = a.optString("begin_ms", "").trim();
                long begin;
                boolean allDay = "true".equalsIgnoreCase(a.optString("all_day", "false"));
                if (b.matches("\\d{10,13}")) {
                    begin = Long.parseLong(b);
                    if (b.length() == 10) begin *= 1000L;
                } else {
                    java.util.List<DateTimeNormalizer.Span> sp = DateTimeNormalizer.findAll(b, nowMs, tz);
                    if (sp.isEmpty()) return null;
                    begin = sp.get(0).beginMs;
                    if (sp.get(0).allDay()) allDay = true;
                    if (sp.get(0).endMs > begin && a.optString("end_ms", "").trim().length() == 0) {
                        a.put("end_ms", String.valueOf(sp.get(0).endMs));
                    }
                }
                a.put("begin_ms", String.valueOf(begin));
                String e = a.optString("end_ms", "").trim();
                long end = e.matches("\\d{10,13}") ? Long.parseLong(e) * (e.length() == 10 ? 1000L : 1L) : 0L;
                if (end <= begin) end = begin + (allDay ? 24L : 1L) * 3600000L;
                a.put("end_ms", String.valueOf(end));
                a.put("all_day", allDay ? "true" : "false");
                if (a.optString("title", "").trim().length() == 0) a.put("title", "屏幕日程");
            } else if (SIGN_OPEN_URL.equals(sign)) {
                String u = a.optString("url", "").trim();
                if (u.length() > 0 && !u.contains(":") ) u = "http://" + u;
                u = ActionMapper.safeUrl(u);
                if (u == null) return null;
                a.put("url", u);
            } else if (SIGN_NAV.equals(sign)) {
                if (a.optString("address", "").trim().length() == 0) return null;
            } else if (SIGN_COPY.equals(sign)) {
                if (a.optString("text", "").length() == 0) return null;
            }
        } catch (Exception ex) {
            return null;
        }
        return a;
    }
}
