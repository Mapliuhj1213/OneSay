package com.onesay.assistant.v2.diag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * [diag] #9「设置类自然语言 + 故障诊断」的<b>症状 → 设置键知识表</b>（纯 Java 数据，JVM 可测）。
 *
 * <h3>三张表</h3>
 * <ol>
 *   <li>{@link #KEYS}：<b>可读白名单</b>。每项 = 命名空间 + 键（或固定只读探针命令）+ 中文名 + 值格式 + 核实等级。
 *       诊断只会读这里列出的键；LLM 兜底也只能从这张表里挑（{@link DiagLlm}）。</li>
 *   <li>{@link #FIXES}：<b>可写白名单</b>。每项 = 动作芯片文案 + 写什么（键/目标值，或复用现有能力签名）+ 风险 + 是否可撤销。
 *       写入只会来自这里（{@link #checkWrite}）；LLM 永远拿不到写权限。</li>
 *   <li>{@link #SYMPTOMS}：症状。每项 = 触发短语（正则）+ 要读的键 + 诊断规则（值谓词 → 发现文案 → 建议修复）。</li>
 * </ol>
 *
 * <h3>核实等级（{@link Verify}）</h3>
 * <ul>
 *   <li>{@code AOSP}：Android 8 公开常量（Settings.System/Secure/Global）或 AOSP 固定输出格式，键名可信；</li>
 *   <li>{@code UNVERIFIED}：含义或输出格式是按 AOSP 源码推断的探针，<b>必须真机核实</b>。</li>
 * </ul>
 * 厂商各自命名、没有通用 settings 键的开关（息屏显示、防误触、护眼……）<b>不进这张表</b>：用 {@link FixKind#UI_FIND}
 * 芯片打开标准设置页，经无障碍读控件树找到它（{@link SettingsNav}），报告当前状态，你确认后才代点。
 * 读到 {@code null}（键不存在）时：发现文案如实说「这台机器上没读到」，<b>不会</b>生成写这个键的芯片，改给「打开设置页」芯片。
 */
public final class DiagKnowledge {

    private DiagKnowledge() {}

    /** ★ device-1102：单 App 断网症状 id（已知案例库：VPN / Tailscale）。 */
    public static final String SYMPTOM_APP_OFFLINE = "app_offline";

    // =====================================================================
    // 类型
    // =====================================================================

    /** 键名核实等级。 */
    public enum Verify { AOSP, UNVERIFIED }

    /** 值的人话格式。 */
    public enum Fmt { BOOL01, MS, BRIGHT, BMODE, ZEN, RINGER, LOCMODE, VOL15, FONT, WIFI_SLEEP, TEMP10, DBM, PCT, INWL, RAW, STAYON }

    /** 一个可读项：settings 键或固定只读探针。 */
    public static final class Key {
        /** 稳定 id：settings 键为 {@code ns/key}（如 {@code system/screen_off_timeout}），探针为 {@code probe/xxx}。 */
        public final String id;
        /** system / secure / global / probe。 */
        public final String ns;
        /** settings 键名；探针为 null。 */
        public final String key;
        /** 探针的固定只读命令（来自本表，绝不拼接外部输入）；settings 键为 null。 */
        public final String probeCmd;
        /** 探针输出里取值的正则（第 1 组）；null = 整段输出 trim。 */
        public final String probeRegex;
        public final String label;
        public final Fmt fmt;
        public final Verify verify;
        public final String note;

        Key(String id, String ns, String key, String probeCmd, String probeRegex, String label, Fmt fmt, Verify verify, String note) {
            this.id = id;
            this.ns = ns;
            this.key = key;
            this.probeCmd = probeCmd;
            this.probeRegex = probeRegex;
            this.label = label;
            this.fmt = fmt;
            this.verify = verify;
            this.note = note;
        }

        public boolean isProbe() {
            return probeCmd != null;
        }

        @Override public String toString() {
            return id;
        }
    }

    /** 修复动作的执行方式。 */
    public enum FixKind {
        /** settings put（可选经由现有能力 viaCap 执行正向写入）；撤销 = 写回旧值 / settings delete。 */
        SETTING,
        /** 现有能力（如 volume.ring.set）；旧值由 prevGetSign 读取，撤销 = 同一能力带旧值。 */
        CAP,
        /** dumpsys deviceidle whitelist +包名（包名来自本表）；撤销 = -包名（仅当原来不在白名单）。 */
        DEVICEIDLE,
        /** 打开设置页（现有 settings.page.* 能力）；不写设置、无需撤销。 */
        PAGE,
        /** 打开标准设置页（{@link #capSign} = Settings.ACTION_*），读控件树找「{@link #target}」并报告；代点要你每次确认（{@link SettingsNav}）。 */
        UI_FIND
    }

    /** 一个修复动作（动作芯片）。 */
    public static final class Fix {
        public final String id;
        /** 芯片文案（≤10 字）。 */
        public final String label;
        public final FixKind kind;
        // SETTING
        public final String keyId;
        public final String target;
        // SETTING 的正向写入可经由现有能力；CAP / PAGE 的能力签名
        public final String capSign;
        /** 现有能力的参数（JSON 文本，来自本表）。 */
        public final String capArgs;
        /** CAP：读取旧值的现有只读能力签名（如 volume.ring.get）；撤销时作为参数名 undoArg 的值。 */
        public final String prevGetSign;
        public final String undoArg;
        // DEVICEIDLE
        public final String pkg;
        /** 1 = 低（可逆的显示/声音偏好），2 = 中（影响通知/耗电/联网行为）。 */
        public final int risk;
        public final boolean reversible;
        public final String note;

        Fix(String id, String label, FixKind kind, String keyId, String target, String capSign, String capArgs,
            String prevGetSign, String undoArg, String pkg, int risk, boolean reversible, String note) {
            this.id = id;
            this.label = label;
            this.kind = kind;
            this.keyId = keyId;
            this.target = target;
            this.capSign = capSign;
            this.capArgs = capArgs;
            this.prevGetSign = prevGetSign;
            this.undoArg = undoArg;
            this.pkg = pkg;
            this.risk = risk;
            this.reversible = reversible;
            this.note = note;
        }

        public boolean writes() {
            return kind != FixKind.PAGE && kind != FixKind.UI_FIND;
        }

        @Override public String toString() {
            return id;
        }
    }

    /** 比较操作。 */
    public enum Op { EQ, NE, LT, LE, GT, GE, NULL, NOTNULL, CONTAINS }

    /** 一条值谓词。 */
    public static final class Cond {
        public final String keyId;
        public final Op op;
        public final String operand;

        Cond(String keyId, Op op, String operand) {
            this.keyId = keyId;
            this.op = op;
            this.operand = operand;
        }

        /** 对快照求值（快照里 null = 键不存在 / 未读到）。 */
        public boolean test(Map<String, String> snap) {
            String v = snap != null ? snap.get(keyId) : null;
            switch (op) {
                case NULL: return v == null;
                case NOTNULL: return v != null;
                case CONTAINS: return v != null && operand != null && v.contains(operand);
                default: break;
            }
            if (v == null) return false;
            if (op == Op.EQ || op == Op.NE) {
                boolean eq = valueEquals(v, operand);
                return op == Op.EQ ? eq : !eq;
            }
            Double a = num(v);
            Double b = num(operand);
            if (a == null || b == null) return false;
            switch (op) {
                case LT: return a < b;
                case LE: return a <= b;
                case GT: return a > b;
                case GE: return a >= b;
                default: return false;
            }
        }
    }

    /** 一条诊断规则：条件（全部成立）→ 发现文案 → 建议修复。 */
    public static final class Rule {
        public final List<Cond> conds;
        /** 文案：{v} = 第一个条件键的人话值；{v2} = 第二个条件键的人话值。 */
        public final String finding;
        public final List<String> fixIds;

        Rule(List<Cond> conds, String finding, List<String> fixIds) {
            this.conds = conds;
            this.finding = finding;
            this.fixIds = fixIds;
        }

        public boolean fires(Map<String, String> snap) {
            for (Cond c : conds) {
                if (!c.test(snap)) return false;
            }
            return true;
        }

        public String primaryKey() {
            return conds.isEmpty() ? null : conds.get(0).keyId;
        }
    }

    /** 一个症状。 */
    public static final class Symptom {
        public final String id;
        public final String title;
        /** 触发短语（在 {@code TriageDecider.normalize} 之后的小写无标点文本上 find）。 */
        public final List<Pattern> triggers;
        /** 未知症状兜底用的领域词（「为什么…屏幕…」命中领域但没命中触发短语时，按领域取要读的键）。 */
        public final List<String> domains;
        public final List<String> keyIds;
        public final List<Rule> rules;
        /** 规则都没给出芯片时的兜底芯片（一般是设置页）。 */
        public final String fallbackFix;
        /** 「看起来都正常」时追加的一句提示。 */
        public final String normalHint;

        Symptom(String id, String title, List<Pattern> triggers, List<String> domains, List<String> keyIds,
                List<Rule> rules, String fallbackFix, String normalHint) {
            this.id = id;
            this.title = title;
            this.triggers = triggers;
            this.domains = domains;
            this.keyIds = keyIds;
            this.rules = rules;
            this.fallbackFix = fallbackFix;
            this.normalHint = normalHint;
        }

        @Override public String toString() {
            return id;
        }
    }

    // =====================================================================
    // 表 1：可读白名单
    // =====================================================================

    public static final Map<String, Key> KEYS;
    // =====================================================================
    // 表 2：可写白名单（修复动作）
    // =====================================================================
    public static final Map<String, Fix> FIXES;
    /** 允许被巡检写入的 settings 键（= 所有 SETTING 修复用到的键）。 */
    private static final Map<String, Fix> WRITABLE;
    /** DEVICEIDLE 允许的包名。 */
    private static final List<String> IDLE_PKGS;
    // =====================================================================
    // 表 3：症状
    // =====================================================================
    public static final List<Symptom> SYMPTOMS;

    /** settings 键名 / 命名空间 / 包名的合法字符（防注入：本表任何进入命令行的字面量都必须满足）。 */
    static final Pattern SAFE_NAME = Pattern.compile("^[a-z][a-z0-9_.]{0,63}$");
    /** 写入值（目标值或撤销用的旧值）的合法字符。 */
    static final Pattern SAFE_VALUE = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

    static {
        LinkedHashMap<String, Key> k = new LinkedHashMap<String, Key>();
        // ---- 显示 ----
        put(k, s("system", "screen_off_timeout", "屏幕超时（自动熄屏）", Fmt.MS, Verify.AOSP, null));
        put(k, s("system", "screen_brightness", "屏幕亮度", Fmt.BRIGHT, Verify.AOSP, null));
        put(k, s("system", "screen_brightness_mode", "自动亮度", Fmt.BMODE, Verify.AOSP, null));
        put(k, s("system", "accelerometer_rotation", "自动旋转", Fmt.BOOL01, Verify.AOSP, null));
        put(k, s("system", "user_rotation", "锁定时的屏幕方向", Fmt.RAW, Verify.AOSP, "0=竖屏 1=横屏"));
        put(k, s("system", "font_scale", "字体大小", Fmt.FONT, Verify.AOSP, null));
        // ★ 11-04 C（「我的手机怎么一直亮屏」诊断不出）：充电时保持唤醒（开发者选项）/ 开发者选项总开关 / 亮屏锁
        //   （亮屏锁探针用单个 -E 模式：多个 -e 会被 exec 守护 v4 当成文件参数拒掉 GREP_FILE_ARG）
        put(k, s("global", "stay_on_while_plugged_in", "充电时保持唤醒（开发者选项）", Fmt.STAYON, Verify.AOSP,
                "位掩码：1 交流电 / 2 USB / 4 无线充电，7 = 任何充电方式都不熄屏"));
        put(k, s("global", "development_settings_enabled", "开发者选项", Fmt.BOOL01, Verify.AOSP, null));
        put(k, p("screen_wakelocks", "dumpsys power | grep -c -E 'SCREEN_BRIGHT_WAKE_LOCK|SCREEN_DIM_WAKE_LOCK|FULL_WAKE_LOCK'", "(\\d+)",
                "持有亮屏锁的数量（应用要求屏幕常亮）", Fmt.RAW, Verify.AOSP, null));
        put(k, p("screen_wakelock_tag", "dumpsys power | grep -m1 -E 'SCREEN_BRIGHT_WAKE_LOCK|SCREEN_DIM_WAKE_LOCK|FULL_WAKE_LOCK'",
                "WAKE_LOCK\\s+'([^']{1,60})'", "第一个亮屏锁的标签", Fmt.RAW, Verify.AOSP, null));
        put(k, s("secure", "doze_always_on", "息屏显示（AOD）", Fmt.BOOL01, Verify.AOSP, "AOSP 键（Android 8.1+）；ROM 不认时读到 null，改走设置页读屏"));
        put(k, s("secure", "doze_enabled", "通知亮屏（来通知时点亮屏幕）", Fmt.BOOL01, Verify.AOSP, "AOSP 键；ROM 不认时读到 null"));
        put(k, s("secure", "wake_gesture_enabled", "抬起唤醒", Fmt.BOOL01, Verify.AOSP, "AOSP 键；部分 ROM 上不存在（读到 null 则跳过）"));
        put(k, s("secure", "night_display_activated", "夜间模式（AOSP 夜间亮度）", Fmt.BOOL01, Verify.AOSP, "部分 ROM 用自己的护眼开关，这项可能为 null（改走设置页读屏）"));
        // ---- 声音 / 振动 / 勿扰 ----
        put(k, s("global", "zen_mode", "勿扰模式", Fmt.ZEN, Verify.AOSP, null));
        put(k, s("global", "mode_ringer", "情景模式（响铃/振动/静音）", Fmt.RINGER, Verify.AOSP, null));
        put(k, s("system", "volume_ring_speaker", "来电铃声音量", Fmt.VOL15, Verify.AOSP,
                "AOSP AudioService 持久化键（volume_<流>_<设备>）；写它不会改实际音量，只读"));
        put(k, s("system", "volume_music_speaker", "媒体音量", Fmt.VOL15, Verify.AOSP, "同上，只读"));
        put(k, s("system", "volume_notification_speaker", "通知音量", Fmt.VOL15, Verify.AOSP, "同上，只读"));
        put(k, s("system", "vibrate_when_ringing", "来电时同时振动", Fmt.BOOL01, Verify.AOSP, null));
        put(k, s("system", "haptic_feedback_enabled", "触摸振动反馈", Fmt.BOOL01, Verify.AOSP, null));
        // ---- 电池 / 后台 ----
        put(k, s("global", "low_power", "省电模式", Fmt.BOOL01, Verify.AOSP, "现有能力 battery.saver.on/off 已在用"));
        put(k, p("battery_level", "dumpsys battery | grep -m1 ' level:'", "level:\\s*(\\d+)", "电量", Fmt.PCT, Verify.AOSP, null));
        put(k, p("battery_temp", "dumpsys battery | grep -m1 temperature", "temperature:\\s*(\\d+)", "电池温度", Fmt.TEMP10, Verify.AOSP, null));
        put(k, p("idle_wechat", "dumpsys deviceidle whitelist | grep -c ',com.tencent.mm,'", "(\\d+)", "微信是否在电池优化白名单", Fmt.INWL, Verify.AOSP, null));
        put(k, p("idle_qq", "dumpsys deviceidle whitelist | grep -c ',com.tencent.mobileqq,'", "(\\d+)", "QQ 是否在电池优化白名单", Fmt.INWL, Verify.AOSP, null));
        put(k, p("wechat_installed", "pm path com.tencent.mm | grep -c package:", "(\\d+)", "是否装了微信", Fmt.RAW, Verify.AOSP, null));
        put(k, p("restrict_bg", "cmd netpolicy get restrict-background", "(enabled|disabled)", "流量节省（限制后台数据）", Fmt.RAW, Verify.UNVERIFIED,
                "cmd netpolicy 的输出格式按 AOSP 源码推断，需真机核实"));
        // ---- 网络 / 定位 / 时间 ----
        put(k, s("global", "wifi_on", "WLAN", Fmt.BOOL01, Verify.AOSP, null));
        put(k, s("global", "wifi_sleep_policy", "休眠时保持 WLAN 连接", Fmt.WIFI_SLEEP, Verify.AOSP, "Android 8 上可能已不生效（仅作参考）"));
        put(k, s("global", "wifi_scan_always_enabled", "WLAN 随时扫描（辅助定位）", Fmt.BOOL01, Verify.AOSP, null));
        put(k, p("wifi_rssi", "dumpsys wifi | grep -m1 -o 'RSSI: -*[0-9]*'", "RSSI:\\s*(-?\\d+)", "WLAN 信号强度", Fmt.DBM, Verify.AOSP, null));
        put(k, s("global", "bluetooth_on", "蓝牙", Fmt.BOOL01, Verify.AOSP, null));
        put(k, s("secure", "location_mode", "定位模式", Fmt.LOCMODE, Verify.AOSP, "现有能力 connectivity.gps.on 写 3"));
        put(k, s("global", "auto_time", "自动同步时间", Fmt.BOOL01, Verify.AOSP, "现有能力 settings.autotime.on/off"));
        put(k, s("global", "auto_time_zone", "自动同步时区", Fmt.BOOL01, Verify.AOSP, null));
        // ---- ★ device-1102：单 App 断网（典型案例：Tailscale/VPN 让某个应用商店「无网络可用」，关掉 VPN 即恢复）----
        put(k, p("vpn_tun", "ip addr show | grep -c tun", "(\\d+)", "VPN 隧道网卡（tun）数量", Fmt.RAW, Verify.AOSP,
                "VPN（含 Tailscale）运行时会有 tun0 一类网卡"));
        put(k, p("vpn_active", "dumpsys connectivity | grep -c 'type: VPN'", "(\\d+)", "系统里活动的 VPN 网络", Fmt.RAW, Verify.UNVERIFIED,
                "dumpsys connectivity 的 NetworkAgentInfo 行格式按 AOSP 推断，需真机核实"));
        put(k, p("tailscale_installed", "pm path com.tailscale.ipn | grep -c package:", "(\\d+)", "是否装了 Tailscale", Fmt.RAW, Verify.AOSP, null));
        put(k, s("global", "private_dns_mode", "私人 DNS", Fmt.RAW, Verify.AOSP, "Android 9+ 才有；Android 8 读到 null 属正常"));
        put(k, s("global", "http_proxy", "全局 HTTP 代理", Fmt.RAW, Verify.AOSP, "只读；守护禁止写"));
        put(k, s("global", "airplane_mode_on", "飞行模式", Fmt.BOOL01, Verify.AOSP, null));
        KEYS = Collections.unmodifiableMap(k);

        LinkedHashMap<String, Fix> f = new LinkedHashMap<String, Fix>();
        // 显示
        putFix(f, setting("timeout_2m", "熄屏改为 2 分钟", "system/screen_off_timeout", "120000", "settings.screentimeout.set", "{\"seconds\":120}", 1, null));
        putFix(f, setting("timeout_5m", "熄屏改为 5 分钟", "system/screen_off_timeout", "300000", "settings.screentimeout.set", "{\"seconds\":300}", 1, null));
        putFix(f, setting("timeout_30s", "熄屏改为 30 秒", "system/screen_off_timeout", "30000", "settings.screentimeout.set", "{\"seconds\":30}", 1, null));
        putFix(f, setting("stay_on_off", "关闭「充电时保持唤醒」", "global/stay_on_while_plugged_in", "0", null, null, 1,
                "开发者选项里的开关；关掉后充电时也按屏幕超时熄屏"));
        putFix(f, setting("aod_off", "关闭息屏显示", "secure/doze_always_on", "0", "settings.aod.off", "{}", 1, null));
        putFix(f, setting("ambient_off", "关闭通知亮屏", "secure/doze_enabled", "0", "settings.ambient.off", "{}", 1, null));
        putFix(f, setting("lift_wake_off", "关闭抬起唤醒", "secure/wake_gesture_enabled", "0", null, null, 1, null));
        putFix(f, setting("rotation_on", "开启自动旋转", "system/accelerometer_rotation", "1", "settings.rotation.on", "{}", 1, null));
        putFix(f, setting("font_l", "字体调到大号", "system/font_scale", "1.1", null, null, 1, null));
        putFix(f, setting("font_xl", "字体调到超大", "system/font_scale", "1.3", null, null, 1, null));
        putFix(f, setting("autobright_on", "开启自动亮度", "system/screen_brightness_mode", "1", "settings.autobrightness.on", "{}", 1, null));
        putFix(f, setting("autobright_off", "关闭自动亮度", "system/screen_brightness_mode", "0", "settings.autobrightness.off", "{}", 1, null));
        putFix(f, setting("bright_40", "亮度降到 40", "system/screen_brightness", "40", "settings.brightness.set", "{\"level\":40}", 1, null));
        // 声音 / 振动
        putFix(f, cap("ring_vol_12", "铃声调到 12/15", "system/volume_ring_speaker", "12", "volume.ring.set", "{\"level\":12}", "volume.ring.get", "level", 1, null));
        putFix(f, cap("media_vol_10", "媒体音量调到 10", "system/volume_music_speaker", "10", "volume.media.set", "{\"level\":10}", "volume.media.get", "level", 1, null));
        putFix(f, setting("ring_vibrate_off", "来电不再同时振动", "system/vibrate_when_ringing", "0", null, null, 1, null));
        putFix(f, setting("haptic_off", "关闭触摸振动", "system/haptic_feedback_enabled", "0", "settings.haptic.off", "{}", 1, null));
        // 电池 / 后台 / 通知
        putFix(f, setting("saver_on", "开启省电模式", "global/low_power", "1", "battery.saver.on", "{}", 2, null));
        putFix(f, setting("saver_off", "关闭省电模式", "global/low_power", "0", "battery.saver.off", "{}", 2, null));
        putFix(f, idle("wechat_unrestrict", "微信不受电池优化", "com.tencent.mm", "probe/idle_wechat", 2));
        putFix(f, idle("qq_unrestrict", "QQ 不受电池优化", "com.tencent.mobileqq", "probe/idle_qq", 2));
        // 网络 / 定位 / 时间
        putFix(f, setting("wifi_keep_on", "休眠时保持 WLAN", "global/wifi_sleep_policy", "2", null, null, 2, "Android 8 上可能不生效"));
        putFix(f, setting("location_high", "定位改为高精度", "secure/location_mode", "3", "connectivity.gps.on", "{}", 2, null));
        putFix(f, setting("autotime_on", "开启自动对时", "global/auto_time", "1", "settings.autotime.on", "{}", 1, null));
        putFix(f, setting("autotz_on", "开启自动时区", "global/auto_time_zone", "1", null, null, 1, null));
        // 设置页（只打开界面，不写设置）
        putFix(f, page("page_display", "打开显示设置", "settings.page.display"));
        putFix(f, page("page_sound", "打开声音设置", "settings.page.sound"));
        putFix(f, page("page_notification", "打开通知设置", "settings.page.notification"));
        putFix(f, page("page_battery", "打开电池设置", "settings.page.battery"));
        putFix(f, page("page_wifi", "打开 WLAN 设置", "settings.page.wifi"));
        putFix(f, page("page_hotspot", "打开热点所在的连接设置", "settings.page.hotspot"));   // ★ int/1102 审计（FastCaps）
        putFix(f, page("page_location", "打开定位设置", "settings.page.location"));
        putFix(f, page("page_datetime", "打开日期时间设置", "settings.page.datetime"));
        putFix(f, page("page_accessibility", "打开辅助功能", "settings.page.accessibility"));
        // 厂商各自命名的设置项（没有通用 settings 键）：经无障碍读设置页控件树找到它、报告当前状态，你确认后再代点（SettingsNav）
        putFix(f, uiFind("find_smart_stay", "查看「注视不熄屏」", "注视时保持亮屏", "android.settings.DISPLAY_SETTINGS"));
        putFix(f, uiFind("find_pocket", "查看「防误触」", "防误触", "android.settings.DISPLAY_SETTINGS"));
        putFix(f, uiFind("find_aod", "查看「息屏显示」", "息屏显示", "android.settings.DISPLAY_SETTINGS"));
        putFix(f, uiFind("find_edge_light", "查看「通知亮屏」", "通知亮屏", "android.settings.NOTIFICATION_SETTINGS"));
        putFix(f, uiFind("find_eye_comfort", "查看「护眼模式」", "护眼模式", "android.settings.DISPLAY_SETTINGS"));
        putFix(f, uiFind("find_pickup_alert", "查看「拿起提醒」", "拿起手机时振动提醒", "android.settings.SOUND_SETTINGS"));
        FIXES = Collections.unmodifiableMap(f);

        LinkedHashMap<String, Fix> w = new LinkedHashMap<String, Fix>();
        List<String> pk = new ArrayList<String>();
        for (Fix x : FIXES.values()) {
            if (x.kind == FixKind.SETTING) w.put(x.keyId, x);
            if (x.kind == FixKind.DEVICEIDLE) pk.add(x.pkg);
        }
        WRITABLE = Collections.unmodifiableMap(w);
        IDLE_PKGS = Collections.unmodifiableList(pk);

        List<Symptom> sy = new ArrayList<Symptom>();
        // 顺序 = 匹配优先级（具体在前、笼统在后）
        // ★ 11-04 C：屏幕一直亮着不熄（≠ 口袋里自己点亮）
        sy.add(new Symptom("screen_stays_on", "屏幕一直亮着不熄",
                pats("(屏幕|手机|屏).{0,6}(一直|老是|总是|始终|怎么都|就是|常常|整天|一晚上)(都)?(亮着|亮屏|开着|不灭|不熄|不息屏|不黑)",
                        "(屏幕|手机|屏).{0,6}(不熄屏|不息屏|不灭屏|不黑屏|不锁屏|不会熄|不会灭|不会自己熄|不自动熄|不自动锁|不会自动锁|熄不了|灭不了|关不掉|常亮)",
                        "^(怎么|为什么|为啥)?(一直亮屏|屏幕常亮|老亮着屏|一直亮着)", "(充电|插着电|充着电).{0,6}(屏幕|屏).{0,4}(一直|不)(亮|熄|灭)"),
                list("亮屏", "熄屏", "常亮"),
                list("global/stay_on_while_plugged_in", "global/development_settings_enabled", "system/screen_off_timeout",
                        "probe/screen_wakelocks", "probe/screen_wakelock_tag", "system/screen_brightness_mode"),
                rules(
                        rule("开发者选项里的「充电时保持唤醒」开着（当前：{v}）：只要插着充电器，屏幕就不会自动熄灭。", list("stay_on_off"),
                                c("global/stay_on_while_plugged_in", Op.NE, "0"), c("global/stay_on_while_plugged_in", Op.NOTNULL, null)),
                        rule("屏幕超时设成了 {v}，不碰屏幕要这么久才熄。", list("timeout_2m", "timeout_30s"),
                                c("system/screen_off_timeout", Op.GE, "600000")),
                        rule("有应用正要求屏幕常亮（亮屏锁 {v} 个，视频 / 导航 / 游戏类常见），关掉那个应用屏幕就会按时熄灭。", list("page_display"),
                                c("probe/screen_wakelocks", Op.GT, "0"))),
                "page_display", "正在看视频、导航或玩游戏时，这些应用本来就会让屏幕常亮；有的手机还有「注视时保持亮屏」，看着屏幕时不会熄，可以让我去设置里查看。"));
        sy.add(new Symptom("pocket_screen_on", "口袋里屏幕总亮 / 误触",
                pats("(口袋|兜|包包?|裤兜)里.{0,8}(亮|误触|点亮|自己开|乱点|乱按|打电话|拨号)",
                        "(放|装|揣)(进|在)?(口袋|兜|包).{0,6}(亮|误触|乱点)", "误触", "(屏幕|手机).{0,4}(自己|自动|莫名其妙|老是|总是).{0,3}(点亮|亮起来|亮)(?!度)"),
                list("口袋", "误触", "亮屏"),
                list("secure/doze_always_on", "secure/doze_enabled", "secure/wake_gesture_enabled"),
                rules(
                        rule("息屏显示（AOD）开着（当前：{v}），屏幕上会一直有时钟等内容，口袋里看着像「亮着」。", list("aod_off"),
                                c("secure/doze_always_on", Op.EQ, "1")),
                        rule("通知亮屏开着（当前：{v}），来通知时会点亮屏幕。", list("ambient_off"),
                                c("secure/doze_enabled", Op.EQ, "1")),
                        rule("抬起唤醒开着（当前：{v}），走路晃动时可能被当成「拿起手机」而亮屏。", list("lift_wake_off"),
                                c("secure/wake_gesture_enabled", Op.EQ, "1"))),
                "find_pocket", "也可能是来电/通知亮屏，属于正常行为；防误触、息屏显示、通知亮屏这类开关各家叫法不同，我可以去设置页里帮你找。"));
        sy.add(new Symptom("screen_off_fast", "屏幕老是自己熄灭",
                pats("(屏幕|手机).{0,4}(老是|总是|经常|一直|动不动|自己|没多久|一会儿就?|很快就?).{0,3}(熄灭|熄屏|息屏|灭屏|黑屏|暗了|黑了|锁屏|灭了|熄了|暗下去)",
                        "(熄屏|息屏|灭屏|黑屏|锁屏).{0,3}(太快|好快|很快|太早)",
                        "(看|盯着|读|用).{0,8}(时|的时候|时候).{0,4}(别|不要)(让它|让屏幕|让手机)?(熄屏|息屏|灭屏|黑屏|暗|锁屏|熄灭)",
                        "(别|不要)(让它|让屏幕|让手机)(自动)?(熄屏|息屏|灭屏|黑屏|熄灭)"),
                list("熄屏", "息屏", "灭屏", "黑屏"),
                list("system/screen_off_timeout"),
                rules(
                        rule("屏幕超时只有 {v}，不碰屏幕这么久就会自动熄屏。", list("timeout_2m", "timeout_5m"),
                                c("system/screen_off_timeout", Op.LE, "30000")),
                        rule("屏幕超时是 {v}，看长文章时可能还是不够。", list("timeout_5m"),
                                c("system/screen_off_timeout", Op.GT, "30000"), c("system/screen_off_timeout", Op.LT, "300000"))),
                "find_smart_stay", "如果开着省电模式，系统也可能把超时压短。"));
        sy.add(new Symptom("battery_drain", "耗电快",
                pats("耗电.{0,3}(好|很|太|特别|有点|贼)?(快|厉害|严重|多)", "掉电.{0,2}(好|很|太|特别|有点)?快", "(电量|电).{0,2}掉得.{0,2}(好|很|太|特别)?快",
                        "电(池)?.{0,2}(不经用|不耐用|撑不住|不够用)", "(手机|电池).{0,3}(发烫|发热|好烫|很烫)", "费电"),
                list("耗电", "电池", "掉电", "发烫", "发热"),
                list("system/screen_brightness_mode", "system/screen_brightness", "system/screen_off_timeout", "secure/doze_always_on",
                        "global/low_power", "secure/location_mode", "global/wifi_scan_always_enabled", "probe/battery_level", "probe/battery_temp"),
                rules(
                        rule("电量只剩 {v}，省电模式却是关着的（当前：{v2}）。", list("saver_on"),
                                c("probe/battery_level", Op.LT, "30"), c("global/low_power", Op.EQ, "0")),
                        rule("屏幕是手动亮度且偏高（当前：{v2}），屏幕是最耗电的部件。", list("autobright_on"),
                                c("system/screen_brightness_mode", Op.EQ, "0"), c("system/screen_brightness", Op.GE, "180")),
                        rule("屏幕超时设成了 {v}，屏幕亮着的时间偏长。", list("timeout_30s"),
                                c("system/screen_off_timeout", Op.GE, "120000")),
                        rule("息屏显示（AOD）开着（当前：{v}），每天会多耗一些电。", list("aod_off"),
                                c("secure/doze_always_on", Op.EQ, "1")),
                        rule("电池温度偏高（当前：{v}），发热时耗电会明显加快，先别边充边玩。", list(),
                                c("probe/battery_temp", Op.GE, "400")),
                        rule("定位是高精度模式（当前：{v}），常驻定位的 App 会更费电。", list(),
                                c("secure/location_mode", Op.EQ, "3")),
                        rule("省电模式没开（当前：{v}），外出电不够用时可以打开。", list("saver_on"),
                                c("global/low_power", Op.EQ, "0"), c("probe/battery_level", Op.GE, "30"))),
                "page_battery", "具体是哪个 App 耗电，可以在电池设置里看排行。"));
        sy.add(new Symptom("notification_delay", "通知收不到 / 消息延迟",
                pats("(通知|消息|微信|qq|短信).{0,6}(收不到|没收到|接收不到|延迟|很慢|好慢|晚到|不及时|不提醒|没有提醒|没提醒|不提示|没提示|没反应|没动静|不弹|不响|老是晚|总是晚)",
                        "(收不到|接不到).{0,4}(通知|消息|微信)", "(微信|qq).{0,4}(不来消息|没提示|没通知)"),
                list("通知", "消息", "微信"),
                list("global/zen_mode", "global/low_power", "probe/wechat_installed", "probe/idle_wechat", "probe/restrict_bg"),
                rules(
                        rule("勿扰模式开着（当前：{v}），通知会被静音或不弹出。", list("page_notification"),
                                c("global/zen_mode", Op.NE, "0")),
                        rule("微信不在电池优化白名单里（当前：{v}），锁屏一会儿后系统会让它进入休眠，消息就会延迟到亮屏才到。", list("wechat_unrestrict"),
                                c("probe/wechat_installed", Op.GE, "1"), c("probe/idle_wechat", Op.EQ, "0")),
                        rule("省电模式开着（当前：{v}），后台联网会受限制。", list("saver_off"),
                                c("global/low_power", Op.EQ, "1")),
                        rule("流量节省（限制后台数据）开着，后台 App 用移动数据收不到消息。", list("page_notification"),
                                c("probe/restrict_bg", Op.EQ, "enabled"))),
                "page_notification", "也可能是这个 App 自己的通知开关被关了，可以在「通知」设置里按 App 查看。"));
        sy.add(new Symptom("volume_low", "声音太小 / 来电没声音",
                pats("(声音|音量).{0,3}(太|好|很|有点|特别)小", "太小声", "小声了", "来电.{0,4}(没|听不到|没有|听不见)(声音|铃声|响)?",
                        "(铃声|来电铃声).{0,3}(太|好|很|有点)?小", "听不(到|见)(来电|铃声|电话)", "(电话|手机).{0,4}(来了|响了|来电)?.{0,3}(没|不)(声音|响)",
                        "(手机|电话).{0,3}(不响|没声音|没声了)",
                        // R5：「别人打电话我听不到」「有人打过来我都听不见」——来电在前、听不到在后（排除通话中听不到对方）
                        "(打电话|打来电话|来电话|打过来|打给我|打我电话|打我手机).{0,6}听不(到|见)(?!对方|对面)"),
                list("声音", "铃声", "来电", "音量"),
                list("global/mode_ringer", "global/zen_mode", "system/volume_ring_speaker", "system/volume_music_speaker"),
                rules(
                        rule("手机现在是{v}，来电不会响铃。", list("ring_vol_12"),
                                c("global/mode_ringer", Op.NE, "2")),
                        rule("勿扰模式开着（当前：{v}），来电和通知可能被静音。", list("page_notification"),
                                c("global/zen_mode", Op.NE, "0")),
                        rule("来电铃声音量偏低（当前：{v}）。", list("ring_vol_12"),
                                c("system/volume_ring_speaker", Op.LE, "6")),
                        rule("媒体音量偏低（当前：{v}），放视频/音乐会很小声。", list("media_vol_10"),
                                c("system/volume_music_speaker", Op.LE, "5"))),
                "page_sound", "如果连着蓝牙耳机，声音会从耳机出。"));
        sy.add(new Symptom("vibrate_always", "一直振动",
                pats("(为什么|为啥|老是|总是|一直|动不动|莫名其妙|经常).{0,6}(振动|震动|在震|在振|震个不停|振个不停)",
                        "(振动|震动).{0,3}(停不下来|个不停|不停|太频繁|太多)", "(老是|总是|一直)在?(振|震)"),
                list("振动", "震动"),
                list("global/mode_ringer", "system/haptic_feedback_enabled", "system/vibrate_when_ringing"),
                rules(
                        rule("手机现在是{v}，所有来电和通知都会用振动代替响铃。", list("ring_vol_12"),
                                c("global/mode_ringer", Op.EQ, "1")),
                        rule("触摸振动反馈开着（当前：{v}），每次点按键盘、按键都会轻振。", list("haptic_off"),
                                c("system/haptic_feedback_enabled", Op.EQ, "1")),
                        rule("来电时同时振动开着（当前：{v}）。", list("ring_vibrate_off"),
                                c("system/vibrate_when_ringing", Op.EQ, "1"))),
                "page_sound", "如果是某个 App 一直在振，多半是它自己的通知振动设置。"));
        sy.add(new Symptom("dnd_check", "勿扰是不是开着",
                pats("(勿扰|免打扰).{0,6}(是不是|开着吗|开着没|开了吗|开了没|有没有开|打开了吗|开没开|还开着)", "(是不是|是否).{0,4}(开了?|开着|打开了)?.{0,2}(勿扰|免打扰)"),
                list("勿扰", "免打扰"),
                list("global/zen_mode", "global/mode_ringer"),
                rules(
                        rule("勿扰模式开着：{v}。", list("page_notification"),
                                c("global/zen_mode", Op.NE, "0"), c("global/zen_mode", Op.NOTNULL, null)),
                        rule("勿扰模式是关着的（当前：{v}）。", list(),
                                c("global/zen_mode", Op.EQ, "0")),
                        rule("另外手机是{v}。", list("ring_vol_12"),
                                c("global/mode_ringer", Op.NE, "2"))),
                "page_notification", "Android 8 上外部程序改勿扰会被系统改回，所以这里只给设置页入口。"));
        // ★ device-1102：单个 App 显示「无网络 / 连不上」——先排除 VPN（Tailscale）再看全机网络。放在 wifi_drops 之前（更具体）。
        sy.add(new Symptom(SYMPTOM_APP_OFFLINE, "某个 App 显示无网络",
                pats("(商店|应用商店|app|应用|软件|微信|qq|浏览器|支付宝|淘宝|抖音|游戏).{0,10}(无网络|没网|没有网|没有网络|网络不可用|无法连接|连不上|上不了网|网络错误|网络异常|加载不出|连接失败)",
                        "(显示|提示|说|一直|老是|总是).{0,4}(无网络可用|网络不可用|没有网络连接|无网络连接|请检查网络)",
                        "(别的|其他|其它)(app|应用|软件).{0,6}(都)?(正常|能上网|可以上网|没问题)"),
                list("无网络", "没网", "连不上", "网络不可用"),
                list("probe/vpn_tun", "probe/vpn_active", "probe/tailscale_installed", "global/private_dns_mode", "global/http_proxy",
                        "global/airplane_mode_on", "global/wifi_on", "probe/restrict_bg", "global/auto_time"),
                rules(
                        rule("手机上有 VPN 隧道在跑（tun 网卡 {v} 个）。只有个别 App 显示无网络、别的 App 正常时，最常见的原因就是 VPN："
                                        + "这个 App 的流量被转进了 VPN，而 VPN 那头不给它放行。先临时关掉 VPN 再打开这个 App 试试；"
                                        + "确认是它之后，在 Tailscale 的「排除的应用（Excluded apps / 分应用代理）」里把这个 App 加进去，就不用每次关 VPN。",
                                list(), c("probe/vpn_tun", Op.GE, "1")),
                        rule("系统里有活动的 VPN 网络（{v} 条）。单个 App 断网时先关掉 VPN 试一次；是它的话把这个 App 加进 VPN 的排除列表。",
                                list(), c("probe/vpn_active", Op.GE, "1"), c("probe/vpn_tun", Op.LT, "1")),
                        rule("装了 Tailscale（当前没看到隧道在跑）。如果问题时有时无，留意是不是 Tailscale 连上的时候才出现。",
                                list(), c("probe/tailscale_installed", Op.GE, "1"), c("probe/vpn_tun", Op.LT, "1")),
                        rule("设了全局 HTTP 代理（{v}），只走代理的 App 可能连不上。", list("page_wifi"),
                                c("global/http_proxy", Op.CONTAINS, ":"), c("global/http_proxy", Op.NE, ":0")),
                        rule("私人 DNS 是「{v}」：指定的 DNS 服务器连不上时，部分 App 会显示无网络。", list("page_wifi"),
                                c("global/private_dns_mode", Op.EQ, "hostname")),
                        rule("飞行模式开着（当前：{v}）。", list(), c("global/airplane_mode_on", Op.EQ, "1")),
                        rule("流量节省（限制后台数据）开着：用移动数据时，没加白名单的 App 会连不上。", list("page_wifi"),
                                c("probe/restrict_bg", Op.EQ, "enabled")),
                        rule("自动同步时间是关着的（当前：{v}）：手机时间不准时，商店这类 App 的安全连接会失败，看起来像没网。", list("autotime_on"),
                                c("global/auto_time", Op.EQ, "0"))),
                "page_wifi", "如果别的 App 也上不了网，是整机网络的问题；只有它不行，再看它的账号登录、清一下它的缓存，或搜一下这个报错。"));
        sy.add(new Symptom("wifi_drops", "Wi-Fi 老断",
                pats("(wifi|wlan|无线网|无线网络|网络).{0,6}(老是|总是|经常|一直|动不动)?.{0,2}(断|掉线|断开|断线|连不稳|不稳定|自己关)",
                        "(wifi|wlan|无线网).{0,4}(信号)?(差|弱|不好)"),
                list("wifi", "wlan", "无线网"),
                list("global/wifi_on", "global/wifi_sleep_policy", "probe/wifi_rssi", "global/low_power"),
                rules(
                        rule("WLAN 现在是关着的（当前：{v}）。", list("page_wifi"),
                                c("global/wifi_on", Op.EQ, "0")),
                        rule("信号偏弱（当前：{v}），离路由器远或隔墙多时容易断。", list(),
                                c("probe/wifi_rssi", Op.LE, "-75")),
                        rule("休眠时 WLAN 不保持连接（当前：{v}），锁屏后可能断开。", list("wifi_keep_on"),
                                c("global/wifi_sleep_policy", Op.NE, "2"), c("global/wifi_sleep_policy", Op.NOTNULL, null)),
                        rule("省电模式开着（当前：{v}），锁屏后的联网会受限制。", list("saver_off"),
                                c("global/low_power", Op.EQ, "1"))),
                "page_wifi", "有的手机会在 Wi-Fi 差时自动切到移动数据（「智能网络切换」一类），可以在 WLAN 设置里看看。"));
        // ★ int/1102 审计：「热点开了电脑连不上」走设备诊断（读飞行 / 省电 / VPN / WLAN 状态），不再进 GUI 点界面
        sy.add(new Symptom("hotspot_no_client", "开了热点别的设备连不上 / 没网",
                pats("热点.{0,10}(连不上|连不了|搜不到|找不到|用不了|没网|上不了网|没有网|连接不上|不能用)",
                        "(电脑|平板|ipad|笔记本|手机|设备).{0,6}(连不上|连不了|搜不到|找不到).{0,4}(热点|我的热点|手机热点)",
                        "(共享|分享)(网络|流量).{0,6}(不行|没网|连不上|用不了)"),
                list("热点", "共享网络"),
                list("global/airplane_mode_on", "global/low_power", "probe/vpn_tun", "global/wifi_on"),
                rules(
                        rule("飞行模式开着（当前：{v}）：移动数据断了，热点能连上也没网。", list(),
                                c("global/airplane_mode_on", Op.EQ, "1")),
                        rule("省电模式开着（当前：{v}）：部分机型省电时会限制或自动关闭热点。", list("saver_off"),
                                c("global/low_power", Op.EQ, "1")),
                        rule("手机上有 VPN 隧道在跑（tun 网卡 {v} 个）：有些 VPN 不转发热点设备的流量，表现为连上了没网。先临时关掉 VPN 再试。",
                                list(), c("probe/vpn_tun", Op.GE, "1"))),
                "page_hotspot", "手机这边看起来正常。电脑上：确认连的是本机热点名、密码输对；电脑「忘记此网络」后重连；"
                        + "老电脑不认 5 GHz 时把热点频段改成 2.4 GHz；热点设置里看是否开了「仅允许的设备」。都正常还没网，多半是套餐限制了热点共享流量。"));
        sy.add(new Symptom("rotation", "自动旋转不灵",
                pats("(自动)?旋转.{0,4}(不灵|不好使|没反应|不管用|失灵|不转|转不了|不起作用|没用)",
                        "(屏幕|画面|视频).{0,4}(不会?(自动)?(旋转|转|横过来)|转不过来|横不过来|不能横)", "横屏.{0,3}(不了|不过来|不行)"),
                list("旋转", "横屏", "竖屏"),
                list("system/accelerometer_rotation", "system/user_rotation"),
                rules(
                        rule("自动旋转是关着的（当前：{v}），屏幕被锁定了方向。", list("rotation_on"),
                                c("system/accelerometer_rotation", Op.EQ, "0")),
                        rule("自动旋转是开着的（当前：{v}）：如果某个 App 还是不转，多半是它只支持竖屏。", list(),
                                c("system/accelerometer_rotation", Op.EQ, "1"))),
                "page_display", null));
        sy.add(new Symptom("font_small", "字太小看不清",
                pats("字(体|号)?.{0,2}(太|好|很|有点|有些|特别)小", "字.{0,4}看不清", "看不清.{0,2}字"),
                list("字体", "字号"),
                list("system/font_scale"),
                rules(
                        rule("字体大小是{v}。", list("font_l", "font_xl"),
                                c("system/font_scale", Op.LT, "1.1")),
                        rule("字体已经是{v}，再大可以用辅助功能里的「放大」。", list("font_xl"),
                                c("system/font_scale", Op.GE, "1.1"), c("system/font_scale", Op.LT, "1.3"))),
                "page_accessibility", null));
        sy.add(new Symptom("night_glare", "晚上屏幕太刺眼",
                pats("(晚上|夜里|夜间|半夜|睡前|关灯|黑暗).{0,8}(刺眼|晃眼|太亮|好亮|亮瞎|眼睛疼|眼睛难受|眼睛累)", "屏幕.{0,3}(刺眼|晃眼)", "刺眼"),
                list("刺眼", "晃眼"),
                list("system/screen_brightness_mode", "system/screen_brightness", "secure/night_display_activated"),
                rules(
                        rule("现在是手动亮度（{v}），环境变暗时不会自动降亮度。", list("autobright_on", "bright_40"),
                                c("system/screen_brightness_mode", Op.EQ, "0")),
                        rule("亮度偏高（当前：{v}）。", list("bright_40"),
                                c("system/screen_brightness", Op.GE, "120"))),
                "find_eye_comfort", "护眼 / 夜间模式各家叫法不同，我可以去显示设置里帮你找。"));
        sy.add(new Symptom("brightness_jumps", "亮度老是自己变",
                pats("亮度.{0,4}(老是|总是|一直|经常|自己|自动).{0,2}(变|变暗|变亮|乱跳|跳|调)", "(屏幕)?(忽明忽暗|一会亮一会暗|一会儿亮一会儿暗)", "(屏幕|亮度).{0,2}自己(变|调)"),
                list("亮度"),
                list("system/screen_brightness_mode", "global/low_power"),
                rules(
                        rule("自动亮度开着（当前：{v}），会按环境光自动调亮调暗。", list("autobright_off"),
                                c("system/screen_brightness_mode", Op.EQ, "1")),
                        rule("省电模式开着（当前：{v}），会压低亮度上限。", list("saver_off"),
                                c("global/low_power", Op.EQ, "1"))),
                "page_display", null));
        sy.add(new Symptom("time_wrong", "时间不准",
                pats("时间.{0,3}(不准|不对|错了|慢了|快了|有偏差)", "(时钟|钟|手机时间).{0,3}(不准|慢了|快了)", "时区.{0,3}(不对|错了)"),
                list("时间不准", "时区"),
                list("global/auto_time", "global/auto_time_zone"),
                rules(
                        rule("自动同步时间是关着的（当前：{v}）。", list("autotime_on"),
                                c("global/auto_time", Op.EQ, "0")),
                        rule("自动同步时区是关着的（当前：{v}）。", list("autotz_on"),
                                c("global/auto_time_zone", Op.EQ, "0"))),
                "page_datetime", null));
        sy.add(new Symptom("location_bad", "定位不准",
                pats("定位.{0,4}(不准|飘|偏|漂移|很慢|好慢|慢|找不到|乱跳|不对)", "(导航|地图).{0,4}(定位)?(不准|飘|偏|乱跳)", "gps.{0,3}(不准|信号差|搜不到|没信号)"),
                list("定位", "gps"),
                list("secure/location_mode", "global/wifi_scan_always_enabled", "global/wifi_on"),
                rules(
                        rule("定位是关着的（当前：{v}）。", list("location_high"),
                                c("secure/location_mode", Op.EQ, "0")),
                        rule("定位不是高精度模式（当前：{v}），只靠 GPS 或只靠网络都会更慢更偏。", list("location_high"),
                                c("secure/location_mode", Op.NE, "3"), c("secure/location_mode", Op.NE, "0")),
                        rule("WLAN 关着且没开「随时扫描」，室内只能靠 GPS（当前随时扫描：{v}）。", list("page_location"),
                                c("global/wifi_scan_always_enabled", Op.EQ, "0"), c("global/wifi_on", Op.EQ, "0"))),
                "page_location", "在室内或高楼间 GPS 信号本来就弱。"));
        SYMPTOMS = Collections.unmodifiableList(sy);
    }

    // =====================================================================
    // 查询 / 白名单
    // =====================================================================

    public static Key key(String id) {
        return id == null ? null : KEYS.get(id);
    }

    public static Fix fix(String id) {
        return id == null ? null : FIXES.get(id);
    }

    public static Symptom symptom(String id) {
        if (id == null) return null;
        for (Symptom s : SYMPTOMS) if (s.id.equals(id)) return s;
        return null;
    }

    /** 可读：id 在白名单里。 */
    public static boolean isReadable(String keyId) {
        return keyId != null && KEYS.containsKey(keyId);
    }

    /**
     * 写入守卫：只有本表某个 SETTING 修复用到的键可以写；值必须是安全字面量。
     * @return null = 允许；否则是拒绝原因
     */
    public static String checkWrite(String ns, String key, String value) {
        if (ns == null || key == null) return "缺少命名空间或键";
        String id = ns + "/" + key;
        if (!WRITABLE.containsKey(id)) return "不在可写白名单：" + id;
        if (value != null && !SAFE_VALUE.matcher(value).matches()) return "值不合法";
        return null;
    }

    /** DEVICEIDLE 包名守卫。 */
    public static boolean isIdlePkgAllowed(String pkg) {
        return pkg != null && IDLE_PKGS.contains(pkg);
    }

    /** 全部可写 settings 键 id（文档 / 测试用）。 */
    public static List<String> writableKeys() {
        return new ArrayList<String>(WRITABLE.keySet());
    }

    /** 值相等：数字按数值比（「1.10」=「1.1」），否则按字符串。 */
    public static boolean valueEquals(String a, String b) {
        if (a == null || b == null) return a == b;
        Double x = num(a);
        Double y = num(b);
        if (x != null && y != null) return Math.abs(x - y) < 1e-6;
        return a.trim().equals(b.trim());
    }

    static Double num(String s) {
        if (s == null) return null;
        try {
            return Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // =====================================================================
    // 构造工具
    // =====================================================================

    private static void put(Map<String, Key> m, Key k) {
        if (m.put(k.id, k) != null) throw new IllegalStateException("重复键 " + k.id);
    }

    private static void putFix(Map<String, Fix> m, Fix f) {
        if (m.put(f.id, f) != null) throw new IllegalStateException("重复修复 " + f.id);
    }

    private static Key s(String ns, String key, String label, Fmt fmt, Verify v, String note) {
        if (!SAFE_NAME.matcher(ns).matches() || !SAFE_NAME.matcher(key).matches()) throw new IllegalStateException("非法键名 " + ns + "/" + key);
        return new Key(ns + "/" + key, ns, key, null, null, label, fmt, v, note);
    }

    private static Key p(String name, String cmd, String regex, String label, Fmt fmt, Verify v, String note) {
        return new Key("probe/" + name, "probe", null, cmd, regex, label, fmt, v, note);
    }

    private static Fix setting(String id, String label, String keyId, String target, String viaCap, String capArgs, int risk, String note) {
        return new Fix(id, label, FixKind.SETTING, keyId, target, viaCap, capArgs, null, null, null, risk, true, note);
    }

    /** CAP：keyId 只用于显示「改前」值（不写它），target = 能力参数里的目标值（用于判断要不要给芯片、回读校验）。 */
    private static Fix cap(String id, String label, String displayKeyId, String target, String sign, String args, String prevGet, String undoArg, int risk, String note) {
        return new Fix(id, label, FixKind.CAP, displayKeyId, target, sign, args, prevGet, undoArg, null, risk, true, note);
    }

    /** DEVICEIDLE：keyId = 对应的只读探针（显示「改前」、判断要不要给芯片）。 */
    private static Fix idle(String id, String label, String pkg, String probeKeyId, int risk) {
        if (!SAFE_NAME.matcher(pkg).matches()) throw new IllegalStateException("非法包名 " + pkg);
        return new Fix(id, label, FixKind.DEVICEIDLE, probeKeyId, "1", null, null, null, null, pkg, risk, true, null);
    }

    private static Fix uiFind(String id, String label, String settingLabel, String settingsAction) {
        return new Fix(id, label, FixKind.UI_FIND, null, settingLabel, settingsAction, null, null, null, null, 1, false, null);
    }

    private static Fix page(String id, String label, String sign) {
        return new Fix(id, label, FixKind.PAGE, null, null, sign, "{}", null, null, null, 0, false, null);
    }

    private static List<Pattern> pats(String... re) {
        List<Pattern> l = new ArrayList<Pattern>();
        for (String r : re) l.add(Pattern.compile(r));
        return Collections.unmodifiableList(l);
    }

    private static List<String> list(String... v) {
        List<String> l = new ArrayList<String>();
        Collections.addAll(l, v);
        return Collections.unmodifiableList(l);
    }

    private static List<Rule> rules(Rule... r) {
        List<Rule> l = new ArrayList<Rule>();
        Collections.addAll(l, r);
        return Collections.unmodifiableList(l);
    }

    private static Rule rule(String finding, List<String> fixIds, Cond... conds) {
        List<Cond> l = new ArrayList<Cond>();
        Collections.addAll(l, conds);
        return new Rule(Collections.unmodifiableList(l), finding, fixIds);
    }

    private static Cond c(String keyId, Op op, String operand) {
        return new Cond(keyId, op, operand);
    }
}
