package com.onesay.assistant.v2.sched;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [sched] 本地日程的语音 / 文字引擎（纯 Java，JVM 可测：SchedEngineTest）。
 *
 * <h3>流程</h3>
 * {@link #plan}（只读：判断这句话是不是日程、要做什么；同一句话反复调结果一样）→ {@link #apply}（落库 / 改追问状态）。
 * 路由层 {@code SchedHooks.route} 先 plan 再 apply；{@code claims} 只 plan。
 *
 * <h3>确认规则（用户 2026-10-24 定）</h3>
 * <ul>
 *   <li>新建：不确认，直接建；回执里说「说撤销可以撤回」，{@link #UNDO_WINDOW_MS} 内说「撤销」= 软删；</li>
 *   <li>修改 / 删除：先说清楚要改哪条、改成什么，等「确认」；说「算了」放弃。{@link #PENDING_MS} 内没回就作废；</li>
 *   <li>有歧义：追问（凌晨还是下午 / 今天还是下周三 / 哪一条），回答后继续。</li>
 * </ul>
 *
 * <h3>不接管（交给原有流程）</h3>
 * 「提醒我…」（S3 提醒）、闹钟 / 叫醒、待办、定时任务、明确说「谷歌 / Google 日历」（B 线连接器）、「打开日历」、问天气等。
 */
public final class SchedEngine {

    public static final long PENDING_MS = 2L * 60000L;
    public static final long UNDO_WINDOW_MS = 10L * 60000L;
    static final long DUP_MS = 5000L;

    public enum Kind { CREATED, ASK, CONFIRM, UPDATED, DELETED, UNDONE, CANCELLED, LISTED, NOT_FOUND, ERROR }

    public static final class Reply {
        public final Kind kind;
        public final String text;
        public long eventId;
        /** ★ int/1102 问一次记住：这次回答要记成的偏好（SchedHooks 落到 prefs；null = 不记）。 */
        public String prefKey, prefVal;
        /** ★ int/1102：追问时给的选项芯片（点一下 = 说了这句；null = 按回执种类给默认芯片）。 */
        public List<String> chips;

        Reply(Kind k, String t) {
            kind = k;
            text = t;
        }

        @Override public String toString() {
            return kind + ": " + text;
        }
    }

    /** plan 的结果（不可变地描述要做的事）。 */
    public static final class Plan {
        enum Op { CREATE, ASK_FULL, ASK_TIME, ASK_TITLE, ASK_OPTION, QUERY, UPDATE, DELETE, PICK, CONFIRM_YES, CONFIRM_NO, ANSWER, UNDO, NOT_FOUND, ERROR }

        final Op op;
        String utterance;
        SchedEvent draft;
        CnTime.Result time;
        String note = "";
        long from, to;
        String rangeLabel;
        String titleFilter;
        boolean nextOnly;
        List<SchedEvent> targets = new ArrayList<SchedEvent>();
        SchedEvent change;          // UPDATE：改完的样子（对单条）
        String changeDesc;          // UPDATE：改了什么（给人看）
        long shiftMs;               // UPDATE：整体平移
        String message;
        int pick = -1;
        CnTime.Option option;

        Plan(Op op) {
            this.op = op;
        }

        public String op() {
            return op.name();
        }

        @Override public String toString() {
            return op + (draft != null ? " " + draft : "") + (message != null ? " msg=" + message : "") + " targets=" + targets.size();
        }
    }

    /** 等用户回答的状态。 */
    static final class Pending {
        String type;   // ASK_OPTION / ASK_TIME / ASK_TITLE / ASK_WEEKDAY / CONFIRM_UPDATE / CONFIRM_DELETE / PICK_UPDATE / PICK_DELETE
        long at;
        SchedEvent draft;
        CnTime.Result time;
        List<SchedEvent> targets = new ArrayList<SchedEvent>();
        SchedEvent change;
        String changeDesc;
        long shiftMs;
        String rawUpdate;   // PICK_UPDATE：原话（选定后重算改动）
        String prefKey;     // ★ int/1102 问一次记住：这次追问的回答要记成哪个偏好（null = 不记）
    }

    /** 最近一次可撤销的动作。 */
    static final class Undo {
        String type;   // CREATE / DELETE / UPDATE
        long at;
        List<SchedEvent> before = new ArrayList<SchedEvent>();
        List<Long> ids = new ArrayList<Long>();
    }

    public static final class Settings {
        public int defaultLead = 10;
        /** ★ int/1102（用户 2026-11-03 拍板「首次问一次、记住答案」）：只说日子的提醒默认几点（当天分钟数）；-1 = 还没问过。 */
        public int remindMinute = -1;
        /** ★ int/1102：说「今天」而默认钟点已过时怎么办：null = 还没问过；{@link #LATE_ASK} / {@link #LATE_1H} / {@link #LATE_EVENING}。 */
        public String todayLate;
        /** ★ int/1102：列日程时出 Markdown 表格（富内容插件开时由 SchedHooks 置位；关时出列表行）。 */
        public boolean tableList;
    }

    private final SchedStore mStore;
    private final TimeZone mTz;
    private Pending mPending;
    private Undo mUndo;
    private long mLastTouched;
    private String mLastUtt;
    private long mLastUttAt;
    private Reply mLastReply;
    public final Settings settings = new Settings();

    public SchedEngine(SchedStore store, TimeZone tz) {
        mStore = store;
        mTz = tz == null ? TimeZone.getDefault() : tz;
    }

    public SchedStore store() {
        return mStore;
    }

    public TimeZone tz() {
        return mTz;
    }

    /** 有没有在等回答（控制台 / 日志）。 */
    public synchronized String pendingType(long now) {
        Pending p = livePending(now);
        return p == null ? null : p.type;
    }

    // ================================================================== 正则

    static final Pattern NOT_OURS = Pattern.compile("闹钟|叫醒|叫我起床|起床|倒计时|计时器|定时任务|待办|谷歌|google|Google|GOOGLE|备忘录|便签|笔记|天气|气温|温度|几度|下雨|下雪|空气|限行|股价|汇率");
    static final Pattern REMIND_WORD = Pattern.compile("提醒|叫我|通知我");
    static final Pattern KW = Pattern.compile("日程|行程|日历|安排");
    static final Pattern OPEN_APP = Pattern.compile("^(打开|启动|进入|切换到)(一下)?(系统)?(日历|日程|行程)(应用|app|App|APP)?(页面)?$");
    static final Pattern CREATE_VERB = Pattern.compile("^(帮我|给我|请)?(添加|新建|创建|新增|加|加个|加一个|建个|建一个|记个|记一个|记一下|记下|记|安排|安排一下|登记|定个|约)(一个|个|一下)?(日程|行程|会议|安排)?[:：，,]?");
    static final Pattern ADD_TO = Pattern.compile("^(把|将)?(.+?)(加到|加进|加入|记到|记进|放到|放进|写到|写进|添加到)(我的)?(日程|日历|行程)(里|里面|中|表)?(去)?$");
    static final Pattern KW_PREFIX = Pattern.compile("^(日程|行程)[:：，, ]+");
    static final Pattern EVENT_LEX = Pattern.compile(
            "会|面试|考试|上课|下课|课|聚餐|吃饭|午饭|晚饭|早饭|饭局|聚会|约|见|拜访|看病|看牙|牙医|复诊|体检|就诊|挂号|医院|航班|飞机|高铁|火车|动车|出差|培训|讲座|演出|音乐会|电影|比赛|"
                    + "生日|纪念日|婚礼|值班|加班|交|截止|deadline|报告|答辩|汇报|评审|讨论|面谈|签约|接|送|取|办|健身|跑步|游泳|瑜伽|打球|羽毛球|篮球|足球|理发|剪头|洗车|保养|年检|"
                    + "还款|还信用卡|缴费|交房租|房租|开学|放假|出发|回家|返程|登机|值日|家长会|例会|周会|晨会|站会|复盘|约会|相亲|喝茶|喝咖啡|逛街|旅行|旅游|搬家|装修|签证|快递|开学典礼|典礼|仪式|活动|晨跑|锻炼|运动|吃药|打针|疫苗|上班|培训|自习|补课|辅导|练琴|彩排|排练|直播|路演|开庭|报到|报名");
    // ★ int/1102 日程常识（用户 2026-11-02：「记日程它直接说我记住了」）：
    /** 记忆式前缀（「记住 / 别忘了 / 记得 …明天三点开会」）：剥掉再按日程解析，标题里不留这些字。 */
    static final Pattern MEMO_PREFIX = Pattern.compile("^(帮我|请|你)?(记住|记着|记得|别忘了|不要忘了|别让我忘了|提醒一下)(一下)?[:：，, ]*");
    /** 事实陈述（「我女儿生日是5月3号」「结婚纪念日在6月」）：不是日程，交给记忆。 */
    static final Pattern FACT_TAIL = Pattern.compile("(是|为|在|叫)$");
    /** 相对时长（「10分钟后提醒我」「半小时后」）：纯提醒，归 S3。 */
    static final Pattern RELATIVE = Pattern.compile("(" + CnTime.N + "|半|一会儿?|过会儿?)(个)?(半)?(分钟|分|小时|钟头|秒)(以?后|之后|后)|(一会儿|待会儿?|过会儿?)");
    /** 叫醒 / 闹钟式响铃：归闹钟（clock.alarm.set / S3），不建日程。 */
    static final Pattern ALARM_WORD = Pattern.compile("叫我|叫醒|闹钟|喊我");
    /** 凌晨也合理的事（不默认下午）：交通 / 早起类。 */
    static final Pattern EARLY_LEX = Pattern.compile("航班|飞机|登机|高铁|火车|动车|赶车|赶飞机|起床|晨跑|出发|值班|夜班|看日出|日出|接机|送机");
    /** 只说「加个日程」「新建日程」：什么都没给 → 问一句（不交给记一记 / LLM）。 */
    static final Pattern BARE_CREATE = Pattern.compile("^(帮我|给我|请)?(添加|新建|创建|新增|加|建|记|安排|登记|定)(一个|个|一下|一条|条)?(新的)?(日程|行程)(吧)?$");
    /** 「今天的日程」「明天安排」「日程表」这类不带问词的查看。 */
    static final Pattern LIST_STMT = Pattern.compile("^(帮我)?(查看|看看|看一下|打开|列出|列一下|说说|读一下)?(我的)?(今天|明天|后天|这周|本周|下周|周末|这个周末|最近)?(的)?(全部|所有)?(日程|行程|安排)(表|列表|安排)?$");
    /** ★ audit-J（Gemini 真机 SCHED-04）：通信动词在时间短语之前 =「给老王发微信说明天下午三点开会」是发消息，不建日程。 */
    static final Pattern COMM_VERB = Pattern.compile(
            "发(?:个|条|一条|一个)?(?:微信|消息|短信|信息|语音)|微信(?:告诉|跟|和|通知|说)|打电话给|给[^，,。\\d点号]{1,8}?打(?:个)?电话|打给[^，,。]{1,8}"
                    + "|(?:跟|和)(?!我)[^，,。\\d点号]{1,8}?(?:说|讲)|告诉(?!我)|通知(?!我)|回复(?!我)|@");
    /** 时间短语起点（日期 / 星期 / 时段 / 钟点）。 */
    static final Pattern TIME_START = Pattern.compile(
            "今天|明天|后天|大后天|今晚|明早|明晚|下周|下下周|这周|本周|周[一二三四五六日天]|星期|礼拜|早上|上午|中午|下午|傍晚|晚上|凌晨|"
                    + "[0-9零一二两三四五六七八九十]+(?:点|:|号|日|月)");

    /** 通信动词出现在时间短语之前（或句子根本没有时间）→ true。纯函数，JVM 可测。 */
    public static boolean commBeforeTime(String q) {
        if (q == null) return false;
        java.util.regex.Matcher c = COMM_VERB.matcher(q);
        if (!c.find()) return false;
        java.util.regex.Matcher t = TIME_START.matcher(q);
        return !t.find() || c.start() < t.start();
    }
    static final Pattern PHONE_ACTION = Pattern.compile("^(打开|关闭|关掉|开启|播放|暂停|发|发送|打电话|拨打|导航|搜索|搜一下|查一下|查询|设置|调|调到|拍|截图|录|翻译|下载|购买|买|点外卖|叫车|打车|帮我打)");
    static final Pattern QUESTION = Pattern.compile("什么|哪些|几个|多少|有没有|有啥|吗$|么$|呢$|是不是|几点|忙不忙|忙吗|空不空|有空|查看|查查|看看|看一下|列一下|列出|查一下|我的日程|我的行程|我的安排");
    static final Pattern QUERY_SUBJ = Pattern.compile("日程|行程|安排|会议|开会|有会|约会|活动|课|忙|空");
    static final Pattern NEXT = Pattern.compile("下一个|下个|接下来的?第一个|最近的?一个|最近的一个");
    static final Pattern UPDATE = Pattern.compile("^(把|将)?(.+?)(改到|改成|改为|改在|挪到|推迟到|延后到|延迟到|提前到|换到|调到|调整到|移到|推到|改时间到|改期到|放到)(.+)$");
    static final Pattern SHIFT = Pattern.compile("^(把|将)?(.+?)(往后推|往后挪|推迟|延后|延迟|往前挪|提前)(了)?(" + CnTime.N + "|半)?个?(半)?(分钟|小时|钟头|天)$");
    static final Pattern RENAME = Pattern.compile("^(把|将)?(.+?)(的)?(改名为|改名成|改名叫|名字改成|名字改为|标题改成|标题改为|改叫)(.+)$");
    static final Pattern RELOC = Pattern.compile("^(把|将)?(.+?)的(地点|地址|位置)(改成|改为|改到|换成|设为|是)(.+)$");
    static final Pattern DELETE = Pattern.compile("^(删除|删掉|删了|删去|去掉|移除|取消|取消掉|不要|撤掉)(一下)?(.+?)$");
    static final Pattern DELETE2 = Pattern.compile("^(把|将)?(.+?)(删除|删掉|删了|去掉|移除|取消掉|取消了|取消|不去了|不开了|不办了|作废)(吧)?$");
    static final Pattern CLEAR = Pattern.compile("^(清空|删光|全部删掉|全删了|删除全部|删除所有)(.*)$");
    static final Pattern YES = Pattern.compile("^(确认|确定|确认删除|确认修改|确认一下|好|好的|好啊|好吧|是|是的|对|对的|没错|可以|行|嗯|嗯嗯|没问题|删吧|删|删掉|改吧|改|就这样|ok|OK|Ok|yes)$");
    static final Pattern NO = Pattern.compile("^(不|不要|不用|不用了|算了|取消|别|别改|别删|不了|否|no|No|先不|先不要|不删了|不改了|放弃)$");
    static final Pattern UNDO_P = Pattern.compile("^(撤销|撤回|撤销一下|撤回一下|撤销刚才的?(操作|日程|那个)?|撤回刚才的?(操作|日程|那个)?|刚才(那个|的)?不要了|刚才(那个|的)?(日程)?(删掉|删了|取消|取消掉)(吧)?|恢复刚才(删掉|删除)?的?(日程)?|还原)$");
    static final Pattern PICK = Pattern.compile("^(第)?([一二三四五六七八九1-9])(个|条|项)?$");
    static final Pattern THAT = Pattern.compile("^(刚才|刚刚)(那个|的|那条|建的|加的)?(日程|会|那个)?$|^(它|那个|这个|那条|这条)$");
    static final Pattern FILLER_HEAD = Pattern.compile("^(我|我们|咱们|大家)?(要|得|需要|准备|打算|计划|有|有个|有一个|去|在|于|和|跟|与|的|个|一个|，|,|:|：|。| )+");
    static final Pattern FILLER_TAIL = Pattern.compile("(的|了|吧|呀|啊|哦|哈|一下|，|,|。|！|!| )+$");

    // ================================================================== plan

    /** 只读判断。null = 不是日程的话。 */
    public synchronized Plan plan(String utterance, long now) {
        String q = norm(utterance);
        if (q.length() == 0) return null;
        // ★ trip-decompose：出行安排是复合任务，日程本地层不接（AgentLoop 规划模式里再按具体事件建日程）
        if (com.onesay.assistant.v2.trip.TripIntent.isTrigger(utterance) && livePending(now) == null) return null;

        Pending p = livePending(now);
        if (p != null) {
            Plan a = planAnswer(p, q, now);
            if (a != null) return a;
        }
        if (UNDO_P.matcher(q).matches()) {
            Undo u = mUndo;
            if (u != null && now - u.at <= UNDO_WINDOW_MS) return new Plan(Plan.Op.UNDO);
            if (q.contains("日程")) {
                Plan x = new Plan(Plan.Op.NOT_FOUND);
                x.message = "没有可以撤销的日程操作了。";
                return x;
            }
            return null;   // 「撤销」交给别的功能（S3 / 面板）
        }
        if (OPEN_APP.matcher(q).matches()) return null;
        boolean kw = KW.matcher(q).find();
        if (NOT_OURS.matcher(q).find() && !q.contains("日程") && !q.contains("行程")) return null;
        if (q.contains("谷歌") || q.toLowerCase(Locale.ROOT).contains("google")) return null;
        // ★ NG-07（2026-11-04）：「别忘了交房租」「不要忘了给妈妈打电话」「提醒我交房租」= 要办的事 → 提醒（缺时间就问一次什么时候），
        //   不当记忆写入；「别忘了我的车停在B2」「记住我的工号是123」这类事实照旧归记忆（S2）。在通信判定之前：任务本身可以是打电话。
        // backlog #5「日程 290 抢提醒」（JVM 复现）：「取消每天8点的提醒」被建成日程「取消」、「把吃药的提醒改到9点」被建成
        //   「把吃药的改到」、「今天有什么提醒」被追问「「什么」几点提醒」—— 管理 / 查看已有提醒（不是新事项）一律不接，交给 S3（300）。
        if (!kw && REMIND_MANAGE.matcher(q).find() && !LEAD_EDIT.matcher(q).find()) return null;   // 「把周会改成提前一小时提醒」= 改日程提前量，仍归日程
        Plan fr = planForgetRemind(q, now);
        if (fr != null) return fr;
        if (commBeforeTime(q)) return null;   // ★ audit-J：发微信 / 发消息 / 告诉X … + 时间 = 通信，不归日程（6d-sched 与 SchedulePlugin 290 同源）

        if (BARE_CREATE.matcher(q).matches()) {
            Plan b = new Plan(Plan.Op.ASK_FULL);
            b.utterance = q;
            return b;
        }
        Plan x;
        if ((x = planDelete(q, now, kw)) != null) return x;
        if ((x = planUpdate(q, now, kw)) != null) return x;
        if ((x = planQuery(q, now)) != null) return x;
        return planCreate(q, now, kw);
    }

    private Plan planAnswer(Pending p, String q, long now) {
        if (p.type.startsWith("CONFIRM")) {
            if (YES.matcher(q).matches()) return new Plan(Plan.Op.CONFIRM_YES);
            if (NO.matcher(q).matches()) return new Plan(Plan.Op.CONFIRM_NO);
            return null;
        }
        if (NO.matcher(q).matches()) return new Plan(Plan.Op.CONFIRM_NO);
        if (p.type.startsWith("PICK")) {
            int i = pickIndex(q, p.targets);
            if (i >= 0) {
                Plan x = new Plan(Plan.Op.PICK);
                x.pick = i;
                return x;
            }
            return null;
        }
        if ("ASK_OPTION".equals(p.type)) {
            for (CnTime.Option o : p.time.options) {
                if (q.equals(o.label) || q.contains(o.label) || o.label.contains(q) && q.length() >= 2) {
                    Plan x = new Plan(Plan.Op.ANSWER);
                    x.option = o;
                    return x;
                }
            }
            // 「下午四点」：换了个钟点，日子沿用追问里的那天
            CnTime.Result rc = CnTime.parse(q, now, mTz);
            if (rc.found && rc.hasTime && !rc.timeGuessed && !rc.hasDate && !rc.ambiguous() && !p.time.options.isEmpty()
                    && stripTitle(rc.rest).length() <= 2) {
                long day = CnTime.dayStart(p.time.options.get(0).start, mTz);
                Plan x = new Plan(Plan.Op.ANSWER);
                x.option = new CnTime.Option(q, combine(day, rc.start), rc.end > 0 ? combine(day, rc.end) : 0);
                return x;
            }
            // 「下午」「凌晨」「晚上」「早上」只说时段
            for (CnTime.Option o : p.time.options) {
                String per = o.label.replaceAll("[0-9].*$", "");
                if (per.length() > 0 && q.startsWith(per)) {
                    Plan x = new Plan(Plan.Op.ANSWER);
                    x.option = o;
                    return x;
                }
            }
            Matcher m = PICK.matcher(q);
            if (m.matches()) {
                int i = CnNum.parse(m.group(2)) - 1;
                if (i >= 0 && i < p.time.options.size()) {
                    Plan x = new Plan(Plan.Op.ANSWER);
                    x.option = p.time.options.get(i);
                    return x;
                }
            }
            // 换了个说法重新说时间
            CnTime.Result r = CnTime.parse(q, now, mTz);
            if (r.found && (r.hasTime || r.hasDate) && !r.ambiguous() && stripTitle(r.rest).length() <= 2) {
                Plan x = new Plan(Plan.Op.ANSWER);
                x.time = r;
                return x;
            }
            return null;
        }
        if ("ASK_TIME".equals(p.type) || "ASK_WEEKDAY".equals(p.type)) {
            String pre = p.time != null && p.time.question != null && p.time.question.startsWith("这") ? "这" : "下";
            String t = "ASK_WEEKDAY".equals(p.type) && q.matches("^(周|星期|礼拜)[一二三四五六日天].*") ? pre + q : q;
            CnTime.Result r = CnTime.parse(t, now, mTz);
            if (r.found && (r.hasTime || r.hasDate || r.repeat != SchedEvent.REPEAT_NONE) && stripTitle(r.rest).length() <= 4) {
                Plan x = new Plan(Plan.Op.ANSWER);
                x.time = r;
                return x;
            }
            return null;
        }
        if ("ASK_FULL".equals(p.type)) {
            // 「加个日程」→「明天下午三点开会」：整句按显式新建解析（不要求事件词）
            Plan c = planCreate("添加日程" + q, now, true);
            if (c != null && c.op != Plan.Op.ASK_FULL) return c;
            return null;
        }
        if ("ASK_TITLE".equals(p.type)) {
            if (q.length() > 30 || QUESTION.matcher(q).find()) return null;
            Plan x = new Plan(Plan.Op.ANSWER);
            x.message = stripTitle(q);
            return x.message.length() > 0 ? x : null;
        }
        return null;
    }

    private Plan planQuery(String q, long now) {
        boolean question = QUESTION.matcher(q).find() || LIST_STMT.matcher(q).matches();
        if (!question || !QUERY_SUBJ.matcher(q).find()) return null;
        if (PHONE_ACTION.matcher(q).find() && !q.contains("日程") && !q.contains("行程")) return null;
        if (q.contains("课") && !q.matches(".*(有|几节|什么)课.*")) return null;
        if ((q.contains("忙") || q.contains("空")) && !q.matches(".*(忙不忙|忙吗|有空吗|有没有空|空不空|有空没|忙不).*")) return null;
        CnTime.Result r = CnTime.parse(q, now, mTz);
        Plan x = new Plan(Plan.Op.QUERY);
        long today = CnTime.dayStart(now, mTz);
        if (NEXT.matcher(q).find()) {
            x.nextOnly = true;
            x.from = now;
            x.to = CnTime.addDays(today, 60, mTz);
            x.rangeLabel = "接下来";
        } else if (r.weekStart >= 0) {
            x.from = r.weekStart;
            x.to = CnTime.addDays(r.weekStart, 7, mTz);
            x.rangeLabel = q.contains("下下") ? "下下周" : q.contains("下") ? "下周" : "这周";
            if (x.from < today) x.from = today;
        } else if (r.weekend) {
            x.from = r.start;
            x.to = CnTime.addDays(CnTime.dayStart(r.start, mTz), (r.start == today && isSunday(now)) ? 1 : 2, mTz);
            x.rangeLabel = "周末";
        } else if (r.hasDate && !r.ambiguous()) {
            long d = CnTime.dayStart(r.start, mTz);
            x.from = d;
            x.to = CnTime.addDays(d, 1, mTz);
            x.rangeLabel = SchedFormat.dayWord(d, now, mTz);
            if (r.hasTime && !r.allDay && (q.contains("上午") || q.contains("下午") || q.contains("晚上") || q.contains("中午") || q.contains("早上"))) {
                long[] seg = segment(q, d);
                if (seg != null) {
                    x.from = seg[0];
                    x.to = seg[1];
                    x.rangeLabel = x.rangeLabel + periodOf(q);
                }
            }
        } else if (r.soon) {
            x.from = now;
            x.to = CnTime.addDays(today, 7, mTz);
            x.rangeLabel = "最近一周";
        } else if (q.matches(".*(上午|下午|晚上|中午|早上).*")) {
            long[] seg = segment(q, today);
            x.from = seg == null ? today : seg[0];
            x.to = seg == null ? CnTime.addDays(today, 1, mTz) : seg[1];
            x.rangeLabel = "今天" + periodOf(q);
        } else {
            // 「我的日程」「有什么安排」：今天起 7 天
            boolean generic = q.matches(".*(我的日程|我的行程|我的安排|有什么日程|有哪些日程|日程有哪些|查看日程|看看日程|日程列表).*");
            x.from = generic ? now : today;
            x.to = generic ? CnTime.addDays(today, 7, mTz) : CnTime.addDays(today, 1, mTz);
            x.rangeLabel = generic ? "接下来一周" : "今天";
        }
        // 「明天几点开会」：按标题过滤
        String t = stripTitle(r.rest.replaceAll("我们|咱们|我|下一个|下个|接下来的?|第一个|最近的?一个|几点|什么时候|啥时候|有什么|有哪些|有没有|有几个|是什么|有啥|吗|么|呢|查看|查查|看看|看一下|列一下|列出|查一下|我的|日程|行程|安排|是|在|有|忙不忙|忙吗|有空吗|有没有空|空不空|几个|哪些|什么", " "));
        t = t.replaceAll("\\s+", "");
        if (t.length() >= 1 && !t.matches("^(会|会议|开会|有会)$") || q.contains("几点")) {
            String f = t.length() > 0 ? t : null;
            if (f != null && f.matches("^(会|会议|开会|有会)$")) f = "会";
            x.titleFilter = f;
        }
        if (q.matches(".*(有会|开会|会议).*") && x.titleFilter == null) x.titleFilter = "会";
        return x;
    }

    private Plan planCreate(String q, long now, boolean kw) {
        boolean remind = REMIND_WORD.matcher(q).find() && !CnTime.LEAD.matcher(q).find() && !CnTime.NO_REMIND.matcher(q).find();
        // ★ int/1102：「提醒我明天下午三点开会」= 日程 + 到点提醒（日历 + 提醒）；只有相对时长（10 分钟后）或叫醒 / 闹钟才归 S3 / 闹钟
        boolean remindAt = false;
        if (remind && !q.contains("日程") && !q.contains("行程")) {
            if (ALARM_WORD.matcher(q).find() || RELATIVE.matcher(q).find()) return null;
            String stripped = q.replaceAll("(到时候?|到点)?(提醒|通知)(我|一下)?(一下)?", "");
            CnTime.Result rr = CnTime.parse(stripped, now, mTz);
            String tt = stripTitle(rr.rest);
            // ★ backlog #5：「今晚8点提醒我给妈妈打电话」有钟点 + 任务动词也算（原先只认事件词 → 掉进 AgentLoop）
            if (!(rr.found && rr.hasTime && (EVENT_LEX.matcher(tt).find() || TASK_VERB.matcher(tt).find()))) return remindDateOnly(rr, tt, now);   // 没有钟点 / 不是事件 = S3 提醒（只说日子的见 remindDateOnly）
            q = stripped;
            remindAt = true;
        }
        if (!remind && ALARM_WORD.matcher(q).find() && !q.contains("日程") && !q.contains("行程")) return null;
        String body = q;
        boolean explicit = false;
        Matcher mm = MEMO_PREFIX.matcher(body);
        if (mm.find() && mm.end() < body.length()) body = body.substring(mm.end());
        final String src = body;
        Matcher m = ADD_TO.matcher(src);
        if (m.matches()) {
            body = m.group(2);
            explicit = true;
        } else if ((m = KW_PREFIX.matcher(src)).find()) {
            body = src.substring(m.end());
            explicit = true;
        } else if ((m = CREATE_VERB.matcher(src)).find() && (m.group(4) != null || m.group(2).length() >= 2)) {
            body = src.substring(m.end());
            explicit = m.group(4) != null || m.group(2).matches("添加|新建|创建|新增|安排|安排一下|登记");
        }
        if (!explicit && QUESTION.matcher(q).find()) return null;
        if (!explicit && PHONE_ACTION.matcher(q).find()) return null;
        CnTime.Result r = CnTime.parse(body, now, mTz);
        String title = stripTitle(r.rest.replaceAll("日程|行程", " "));
        if (r.error != null) {
            if (!explicit && !EVENT_LEX.matcher(title).find()) return null;
            Plan x = new Plan(Plan.Op.ERROR);
            x.message = r.error + "，再说一次日子吧。";
            return x;
        }
        boolean timeLike = r.found && (r.hasDate || r.hasTime || r.repeat != SchedEvent.REPEAT_NONE || r.weekStart >= 0);
        if (!explicit) {
            if (!timeLike) return null;
            if (!EVENT_LEX.matcher(title).find() && !(remindAt && TASK_VERB.matcher(title).find())) return null;   // ★ backlog #5：带钟点的提醒 + 任务动词
            if (title.length() > 24) return null;
            if (FACT_TAIL.matcher(title).find()) return null;
            if (INFO_VERB.matcher(title).find()) return null;
            if (title.matches("^我.{0,6}(的)?(生日|纪念日|结婚纪念日|忌日)$")) return null;   // 「我女儿5月3号生日」= 家人事实（记忆）   // ★ int/1102：「我女儿生日是5月3号」是事实（记忆），不是日程
        }
        // ★ int/1102 常识：不带时段的 1–6 点，开会 / 吃饭这类按下午记（回执里说一句，可撤销），不追问；航班 / 起床类才问
        boolean pmDefault = false;
        if (r.ambiguous() && r.options.size() == 2 && r.options.get(0).label.matches("^(下午|晚上)[0-9]+点$") && !EARLY_LEX.matcher(title).find()) {
            r.start = r.options.get(0).start;
            r.end = r.options.get(0).end;
            r.question = null;
            r.options.clear();
            pmDefault = true;
            if (!r.hasDate && r.start < now - 60000L) {   // 没说哪天、今天下午这个点已过 → 明天
                r.start = CnTime.addDays(r.start, 1, mTz);
                if (r.end > 0) r.end = CnTime.addDays(r.end, 1, mTz);
                r.rolled = true;
            }
        }
        SchedEvent d = draftFrom(r, title, now);
        if (remindAt && d.leadMin == SchedEvent.LEAD_DEFAULT) d.leadMin = 0;   // 「提醒我三点开会」= 三点准点提醒
        if (pmDefault) d.note = "PM_DEFAULT";
        Plan x;
        if (!timeLike) {
            if (title.length() == 0) return null;   // 「加个日程」什么都没说：交给面板（不抢）
            x = new Plan(Plan.Op.ASK_TIME);
        } else if (title.length() == 0) {
            x = new Plan(Plan.Op.ASK_TITLE);
        } else if (r.ambiguous()) {
            x = new Plan(Plan.Op.ASK_OPTION);
        } else {
            x = new Plan(Plan.Op.CREATE);
        }
        x.utterance = q;
        x.draft = d;
        x.time = r;
        return x;
    }

    /** ★ backlog #5：管理 / 查看已有提醒（取消 / 删 / 关 / 改 / 推迟 / 看看 / 有哪些 … 提醒），「提醒我取消订阅」这种新事项不算。 */
    static final Pattern REMIND_MANAGE = Pattern.compile(
            "(取消|删除|删掉|删了|删去|去掉|关掉|关闭|关了|停掉|停止|撤掉|改到|改成|改为|挪到|推迟|延后|提前到|查看|看看|看一下|列出|列一下|有哪些|有什么|有没有|有几个|还有).{0,12}提醒(?!我)"
                    + "|提醒(?!我)(的)?.{0,8}(取消|删掉|删除|删了|关掉|关了|不要了|改到|改成|改为|挪到|推迟|延后|提前)");

    /** ★ backlog #5：改日程的提醒提前量（「改成提前一小时提醒」「提前10分钟提醒」），不算管理提醒。 */
    static final Pattern LEAD_EDIT = Pattern.compile("提前([0-9一二两三四五六七八九十半]+|几)(个)?(分钟|小时|钟头|天)提醒");

    /** ★ NG-07：「别忘了 / 不要忘了 / 别让我忘了 / 记得 …」任务式前缀（「记住 / 记一下」不在内：那是记忆写入）。 */
    static final Pattern FORGET_PREFIX = Pattern.compile(
            "^(帮我|请|你)?(千万)?(别忘了|别忘记了?|不要忘了|不要忘记了?|别让我忘了|不要让我忘了|记得|提醒我|到时候?提醒我|提醒一下我?)(要|去|得)?(一下)?[:：，, ]*");
    /** 任务起头的动词（办事 / 带东西 / 打电话…）。「记得我车停哪吗」「别忘了我的车停在B2」不以动词开头 → 不是任务。 */
    static final Pattern TASK_VERB = Pattern.compile(
            "^(把|去|要|得|再|按时|及时)?(交|缴|还|带|买|拿|取|寄|收|给|打|发|回|吃|喝|喂|浇|关|锁|倒|充|续|洗|晾|晒|收拾|写|做|看|接|送|预约|约|订|报|签|填|提交|上交|准备|复习|练|背|打卡|签到|开会|上课|锻炼|跑步|健身|联系|问|通知|转账|付|支付|扔|丢|换|修|办|处理|检查|确认|领|退|抢|还款|吃药|拿药|遛|出门|下楼|起来|喂奶|关火|关灯|关窗)");
    /** 事实陈述的痕迹（「…停在B2」「…是123」「…叫小明」）：归记忆。 */
    static final Pattern FACT_MARK = Pattern.compile("(停在|放在|搁在|存在|在|是|叫|为|号码|密码|工号|车牌|生日)");

    /** 只读：任务式「别忘了X」/「提醒我X」且整句没有任何时间 → ASK_TIME（问一次什么时候提醒，答了就建日程 + 准点提醒）。有时间的照旧走 planCreate。 */
    private Plan planForgetRemind(String q, long now) {
        Matcher m = FORGET_PREFIX.matcher(q);
        if (!m.find() || m.end() >= q.length()) return null;
        String rest = q.substring(m.end());
        Matcher m2 = FORGET_PREFIX.matcher(rest);   // 「别忘了提醒我交房租」：叠了两层前缀
        if (m2.find() && m2.end() < rest.length()) rest = rest.substring(m2.end());
        if (QUESTION.matcher(rest).find() || rest.endsWith("吗") || rest.endsWith("么")) return null;
        if (ALARM_WORD.matcher(rest).find() || RELATIVE.matcher(rest).find()) return null;
        if (NOT_OURS.matcher(rest).find()) return null;
        if (!TASK_VERB.matcher(rest).find()) return null;
        if (FACT_MARK.matcher(rest).find() && !rest.matches("^(把|去|要|得)?(交|缴|给|打|发|带|买|拿|取|还|寄).*")) return null;
        CnTime.Result r = CnTime.parse(rest, now, mTz);
        if (r.found && (r.hasDate || r.hasTime || r.repeat != SchedEvent.REPEAT_NONE || r.weekStart >= 0)) return null;   // 有时间：planCreate 接
        String title = stripTitle(r.rest);
        if (title.length() < 2 || title.length() > 24 || REMIND_NOT_TASK.matcher(title).find()) return null;
        SchedEvent d = draftFrom(r, title, now);
        d.leadMin = 0;   // 提醒 = 准点
        d.note = "ASK_REMIND_WHEN";
        Plan x = new Plan(Plan.Op.ASK_TIME);
        x.utterance = q;
        x.draft = d;
        x.time = r;
        return x;
    }

    /** 「取消明天的提醒」「看看明天的提醒」：管理 / 查看已有提醒，不是新事项。 */
    static final Pattern REMIND_NOT_TASK = Pattern.compile("^(取消|删除|删掉|删|关掉|关闭|关了|停掉|别|不要|不用|改|修改|推迟|延后|提前|查看|看看|列出|有没有|有什么|有哪些|还有)$"
            + "|^(取消|删除|删掉|关掉|关闭|停掉|修改|查看|列出)|的$|^(提醒|闹钟|日程|安排)$");
    /** ★ int/1102（TriageProbeParity：「获取今日华尔街日报」被建成全天日程「获取华尔街日报」——EVENT_LEX 单字「取」）：取信息 / 操作 App 的句子不是日程。 */
    static final Pattern INFO_VERB = Pattern.compile("^(获取|查询|查一下|查查|搜一下|搜索|搜|打开|读一下|念一下|下载|播放|放一首|推荐|告诉我|给我看|看看今天|看看今日)");

    /** 只说日子的提醒默认几点（「提醒我明天交报告」→ 那天上午 9 点准点提醒，回执里说明）。 */
    public static final int REMIND_DEFAULT_HOUR = 9;
    /** ★ int/1102 问一次记住：偏好键（prefs bixby_sched）与「今天已过」的三种处理。 */
    public static final String PREF_REMIND_MINUTE = "remind_minute", PREF_TODAY_LATE = "today_late";
    public static final String LATE_ASK = "ask", LATE_1H = "1h", LATE_EVENING = "20";
    /** 第一次问「默认几点」给的钟点（只给还没过的）。 */
    static final int[] REMIND_HOUR_CHOICES = {8, 9, 10};
    static final String OPT_ASK_EACH = "每次问我几点", OPT_1H = "1小时后", OPT_EVENING = "今晚8点";

    /** 设置页 / 回执用：偏好的人话。 */
    public static String lateLabel(String v) {
        if (LATE_1H.equals(v)) return "1 小时后提醒";
        if (LATE_EVENING.equals(v)) return "今晚 8 点提醒";
        if (LATE_ASK.equals(v)) return "每次问我几点";
        return null;
    }

    private long atMinute(long day, int minuteOfDay) {
        java.util.Calendar c = java.util.Calendar.getInstance(mTz);
        c.setTimeInMillis(day);
        c.set(java.util.Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        c.set(java.util.Calendar.MINUTE, minuteOfDay % 60);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 一小时后，取整到 5 分钟。 */
    static long inAnHour(long now) {
        long t = now + 3600000L;
        long five = 5L * 60000L;
        return ((t + five - 1) / five) * five;
    }

    /**
     * ★ int/1102 常识：「提醒我明天交报告」「明天提醒我交报告」= 有日子、没钟点、有事 → 那天上午 9 点准点提醒（日程 + 提醒，回执说明，可撤销），
     * 不进 AgentLoop、不追问；说的是「今天」且 9 点已过 → 只问一次几点。重复 / 相对时长 / 没有事项 / 日子不合法 → null（照旧给 S3）。
     */
    private Plan remindDateOnly(CnTime.Result rr, String title, long now) {
        if (rr == null || !rr.found || rr.hasTime || !rr.hasDate || rr.error != null || rr.ambiguous()) return null;
        if (rr.repeat != SchedEvent.REPEAT_NONE || rr.weekStart >= 0) return null;
        if (title == null || title.length() == 0 || title.length() > 24) return null;
        if (FACT_TAIL.matcher(title).find() || REMIND_NOT_TASK.matcher(title).find()) return null;
        SchedEvent d = draftFrom(rr, title, now);
        long day = CnTime.dayStart(d.startAt, mTz);
        long today = CnTime.dayStart(now, mTz);
        if (day < today) return null;
        d.leadMin = 0;
        d.endAt = 0;
        int minute = settings.remindMinute;
        if (minute < 0) {
            // ★ int/1102 问一次记住：还没定过默认几点 → 给还没过的 8 / 9 / 10 点芯片，选了就建这一条并记成默认（设置里可改）
            CnTime.Result ask = new CnTime.Result();
            ask.found = true;
            ask.hasDate = true;
            ask.hasTime = true;
            for (int h : REMIND_HOUR_CHOICES) {
                long t = atMinute(day, h * 60);
                if (t > now) ask.options.add(new CnTime.Option(SchedFormat.clockWord(t, mTz), t, 0));
            }
            if (!ask.options.isEmpty()) {
                ask.question = "没说几点。只说日子的提醒，以后默认几点提醒你？选一次就记住，设置里可以改。";
                d.allDay = false;
                d.startAt = ask.options.get(0).start;
                Plan x = new Plan(Plan.Op.ASK_OPTION);
                x.draft = d;
                x.time = ask;
                x.note = "pref:" + PREF_REMIND_MINUTE;
                return x;
            }
            minute = REMIND_DEFAULT_HOUR * 60;   // 今天、几个钟点都过了：按「今天已过」处理（下面）
        }
        long at = atMinute(day, minute);
        Plan x;
        if (at < now) {   // 「提醒我今天交报告」而默认钟点已过：按记住的处理方式；没问过 → 问一次怎么处理
            return latePlan(d, rr, day, now);
        } else {
            d.allDay = false;
            d.startAt = at;
            d.note = "AM_DEFAULT";
            x = new Plan(Plan.Op.CREATE);
        }
        x.draft = d;
        x.time = rr;
        return x;
    }

    /** 「今天」的提醒钟点已过 → 按记住的 today_late 处理；没记过 → 问一次（ASK_OPTION pref:today_late）。 */
    private Plan latePlan(SchedEvent d, CnTime.Result rr, long day, long now) {
        Plan x;
        {
            String late = settings.todayLate;
            if (LATE_1H.equals(late) || LATE_EVENING.equals(late)) {
                long ev = atMinute(day, 20 * 60);
                d.allDay = false;
                d.startAt = LATE_EVENING.equals(late) && ev > now ? ev : inAnHour(now);
                d.note = "LATE_AUTO";
                x = new Plan(Plan.Op.CREATE);
                x.draft = d;
                x.time = rr;
                return x;
            }
            if (late == null) {
                CnTime.Result ask = new CnTime.Result();
                ask.found = true;
                ask.hasDate = true;
                ask.hasTime = true;
                ask.options.add(new CnTime.Option(OPT_1H, inAnHour(now), 0));
                long ev = atMinute(day, 20 * 60);
                if (ev > now + 30L * 60000L) ask.options.add(new CnTime.Option(OPT_EVENING, ev, 0));
                ask.options.add(new CnTime.Option(OPT_ASK_EACH, -1L, 0));
                ask.question = "今天默认的提醒时间已经过了。这种情况以后怎么提醒？选一次就记住，设置里可以改；也可以直接说几点。";
                d.allDay = false;
                d.startAt = ask.options.get(0).start;
                x = new Plan(Plan.Op.ASK_OPTION);
                x.draft = d;
                x.time = ask;
                x.note = "pref:" + PREF_TODAY_LATE;
                return x;
            }
            d.allDay = true;
            d.startAt = day;
            d.note = "ASK_REMIND_CLOCK";
            x = new Plan(Plan.Op.ASK_TIME);
        }
        x.draft = d;
        x.time = rr;
        return x;
    }

    private Plan planUpdate(String q, long now, boolean kw) {
        Matcher m;
        if ((m = RENAME.matcher(q)).matches()) {
            List<SchedEvent> c = find(m.group(2), now);
            if (c.isEmpty()) return notFound(m.group(2), kw);
            Plan x = new Plan(Plan.Op.UPDATE);
            x.targets = c;
            String nt = stripTitle(m.group(5));
            if (nt.length() == 0) return null;
            x.message = "rename:" + nt;
            return x;
        }
        if ((m = RELOC.matcher(q)).matches()) {
            List<SchedEvent> c = find(m.group(2), now);
            if (c.isEmpty()) return notFound(m.group(2), kw);
            Plan x = new Plan(Plan.Op.UPDATE);
            x.targets = c;
            x.message = "loc:" + stripTitle(m.group(5));
            return x;
        }
        if ((m = SHIFT.matcher(q)).matches()) {
            String what = m.group(2);
            if (what.contains("提醒")) return null;
            List<SchedEvent> c = find(what, now);
            if (c.isEmpty()) return notFound(what, kw);
            String a = m.group(5);
            int n = a == null ? 1 : "半".equals(a) ? 0 : CnNum.parse(a);
            if (n < 0) return null;
            long unit = "分钟".equals(m.group(7)) ? 60000L : "天".equals(m.group(7)) ? 86400000L : 3600000L;
            long ms = n * unit + ("半".equals(a) || m.group(6) != null ? unit / 2 : 0);
            if (ms <= 0) return null;
            boolean back = m.group(3).contains("提前") || m.group(3).contains("往前");
            Plan x = new Plan(Plan.Op.UPDATE);
            x.targets = c;
            x.shiftMs = back ? -ms : ms;
            return x;
        }
        if ((m = UPDATE.matcher(q)).matches()) {
            String what = m.group(2), to = m.group(4);
            if (what.matches(".*(音量|亮度|模式|声音|频道|温度|空调|闹钟|提醒|待办).*")) return null;
            List<SchedEvent> c = find(what, now);
            if (c.isEmpty()) return notFound(what, kw);
            Plan x = new Plan(Plan.Op.UPDATE);
            x.targets = c;
            CnTime.Result lead = CnTime.parse(to, now, mTz);
            if (to.contains("提前") && to.contains("提醒") || CnTime.NO_REMIND.matcher(to).find()) {
                x.message = "lead:" + lead.leadMin;
                return x;
            }
            x.time = CnTime.parse(to, now, mTz);
            x.utterance = to;
            if (!x.time.found) {
                Plan e = new Plan(Plan.Op.ERROR);
                e.message = "没听懂要改到什么时候，再说一次吧（例如「改到周五下午三点」）。";
                return e;
            }
            return x;
        }
        return null;
    }

    private Plan planDelete(String q, long now, boolean kw) {
        Matcher m;
        String what = null;
        boolean all = false;
        if ((m = CLEAR.matcher(q)).matches()) {
            what = m.group(2);
            all = true;
            if (!KW.matcher(q).find()) return null;
        } else if ((m = DELETE.matcher(q)).matches()) {
            what = m.group(3);
        } else if ((m = DELETE2.matcher(q)).matches()) {
            what = m.group(2);
        }
        if (what == null) return null;
        if (REMIND_WORD.matcher(what).find() || what.matches(".*(定时|闹钟|待办|任务|静音|勿扰|订单|关注|订阅|收藏|下载|蓝牙|热点).*")) return null;
        if (what.contains("所有") || what.contains("全部")) all = true;
        List<SchedEvent> c = all ? inRange(what, now) : find(what, now);
        if (c.isEmpty()) {
            if (all) {
                Plan x = new Plan(Plan.Op.NOT_FOUND);
                x.message = "那段时间没有日程，不用删。";
                return x;
            }
            return notFound(what, kw);
        }
        Plan x = new Plan(Plan.Op.DELETE);
        x.targets = c;
        x.message = all ? "all" : null;
        return x;
    }

    private Plan notFound(String what, boolean kw) {
        boolean ours = kw || EVENT_LEX.matcher(what).find() && CnTime.parse(what, System.currentTimeMillis(), mTz).found;
        if (!ours && !THAT.matcher(stripTitle(what)).matches()) return null;
        if (!ours && mLastTouched <= 0) return null;
        Plan x = new Plan(Plan.Op.NOT_FOUND);
        x.message = "没找到「" + clip(stripTitle(what.replaceAll("日程|行程|安排", "")), 16) + "」这个日程。";
        if (stripTitle(what.replaceAll("日程|行程|安排", "")).length() == 0) x.message = "没找到对应的日程。";
        return x;
    }

    // ================================================================== apply

    public synchronized Reply apply(Plan x, long now) {
        if (x == null) return null;
        Reply r = doApply(x, now);
        mLastReply = r;
        return r;
    }

    /** plan + apply；同一句话 5 秒内重复进来（路由层重试）只执行一次，返回上次的回复。 */
    public synchronized Reply handle(String utterance, long now) {
        String q = norm(utterance);
        if (mLastUtt != null && mLastUtt.equals(q) && now - mLastUttAt < DUP_MS && mLastReply != null) return mLastReply;
        Plan x = plan(utterance, now);
        if (x == null) {
            // 中间插了一句别的话：追问 / 待确认作废（免得之后一句「确认」删掉了两分钟前问过的日程）
            if (q.length() > 0) mPending = null;
            return null;
        }
        Reply r = apply(x, now);
        mLastUtt = q;
        mLastUttAt = now;
        return r;
    }

    private Reply doApply(Plan x, long now) {
        Pending p = livePending(now);
        if (x.op != Plan.Op.ANSWER && x.op != Plan.Op.PICK && x.op != Plan.Op.CONFIRM_YES && x.op != Plan.Op.CONFIRM_NO) mPending = null;
        switch (x.op) {
            case CREATE:
                mPending = null;
                return create(x.draft, x.time, now);
            case ASK_FULL: {
                Pending n = new Pending();
                n.type = "ASK_FULL";
                n.at = now;
                mPending = n;
                return new Reply(Kind.ASK, ASK_FULL_TEXT);
            }
            case ASK_TIME: {
                Pending n = new Pending();
                n.type = x.time != null && x.time.weekStart >= 0 ? "ASK_WEEKDAY" : "ASK_TIME";
                n.at = now;
                n.draft = x.draft;
                n.time = x.time;
                mPending = n;
                if ("ASK_REMIND_CLOCK".equals(x.draft.note)) return new Reply(Kind.ASK, "今天几点提醒你「" + x.draft.title + "」？");
                if ("ASK_REMIND_WHEN".equals(x.draft.note)) return new Reply(Kind.ASK, "什么时候提醒你「" + x.draft.title + "」？");
                return new Reply(Kind.ASK, "「" + x.draft.title + "」是什么时候？");
            }
            case ASK_TITLE: {
                Pending n = new Pending();
                n.type = "ASK_TITLE";
                n.at = now;
                n.draft = x.draft;
                n.time = x.time;
                mPending = n;
                return new Reply(Kind.ASK, SchedFormat.when(x.draft.startAt, x.draft.allDay, now, mTz) + "要记什么事？");
            }
            case ASK_OPTION: {
                Pending n = new Pending();
                n.type = x.time.weekStart >= 0 && x.time.options.isEmpty() ? "ASK_WEEKDAY" : "ASK_OPTION";
                n.at = now;
                n.draft = x.draft;
                n.time = x.time;
                n.prefKey = x.note != null && x.note.startsWith("pref:") ? x.note.substring(5) : null;
                mPending = n;
                Reply ar = new Reply(Kind.ASK, "「" + x.draft.title + "」" + x.time.question);
                if (n.prefKey != null) {
                    ar.chips = new ArrayList<String>();
                    for (CnTime.Option o : x.time.options) ar.chips.add(o.label);
                }
                return ar;
            }
            case ANSWER: {
                if (p == null) return new Reply(Kind.ERROR, "刚才的问题已经过期了，再说一次完整的日程吧。");
                SchedEvent d = p.draft.copy();
                CnTime.Result t = p.time;
                if ("ASK_TITLE".equals(p.type)) {
                    d.title = clip(x.message, 40);
                    mPending = null;
                    return create(d, t, now);
                }
                if (p.prefKey != null) return answerPref(p, x, d, now);
                if (x.option != null) {
                    d.startAt = x.option.start;
                    d.endAt = x.option.end;
                    d.allDay = t != null && t.allDay && x.option.end == 0 && !t.hasTime;
                } else if (x.time != null) {
                    CnTime.Result nt = x.time;
                    if (nt.ambiguous()) {
                        Pending n = new Pending();
                        n.type = nt.weekStart >= 0 && nt.options.isEmpty() ? "ASK_WEEKDAY" : "ASK_OPTION";
                        n.at = now;
                        n.draft = d;
                        n.time = nt;
                        mPending = n;
                        return new Reply(Kind.ASK, nt.question);
                    }
                    SchedEvent nd = draftFrom(nt, d.title, now);
                    nd.leadMin = d.leadMin != SchedEvent.LEAD_DEFAULT && nt.leadMin == SchedEvent.LEAD_DEFAULT ? d.leadMin : nd.leadMin;
                    if (!nt.hasTime && t != null && t.hasTime && !p.draft.allDay && nt.repeat == SchedEvent.REPEAT_NONE) {
                        // 「下周下午三点开会」→「周三」：日子用回答的，钟点用原来的
                        nd.allDay = false;
                        nd.startAt = combine(CnTime.dayStart(nt.start, mTz), p.draft.startAt);
                        nd.endAt = p.draft.endAt > p.draft.startAt ? nd.startAt + (p.draft.endAt - p.draft.startAt) : 0;
                    }
                    d = nd;
                    t = nt;
                }
                mPending = null;
                return create(d, t, now);
            }
            case QUERY:
                return query(x, now);
            case UPDATE:
                return prepareUpdate(x, now);
            case DELETE:
                return prepareDelete(x.targets, "all".equals(x.message), now);
            case PICK: {
                if (p == null || x.pick < 0 || x.pick >= p.targets.size()) return new Reply(Kind.ERROR, "没有这一条。");
                SchedEvent t = p.targets.get(x.pick);
                List<SchedEvent> one = new ArrayList<SchedEvent>();
                one.add(t);
                if ("PICK_DELETE".equals(p.type)) {
                    mPending = null;
                    return prepareDelete(one, false, now);
                }
                Plan u = new Plan(Plan.Op.UPDATE);
                u.targets = one;
                u.time = p.time;
                u.shiftMs = p.shiftMs;
                u.message = p.changeDesc;
                mPending = null;
                return prepareUpdate(u, now);
            }
            case CONFIRM_YES:
                return confirm(p, now);
            case CONFIRM_NO:
                mPending = null;
                return new Reply(Kind.CANCELLED, p != null && p.type.contains("DELETE") ? "好的，不删了。" : p != null && p.type.contains("UPDATE") ? "好的，不改了。" : "好的，这条日程先不记了。");
            case UNDO:
                return undo(now);
            case NOT_FOUND:
                return new Reply(Kind.NOT_FOUND, x.message);
            case ERROR:
            default:
                return new Reply(Kind.ERROR, x.message == null ? "没听懂，再说一次吧。" : x.message);
        }
    }

    /**
     * ★ int/1102 问一次记住：回答了「默认几点」/「今天已过怎么办」→ 建这一条，并把答案带回去记成偏好（SchedHooks 落 prefs，引擎设置同步）。
     * 选项芯片 / 说钟点都算回答；「每次问我几点」→ 记 ask，再问一次今天几点。
     */
    private Reply answerPref(Pending p, Plan x, SchedEvent d, long now) {
        String key = p.prefKey;
        String val;
        long start;
        if (x.option != null) {
            if (x.option.start < 0) {   // 「每次问我几点」
                settings.todayLate = LATE_ASK;
                Pending n = new Pending();
                n.type = "ASK_TIME";
                n.at = now;
                d.allDay = true;
                d.startAt = CnTime.dayStart(now, mTz);
                d.note = "ASK_REMIND_CLOCK";
                n.draft = d;
                n.time = p.time;
                mPending = n;
                Reply r = new Reply(Kind.ASK, "好，以后遇到这种情况都先问你。今天几点提醒你「" + d.title + "」？");
                r.prefKey = PREF_TODAY_LATE;
                r.prefVal = LATE_ASK;
                return r;
            }
            start = x.option.start;
            if (PREF_TODAY_LATE.equals(key)) {
                val = OPT_EVENING.equals(x.option.label) ? LATE_EVENING : OPT_1H.equals(x.option.label) ? LATE_1H : LATE_ASK;   // 说了别的钟点 = 喜欢自己定
            }
            else val = null;
        } else if (x.time != null && x.time.hasTime && !x.time.ambiguous()) {
            long day = CnTime.dayStart(p.draft.startAt, mTz);
            start = x.time.hasDate ? x.time.start : combine(day, x.time.start);
            val = PREF_TODAY_LATE.equals(key) ? LATE_ASK : null;   // 「今天已过」时直接说了钟点 = 喜欢自己定时间
        } else {
            mPending = p;   // 没答到点子上：保留追问
            return new Reply(Kind.ASK, "选一个，或者直接说几点（比如「上午9点」）。");
        }
        if (PREF_REMIND_MINUTE.equals(key)) {
            java.util.Calendar k = java.util.Calendar.getInstance(mTz);
            k.setTimeInMillis(start);
            int m = k.get(java.util.Calendar.HOUR_OF_DAY) * 60 + k.get(java.util.Calendar.MINUTE);
            val = String.valueOf(m);
            settings.remindMinute = m;
            if (start < now) {
                // 答了「凌晨1点」而今天这个钟点已经过了 → 默认照记，这一条按「今天已过」处理，不建过去的日程
                mPending = null;
                CnTime.Result rr = new CnTime.Result();
                rr.found = true;
                rr.hasDate = true;
                Plan lp = latePlan(d, rr, CnTime.dayStart(start, mTz), now);
                Reply lr = doApply(lp, now);
                String head = "好，以后只说日子的提醒都按" + SchedFormat.clockWord(start, mTz) + "提醒你（设置 › 记忆 › 常用默认 可以改）。";
                Reply out = new Reply(lr.kind, head + "不过今天" + SchedFormat.clockWord(start, mTz) + "已经过了。" + lr.text.replace("今天默认的提醒时间已经过了。", ""));
                out.eventId = lr.eventId;
                out.chips = lr.chips;
                out.prefKey = key;
                out.prefVal = val;
                return out;
            }
        } else if (val != null) {
            settings.todayLate = val;
        }
        d.allDay = false;
        d.startAt = start;
        d.endAt = 0;
        d.leadMin = 0;
        d.note = "";
        mPending = null;
        Reply r = create(d, null, now);
        if (r.kind == Kind.CREATED && val != null) {
            String tail = PREF_REMIND_MINUTE.equals(key)
                    ? "以后只说日子的提醒都按" + SchedFormat.clockWord(start, mTz) + "提醒你"
                    : "以后今天的默认时间过了就" + lateLabel(val);
            Reply out = new Reply(Kind.CREATED, r.text.replaceFirst("说「撤销」可以撤回。", tail + "（设置 › 记忆 › 常用默认 可以改）。说「撤销」可以撤回。"));
            out.eventId = r.eventId;
            out.prefKey = key;
            out.prefVal = val;
            return out;
        }
        return r;
    }

    // ------------------------------------------------------------------ 新建

    SchedEvent draftFrom(CnTime.Result r, String title, long now) {
        SchedEvent d = new SchedEvent();
        d.title = clip(title, 40);
        d.startAt = r.start > 0 ? r.start : CnTime.dayStart(now, mTz);
        d.endAt = r.end;
        d.allDay = r.allDay || !r.hasTime;
        if (d.allDay) {
            d.startAt = CnTime.dayStart(d.startAt, mTz);
            d.endAt = 0;
        }
        d.repeat = r.repeat;
        d.weekMask = r.weekMask;
        d.monthDay = r.monthDay;
        d.lunar = r.lunar;
        d.lunarMonth = r.lunarMonth;
        d.lunarDay = r.lunarDay;
        d.leadMin = r.leadMin;
        return d;
    }

    private Reply create(SchedEvent d, CnTime.Result t, long now) {
        if (d.title == null || d.title.trim().length() == 0) d.title = "日程";
        // 一次性、已经过去 → 追问一次（不默默记一条过去的日程）
        if (!d.repeating() && t != null && !"PAST_OK".equals(d.note)) {
            boolean past = d.allDay ? d.startAt < CnTime.dayStart(now, mTz) : d.startAt < now - 60000L;
            if (past && (t.options.isEmpty())) {
                Pending n = new Pending();
                n.type = "ASK_OPTION";
                n.at = now;
                SchedEvent pd = d.copy();
                pd.note = "PAST_OK";
                n.draft = pd;
                CnTime.Result rr = new CnTime.Result();
                rr.allDay = d.allDay;
                rr.hasTime = !d.allDay;
                long tomorrow = d.allDay ? CnTime.addDays(CnTime.dayStart(now, mTz), 1, mTz)
                        : nextSameClock(d.startAt, now);
                rr.options.add(new CnTime.Option(SchedFormat.dayWord(tomorrow, now, mTz), tomorrow, d.endAt > 0 ? tomorrow + (d.endAt - d.startAt) : 0));
                rr.options.add(new CnTime.Option("就记原来的", d.startAt, d.endAt));
                rr.question = SchedFormat.when(d.startAt, d.allDay, now, mTz) + "已经过去了，改成" + rr.options.get(0).label + "吗？";
                n.time = rr;
                mPending = n;
                return new Reply(Kind.ASK, "「" + d.title + "」" + rr.question);
            }
        }
        boolean pmDefault = "PM_DEFAULT".equals(d.note);
        boolean amDefault = "AM_DEFAULT".equals(d.note);
        boolean lateAuto = "LATE_AUTO".equals(d.note);
        d.note = "";
        d.createdAt = now;
        d.updatedAt = now;
        d.deleted = false;
        if (d.source == null || d.source.length() == 0) d.source = "voice";
        long id = mStore.insert(d);
        Undo u = new Undo();
        u.type = "CREATE";
        u.at = now;
        u.ids.add(id);
        mUndo = u;
        mLastTouched = id;
        mPending = null;
        StringBuilder b = new StringBuilder("已添加日程：");
        long first = d.repeating() ? SchedRecur.nextStart(d, now - 60000L, mTz) : d.startAt;
        if (d.repeating()) {
            b.append(SchedRecur.describe(d)).append(d.allDay ? "" : SchedFormat.clockWord(d.startAt, mTz)).append(" ").append(d.title);
            if (first > 0) b.append("，下一次是").append(SchedFormat.when(first, d.allDay, now, mTz));
        } else {
            b.append(SchedFormat.span(d.startAt, d.endAt, d.allDay, now, mTz)).append(" ").append(d.title);
        }
        b.append("。");
        int lead = effectiveLead(d);
        if (lead == CnTime.LEAD_NONE) b.append("不提醒。");
        else if (d.allDay) b.append("当天早上提醒你。");
        else b.append(SchedFormat.lead(lead)).append("。");
        if (t != null && t.rolled) b.append("今天这个点已经过了，记在了明天。");
        if (t != null && t.timeGuessed) b.append("没说几点，先按").append(SchedFormat.clockWord(d.startAt, mTz)).append("记。");
        if (pmDefault) b.append("没说上午下午，按").append(SchedFormat.clockWord(d.startAt, mTz)).append("记。");
        if (amDefault) b.append("没说几点，按").append(SchedFormat.clockWord(d.startAt, mTz)).append("提醒你，要改时间直接说「改到几点」。");
        if (lateAuto) b.append("今天的默认时间已经过了，按你定的习惯").append(SchedFormat.clockWord(d.startAt, mTz)).append("提醒你，要改直接说「改到几点」。");
        b.append("说「撤销」可以撤回。");
        b.append("\n").append(card(d, now));
        Reply r = new Reply(Kind.CREATED, b.toString());
        r.eventId = id;
        return r;
    }

    /** 「加个日程」什么都没给时的追问。 */
    public static final String ASK_FULL_TEXT = "要记什么日程？说时间和事情就行，比如「明天下午三点开会」。";

    /**
     * ★ int/1102：日程卡（日历 + 提醒）——标题、日期时间、地点、提醒、重复。Markdown 列表行：富内容插件开时渲染成列表卡，关时就是几行字。
     */
    String card(SchedEvent d, long now) {
        StringBuilder c = new StringBuilder();
        long first = d.repeating() ? SchedRecur.nextStart(d, now - 60000L, mTz) : d.startAt;
        c.append("- 事项：").append(d.title).append("\n");
        c.append("- 时间：").append(d.repeating() ? SchedFormat.when(first > 0 ? first : d.startAt, d.allDay, now, mTz)
                : SchedFormat.span(d.startAt, d.endAt, d.allDay, now, mTz)).append("\n");
        c.append("- 地点：").append(d.location == null || d.location.length() == 0 ? "未填" : d.location).append("\n");
        int lead = effectiveLead(d);
        c.append("- 提醒：").append(lead == CnTime.LEAD_NONE ? "不提醒" : d.allDay ? "当天早上（灵动岛）" : lead == 0 ? "准点（灵动岛）" : "提前" + leadWord(lead) + "（灵动岛）").append("\n");
        c.append("- 重复：").append(d.repeating() ? SchedRecur.describe(d) : "不重复");
        return c.toString();
    }

    static String leadWord(int min) {
        if (min % 1440 == 0) return (min / 1440) + "天";
        if (min % 60 == 0) return (min / 60) + "小时";
        if (min == 30) return "半小时";
        return min + "分钟";
    }

    /** day（0 点）那天 + clock 的钟点。 */
    private long combine(long day, long clock) {
        Calendar s = Calendar.getInstance(mTz);
        s.setTimeInMillis(clock);
        Calendar k = Calendar.getInstance(mTz);
        k.setTimeInMillis(day);
        k.set(Calendar.HOUR_OF_DAY, s.get(Calendar.HOUR_OF_DAY));
        k.set(Calendar.MINUTE, s.get(Calendar.MINUTE));
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        return k.getTimeInMillis();
    }

    private long nextSameClock(long start, long now) {
        Calendar k = Calendar.getInstance(mTz);
        k.setTimeInMillis(start);
        Calendar n = Calendar.getInstance(mTz);
        n.setTimeInMillis(now);
        n.set(Calendar.HOUR_OF_DAY, k.get(Calendar.HOUR_OF_DAY));
        n.set(Calendar.MINUTE, k.get(Calendar.MINUTE));
        n.set(Calendar.SECOND, 0);
        n.set(Calendar.MILLISECOND, 0);
        if (n.getTimeInMillis() < now) n.add(Calendar.DAY_OF_MONTH, 1);
        return n.getTimeInMillis();
    }

    public int effectiveLead(SchedEvent e) {
        if (e.leadMin == CnTime.LEAD_NONE) return CnTime.LEAD_NONE;
        return e.leadMin >= 0 ? e.leadMin : Math.max(0, settings.defaultLead);
    }

    // ------------------------------------------------------------------ 查询

    private Reply query(Plan x, long now) {
        List<SchedRecur.Occ> occ = SchedRecur.expandAll(mStore.all(false), x.from, x.to, mTz);
        if (x.titleFilter != null) {
            List<SchedRecur.Occ> f = new ArrayList<SchedRecur.Occ>();
            for (SchedRecur.Occ o : occ) if (titleMatches(o.event.title, x.titleFilter)) f.add(o);
            occ = f;
        }
        if (x.nextOnly) {
            for (SchedRecur.Occ o : occ) {
                if (o.start >= now || (o.event.allDay && o.end > now)) {
                    mLastTouched = o.event.id;
                    return new Reply(Kind.LISTED, "下一个日程：" + SchedFormat.when(o.start, o.event.allDay, now, mTz) + " " + o.event.title + "。");
                }
            }
            return new Reply(Kind.LISTED, "接下来没有日程。");
        }
        String what = x.titleFilter != null && !"会".equals(x.titleFilter) ? "「" + x.titleFilter + "」" : x.titleFilter != null ? "会" : "日程";
        if (occ.isEmpty()) return new Reply(Kind.LISTED, x.rangeLabel + "没有" + what + "。");
        boolean multiDay = x.to - x.from > 86400000L + 3600000L;
        StringBuilder b = new StringBuilder(x.rangeLabel).append("有 ").append(occ.size()).append(" 个").append(what).append("：");
        int n = 0;
        for (SchedRecur.Occ o : occ) {
            if (n >= 6) break;
            if (n > 0) b.append("；");
            if (multiDay) b.append(SchedFormat.when(o.start, o.event.allDay, now, mTz));
            else b.append(o.event.allDay ? "全天" : SchedFormat.clockWord(o.start, mTz));
            b.append(" ").append(o.event.title);
            n++;
        }
        if (occ.size() > 6) b.append("，等");
        b.append("。");
        if (occ.size() == 1) mLastTouched = occ.get(0).event.id;
        // ★ int/1102：日程表卡（「今天有什么安排」）——富内容开 = 表格；关 = 列表行
        b.append("\n");
        if (settings.tableList) b.append("\n| 时间 | 事项 | 地点 | 提醒 |\n|---|---|---|---|");
        int k = 0;
        for (SchedRecur.Occ o : occ) {
            if (k >= 12) break;
            String when = multiDay ? SchedFormat.when(o.start, o.event.allDay, now, mTz) : (o.event.allDay ? "全天" : SchedFormat.clockWord(o.start, mTz));
            String loc = o.event.location == null || o.event.location.length() == 0 ? "—" : o.event.location;
            int lead = effectiveLead(o.event);
            String rem = lead == CnTime.LEAD_NONE ? "不提醒" : o.event.allDay ? "当天早上" : lead == 0 ? "准点" : "提前" + leadWord(lead);
            if (settings.tableList) b.append("\n| ").append(when).append(" | ").append(o.event.title).append(" | ").append(loc).append(" | ").append(rem).append(" |");
            else b.append("\n- ").append(when).append(" ").append(o.event.title).append(loc.equals("—") ? "" : "（" + loc + "）");
            k++;
        }
        return new Reply(Kind.LISTED, b.toString());
    }

    /** 控制台 / 晨间播报：[from, to) 的发生。 */
    public synchronized List<SchedRecur.Occ> occurrences(long from, long to) {
        return SchedRecur.expandAll(mStore.all(false), from, to, mTz);
    }

    // ------------------------------------------------------------------ 修改

    private Reply prepareUpdate(Plan x, long now) {
        if (x.targets.size() > 1) {
            Pending n = new Pending();
            n.type = "PICK_UPDATE";
            n.at = now;
            n.targets = new ArrayList<SchedEvent>(x.targets);
            n.time = x.time;
            n.shiftMs = x.shiftMs;
            n.changeDesc = x.message;
            mPending = n;
            return new Reply(Kind.ASK, "找到 " + x.targets.size() + " 个日程，要改哪一个？" + listing(x.targets, now));
        }
        SchedEvent old = x.targets.get(0);
        if (old.imported()) return new Reply(Kind.ERROR, "「" + old.title + "」是从系统日历导入的只读副本，请到系统日历里改。");
        SchedEvent nw = old.copy();
        String desc;
        if (x.message != null && x.message.startsWith("rename:")) {
            nw.title = clip(x.message.substring(7), 40);
            desc = "把「" + old.title + "」改名为「" + nw.title + "」";
        } else if (x.message != null && x.message.startsWith("loc:")) {
            nw.location = clip(x.message.substring(4), 40);
            desc = "把「" + old.title + "」的地点改为「" + nw.location + "」";
        } else if (x.message != null && x.message.startsWith("lead:")) {
            int l = Integer.parseInt(x.message.substring(5));
            nw.leadMin = l == SchedEvent.LEAD_DEFAULT ? settings.defaultLead : l;
            desc = "把「" + old.title + "」改成" + SchedFormat.lead(effectiveLead(nw));
        } else if (x.shiftMs != 0) {
            nw.startAt = old.startAt + x.shiftMs;
            if (old.endAt > 0) nw.endAt = old.endAt + x.shiftMs;
            long base = nextOccOf(old, now);
            desc = "把「" + old.title + "」从" + SchedFormat.when(base, old.allDay, now, mTz) + (x.shiftMs > 0 ? "推迟" : "提前") + dur(Math.abs(x.shiftMs))
                    + "，改到" + SchedFormat.when(base + x.shiftMs, old.allDay, now, mTz);
        } else if (x.time != null) {
            CnTime.Result t = x.time;
            if (t.ambiguous()) {
                // 改时间时的歧义：取第一个选项（下午 / 今天），在确认句里说清楚，用户可以说「算了」
                if (!t.options.isEmpty()) {
                    t.start = t.options.get(0).start;
                    t.end = t.options.get(0).end;
                } else {
                    return new Reply(Kind.ASK, t.question + "（说完整一点，例如「改到下周三下午三点」）");
                }
            }
            long base = nextOccOf(old, now);
            long dur = old.endAt > old.startAt ? old.endAt - old.startAt : 0;
            if (t.hasTime) {
                long ns = t.start;
                if (!t.hasDate) {
                    // 只说了钟点：保持原来那天
                    Calendar k = Calendar.getInstance(mTz);
                    k.setTimeInMillis(base);
                    Calendar s = Calendar.getInstance(mTz);
                    s.setTimeInMillis(t.start);
                    k.set(Calendar.HOUR_OF_DAY, s.get(Calendar.HOUR_OF_DAY));
                    k.set(Calendar.MINUTE, s.get(Calendar.MINUTE));
                    k.set(Calendar.SECOND, 0);
                    k.set(Calendar.MILLISECOND, 0);
                    ns = k.getTimeInMillis();
                }
                nw.startAt = old.repeating() ? withSeriesShift(old, base, ns) : ns;
                nw.allDay = false;
                nw.endAt = t.end > 0 ? (old.repeating() ? nw.startAt + (t.end - t.start) : t.end) : (dur > 0 ? nw.startAt + dur : 0);
            } else {
                // 只说了日子：保持原来的钟点
                long day = CnTime.dayStart(t.start, mTz);
                long ns = old.allDay ? day : SchedRecur.withClock(day, old, mTz);
                nw.startAt = old.repeating() ? withSeriesShift(old, base, ns) : ns;
                nw.endAt = dur > 0 ? nw.startAt + dur : 0;
            }
            if (t.repeat != SchedEvent.REPEAT_NONE) {
                nw.repeat = t.repeat;
                nw.weekMask = t.weekMask;
                nw.monthDay = t.monthDay;
                nw.lunar = t.lunar;
                nw.lunarMonth = t.lunarMonth;
                nw.lunarDay = t.lunarDay;
            }
            if (t.leadMin != SchedEvent.LEAD_DEFAULT) nw.leadMin = t.leadMin;
            desc = "把「" + old.title + "」从" + SchedFormat.when(base, old.allDay, now, mTz) + "改到"
                    + SchedFormat.when(nw.repeating() ? Math.max(nw.startAt, SchedRecur.nextStart(nw, now - 60000L, mTz)) : nw.startAt, nw.allDay, now, mTz);
        } else {
            return new Reply(Kind.ERROR, "没听懂要怎么改。");
        }
        if (old.repeating()) desc += "（重复日程，以后每次都改）";
        Pending n = new Pending();
        n.type = "CONFIRM_UPDATE";
        n.at = now;
        n.targets.add(old);
        n.change = nw;
        n.changeDesc = desc;
        mPending = n;
        return new Reply(Kind.CONFIRM, desc + "，确认吗？");
    }

    /** 重复日程改时间：系列整体平移（这一次 base → ns 的差值加到起点上）。 */
    private long withSeriesShift(SchedEvent old, long base, long ns) {
        return old.startAt + (ns - base);
    }

    private long nextOccOf(SchedEvent e, long now) {
        if (!e.repeating()) return e.startAt;
        long n = SchedRecur.nextStart(e, now - 60000L, mTz);
        return n > 0 ? n : e.startAt;
    }

    // ------------------------------------------------------------------ 删除

    private Reply prepareDelete(List<SchedEvent> targets, boolean all, long now) {
        if (targets.size() > 1 && !all) {
            Pending n = new Pending();
            n.type = "PICK_DELETE";
            n.at = now;
            n.targets = new ArrayList<SchedEvent>(targets);
            mPending = n;
            return new Reply(Kind.ASK, "找到 " + targets.size() + " 个日程，要删哪一个？" + listing(targets, now));
        }
        Pending n = new Pending();
        n.type = "CONFIRM_DELETE";
        n.at = now;
        n.targets = new ArrayList<SchedEvent>(targets);
        mPending = n;
        if (targets.size() == 1) {
            SchedEvent e = targets.get(0);
            return new Reply(Kind.CONFIRM, "要删除" + (e.repeating() ? SchedRecur.describe(e) + "的" : SchedFormat.when(e.startAt, e.allDay, now, mTz) + "的")
                    + "「" + e.title + "」吗？" + (e.repeating() ? "（整个重复系列都会删掉）" : ""));
        }
        return new Reply(Kind.CONFIRM, "要删除这 " + targets.size() + " 个日程吗？" + listing(targets, now));
    }

    // ------------------------------------------------------------------ 确认 / 撤销

    private Reply confirm(Pending p, long now) {
        if (p == null) return new Reply(Kind.ERROR, "没有要确认的操作了。");
        mPending = null;
        if ("CONFIRM_DELETE".equals(p.type)) {
            Undo u = new Undo();
            u.type = "DELETE";
            u.at = now;
            for (SchedEvent e : p.targets) {
                mStore.setDeleted(e.id, true, now);
                u.ids.add(e.id);
            }
            mUndo = u;
            return new Reply(Kind.DELETED, (p.targets.size() == 1 ? "已删除「" + p.targets.get(0).title + "」" : "已删除 " + p.targets.size() + " 个日程") + "。说「撤销」可以恢复。");
        }
        if ("CONFIRM_UPDATE".equals(p.type)) {
            SchedEvent old = p.targets.get(0);
            SchedEvent nw = p.change.copy();
            nw.updatedAt = now;
            mStore.update(nw);
            Undo u = new Undo();
            u.type = "UPDATE";
            u.at = now;
            u.before.add(old);
            mUndo = u;
            mLastTouched = nw.id;
            Reply r = new Reply(Kind.UPDATED, "改好了：" + p.changeDesc.replaceFirst("^把", "") + "。说「撤销」可以改回去。");
            r.eventId = nw.id;
            return r;
        }
        return new Reply(Kind.ERROR, "没有要确认的操作了。");
    }

    private Reply undo(long now) {
        Undo u = mUndo;
        mUndo = null;
        mPending = null;
        if (u == null) return new Reply(Kind.ERROR, "没有可以撤销的日程操作了。");
        if ("CREATE".equals(u.type)) {
            String t = "";
            for (long id : u.ids) {
                SchedEvent e = mStore.get(id);
                if (e != null) t = e.title;
                mStore.setDeleted(id, true, now);
            }
            return new Reply(Kind.UNDONE, "已撤销，刚才的「" + t + "」没有记。");
        }
        if ("DELETE".equals(u.type)) {
            for (long id : u.ids) mStore.setDeleted(id, false, now);
            SchedEvent e = u.ids.size() == 1 ? mStore.get(u.ids.get(0)) : null;
            return new Reply(Kind.UNDONE, e != null ? "已恢复「" + e.title + "」。" : "已恢复 " + u.ids.size() + " 个日程。");
        }
        for (SchedEvent e : u.before) mStore.update(e);
        return new Reply(Kind.UNDONE, "已改回原来的样子。");
    }

    /** 这句话刚刚（5 秒内）被 {@link #handle} 处理过（路由层先 route 再问 claims 时用）。 */
    public synchronized boolean recentlyHandled(String utterance, long now) {
        return mLastUtt != null && mLastUtt.equals(norm(utterance)) && now - mLastUttAt < DUP_MS;
    }

    /** 控制台「撤销」行：还能撤销时返回一句说明（「刚才新建了 1 个日程」），否则 null。 */
    public synchronized String undoLabel(long now) {
        Undo u = mUndo;
        if (u == null || now - u.at > UNDO_WINDOW_MS) return null;
        if ("CREATE".equals(u.type)) return "刚才新建了日程";
        if ("DELETE".equals(u.type)) return "刚才删除了 " + u.ids.size() + " 个日程";
        return "刚才修改了日程";
    }

    /** 控制台删除（界面已二次确认）：软删 + 登记撤销。 */
    public synchronized boolean deleteNow(long id, long now) {
        boolean ok = mStore.setDeleted(id, true, now);
        if (ok) {
            Undo u = new Undo();
            u.type = "DELETE";
            u.at = now;
            u.ids.add(id);
            mUndo = u;
        }
        return ok;
    }

    /** 控制台修改（界面已二次确认）：覆盖 + 登记撤销。 */
    public synchronized boolean updateNow(SchedEvent nw, long now) {
        SchedEvent old = mStore.get(nw.id);
        if (old == null) return false;
        nw.updatedAt = now;
        boolean ok = mStore.update(nw);
        if (ok) {
            Undo u = new Undo();
            u.type = "UPDATE";
            u.at = now;
            u.before.add(old);
            mUndo = u;
            mLastTouched = nw.id;
        }
        return ok;
    }

    /** 控制台新建（不确认，可撤销）。 */
    public synchronized long createNow(SchedEvent e, long now) {
        e.createdAt = now;
        e.updatedAt = now;
        e.deleted = false;
        long id = mStore.insert(e);
        noteCreated(id, now);
        return id;
    }

    /** 控制台 / 测试：直接撤销（不经语音）。 */
    public synchronized Reply undoNow(long now) {
        return undo(now);
    }

    /** 控制台新建 / 编辑后登记撤销（界面上的「撤销」）。 */
    public synchronized void noteCreated(long id, long now) {
        Undo u = new Undo();
        u.type = "CREATE";
        u.at = now;
        u.ids.add(id);
        mUndo = u;
        mLastTouched = id;
    }

    // ------------------------------------------------------------------ 找日程

    /** 按「明天的会」「开会」「周五下午的面试」「刚才那个」找日程（一次性：今天起 60 天内 + 昨天；重复：按系列）。 */
    List<SchedEvent> find(String what, long now) {
        List<SchedEvent> out = new ArrayList<SchedEvent>();
        String w = what == null ? "" : what.trim();
        String bare = stripTitle(w.replaceAll("那个|这个|那条|这条|的", ""));
        if (THAT.matcher(stripTitle(w)).matches() || bare.matches("^(刚才|刚刚)(建|加|记)?(的)?(日程)?$")) {
            if (mLastTouched > 0) {
                SchedEvent e = mStore.get(mLastTouched);
                if (e != null && !e.deleted) out.add(e);
            }
            return out;
        }
        CnTime.Result r = CnTime.parse(w, now, mTz);
        String t = stripTitle(r.rest.replaceAll("日程|行程|安排|那个|这个|那条|这条|所有|全部|的", " ").replaceAll("\\s+", ""));
        if (t.equals("会") || t.equals("会议") || t.equals("开会")) t = "会";
        boolean dateHint = r.found && (r.hasDate || r.weekStart >= 0);
        if (t.length() == 0 && !dateHint) return out;
        long today = CnTime.dayStart(now, mTz);
        long from = dateHint ? (r.weekStart >= 0 && !r.hasDate ? r.weekStart : CnTime.dayStart(r.start, mTz)) : CnTime.addDays(today, -1, mTz);
        long to = dateHint ? CnTime.addDays(from, r.weekStart >= 0 && !r.hasDate ? 7 : 1, mTz) : CnTime.addDays(today, 60, mTz);
        int hour = -1;
        if (r.hasTime && !r.timeGuessed && !r.allDay && !r.ambiguous()) {
            Calendar k = Calendar.getInstance(mTz);
            k.setTimeInMillis(r.start);
            hour = k.get(Calendar.HOUR_OF_DAY);
        }
        String period = r.timeGuessed ? periodOfStr(w) : null;
        List<Long> seen = new ArrayList<Long>();
        for (SchedRecur.Occ o : SchedRecur.expandAll(mStore.all(false), from, to, mTz)) {
            if (seen.contains(o.event.id)) continue;
            if (t.length() > 0 && !titleMatches(o.event.title, t)) continue;
            if (hour >= 0) {
                Calendar k = Calendar.getInstance(mTz);
                k.setTimeInMillis(o.start);
                if (k.get(Calendar.HOUR_OF_DAY) != hour) continue;
            }
            if (period != null && !inPeriod(o.start, period)) continue;
            seen.add(o.event.id);
            out.add(o.event);
        }
        // 没给日子、一次性里没找到：再看重复日程的系列名
        if (out.isEmpty() && !dateHint && t.length() > 0) {
            for (SchedEvent e : mStore.all(false)) if (e.repeating() && titleMatches(e.title, t)) out.add(e);
        }
        return out;
    }

    private List<SchedEvent> inRange(String what, long now) {
        CnTime.Result r = CnTime.parse(what, now, mTz);
        long today = CnTime.dayStart(now, mTz);
        long from, to;
        if (r.weekStart >= 0 && !r.hasDate) {
            from = r.weekStart;
            to = CnTime.addDays(from, 7, mTz);
        } else if (r.hasDate) {
            from = CnTime.dayStart(r.start, mTz);
            to = CnTime.addDays(from, 1, mTz);
        } else {
            from = today;
            to = CnTime.addDays(today, 1, mTz);
        }
        List<SchedEvent> out = new ArrayList<SchedEvent>();
        List<Long> seen = new ArrayList<Long>();
        for (SchedRecur.Occ o : SchedRecur.expandAll(mStore.all(false), from, to, mTz)) {
            if (o.event.repeating() || seen.contains(o.event.id)) continue;   // 「清空明天的日程」不动重复系列
            seen.add(o.event.id);
            out.add(o.event);
        }
        return out;
    }

    static boolean titleMatches(String title, String q) {
        if (title == null || q == null) return false;
        String a = title.replaceAll("\\s+", ""), b = q.replaceAll("\\s+", "");
        if (b.length() == 0) return false;
        if (a.contains(b) || b.contains(a)) return true;
        if ("会".equals(b)) return a.contains("会");
        // 字重合（「产品评审」vs「评审会」）：q 里至少 2 个字、过半出现在标题里
        int hit = 0, n = 0;
        for (int i = 0; i < b.length(); i++) {
            char ch = b.charAt(i);
            if ("的了个一和在去有我".indexOf(ch) >= 0) continue;
            n++;
            if (a.indexOf(ch) >= 0) hit++;
        }
        return n >= 2 && hit * 2 > n;
    }

    private int pickIndex(String q, List<SchedEvent> targets) {
        Matcher m = PICK.matcher(q);
        if (m.matches()) {
            int i = CnNum.parse(m.group(2)) - 1;
            return i >= 0 && i < targets.size() ? i : -1;
        }
        if (q.matches("^(最后一个|最后那个)$")) return targets.size() - 1;
        int hit = -1;
        for (int i = 0; i < targets.size(); i++) {
            if (titleMatches(targets.get(i).title, stripTitle(q))) {
                if (hit >= 0) return -1;
                hit = i;
            }
        }
        return hit;
    }

    private String listing(List<SchedEvent> l, long now) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.size() && i < 5; i++) {
            SchedEvent e = l.get(i);
            b.append(i == 0 ? "" : "；").append(i + 1).append(". ")
                    .append(e.repeating() ? SchedRecur.describe(e) : SchedFormat.when(e.startAt, e.allDay, now, mTz)).append(" ").append(e.title);
        }
        if (l.size() > 5) b.append("…");
        return b.toString();
    }

    // ------------------------------------------------------------------ 工具

    private Pending livePending(long now) {
        Pending p = mPending;
        if (p != null && now - p.at > PENDING_MS) {
            mPending = null;
            return null;
        }
        return p;
    }

    /**
     * ★ int/1102：这句话是不是日程（建 / 查 / 改 / 删 / 追问都算；纯函数，空库现算，不落库）。分诊 6d、S2 记一下与隐式记忆的排除用。
     * 「记个待办」「提醒我 10 分钟后」「叫我起床」「我女儿生日是5月3号」不算。
     */
    public static boolean looksLikeSchedule(String utterance) {
        try {
            if (utterance == null || utterance.trim().length() < 3) return false;
            SchedEngine e = new SchedEngine(new SchedStore.Memory(), TimeZone.getDefault());
            Plan x = e.plan(utterance, System.currentTimeMillis());
            if (x == null) return false;
            // 空库下「删掉明天的会」也会给 NOT_FOUND（说明是日程句）；ERROR = 日子不合法但确实在说日程
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** ★ NG-07：任务式「别忘了X / 提醒我X」（没有时间、要问什么时候提醒）。分诊用：这种句子不归记忆写入。纯函数。 */
    public static boolean isForgetRemindTask(String utterance) {
        try {
            if (utterance == null || utterance.trim().length() < 3) return false;
            SchedEngine e = new SchedEngine(new SchedStore.Memory(), TimeZone.getDefault());
            return e.planForgetRemind(norm(utterance), System.currentTimeMillis()) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是日程「查看」（今天有什么安排 / 今天的日程）。 */
    public static boolean looksLikeScheduleQuery(String utterance) {
        try {
            SchedEngine e = new SchedEngine(new SchedStore.Memory(), TimeZone.getDefault());
            Plan x = e.plan(utterance, System.currentTimeMillis());
            return x != null && x.op == Plan.Op.QUERY;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String norm(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("[。！!？?~～…\\s]+$", "").replaceAll("^(嗨|嘿|你好)[，, ]*", "");
        t = t.replaceAll("^(请|麻烦)(你)?(帮我)?", "帮我");
        return t.replaceAll("\\s+", "").trim();
    }

    static String stripTitle(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        for (int i = 0; i < 3; i++) {
            String before = t;
            t = t.replaceAll("^(帮我|给我|请)?(添加|新建|创建|加个|加一个|建个|记个|记一下|记下|安排一下|安排)?(一个|个)?(日程|行程)?[:：，, ]*", "");
            t = FILLER_HEAD.matcher(t).replaceAll("");
            t = FILLER_TAIL.matcher(t).replaceAll("");
            t = t.replaceAll("^(要|去|在|有|和|跟)", "").replaceAll("(这件事|这个事|的事|日程|行程)$", "").trim();
            if (t.equals(before)) break;
        }
        t = t.replaceAll("^(我|我们)?(约了|约好了|约好|定了|订了)", "").trim();   // ★ int/1102：「我约了周六七点吃饭」→「吃饭」
        // 句中被时间词切开留下的空格：「开 会」→「开会」；「和 王总 吃饭」保留词间一个空格
        t = t.replaceAll("(?<=[\\u4e00-\\u9fa5]) (?=[\\u4e00-\\u9fa5])", "");
        return t.trim();
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[\\u0000-\\u001f\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069]", "").trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String dur(long ms) {
        long min = ms / 60000L;
        if (min % 1440 == 0) return (min / 1440) + "天";
        if (min == 30) return "半小时";
        if (min % 60 == 0) return (min / 60) + "小时";
        if (min > 60 && min % 30 == 0) return (min / 60) + "个半小时";
        return min + "分钟";
    }

    private boolean isSunday(long now) {
        Calendar k = Calendar.getInstance(mTz);
        k.setTimeInMillis(now);
        return k.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY;
    }

    private long[] segment(String q, long day) {
        String p = periodOf(q);
        int a, b;
        if ("上午".equals(p) || "早上".equals(p)) { a = 0; b = 12; }
        else if ("中午".equals(p)) { a = 11; b = 14; }
        else if ("下午".equals(p)) { a = 12; b = 18; }
        else if ("晚上".equals(p)) { a = 18; b = 24; }
        else return null;
        return new long[]{day + a * 3600000L, day + b * 3600000L};
    }

    private static String periodOf(String q) {
        if (q.contains("上午")) return "上午";
        if (q.contains("早上")) return "早上";
        if (q.contains("中午")) return "中午";
        if (q.contains("下午")) return "下午";
        if (q.contains("晚上") || q.contains("今晚") || q.contains("明晚")) return "晚上";
        return "";
    }

    private static String periodOfStr(String q) {
        String p = periodOf(q);
        return p.length() == 0 ? null : p;
    }

    private boolean inPeriod(long t, String p) {
        Calendar k = Calendar.getInstance(mTz);
        k.setTimeInMillis(t);
        int h = k.get(Calendar.HOUR_OF_DAY);
        if ("上午".equals(p) || "早上".equals(p)) return h < 12;
        if ("中午".equals(p)) return h >= 11 && h < 14;
        if ("下午".equals(p)) return h >= 12 && h < 18;
        if ("晚上".equals(p)) return h >= 18;
        return true;
    }
}
