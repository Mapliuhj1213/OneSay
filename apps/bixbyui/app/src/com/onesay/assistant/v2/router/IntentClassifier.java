package com.onesay.assistant.v2.router;

import java.util.List;

/**
 * 意图分类器：在路由之前先判定「这条输入到底要干什么」。
 *
 * 设计动机（2026-10-02 用户诉求）：
 * 旧实现把 Ask / L0规则 / L1缓存 / L2大模型 串成一条无差别的瀑布，导致：
 *   1. 本地 Ask 规则会抢答本该联网查的问题（天气被写死的假答案拦截）；
 *   2. 问答类问题也走「能力编译」通道，模型容易被误导去编造系统动作；
 *   3. 缓存对 Ask 与 Agent 用同一套策略，时效性问题（几点、天气）被错误缓存。
 *
 * 因此先把输入分成三类，后续每一层都按 kind 走不同策略：
 *
 *   ASK   —— 求知/问答/闲聊/翻译/解释（**永远不会触发系统动作**，最安全）
 *   AGENT —— 明确要求操作手机硬件或设置
 *   MIXED —— 一条输入里既有动作又有提问（"调到最低，顺便几点了"）
 *
 * 判定原则：**宁可为 ASK，不可误判为 AGENT**。
 * 因为 ASK 走只读链路，判错最多是答非所问；AGENT 判错会真的改系统状态。
 */
public final class IntentClassifier {

    private IntentClassifier() {}

    public enum Kind {
        ASK,
        AGENT,
        MIXED
    }

    /** 手机硬件 / 系统设置对象词。与命令动词同时出现才构成 AGENT 信号。 */
    private static final String[] DEVICE_OBJECTS = {
            "蓝牙", "wifi", "无线", "热点", "亮度", "音量", "静音", "免打扰", "振动",
            "飞行模式", "定位", "gps", "流量", "数据网络", "移动数据", "手电筒", "闪光灯",
            "屏幕", "息屏", "自动旋转", "旋转", "护眼", "深色", "亮度", "铃声",
            "闹钟", "计时器", "倒计时", "提醒", "日程", "备忘录", "便签", "笔记",
            "相机", "拍照", "录像", "截图", "录屏", "电话", "短信", "微信", "qq",
            "音乐", "播放", "歌曲", "视频", "地图", "导航", "浏览器", "搜索",
            "应用", "软件", "app", "软件包", "设置", "系统", "锁屏", "重启", "关机",
            "充电", "电池", "省电", "存储", "内存", "缓存"
    };

    /** 命令动词（祈使/使役）。 */
    private static final String[] CMD_VERBS = {
            "打开", "开启", "启动", "关闭", "关掉", "关上", "禁用", "停用", "启用",
            "设置", "设为", "调到", "调高", "调低", "调大", "调小", "增加", "减小",
            "提高", "降低", "切换到", "进入", "退出", "返回", "清理", "清除", "删除",
            "播放", "暂停", "停止", "下一首", "上一首", "打电话", "拨打", "发短信", "发消息",
            "发送", "写", "记一下", "记住", "提醒我", "定个", "设个", "导航到", "去",
            "搜一下", "搜索", "查一下", "截屏", "截图", "录屏", "帮我", "给我", "把",
            // **应用商店下载 / 安装**类。
            //   实测踩过：「在应用商店下载微信」分类=ASK → 走 LLM 自由问答，
            //   模型回了一段"打开商店搜微信点安装"的文字教程，还自陈"我无法直接操作"。
            //   根因：CMD_VERBS 里没有"下载"，只有"安装"（且在 ACTION_VERBS 不在 CMD_VERBS），
            //   looksLikeCommand 判 false → 分类器直接判 ASK。
            "下载", "下载安装", "装一个", "新建", "创建", "保存"
    };

