package com.onesay.assistant.v2.router;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 入口分诊决策表（P0-1，对应审计报告 §7.4）。
 *
 * <h3>为什么要有它</h3>
 * <p>旧入口闸 {@code UnifiedRouter.shouldUseAgentLoop} 的最后一行是 {@code return l0 == null}：
 * 凡是 L0 规则没命中的句子（知识问答、闲聊、"1加1等于几"）全进多轮工具循环，
 * AskEngine、L1 缓存、流式问答全部被跳过 —— 这是用户投诉 #2"问答/办事分不清"的根因。
 * 审计 70 条标注语料里旧逻辑只对 24 条。
 *
 * <h3>怎么判</h3>
 * <p>纯函数、零 Android 依赖、确定性：同一句话永远给同一个结论。按顺序走决策表，命中即停：
 * <ol>
 *   <li>空 / 只有唤醒词 → AMBIGUOUS（调用方丢弃）</li>
 *   <li>条件句 / 评估句 / "做+问"并列 → MIXED</li>
 *   <li>方法、原因、定义、创作、闲聊、算术、外部世界信息 → ASK（即使含设备词："wifi是什么"）</li>
 *   <li>剥掉礼貌框架（能不能/可以…吗/你能/帮我/麻烦）后：动作动词 + 缺必填槽 → CLARIFY</li>
 *   <li>动作动词（打开/关/调/设/发/打给/截图/下载/记/搜…）→ DO_ACT（≥2 个动作或时序词 = 多步）</li>
 *   <li>设备/个人数据对象 + 读取谓词（多少/开着吗/有哪些/看看/读…）→ DO_READ</li>
 *   <li>光名词可切换对象（手电筒/飞行模式）、抱怨式（太亮了）、意愿式（我想听歌）→ DO_ACT</li>
 *   <li>带疑问语气但无设备对象 → ASK（低置信）</li>
 *   <li>其余 → AMBIGUOUS（调用方可走 LLM 分类兜底；兜底失败按 ASK 处理 —— ASK 无副作用）</li>
 * </ol>
 *
 * <h3>与调用方的约定</h3>
 * <ul>
 *   <li>{@link Decision#useAgentLoop}：ASK / CLARIFY / AMBIGUOUS <b>永不</b>进 AgentLoop；
 *       MIXED 一律进；DO 类多步或达成型（下载/搜索）进；单步 DO 只有在本地层
 *       （L0 / L1 缓存）都接不住时才进（由 {@link Signals} 注入）。</li>
 *   <li>{@link Decision#core}：剥掉礼貌/查看框架后的核心短语（"帮我看看现在几点"→"现在几点"），
 *       调用方可以用它再试一次 L0 / AskEngine。</li>
 * </ul>
 *
 * <p>Java 7 语法（无 lambda / stream）。
 */
public final class TriageDecider {

    private TriageDecider() {}

    /** 分诊标签。 */
    public enum Label {
        /** 知识 / 闲聊 / 方法 / 创作 / 外部世界信息：走 AskEngine → L1(ASK) → 流式问答，永不进 AgentLoop。 */
        ASK,
        /** 读设备状态 / 个人数据 / 屏幕内容。 */
        DO_READ,
        /** 改变状态 / 打开 / 发送 / 创建。 */
        DO_ACT,
        /** 同一句里既要做又要问，或条件/评估类（"如果…就…"、"够不够"）。 */
        MIXED,
        /** 意图明确但缺必填参数（"定个闹钟"）：追问，绝不让 LLM 编默认值。 */
        CLARIFY,
        /** 规则层判不了：可选 LLM 分类兜底。 */
        AMBIGUOUS,
        /**
         * [diag] 症状描述（「为什么口袋里屏幕总亮」「手机耗电好快」「勿扰是不是开着」）：读当前设置 → 解释原因 → 修复芯片。
         * 永不进 AgentLoop、永不查/写 L1 ASK 缓存（设置值会变）。。
         */
        DIAGNOSE
    }

    /** 调用方注入的外部信号（JVM 测试用 {@link #offline()}）。 */
    public static final class Signals {
        /** L0 规则命中的签名（null = 未命中 / 未知）。 */
        public final String l0Sign;
        /** L1 缓存（同标签组）命中。 */
        public final boolean cacheHit;
        /** AskEngine 本地能答。 */
        public final boolean askLocal;

        public Signals(String l0Sign, boolean cacheHit, boolean askLocal) {
            this.l0Sign = l0Sign;
            this.cacheHit = cacheHit;
            this.askLocal = askLocal;
        }

        public static Signals offline() {
            return new Signals(null, false, false);
        }
    }

    /** 分诊结论。 */
    public static final class Decision {
        public final Label label;
        /** 是否建议进 AgentLoop（见类注释的约定）。 */
        public final boolean useAgentLoop;
        /** 置信度 0~1（规则层给的是经验值，用于决定要不要 LLM 兜底与日志）。 */
        public final double confidence;
        /** 命中的规则编号（日志 [TRIAGE] rule#N）。 */
        public final String rule;
        /** 剥掉礼貌/查看框架后的核心短语（可能与原句相同）。 */
        public final String core;
        /** DO 类：是否多步（≥2 个动作 / 时序词）。 */
        public final boolean multiStep;
        /** DO 类：是否达成型动作（下载/安装/搜索）—— 一步只到"中间态"，需要循环看结果。 */
        public final boolean goalOriented;
        /** 由谁判的："rule" 或 "llm"。 */
        public final String by;

        Decision(Label label, boolean useAgentLoop, double confidence, String rule, String core,
                 boolean multiStep, boolean goalOriented, String by) {
            this.label = label;
            this.useAgentLoop = useAgentLoop;
            this.confidence = confidence;
            this.rule = rule;
            this.core = core;
            this.multiStep = multiStep;
            this.goalOriented = goalOriented;
            this.by = by;
        }

        public boolean isDo() {
            return label == Label.DO_ACT || label == Label.DO_READ;
        }

        /** 换标签（LLM 兜底后用），其余字段按新标签重新推导 useAgentLoop。 */
        public Decision relabel(Label l, double conf, String by, Signals s) {
            return build(l, conf, rule, core, multiStep, goalOriented, by, s);
        }

        @Override public String toString() {
            return "label=" + label + " conf=" + confidence + " by=" + by + "#" + rule
                    + " agentLoop=" + useAgentLoop + (multiStep ? " multi" : "")
                    + (goalOriented ? " goal" : "") + " core=" + core;
        }
    }

    // =====================================================================
    // 词表（只收"信号词"，不收例句；每个词都要在 TriageDeciderTest 里有用例覆盖）
    // =====================================================================

    /** 唤醒词残留（ASR 偶尔把唤醒词带进正文）。 */
    private static final String[] WAKE_WORDS = {
            // OneSay：不剥离厂商唤醒词残留（原 嗨比西比 / Bixby 表已删除）
    };

    /** 礼貌/请求框架（剥离后按核心短语判）。按长度降序排列，先剥长的。 */
    private static final String[] POLITE_PREFIX = {
            "你能不能帮我", "能不能帮我", "可不可以帮我", "可以帮我", "能帮我", "你能帮我", "你可以帮我",
            "麻烦你帮我", "麻烦帮我", "请你帮我", "请帮我", "帮我一下",
            "能不能", "可不可以", "能否", "你能", "你可以", "可以", "麻烦你", "麻烦", "请你", "请",
            "帮我", "帮忙", "给我", "替我",
    };
    private static final String[] POLITE_SUFFIX = {
            "好不好", "可以吗", "行不行", "好吗", "行吗", "成吗", "一下吧", "一下", "吧", "呗", "呀", "啊", "哦", "呢", "吗",
    };
    /** "查看"框架：剥掉后看宾语（"帮我看看现在几点" → "现在几点"）。 */
    private static final String[] PEEK_PREFIX = {
            "看一下", "看一看", "看看", "查一下", "查查", "瞧瞧", "告诉我",
    };

    /** 方法/原因/定义类疑问 —— ASK 的最强信号（即使含设备词）。 */
    private static final String[] HOWTO_WHY = {
            "怎么办", "怎么回事", "怎么样才能", "怎么", "怎样", "如何", "为什么", "为啥", "干嘛要",
            "在哪里设置", "在哪设置", "在哪儿设置", "在哪里打开", "在哪里",
            "是什么", "什么是", "什么意思", "啥意思", "是啥", "有什么用", "有啥用", "什么作用",
            "区别", "原理", "是谁", "谁是",
    };
    /** 知识/创作/闲聊类请求动词。 */
    private static final String[] ASK_VERBS = {
            "介绍", "解释", "翻译", "推荐", "讲个笑话", "讲笑话", "讲个故事", "讲故事", "说个笑话",
            "写一首", "写首", "写一篇", "写篇", "作一首", "写诗", "写个故事", "写一段", "写段",
            "总结一下", "科普", "聊聊", "聊天", "讲讲", "说说", "想个", "起个名", "取个名", "你会做什么", "你能做什么", "你是谁", "你叫什么", "你好", "谢谢", "早上好", "晚安",
    };
    /** 外部世界信息（时效类）—— 不是设备数据，走问答通道（联网检索）。 */
    private static final String[] WORLD_INFO = {
            "天气", "下雨", "下雪", "气温", "刮风", "雾霾", "空气质量", "新闻", "头条", "股价", "股票", "汇率",
            "油价", "金价", "比特币", "限行", "路况", "多少钱", "价格", "比分", "日出", "日落", "多少度", "几度",
    };
    /** 算术/换算 —— AskEngine 本地答或问答通道。 */
    private static final String ARITH_RE =
            ".*([0-9零一二两三四五六七八九十百千万]+(加|减|乘以|乘|除以|除|\\+|-|\\*|/|x)[0-9零一二两三四五六七八九十百千万]+).*|.*(等于几|等于多少|等于多少米|是多少米|多少公斤|换算).*";

    /** 条件 / 评估 —— 必须先看结果再决定，MIXED。 */
    private static final String[] CONDITIONAL = {
            "如果", "要是", "假如", "的话就", "够不够", "该不该", "值不值", "要不要", "还够", "再决定", "再判断", "根据",
    };
    /** "做 + 问"并列连接（"打开蓝牙顺便告诉我什么是蓝牙"）。 */
    private static final String[] DO_AND_ASK = {
            "顺便告诉我", "顺便说说", "顺便讲讲", "然后告诉我", "再告诉我", "并告诉我", "并且告诉我", "顺便介绍",
    };
    /** 时序词（多步）。 */
    private static final String[] SEQUENCE = {
            "然后", "接着", "之后再", "再帮我", "顺便", "完了再", "完再", "并且", "同时",
    };

    /**
     * 动作动词（祈使）。注意不收"开"单字（"开着吗"是读）；"关"单字用搭配收。
     * 每个词后面注释它属于哪类动作，用于"达成型"判断。
     */
    private static final String[] ACT_VERBS = {
            "打开", "开启", "启动", "开一下", "开下", "开个", "开开",
            "关掉", "关闭", "关了", "关上", "关下", "关一下", "关机", "重启",
            "调高", "调低", "调大", "调小", "调亮", "调暗", "调到", "调成", "调为", "调整", "调一下", "调",
            "设置成", "设成", "设为", "设定", "设置", "设个", "设一个", "定个", "定一个", "定", "设",
            "发短信", "发消息", "发微信", "发给", "发送", "发个", "回复",
            "打电话", "打给", "拨打", "呼叫", "打个电话",
            "截图", "截个图", "截屏", "截一张", "录屏", "拍照", "拍张", "拍个", "拍一张",
            "下载", "安装", "卸载", "装个", "装一下", "删掉", "删除", "清理", "清除",
            "新建", "创建", "记一下", "记下", "记个", "帮我记", "记录", "备忘", "写一封", "写封", "写个邮件", "写邮件",
            "存草稿", "草稿",
            "搜一下", "搜索", "搜个", "搜", "查找", "找一下",
            "打车", "打个车", "叫车", "叫个车", "来个", "来一个",
            "播放", "放首", "放一首", "放个", "来首", "来一首", "暂停", "下一首", "上一首", "切歌",
            "提醒我", "提醒", "叫醒", "叫我起床", "叫我",
            "静音", "切换", "连接", "断开", "分享", "导航", "跳转", "进入", "返回桌面", "回到桌面",
    };
    /** 达成型动作（一步只到"中间态"）：下载/安装/搜索。 */
    private static final String[] GOAL_VERBS = {
            "下载", "安装", "装个", "装一下", "搜一下", "搜索", "搜个", "搜", "查找", "找一下",
    };

    /** 设备 / 个人数据对象（读取的宾语）。 */
    private static final String[] DEVICE_OBJECTS = {
            "电量", "电池", "还有多少电", "存储", "空间", "内存", "音量", "声音", "亮度", "屏幕", "画面",
            "wifi", "wlan", "无线网", "网络", "蓝牙", "定位", "gps", "位置", "数据", "流量", "热点",
            "飞行模式", "勿扰", "省电", "自动旋转", "旋转", "字体", "铃声", "振动", "通知",
            "闹钟", "日程", "日历", "行程", "会议", "待办", "安排",
            "短信", "信息", "消息", "邮件", "邮箱", "收件箱", "微信", "未接来电", "来电", "通话记录",
            "相册", "照片", "截图", "联系人", "电话",
            "时间", "几点", "日期", "几号", "星期几", "周几", "礼拜几",
    };
    /** 读取谓词。 */
    private static final String[] READ_PREDICATES = {
            "多少", "几点", "几号", "星期几", "周几", "礼拜几", "开着吗", "开着没", "开了吗", "开了没", "关着吗", "关了吗",
            "连上了吗", "连上没", "连着吗", "连接了吗", "有哪些", "有什么", "有没有", "有几", "还剩", "剩多少", "剩下",
            "是多少", "是几", "看看", "看一下", "查一下", "查查", "读一下", "读读", "念一下", "读", "念",
            "告诉我", "是不是", "状态", "最新",
    };
    /** 光名词可切换对象（整句只有它 = 切换）。 */
    private static final String[] TOGGLE_NOUNS = {
            "手电筒", "闪光灯", "飞行模式", "wifi", "wlan", "蓝牙", "热点", "静音", "勿扰模式", "勿扰",
            "省电模式", "夜间模式", "护眼模式", "深色模式", "自动旋转", "定位", "移动数据", "数据",
    };
    /** 抱怨式（隐含动作）。 */
    private static final String[] COMPLAINTS = {
            "太亮了", "太暗了", "太黑了", "太刺眼", "太吵了", "太大声", "声音太小", "声音太大", "听不清", "看不清",
            "太小声", "好吵", "好暗", "好亮",
    };
    /** 比较式调节尾巴。 */
    private static final String[] ADJUST_TAILS = {
            "大一点", "小一点", "亮一点", "暗一点", "高一点", "低一点", "大点", "小点", "亮点", "暗点", "高点", "低点",
            "大些", "小些", "亮些", "暗些",
    };
    /** 意愿式。 */
    private static final String[] DESIRE = {
            "我想听", "我要听", "想听歌", "听歌", "听音乐", "听首歌", "我想看", "我要看", "我想拍", "我要拍",
    };
    /** 需要必填槽的动作对象 → 有数字/时间/方向才算参数齐全。 */
    private static final String[] SLOT_OBJECTS = { "闹钟", "计时器", "倒计时", "定时" };
    /** ★ int/1102：批量指令（目标 = 全部），不追问「哪一个 / 几点」。 */
    static final java.util.regex.Pattern BATCH_ALL = java.util.regex.Pattern.compile("所有|全部|全都|统统|一律|全(删|关|开|清)|都(删|关|清|打开)|清空");

    // ---- [s2] 记得住：记一下 / 回忆 / 记忆管理 / 习惯默认值（闪记记账默认关、不进分诊）----
    /** 「记一下…」写入前缀（核心短语开头）。不收「记得」（「记得我车停哪吗」是问句）。 */
    /** 票类口令的分诊规则 id（PlannerPlugin.triageRules 声明）。 */
    public static final String RULE_TICKET_SAVE = "6k-ticket-save";
    private static final java.util.regex.Pattern MEMORY_WRITE = java.util.regex.Pattern.compile(
            "^(记一下|记住|记下来|记下|记个|记着|备忘一下|备忘|别忘了|存一下)");
    /** 回忆问句：「我车停哪了」「我把护照放哪了」「上次记的…」「我记了什么」。 */
    private static final java.util.regex.Pattern MEMORY_RECALL = java.util.regex.Pattern.compile(
            "(停|放|搁|存|塞|藏|锁|丢)(在|到)?(哪儿|哪里|哪个位置|哪个地方|哪一?[层个楼排格区号]|几号|几层|什么地方|什么位置|哪)(了|呢|啦|来着|呀|啊)*$"
            + "|上次记的|之前记的|刚才记的|刚记的|我记的|我记过|记过的|你记得我|还记得我|记了什么|记了些什么|记了哪些|记了啥");
    /** 记忆管理：「忘掉停车那条」「清空记忆」；★ int/1102 审计：「忘掉我的工号」「别再记我的住址」（句首 + 我的…）。 */
    private static final java.util.regex.Pattern MEMORY_MANAGE = java.util.regex.Pattern.compile(
            "(忘掉|忘记|忘了|清空|删除|删掉|清除|清掉).{0,10}(记录|记忆|备忘|那条|这条|记的)|^(忘掉|忘记掉|别再记|不要再记|别记)(我的|我)");

    /**
     * ★ int/1102 审计：富内容生成请求（「画个流程图…」「给段 python 代码」「写个单例的例子」「用 mermaid 画时序图」）= 问答通道直接出
     * 代码块 / 图（rich-output），不是设备动作 → ASK，不再 AMBIGUOUS → LLM 分类 → AgentLoop。
     */
    private static final java.util.regex.Pattern RICH_GEN = java.util.regex.Pattern.compile(
            "^(帮我|给我|请|麻烦)?(画|绘制|生成|写|给|来|出|列|做)(个|一个|一段|段|张|一张|份|一份|下|一下)?.{0,20}"
                    + "(流程图|时序图|序列图|思维导图|架构图|结构图|饼图|柱状图|折线图|条形图|图表|代码|例子|示例|正则|正则表达式|清单|脚本|函数|sql|算法|伪代码)"
                    + "|^(帮我|请)?用(mermaid|svg|chart|代码|markdown|列表|清单)(来)?(画|写|列|表示|说明|展示)"
                    // ★ int/1102 审计：「用表格对比一下 iphone 和三星」= 问答通道出表格（rich-output），不是调研任务进 AgentLoop
                    + "|^(帮我|请|给我)?(用|做|列|画|出)(个|一个|张|一张)?(表格|表|对比表|图表)(来)?(对比|比较|列|列出|整理|总结|说明|展示|汇总)");

    /** ★ int/1102 审计：对上一轮回答的追问（「再说一遍」「详细一点」）= ASK（问答通道带历史），不是动作。 */
    private static final java.util.regex.Pattern ANSWER_FOLLOWUP = java.util.regex.Pattern.compile(
            "^(再说一遍|再讲一遍|再说一次|重复一遍|详细一点|详细点|说详细点|说详细一点|具体一点|具体点|说具体点|展开说说|展开讲讲|展开一下|简单点|简单一点|说简单点|通俗一点|举个例子|再举个例子)$");
    /** 习惯默认值：「别默认用滴滴了」「以后打车都用滴滴」。 */
    private static final java.util.regex.Pattern HABIT_PREF = java.util.regex.Pattern.compile(
            "默认用|默认的|别默认|不要默认|不用默认|取消默认|以后.{0,6}都用|今后.{0,6}都用|重置.{0,8}默认|(清除|清空).{0,6}默认");
    /** [roles] 默认应用查询：「打车用哪个」「默认应用有哪些」「看看我的默认应用」= 读本机状态（DO_READ）。 */
    private static final java.util.regex.Pattern ROLE_READ = java.util.regex.Pattern.compile(
            "(打车|叫车|导航|外卖|点餐|听歌|听音乐|看视频|浏览器|邮件)(默认)?(用哪个|用哪家|用什么|用的哪个|用的什么)(app|软件|应用)?$"
            + "|默认(的)?(应用|app|软件)(都)?(有哪些|有什么|是什么|是哪些|列表|都是啥|是啥)?$"
            + "|(看看|列出|查看|说说|看下|看一下)(我的)?默认(的)?(应用|app|软件)");

    // ---- D-3b（2026-10-09 第 4 阶段）：旧盲测 4 条错判的泛化修正（盲测集 v2 在这之前已冻结）----
    /** 两地之间的距离 / 路程问句（「北京到上海多远」「从家到公司几公里」）= 外部世界信息 → ASK；带动作（导航/打车）的不算。 */
    private static final java.util.regex.Pattern DISTANCE_Q = java.util.regex.Pattern.compile(
            "^(从)?[^到]{1,12}到[^到]{1,12}(有|大概|大约|一共)?(多远|多少公里|多少千米|多少里|几公里|几千米|距离多少|的距离)(啊|呀|呢|吗)?$");
    /** 阈值触发条件（「电量低于百分之二十的时候提醒我」「超过三十度时告诉我」）：要先盯状态再决定 → MIXED。 */
    private static final java.util.regex.Pattern THRESHOLD_WHEN = java.util.regex.Pattern.compile(
            "(低于|高于|超过|不足|少于|小于|大于|不到|降到|升到|跌破|突破)[^，,。]{0,12}(的时候|之时|时候|时)");
    /** 读完再做知识加工（「看看屏幕上写的什么然后翻译一下」）：先读（设备/屏幕）、再问 → MIXED。 */
    private static final String[] READ_THEN_ASK_VERBS = { "翻译", "解释", "总结", "概括", "讲讲", "说说" };
    /** 省略对象的音量比较式（「再大声一点」「小声点」）：整句只剩它时 = 隐含调节动作 DO_ACT。 */
    private static final String[] IMPLICIT_ADJUST = {
            "大声一点", "大声点", "大声些", "小声一点", "小声点", "小声些", "大声一些", "小声一些",
    };
    private static final String[] ADJUST_LEADERS = { "再稍微", "稍微再", "还能再", "再", "稍微", "更" };

    /** 疑问语气（只在没有其它信号时作为 ASK 的弱证据）。 */
    private static final String[] QUESTION_TAILS = { "吗", "呢", "么", "?", "？", "啥", "什么", "哪", "谁", "几", "有多", "多少", "多久" };

    // =====================================================================
    // 入口
    // =====================================================================

    /** ★ int/1102：本地日程开关（SchedulePlugin.onInit / 开关变化时写；默认开）。关 = 日程句回到原判法。 */
    public static volatile boolean sSchedOn = true;
    /** ★ trip-decompose：规划模式的分诊规则名（AgentLoop 据此进规划模式）。 */
    public static final String RULE_TRIP_PLAN = "2p-trip-plan";

    /** 规则层决策（纯函数）。设置诊断按开着处理（与产品默认一致）。 */
    public static Decision decide(String utterance, Signals s) {
        return decide(utterance, s, true);
    }

    /**
     * [diag] 规则层决策。
     * @param allowDiagnose 设置诊断开关（关闭 = 症状句回到 #9 之前的判法）
     */
    public static Decision decide(String utterance, Signals s, boolean allowDiagnose) {
        return decide(utterance, s, allowDiagnose, true);
    }

    /**
     * [s2] round3 #2：规则层决策。
     * @param allowImplicitMemory 免前缀记忆开关（{@code bixby_s2 memory.implicit}）；关闭 = 陈述句回到 round3 之前的判法
     */
    public static Decision decide(String utterance, Signals s, boolean allowDiagnose, boolean allowImplicitMemory) {
        return decide(utterance, s, allowDiagnose, allowImplicitMemory, true);
    }

    /**
     * [imgsave] 规则层决策。
     * @param allowImageSave 语音存图开关（{@code bixby_imgsave}）；关闭 = 「保存图片」类句子回到原判法
     */
    public static Decision decide(String utterance, Signals s, boolean allowDiagnose, boolean allowImplicitMemory, boolean allowImageSave) {
        return decide(utterance, s, allowDiagnose, allowImplicitMemory, allowImageSave, true);
    }

    /**
     * [summary] 规则层决策。
     * @param allowSummary #18 长内容摘要开关（{@code bixby_summary}）；关闭 = 「总结一下 / 这篇讲了什么」回到原判法
     */
    public static Decision decide(String utterance, Signals s, boolean allowDiagnose, boolean allowImplicitMemory, boolean allowImageSave,
                                  boolean allowSummary) {
        return decide(utterance, s, allowDiagnose, allowImplicitMemory, allowImageSave, allowSummary, true);
    }

    /**
     * [s3] 规则层决策。
     * @param allowS3 定时任务 / 待办开关（{@code bixby_s3} 或 {@code bixby_todo} 任一开）；关闭 = 这些句子回到原判法
     */
    public static Decision decide(String utterance, Signals s, boolean allowDiagnose, boolean allowImplicitMemory, boolean allowImageSave,
                                  boolean allowSummary, boolean allowS3) {
        if (s == null) s = Signals.offline();
        String q = normalize(utterance);
        if (q.length() == 0) {
            return build(Label.AMBIGUOUS, 0.0, "1-empty", "", false, false, "rule", s);
        }

        // ★ 11-04 F（白盒审计·否定句）：「别打开蓝牙 / 不要静音 / 先别打开相机」是叫我<b>别做</b>，不能按关键词当动作进 AgentLoop。
        //   只拦整句以否定词开头、后面紧跟动作动词、且不以「了」收尾的（「不要提醒我了」是要取消，照旧走动作）；
        //   落 ASK：只回一句话、永不调工具。
        if (isNegatedCommand(q)) {
            return new Decision(Label.ASK, false, 0.9, "0n-negated", q, false, false, "rule");
        }

        // ★ fix/shop-research（R5）：「好 / 你决定」接回上一轮任务（TaskContinuation 改写过）→ 直接进 AgentLoop，不重新分诊成问答
        if (TaskContinuation.isContinuation(utterance)) {
            return new Decision(Label.DO_ACT, true, 0.95, "0c-continue", TaskContinuation.strip(utterance), false, true, "rule");
        }
        String core = stripFrames(q);
        boolean polite = !core.equals(q) || hasPoliteFrame(q);
        // ★ trip-decompose：「安排 / 规划 / 帮我搞定 / 准备一下」+ 出行 / 日期 / 地点 = 复合任务 → AgentLoop 规划模式（在日程本地层之前；
        //   原先「下个月10号我要去东京…安排一下」落到 10-unsure → LLM 判成问答 / 单步，回「没接口 / 做不了」）
        if (com.onesay.assistant.v2.trip.TripIntent.isTrigger(utterance)) {
            return new Decision(Label.MIXED, true, 0.9, RULE_TRIP_PLAN, core, true, true, "rule");
        }

        // [imgsave] 「保存图片 / 存这张图 / 存一下图片 / 全部保存」= 单步 DO_ACT，本地接管、永不进 AgentLoop。
        //   在记忆规则之前：「存一下图片」不能被 S2 的「存一下」前缀判成记一下。判定与 L0 / 面板同源（ImageSaveIntent，用原句）。
        if (allowImageSave && com.onesay.assistant.v2.imgsave.ImageSaveIntent.isTrigger(utterance)) {
            return new Decision(Label.DO_ACT, false, 0.9, "6i-image-save", core, false, false, "rule");
        }
        // [planner] 票类口令（「帮我记下这张票」「把这张票存下来」「记下票」）= 识屏认票，单步 DO_ACT：
        //   计划 App 在 → 票夹（PlannerPlugin 285），不在 → 记一记识屏。在记忆规则之前：不能被「记下 / 存一下」前缀判成 memory.add。
        if (com.onesay.assistant.v2.planner.PlannerVoice.ticket(utterance)) {
            return new Decision(Label.DO_ACT, false, 0.9, RULE_TICKET_SAVE, core, false, false, "rule");
        }
        // [summary] 「总结一下 / 总结这页 / 这篇讲了什么 / 太长不看」= 读屏幕 → 只读单步 DO_READ，本地接管、永不进 AgentLoop。
        //   判定与 L0 / 面板同源（SummaryIntent，用原句）；「总结我的记忆」「总结今天的邮件」、知识问句不命中，照旧。
        if (allowSummary && com.onesay.assistant.v2.summary.SummaryIntent.isTrigger(utterance)) {
            return new Decision(Label.DO_READ, false, 0.9, "5s-screen-summary", core, false, false, "rule");
        }
        // ★ int/1102 [sched] 日程（日历 + 提醒）：带日期 / 钟点的事件句、「今天有什么安排」、「加个日程」= 单步，本地日程接管（SchedulePlugin 290），
        //   永不进 AgentLoop、永不归记一记 / 记忆（用户 2026-11-02：「让它记日程它直接说我记住了」）。判定与 SchedEngine.plan 同源。
        if (sSchedOn && com.onesay.assistant.v2.sched.SchedEngine.looksLikeSchedule(utterance)) {
            boolean read = com.onesay.assistant.v2.sched.SchedEngine.looksLikeScheduleQuery(utterance);
            return new Decision(read ? Label.DO_READ : Label.DO_ACT, false, 0.9, read ? "5d-sched-list" : "6d-sched", core, false, false, "rule");
        }
        // [s3] 定时任务 / 待办（S3Parser 与 S3Engine 同源）：新建 / 取消 / 记个待办 / 明说待办的勾销 = 单步 DO_ACT，列表 = DO_READ；
        //   永不进 AgentLoop。在记忆规则之前：「记个待办 买牛奶」不能被 S2 的「记个」前缀判成记一下。
        //   不接管：没有钟点的「提醒我明天交报告」、相对时间的「40分钟后提醒我」、闹钟类「每天7点叫醒我」（照旧）。
        if (allowS3) {
            com.onesay.assistant.v2.s3.S3Parser.Parsed sp = com.onesay.assistant.v2.s3.S3Parser.parse(utterance, System.currentTimeMillis(),
                    java.util.TimeZone.getDefault());
            if (sp != null) {
                switch (sp.type) {
                    case CREATE: return new Decision(Label.DO_ACT, false, 0.9, "6t-s3-create", core, false, false, "rule");
                    case LIST: return new Decision(Label.DO_READ, false, 0.9, "5t-s3-list", core, false, false, "rule");
                    case TODO_LIST: return new Decision(Label.DO_READ, false, 0.9, "5t-todo-list", core, false, false, "rule");
                    case TODO_ADD: return new Decision(Label.DO_ACT, false, 0.9, "6t-todo-add", core, false, false, "rule");
                    case TODO_CLEAR: return new Decision(Label.DO_ACT, false, 0.9, "6t-todo-clear", core, false, false, "rule");   // ★ int/1102 审计
                    case CANCEL:
                        if (utterance.contains("提醒") || utterance.contains("定时")) {
                            return new Decision(Label.DO_ACT, false, 0.85, "6t-s3-cancel", core, false, false, "rule");
                        }
                        break;
                    case TODO_DONE:
                        if (com.onesay.assistant.v2.s3.S3Parser.explicitTodoDone(utterance)) {
                            return new Decision(Label.DO_ACT, false, 0.85, "6t-todo-done", core, false, false, "rule");
                        }
                        break;
                    default:
                        break;
                }
            }
        }
        // [s2] 记得住：回忆问句 = ASK（本地记忆库先答，永不进 AgentLoop）；记一下 / 记忆管理 / 习惯偏好 = 单步 DO_ACT
        if (MEMORY_RECALL.matcher(q).find() || com.onesay.assistant.v2.s2.NoteParser.isKinQuestion(utterance)) {
            return build(Label.ASK, 0.85, "3m-memory-recall", core, false, false, "rule", s);
        }
        if ((MEMORY_WRITE.matcher(core).find() || MEMORY_WRITE.matcher(q).find())
                && !com.onesay.assistant.v2.sched.SchedEngine.isForgetRemindTask(utterance)) {   // ★ NG-07：「别忘了交房租」是提醒，不是记忆（日程关着时也不写记忆）
            return build(Label.DO_ACT, 0.9, "6m-memory-write", core, containsAny(core, SEQUENCE), false, "rule", s);
        }
        // [s2] round3 #2：免前缀的事实陈述（「我车停在B2-17」「护照放在书房第二个抽屉」）= 记一下（单步 DO_ACT，S2 本地接管）。
        //   判定与 S2 完全同源（ImplicitNote，用原句：normalize 会把「B2-17」的 - 去掉）；问句/指令/知识陈述不命中。
        if (allowImplicitMemory) {
            com.onesay.assistant.v2.s2.ImplicitNote.Hit ih = com.onesay.assistant.v2.s2.ImplicitNote.detect(utterance);
            if (ih != null) {
                return build(Label.DO_ACT, 0.85, "6m-memory-implicit", core, false, false, "rule", s);
            }
        }
        if (ROLE_READ.matcher(q).find() && !HABIT_PREF.matcher(q).find()) {
            return build(Label.DO_READ, 0.85, "5r-role-read", core, false, false, "rule", s);
        }
        if (MEMORY_MANAGE.matcher(q).find() || HABIT_PREF.matcher(q).find()) {
            return build(Label.DO_ACT, 0.85, "6m-memory-manage", core, false, false, "rule", s);
        }
        // ★ int/1102 审计：改值追问（「改成八点」「再大一点」）走到这里 = 第 0 层没找到可改的上一轮 → CLARIFY（本地问一句），不进 AgentLoop
        if (FollowupEdit.isEditShape(q)) {
            return build(Label.CLARIFY, 0.85, "1f-followup-edit", core, false, false, "rule", s);
        }
        // ★ int/1102 审计：追问上一轮回答 / 富内容生成请求 → ASK（问答通道；富内容由 rich-output 渲染）
        if (ANSWER_FOLLOWUP.matcher(q).matches()) {
            return build(Label.ASK, 0.85, "3f-answer-followup", core, false, false, "rule", s);
        }
        if (s.l0Sign == null && RICH_GEN.matcher(q).find()) {
            return build(Label.ASK, 0.85, "3r-rich-gen", core, false, false, "rule", s);
        }
        // 其它明确要表格的说法（「iPhone和三星对比一下，用表格」「列个表说说区别」）同样直接问答出表格
        if (s.l0Sign == null && com.onesay.assistant.plug.richout.core.RichOutPlugs.wantsTable(q)) {
            return build(Label.ASK, 0.85, "3t-table", core, false, false, "rule", s);
        }
        int actCount = countActs(core);
        boolean act = actCount > 0;
        boolean multi = actCount >= 2 || containsAny(core, SEQUENCE) || isAndJoinedActs(core);
        boolean goal = containsAny(core, GOAL_VERBS);

        // 2. MIXED：条件/评估，或"做 + 问"并列
        if (containsAny(q, CONDITIONAL) && (act || containsAny(q, DEVICE_OBJECTS))) {
            return build(Label.MIXED, 0.85, "2-conditional", core, true, goal, "rule", s);
        }
        if (containsAny(q, DO_AND_ASK) && (act || containsAny(q, DEVICE_OBJECTS))) {
            return build(Label.MIXED, 0.9, "2-do-and-ask", core, true, goal, "rule", s);
        }
        // D-3b：阈值触发（「低于…的时候提醒我」）—— 先盯状态、到条件再做，与「如果…就…」同类
        if (THRESHOLD_WHEN.matcher(q).find() && (act || containsAny(q, DEVICE_OBJECTS) || q.contains("告诉我") || q.contains("通知我"))) {
            return build(Label.MIXED, 0.85, "2-threshold-when", core, true, goal, "rule", s);
        }
        // D-3b：先读设备/屏幕、再做知识加工（「看看屏幕上写的什么然后翻译一下」）
        if (containsAny(q, SEQUENCE) && containsAny(q, READ_THEN_ASK_VERBS)
                && containsAny(q, DEVICE_OBJECTS) && containsAny(q, READ_PREDICATES)) {
            return build(Label.MIXED, 0.85, "2-read-then-ask", core, true, false, "rule", s);
        }

        // 2d. [diag] 症状描述 → DIAGNOSE（在方法/原因问句之前：「为什么一直振动」是要诊断，不是要科普）
        if (allowDiagnose) {
            com.onesay.assistant.v2.diag.DiagMatcher.Match dm = diagnoseMatch(q, core, actCount);
            if (dm != null) {
                return build(Label.DIAGNOSE, dm.unknown ? 0.7 : 0.9, "2d-diagnose:" + dm.id(), core, false, false, "rule", s);
            }
        }

        // 2a. 「微信一打开就闪退」= 描述应用故障，原被动词「打开」判 6-act → 单步 DO 无 L0 → 进 AgentLoop
        //     （循环可能真去打开微信）。本机诊断表不管应用崩溃 → 按问答给排查建议（零副作用，不进循环）；带「帮我清缓存 / 重启」等指令的照旧当动作。
        if (isAppCrashComplaint(q)) {
            return build(Label.ASK, 0.85, "3-app-crash", core, false, false, "rule", s);
        }

        // 2w. ★ arch/agent-loop（B 路）：「帮我调研 / 查资料 / 网上查 / 查一下＋话题」= 先查再答的调研任务 → 进 AgentLoop
        //   （循环里有 web.search / web.read / research.spawn），不再被分诊前的浏览器跑腿路由抢走（ErrandHooks.claims 同步收窄）。
        //   弱信号「查一下 X」只在 X 不是设备 / 个人数据、不是天气股价这类一句话就能答的时效信息、也没有别的动作时才算。
        if (sWebResearch) {
            String wr = webResearchRule(q, core);
            if (wr != null) return build(Label.DO_READ, wr.endsWith("strong") ? 0.85 : 0.75, "5w-web-research-" + wr, core, false, true, "rule", s);
        }

        // 2m.（排在 2w 之后：明说「调研 / 对比一下」的仍记 5w） ★ fix/research-depth（用户 11-03 12:04）：行情 / 市场 / 走势 / 二手价 / 客单价 / 对比类 → 进 AgentLoop 先拆维度检索
        //   （带收工门槛：≥2 个独立来源 + ≥1 次 web.read、终答缺数字 nudge），不走纯问答直答（「今天全球股市行情」只给总体趋势）
        if (sWebResearch && ResearchGate.kindOf(utterance) == ResearchGate.Kind.RESEARCH) {
            return build(Label.DO_READ, 0.85, "5m-deep-research", core, false, true, "rule", s);
        }

        // 3a. D-3b：两地距离问句 → ASK（外部世界信息）；有动作动词（导航/打车）的不在这里
        if (actCount == 0 && DISTANCE_Q.matcher(q).find()) {
            return build(Label.ASK, 0.85, "3-distance", core, false, false, "rule", s);
        }
        // 3. ASK：方法/原因/定义 —— 即使含设备词、即使有礼貌框架（"能告诉我怎么设置闹钟吗"）
        if (containsAny(q, HOWTO_WHY) && !isHowToWithActTail(q)) {
            return build(Label.ASK, 0.9, "3-howto-why", core, false, false, "rule", s);
        }
        if (containsAny(q, ASK_VERBS) && !isMessageWriting(q) && !isMessageSend(q)) {
            return build(Label.ASK, 0.9, "3-ask-verb", core, false, false, "rule", s);
        }
        if (q.matches(ARITH_RE)) {
            return build(Label.ASK, 0.9, "3-arith", core, false, false, "rule", s);
        }
        if (containsAny(q, WORLD_INFO) && !startsWithLaunch(core)) {
            return build(Label.ASK, 0.85, "3-world-info", core, false, false, "rule", s);
        }

        // 4. CLARIFY：动作 + 需要槽位的对象，但句中没有任何数值/时间/方向
        //   ★ int/1102 审计（用户点名的小毛病）：「删除所有闹钟」「关掉全部闹钟」目标就是全部，L0 已接住（alarm.cancel.all /
        //   alarm.toggle.all 无必填参数）或句中有「所有 / 全部 / 全…」→ 不追问时间
        if (act && containsAny(core, SLOT_OBJECTS) && !hasNumberOrTime(core) && s.l0Sign == null && !BATCH_ALL.matcher(core).find()) {
            return build(Label.CLARIFY, 0.85, "4-missing-slot", core, false, false, "rule", s);
        }

        // 5. DO_READ：读取谓词 + 设备/个人对象，且没有祈使动作（"打开"等）
        boolean deviceObj = containsAny(core, DEVICE_OBJECTS) || containsAny(q, DEVICE_OBJECTS);
        boolean readPred = containsAny(q, READ_PREDICATES);
        if (deviceObj && readPred && !hasImperativeAct(q, core)) {
            return build(Label.DO_READ, 0.85, "5-device-read", peel(core), false, false, "rule", s);
        }

        // 6. DO_ACT：祈使动作
        if (act) {
            return build(Label.DO_ACT, polite ? 0.85 : 0.9, "6-act", core, multi, goal, "rule", s);
        }

        // 6b. 设备量 + 比较式（"声音大一点"、"亮度高点"）：隐含调节动作
        if (deviceObj && containsAny(core, ADJUST_TAILS)) {
            return build(Label.DO_ACT, 0.8, "6-adjust", core, false, false, "rule", s);
        }

        // 7. 光名词 / 抱怨 / 意愿
        if (equalsAny(core, TOGGLE_NOUNS)) {
            return build(Label.DO_ACT, 0.75, "7-toggle-noun", core, false, false, "rule", s);
        }
        if (containsAny(q, COMPLAINTS)) {
            return build(Label.DO_ACT, 0.8, "7-complaint", core, false, false, "rule", s);
        }
        if (equalsAny(stripLeaders(core, ADJUST_LEADERS), IMPLICIT_ADJUST)) {
            return build(Label.DO_ACT, 0.8, "7-implicit-adjust", core, false, false, "rule", s);
        }
        if (containsAny(q, DESIRE)) {
            return build(Label.DO_ACT, 0.8, "7-desire", core, false, false, "rule", s);
        }

        // 7b. 只有设备对象 + 疑问语气（"蓝牙呢"、"屏幕上啥"）→ 读
        if (deviceObj && containsAny(q, QUESTION_TAILS)) {
            return build(Label.DO_READ, 0.65, "7-device-question", peel(core), false, false, "rule", s);
        }

        // 8. 外部信号兜底：L0 命中了（规则层没认出来的说法）
        if (s.l0Sign != null) {
            Label l = isReadOnlySign(s.l0Sign) ? Label.DO_READ : Label.DO_ACT;
            return build(l, 0.7, "8-l0-signal", core, false, false, "rule", s);
        }

        // 9. 弱 ASK：带疑问语气、没有设备对象
        if (containsAny(q, QUESTION_TAILS) && !deviceObj) {
            return build(Label.ASK, 0.6, "9-question-tone", core, false, false, "rule", s);
        }

        return build(Label.AMBIGUOUS, 0.3, "10-unsure", core, false, false, "rule", s);
    }

    /** D-3b：去掉句首的程度/重复副词（「再」「稍微」），只用于省略对象的比较式。 */
    static String stripLeaders(String core, String[] leaders) {
        for (String l : leaders) {
            if (core.startsWith(l) && core.length() > l.length()) return core.substring(l.length());
        }
        return core;
    }

    /**
     * [diag] 这句话是不是「症状描述」（纯函数；UnifiedRouter / 面板挂钩与分诊表共用这一个结论）。
     * @return null = 不是
     */
    public static com.onesay.assistant.v2.diag.DiagMatcher.Match diagnoseMatch(String utterance) {
        String q = normalize(utterance);
        if (q.length() == 0) return null;
        String core = stripFrames(q);
        return diagnoseMatch(q, core, countActs(core));
    }

    /** ★ backlog 6b：应用故障抱怨（一打开就闪退 / 老是崩溃 / 怎么卡死了），且没有祈使指令。纯函数。 */
    static final java.util.regex.Pattern APP_CRASH = java.util.regex.Pattern.compile(
            "(一打开就?|打开就|一开就|一点开?就|点开就|一进去?就|进去就|老是|总是|经常|一直|动不动就?|怎么|又)(会|要)?(闪退|崩溃|崩掉|崩了|卡死|白屏|自动退出)"
                    + "|(闪退|崩溃)(了)?(怎么办|怎么回事|咋办|咋回事|是什么原因|什么原因)");
    static final java.util.regex.Pattern APP_CRASH_IMPERATIVE = java.util.regex.Pattern.compile(
            "帮我|给我|替我|请|麻烦|把|清理|清除|清一下|清空|重启|卸载|重装|强行停止|强制停止|然后|再");

    public static boolean isAppCrashComplaint(String normalized) {
        return normalized != null && APP_CRASH.matcher(normalized).find() && !APP_CRASH_IMPERATIVE.matcher(normalized).find();
    }

    /** ★ arch/agent-loop：调研规则开关（UnifiedRouter 按 LoopConfig.researchInLoop 设置；默认开）。 */
    static volatile boolean sWebResearch = true;

    public static void setWebResearch(boolean on) {
        sWebResearch = on;
    }

    /** 强信号：明说要调研 / 查资料 / 上网查 / 对比评测。 */
    private static final java.util.regex.Pattern WEB_RESEARCH_STRONG = java.util.regex.Pattern.compile(
            "调研|研究一下|研究研究|帮我研究|查资料|查查资料|找资料|搜集资料|收集资料|找找资料|整理一下资料|做个?对比|对比一下|比较一下|横向对比|横评|测评对比|"
                    + "去网上(?:查|找|看|搜)|上网(?:查|找|搜)|网上(?:查|找找|搜搜|搜一下|查一下)|帮我(?:在)?网上|搜一下资料|查一下资料|查查看(?:网上|资料)");
    /** 弱信号：「查一下 / 帮我查 / 查查」。 */
    private static final java.util.regex.Pattern WEB_RESEARCH_WEAK = java.util.regex.Pattern.compile(
            "(?:帮我|给我|替我)?(?:查一下|查查|查一查|帮我查|查下|了解一下|打听一下)");
    /** 弱信号的话题词（评测 / 口碑 / 攻略 / 哪个好 / 官网 / 怎么样）—— 有它才把「查一下」当调研。 */
    private static final java.util.regex.Pattern WEB_RESEARCH_TOPIC = java.util.regex.Pattern.compile(
            "评测|测评|口碑|评价|攻略|教程|哪个好|哪款|哪家|推荐|怎么样|好不好|值不值|参数|配置|官网|资料|信息|介绍|历史|背景|政策|规定|最新进展|发布会|开源|论文|"
                    + "价格区间|多少钱一|优缺点|区别|对比|排行|榜单");
    /** 调研句里出现就不算调研的动作（不是「搜 / 查」本身）。 */
    private static final String[] WEB_RESEARCH_BLOCK_ACTS = {
            "打开", "关掉", "关闭", "设置", "发送", "发给", "打电话", "打给", "下载", "安装", "卸载", "删除", "截图", "播放", "导航", "打车", "提醒",
    };

    /** @return null = 不是调研句；"strong" / "weak-strong"（弱信号 + 话题） */
    static String webResearchRule(String q, String core) {
        if (q == null) return null;
        for (String a : WEB_RESEARCH_BLOCK_ACTS) if (q.contains(a)) return null;
        if (WEB_RESEARCH_STRONG.matcher(q).find()) return "strong";
        if (!WEB_RESEARCH_WEAK.matcher(q).find()) return null;
        if (containsAny(q, DEVICE_OBJECTS) || containsAny(q, WORLD_INFO)) return null;
        if (!WEB_RESEARCH_TOPIC.matcher(q).find()) return null;
        return "weak-strong";
    }

    private static com.onesay.assistant.v2.diag.DiagMatcher.Match diagnoseMatch(String q, String core, int actCount) {
        return com.onesay.assistant.v2.diag.DiagMatcher.match(q, core, actCount > 0);
    }

    /**
     * ★ arch/island-progress（C 路）：进 AgentLoop 的请求要不要<b>一开始</b>就作为「任务」上灵动岛。
     * <ul>
     *   <li>MIXED（做 + 问 / 条件式）、多步（≥2 个动作 / 时序词）→ true：照旧立即收进岛；</li>
     *   <li>其余（单步 DO，含「下载 / 搜一下」这类达成型单步、L0/L1 没接住的单步）→ false：<b>不包成任务</b>，
     *       留在面板上；只有真的变成多步（GUI 操作 / 子目标 ≥ 2 / 第 2 次工具调用 / 中途问人）才上岛
     *       （见 IslandModel#taskStarted(long, String, boolean)）。</li>
     * </ul>
     * 只影响岛的呈现，不改 {@link Decision#useAgentLoop}（是否进循环）。
     */
    public static boolean islandUpFront(Decision d) {
        if (d == null) return false;
        return d.label == Label.MIXED || d.multiStep || isResearch(d);
    }

    /**
     * ★ 11-04（用户 01:06「上网去查查现在二手摩托车的市场价格」卡在面板上一分多钟才出结果）：调研类（5w 上网查 / 5m 行情·二手价·对比）
     * 要多轮搜索 + 读网页，和跑腿一样一开始就收进灵动岛后台进行，面板不干等。
     */
    public static boolean isResearch(Decision d) {
        return d != null && d.rule != null && (d.rule.startsWith("5w-web-research") || d.rule.equals("5m-deep-research"));
    }

    /**
     * ★ fix/triage-fast：分诊第 0 层（{@link FastPathTriage}）命中 —— 快路径模板（单步）或零 LLM 多意图计划。
     * 永不进 AgentLoop；只读签名记 DO_READ，其余 DO_ACT；计划记 multiStep（只影响日志 / 呈现）。
     */
    public static Decision fastPath(String utterance, String sign, boolean plan) {
        String q = normalize(utterance);
        Label l = (!plan && isReadOnlySign(sign)) ? Label.DO_READ : Label.DO_ACT;
        return new Decision(l, false, 0.95, plan ? "0c-compound" : "0f-fast:" + sign, stripFrames(q), plan, false, "rule");
    }

    /** ★ int/1102：这个判定是否来自分诊第 0 层（{@link #fastPath}）。 */
    public static boolean isFastPath(Decision d) {
        return d != null && d.rule != null && (d.rule.startsWith("0f-fast:") || d.rule.equals("0c-compound"));
    }

    /** 统一按标签推导 useAgentLoop（见类注释的约定）。 */
    static Decision build(Label l, double conf, String rule, String core,
                          boolean multi, boolean goal, String by, Signals s) {
        boolean loop;
        switch (l) {
            case MIXED:
                loop = true;
                break;
            case DO_ACT:
            case DO_READ:
                if (multi || goal) {
                    loop = true;
                } else {
                    // 单步：本地层（L0 / L1 缓存）接得住就不进循环
                    loop = !(s.l0Sign != null || s.cacheHit || s.askLocal);
                }
                break;
            default:   // ASK / CLARIFY / AMBIGUOUS / DIAGNOSE
                loop = false;
        }
        return new Decision(l, loop, conf, rule, core, multi, goal, by);
    }

    // =====================================================================
    // LLM 兜底（只给 AMBIGUOUS 用）：提示词 + 结果解析（纯函数，可测）
    // =====================================================================

    /** 分类提示词（要求严格 JSON，温度 0，max_tokens≈40）。 */
    public static String llmPrompt() {
        return "你是手机语音助手的分诊器。把用户一句话分成一类，只输出 JSON，不要任何解释：\n"
                + "{\"label\":\"ASK|DO_READ|DO_ACT|MIXED|CLARIFY\",\"confidence\":0到1}\n"
                + "ASK=知识/闲聊/方法/创作/天气新闻等外部信息；DO_READ=读取本机状态或个人数据（电量、闹钟、邮件、屏幕）；"
                + "DO_ACT=让手机做事（打开、设置、发送、创建）；MIXED=既要做又要问或带条件；CLARIFY=要做事但缺必要参数。";
    }

    /** LLM 结论的最低采信置信度。 */
    public static final double LLM_MIN_CONFIDENCE = 0.75;

    /**
     * 解析 LLM 分类结果。不合法 / 置信度不足 → 返回 null（调用方回落到规则层结论）。
     */
    public static Label parseLlmLabel(String content, double[] confidenceOut) {
        if (content == null) return null;
        String s = content.trim();
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            JSONObject o = new JSONObject(s.substring(a, b + 1));
            String lab = o.optString("label", "").trim().toUpperCase(Locale.ROOT);
            double conf = o.optDouble("confidence", 0.0);
            if (confidenceOut != null && confidenceOut.length > 0) confidenceOut[0] = conf;
            Label l;
            try {
                l = Label.valueOf(lab);
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (l == Label.AMBIGUOUS) return null;
            if (l == Label.DIAGNOSE) return null;   // [diag] 只由规则层判（提示词里也没有这一类）
            if (conf < LLM_MIN_CONFIDENCE) return null;
            return l;
        } catch (Throwable t) {
            return null;
        }
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /** 归一化：小写、去空白与标点、剥唤醒词残留。 */
    private static final java.util.regex.Pattern NEGATED_CMD = java.util.regex.Pattern.compile(
            "^(?:先|千万|可|你|请|暂时)?(?:别|不要|不用|甭|不许|不准|不必)(?:再|帮我|给我|去)?(?:把.{1,8}?)?"
            + "(?:打开|开启|开|关闭|关掉|关|删除|删掉|删|静音|发|打给|打电话|拨|调大|调小|调|设|定|提醒|叫|播放|放|拍|截|锁|重启|卸载|安装|下载|买|付|转账|启动|退出)");

    /** ★ 11-04 F：否定式命令（叫我别做某事）。以「了」收尾的视为取消（「不要提醒我了」），不算。 */
    static boolean isNegatedCommand(String q) {
        if (q == null) return false;
        String t = q.replaceAll("[。！!，,？?\\s]+$", "");
        if (t.endsWith("了") || t.endsWith("了吧")) return false;
        if (com.onesay.assistant.v2.s3.S3Parser.isStopRemind(t)) return false;   // ★ AL-07：「别提醒我」「不用再提醒」= 明确取消，不是否定式命令
        return NEGATED_CMD.matcher(t).find();
    }

    public static String normalize(String s) {
        if (s == null) return "";
        String q = s.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！、：；“”‘’《》（）【】…·～~]", "");
        // 保留问号语义：统一成中文问号以外的形式已被去掉，这里用末尾"？"信号由 QUESTION_TAILS 的"吗/呢"承担
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String w : WAKE_WORDS) {
                if (q.startsWith(w) && q.length() > w.length()) {
                    q = q.substring(w.length());
                    changed = true;
                } else if (q.equals(w)) {
                    return "";
                }
            }
        }
        return q;
    }

    /** 剥礼貌框架（前后缀，可重复）。 */
    static String stripFrames(String q) {
        String cur = q;
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 6) {
            changed = false;
            for (String p : POLITE_PREFIX) {
                if (cur.startsWith(p) && cur.length() > p.length() + 1) {
                    cur = cur.substring(p.length());
                    changed = true;
                    break;
                }
            }
            for (String suf : POLITE_SUFFIX) {
                if (cur.endsWith(suf) && cur.length() > suf.length() + 1) {
                    cur = cur.substring(0, cur.length() - suf.length());
                    changed = true;
                    break;
                }
            }
        }
        if (cur.startsWith("把") && cur.length() > 2) cur = cur.substring(1);
        return cur;
    }

    /** 再剥一层"查看"框架（给 DO_READ 的 core 用）。 */
    static String peel(String core) {
        for (String p : PEEK_PREFIX) {
            if (core.startsWith(p) && core.length() > p.length() + 1) {
                return core.substring(p.length());
            }
        }
        return core;
    }

    private static boolean hasPoliteFrame(String q) {
        return q.startsWith("能") || q.startsWith("可以") || q.startsWith("你能") || q.endsWith("吗");
    }

    static int countActs(String q) {
        // 按位置扫描，避免"调高"同时命中"调高"与"调"重复计数：
        // 每个位置取最长命中，然后跳过它。
        int count = 0;
        int i = 0;
        while (i < q.length()) {
            int best = 0;
            for (String v : ACT_VERBS) {
                if (v.length() > best && q.startsWith(v, i)) best = v.length();
            }
            if (best > 0) {
                if (!isFalseAct(q, i, best)) count++;
                i += best;
            } else {
                i++;
            }
        }
        return count;
    }

    /** 动词误报排除：数字/时间搭配里的"定"(一定)、"设"(设备)、"叫我"(叫我什么) 等。 */
    private static boolean isFalseAct(String q, int i, int len) {
        String v = q.substring(i, i + len);
        String after = i + len < q.length() ? q.substring(i + len) : "";
        String before = q.substring(0, i);
        if (v.equals("定") && (endsWithAny(before, "一", "确", "肯", "决", "固", "稳", "规", "指", "约", "否", "鉴")
                || after.startsWith("义") || after.startsWith("位") || after.startsWith("律"))) return true;
        if (v.equals("设") && (after.startsWith("备") || after.startsWith("计") || before.endsWith("假")
                || before.endsWith("建"))) return true;
        if (v.equals("设置") && after.length() == 0) return true;                       // "闹钟在哪里设置"
        if (v.equals("叫我") && (after.startsWith("什么") || after.startsWith("啥"))) return true;
        if (v.equals("调") && (after.startsWith("查") || after.startsWith("用"))) return true;
        if (v.equals("搜") && after.length() == 0) return true;
        if (v.equals("提醒") && after.startsWith("事项")) return true;
        if (v.equals("草稿") && before.length() == 0) return false;
        return false;
    }

    /** "打开蓝牙和wifi"：和/跟/还有 两侧都是动作或可切换对象。 */
    private static boolean isAndJoinedActs(String q) {
        String[] joins = { "和", "跟", "还有", "以及" };
        for (String j : joins) {
            int k = q.indexOf(j);
            if (k <= 0 || k + j.length() >= q.length()) continue;
            String left = q.substring(0, k);
            String right = q.substring(k + j.length());
            boolean leftAct = countActs(left) > 0;
            boolean rightObj = countActs(right) > 0 || containsAny(right, TOGGLE_NOUNS);
            boolean leftObj = containsAny(left, TOGGLE_NOUNS);
            if (leftAct && leftObj && rightObj) return true;
        }
        return false;
    }

    /** "怎么设置闹钟"是方法问句；但"怎么还不打开"之类极少，这里只排除"怎么"后紧跟祈使+宾语且以"吧"结尾的命令口吻。 */
    private static boolean isHowToWithActTail(String q) {
        return false;
    }

    /** "写一封草稿邮件/写个短信"是动作，不是创作。 */
    /**
     * ★ R13 真机：「给10086发短信说你好」被 ASK_VERBS 里的「你好」截成问答。发消息句（发 / 回 + 短信 / 信息 / 消息 / 微信）里的
     * 「你好 / 谢谢 / 晚安」是<b>消息内容</b>，不是在跟助手打招呼 —— 交给动作通道（sms.send 有确认卡）。
     */
    static boolean isMessageSend(String q) {
        if (q == null) return false;
        boolean verb = q.contains("发短信") || q.contains("发信息") || q.contains("发消息") || q.contains("发微信")
                || q.contains("回短信") || q.contains("回信息") || q.contains("回消息") || q.contains("发个短信")
                || q.contains("发条短信") || q.contains("发一条短信") || q.contains("发个信息") || q.contains("发条信息");
        return verb;
    }

    private static boolean isMessageWriting(String q) {
        return (q.contains("写") && (q.contains("邮件") || q.contains("短信") || q.contains("笔记")
                || q.contains("备忘") || q.contains("草稿")));
    }

    private static boolean startsWithLaunch(String core) {
        return core.startsWith("打开") || core.startsWith("开启") || core.startsWith("启动") || core.startsWith("进入");
    }

    /** 祈使动作（会改变状态）——读取句里不应出现。"看/查/读"不算。 */
    private static boolean hasImperativeAct(String q, String core) {
        int n = countActs(core);
        if (n == 0) return false;
        // "提醒"在"有什么提醒"里是名词 —— 读谓词优先
        if (core.contains("有哪些") || core.contains("有什么") || core.contains("有没有")) return false;
        // 祈使口吻（"可以把wifi关了吗" / "你能打开蓝牙吗"）：仍是动作
        if (q.contains("把") || hasPoliteFrame(q) && !q.endsWith("了吗") && !q.endsWith("着吗")) {
            if (q.contains("把")) return true;
            if (q.startsWith("能") || q.startsWith("可以") || q.startsWith("你能") || q.startsWith("帮")
                    || q.startsWith("麻烦") || q.startsWith("请")) return true;
        }
        // 状态追问（"蓝牙打开了吗" / "wifi连接了没"）：读
        String[] stateAsk = { "了吗", "着吗", "了没", "着没", "没有开", "开没开", "关没关", "了没有" };
        if (containsAny(q, stateAsk)) return false;
        return true;
    }

    private static boolean endsWithAny(String s, String... tails) {
        for (String t : tails) if (s.endsWith(t)) return true;
        return false;
    }

    private static boolean hasNumberOrTime(String q) {
        if (q.matches(".*[0-9].*")) return true;
        if (q.matches(".*[零一二两三四五六七八九十百半]+(点|分|秒|小时|个小时|分钟).*")) return true;
        String[] dir = { "早上", "晚上", "中午", "下午", "凌晨", "明早", "最高", "最低", "最大", "最小", "一半" };
        return containsAny(q, dir);
    }

    static boolean isReadOnlySign(String sign) {
        if (sign == null) return false;
        return sign.endsWith(".state") || sign.endsWith(".get") || sign.endsWith(".check")
                || sign.endsWith(".query") || sign.endsWith(".list") || sign.equals("time.now")
                || sign.equals("date.today") || sign.startsWith("battery.level")
                || sign.equals("vision.screen.understand") || sign.equals("mail.inbox.latest");
    }

    static boolean containsAny(String q, String[] words) {
        if (q == null) return false;
        for (String w : words) {
            if (q.indexOf(w) >= 0) return true;
        }
        return false;
    }

    private static boolean equalsAny(String q, String[] words) {
        for (String w : words) {
            if (q.equals(w)) return true;
        }
        return false;
    }
}
