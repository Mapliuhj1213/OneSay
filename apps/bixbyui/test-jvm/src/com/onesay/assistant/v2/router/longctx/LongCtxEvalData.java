package com.onesay.assistant.v2.router.longctx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ fix/1123-longctx-phone：长会话评测集（确定性）。
 *
 * <p>每个场景是一串用户消息：多轮闲聊问答、话题切换、早先说过的事实（后面再问）、穿插回到已结话题、
 * 省略式追问、出行技能话题（要求按技能准则答）、识屏 / 代操作这类重活回执。每条都带「标准答案」标签，
 * 由 {@link FakeLongCtxModel} 与评测器按标签判分。场景按种子确定性生成，改前改后同一份。
 */
public final class LongCtxEvalData {
    private LongCtxEvalData() {
    }

    public enum Kind { CHAT, FOLLOW, STATE, RECALL, TRIP, HEAVY }

    /** 一个问题 / 一句话及其标签。 */
    public static final class Q {
        public final String id;
        public final Kind kind;
        public final String text;
        /** 标准答案（CHAT / FOLLOW / TRIP / STATE 的应答）。 */
        public final String answer;
        /** RECALL：标准值（答复里要出现它）与模型编的错值。 */
        public final String value, wrongValue;
        /** HEAVY：重活回执全文（含过程）与结论。 */
        public final String receipt;

        Q(String id, Kind kind, String text, String answer, String value, String wrongValue, String receipt) {
            this.id = id;
            this.kind = kind;
            this.text = text;
            this.answer = answer;
            this.value = value;
            this.wrongValue = wrongValue;
            this.receipt = receipt;
        }
    }

    public static final class Scenario {
        public final String name;
        public final int seed;
        public final List<Q> turns = new ArrayList<Q>();

        Scenario(String name, int seed) {
            this.name = name;
            this.seed = seed;
        }
    }

    static final Map<String, Q> POOL = new LinkedHashMap<String, Q>();

    static Q chat(String id, String text, String answer) {
        Q q = new Q(id, Kind.CHAT, text, answer, null, null, null);
        POOL.put(id, q);
        return q;
    }

    static Q follow(String id, String text, String answer) {
        Q q = new Q(id, Kind.FOLLOW, text, answer, null, null, null);
        POOL.put(id, q);
        return q;
    }

    static Q trip(String id, String text, String answer) {
        Q q = new Q(id, Kind.TRIP, text, answer, null, null, null);
        POOL.put(id, q);
        return q;
    }

    static Q state(String id, String text, String answer) {
        Q q = new Q(id, Kind.STATE, text, answer, null, null, null);
        POOL.put(id, q);
        return q;
    }

    static Q recall(String id, String text, String value, String wrong) {
        Q q = new Q(id, Kind.RECALL, text, null, value, wrong, null);
        POOL.put(id, q);
        return q;
    }

    static Q heavy(String id, String text, String receipt, String answer) {
        Q q = new Q(id, Kind.HEAVY, text, answer, null, null, receipt);
        POOL.put(id, q);
        return q;
    }

    static String steps(String head, int n, String conclusion) {
        StringBuilder b = new StringBuilder(head).append("，共 ").append(n).append(" 次调用：\n");
        for (int i = 1; i <= n; i++) {
            b.append("· 第").append(i).append("步 gui.unfold{target:设置页第").append(i).append("项} → 成功；屏幕文字：")
                    .append("显示 亮度 自动亮度 护眼模式 字体大小 屏幕超时 动态效果 状态栏 导航方式 第").append(i).append("屏\n");
        }
        return b.append("结论：").append(conclusion).toString();
    }

    static String screen(String head, int lines) {
        StringBuilder b = new StringBuilder(head).append("\n");
        for (int i = 1; i <= lines; i++) b.append(i).append(". 群消息：周末聚餐改到周六晚上七点，地点在万达三楼烤肉店，记得带伞，第").append(i).append("条\n");
        return b.toString();
    }

