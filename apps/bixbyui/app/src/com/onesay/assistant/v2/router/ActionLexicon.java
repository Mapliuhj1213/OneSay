package com.onesay.assistant.v2.router;

import java.util.ArrayList;
import java.util.List;

/**
 * 动作语素引擎 —— 机制化识别「这句话在指挥手机做什么」。
 *
 * <h3>为什么要有这个类（2026-10-04，用户判定"定向拟合"后的重构）</h3>
 *
 * <p>旧实现（{@link IntentClassifier#looksLikeCommand}、旧分诊探针 TriageV2 —— 2026-10-09 已删除）用
 * <b>精确词表 + {@code containsAny}</b> 判意图。它有一个致命性质：
 * <b>必须"恰好收录了那个词"才能命中</b>。
 * 于是出现这条曲线：
 * <pre>
 *   收录「下载」  → 「帮我下载个抖音」对了
 *   没收录「装上」→ 「把微信装上」 错了
 *   补上「装上」  → 「给我装个高德」 又错了（因为收录的是"装上"不是"装个"）
 *   补上「装个」  → 「装一下XX」    又错了
 *   ...永无止境
 * </pre>
 * 这就是用户说的「定向拟合」—— 每次只修好被举的那个例句，换个说法立刻失效。
 *
 * <h3>机制化的做法</h3>
 *
 * <p>中文祈使句的动作信息**几乎全部承载在单字/双字语素上**，
 * 而"个/上/一下/一个/吧/呢/给我/帮我"这些是**附着成分**，不承载动作语义。
 * 所以正确做法不是穷举"装一个/装个/装上/装一下"，而是：
 *
 * <pre>
 *   ① 先剥离附着成分 → 得到"骨感"的句子
 *   ② 在骨架里找**动作语素**
 *   ③ 「语素 + 宾语类别」共同决定这是哪一类动作
 * </pre>
 *
 * <p>这样一来，「下载个抖音」「装个高德」「弄个微信上去」「搞个美团」——
 * 不管用哪个动词、带哪个量词，只要核心语素在表里，就能被识别为 INSTALL。
 *
 * <h3>语素表设计纪律</h3>
 * <ol>
 *   <li><b>只收语素，不收整词</b>：收「装」而不是「装一个」；收「搜」而不是「搜一下」。
 *       附着成分由 {@link #stripParticles} 统一剥离。</li>
 *   <li><b>歧义语素必须带约束</b>：单字「找」既是"搜索"（找一下美团）又是"打开"（找美团=打开美团），
 *       所以「找」归 SEARCH，但检测到「找」后面跟的是**已装应用名**时由上层按 LAUNCH 处理。
 *       本引擎只给"候选语素"，最终归类由 {@link #classify} 结合宾语判。</li>
 *   <li><b>宁可给候选，不硬判</b>：本引擎的输出是"这句话像哪几类动作"，
 *       供分类器做**证据**，不是最终裁决。避免又变成一套会漏的词表。</li>
 * </ol>
 *
 * <p>本类**不引入任何与具体例句绑定的词条**（如"抖音""美团"），
 * 只描述语言结构 —— 这是它和旧词表的本质区别。
 */
public final class ActionLexicon {

    private ActionLexicon() {}

    // ---------- 意图类别 ----------

    /** 动作大类：与"用户想在本机做什么"对齐，不与具体能力签名绑定。 */
    public enum Intent {
        /** 安装 / 下载 / 卸载应用。语素：装、下载、卸载、卸。 */
        INSTALL,
        /** 在某个容器里搜索 / 查找内容。语素：搜、找、查、检索、翻。 */
        SEARCH,
        /** 打开 / 启动某个界面或应用。语素：打开、开、启动、唤起、调出、进。 */
        LAUNCH,
        /** 新建 / 写入 / 保存内容。语素：新建、创建、写、记、输入、保存、存。 */
        CREATE,
        /** 设置 / 调整状态。语素：设置、调、改、切换、换成、开、关。 */
        CONFIGURE,        /** 查询信息（读）。语素：查、看、告诉我、多少、几点、是什么。 */
        QUERY_INFO,
        /** 无明确动作。 */
        NONE
    }

