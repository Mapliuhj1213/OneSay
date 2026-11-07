package com.onesay.assistant.v2.quicknote;

import com.onesay.assistant.v2.vision.screenref.ScreenEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * [quicknote] ★ R11 邮件感知（纯 Java，本机抽取，零网络，JVM 全测）。
 *
 * <p>在邮件 App（QQ 邮箱 / Gmail / 网易 / 三星邮件 / Outlook …）里长按记一记时，从读信页的文字里抽：
 * 发件人、主题、一句要点，以及<b>待办 / 截止 / 会议</b>和它们的时间。结果卡据此给「提醒」「加日程」「加待办」。
 *
 * <p>时间只认<b>将来的</b>（≥ 现在 + 5 分钟）：读信页最上面的「2026年10月7日 00:28」是发信时间，过去了，不会被当成日程。
 * 文字树为空时，记一记照旧先走已有的视觉兜底拿到文字，再交给这里（不额外调用大模型）。
 */
public final class MailDigest {

    private MailDigest() {}

    public enum Kind { MEETING, DEADLINE, TODO, NONE }

    public static final class Result {
        public Kind kind = Kind.NONE;
        public String sender = "", subject = "", point = "";
        /** 事件时间（会议开始 / 截止）；0 = 没有。 */
        public long at;
        public boolean allDay;

        public boolean actionable() {
            return kind != Kind.NONE;
        }

        /** 结果卡正文 / 存库摘要：「发件人 · 主题 · 要点」。 */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            if (sender.length() > 0) sb.append(sender);
            if (subject.length() > 0) sb.append(sb.length() > 0 ? " · " : "").append(subject);
            if (point.length() > 0 && !point.equals(subject)) sb.append(sb.length() > 0 ? " · " : "").append(point);
            return sb.toString();
        }

        public String kindLabel() {
            switch (kind) {
                case MEETING: return "会议";
                case DEADLINE: return "截止";
                case TODO: return "待办";
                default: return "";
            }
        }

