package com.onesay.assistant.v2.apifirst;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ★ feat/api-first（2026-11-11）：接口 / 深链优先、点屏兜底的路由层。纯 Java，JVM 可测；Android 侧只实现 {@link Env} / {@link Runner}。
 *
 * <p>执行一个意图前按顺序选：
 * <ol>
 *   <li>① 系统接口 / ContentProvider（闹钟、计时器、日历、联系人、设置页）；</li>
 *   <li>② 已配置的接口（和风天气 / Tavily 搜索 / 海纳商品；没有密钥或开源版默认关 → 跳过）；</li>
 *   <li>③ App 深链（{@link DeepLinks}，只打开到对应页面）→ 失败回落浏览器 https 链接；</li>
 *   <li>④ 界面自动化（现有点屏代理）。</li>
 * </ol>
 * 每一步（选中 / 跳过 / 失败回落）都记一行 {@link Outcome#trace}，写进日志和工具观测，时间轴也能看到。
 *
 * <p><b>付款</b>：{@link ApiIntent#payment()} 的种类（订火车票、点外卖、买东西）只走到深链 / 浏览器页为止，
 * <b>永远不进 ④</b>，结果里写明「需要你在 X 里确认并付款」—— 与「能批就不拦」的硬拒第 ① 条（付款最后一下必须你亲手点，
 * 原因码 {@code pay_user_tap}，见 SensitiveGuard.userMustTap）一致。
 * 出行类：查询结果走 ②（搜索接口拿），订票只给深链。
 */
public final class ApiFirstRouter {

    public static final String TAG = "[API-FIRST]";
    /** 与「能批就不拦」硬拒 ① 同一个原因码。 */
    public static final String CODE_PAY_USER_TAP = "pay_user_tap";

    // ---- ② 已配置接口的名字 ----
    public static final String API_QWEATHER = "qweather";
    public static final String API_TAVILY = "tavily";
    public static final String API_HAINA = "haina";

    /** 路由层看环境（只读判断，不执行）。 */
    public interface Env {
        /** ① 这台机器上有没有这一类的系统接口（例：有没有能处理 AlarmClock.ACTION_SET_ALARM 的应用）。 */
        boolean systemAvailable(String kind);

        /** ② 已配置接口这一档总开关（个人版默认开；开源版默认关）。 */
        boolean keyedEnabled();

        /** ② 这个接口有没有配密钥（只回答有没有，不碰密钥本身）。 */
        boolean hasKey(String api);

        /** ③ 这个包装没装。 */
        boolean installed(String pkg);

        /** ④ 点屏代理现在能不能用（读屏服务在不在）。 */
        boolean guiAvailable();
    }

    /** 一次尝试的结果。 */
    public static final class Attempt {
        public final boolean ok;
        public final String data;
        public final String why;

        private Attempt(boolean ok, String data, String why) {
            this.ok = ok;
            this.data = data == null ? "" : data;
            this.why = why == null ? "" : why;
        }

        public static Attempt ok(String data) {
            return new Attempt(true, data, null);
        }

        public static Attempt fail(String why) {
            return new Attempt(false, null, why == null || why.length() == 0 ? "没成功" : why);
        }
    }

    /** 真正去做的那一侧（Android：startActivity / ContentResolver / HTTP / phone.operate）。 */
    public interface Runner {
        Attempt system(ApiIntent in);

        Attempt keyed(String api, ApiIntent in);

        /** 打开 uri（{@code launch:包名} = 只把 App 开到首页）。pkg 为 null = 用浏览器 / 系统默认处理。 */
        Attempt open(DeepLinks.Link link, String uri);

        Attempt gui(ApiIntent in);

        /** 停止键按了没有（按了就不再试下一条）。 */
        boolean stopped();
    }

    /** 计划里的一条候选。 */
    public static final class Step {
        public final RouteTier tier;
        public final String api;
        public final DeepLinks.Link link;

        Step(RouteTier tier, String api, DeepLinks.Link link) {
            this.tier = tier;
            this.api = api;
            this.link = link;
        }

        public String name() {
            if (tier == RouteTier.KEYED_API) return apiLabel(api);
            if (link != null) return link.app;
            if (tier == RouteTier.SYSTEM) return "系统接口";
            return "界面操作";
        }

        @Override public String toString() {
            return tier.mark() + tier.label + " " + name();
        }
    }

    /** 选路的结果：按顺序要试的候选 + 选 / 不选的原因。 */
    public static final class Plan {
        public final ApiIntent intent;
        public final List<Step> steps;
        public final List<String> notes;

        Plan(ApiIntent intent, List<Step> steps, List<String> notes) {
            this.intent = intent;
            this.steps = Collections.unmodifiableList(steps);
            this.notes = Collections.unmodifiableList(notes);
        }

        public List<RouteTier> tiers() {
            List<RouteTier> out = new ArrayList<RouteTier>();
            for (Step s : steps) out.add(s.tier);
            return out;
        }
    }

    /** 执行结果。 */
    public static final class Outcome {
        public boolean ok;
        public boolean stopped;
        public RouteTier tier;
        public String via = "";
        public String uri = "";
        public String data = "";
        /** 交给用户的那一下（付款 / 确认），空 = 没有。 */
        public String handoff = "";
        public String code = "";
        public final List<String> trace = new ArrayList<String>();
        /** 出行查询 / 付款类：顺带给出的预订入口（app 名 + 链接），由模型转述给用户，不自动打开。 */
        public final List<String[]> links = new ArrayList<String[]>();

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("ok", ok);
                if (stopped) o.put("stopped", true);
                if (tier != null) o.put("route", tier.mark() + tier.label);
                if (via.length() > 0) o.put("via", via);
                if (uri.length() > 0 && !uri.startsWith(DeepLinks.LAUNCH)) o.put("opened", uri);
                if (data.length() > 0) o.put("data", data);
                if (handoff.length() > 0) o.put("handoff", handoff);
                if (code.length() > 0) o.put("code", code);
                JSONArray t = new JSONArray();
                for (String s : trace) t.put(s);
                o.put("trace", t);
                if (!links.isEmpty()) {
                    JSONArray l = new JSONArray();
                    for (String[] x : links) {
                        JSONObject e = new JSONObject();
                        e.put("app", x[0]);
                        e.put("link", x[1]);
                        l.put(e);
                    }
                    o.put("booking_links", l);
                }
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    // ------------------------------------------------------------------ 每个种类能走哪些路

    /** ① 系统接口能直接办的种类。 */
    static boolean systemKind(String kind) {
        return ApiIntent.ALARM_SET.equals(kind) || ApiIntent.TIMER_SET.equals(kind) || ApiIntent.CALENDAR_ADD.equals(kind)
                || ApiIntent.CONTACT_FIND.equals(kind) || ApiIntent.SETTINGS_OPEN.equals(kind);
    }

    /** ② 这个种类用哪个已配置接口（没有 → null）。 */
    static String keyedApi(String kind) {
        if (ApiIntent.WEATHER.equals(kind)) return API_QWEATHER;
        if (ApiIntent.WEB_SEARCH.equals(kind) || ApiIntent.TRAIN_QUERY.equals(kind)) return API_TAVILY;
        if (ApiIntent.GOODS_SEARCH.equals(kind)) return API_HAINA;
        return null;
    }

    public static String apiLabel(String api) {
        if (API_QWEATHER.equals(api)) return "和风天气";
        if (API_TAVILY.equals(api)) return "Tavily 搜索";
        if (API_HAINA.equals(api)) return "海纳商品";
        return api == null ? "" : api;
    }

    // ------------------------------------------------------------------ 选路

    public static Plan plan(ApiIntent in, Env env) {
        List<Step> steps = new ArrayList<Step>();
        List<String> notes = new ArrayList<String>();
        if (in == null || !in.known()) {
            notes.add("不认识的意图（" + (in == null ? "" : in.kind) + "），不经路由层");
            return new Plan(in, steps, notes);
        }
        String k = in.kind;
        // ① 系统接口
        if (systemKind(k)) {
            if (env.systemAvailable(k)) {
                steps.add(new Step(RouteTier.SYSTEM, null, null));
                notes.add("①系统接口：有，先用它");
            } else {
                notes.add("①系统接口：这台机器上没有能处理的应用，跳过");
            }
        } else {
            notes.add("①系统接口：这类事没有系统接口，跳过");
        }
        // ② 已配置接口
        String api = keyedApi(k);
        if (api == null) {
            notes.add("②已配置接口：这类事没有对应接口，跳过");
        } else if (!env.keyedEnabled()) {
            notes.add("②已配置接口：" + apiLabel(api) + " 这一档没打开，跳过");
        } else if (!env.hasKey(api)) {
            notes.add("②已配置接口：" + apiLabel(api) + " 没配密钥，跳过");
        } else {
            steps.add(new Step(RouteTier.KEYED_API, api, null));
            notes.add("②已配置接口：" + apiLabel(api) + " 已配置，用它拿结果");
        }
        // ③ 深链 → 浏览器链接（出行查询：深链只作为预订入口给出，见 run）
        if (!ApiIntent.TRAIN_QUERY.equals(k)) {
            List<DeepLinks.Link> links = DeepLinks.preferApp(DeepLinks.forKind(k), in.slot("app"));
            for (DeepLinks.Link l : links) {
                String miss = l.missing(in);
                if (miss != null) {
                    notes.add("③" + l.tier().label + " " + l.app + "：缺少「" + slotLabel(miss) + "」，跳过");
                    continue;
                }
                if (!l.browser() && !env.installed(l.pkg)) {
                    notes.add("③App 深链 " + l.app + "：没装，跳过");
                    continue;
                }
                steps.add(new Step(l.tier(), null, l));
            }
        } else {
            // 查车次：结果靠 ② 搜索拿；接口没有 / 失败时退到 12306 官网查询页让你自己看（只打开页面）
            DeepLinks.Link web = DeepLinks.byId("web.12306.query");
            if (web != null && web.missing(in) == null) {
                steps.add(new Step(RouteTier.BROWSER, null, web));
                notes.add("③浏览器链接：12306 官网查询页（接口走不通时打开）");
            } else {
                notes.add("③浏览器链接：缺出发地 / 目的地，不能拼 12306 查询页");
            }
        }
        // ④ 界面自动化
        if (in.payment()) {
            notes.add("④界面操作：涉及付款，不进界面自动化（最后一下得你亲手点）");
        } else if (ApiIntent.TRAIN_QUERY.equals(k)) {
            notes.add("④界面操作：查车次不去点 App，跳过");
        } else if (!env.guiAvailable()) {
            notes.add("④界面操作：读屏服务没开，不可用");
        } else {
            steps.add(new Step(RouteTier.GUI, null, null));
        }
        return new Plan(in, steps, notes);
    }

    static String slotLabel(String slot) {
        if ("q".equals(slot)) return "关键词";
        if ("dest".equals(slot)) return "目的地";
        if ("from".equals(slot)) return "出发地";
        if ("to".equals(slot)) return "目的地";
        if ("date".equals(slot)) return "日期";
        return slot;
    }

    // ------------------------------------------------------------------ 执行（按计划逐条试，失败回落下一条）

    public static Outcome run(Plan plan, Runner r) {
        Outcome o = new Outcome();
        o.trace.addAll(plan.notes);
        ApiIntent in = plan.intent;
        if (in == null || !in.known()) {
            o.code = "unknown_intent";
            return o;
        }
        for (Step s : plan.steps) {
            if (r.stopped()) {
                o.stopped = true;
                o.code = "stopped";
                o.trace.add("已停止");
                return o;
            }
            Attempt a;
            String uri = "";
            switch (s.tier) {
                case SYSTEM:
                    a = r.system(in);
                    break;
                case KEYED_API:
                    a = r.keyed(s.api, in);
                    break;
                case DEEPLINK:
                case BROWSER:
                    uri = s.link.fill(in);
                    a = r.open(s.link, uri);
                    break;
                default:
                    if (in.payment()) {   // 双保险：计划里本来就没有，万一有也不做
                        a = Attempt.fail("涉及付款，不进界面自动化");
                    } else {
                        a = r.gui(in);
                    }
                    break;
            }
            String head = s.tier.mark() + s.tier.label + " " + s.name();
            if (a == null) a = Attempt.fail("没有结果");
            if (!a.ok) {
                o.trace.add(head + "：失败（" + a.why + "），换下一条");
                continue;
            }
            o.ok = true;
            o.tier = s.tier;
            o.via = s.name();
            o.uri = uri;
            o.data = a.data;
            o.trace.add(head + "：" + (s.link != null ? "已打开" : "成功"));
            finish(o, in, s);
            return o;
        }
        if (r.stopped()) {
            o.stopped = true;
            o.code = "stopped";
            o.trace.add("已停止");
            return o;
        }
        o.ok = false;
        o.code = in.payment() ? CODE_PAY_USER_TAP : "no_route";
        o.trace.add("几条路都没走通");
        if (in.payment()) {
            o.handoff = "没能替你打开预订 / 下单页，需要你自己打开 " + appsFor(in.kind) + "，确认并付款";
            addBookingLinks(o, in);
        }
        return o;
    }

    /** 成功后的收尾：付款类写交接，查车次附预订入口。 */
    private static void finish(Outcome o, ApiIntent in, Step s) {
        if (in.payment()) {
            o.code = CODE_PAY_USER_TAP;
            String what = ApiIntent.TRAIN_BOOK.equals(in.kind) ? "确认车次并付款"
                    : ApiIntent.FOOD_ORDER.equals(in.kind) ? "选好菜、确认订单并付款" : "确认商品并付款";
            o.handoff = "需要你在" + (s.link != null ? s.link.app : "App") + "里" + what;
        } else if (ApiIntent.TRAIN_QUERY.equals(in.kind)) {
            o.handoff = "订票需要你在 12306 / 携程 / 同程里确认车次并付款";
            addBookingLinks(o, in);
        }
    }

    private static void addBookingLinks(Outcome o, ApiIntent in) {
        String kind = ApiIntent.TRAIN_QUERY.equals(in.kind) ? ApiIntent.TRAIN_BOOK : in.kind;
        for (DeepLinks.Link l : DeepLinks.forKind(kind)) {
            if (l.missing(in) != null) continue;
            if (l.browser()) o.links.add(new String[]{l.app, l.fill(in)});
            else o.links.add(new String[]{l.app, "（打开 " + l.app + "）"});
        }
    }

    static String appsFor(String kind) {
        StringBuilder sb = new StringBuilder();
        for (DeepLinks.Link l : DeepLinks.forKind(kind)) {
            if (l.browser()) continue;
            if (sb.indexOf(l.app) >= 0) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(l.app);
        }
        return sb.length() == 0 ? "对应的 App" : sb.toString();
    }

    /** 一行日志（不含密钥、不含查询原文以外的东西）。 */
    public static String logLine(Outcome o) {
        StringBuilder sb = new StringBuilder(TAG).append(' ');
        sb.append(o.ok ? "走 " + (o.tier == null ? "?" : o.tier.mark() + o.tier.label) + " " + o.via : (o.stopped ? "已停止" : "未走通"));
        for (String t : o.trace) sb.append(" | ").append(t);
        return sb.toString();
    }
}