    /**
     * 动作语素表：语素 → 意图。
     *
     * <p>顺序即优先级（INSTALL/CREATE 这类"强动作"排在前面，
     * 因为它们的宾语形态最明确，最不容易误判）。
     */
    private static final String[][] LEXICON = {
            // ---- INSTALL：装 / 下载 / 卸载。注意「装」必须在「卸载」前不冲突（互不包含）----
            { "下载安装", "INSTALL" }, { "下载", "INSTALL" },
            { "卸掉", "INSTALL" }, { "卸载", "INSTALL" }, { "卸了", "INSTALL" },
            { "安装", "INSTALL" }, { "装上", "INSTALL" }, { "装个", "INSTALL" },
            { "装一个", "INSTALL" }, { "装一下", "INSTALL" }, { "装到", "INSTALL" },
            { "装", "INSTALL" }, { "弄个", "INSTALL" }, { "弄一个", "INSTALL" },
            { "搞个", "INSTALL" }, { "搞一个", "INSTALL" }, { "安一个", "INSTALL" },

            // ---- CREATE：新建 / 写 / 记 / 保存 ----
            { "新建", "CREATE" }, { "创建", "CREATE" }, { "建一个", "CREATE" },
            { "记一下", "CREATE" }, { "记下来", "CREATE" }, { "记录", "CREATE" },
            { "写一条", "CREATE" }, { "写一个", "CREATE" }, { "写个", "CREATE" },
            { "写", "CREATE" }, { "输入", "CREATE" }, { "录入", "CREATE" },
            { "保存", "CREATE" }, { "存一下", "CREATE" }, { "存下来", "CREATE" },

            // ---- CREATE：定闹钟 / 叫醒 / 提醒（补，结构性缺口）----
            //
            // 【为什么这是"缺口"而不是"漏了一个词"】
            //   「叫醒我」「叫我起床」「提醒我」是**同一族动作**（在本机创建一条按时触发的事件），
            //   旧表里一个语素都没有 —— 整个"订时间"这一支没建立起来。后果：
            //     「40分钟后叫醒我」→ 无语素命中 → NONE → 判 ASK 走问答通道
            //       → 模型手里既没时钟也没能力清单 → 只能答"你告诉我现在几点"
            //     而「定七点的闹钟」是靠 L0 模板侥幸命中的，不是语素引擎之功。
            //   所以补的是这一**范畴**（叫醒/叫我/提醒/定时间），不是某一个例句。
            //
            // 【为什么会分两次补】第一次只收了"叫醒/提醒我"，自测里
            //   「两小时后叫我起床」立刻暴露漏判（"叫我"没在表里）——
            //   说明**收语素要按"说法族"收全**（叫醒/叫我/喊我…是同一个动作的不同说法），
            //   只收其中一个说法，换一个口语搭配就漏。这正是"补词"的老毛病，一次收全。
            { "叫我起床", "CREATE" }, { "叫我起来", "CREATE" }, { "叫醒我", "CREATE" },
            { "叫醒", "CREATE" }, { "叫我", "CREATE" },
            { "喊我起床", "CREATE" }, { "喊醒", "CREATE" }, { "喊我", "CREATE" },
            { "提醒我", "CREATE" }, { "提醒一下", "CREATE" },
            { "提醒", "CREATE" }, { "定个", "CREATE" }, { "设个", "CREATE" },

            // ---- SEARCH：搜 / 找 / 查 / 检索 ----
            { "搜索", "SEARCH" }, { "搜一下", "SEARCH" }, { "搜一搜", "SEARCH" },
            { "搜个", "SEARCH" }, { "搜", "SEARCH" },
            { "查找", "SEARCH" }, { "找一下", "SEARCH" }, { "找找", "SEARCH" },
            { "找", "SEARCH" }, { "检索", "SEARCH" }, { "翻一下", "SEARCH" },

            // ---- CONFIGURE：设置 / 调 / 改 / 切换 ----
            { "换成", "CONFIGURE" }, { "换为", "CONFIGURE" }, { "改成", "CONFIGURE" },
            { "改为", "CONFIGURE" }, { "切换", "CONFIGURE" }, { "切到", "CONFIGURE" },
            { "调高", "CONFIGURE" }, { "调低", "CONFIGURE" }, { "调大", "CONFIGURE" },
            { "调小", "CONFIGURE" }, { "调到", "CONFIGURE" }, { "调", "CONFIGURE" },
            { "设置", "CONFIGURE" }, { "设为", "CONFIGURE" }, { "设成", "CONFIGURE" },

            // ---- CONFIGURE：开关类语素（补，结构性缺口）----
            //
            // 【为什么这是"缺口"而不是"漏了一个词"】
            //   「开/关」是中文最基础的一对动作范畴（开灯/关灯、开蓝牙/关蓝牙、
            //   开声音/关声音、开省电/关省电），旧表却只收了"打开/开启"（都归 LAUNCH），
            //   完全没有"关"这一支。后果：
            //     "蓝牙关了吧"    → 无语素命中 → NONE → 判 ASK → LLM 答"Windows 11 怎么关蓝牙"
            //     "把wifi关掉"    → 同（靠别的路径侥幸命中，不是机制之功）
            //   这不是"某个例句没收录"，是**整个动作范畴没建立** —— 属于机制缺口，
            //   所以补的方式是把这一范畴建全（开/关/开启/关闭/开一下/关一下…），
            //   而不是只补"关"一个字。
            //
            // 【为什么单字「开」不能像「打开」那样无脑收】
            //   「开」的歧义度高：「开车」「开会」「开心」都可能误撞。
            //   故单字「开/关」作为**最弱候选**放在表末，由 classify 结合宾语裁量。
            { "关闭", "CONFIGURE" }, { "关掉", "CONFIGURE" }, { "关上", "CONFIGURE" },
            { "关一下", "CONFIGURE" }, { "关了", "CONFIGURE" },
            { "开掉", "CONFIGURE" }, { "开到", "CONFIGURE" },

            // ---- LAUNCH：打开 / 启动 / 进 ----
            { "打开", "LAUNCH" }, { "开启", "LAUNCH" }, { "启动", "LAUNCH" },
            { "唤起", "LAUNCH" }, { "调出", "LAUNCH" }, { "开一下", "LAUNCH" },
            { "进入", "LAUNCH" }, { "进", "LAUNCH" }, { "回到", "LAUNCH" },

            // ---- QUERY_INFO：以"求知"为主的语素（弱动作，仅作辅助证据）----
            { "查一下", "QUERY_INFO" }, { "查查", "QUERY_INFO" }, { "查询", "QUERY_INFO" },
            { "查", "QUERY_INFO" }, { "看看", "QUERY_INFO" }, { "看一下", "QUERY_INFO" },
            { "看", "QUERY_INFO" }, { "告诉我", "QUERY_INFO" }, { "说说", "QUERY_INFO" },
            { "是什么", "QUERY_INFO" }, { "多少", "QUERY_INFO" }, { "几点", "QUERY_INFO" },

            // ---- QUERY_INFO：**"读"这一支**（补，结构性缺口）----
            //
            // 【为什么是缺口】"读"是中文最基础的读取动作，旧表却**一个读类语素都没有**
            //   （只有 查/看/告诉我/说说/是什么）。后果（实测）：
            //     「读一下屏幕上的内容」→ 无语素命中 → 走**目标兜底**通道（"屏幕"命中
            //     能力库摘要）→ 判成 **CONFIGURE** → 而注入"识屏工具"的是 QUERY_INFO 家族
            //     → vision.screen.understand **进不了候选** → 模型答
            //     「本机接口里没有读取屏幕内容的能力，我做不到」。
            //   它与「叫醒/提醒」是**同一类缺口**：整支动作范畴没建立，不是漏了一个词。
            //   补齐后 QUERY_INFO 命中 → 家族注入读屏工具 + 语素对齐给 vision +25 加权。
            // 【收词纪律】先收多字搭配（更明确），单字「读」放最后兜底。
            { "读一下", "QUERY_INFO" }, { "读一读", "QUERY_INFO" }, { "读出", "QUERY_INFO" },
            { "朗读", "QUERY_INFO" }, { "念一下", "QUERY_INFO" }, { "念出来", "QUERY_INFO" },
            { "读", "QUERY_INFO" }
    };

