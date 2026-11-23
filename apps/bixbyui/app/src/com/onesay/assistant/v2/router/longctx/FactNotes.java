package com.onesay.assistant.v2.router.longctx;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ fix/1123-longctx-phone 第 2 条：状态摘要里的「已知事实」—— 只从<b>用户亲口说的话</b>里摘（不从模型答复里摘，免得把幻觉记成事实）。
 *
 * <p>按分句挑：带「我 / 我的」且说的是身份、住处、行程、时间、编号、过敏忌口、车位这类可核对的事；
 * 紧跟在事实分句后的补充短句（「早上八点二十起飞」「平时骑电动车通勤」）并进上一条。
 * 同一类事实（航班 / 车位 / 过敏 / 称呼 …）后说的覆盖先说的（用户改口以新的为准）。
 * 密码、验证码、证件、卡号一律不摘。最多 {@link #MAX} 条，留最新的。非线程安全，由 ConversationMemory 加锁调用。
 */
public final class FactNotes {

    public static final int MAX = 12;
    public static final int MAX_CHARS = 60;

    public static final class Fact {
        public final int turnId;
        public final String text;
        public final String key;

        Fact(int id, String t, String k) {
            turnId = id;
            text = t;
            key = k;
        }
    }

    static final Pattern SPLIT = Pattern.compile("[，,。；;！!？?\n]+");
    static final Pattern SENSITIVE = Pattern.compile("密码|验证码|身份证|银行卡|卡号|支付码|口令|cvv|CVV");
    /** 不必带「我」也算事实的强关键词（按出现先后取第一个当类别）。 */
    static final Pattern STRONG = Pattern.compile("航班|车次|过敏|忌口|车位|停在|生日|纪念日|地址|门牌|房间号|预约号|订单号|取件码");
    /** 要带「我」才算的（身份 / 住处 / 偏好 / 安排）。 */
    static final Pattern MINE = Pattern.compile("叫我|我叫|名字|我在.{1,8}(上班|工作|上学|读书)|我住|住在|我家|我是|我们是|不吃|不喝|喜欢|讨厌|习惯|通勤|我要去|我得去|我明天|我后天|我下周|下周.我|明天我|后天我|我今天");
    static final Pattern TIME_OR_NUM = Pattern.compile("[0-9]|[零一二三四五六七八九十两]+(点|号|月|日|层|区|楼)|早上|上午|中午|下午|晚上|周[一二三四五六日天]");
    static final Pattern ASK = Pattern.compile("[吗呢么]$|^(什么|怎么|为什么|哪|几|多少|谁)|(什么|怎么|哪里|哪儿|几点|多少)");
    static final Pattern REMEMBER = Pattern.compile("^(对了|跟你说|我跟你说|记一下|记住|帮我记|别忘了)");

    private final List<Fact> facts = new ArrayList<Fact>();

    /** 看一条用户原话，摘出事实。返回本次新增 / 覆盖的条数。 */
    public int observe(int turnId, String userText) {
        if (userText == null) return 0;
        String[] parts = SPLIT.split(userText.trim());
        int n = 0;
        Fact prev = null;
        for (String raw : parts) {
            String c = raw.trim();
            if (c.length() < 3) continue;
            if (SENSITIVE.matcher(c).find()) {
                prev = null;
                continue;
            }
            String key = keyOf(c);
            boolean question = ASK.matcher(c).find() && key == null;
            if (key != null && !ASK.matcher(c).find()) {
                prev = put(turnId, c, key);
                n++;
            } else if (prev != null && !question && !c.contains("你") && c.length() <= 24 && !REMEMBER.matcher(c).find()) {
                // 补充短句并进上一条
                String merged = TextSim.head(prev.text + "，" + c, MAX_CHARS);
                Fact f = new Fact(prev.turnId, merged, prev.key);
                facts.set(facts.indexOf(prev), f);
                prev = f;
            } else {
                prev = null;
            }
        }
        return n;
    }

    static String keyOf(String c) {
        Matcher m = STRONG.matcher(c);
        if (m.find()) {
            String k = m.group();
            return "停在".equals(k) ? "车位" : k;
        }
        boolean me = c.contains("我");
        if (!me) return null;
        Matcher mm = MINE.matcher(c);
        if (mm.find()) {
            String g = mm.group();
            if (g.contains("叫") || g.contains("名字")) return "称呼";
            if (g.contains("上班") || g.contains("工作") || g.contains("上学") || g.contains("读书")) return "工作地";
            if (g.contains("住") || g.contains("家")) return "住处";
            if (g.contains("不吃") || g.contains("不喝") || g.contains("喜欢") || g.contains("讨厌")) return "口味:" + TextSim.head(c, 12);
            return "安排:" + TextSim.head(c, 12);
        }
        if (TIME_OR_NUM.matcher(c).find() && c.length() <= 30) return "安排:" + TextSim.head(c, 12);
        return null;
    }

    private Fact put(int turnId, String c, String key) {
        for (int i = facts.size() - 1; i >= 0; i--) if (facts.get(i).key.equals(key)) facts.remove(i);
        Fact f = new Fact(turnId, TextSim.head(c, MAX_CHARS), key);
        facts.add(f);
        while (facts.size() > MAX) facts.remove(0);
        return f;
    }

    public List<Fact> all() {
        return new ArrayList<Fact>(facts);
    }

    /** 某几条轮次说出的事实（状态摘要只写已折叠轮次的事实；逐字还在历史里的不重复写）。 */
    public List<Fact> from(java.util.Set<Integer> turnIds) {
        List<Fact> l = new ArrayList<Fact>();
        for (Fact f : facts) if (turnIds.contains(f.turnId)) l.add(f);
        return l;
    }

    public void reset() {
        facts.clear();
    }

    public int size() {
        return facts.size();
    }
}
