package com.onesay.assistant.v2.notice;

import android.content.Context;
import android.content.SharedPreferences;

import com.onesay.assistant.v2.kernel.FeatureSwitch;

/**
 * 消息提醒的设置（prefs bixby_notice；kill-switch notice.off）。控制台「灵动岛 › 消息提醒」页改这些值。
 * 免打扰时段本身沿用 A 线回调中枢的设置（bixby_callback/quiet_on、quiet_start、quiet_end），这里不另起一份。
 */
public final class NoticeSettings {

    private NoticeSettings() {}

    public static final String PREFS = "bixby_notice";
    public static final FeatureSwitch SWITCH = new FeatureSwitch(PREFS, "enabled", true, "notice.off");

    public static final String K_SRC_CALLBACK = "src_callback", K_SRC_CALENDAR = "src_calendar", K_SRC_MAIL = "src_mail";
    public static final String K_CAL_LEAD = "cal_lead_min", K_MAIL_LEAD = "mail_lead_min", K_QUIET_CAL_HIGH = "quiet_cal_high";

    // ---- 灵动岛智能化：新来源（各自独立开关）+ 细项 ----
    /** 麦克风 / 相机正在使用（默认开）。 */
    public static final String K_SRC_PRIVACY = "src_privacy";
    /** 通话：来电 / 通话中（时长 + 静音 / 免提 / 挂断）/ 未接（默认开）。 */
    public static final String K_SRC_CALL = "src_call";
    /** 重要通知上岛（经通知使用权；默认开，细项见下）。 */
    public static final String K_SRC_NOTIFY = "src_notify";
    /** 充电 / 低电量 / 耳机连接（默认开）。 */
    public static final String K_SRC_SYSTEM = "src_system";
    /** 外卖 / 打车进度（可选，默认关）。 */
    public static final String K_SRC_ERRAND = "src_errand";
    /** 重要通知细项：验证码、语音通话邀请（微信 / QQ 等）、闹钟与计时器、导航、正在播放（默认关）、白名单 App。 */
    public static final String K_N_OTP = "n_otp", K_N_VOIP = "n_voip", K_N_ALARM = "n_alarm", K_N_NAV = "n_nav", K_N_MUSIC = "n_music";
    /** 白名单 App（逗号分隔的包名）。 */
    public static final String K_N_WHITELIST = "n_whitelist";
    /** 敏感内容显示：验证码明文（默认关 = 打码）、白名单通知正文（默认关 = 只显示「有新消息」）、来电号码（默认关 = 只显示联系人名 / 打码）。 */
    public static final String K_REVEAL_OTP = "reveal_otp", K_REVEAL_CONTENT = "reveal_content", K_REVEAL_NUMBER = "reveal_number";
    /** 免打扰时段照常显示：麦克风 / 相机（默认开）、通话（默认开）。 */
    public static final String K_QUIET_PRIVACY = "quiet_privacy", K_QUIET_CALL = "quiet_call";
    /** 低电量阈值（%，默认 20；≤ 一半时再提醒一次且为高优先级）。 */
    public static final String K_LOW_BATTERY = "low_battery_pct";

    public static boolean flag(Context ctx, String key) {
        return prefs(ctx).getBoolean(key, defaultOf(key));
    }

    /** 各开关的默认值（纯函数，单测锁定）：外卖打车、正在播放、各种明文显示默认关，其余默认开。 */
    public static boolean defaultOf(String key) {
        return !(K_SRC_ERRAND.equals(key) || K_N_MUSIC.equals(key) || K_REVEAL_OTP.equals(key) || K_REVEAL_CONTENT.equals(key)
                || K_REVEAL_NUMBER.equals(key));
    }

    public static java.util.Set<String> whitelist(Context ctx) {
        return parseWhitelist(prefs(ctx).getString(K_N_WHITELIST, ""));
    }

    /** 「a.b, c.d」→ 合法包名集合（纯函数）。 */
    public static java.util.Set<String> parseWhitelist(String raw) {
        java.util.Set<String> s = new java.util.LinkedHashSet<String>();
        if (raw == null) return s;
        for (String p : raw.split("[,，\\s]+")) {
            String t = p.trim();
            if (NoticeAction.safePackage(t) && !t.startsWith("settings:")) s.add(t);
        }
        return s;
    }

    public static int lowBattery(Context ctx) {
        return clamp(prefs(ctx).getInt(K_LOW_BATTERY, 20), 5, 50);
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean master(Context ctx) {
        return SWITCH.on(ctx);
    }

    public static boolean source(Context ctx, String key) {
        return prefs(ctx).getBoolean(key, true);
    }

    public static int calLead(Context ctx) {
        return clamp(prefs(ctx).getInt(K_CAL_LEAD, CalendarNotices.DEFAULT_LEAD_MIN), 0, 120);
    }

    public static int mailLead(Context ctx) {
        return clamp(prefs(ctx).getInt(K_MAIL_LEAD, ScheduledMailNotices.DEFAULT_LEAD_MIN), 1, 60);
    }

    public static boolean quietHighCalendar(Context ctx) {
        return prefs(ctx).getBoolean(K_QUIET_CAL_HIGH, true);
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    public static String dump(Context ctx) {
        return "enabled=" + master(ctx) + " callback=" + source(ctx, K_SRC_CALLBACK) + " calendar=" + source(ctx, K_SRC_CALENDAR)
                + " mail=" + source(ctx, K_SRC_MAIL) + " calLead=" + calLead(ctx) + " mailLead=" + mailLead(ctx)
                + " quietCalHigh=" + quietHighCalendar(ctx)
                + " privacy=" + flag(ctx, K_SRC_PRIVACY) + " call=" + flag(ctx, K_SRC_CALL) + " notify=" + flag(ctx, K_SRC_NOTIFY)
                + "(otp=" + flag(ctx, K_N_OTP) + " voip=" + flag(ctx, K_N_VOIP) + " alarm=" + flag(ctx, K_N_ALARM) + " nav=" + flag(ctx, K_N_NAV)
                + " music=" + flag(ctx, K_N_MUSIC) + " wl=" + whitelist(ctx).size() + ")"
                + " system=" + flag(ctx, K_SRC_SYSTEM) + " errand=" + flag(ctx, K_SRC_ERRAND)
                + " quietPrivacy=" + flag(ctx, K_QUIET_PRIVACY) + " quietCall=" + flag(ctx, K_QUIET_CALL);
    }
}
