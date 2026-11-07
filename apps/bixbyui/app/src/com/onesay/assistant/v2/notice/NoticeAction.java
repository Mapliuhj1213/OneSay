package com.onesay.assistant.v2.notice;

import org.json.JSONObject;

/**
 * 提醒上的一个按钮。动作只描述「要做什么」，真正执行在 Android 胶水（{@code NoticeHooks}）。
 *
 * <p><b>安全约束（单测锁定）</b>：没有任何动作能发出邮件。邮件相关只有：
 * {@link Kind#OPEN_MAIL_CARD}（打开确认卡——发送仍只能在卡上点「发送」）、{@link Kind#VIEW_TASK}（看定时任务）、
 * {@link Kind#POSTPONE_MAIL}（把这次定时任务往后挪）、{@link Kind#CANCEL_MAIL}（跳过这次 / 删草稿）。
 */
public final class NoticeAction {

    public enum Kind {
        /** 打开 http(s) 链接（会议「加入」）。 */
        OPEN_URL,
        /** 打开 App 里的页面（arg = 控制台页面 id，如 callback.history）。 */
        OPEN_PAGE,
        /** 打开定时任务列表（arg = s3:&lt;id&gt;）。 */
        VIEW_TASK,
        /** 打开邮件确认卡（arg = 会话 id）——只打开，不发送。 */
        OPEN_MAIL_CARD,
        /** 稍后再提醒（arg = 分钟）。 */
        SNOOZE,
        /** 定时邮件：这次往后推（arg = s3:&lt;id&gt;@&lt;计划时刻&gt;|分钟）。 */
        POSTPONE_MAIL,
        /** 定时邮件：取消这次（arg = s3:&lt;id&gt;@&lt;计划时刻&gt;）；草稿待确认：删草稿（arg = draft:&lt;会话 id&gt;）。 */
        CANCEL_MAIL,
        DISMISS,
        /** 扩展来源自己的动作（回调 {@link NoticeSource#onAction}）。 */
        CUSTOM,
        /**
         *：来源自己的动作，但<b>提醒留在岛上</b>（通话里的「静音 / 免提」）。中心不改状态，来源执行后自己重发一条更新按钮文字。
         * 回调 {@link NoticeSource#onAction}。
         */
        INLINE,
        /**：复制到剪贴板（验证码「复制」）。arg = 要复制的文本——<b>敏感</b>：{@link #toString} / Outcome 日志打码，提醒本身不落库。 */
        COPY_TEXT,
        /**：打开某个 App（arg = 包名）或它的应用信息页（arg = settings:&lt;包名&gt;）。只认合法包名。 */
        OPEN_APP
    }

    public final String label;
    public final Kind kind;
    public final String arg;

    public NoticeAction(String label, Kind kind, String arg) {
        this.label = label == null ? "" : IslandNotice.clip(label, 8);
        this.kind = kind == null ? Kind.DISMISS : kind;
        this.arg = arg == null ? "" : arg;
    }

    JSONObject toJson() throws Exception {
        return new JSONObject().put("l", label).put("k", kind.name()).put("a", arg);
    }

    static NoticeAction fromJson(JSONObject o) {
        if (o == null) return null;
        try {
            return new NoticeAction(o.optString("l", ""), Kind.valueOf(o.optString("k", "DISMISS")), o.optString("a", ""));
        } catch (Exception e) {
            return null;
        }
    }

    /** 会议链接只认 http(s)，防 intent:// / javascript: 之类。 */
    public static boolean safeUrl(String u) {
        if (u == null) return false;
        String s = u.trim().toLowerCase(java.util.Locale.ROOT);
        return (s.startsWith("https://") || s.startsWith("http://")) && s.length() > 10 && s.indexOf(' ') < 0;
    }

    /** 包名合法性（OPEN_APP 用；防把任意字符串塞进 Intent）。 */
    public static boolean safePackage(String p) {
        if (p == null) return false;
        String s = p.startsWith("settings:") ? p.substring(9) : p;
        return s.length() > 2 && s.length() < 160 && s.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    }

    /** 日志里给 arg 打码（COPY_TEXT 的 arg 是验证码）。 */
    public static String logArg(Kind k, String arg) {
        if (k == Kind.COPY_TEXT) return arg == null || arg.length() == 0 ? "" : "<" + arg.length() + " 字已打码>";
        return arg == null ? "" : arg;
    }

    @Override public String toString() {
        return label + "(" + kind + ")";
    }
}
