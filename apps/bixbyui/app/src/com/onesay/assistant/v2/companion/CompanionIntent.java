package com.onesay.assistant.v2.companion;

import com.onesay.assistant.v2.s3.S3Parser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [companion] 一句话属不属于伴侣 App 的领域（待办 / 日程 → 计划；闹钟 → 时钟），以及删 / 改待办这类 S3 不认的句式。
 * 纯正则、不读库、JVM 可测。只做分类，执行在 {@link CompanionRouter} / {@link CompanionHooks}。
 */
public final class CompanionIntent {
    private CompanionIntent() {}

    public enum Domain { NONE, PLAN, CLOCK }

    public enum Kind {
        NONE,
        TODO_ADD, TODO_LIST, TODO_COMPLETE, TODO_CLEAR,
        /** 删一条待办（S3 不认的句式，原先落进 AgentLoop）。 */
        TODO_DELETE,
        /** 改一条待办的内容。 */
        TODO_UPDATE,
        /** 句子里说了「待办」但不是上面任何一种（交给原规则 / 问答，但不进 AgentLoop）。 */
        TODO_OTHER,
        EVENT, ALARM
    }

    public static final class Parsed {
        public final Kind kind;
        public final Domain domain;
        /** 删 / 改 / 勾的目标文字。 */
        public final String target;
        /** 改成什么。 */
        public final String newText;

        Parsed(Kind kind, Domain domain, String target, String newText) {
            this.kind = kind;
            this.domain = domain;
            this.target = target == null ? "" : target;
            this.newText = newText == null ? "" : newText;
        }

        @Override public String toString() {
            return kind + "/" + domain + (target.length() > 0 ? " target=" + target : "") + (newText.length() > 0 ? " new=" + newText : "");
        }
    }

    static final Parsed NONE = new Parsed(Kind.NONE, Domain.NONE, "", "");

    static final String DEL = "(?:删除|删掉|删了|删去|去掉|移除|移掉|干掉|不要了|划去)";
    static final String DEMO = "(?:这条|那条|这个|那个|这一条|那一条)?";
    /** 「删除待办 X」「删掉这条待办：X」「删除我的待办 X」 */
    static final Pattern DEL_A = Pattern.compile("^" + DEL + "(?:一下)?(?:我的)?" + DEMO + "待办(?:事项)?(?:里的|中的|里|中)?[：:，,\\s]*(.+)$");
    /** 「删除 X 这条待办」「删掉买牛奶的待办」 */
    static final Pattern DEL_B = Pattern.compile("^" + DEL + "(?:一下)?(.+?)(?:的)?" + DEMO + "待办(?:事项)?$");
    /** 「把 X 从待办里删了」「把待办 X 删掉」「X 那条待办删掉」 */
    static final Pattern DEL_C = Pattern.compile("^(?:把)?(?:待办(?:事项)?[：:，,\\s]*)?(.+?)" + DEMO + "(?:待办(?:事项)?)?(?:从(?:我的)?待办(?:事项|列表)?(?:里|中)?)?" + DEL + "(?:吧|了|掉)?$");
    /** 「把待办 X 改成 Y」「把 X 这条待办改为 Y」 */
    static final Pattern UPD = Pattern.compile("^(?:把)?(?:待办(?:事项)?[：:，,\\s]*)?(.+?)" + DEMO + "(?:待办(?:事项)?)?(?:的内容|的名字)?(?:改成|改为|改名为|改名成|换成|更新为|更新成)(.+)$");
    static final Pattern EVENT_WORD = Pattern.compile("日程|行程安排");
    /** 和别的动作串在一起（「把待办发给张三」「查完日程再订票」）：多步任务照旧，不抢。 */
    static final Pattern OTHER_ACTION = Pattern.compile("发给|发送|发到|分享|转发|告诉|微信|短信|邮件|截图|然后|接着|之后再|再帮我");
    static final Pattern ALARM_WORD = Pattern.compile("闹钟|闹铃|叫醒我|叫我起床");
    /** 点名了别家 App（「在滴答清单里加个待办」）：尊重用户，不抢。 */
    static final Pattern OTHER_APP = Pattern.compile("(?:在|用|到|去|打开)([\\u4e00-\\u9fa5A-Za-z0-9]{2,10}?)(?:App|app|APP|应用)?(?:里|上|中)");
    static final Pattern OURS = Pattern.compile("^(?:计划|OneSay|onesay|时钟|闹钟|我的待办|待办|日程|日历|手机)");