    /**
     * 「能独立成事」的动作动词 —— 用来数一句话里究竟挂了**几件事**。
     *
     * <p>比 {@link #CMD_VERBS} 窄得多，收词时遵守两条纪律：
     * <ol>
     *   <li><b>不收助动词</b>："把 / 给 / 去 / 帮我 / 给我"几乎出现在每句祈使句里，
     *       收进来会把「帮我把亮度调低」这种单动作句全判成复合句。</li>
     *   <li><b>不收会撞应用名的词</b>："设置"（打开设置）、"播放"（音乐播放器）、
     *       "翻译"（翻译App）、"导航"（高德导航）—— 这些词既是动词又是应用名，
     *       收进来会让「打开音乐播放器」被数成两个动作。</li>
     * </ol>
     *
     * <p>它解决的是「打开浏览器搜索必应」这类**动宾并列**：整句没有一个时序词
     * （"然后/接着/后"），旧判据完全看不见，于是被判成单步 AGENT，
     * 贪婪模板吃掉前半句后，后半句再没有机会被执行。
     */
    private static final String[] ACTION_VERBS = {
            "打开", "开启", "启动", "关闭", "关掉", "禁用", "启用",
            "搜索", "搜一下", "搜一搜", "查一下", "查询", "检索",
            "截图", "截屏", "录屏", "拍照", "照相", "录像",
            "发送", "发消息", "发短信", "打电话", "拨打",
            "记一下", "提醒我", "定个", "设个", "设为", "调到",
            "清理", "卸载", "安装",
            // 下载 / 内容生产类（与 CMD_VERBS 同步补，保持两表语义一致）。
            "下载", "新建", "创建", "保存", "输入"
    };

    /** 疑问 / 求知标记。 */
    private static final String[] QUESTION_MARKS = {
            "吗", "呢", "什么", "为什么", "怎么回事", "怎么", "如何", "多少", "几",
            "谁", "哪里", "哪儿", "哪个", "是不是", "能不能", "可不可以", "可以吗",
            "有没有", "行不行", "好不好", "对不对", "介绍", "解释", "讲讲", "说说",
            "翻译", "什么意思", "区别", "是什么", "怎么样", "怎样", "啥", "请问",
            "请教", "道理", "原理", "原因", "作用", "好处", "坏处", "怎么办"
    };

    /** 并列连接词：一条输入里挂多件事的强信号。 */
    private static final String[] CONJUNCTIONS = {
            "顺便", "同时", "然后", "接着", "另外", "并且", "以及", "还有", "并且",
            "和", "跟", "再帮", "再帮我把", "再顺便", "顺带", "另外帮"
    };

    /**
     * 时序连接词：显式表示"做完 A 再做 B"。
     * 与 {@link #CONJUNCTIONS} 分开，是因为它们的误伤面不同 ——
     * 这里必须整体出现（"然后"/"接着"…），不能拿单字"后"去 contains，
     * 单字交给 {@link #looksLikeMultiAction} 的正则按结构判定。
     */
    private static final String[] SEQUENCE_WORDS = {
            "然后", "接着", "之后", "以后", "随后", "再帮", "然后再", "再顺便",
            "顺便再", "再给我", "再帮我"
    };

    /** "A后B" / "A完B" 的时序结构：前后各至少 2 个非空白字符 */
    private static final String SEQUENCE_PATTERN = ".*\\S{2,}(完后|完之后|好了后|好之后|后|完)\\S{2,}.*";

    /**
     * 时效性词：含这些词的问答结果**绝不允许落缓存**。
     * 否则用户明天问「今天天气」会拿到今天缓存的答案。
     */
    private static final String[] VOLATILE_WORDS = {
            "今天", "现在", "当前", "此刻", "几点", "时间", "天气", "气温", "温度",
            "新闻", "股价", "股票", "汇率", "油价", "限行", "路况", "最新", "最近",
            "刚才", "明天", "昨天", "本周", "这周", "本月", "这个月", "今年",
            "第几", "还剩", "剩余", "余额", "电量", "内存", "存储空间", "跑了多久",
            // **行情/报价类**词汇。
            //   起因实测：问「比特币售价多少」被缓存了 30 天 —— 这句话一个原词都没命中，
            //   于是被当成"稳定知识"落库，之后同一个问题永远返回第一次那条答案。
            //   反过来说，只要答案里有"随行情变动的数字"，这条缓存早晚会骗人。
            //   注意收词纪律：收**报价措辞**（售价/多少钱/行情…）而不是**资产名**
            //   （比特币/黄金…）——「比特币是什么」是稳定知识，缓存它没问题；
            //   「比特币多少钱」才有实时性。
            //   （宁可多不收缓存：漏判只是"慢一点"，误判是"答错还改不掉"。）
            "售价", "价格", "报价", "多少钱", "行情", "市值", "涨跌", "涨幅", "跌幅",
            "币价", "金价", "银价", "油价多少", "一桶", "一克"
    };

    /**
     * 执行分类。
     *
     * @param query 用户输入（已剥离唤醒词）
     * @param l0Hit 该输入是否**真实命中**了 L0 离线规则（这是 AGENT 的铁证，由调用方传入）
     */
    public static Kind classify(String query, boolean l0Hit) {
        return classify(query, l0Hit, false);
    }