        @Override public String toString() {
            return kind + " at=" + at + " subj=" + subject.length() + " point=" + point.length();
        }
    }

    static final Pattern MAIL_PKG = Pattern.compile("^(com\\.tencent\\.androidqqmail|com\\.google\\.android\\.gm|com\\.netease\\.mail|com\\.netease\\.mobimail"
            + "|com\\.samsung\\.android\\.email\\.provider|com\\.samsung\\.android\\.email\\.ui|com\\.android\\.email|com\\.microsoft\\.office\\.outlook"
            + "|com\\.alibaba\\.alimei|com\\.yahoo\\.mobile\\.client\\.android\\.mail|ch\\.protonmail\\.android|com\\.sina\\.mail|cn\\.com\\.mail189"
            + "|com\\.asiainfo\\.android|cn\\.cj\\.pe|com\\.fsck\\.k9|com\\.easilydo\\.mail|me\\.bluemail\\.mail|com\\.readdle\\.spark)$");

    public static boolean isMailApp(String pkg) {
        return pkg != null && MAIL_PKG.matcher(pkg).matches();
    }

    static final Pattern MEETING = Pattern.compile("(?i)会议|开会|例会|周会|面试|会面|评审|研讨|讲座|培训|答辩|直播|腾讯会议|飞书会议|钉钉会议|会议号|入会|参会|议程|日程邀请|邀请您参加|zoom|teams|webex|meeting|webinar|invitation");
    static final Pattern DEADLINE = Pattern.compile("(?i)截止|截至|之前|以前|前(提交|完成|回复|确认|缴|交|报名|处理|付款|支付)|最晚|最迟|逾期|到期|deadline|\\bdue\\b");
    static final Pattern TODO = Pattern.compile("(?i)请(您|你)?(于|在|尽快|及时|务必|按时|查收|确认|回复|提交|填写|完成|处理|审核|审批|签署|缴纳|支付|参加|出席|报名|上传|更新|核对)|需要(您|你)|麻烦(您|你)|务必|记得|待办|待处理|action required|please (reply|confirm|submit|review|complete|sign|join)");
    /** 读信页的界面字（按钮 / 栏目），不当主题。 */
    static final Pattern UI_NOISE = Pattern.compile("^(回复|全部回复|转发|删除|标记|标为已读|标为未读|收件箱|写邮件|返回|更多|详情|发件人|收件人|抄送|密送|时间|附件|星标|归档"
            + "|移动|举报|拒收|翻译|下载|预览|查看全部|展开|收起|今天|昨天|上午|下午|我|To|From|Cc|Reply|Forward|Delete|Archive|Inbox|More)[:：]?$");
    /** ★ R11 真机复核：读信页的导航 / 操作按钮描述（QQ 邮箱「进入上一封邮件」「删除邮件」「回复和转发」…），整行含这些就不当主题。 */
    static final Pattern UI_CHROME = Pattern.compile("上一封|下一封|写邮件和设置|删除邮件|回复和转发|更多操作|返回收件箱|标记为|^(分享|隐藏|会员中心|管理|未读|已读)$");
    static final Pattern HEADER = Pattern.compile("^(发件人|收件人|抄送|密送|时间|日期|发送时间|From|To|Cc|Date|Sent)[:：\\s]");
    static final Pattern CJK_OR_WORD = Pattern.compile("[\\u4e00-\\u9fa5]|[A-Za-z]{3,}");
    /** 只认至少 5 分钟以后的时间（真机：视觉兜底会把状态栏时钟「下午 11:12」抄进来，它≈现在，不是日程）。 */
    static final long FUTURE_MIN_MS = 5L * 60L * 1000L;
    /** 带日期字样（不是光秃秃一个钟点）。 */
    static final Pattern DATE_WORD = Pattern.compile("月|日|号|周|星期|礼拜|明天|后天|今天|今晚|明早|明晚|下周|本周|\\d{1,2}[/\\-]\\d{1,2}");

    /**
     * @param lines 有意义的文字行（{@code QuickNoteClassifier.meaningful} 之后）
     * @param ents  识屏实体（时间、邮箱…）
     */
    public static Result digest(List<String> lines, List<ScreenEntity> ents, long now) {
        Result r = new Result();
        if (lines == null) lines = new ArrayList<String>();
        if (ents == null) ents = new ArrayList<ScreenEntity>();
        r.sender = sender(lines, ents);
        r.subject = subject(lines);
        // 要点：按「会议 / 截止 / 待办 / 带将来时间」打分，取最高的一行
        String best = null;
        int bestScore = 0;
        for (String l : lines) {
            if (l.length() < 4 || HEADER.matcher(l).find() || UI_NOISE.matcher(l.trim()).matches()) continue;
            int sc = 0;
            if (MEETING.matcher(l).find()) sc += 3;
            if (DEADLINE.matcher(l).find()) sc += 3;
            if (TODO.matcher(l).find()) sc += 2;
            ScreenEntity t = futureTimeIn(l, ents, now);
            if (t != null) sc += 3;
            if (sc > bestScore) {
                bestScore = sc;
                best = l;
            }
        }
        ScreenEntity when = best == null ? null : futureTimeIn(best, ents, now);
        if (when == null) when = firstFuture(ents, now);
        String all = join(lines);
        if (best != null && bestScore >= 2) {
            r.point = clip(best.trim(), 48);
            if (MEETING.matcher(best).find() || (when != null && MEETING.matcher(all).find() && !DEADLINE.matcher(best).find())) r.kind = Kind.MEETING;
            else if (DEADLINE.matcher(best).find()) r.kind = Kind.DEADLINE;
            else if (TODO.matcher(best).find()) r.kind = Kind.TODO;
            else if (when != null) r.kind = MEETING.matcher(all).find() ? Kind.MEETING : DEADLINE.matcher(all).find() ? Kind.DEADLINE : Kind.TODO;
        }
        if (r.kind != Kind.NONE && when != null) {
            r.at = when.beginMs;
            r.allDay = when.allDay;
        }
        return r;
    }

    /**
     * 提醒时刻（纯函数）：会议提前 10 分钟；截止提前 1 小时（全天截止 = 当天 9:00）；待办 = 到点。
     * 算出来已经过了但事件还没到 → 用事件时间；事件也过了 → 0（不给提醒）。
     */
    public static long remindAt(Kind kind, long at, boolean allDay, long now, java.util.TimeZone tz) {
        if (at <= 0) return 0L;
        long r;
        if (kind == Kind.MEETING) r = at - 10L * 60L * 1000L;
        else if (kind == Kind.DEADLINE) {
            if (allDay) {
                java.util.Calendar c = java.util.Calendar.getInstance(tz);
                c.setTimeInMillis(at);
                c.set(java.util.Calendar.HOUR_OF_DAY, 9);
                c.set(java.util.Calendar.MINUTE, 0);
                c.set(java.util.Calendar.SECOND, 0);
                c.set(java.util.Calendar.MILLISECOND, 0);
                r = c.getTimeInMillis();
            } else r = at - 60L * 60L * 1000L;
        } else r = at;
        if (r > now) return r;
        return at > now ? at : 0L;
    }

    static ScreenEntity futureTimeIn(String line, List<ScreenEntity> ents, long now) {
        for (ScreenEntity e : ents) {
            if (e.type != ScreenEntity.Type.DATETIME || e.beginMs <= 0) continue;
            if (e.beginMs < now + FUTURE_MIN_MS) continue;
            if (e.raw != null && e.raw.length() > 0 && line.contains(e.raw)) return e;
        }
        return null;
    }

    static ScreenEntity firstFuture(List<ScreenEntity> ents, long now) {
        for (ScreenEntity e : ents) {
            if (e.type != ScreenEntity.Type.DATETIME || e.beginMs < now + FUTURE_MIN_MS) continue;
            if (e.raw == null || !DATE_WORD.matcher(e.raw).find()) continue;   // 光一个钟点（多半是状态栏 / 时钟）不兜底
            return e;
        }
        return null;
    }

    static String sender(List<String> lines, List<ScreenEntity> ents) {
        for (String l : lines) {
            if (l.startsWith("发件人") || l.startsWith("From")) {
                String s = l.replaceFirst("^(发件人|From)[:：\\s]*", "").trim();
                s = ScreenSensitivity.EMAIL.matcher(s).replaceAll("").replaceAll("[<>()（）]", "").trim();
                if (s.length() > 0) return clip(s, 20);
            }
        }
        for (ScreenEntity e : ents) {
            if (e.type == ScreenEntity.Type.EMAIL && e.value != null) {
                String v = e.value;
                if (v.contains("bounce") || v.length() > 40) continue;
                return clip(v, 28);
            }
        }
        return "";
    }

    /** 图片名 / 资源串（Icon_recovery_x2_20_20、ACg8oc…=s96-c）：没有空格、带下划线 / 等号 / 数字的一长串，不当主题。 */
    static final Pattern ASSETISH = Pattern.compile("^(?=.*[_=\\d])[A-Za-z0-9_\\-=.+/]{8,}$");
    static final Pattern SENDER_HEADER = Pattern.compile("^(发件人|From)[:：]?\\s*$|^(发件人|From)[:：]");

    static boolean subjectLike(String t) {
        if (t.length() < 4 || t.length() > 80) return false;
        if (UI_NOISE.matcher(t).matches() || HEADER.matcher(t).find() || UI_CHROME.matcher(t).find()) return false;
        if (ASSETISH.matcher(t).matches()) return false;
        if (ScreenSensitivity.stripAddresses(t).length() < 4) return false;
        if (!CJK_OR_WORD.matcher(t).find()) return false;
        return !t.matches("^[\\d年月日:：\\-/\\s上下午AMPMampm.]+$");
    }

    static String subject(List<String> lines) {
        // ★ R11 真机复核：QQ 邮箱读信页的主题在文字树里排在正文之后、紧挨「发件人：」之前 → 先认这一行
        for (int i = 1; i < lines.size(); i++) {
            if (SENDER_HEADER.matcher(lines.get(i).trim()).find()) {
                String prev = lines.get(i - 1).trim();
                if (subjectLike(prev)) return clip(prev, 36);
                break;
            }
        }
        int seen = 0;
        String best = "";
        for (String l : lines) {
            String t = l.trim();
            if (seen++ >= 10) break;
            if (t.length() < 4 || t.length() > 80) continue;
            if (UI_NOISE.matcher(t).matches() || HEADER.matcher(t).find() || UI_CHROME.matcher(t).find()
                    || ASSETISH.matcher(t).matches()) { seen--; continue; }
            if (ScreenSensitivity.stripAddresses(t).length() < 4) continue;
            if (!CJK_OR_WORD.matcher(t).find()) continue;
            if (t.matches("^[\\d年月日:：\\-/\\s上下午AMPMampm.]+$")) continue;
            if (best.length() == 0) best = t;
            break;
        }
        return clip(best, 36);
    }

    static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(s).append(' ');
        return sb.toString();
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