    public static Parsed parse(String utterance) {
        String q = S3Parser.norm(utterance).replaceAll("[。！!？?~～\\s]+$", "").trim();
        q = q.replaceAll("^(?:在|到|去)(?:计划|OneSay|onesay)(?:App|app|APP|应用)?(?:里|中|上)[，,\\s]*", "");   // 「在计划里删除待办 X」= 自家
        if (q.length() == 0) return NONE;
        if (namesOtherApp(q) || OTHER_ACTION.matcher(q).find()) return NONE;
        boolean todo = q.contains("待办");
        if (todo) {
            S3Parser.Parsed s = S3Parser.parse(q, System.currentTimeMillis(), java.util.TimeZone.getDefault());
            if (s != null && s.type == S3Parser.Type.TODO_CLEAR) return new Parsed(Kind.TODO_CLEAR, Domain.PLAN, "", "");
            Matcher m = UPD.matcher(q);
            if (m.matches()) {
                String t = clean(m.group(1)), n = clean(m.group(2));
                if (t.length() > 0 && n.length() > 0 && !t.matches("^" + DEL + ".*")) return new Parsed(Kind.TODO_UPDATE, Domain.PLAN, t, n);
            }
            for (Pattern p : new Pattern[]{DEL_A, DEL_B, DEL_C}) {
                m = p.matcher(q);
                if (m.matches()) {
                    String t = clean(m.group(1));
                    if (t.length() <= 30) return new Parsed(Kind.TODO_DELETE, Domain.PLAN, t, "");
                }
            }
            if (q.matches("^" + DEL + "(?:一下)?(?:我的)?" + DEMO + "待办(?:事项)?$")) return new Parsed(Kind.TODO_DELETE, Domain.PLAN, "", "");
            if (s != null) {
                switch (s.type) {
                    case TODO_ADD: return new Parsed(Kind.TODO_ADD, Domain.PLAN, s.text, "");
                    case TODO_LIST: return new Parsed(Kind.TODO_LIST, Domain.PLAN, "", "");
                    case TODO_DONE: return new Parsed(Kind.TODO_COMPLETE, Domain.PLAN, s.text, "");
                    case TODO_CLEAR: return new Parsed(Kind.TODO_CLEAR, Domain.PLAN, "", "");
                    default: break;
                }
            }
            return new Parsed(Kind.TODO_OTHER, Domain.PLAN, "", "");
        }
        if (ALARM_WORD.matcher(q).find()) return new Parsed(Kind.ALARM, Domain.CLOCK, "", "");
        if (EVENT_WORD.matcher(q).find()) return new Parsed(Kind.EVENT, Domain.PLAN, "", "");
        return NONE;
    }

    /** 点名了别的 App（不是计划 / 时钟 / OneSay 自己）。 */
    public static boolean namesOtherApp(String q) {
        Matcher m = OTHER_APP.matcher(q == null ? "" : q);
        while (m.find()) {
            String name = m.group(1);
            if (!OURS.matcher(name).find()) return true;
        }
        return false;
    }

    static String clean(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("^[，,、：:。\\s「“\"]+|[，,、：:。\\s」”\"]+$", "");
        t = t.replaceAll("^(?:待办(?:事项)?|那条|这条|那个|这个|名为|叫做?)", "").replaceAll("(?:的|那条|这条|那个|这个)$", "");
        return t.replaceAll("^[「“\"]+|[」”\"]+$", "").trim();
    }
}