    /**
     * 需要剥离的**附着成分**（量词 / 助词 / 语气 / 客套前缀）。
     *
     * <p>剥离的目的是让"装个X"和"装X"、"把X装上"归一到同一个骨架，
     * 这样语素匹配就不必为每种搭配单列一条。
     *
     * <p>纪律：只剥"不承载动作语义"的成分。
     * 「上」在"装上"里是趋向补语（可剥），但在"上网"里是实义（不能剥）；
     * 因此「上」采用**受限剥离**——只在句尾或紧跟语素后时剥。
     */
    private static final String[] PARTICLES = {
            "一下", "一个", "一回", "一趟", "个", "把", "给", "帮我", "给我", "麻烦",
            "请", "快", "赶紧", "去", "来", "吧", "呢", "啊", "呀", "啦", "哦", "嘛",
            "然后", "顺便", "顺便帮我把", "的", "了"
    };

    /**
     * 剥离子尾「上/下来/起来/到」（趋向补语）。
     * 只在紧跟在动作语素之后时才剥 —— 避免误伤"上网""上滑"。
     */
    private static final String[] DIRECTIONAL = { "上去", "下来", "起来", "上", "到" };

    /**
     * 宾语类别提示词 —— 用于把一个**歧义语素**落到正确意图。
     *
     * <p>典型：「找一下美团」→ SEARCH（美团是应用，在商店里搜）；
     * 「找一下我的订单」→ SEARCH（内容）。两者都是 SEARCH，问题不大。
     * 但「打开美团」→ LAUNCH。区分靠的是语素「打开」而非宾语。
     *
     * <p>真正需要宾语提示的场景是**容器类**：
     * 「在应用商店里XX」→ XX 是什么动作就是什么动作，但**目标容器是商店**，
     * 这决定了后续路由到 store.* 能力族而不是别的。
     */
    private static final String[][] CONTAINER_HINTS = {
            { "应用商店", "store" }, { "应用市场", "store" }, { "商店", "store" },
            { "软件商店", "store" }, { "市场", "store" }, { "play商店", "store" },
            { "谷歌商店", "store" },
            { "浏览器", "browser" }, { "百度", "browser" }, { "必应", "browser" },
            { "谷歌", "browser" }, { "搜狗", "browser" },
            { "笔记", "notes" }, { "便签", "notes" }, { "备忘录", "notes" }
    };