    /**
     * 执行分类（带"多动作"信号）。
     *
     * @param multiAction 调用方判定的"这句话里挂了不止一件事"信号
     *                    （见 {@link #looksLikeMultiAction}）。
     *                    之所以由调用方传入而不是在这里自己算：分类器拿不到
     *                    L0 命中的**槽位内容**，而贪婪槽位恰恰是多动作误判的源头。
     */
    public static Kind classify(String query, boolean l0Hit, boolean multiAction) {
        if (query == null || query.trim().length() == 0) return Kind.ASK;

        String q = L0RuleRouter.normalizeUtterance(query);
        if (q.length() == 0) return Kind.ASK;

        boolean question = looksLikeQuestion(q);
        boolean command = looksLikeCommand(q);
        // **设备宾语不能只靠手写词表认**。
        //
        //   原实现只查本文件的 {@link #DEVICE_OBJECTS} 手写词表。后果（实测）：
        //     「看看收件箱最新一封」→ 动作语素 QUERY_INFO（"看看"）→ 不在 isDeviceAction 之列
        //     → 手写表里也没有"收件箱/邮件" → actionable=false → 落 **ASK**
        //     → ASK 通道**不注入能力清单** → 模型只能答"我没法读取你的收件箱"。
        //     而此时 Top-K 第一名**明明就是 mail.inbox.latest**（say 模板"收件箱最新"精确命中 +40）。
        //     **工具就摆在桌上，却因为通道判错而没递给模型。**
        //
        //   项目早已定下纪律：目标词是开放集，必须**从能力库反向取**（{@link ActionLexicon#hasDeviceTarget}
        //   遍历能力的 summary/keywords）—— 新增一个能力就自动多一批可识别目标，零改代码。
        //   这里就是那个机制该被用上的地方（此前只在别处接入，分类器漏了）。
        //
        //   手写表保留为 OR 兜底（它覆盖一些不在能力摘要里的通用名词），只加不减，零回归风险。
        boolean hasDeviceObject = containsAny(q, DEVICE_OBJECTS)
                || ActionLexicon.hasDeviceTarget(q);
        // **设备宾语不该靠词表认**。
        //
        //   旧判据要求"命令动词 + DEVICE_OBJECTS 里的词"同时出现才算 AGENT，
        //   于是「商店里搜个支付宝」「给我装个高德地图」因为**宾语不在词表里**被判 ASK。
        //   但"支付宝""高德地图"这些是**应用名**——全世界应用几百万个，词表永远收不全。
        //
        //   机制化做法：换成两条不依赖宾语词表的证据 ——
        //     (a) 动作语素属于**明确作用于设备**的类别（INSTALL/LAUNCH/CREATE/CONFIGURE
        //         —— 这些动作在手机上"做"和"答"是两回事，用户只可能想让它动手）；
        //     (b) 句中有**容器词**（商店/浏览器/笔记…）—— 说明这是"在某界面里操作"。
        //   两者任一成立即可，宾语是什么完全不影响判定。
        ActionLexicon.Intent actIntent = ActionLexicon.classify(q);
        boolean deviceAction = isDeviceAction(actIntent);
        boolean hasContainer = ActionLexicon.container(q) != null;
        boolean actionable = hasDeviceObject || deviceAction || hasContainer;
        boolean hasConjunction = containsAny(q, CONJUNCTIONS);
        // **数动作词**。
        //   前两条（显式并列词 / 时序结构）都要求句子里有"然后""后"这类连接成分，
        //   而「打开浏览器搜索必应」一个连接词都没有 —— 但它是两件事，白纸黑字。
        //   一句话里出现 ≥2 个可独立成事的动作动词，就是复合句的硬证据。
        int actionCount = countActions(q);
        boolean multi = multiAction || looksLikeMultiAction(q) || hasConjunction
                || actionCount >= 2;

        // 1. L0 规则真实命中 —— 说明这句话确实能对上某个系统动作模板
        if (l0Hit) {
            // 例外一：整句还是明显的求知句（"打开蓝牙有什么用"），不能当纯动作执行
            if (question) return Kind.MIXED;
            // ★ 例外二（2026-10-02 修复）：整句其实是**复合任务**，L0 只吃掉了其中一段。
            //   典型："打开相机后截一张图" —— L0 的 app.launch 模板是贪婪的，
            //   会把整串吞成 app="相机后截一张图"（靠品类别名"相机"蒙混过关），
            //   于是被判成 AGENT 单步、直接执行，第二步永远没机会出现。
            //   判成 MIXED 后，这句话会走到 LLM 通道去拆步骤。
            if (multi) return Kind.MIXED;
            return Kind.AGENT;
        }

        // 2. 疑问句优先（安全侧）
        if (question) {
            // "帮我把亮度调到最低好吗" —— 动作请求裹在问句里，交给 LLM 拆成 动作+确认
            if (command && actionable) return Kind.MIXED;
            return Kind.ASK;
        }

        // 3. 命令动词 + 设备宾语，但 L0 没命中（说明是需要 LLM 才能编的复杂动作）
        if (command && actionable) {
            // ★ 复合句优先（2026-10-03）：L0 已经明确不接手，而这句话里挂着不止一件事，
            //   就必须走 LLM 的计划通道。少了这一句，「打开浏览器搜索必应」会被判成
            //   单步 AGENT → 走能力编译 → 只能编出一个动作 → 后半句照样丢。
            if (multi) return Kind.MIXED;
            return Kind.AGENT;
        }

        // 4. 并列连接 / 时序结构 + 命令动词 —— 多件事，交给 LLM 出计划
        if (multi && command) return Kind.MIXED;

        // 5. 兜底：当问答处理。判错只会答非所问，绝不会误动系统。
        return Kind.ASK;
    }

