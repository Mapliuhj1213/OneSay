package com.onesay.assistant.v2.s2;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [s2] 「记一下…」内容的实体槽解析 + 「…放哪了」问句解析（纯 Java）。
 *
 * <h3>写入（{@link #parseNote}）</h3>
 * <ul>
 *   <li>位置类：「车停在 B2-17」「我把护照放在书房第二个抽屉了」「钥匙放到鞋柜上」→ 主体=车/护照/钥匙，关系 place，值=位置；
 *       主体省略的「停在 B2-17」按「车」处理；</li>
 *   <li>存放类：「备用钥匙在妈妈那」→ 主体=备用钥匙，place；</li>
 *   <li>属性类：「张三的生日是 5 月 3 号」「女儿班主任叫王老师」→ 主体=张三的生日 / 女儿班主任，关系 attr；</li>
 *   <li>其余：无槽位，只按关键词（bigram）检索。</li>
 * </ul>
 * 主体做轻度归一（去「我的/我/家里的/那辆」，车子/汽车/爱车 → 车），使「车停哪了」能对上「我的车停在…」。
 *
 * <h3>问句（{@link #parseQuestion}）</h3>
 * 「我车停哪了」「我把护照放哪了」「车停在哪一层」→ 主体 + 想要 place；「护照在哪」→ 弱问句（只有记忆库里有强命中才接管）；
 * 「上次记的停车位置」「我记的…」→ 明确指向记忆库（没找到也由记忆库回答「没找到」）。
 */
public final class NoteParser {

    private NoteParser() {}

    /** 写入解析结果。 */
    public static final class Note {
        public String subject = "";
        public String relation = "";
        public String value = "";

        @Override public String toString() {
            return "subj=" + subject + " rel=" + relation + " val=" + value;
        }
    }

    /** 问句解析结果。 */
    public static final class Question {
        public String subject = "";
        /** 想要的关系（place / attr / 空）。 */
        public String want = "";
        /** 用于关键词检索的剩余文本。 */
        public String text = "";
        /** 明确指向记忆库（「上次记的/我记的/记过」）。 */
        public boolean explicit;
        /** 是否带人称/个人物品语气（我/我的/我把）。 */
        public boolean personal;
        /** ★ int/1102：问的是自己 / 家人的属性（「我女儿生日是哪天」），只有记忆库知道。 */
        public boolean kin;

        @Override public String toString() {
            return "subj=" + subject + " want=" + want + " explicit=" + explicit + " personal=" + personal + " text=" + text;
        }
    }

    private static final String PUT_VERBS = "停放|寄存|停|放|搁|存|塞|藏|锁|挂|丢|落";
    private static final Pattern P_PLACE = Pattern.compile(
            "^(?:我把|把|我将|将)?(.{0,12}?)(?:" + PUT_VERBS + ")(?:到了|在了|到|在|进|于)(.+)$");
    private static final Pattern P_AT = Pattern.compile("^(?:我的)?(.{1,12}?)(?:在|放在)(.{1,30})$");
    private static final Pattern P_ATTR = Pattern.compile("^(.{1,16}?)(?:是|为|叫|就是)(.{1,40})$");
    private static final Pattern TIME_HEAD = Pattern.compile("^(今天|明天|后天|昨天|前天|晚上|早上|下午|上午|中午|周[一二三四五六日天]|下周|这周|本周|\\d+点|[零一二三四五六七八九十两]+点)");

    private static final Pattern Q_PLACE = Pattern.compile(
            "^(?:我把|把|我的|我)?(.{0,12}?)(" + PUT_VERBS + ")(?:在|到)?(?:哪儿|哪里|哪个位置|哪个地方|哪一?[层个楼排格区号]|几号|几层|什么地方|什么位置|哪)(?:了|呢|啦|来着|呀|啊)*$");
    private static final Pattern Q_WHERE = Pattern.compile(
            "^(?:我把|我的|我)?(.{1,12}?)(?:在哪儿|在哪里|在哪|放哪儿|放哪里|放哪)(?:了|呢|啦|来着|呀|啊)*$");
    private static final Pattern Q_EXPLICIT = Pattern.compile(
            "上次记的|之前记的|刚才记的|刚刚记的|刚记的|刚才记了|刚刚记了|刚记了|我记的|我记过|记过的|你记得我|还记得我|帮我记过|我让你记|记下的");

    /** ★ int/1102 常识：自己 / 家人的属性问句（「我女儿生日是哪天」「我老婆鞋码多少」「我女儿什么时候过生日」）——只有记忆库知道，不进 LLM。 */
    private static final Pattern Q_KIN_ATTR = Pattern.compile(
            "^(我们的|我们|我的|我家的|我家|我)?(?:的)?(" + ImplicitNote.KIN + ")?(?:的)?(" + ImplicitNote.ATTR + ")(?:是|在)?"
                    + "(?:哪天|哪一天|几号|几月几号|哪个月|多少号?|什么|啥|什么时候|多大|哪个|哪所|谁|几)(?:啊|呀|呢|来着|吗)*$");
    private static final Pattern Q_KIN_BDAY = Pattern.compile(
            "^(?:我的|我家的|我家|我)?(?:的)?(" + ImplicitNote.KIN + ")(?:的)?(?:是)?(?:哪天|哪一天|什么时候|几号|几月几号|几月)(?:过)?生日(?:啊|呀|呢|来着)*$");

    /** 是不是自己 / 家人的属性问句（分诊 3m 用）。 */
    public static boolean isKinQuestion(String utterance) {
        Question q = kinQuestion(S2Text.key(utterance));
        return q != null;
    }

    static Question kinQuestion(String k) {
        if (k == null || k.length() == 0) return null;
        Matcher m = Q_KIN_ATTR.matcher(k);
        String who = null, attr = null;
        if (m.matches() && (m.group(1) != null || m.group(2) != null)) {
            who = m.group(2) != null ? m.group(2) : (m.group(1).startsWith("我们") ? "我们" : "我");
            attr = m.group(3);
        } else if ((m = Q_KIN_BDAY.matcher(k)).matches()) {
            who = m.group(1);
            attr = "生日";
        }
        if (who == null) return null;
        Question q = new Question();
        q.subject = who + "的" + attr;
        q.want = MemoryEntry.REL_ATTR;
        q.text = k;
        q.personal = true;
        q.explicit = true;   // 只有记忆库知道：没记过也由记忆库答「还没记过」
        q.kin = true;
        return q;
    }

    /** 解析写入内容。 */
    public static Note parseNote(String content) {
        Note n = new Note();
        String c = trimTail(S2Text.compact(content));
        if (c.length() == 0) return n;
        Matcher m = P_PLACE.matcher(c);
        if (m.matches()) {
            String item = canonical(m.group(1));
            String place = trimTail(m.group(2));
            if (place.length() > 0) {
                if (item.length() == 0 && c.indexOf('停') >= 0) item = "车";
                if (item.length() > 0) {
                    n.subject = item;
                    n.relation = MemoryEntry.REL_PLACE;
                    n.value = place;
                    return n;
                }
            }
        }
        m = P_AT.matcher(c);
        if (m.matches() && !TIME_HEAD.matcher(c).find()) {
            String item = canonical(m.group(1));
            String place = trimTail(m.group(2));
            if (item.length() > 0 && place.length() > 0 && !looksLikeEvent(place)) {
                n.subject = item;
                n.relation = MemoryEntry.REL_PLACE;
                n.value = place;
                return n;
            }
        }
        m = P_ATTR.matcher(c);
        if (m.matches() && !TIME_HEAD.matcher(c).find()) {
            String item = canonical(m.group(1));
            String val = trimTail(m.group(2));
            if (item.length() > 0 && val.length() > 0) {
                n.subject = item;
                n.relation = MemoryEntry.REL_ATTR;
                n.value = val;
            }
        }
        return n;
    }

    /** 解析问句；不像「记忆类问句」返回 null。 */
    public static Question parseQuestion(String utterance) {
        String k = S2Text.key(utterance);
        if (k.length() == 0) return null;
        Question q = new Question();
        q.personal = k.startsWith("我") || k.contains("我的") || k.contains("我把");
        Question kq = kinQuestion(k);
        if (kq != null) return kq;
        Matcher em = Q_EXPLICIT.matcher(k);
        if (em.find()) {
            q.explicit = true;
            String rest = (k.substring(0, em.start()) + " " + k.substring(em.end())).trim();
            rest = rest.replaceAll("^(帮我|请|你)?(看看|查查|查一下|看一下|说说|告诉我)?", "")
                    .replaceAll("(是什么|是啥|在哪儿?|在哪里|是多少|是哪天|吗|呢|了|来着)+$", "")
                    .replaceAll("^(那个|那条|关于)", "");
            q.text = rest.trim();
            // 解析显式问句里的主体（「上次记的停车位置」→ 车/place）
            Question inner = rest.length() > 0 ? parseQuestion(rest) : null;
            if (inner != null) {
                q.subject = inner.subject;
                q.want = inner.want;
            } else if (rest.contains("停车") || rest.contains("车位") || rest.contains("车停")) {
                q.subject = "车";
                q.want = MemoryEntry.REL_PLACE;
            }
            return q;
        }
        Matcher m = Q_PLACE.matcher(k);
        if (m.matches()) {
            String item = canonical(m.group(1));
            if (item.length() == 0 && "停".equals(m.group(2))) item = "车";
            if (item.length() == 0) return null;
            q.subject = item;
            q.want = MemoryEntry.REL_PLACE;
            q.text = k;
            q.personal = true;   // 「X 停/放哪了」本身就是问自己的东西
            return q;
        }
        m = Q_WHERE.matcher(k);
        if (m.matches()) {
            String item = canonical(m.group(1));
            if (item.length() == 0) return null;
            q.subject = item;
            q.want = MemoryEntry.REL_PLACE;
            q.text = k;
            return q;
        }
        // 「停车位/车位是多少」「我车位在几号」
        if ((k.contains("停车位") || k.contains("车位")) && (k.contains("多少") || k.contains("几") || k.contains("哪"))) {
            q.subject = "车";
            q.want = MemoryEntry.REL_PLACE;
            q.text = k;
            q.personal = true;
            return q;
        }
        // 属性问句（弱）：「张三的生日是哪天」「女儿班主任叫什么」
        Matcher am = Pattern.compile("^(?:我的)?(.{1,16}?)(?:是什么|是啥|是多少|是哪天|是几号|是几月几号|叫什么|叫啥)(?:来着|呢|啊)?$").matcher(k);
        if (am.matches()) {
            String item = canonical(am.group(1));
            if (item.length() == 0) return null;
            q.subject = item;
            q.want = MemoryEntry.REL_ATTR;
            q.text = k;
            return q;
        }
        return null;
    }

    /** 主体归一：去人称/指示前缀与「的」尾，车类同义词归一。 */
    public static String canonical(String item) {
        if (item == null) return "";
        String s = item.trim();
        s = s.replaceAll("^(我们家的|我们家|咱家的|咱家|家里的|我的|我|你帮我|帮我|那辆|这辆|那个|这个|那把|这把|那本|这本|那张|这张|把)", "");
        s = s.replaceAll("(的|了)$", "");
        if (s.equals("汽车") || s.equals("车子") || s.equals("爱车") || s.equals("小车") || s.equals("私家车")) s = "车";
        return s;
    }

    private static String trimTail(String s) {
        if (s == null) return "";
        return s.replaceAll("(了吧|了哦|了啊|了呀|了|吧|哦|啊|呀|里了)$", "").replaceAll("[，,。.！!~～]+$", "").trim();
    }

    /** 「明天在公司开会」这类「在 + 事件」不算位置。 */
    private static boolean looksLikeEvent(String place) {
        return place.matches(".*(开会|上课|吃饭|见面|集合|开会|聚餐|面试|值班|加班|出差|培训)$");
    }
}