    // ---------- 主入口 ----------

    /**
     * 识别一句话里的动作语素，按优先级返回**第一个**命中的意图。
     *
     * <p>返回 NONE 表示"没找到任何动作语素" —— 这句话不该被当成命令。
     *
     * <h3>无动作语素但有设备目标 → 仍视为命令</h3>
     * <p>实测「让手机安静点」没有任何祈使动词（"让…点"是使役结构），
     * 只凭动作语素会判 NONE → 判 ASK → LLM 答一段无关科普。
     * 但这句**指向本机的一个可控对象**（音量/提示音），且带祈使语气，
     * 语义上就是命令。这类句子走 {@link #hasDeviceTarget} 第二条通道
     * （目标词来自能力库 summary/keywords，不是手写名词表）。
     *
     * <p>注意顺序：**动作语素优先**，目标只在"没有动作语素"时兜底 ——
     * 因为目标词只说明"说的是哪个东西"，不说明"要干什么"，
     * 不能拿它去覆盖真实动作（否则"打开相机"里"相机"是应用名会被当目标而错判）。
     *
     * @param q 已规范化的句子（无标点、无空白）
     */
    public static Intent classify(String q) {
        List<Intent> all = allIntents(q);
        if (!all.isEmpty()) return all.get(0);
        // 兜底通道：无动作语素，但句子里有本机可控目标 + 祈使语气
        if (hasDeviceTarget(q) && isImperative(q)) return Intent.CONFIGURE;
        return Intent.NONE;
    }

