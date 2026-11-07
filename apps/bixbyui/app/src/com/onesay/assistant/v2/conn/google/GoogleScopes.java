package com.onesay.assistant.v2.conn.google;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * [conn/google] 用到的 OAuth 权限范围（最小够用）。
 *
 * <ul>
 *   <li>Gmail：读 = {@link #GMAIL_READONLY}；草稿建 / 改 / 删 / 发 = {@link #GMAIL_COMPOSE}（drafts API 只要这个）。
 *       两者都是 Google 定义的「受限 / 敏感」范围，自用的 Cloud 项目保持「测试」状态、把自己加成测试用户即可。</li>
 *   <li>日历：{@link #CALENDAR_EVENTS}（读写所有日历上的事件；不要整份 calendar 管理权）。</li>
 *   <li>Tasks：{@link #TASKS}（读写）。</li>
 *   <li>{@link #EMAIL}：只为在控制台显示「已登录 xxx@gmail.com」。</li>
 * </ul>
 * 注意：Google 的设备码流程（TV / 受限输入设备）<b>不支持</b>以上 Gmail / 日历 / Tasks 范围（官方「Allowed scopes」只有
 * openid/email/profile、drive.appdata/drive.file、YouTube），所以主流程是 installed app + loopback + PKCE。
 */
public final class GoogleScopes {

    private GoogleScopes() {}

    public static final String EMAIL = "email";
    public static final String OPENID = "openid";
    public static final String GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly";
    public static final String GMAIL_COMPOSE = "https://www.googleapis.com/auth/gmail.compose";
    public static final String CALENDAR_EVENTS = "https://www.googleapis.com/auth/calendar.events";
    public static final String TASKS = "https://www.googleapis.com/auth/tasks";

    /** 设备码流程允许的范围（Google 官方列表的子集）：用于在发起前就拒绝必然 invalid_scope 的请求。 */
    static final List<String> DEVICE_ALLOWED = Arrays.asList(OPENID, EMAIL, "profile",
            "https://www.googleapis.com/auth/drive.appdata", "https://www.googleapis.com/auth/drive.file");

    /** 本 App 一次性申请的全部范围（Gmail 读 + 草稿、日历事件、Tasks）。 */
    public static List<String> all() {
        return new ArrayList<String>(Arrays.asList(OPENID, EMAIL, GMAIL_READONLY, GMAIL_COMPOSE, CALENDAR_EVENTS, TASKS));
    }

    public static String join(List<String> scopes) {
        StringBuilder sb = new StringBuilder();
        for (String s : scopes) {
            if (s == null || s.length() == 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
        }
        return sb.toString();
    }

    /** 设备码流程能不能申请这些范围。 */
    public static boolean deviceFlowSupports(List<String> scopes) {
        for (String s : scopes) if (!DEVICE_ALLOWED.contains(s)) return false;
        return true;
    }

    /** granted（空格分隔）里是否包含 scope。 */
    public static boolean has(String granted, String scope) {
        if (granted == null || scope == null) return false;
        for (String g : granted.split(" ")) if (scope.equals(g)) return true;
        return false;
    }
}