    // ---------------------------------------------------------------- 题库
    static final Q SKY = chat("sky", "天空为什么是蓝色的", "天空是蓝色，是因为阳光里的蓝光被空气散射得最多，所以满天都是蓝色。");
    static final Q SUNSET = follow("sunset", "那晚霞为什么是红的", "晚霞发红，是因为傍晚阳光斜着穿过更厚的空气，蓝光散射没了，剩下红光照到云上。");
    static final Q TEA = chat("tea", "绿茶和红茶有什么区别", "绿茶不发酵、红茶全发酵，所以绿茶清爽、红茶醇厚，红茶更养胃。");
    static final Q TEA_F = follow("tea_f", "那晚上喝哪个好一点", "晚上更适合喝红茶，咖啡因少一点、也不太刺激胃；怕睡不着就都别喝。");
    static final Q SLEEP = chat("sleep", "怎么才能睡得更好", "想睡得好：固定作息、睡前一小时不看手机、卧室暗一点凉一点、下午别喝咖啡。");
    static final Q RUN = chat("run", "跑步膝盖疼怎么办", "跑步膝盖疼先停跑休息、冰敷，换双缓震好的跑鞋，跑前热身；一周还疼就去看医生。");
    static final Q RUN_F = follow("run_f", "那还能游泳吗", "膝盖疼可以游泳，游泳对膝盖压力小；蛙泳蹬腿会别着膝盖，先换自由泳。");
    static final Q PY = chat("py", "Python 列表和元组有什么区别", "Python 列表能改、元组不能改；元组更省内存，能当字典的键，列表不行。");
    static final Q RICE = chat("rice", "电饭煲煮饭水放多少", "电饭煲煮饭，米和水大约一比一点二，手指按在米上，水没过第一个指节就行。");
    static final Q RICE_F = follow("rice_f", "糙米呢", "糙米要多放水，大约一比一点五，最好先泡一小时再煮。");
    static final Q CAT = chat("cat", "猫为什么喜欢踩奶", "猫踩奶是小时候吃奶留下的习惯，踩你说明它放松、觉得安全。");
    static final Q BAT = chat("bat", "手机电池怎么保养", "手机电池别老充到百分之百也别用到没电，留在两成到八成之间最好，别边充边玩大游戏。");
    static final Q BAT_F = follow("bat_f", "那晚上一直插着充电行不行", "晚上插着充电问题不大，手机充满会自己停；打开优化充电更好。");
    static final Q TAX = chat("tax", "个税专项附加扣除有哪些", "个税专项附加扣除有子女教育、继续教育、大病医疗、住房贷款利息、住房租金、赡养老人、婴幼儿照护。");
    static final Q MOON = chat("moon", "月亮为什么会有阴晴圆缺", "月亮的阴晴圆缺，是因为月亮绕地球转，我们看到它被太阳照亮的那一半的多少在变。");
    static final Q EGG = chat("egg", "鸡蛋煮几分钟最好", "鸡蛋水开后煮六分钟是溏心，煮九到十分钟是全熟，煮太久蛋黄会发绿。");
    static final Q EGG_F = follow("egg_f", "冷水下锅还是热水下锅", "鸡蛋冷水下锅不容易裂，水开后再开始计时。");
    static final Q WIFI = chat("wifi", "家里 WiFi 老是断怎么办", "WiFi 老断先重启路由器，把路由器放到家中间、别挨着微波炉，再看看是不是该换路由器了。");
    static final Q PLANT = chat("plant", "绿萝叶子发黄怎么回事", "绿萝叶子发黄多半是浇水太多或者太晒，放散光的地方，土干了再浇。");
    static final Q CODE = chat("code", "Git 怎么撤销上一次提交", "Git 撤销上一次提交：还没推送就用 git reset 软退回一个提交，改动会留着；推送过就用 git revert。");

    static final Q TRIP1 = trip("trip1", "帮我规划一下下周三去成都出差的行程", "下周三去成都出差：早上出发、下午办事、晚上住春熙路附近，回程订周四晚上的。");
    static final Q TRIP2 = trip("trip2", "那几天住哪里方便", "成都出差住春熙路或者天府广场附近最方便，地铁一号线二号线都能到。");
    static final Q TRIP3 = trip("trip3", "这趟大概要带多少预算", "成都出差这趟预算：机票来回一千五左右，住宿两晚八百，吃饭交通三百，一共大概两千六。");
    static final Q TRIP4 = trip("trip4", "那天要是下雨怎么安排", "成都出差那天要是下雨，就把室外的事挪到第二天，带把伞，打车别坐共享单车。");

    static final Q F_NAME = state("f_name", "对了，以后叫我小林就行，我在杭州上班，平时都是骑电动车通勤", "好的小林，记住了：你在杭州上班，骑电动车通勤。");
    static final Q F_FLIGHT = state("f_flight", "我跟你说一下情况啊，下周三我要去成都出差，公司让我自己订票，我刚订好了，我的航班是 CA4502，早上八点二十起飞", "好的，记下了，祝你出差顺利。");
    static final Q F_ALLERGY = state("f_allergy", "跟你说个事，我妈上次带我去医院检查过，医生说我对花生过敏，吃东西的时候帮我注意点", "好的，记住了：你对花生过敏，推荐吃的会避开。");
    static final Q F_PARK = state("f_park", "今天车停在公司地下停车场了，那边车位特别难找，转了好几圈才找到，最后我停在了负二层 B 区 117 号车位", "好的，记下了。");

    static final Q R_NAME = recall("r_name", "你还记得我平时怎么通勤的吗", "电动车", "坐地铁");
    static final Q R_FLIGHT = recall("r_flight", "我那趟航班几点起飞来着", "八点二十", "九点零五");
    static final Q R_FLIGHT2 = recall("r_flight2", "我航班号是多少", "CA4502", "CA1873");
    static final Q R_ALLERGY = recall("r_allergy", "我对什么东西过敏来着", "花生", "海鲜");
    static final Q R_PARK = recall("r_park", "我车停在哪个车位了", "117", "203");