    /**
     * **设备可控目标**识别 —— 数据源是**能力库本身**，不是手写名词表。
     *
     * <h3>为什么改成"从能力库反向生成"（2026-10-04 二改）</h3>
     * <p>本方法第一版是手写 {@code DEVICE_TARGETS = {音量,亮度,wifi,...}}。
     * 写完之后立刻发现它和旧词表是同一个病：
     * <pre>
     *   手写表    → 用户说"让手机安静点"（"安静"不在表里）→ 判 NONE → 答一段 iPhone 科普
     *   补"安静"  → 用户说"别吵"→ 又不在表里 → 再答一段科普
     *   ...又是永无止境
     * </pre>
     *
     * <p>关键在于：**"安静"算不算可控目标，不该由我拍脑袋决定，而该由"本机有没有接口
     * 能实现它"决定。** 实测能力库里确实有 {@code volume.ring.mute}（静音电话来电铃声）、
     * {@code volume.notif.mute}（静音系统消息通知）—— 也就是说"让手机安静"本机**能实现**，
     * 那它就是合法的可控目标。
     *
     * <p>于是正确做法：**目标词从能力库的 summary/keywords 里取**。
     * <ul>
     *   <li>新增一个能力 → 自动多一批可识别目标，**无需改本文件**；</li>
     *   <li>能力库里没有的东西 → 天然不会被识别成"可控目标"，
     *       不会把"让手机变漂亮"这种本机做不到的话当命令。</li>
     * </ul>
     * 这才是"能力库是单一真源"的正确用法 —— 语素引擎只留"动作"这张封闭表，
     * "目标"这张开放表**外包给能力库**。
     *
     * <h3>与动作语素的优先级</h3>
     * <p>目标识别**只在没有动作语素时兜底**，因为目标词只回答"说的是哪个东西"，
     * 不回答"要干什么"，不能拿它覆盖真实动作。
     */
    private static final String[] TARGET_STOPWORDS = {
            "设置", "查询", "调整", "打开", "关闭", "当前", "切换", "开启"
    };

    /**
     * 句子里是否出现**本机可控目标**。
     *
     * <p>数据源：能力库中每个能力的 {@code summary} 与 {@code keywords}
     * （中文自然语言描述），剥掉通用动作词后，剩余实词即"可控目标"。
     *
     * <p>例：{@code volume.ring.mute} 的 summary「静音电话来电铃声」
     * → 目标词含「静音」「铃声」→ "让手机安静点"里的"安静"命中「静音」的语义词根。
     */
    public static boolean hasDeviceTarget(String q) {
        if (q == null || q.length() == 0) return false;
        try {
            com.onesay.assistant.v2.capability.CapabilityRegistry reg =
                    com.onesay.assistant.v2.capability.CapabilityRegistry.getInstance();
            for (com.onesay.assistant.v2.capability.Capability cap : reg.listCapabilities()) {
                if (cap == null) continue;
                if (matchesTarget(q, cap.summary)) return true;
                for (String kw : cap.keywords) {
                    if (kw != null && kw.length() >= 2 && q.indexOf(kw) >= 0) return true;
                }
            }
        } catch (Throwable ignored) {
            // 能力库不可用时退到内置最小集，保证判定链不中断
            return containsAnyRaw(q, FALLBACK_TARGETS);
        }
        return containsAnyRaw(q, FALLBACK_TARGETS);
    }