    /**
     * 是否是一条**复合指令**（一句话里挂了不止一件事）。
     *
     * <p>两条独立的证据链，命中任意一条即为真：
     * <ol>
     *   <li><b>显式时序/并列词</b>："然后"、"接着"、"之后"、"顺便"……</li>
     *   <li><b>{@code A后B} 结构</b>：整句带命令动词，且存在"X 后 Y"（X、Y 各≥2 字）。
     *       这条是为「打开相机**后**截一张图」补的 —— 它用的是单字"后"，
     *       而单字 contains 会误伤"关闭**后**台"这类词，所以只能按结构判。</li>
     * </ol>
     *
     * <p>代价与边界：判成 MIXED 只是让它**多走一趟 LLM**去拆步骤，
     * 不会绕过任何安全门禁 —— LLM 输出仍受能力清单约束，risk≥2 仍要确认卡。
     * 所以宁可宽一点，也不能让复合句被贪婪模板吞成单步。
     */
    public static boolean looksLikeMultiAction(String q) {
        if (q == null) return false;
        String s = L0RuleRouter.normalizeUtterance(q);
        if (s.length() < 4) return false;

        // **先抹掉相对时间短语**再判复合句。
        //
        //   事故：「40分钟后叫醒我」被判成 MIXED（"一句话里挂了两件事"）。
        //   根因是下面的"A后B"结构判据 —— "40分钟"（前）+ "后" + "叫醒我"（后）
        //   恰好满足"前后各 ≥2 字"的形式，于是被当成「先做A再做B」。
        //   但相对时间的"后"是**状语**（什么时候做），时序连接词的"后"是**连接词**
        //   （做完这个再做那个），两者形式相似、语义无关。
        //   不抹掉的话，「10分钟后提醒我吃药」「两小时后叫我起床」这类
        //   **单一动作**全会被拆成多步计划，白白多绕一趟 LLM。
        String stripped = TimeContext.stripRelative(s);
        if (stripped.length() >= 2) s = stripped;
        if (s.length() < 4) return false;

        for (String w : SEQUENCE_WORDS) {
            if (s.indexOf(w) >= 0) return true;
        }
        if (looksLikeCommand(s) && s.matches(SEQUENCE_PATTERN)) return true;
        return false;
    }

    /** 是否带疑问 / 求知特征 */
    public static boolean looksLikeQuestion(String q) {
        if (q == null) return false;
        if (q.endsWith("?") || q.endsWith("？")) return true;
        return containsAny(q, QUESTION_MARKS);
    }

    /** 是否带命令动词 */
    public static boolean looksLikeCommand(String q) {
        if (q == null) return false;
        if (containsAny(q, CMD_VERBS)) return true;
        // **语素引擎兜底**。
        //
        //   旧实现只认 {@link #CMD_VERBS} 里的精确词条，用户换一种说法就漏判 ——
        //   实测「把微信装上」「给我装个高德地图」「应用市场找一下美团」全部漏判成 ASK，
        //   根因就是词表里没有"装上/装个/找一下"这些**搭配**。
        //   这是用户点名的"定向拟合"：补一个词只能救一个例句，永远追不上。
        //
        //   现在改为：先查精确词表（快路径），未命中再问**动作语素引擎**——
        //   它按"语素 + 构词"识别，不依赖具体搭配，因此
        //   「装个/装上/装一下/弄个/搞个」都会被识为 INSTALL 类动作。
        //   这是结构性的，不是给例句开小灶。
        return ActionLexicon.classify(q) != ActionLexicon.Intent.NONE;
    }