    static final Q H_SET = heavy("h_set", "打开设置看看显示里都有什么",
            steps("[执行回执] Agent 循环已完成", 9, "显示设置里有亮度、自动亮度、护眼模式、字体大小、屏幕超时和导航方式。"),
            "显示设置里有亮度、自动亮度、护眼模式、字体大小、屏幕超时和导航方式。");
    static final Q H_SCREEN = heavy("h_screen", "看看屏幕上这个群在说什么",
            screen("[视觉识屏回执] 屏幕识别完成：群里在说周末聚餐改到周六晚上七点，地点在万达三楼烤肉店。", 40),
            "群里在说周末聚餐改到周六晚上七点，地点在万达三楼烤肉店。");

    // ---------------------------------------------------------------- 场景
    static int rng(int seed, int i) {
        int x = seed * 1103515245 + i * 12345 + 0x5bd1e995;
        x ^= x >>> 13;
        x *= 0x27d4eb2d;
        x ^= x >>> 15;
        return x & 0x7fffffff;
    }

    static final Q[][] CHAINS = {
            {SKY, SUNSET}, {TEA, TEA_F}, {SLEEP}, {RUN, RUN_F}, {PY}, {RICE, RICE_F}, {CAT}, {BAT, BAT_F},
            {TAX}, {MOON}, {EGG, EGG_F}, {WIFI}, {PLANT}, {CODE},
    };

    /** 场景：facts 是这一场要说的事实（说完隔一段再问）；trips 插在中段；heavy 插 1–2 次；最后若干轮穿插回问已结话题与事实。 */
    static Scenario build(String name, int seed, int chatChains, Q[] facts, Q[] recalls, boolean trips, Q[] heavies) {
        Scenario s = new Scenario(name, seed);
        List<Q[]> chains = new ArrayList<Q[]>();
        for (Q[] c : CHAINS) chains.add(c);
        // 确定性洗牌
        for (int i = chains.size() - 1; i > 0; i--) {
            int j = rng(seed, i) % (i + 1);
            Q[] t = chains.get(i);
            chains.set(i, chains.get(j));
            chains.set(j, t);
        }
        int fi = 0, hi = 0, ci = 0;
        // 开头先说一个事实
        if (fi < facts.length) s.turns.add(facts[fi++]);
        for (int k = 0; k < chatChains; k++) {
            for (Q q : chains.get(ci++ % chains.size())) s.turns.add(q);
            if (k == 1 && fi < facts.length) s.turns.add(facts[fi++]);
            if (k == 3 && hi < heavies.length) s.turns.add(heavies[hi++]);
            if (trips && k == 4) {
                s.turns.add(TRIP1);
                s.turns.add(TRIP2);
            }
            if (k == 5 && fi < facts.length) s.turns.add(facts[fi++]);
            if (k == 6 && hi < heavies.length) s.turns.add(heavies[hi++]);
        }
        while (fi < facts.length) s.turns.add(facts[fi++]);
        // 再聊几条（让早先的事实被挤远）
        for (int k = 0; k < 3; k++) for (Q q : chains.get(ci++ % chains.size())) s.turns.add(q);
        if (trips) {
            s.turns.add(TRIP3);
            s.turns.add(chains.get(0)[0]);   // 穿插：回到最早聊过的、已经答完的话题（同一句问法）
            s.turns.add(TRIP4);
        }
        for (Q r : recalls) {
            s.turns.add(r);
            s.turns.add(chains.get(ci++ % chains.size())[0]);
        }
        return s;
    }

    public static List<Scenario> all() {
        List<Scenario> l = new ArrayList<Scenario>();
        l.add(build("日常闲聊 + 通勤事实", 11, 12, new Q[]{F_NAME}, new Q[]{R_NAME}, false, new Q[]{H_SCREEN}));
        l.add(build("出差规划 + 航班", 23, 12, new Q[]{F_FLIGHT}, new Q[]{R_FLIGHT, R_FLIGHT2}, true, new Q[]{H_SET}));
        l.add(build("饮食 + 过敏", 37, 12, new Q[]{F_ALLERGY, F_NAME}, new Q[]{R_ALLERGY, R_NAME}, false, new Q[]{H_SET, H_SCREEN}));
        l.add(build("停车 + 出差", 41, 13, new Q[]{F_PARK, F_FLIGHT}, new Q[]{R_PARK, R_FLIGHT}, true, new Q[]{H_SCREEN}));
        l.add(build("超长会话（全部）", 53, 30, new Q[]{F_NAME, F_FLIGHT, F_ALLERGY, F_PARK},
                new Q[]{R_PARK, R_ALLERGY, R_FLIGHT2, R_NAME, R_FLIGHT}, true, new Q[]{H_SET, H_SCREEN}));
        l.add(build("短会话对照", 67, 2, new Q[]{F_PARK}, new Q[]{R_PARK}, false, new Q[]{}));
        return l;
    }

    /** 文字 → 题（评测器 / 假模型按原话认题）。 */
    public static Q byText(String text) {
        if (text == null) return null;
        String t = text.trim();
        for (Q q : POOL.values()) if (q.text.equals(t)) return q;
        return null;
    }

    public static Q byId(String id) {
        return POOL.get(id);
    }

    public static java.util.Collection<Q> pool() {
        return POOL.values();
    }
}