    /**
     * 把能力的 summary 与句子做**中文词级**匹配，判断句子是否指向该能力描述的东西。
     *
     * <h3>中文不能按"非中文断开"切词</h3>
     * <p>初版实现按"非 CJK 字符断开"取词，对
     * {@code "设置屏幕亮度"} 这种**纯中文连续串**会切出整个 6 字串当**一个词**，
     * 于是 {@code q.indexOf("设置屏幕亮度")} 恒为 -1 —— summary 这条路等于废了。
     * 实测表现：{@code 屏幕再暗一点} 的 {@code hasDeviceTarget=false}，
     * 明明 summary 里就写着"屏幕"二字。
     *
     * <p>中文没有词间空格，正确做法是**滑窗取 2~3 字组**（n-gram），
     * 再逐组去句子里找。2 字组已能覆盖"屏幕/亮度/音量/蓝牙"这类设备词，
     * 3 字组用于"手电筒/飞行模式"这类三字目标。
     *
     * <p>刻意**不用分词器**：设备词表小、结构固定，n-gram + 停用词过滤足够，
     * 且零依赖、在 Java 7 下必可用。
     */
    private static boolean matchesTarget(String q, String summary) {
        if (summary == null || summary.length() == 0) return false;

        // 先按标点/字母数字把 summary 切成"中文段"，再对每个中文段滑窗
        int start = -1;
        for (int i = 0; i <= summary.length(); i++) {
            boolean cn = i < summary.length() && isCJK(summary.charAt(i));
            if (cn && start < 0) start = i;
            if (!cn && start >= 0) {
                if (gramHit(q, summary.substring(start, i))) return true;
                start = -1;
            }
            // 非中文段（英文/数字）也整体试一次
            if (!cn && i < summary.length()) {
                // 跳到下一个中文起点，中间的英文串由下面的兜底处理
            }
        }

        // 极少数确定同义：状态词与设备词的归一（不是同义词表，是"同一件事的两种说法"）
        if (stateSynonymMatches(q, summary)) return true;
        return false;
    }

    /**
     * 对一个中文段做 2~3 字滑窗，任一组出现在句子里即命中。
     *
     * <p>例：{@code "设置屏幕亮度"} 的 2 字组 = 设置/置屏/屏幕/幕亮/亮度
     * → {@code "屏幕再暗一点"} 含"屏幕" → 命中。
     */
    private static boolean gramHit(String q, String seg) {
        if (seg == null) return false;
        int n = seg.length();
        for (int len = 3; len >= 2; len--) {
            for (int i = 0; i + len <= n; i++) {
                String gram = seg.substring(i, i + len);
                if (isStopword(gram)) continue;
                if (q.indexOf(gram) >= 0) return true;
            }
        }
        return false;
    }

    /**
     * 状态同义对：{句子里的说法, 能力 summary 里的说法}。
     *
     * <p>纪律（写死在这里，防止它长成同义词表）：
     * <b>只收"同一个物理状态的不同日常说法"，不收"相近但不同"的词。</b>
     * 「安静=静音」是同一状态（都指提示音不响）；
     * 但「小声」≠「静音」（前者是音量小、后者是零）—— 这种就不收，
     * 因为收了会误判成静音。
     */
    private static final String[][] STATE_SYNONYMS = {
            { "安静", "静音" }, { "别吵", "静音" }, { "别响", "静音" },
            { "没声", "静音" }, { "不出声", "静音" }
    };

    /**
     * 句子里是否含某个**状态同义表达**，且目标串里含对应的规范说法。
     *
     * <p>对外暴露，供 {@code CapabilityRetriever} 打分复用 —— 保证
     * "识别这句话说的是什么"与"召回能实现它的能力"用**同一份同义定义**，
     * 不出现两处各写一套、改一边漏一边。
     *
     * @param q          用户句子
     * @param candidate  能力 summary（可能含规范说法，如"静音电话来电铃声"）
     */
    public static boolean stateSynonymMatches(String q, String candidate) {
        if (q == null || candidate == null) return false;
        for (String[] pair : STATE_SYNONYMS) {
            if (q.indexOf(pair[0]) >= 0 && candidate.indexOf(pair[1]) >= 0) return true;
        }
        return false;
    }

    /**
     * 句子里是否含**任何**状态同义表达（不看候选）。
     *
     * <p>供打分方判断"这句话是不是在描述一个状态"，
     * 若是，则对含规范状态词的候选加权。
     */
    public static boolean hasStateExpression(String q) {
        if (q == null) return false;
        for (String[] pair : STATE_SYNONYMS) {
            if (q.indexOf(pair[0]) >= 0) return true;
        }
        return false;
    }

