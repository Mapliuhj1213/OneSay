package com.onesay.assistant.v2.shortcut;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;
import com.onesay.assistant.v2.platform.DeviceCap;
import com.onesay.assistant.v2.platform.DeviceProfile;
import com.onesay.assistant.v2.platform.FeatureAvailability;
import com.onesay.assistant.v2.platform.FeatureRegistry;
import com.onesay.assistant.v2.platform.FeatureSpec;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * [shortcut] v1 动作清单 + 每个动作在这台设备上能不能用（纯 Java，JVM 全测）。
 *
 * <h3>动作从哪来</h3>
 * 绝大多数动作就是工具注册表里已有的能力（闹钟、待办、打开 App / 网页 / 设置页、勿扰、音量、亮度），执行时照常走
 * ExecutionGate + 确认卡；少数没有现成工具的是<b>快捷指令内部动作</b>（{@link #INTERNAL}）：问一句模型 {@code llm.ask}、
 * 加日程 {@code plan.event.add}（写计划 App）、播报晨间简报 {@code brief.play}。它们同样经过门禁（声明了副作用等级）。
 *
 * <h3>能不能用（置灰 + 原因）</h3>
 * <ol>
 *   <li>v1 没有工具的（保存剪贴板里的视频 / 图片）→ 永远置灰，写明原因；天气播报（★ feat/rain-minutely）接分钟级降水，Key / Host 不齐时置灰写原因；</li>
 *   <li><b>上机版专属</b>（{@link #isPrivileged}：清单里标了 privileged，或能力的执行器是 root shell —— shell / custom）：
 *       开源构建（{@link ShortcutBuild#PRIVILEGED_ACTIONS} = false）一律置灰；上机版再按能力探测（root + 执行守护）
 *       用 {@link FeatureAvailability} 裁决，探不到（UNKNOWN）不置灰；</li>
 *   <li>工具注册表里找不到 → 置灰「没有这个工具」；</li>
 *   <li>其余可用。</li>
 * </ol>
 */
public final class ShortcutActions {

    private ShortcutActions() {}

    public static final String LLM_ASK = "llm.ask", EVENT_ADD = "plan.event.add", BRIEF_PLAY = "brief.play", WEATHER = "weather.say",
            MEDIA_SAVE = "media.save_clip";
    /** 快捷指令内部动作（不在工具注册表里，由执行端口自己执行）。 */
    public static final List<String> INTERNAL = Collections.unmodifiableList(java.util.Arrays.asList(LLM_ASK, EVENT_ADD, BRIEF_PLAY, WEATHER, MEDIA_SAVE));

    /** 构建开关（开源默认 false；自用上机版在 local-config 里打开）。 */
    public static final boolean PRIVILEGED_BUILD = ShortcutBuild.PRIVILEGED_ACTIONS;

    public static final String REASON_PRIVILEGED_OFF = "需要特权执行后端（root 或 Shizuku），默认未选择";

    public static final class Param {
        public final String name, label, type, question;

        Param(String name, String label, String type, String question) {
            this.name = name;
            this.label = label;
            this.type = type;
            this.question = question;
        }
    }

    public static final class Action {
        public final String sign, label, group;
        public final List<Param> params = new ArrayList<Param>();
        boolean privileged;
        String notBuilt;

        Action(String sign, String label, String group) {
            this.sign = sign;
            this.label = label;
            this.group = group;
        }

        Action p(String name, String label, String type, String question) {
            params.add(new Param(name, label, type, question));
            return this;
        }

        Action priv() {
            privileged = true;
            return this;
        }

        Action notBuilt(String why) {
            notBuilt = why;
            return this;
        }

        public boolean privileged() {
            return privileged;
        }

        public Param param(String name) {
            for (Param p : params) if (p.name.equals(name)) return p;
            return null;
        }
    }

    private static final List<Action> ALL;

    static {
        List<Action> l = new ArrayList<Action>();
        l.add(new Action("clock.alarm.set", "定闹钟", "闹钟").p("hour", "几点（0–23）", "int", "闹钟定几点？").p("minute", "几分", "int", "几分？"));
        l.add(new Action("clock.timer.set", "倒计时", "闹钟").p("seconds", "多少秒", "int", "倒计时多久（秒）？"));
        l.add(new Action("s3.todo.add", "加待办", "待办").p("text", "内容", "string", "待办写什么？"));
        l.add(new Action(EVENT_ADD, "加日程", "日程").p("title", "标题", "string", "日程叫什么？")
                .p("date", "日期（today / tomorrow / 2026-11-07）", "string", "哪天？").p("time", "时间（HH:mm，留空 = 全天）", "string", "几点？"));
        l.add(new Action("app.launch", "打开 App", "打开").p("app", "App 名字", "string", "打开哪个 App？"));
        l.add(new Action("browser.open", "打开网页 / 深链", "打开").p("url", "网址或深链", "string", "打开哪个网址？"));
        l.add(new Action("settings.page.wifi", "打开 Wi‑Fi 设置页", "打开"));
        l.add(new Action("dnd.on", "打开勿扰", "系统开关").priv());
        l.add(new Action("dnd.off", "关闭勿扰", "系统开关").priv());
        l.add(new Action("volume.media.set", "媒体音量", "系统开关").p("level", "音量（0–15）", "int", "媒体音量调到多少？").priv());
        l.add(new Action("volume.ring.set", "铃声音量", "系统开关").p("level", "音量（0–15）", "int", "铃声音量调到多少？").priv());
        l.add(new Action("settings.brightness.set", "屏幕亮度", "系统开关").p("level", "亮度（0–255）", "int", "亮度调到多少？").priv());
        l.add(new Action(BRIEF_PLAY, "播报晨间简报", "播报").priv());
        l.add(new Action(WEATHER, "播报天气", "播报"));   // ★ feat/rain-minutely：接分钟级降水（和风天气），能不能用看 weatherProbe
        l.add(new Action(MEDIA_SAVE, "保存剪贴板里的视频 / 图片", "保存").notBuilt("这一版没有该工具（feat/media-save 未合入）"));
        l.add(new Action(LLM_ASK, "问一句模型（结果传给下一步）", "模型").p("prompt", "要问的话", "string", "要问模型什么？"));
        ALL = Collections.unmodifiableList(l);
    }

    public static List<Action> all() {
        return ALL;
    }

    public static Action get(String sign) {
        for (Action a : ALL) if (a.sign.equals(sign)) return a;
        return null;
    }

    public static boolean isInternal(String sign) {
        return INTERNAL.contains(sign);
    }

    /** 运行时问参数的那句话。 */
    public static String question(String sign, String param, Capability cap) {
        Action a = get(sign);
        Param p = a == null ? null : a.param(param);
        if (p != null && p.question != null) return p.question;
        String what = cap != null && cap.summary != null ? cap.summary : sign;
        return "「" + what + "」的 " + param + " 填什么？";
    }

    /** 内部动作的能力契约（门禁照常看副作用等级）；不是内部动作返回 null。 */
    public static Capability internalCap(String sign) {
        if (LLM_ASK.equals(sign)) {
            return new Capability.Builder(LLM_ASK, "问一句模型").domain("shortcut").category("shortcut").sideEffect(SideEffect.NONE)
                    .schema(new ParamsSchema().addString("prompt", true, null)).risk(0, false).executor("shortcut").build();
        }
        if (EVENT_ADD.equals(sign)) {
            return new Capability.Builder(EVENT_ADD, "在计划里加日程").domain("shortcut").category("shortcut").sideEffect(SideEffect.PERSISTENT_WRITE)
                    .schema(new ParamsSchema().addString("title", true, null).addString("date", false, "today").addString("time", false, ""))
                    .risk(1, false).executor("shortcut").build();
        }
        if (BRIEF_PLAY.equals(sign)) {
            return new Capability.Builder(BRIEF_PLAY, "播报晨间简报").domain("shortcut").category("shortcut").sideEffect(SideEffect.DEVICE_CONTROL)
                    .risk(0, false).executor("custom").build();   // root 广播给晨间播报 App → 上机版专属
        }
        if (WEATHER.equals(sign) || MEDIA_SAVE.equals(sign)) {
            Action a = get(sign);
            return new Capability.Builder(sign, a.label).domain("shortcut").category("shortcut").sideEffect(SideEffect.NONE).risk(0, false)
                    .executor("shortcut").build();
        }
        return null;
    }

    /** 上机版专属：清单标了 privileged，或执行器是 root shell（shell / custom）。 */
    public static boolean isPrivileged(String sign, Capability cap) {
        Action a = get(sign);
        if (a != null && a.privileged) return true;
        if (cap == null || cap.executorType == null) return false;
        return "shell".equals(cap.executorType) || "custom".equals(cap.executorType);
    }

    static final FeatureSpec ROOT_SPEC = FeatureSpec.status("shortcut.privileged", "快捷指令：系统开关等上机版动作", FeatureRegistry.G_EXEC,
            FeatureSpec.Status.SHIPPED, new DeviceCap[]{DeviceCap.ROOT, DeviceCap.EXEC_DAEMON}, DeviceCap.ROOT.alternative,
            "勿扰 / 音量 / 亮度经 root 执行守护");

    /**
     * 这一步在这台设备上为什么不能用；能用返回 null。
     *
     * @param cap             注册表 / 内部动作里查到的能力；null = 没有这个工具
     * @param profile         能力探测结果（null = 还没探，全按未确认 = 不置灰）
     * @param privilegedBuild {@link ShortcutBuild#PRIVILEGED_ACTIONS}
     */
    public static String unavailableReason(String sign, Capability cap, DeviceProfile profile, boolean privilegedBuild) {
        Action a = get(sign);
        if (a != null && a.notBuilt != null) return a.notBuilt;
        if (WEATHER.equals(sign)) {
            Probe p = weatherProbe;
            String why = p == null ? WEATHER_NOT_READY : p.reason();
            if (why != null) return "播报天气用分钟级降水：" + why;
        }
        if (cap == null) return "没有这个工具：" + sign;
        if (isPrivileged(sign, cap)) {
            if (!privilegedBuild) {
                // OneSay 开源版：不再由编译期开关一刀切，而是看用户选中的执行后端（root / Shizuku 插件）此刻是否可用。
                if (com.onesay.plugin.api.exec.ExecBackends.ready() != null) return null;
                String sel = com.onesay.plugin.api.exec.ExecBackends.selected();
                return sel.length() == 0 ? REASON_PRIVILEGED_OFF : com.onesay.plugin.api.exec.ExecBackends.unavailableReason();
            }
            FeatureAvailability fa = FeatureAvailability.evaluate(ROOT_SPEC, profile);
            if (!fa.switchEnabled()) return fa.reason();
        }
        return null;
    }

    /** ★ feat/rain-minutely：「播报天气」能不能用（宿主 RainPlugin.onInit 装上；没装 = 当作没配好）。 */
    public interface Probe {
        /** 不能用的原因；能用 = null。 */
        String reason();
    }

    public static volatile Probe weatherProbe;
    public static final String WEATHER_NOT_READY = "还没配好（缺和风天气 Key / Host）";

    /** 端口：按签名找能力（注册表 / 插件 / 内部动作）。 */
    public interface Lookup {
        Capability lookup(String sign);
    }

    /** 推给计划 App 的动作清单（{@link ShortcutModel#M_SHORTCUT_ACTIONS}）：每项带可用与否和原因。 */
    public static String catalogJson(Lookup lookup, DeviceProfile profile, boolean privilegedBuild) {
        JSONArray arr = new JSONArray();
        try {
            for (Action a : ALL) {
                Capability cap = lookup == null ? null : lookup.lookup(a.sign);
                String why = unavailableReason(a.sign, cap, profile, privilegedBuild);
                JSONArray ps = new JSONArray();
                for (Param p : a.params) ps.put(new JSONObject().put("name", p.name).put("label", p.label).put("type", p.type));
                arr.put(new JSONObject().put("sign", a.sign).put("label", a.label).put("group", a.group).put("privileged", isPrivileged(a.sign, cap))
                        .put("available", why == null).put("reason", why == null ? "" : why).put("params", ps));
            }
            return new JSONObject().put("format", ShortcutModel.FORMAT).put("actions", arr).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ 内部动作：加日程

    static final java.util.regex.Pattern DATE = java.util.regex.Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    static final java.util.regex.Pattern TIME = java.util.regex.Pattern.compile("(\\d{1,2})[:：](\\d{2})");

    /**
     * {@code plan.event.add} 的参数 → 计划 App events 行（title / start_at / end_at / all_day / source）。
     * date：today / tomorrow / 今天 / 明天 / 后天 / yyyy-MM-dd（缺省今天）；time：HH:mm（缺省 = 全天）。不合法抛 IllegalArgumentException。
     */
    public static java.util.Map<String, Object> eventRow(JSONObject args, long now, java.util.TimeZone tz) {
        String title = args == null ? "" : args.optString("title", "").trim();
        if (title.length() == 0) throw new IllegalArgumentException("日程要有标题");
        String date = args.optString("date", "").trim().toLowerCase(java.util.Locale.ROOT);
        String time = args.optString("time", "").trim();
        java.util.Calendar k = java.util.Calendar.getInstance(tz);
        k.setTimeInMillis(now);
        if (date.length() == 0 || date.equals("today") || date.equals("今天")) {
            // 今天
        } else if (date.equals("tomorrow") || date.equals("明天")) {
            k.add(java.util.Calendar.DAY_OF_MONTH, 1);
        } else if (date.equals("后天")) {
            k.add(java.util.Calendar.DAY_OF_MONTH, 2);
        } else {
            java.util.regex.Matcher m = DATE.matcher(date);
            if (!m.matches()) throw new IllegalArgumentException("日期看不懂：" + date);
            k.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)));
        }
        boolean allDay = time.length() == 0;
        int h = 0, mi = 0;
        if (!allDay) {
            java.util.regex.Matcher m = TIME.matcher(time);
            if (!m.matches()) throw new IllegalArgumentException("时间看不懂：" + time);
            h = Integer.parseInt(m.group(1));
            mi = Integer.parseInt(m.group(2));
            if (h > 23 || mi > 59) throw new IllegalArgumentException("时间不对：" + time);
        }
        k.set(java.util.Calendar.HOUR_OF_DAY, h);
        k.set(java.util.Calendar.MINUTE, mi);
        k.set(java.util.Calendar.SECOND, 0);
        k.set(java.util.Calendar.MILLISECOND, 0);
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        m.put("title", title.length() > 200 ? title.substring(0, 200) : title);
        m.put("start_at", k.getTimeInMillis());
        m.put("all_day", allDay ? 1 : 0);
        m.put("source", "bixbyui");
        return m;
    }
}
