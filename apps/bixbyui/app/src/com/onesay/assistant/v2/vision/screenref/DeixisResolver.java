package com.onesay.assistant.v2.vision.screenref;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [screenref] 指代消解：把"导航去这里 / 打这个电话 / 复制这个 / 把上面的时间加到日历"落到一个屏幕实体上。
 * 纯 Java（JVM 可测），不碰 UI、不碰网络。
 *
 * <h3>规则</h3>
 * <ol>
 *   <li><b>是否指代</b>：含"这个/这条/这里/这儿/这家/屏幕上/页面上/上面的/刚才那个/刚刚那个"等指示词，
 *       或"复制取件码 / 查快递"这种<b>屏幕专属名词且没给出具体值</b>的说法；"这个周末/这周"等时间短语不算。</li>
 *   <li><b>动词定类型</b>：导航/去/到 → 地址；打/拨/呼叫 → 电话；加日历/提醒/建日程 → 时间；
 *       打开/访问 → 链接；查快递/查物流 → 单号；发邮件 → 邮箱；复制 → 任意（按名词提示或优先级：取件码 > 单号 > 电话 > 链接 > 地址 > 邮箱 > 金额 > 时间）。</li>
 *   <li><b>名词提示</b>（地址/电话/链接/单号/取件码/时间…）在"复制""打开"这类宽动词下决定类型。</li>
 *   <li><b>裁决</b>：同类型只有 1 个 → 直接执行；有序数词（第一个/第二个/最后一个）→ 取对应项；
 *       话里带了能区分的字（"北京那个地址""顺丰的单号"）→ 按片段匹配；置信度领先 ≥0.2 → 取第一；否则<b>歧义</b>，给出 ≤3 个候选让用户选。</li>
 *   <li>有指代但动词不在上表（"这个是什么意思""把这个发给老婆"）→ {@link Kind#NEEDS_AGENT}：交给 AgentLoop，并自动注入屏幕上下文。</li>
 *   <li>[round7] 复合任务（"看看屏幕上写的什么然后打开微信"）→ {@link #isCompoundTask}：面板入口不接管，交路由层 / AgentLoop。</li>
 * </ol>
 */
public final class DeixisResolver {

    private DeixisResolver() {}

    public enum Action { NAVIGATE, CALL, CALENDAR, COPY, OPEN_URL, TRACK, EMAIL, NONE }

    public enum Kind {
        /** 不是指代屏幕的说法，原流程照旧。 */
        NOT_DEIXIS,
        /** 已唯一确定实体与动作。 */
        RESOLVED,
        /** 多个候选，需要用户选。 */
        AMBIGUOUS,
        /** 是指代，但屏幕上没有对应类型的实体。 */
        NO_MATCH,
        /** 是指代，但动作需要模型来理解（交 AgentLoop + 注入屏幕上下文）。 */
        NEEDS_AGENT
    }

    public static final class Resolution {
        public final Kind kind;
        public final Action action;
        public final ScreenEntity entity;
        public final List<ScreenEntity> candidates;
        /** 需要的实体类型（NO_MATCH 时用于提示"屏幕上没找到地址"）。 */
        public final ScreenEntity.Type wantedType;

        Resolution(Kind kind, Action action, ScreenEntity entity, List<ScreenEntity> candidates, ScreenEntity.Type wantedType) {
            this.kind = kind;
            this.action = action;
            this.entity = entity;
            this.candidates = candidates == null ? Collections.<ScreenEntity>emptyList() : candidates;
            this.wantedType = wantedType;
        }

        @Override
        public String toString() {
            return kind + "/" + action + (entity != null ? " " + entity : "") + (candidates.isEmpty() ? "" : " cands=" + candidates);
        }
    }

    public static final int MAX_CANDIDATES = 3;

    private static final Pattern DEIXIS = Pattern.compile(
            "这个|这条|这页|这一页|这张图|这里|这儿|这边|这家|这一条|这串|这段|此处|屏幕上|屏幕里|屏幕中|页面上|页面里|界面上|上面的|上面那|上边的|下面的|下面那|"
                    + "上面第|下面第|最后一个|最后那个|最上面|最下面|第[一二三四五六七八九十1-9]个?(?:地址|电话|号码|链接|单号|时间|地方|位置)|刚才那|刚刚那|刚才的|刚刚的|那个地址|那个电话|那个号码|那个链接|那个单号|那个时间|那个取件码|那条|图上|当前页面");
    /** "这个周末/这周/这个月"等时间短语里的"这"不是屏幕指代。 */
    private static final Pattern DEIXIS_FALSE = Pattern.compile("这个?(?:周末|星期|礼拜|月|季度|学期|时候|样子|样|么|么样)|这周|这次|这会儿|这么|这样");
    /**
     * [s1] 指向剪贴板（「刚复制的地址」「剪贴板里的电话」）。命中时只在剪贴板实体里找，且"复制"二字不当动词。
     */
    private static final Pattern CLIP_REF = Pattern.compile(
            "刚才复制的|刚刚复制的|刚复制的|才复制的|复制的|刚才拷贝的|刚拷贝的|拷贝的|剪贴板里?的?|剪切板里?的?|粘贴板里?的?");
    private static final Pattern IMPLICIT_NOUN = Pattern.compile("取件码|取货码|提货码|取餐码|取餐号|验证码|快递单号|运单号|物流单号|单号|快递|物流");
    private static final Pattern HAS_VALUE = Pattern.compile("[A-Za-z]*\\d{4,}|[A-Za-z0-9]+-[A-Za-z0-9]+");

    private static final Pattern V_NAV = Pattern.compile("导航|带我去|怎么去|怎么走|路线|开车去|打车去|骑车去|走过去|去这|去那|到这|到那|过去|去一下|^去|^到");
    private static final Pattern V_CALL = Pattern.compile("打电话|打给|打过去|打一下|拨打|拨号|拨一下|呼叫|致电|给.{0,4}打|打这|打那|^打|^拨|call");
    private static final Pattern V_CAL = Pattern.compile("日历|日程|提醒我|设个?提醒|加提醒|记到|备忘|安排上|闹钟");
    private static final Pattern V_COPY = Pattern.compile("复制|拷贝|copy|记下|抄下");
    private static final Pattern V_TRACK = Pattern.compile("查快递|查物流|查一下快递|查单号|快递到哪|物流到哪|到哪了|查.{0,5}(?:单号|快递|物流)|追踪");
    private static final Pattern V_OPEN = Pattern.compile("打开|访问|点开|进入|浏览|看看这个链接|开一下");
    private static final Pattern V_EMAIL = Pattern.compile("发邮件|写邮件|发封邮件|发个邮件|邮件给");

    /** 名词提示（顺序有意义："号码"必须先于"码"，"邮件地址"先于"地址"）。 */
    private static final Object[][] NOUNS = {
            {Pattern.compile("电话|号码|手机号|手机|座机|热线|客服"), ScreenEntity.Type.PHONE},
            {Pattern.compile("取件码|取货码|提货码|取餐码|取餐号|验证码|兑换码|券码|码"), ScreenEntity.Type.CODE},
            {Pattern.compile("快递单号|运单号|物流单号|单号|快递|物流"), ScreenEntity.Type.TRACKING},
            {Pattern.compile("链接|网址|网页|网站|url|URL"), ScreenEntity.Type.URL},
            {Pattern.compile("邮箱|邮件地址|email"), ScreenEntity.Type.EMAIL},
            {Pattern.compile("地址|位置|地点|这里|这儿|那里|门店|店"), ScreenEntity.Type.ADDRESS},
            {Pattern.compile("时间|日期|几点|会议|活动|日程"), ScreenEntity.Type.DATETIME},
            {Pattern.compile("金额|价格|多少钱|费用|钱数"), ScreenEntity.Type.AMOUNT},
    };

    private static final ScreenEntity.Type[] COPY_PRIORITY = {
            ScreenEntity.Type.CODE, ScreenEntity.Type.TRACKING, ScreenEntity.Type.PHONE, ScreenEntity.Type.URL,
            ScreenEntity.Type.ADDRESS, ScreenEntity.Type.EMAIL, ScreenEntity.Type.AMOUNT, ScreenEntity.Type.DATETIME};

    private static final Pattern ORDINAL = Pattern.compile("第\\s*([一二三四五六七八九十1-9])\\s*(?:个|条|项|处)?|最后一?个|最后那个|最下面|最上面");

    /**
     * [round7] 复合任务的顺序连接词（「看看屏幕上写的什么<b>然后</b>打开微信」）。
     * 不收句首的「再」（「再拨一下这个号码」是重复，不是复合），裸「再」只在后面紧跟另起动作时才算（再去/再打开…）。
     */
    private static final Pattern SEQ = Pattern.compile(
            "然后|接着|随后|之后再|之后|完了再|完再|以后再|并且|顺便|同时|再去|再帮我|再给我|再把|再打开|再启动|再进入|再发|再告诉|再搜");
    /** [round7] 复合句后半句「另起一件事」的动词（打开 App / 发消息 / 告诉 / 搜索 / 播放 / 改设置…）。 */
    private static final Pattern NEXT_ACT = Pattern.compile(
            "打开|启动|进入|点开|发给|发送|发消息|发微信|发短信|告诉|分享|转发|回复|搜索|搜一下|百度|播放|放一首|打车|叫车|设置|设个|关掉|关闭|开启|调高|调低|调大|调小|打给|拨打|提醒|记一下|记下来|截图|截屏");
    /** [round7] 前半句是「读 / 看屏幕」（没有具体动作对象，只为拿到屏幕内容）。 */
    private static final Pattern READ_CLAUSE = Pattern.compile(
            "看看|看一下|看下|瞧瞧|读一下|读读|读出|念一下|念念|念出|写的什么|写了什么|写的是什么|写着什么|说的什么|说了什么|是什么|有什么|有哪些|识别|总结|概括");

    /**
     * [round7] 复合任务：<b>前半句读/指代屏幕 + 顺序连接词 + 后半句另起动作</b>（「看看屏幕上写的什么然后打开微信」
     * 「复制这个取件码然后打开淘宝」）。这类句子识屏指代只能做前半句（单实体动作），后半句会被吞掉或误判成
     * 「打开链接」→ NO_MATCH，所以<b>不由识屏接管</b>：交路由层 → 分诊（多步 DO_ACT）→ AgentLoop；
     * AgentLoop 仍按 {@link #hasDeixis} 追加 screen.* 工具 / 注入屏幕上下文，读屏后置污点，后续 app.launch 走红卡。
     * 纯指代（「打开屏幕上这个链接」「导航去这里」「复制这个」）没有连接词 → 照旧由识屏接管。
     */
    public static boolean isCompoundTask(String utterance) {
        String u = norm(utterance);
        if (u.length() < 4) return false;
        Matcher m = SEQ.matcher(u);
        while (m.find()) {
            if (m.start() < 2) continue;                       // 句首连接词 / 前半句太短：不算
            String left = u.substring(0, m.start());
            String right = u.substring(m.start());              // 含连接词本身（「再打开」里的「打开」要算进来）
            boolean leftScreen = READ_CLAUSE.matcher(left).find() || hasDeixisCore(left);
            if (leftScreen && NEXT_ACT.matcher(right).find()) return true;
        }
        return false;
    }

    /** {@link #hasDeixis} 的显式指示词部分（不含剪贴板 / 隐式名词规则），给 {@link #isCompoundTask} 判前半句。 */
    private static boolean hasDeixisCore(String normalized) {
        String cleaned = DEIXIS_FALSE.matcher(normalized).replaceAll("");
        return DEIXIS.matcher(cleaned).find() || (IMPLICIT_NOUN.matcher(normalized).find() && !HAS_VALUE.matcher(normalized).find());
    }

    /**
     * 纯询问（「屏幕上这是什么」「这页讲什么」「这个是什么意思」）——只读、只要一个回答，
     * 不进 AgentLoop 多步（会读屏置污点、后续步骤弹「做了就撤不回来」红卡）；由识屏入口直接走
     * {@code FloatingSheetService.triggerScreenVision(command)}。带动作 / 复合 / 外发动词的不算。
     */
    private static final Pattern PURE_Q = Pattern.compile(
            "是什么|是啥|什么意思|啥意思|(?:讲|说|写|画|显示|在讲|在说)(?:的|了|着)?(?:是)?(?:什么|啥)|有(?:什么|啥|哪些)|怎么回事|是谁|"
                    + "什么内容|啥内容|什么东西|啥东西|什么情况|啥情况|总结|概括|解释|翻译|看得懂|看不懂|多少钱|几点");
    private static final Pattern NOT_PURE = Pattern.compile(
            "发给|发送|发到|发个|告诉|分享|转发|回复|保存|存到|存一下|下载|删除|删掉|设置|设成|设为|改成|填|输入|提交|买|下单|付款|支付|收藏|点赞|关注");

    public static boolean isPureQuestion(String utterance) {
        String u = norm(utterance);
        if (u.length() < 3 || !hasDeixis(utterance)) return false;
        if (isCompoundTask(utterance) || actionOf(utterance) != Action.NONE) return false;
        if (NOT_PURE.matcher(u).find()) return false;
        return PURE_Q.matcher(u).find();
    }

    /** 归一化：去空白与常见标点。 */
    static String norm(String u) {
        if (u == null) return "";
        return u.replaceAll("[\\s，,。.！!？?、~～…]+", "");
    }

    /** 是否指代屏幕内容（public 供入口快速预判）。 */
    public static boolean hasDeixis(String utterance) {
        String u = norm(utterance);
        if (u.length() == 0) return false;
        String cleaned = DEIXIS_FALSE.matcher(u).replaceAll("");
        if (DEIXIS.matcher(cleaned).find()) return true;
        if (CLIP_REF.matcher(u).find() && actionOf(u) != Action.NONE) return true;   // [s1] 「导航到刚复制的地址」
        // 隐式：屏幕专属名词 + 没给具体值（"复制取件码""查快递"）
        if (IMPLICIT_NOUN.matcher(u).find() && !HAS_VALUE.matcher(u).find()
                && (V_COPY.matcher(u).find() || V_TRACK.matcher(u).find() || u.startsWith("查"))) {
            return true;
        }
        return false;
    }

    /** 动词 → 动作。 */
    public static Action actionOf(String utterance) {
        String u = CLIP_REF.matcher(norm(utterance)).replaceAll("");   // [s1] 「刚复制的」里的"复制"不是动词
        if (V_COPY.matcher(u).find()) return Action.COPY;
        if (V_TRACK.matcher(u).find()) return Action.TRACK;
        if (V_CAL.matcher(u).find()) return Action.CALENDAR;
        if (V_EMAIL.matcher(u).find()) return Action.EMAIL;
        if (V_NAV.matcher(u).find()) return Action.NAVIGATE;
        if (V_OPEN.matcher(u).find()) return Action.OPEN_URL;
        if (V_CALL.matcher(u).find()) return Action.CALL;
        return Action.NONE;
    }

    /** 名词提示 → 类型（可为 null）。 */
    public static ScreenEntity.Type nounType(String utterance) {
        String u = norm(utterance);
        for (Object[] kv : NOUNS) {
            if (((Pattern) kv[0]).matcher(u).find()) return (ScreenEntity.Type) kv[1];
        }
        return null;
    }

    /** 序数词：0-based 下标；"最后一个/最下面" = Integer.MAX_VALUE；"最上面" = 0；无 = -1。 */
    public static int ordinalOf(String utterance) {
        String u = norm(utterance);
        Matcher m = ORDINAL.matcher(u);
        if (!m.find()) return -1;
        String g = m.group();
        if (g.startsWith("最后") || g.startsWith("最下")) return Integer.MAX_VALUE;
        if (g.startsWith("最上")) return 0;
        String d = m.group(1);
        int n = DateTimeNormalizer.cnOrInt(d);
        return n > 0 ? n - 1 : -1;
    }

    /** [s1] 话里是否明确指向剪贴板（「导航到刚复制的地址」）。 */
    public static boolean refersToClipboard(String utterance) {
        return CLIP_REF.matcher(norm(utterance)).find();
    }

    /**
     * [s1] 带剪贴板兜底的主入口。
     * <ul>
     *   <li>话里明确指向剪贴板（{@link #refersToClipboard}）→ 只在 {@code clipEntities} 里消解；</li>
     *   <li>否则先按屏幕消解；屏幕上<b>没有</b>所需类型（NO_MATCH）且剪贴板（5 分钟内）有 → 用剪贴板里最近的那个（低优先级来源）；</li>
     *   <li>「复制」类动作不走剪贴板兜底（把剪贴板再复制一遍没有意义）。</li>
     * </ul>
     * {@code clipEntities} 为空时与 {@link #resolve(String, List)} 完全一致。
     */
    public static Resolution resolve(String utterance, List<ScreenEntity> entities, List<ScreenEntity> clipEntities) {
        List<ScreenEntity> clip = clipEntities == null ? Collections.<ScreenEntity>emptyList() : clipEntities;
        if (refersToClipboard(utterance) && hasDeixis(utterance)) {
            return resolve(utterance, clip);
        }
        Resolution r = resolve(utterance, entities);
        if (r.kind != Kind.NO_MATCH || clip.isEmpty() || r.action == Action.COPY || r.wantedType == null) return r;
        List<ScreenEntity> fromClip = filterType(clip, r.wantedType);
        if (fromClip.isEmpty()) return r;
        return new Resolution(Kind.RESOLVED, r.action, fromClip.get(0), fromClip, r.wantedType);
    }

    /** 主入口。 */
    public static Resolution resolve(String utterance, List<ScreenEntity> entities) {
        if (!hasDeixis(utterance)) return new Resolution(Kind.NOT_DEIXIS, Action.NONE, null, null, null);
        List<ScreenEntity> all = entities == null ? Collections.<ScreenEntity>emptyList() : entities;
        // [round7] 复合任务（读屏 + 然后另起动作）：单实体动作做不完 → 交 AgentLoop（面板入口已在拦截处放行，这里保持口径一致）
        if (isCompoundTask(utterance)) return new Resolution(Kind.NEEDS_AGENT, Action.NONE, null, all, null);
        Action action = actionOf(utterance);
        ScreenEntity.Type noun = nounType(utterance);

        ScreenEntity.Type wanted;
        switch (action) {
            case NAVIGATE: wanted = ScreenEntity.Type.ADDRESS; break;
            case CALL: wanted = ScreenEntity.Type.PHONE; break;
            case CALENDAR: wanted = ScreenEntity.Type.DATETIME; break;
            case TRACK: wanted = ScreenEntity.Type.TRACKING; break;
            case EMAIL: wanted = ScreenEntity.Type.EMAIL; break;
            case OPEN_URL:
                // "打开这个"：屏幕上有链接就是链接；名词指向别的（"打开这个地址"）→ 导航
                if (noun == ScreenEntity.Type.ADDRESS) { action = Action.NAVIGATE; wanted = ScreenEntity.Type.ADDRESS; }
                else if (noun == ScreenEntity.Type.EMAIL) { action = Action.EMAIL; wanted = ScreenEntity.Type.EMAIL; }
                else wanted = ScreenEntity.Type.URL;
                break;
            case COPY: wanted = noun; break;   // 可为 null：按优先级挑
            default:
                return new Resolution(Kind.NEEDS_AGENT, Action.NONE, null, filterType(all, noun), noun);
        }

        List<ScreenEntity> cands;
        if (wanted == null) {
            cands = Collections.emptyList();
            for (ScreenEntity.Type t : COPY_PRIORITY) {
                List<ScreenEntity> c = usable(filterType(all, t), t);
                if (!c.isEmpty()) { cands = c; wanted = t; break; }
            }
        } else {
            cands = usable(filterType(all, wanted), wanted);
        }
        if (cands.isEmpty()) return new Resolution(Kind.NO_MATCH, action, null, null, wanted);

        sortByPosition(cands);
        int ord = ordinalOf(utterance);
        if (ord >= 0) {
            int idx = ord == Integer.MAX_VALUE ? cands.size() - 1 : ord;
            if (idx < cands.size()) return new Resolution(Kind.RESOLVED, action, cands.get(idx), cands, wanted);
        }
        // 去重后只剩一个
        if (cands.size() == 1) return new Resolution(Kind.RESOLVED, action, cands.get(0), cands, wanted);

        // 话里带了能区分的字（"北京那个""顺丰的"）
        List<ScreenEntity> byHint = filterByHint(utterance, cands);
        if (byHint.size() == 1) return new Resolution(Kind.RESOLVED, action, byHint.get(0), cands, wanted);

        // 置信度明显领先
        List<ScreenEntity> byConf = new ArrayList<ScreenEntity>(cands);
        Collections.sort(byConf, new Comparator<ScreenEntity>() {
            @Override
            public int compare(ScreenEntity a, ScreenEntity b) {
                return Double.compare(b.confidence, a.confidence);
            }
        });
        if (byConf.get(0).confidence - byConf.get(1).confidence >= 0.2) {
            return new Resolution(Kind.RESOLVED, action, byConf.get(0), cands, wanted);
        }
        List<ScreenEntity> top = cands.size() > MAX_CANDIDATES ? new ArrayList<ScreenEntity>(cands.subList(0, MAX_CANDIDATES)) : cands;
        return new Resolution(Kind.AMBIGUOUS, action, null, top, wanted);
    }

    /** 用户在歧义追问后只说了"第二个/北京那个"：在上次候选里挑。挑不出返回 null。 */
    public static ScreenEntity pickFromCandidates(String utterance, List<ScreenEntity> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        int ord = ordinalOf(utterance);
        if (ord >= 0) {
            int idx = ord == Integer.MAX_VALUE ? candidates.size() - 1 : ord;
            return idx < candidates.size() ? candidates.get(idx) : null;
        }
        List<ScreenEntity> byHint = filterByHint(utterance, candidates);
        return byHint.size() == 1 ? byHint.get(0) : null;
    }

    // ---------- 内部 ----------

    private static List<ScreenEntity> filterType(List<ScreenEntity> all, ScreenEntity.Type t) {
        List<ScreenEntity> r = new ArrayList<ScreenEntity>();
        if (t == null) return r;
        for (ScreenEntity e : all) if (e.type == t) r.add(e);
        return r;
    }

    /** 时间：有"日期级"（置信度≥0.5）的就只用它们，聊天列表里的孤立钟点不参与。 */
    private static List<ScreenEntity> usable(List<ScreenEntity> list, ScreenEntity.Type t) {
        if (t != ScreenEntity.Type.DATETIME) return list;
        List<ScreenEntity> strong = new ArrayList<ScreenEntity>();
        for (ScreenEntity e : list) if (e.confidence >= 0.5) strong.add(e);
        return strong.isEmpty() ? list : strong;
    }

    private static void sortByPosition(List<ScreenEntity> list) {
        Collections.sort(list, new Comparator<ScreenEntity>() {
            @Override
            public int compare(ScreenEntity a, ScreenEntity b) {
                if (a.screenY >= 0 && b.screenY >= 0 && a.screenY != b.screenY) return a.screenY - b.screenY;
                return a.order - b.order;
            }
        });
    }

    private static final Pattern HINT_STRIP = Pattern.compile(
            "导航|带我去|怎么去|拨打|打电话|打给|呼叫|复制|拷贝|打开|访问|查快递|查物流|加到|加入|添加|日历|日程|提醒我|"
                    + "刚复制的|复制的|剪贴板|剪切板|这个|这条|这里|这儿|那个|那条|屏幕上|上面的|刚才|刚刚|地址|电话|号码|链接|单号|取件码|时间|的|去|到|把|给|我|一下|帮");

    private static List<ScreenEntity> filterByHint(String utterance, List<ScreenEntity> cands) {
        String rest = HINT_STRIP.matcher(norm(utterance)).replaceAll(" ").trim();
        List<ScreenEntity> r = new ArrayList<ScreenEntity>();
        if (rest.length() < 2) return r;
        Set<String> grams = new LinkedHashSet<String>();
        for (String part : rest.split("\\s+")) {
            for (int i = 0; i + 2 <= part.length(); i++) grams.add(part.substring(i, i + 2).toLowerCase());
        }
        if (grams.isEmpty()) return r;
        int best = 0;
        for (ScreenEntity e : cands) {
            String hay = ((e.raw == null ? "" : e.raw) + "|" + (e.value == null ? "" : e.value) + "|"
                    + (e.subLabel == null ? "" : e.subLabel)).toLowerCase();
            int hit = 0;
            for (String g : grams) if (hay.contains(g)) hit++;
            if (hit > best) { best = hit; r.clear(); r.add(e); }
            else if (hit == best && hit > 0) r.add(e);
        }
        return best > 0 ? r : new ArrayList<ScreenEntity>();
    }
}