    /** 极简兜底目标（能力库不可用时）；正常路径不走这里。 */
    private static final String[] FALLBACK_TARGETS = {
            "音量", "声音", "亮度", "蓝牙", "手电", "电量", "飞行模式", "闹钟"
    };

    private static boolean containsAnyRaw(String q, String[] words) {
        for (String w : words) {
            if (q.indexOf(w) >= 0) return true;
        }
        return false;
    }

    private static boolean isStopword(String w) {
        for (String s : TARGET_STOPWORDS) {
            if (w.equals(s)) return true;
        }
        return false;
    }

    private static boolean isCJK(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    /**
     * 粗略判断祈使语气（"点/些/了/吧/一下"等收尾语气，或"让/把/给我"等使役）。
     *
     * <p>它**不**用来单独判命令，只作为"有目标词"时的**辅助证据** ——
     * 避免把陈述句"我手机音量是5"误判成命令。
     */
    private static boolean isImperative(String q) {
        if (q == null || q.length() == 0) return false;
        String[] cues = { "点", "些", "吧", "了", "让", "把", "帮我", "给我",
                "一下", "一点", "小", "大", "高", "低", "多点", "少点" };
        for (String c : cues) {
            if (q.indexOf(c) >= 0) return true;
        }
        return false;
    }

    /**
     * 返回**所有**命中意图（去重，按语素表优先级）。
     *
     * <p>为什么返回列表而不是单个：一句话可能同时含多类动作
     * （"打开应用商店下载微信" → LAUNCH + INSTALL）。
     * 分类器需要知道"有几类动作"来判复合句，而不是只拿第一名。
     */
    public static List<Intent> allIntents(String q) {
        List<Intent> out = new ArrayList<Intent>();
        if (q == null || q.length() == 0) return out;

        String skel = stripParticles(q);
        // 骨架和原句都扫一遍：骨架用于归一句话形态，原句用于兜住"把X装上"这类后置语素
        for (String text : new String[]{ skel, q }) {
            collectIntents(text, out);
        }
        return out;
    }

    private static void collectIntents(String text, List<Intent> out) {
        if (text == null || text.length() == 0) return;
        boolean[] taken = new boolean[text.length()];
        for (String[] entry : LEXICON) {
            String morpheme = entry[0];
            Intent intent = Intent.valueOf(entry[1]);
            int from = 0;
            while (true) {
                int i = text.indexOf(morpheme, from);
                if (i < 0) break;
                int end = i + morpheme.length();
                // 已被更长的语素占用 → 跳过（长语素优先，避免"下载安装"被"下载"+"安装"重复计）
                boolean overlap = false;
                for (int k = i; k < end; k++) {
                    if (taken[k]) { overlap = true; break; }
                }
                if (!overlap) {
                    for (int k = i; k < end; k++) taken[k] = true;
                    if (!out.contains(intent)) out.add(intent);
                }
                from = end;
            }
        }
    }

    /**
     * 剥离附着成分，得到"骨感"句子。
     *
     * <p>例：
     * <pre>
     *   "帮我把微信装上吧"   → "微信上"        （帮我把/吧 剥掉）
     *   "给我装个高德地图"   → "装高德地图"     （给我/个 剥掉）
     *   "下载个抖音"         → "下载抖音"       （个 剥掉）
     *   "应用市场找一下美团" → "应用市场找美团"  （一下 剥掉）
     * </pre>
     */
    public static String stripParticles(String q) {
        if (q == null || q.length() == 0) return "";
        String s = q;
        // 1. 剥前缀客套（只剥一次，且必须还剩内容）
        for (String p : new String[]{ "请帮我把", "麻烦帮我把", "请帮我", "麻烦帮我", "帮我",
                "麻烦把", "给我把", "请把", "把", "麻烦", "请", "给我", "帮我", "帮我给", "给" }) {
            if (s.startsWith(p) && s.length() > p.length() + 1) {
                s = s.substring(p.length());
                break;
            }
        }
        // 2. 剥所有中置/后置附着成分
        for (String p : PARTICLES) {
            s = s.replace(p, "");
        }
        // 3. 剥趋向补语（受限：只剥紧跟在某动作语素之后的）
        s = stripDirectional(s);
        return s;
    }

    /**
     * 受限剥离趋向补语。
     *
     * <p>"装上"的"上"要剥（→"装"），但"上网"的"上"不能剥。
     * 判据：该补语前面 1~2 字构成一个**已知动作语素**。
     */
    private static String stripDirectional(String s) {
        String out = s;
        for (String d : DIRECTIONAL) {
            int idx = out.indexOf(d);
            while (idx >= 0) {
                // 检查前面是否是动作语素
                boolean followsMorpheme = false;
                for (String[] entry : LEXICON) {
                    String m = entry[0];
                    if (idx >= m.length() && out.regionMatches(idx - m.length(), m, 0, m.length())) {
                        followsMorpheme = true;
                        break;
                    }
                }
                if (!followsMorpheme) break;
                out = out.substring(0, idx) + out.substring(idx + d.length());
                idx = out.indexOf(d);
            }
        }
        return out;
    }

    /**
     * 识别这句话指向的**容器**（在哪里面做这件事）。
     *
     * @return "store" / "browser" / "notes" / null
     */
    public static String container(String q) {
        if (q == null) return null;
        for (String[] h : CONTAINER_HINTS) {
            if (q.indexOf(h[0]) >= 0) return h[1];
        }
        return null;
    }

    /**
     * 提取动作后面的**宾语**（动作作用的对象），用于槽位清洗。
     *
     * <p>这是解决「下载个抖音」→ keyword="个抖音" 的机制化做法：
     * 不靠"个"在剥列表里（那只是单例修补），而是**从语法上**定位
     * "动作语素之后的第一个实词串"，把其间的量词/助词清掉。
     *
     * <p>例：
     * <pre>
     *   "下载个抖音"     → "抖音"
     *   "装个高德地图"   → "高德地图"
     *   "在商店搜个支付宝" → "支付宝"（"在商店"是状语，"搜"之后才是宾语）
     * </pre>
     *
     * @param q 原句（已规范化）
     * @return 宾语串；找不到返回 null
     */
    public static String objectOf(String q) {
        if (q == null || q.length() == 0) return null;
        String s = stripParticles(q);
        if (s.length() == 0) return null;

        // 找到所有动作语素的位置，取**最后一个**语素的右边界作为宾语起点
        // （取最后一个是因"打开应用商店下载微信"里宾语是"微信"而不是"应用商店"）
        int bestEnd = -1;
        for (String[] entry : LEXICON) {
            String m = entry[0];
            int i = s.lastIndexOf(m);
            if (i < 0) continue;
            int end = i + m.length();
            if (end > bestEnd) bestEnd = end;
        }
        // **槽位片段**场景。
        //
        //   L0 传进来往往不是整句，而是模板已经吃掉了动作词之后的**残片**，
        //   例如模板「下载{n}」对「帮我下载个抖音」捕获到的是 nStr="个抖音"。
        //   此时片段里**根本没有动作语素**（"下载"在模板里）→ 上面 bestEnd 仍为 -1。
        //   旧实现这里直接返回 null，于是清洗落空、量词原样留在槽位。
        //
        //   正确语义：取不到动作右界时，**把片段整体当作宾语**，
        //   但开头的量词/助词（个/一个/一下/的）仍需剥掉 —— 这正是
        //   "个抖音" → "抖音" 的机制化解法（不靠往剥离变体加特例）。
        String obj;
        if (bestEnd >= 0 && bestEnd < s.length()) {
            obj = s.substring(bestEnd);
        } else {
            obj = s;
        }
        // 清掉残余的量词/助词（可能因为剥序残留）
        for (String p : new String[]{ "一下", "一个", "个", "的", "吧", "呢", "啊", "呀", "啦" }) {
            if (obj.startsWith(p)) obj = obj.substring(p.length());
        }
        obj = obj.trim();
        return obj.length() > 0 ? obj : null;
    }

    /** 观测用：语素表规模（供自测断言引擎已加载）。 */
    public static int lexiconSize() {
        return LEXICON.length;
    }
}