    /**
     * 取该句的**动作语义类别**（语素引擎结果），供上层按类别路由。
     * 例：INSTALL → 商铺族；SEARCH → 搜索族；CREATE → 笔记族。
     */
    public static ActionLexicon.Intent actionIntent(String q) {
        if (q == null) return ActionLexicon.Intent.NONE;
        String n = L0RuleRouter.normalizeUtterance(q);
        return ActionLexicon.classify(n);
    }

    /** 是否含并列连接词（可能需要多步计划） */
    public static boolean hasConjunction(String q) {
        return containsAny(q, CONJUNCTIONS);
    }

    /**
     * 是否为时效性问题 —— 结果不得进缓存。
     * 例：「今天天气」「现在几点」「股价多少」「我还剩多少电量」。
     */
    public static boolean isTimeSensitive(String query) {
        if (query == null) return false;
        String q = L0RuleRouter.normalizeUtterance(query);
        if (q.length() == 0) return false;

        // **相对时间**请求同样是时效性的，而且更隐蔽。
        //
        //   事故（test_agent 探针实测）：「40分钟后叫醒我」被模型编译成
        //   clock.alarm.set{hour:0, minute:57} —— 这个绝对时刻**只在"当时"成立**。
        //   但旧词表（VOLATILE_WORDS）里没有"分钟/小时后"这类说法，
        //   于是这条调用被当成稳定技能落进 L1 缓存。后果：
        //     明天用户再在同一时刻说同一句话 → 命中缓存 → 原样设 00:57 的闹钟；
        //     而在别的时间说 → 同样设 00:57 → **闹钟时间全错**，且因为是
        //     "已验证缓存"，连模型都不会再过一遍。
        //   实测日志：首次编译 1488ms，二次复用 16ms 命中 L1 返回同一组 hour/minute。
        //
        //   所以判据放在这里（单一真源）：只要句中有相对时间表达式，一律不落缓存。
        //   两条缓存写入路径（L1SkillRouter.recordSkill / UnifiedRouter 的 ROUTE-6）
        //   都查这一个函数，不需要各写一套。
        if (TimeContext.parseRelative(q) != null) return true;

        return containsAny(q, VOLATILE_WORDS);
    }

    /**
     * 数一句话里出现了几个「可独立成事的动作动词」。
     *
     * <p>同一个词重复出现只按次数累计（"打开打开"这种重复口吃会算 2 次，
     * 但那本来就该交给 LLM 去重）。不做分词，纯扫描 —— 收词纪律见 {@link #ACTION_VERBS}，
     * 只收那些**不会出现在应用名里**的动词，所以不需要分词也不会误伤。
     *
     * @return 命中次数；0 表示这句话不含任何明确动作词
     */
    public static int countActions(String q) {
        if (q == null || q.length() == 0) return 0;
        int count = 0;
        for (String v : ACTION_VERBS) {
            int from = 0;
            while (true) {
                int i = q.indexOf(v, from);
                if (i < 0) break;
                count++;
                from = i + v.length();
            }
        }
        // 数值为 0 时用语素引擎补一次。
        //   旧实现靠 ACTION_VERBS 白名单数动作，漏了"下载个X"这类非常规搭配。
        //   语素引擎按类别去重计数，能识别出"打开…下载…"是两类动作（复合句证据）。
        if (count == 0) {
            List<ActionLexicon.Intent> intents = ActionLexicon.allIntents(q);
            for (ActionLexicon.Intent it : intents) {
                if (isDeviceAction(it) || it == ActionLexicon.Intent.SEARCH
                        || it == ActionLexicon.Intent.QUERY_INFO) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 该动作类别是否属于「明确作用于设备」——即在手机上"做"与"答"是两回事。
     *
     * <p>判据来自用户诉求的本质：INSTALL（装应用）、LAUNCH（开界面）、
     * CREATE（在本机产出实体：一条笔记）、CONFIGURE（改系统状态）——
     * 这四类用户**只可能想要"动手"**，不可能想要一段文字说明。
     *
     * <p>而 SEARCH / QUERY_INFO 是**模糊类**：既可"帮我在商店里搜"（动手），
     * 也可"告诉我这个词什么意思"（求知），所以不列入，交给上下文（容器/疑问词）判。
     */
    public static boolean isDeviceAction(ActionLexicon.Intent it) {
        return it == ActionLexicon.Intent.INSTALL
                || it == ActionLexicon.Intent.LAUNCH
                || it == ActionLexicon.Intent.CREATE
                || it == ActionLexicon.Intent.CONFIGURE;
    }

    private static boolean containsAny(String q, String[] words) {
        for (String w : words) {
            if (q.indexOf(w) >= 0) return true;
        }
        return false;
    }
}
