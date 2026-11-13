package com.onesay.assistant.v2.router;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P0-1 分诊决策表回归集：审计报告 §7.5 的 70 条原样移植 + 用户点名例句与边界扩展（共 102 条；2026-10-08 S2 +13 条；#9 设置诊断 +26 条；默认应用角色 +9 条；
 * round3 免前缀记忆 +14 条）。
 * 标签：ASK / DO_READ / DO_ACT / MIXED / CLARIFY / DIAGNOSE。验收门槛 ≥95%。
 */
public class TriageDeciderTest {

    /** 审计 §7.5 原 70 条（顺序、期望与审计一致）。 */
    static final String[][] AUDIT_70 = {
        {"现在几点", "DO_READ"},
        {"帮我看看现在几点", "DO_READ"},
        {"今天几号", "DO_READ"},
        {"今天星期几", "DO_READ"},
        {"打开时钟", "DO_ACT"},
        {"天气怎么样", "ASK"},
        {"明天会下雨吗", "ASK"},
        {"打开天气", "DO_ACT"},
        {"我的邮件有什么", "DO_READ"},
        {"读一下QQ邮箱的邮件", "DO_READ"},
        {"有没有新短信", "DO_READ"},
        {"我的闹钟有哪些", "DO_READ"},
        {"帮我看看今天有什么日程", "DO_READ"},
        {"蓝牙开着吗", "DO_READ"},
        {"wifi连上了吗", "DO_READ"},
        {"电量多少", "DO_READ"},
        {"今天电量还剩多少", "DO_READ"},
        {"存储空间还剩多少", "DO_READ"},
        {"音量是多少", "DO_READ"},
        {"屏幕上有什么", "DO_READ"},
        {"怎么设置闹钟", "ASK"},
        {"闹钟在哪里设置", "ASK"},
        {"怎么关掉自动旋转", "ASK"},
        {"自动旋转怎么关", "ASK"},
        {"蓝牙连不上怎么办", "ASK"},
        {"为什么蓝牙连不上", "ASK"},
        {"wifi是什么", "ASK"},
        {"什么是蓝牙", "ASK"},
        {"介绍一下苏州园林", "ASK"},
        {"帮我解释一下量子力学", "ASK"},
        {"翻译一下hello", "ASK"},
        {"1加1等于几", "ASK"},
        {"你是谁", "ASK"},
        {"讲个笑话", "ASK"},
        {"和平精英怎么玩", "ASK"},
        {"推荐几部电影", "ASK"},
        {"帮我写一首关于秋天的诗", "ASK"},
        {"能不能把亮度调高", "DO_ACT"},
        {"可以把wifi关了吗", "DO_ACT"},
        {"能帮我打开相机吗", "DO_ACT"},
        {"你能打开蓝牙吗", "DO_ACT"},
        {"亮度调高一点", "DO_ACT"},
        {"把亮度调到30", "DO_ACT"},
        {"亮度调到50", "DO_ACT"},
        {"帮我把蓝牙打开", "DO_ACT"},
        {"关掉手电筒", "DO_ACT"},
        {"手电筒", "DO_ACT"},
        {"飞行模式", "DO_ACT"},
        {"太亮了", "DO_ACT"},
        {"截个图", "DO_ACT"},
        {"把音量调小", "DO_ACT"},
        {"给妈妈打电话", "DO_ACT"},
        {"发短信给张三说我晚点到", "DO_ACT"},
        {"我想听歌", "DO_ACT"},
        {"定个闹钟", "CLARIFY"},
        {"明天早上七点叫醒我", "DO_ACT"},
        {"40分钟后提醒我喝水", "DO_ACT"},
        {"帮我记一下明天开会", "DO_ACT"},
        {"新建一个笔记", "DO_ACT"},
        {"写一封草稿邮件", "DO_ACT"},
        {"搜一下linkedin", "DO_ACT"},
        {"下载微信", "DO_ACT"},
        {"打开蓝牙和wifi", "DO_ACT"},
        {"打开wifi然后打开蓝牙", "DO_ACT"},
        {"打开蓝牙顺便告诉我什么是蓝牙", "MIXED"},
        {"帮我查一下明天的天气", "ASK"},
        {"看看电量够不够看一部电影", "MIXED"},
        {"如果电量低于20就打开省电模式", "MIXED"},
        {"帮我看看屏幕", "DO_READ"},
        {"天太黑了屏幕刺眼帮我调低点亮度", "DO_ACT"},
    };

    /** 扩展：用户点名例句（QQ 邮箱、苏州园林、看时间…）与边界。 */
    static final String[][] EXTRA = {
        {"帮我读一下QQ邮箱里的最新邮件", "DO_READ"},
        {"看看收件箱最新一封", "DO_READ"},
        {"比特币现在多少钱", "ASK"},
        {"今天有什么新闻", "ASK"},
        {"苏州园林有哪些", "ASK"},
        {"打开蓝牙有什么用", "ASK"},
        {"把屏幕亮度调到最低", "DO_ACT"},
        {"静音", "DO_ACT"},
        {"开启省电模式", "DO_ACT"},
        {"设个计时器", "CLARIFY"},
        {"五分钟后提醒我关火", "DO_ACT"},
        {"现在几点了", "DO_READ"},
        {"几点了", "DO_READ"},
        {"还有多少电", "DO_READ"},
        {"网络连上了吗", "DO_READ"},
        {"蓝牙打开了吗", "DO_READ"},
        {"给张三发微信说我到了", "DO_ACT"},
        {"打开相机然后拍张照", "DO_ACT"},
        {"先查一下电量再决定要不要开省电模式", "MIXED"},
        {"你好", "ASK"},
        {"谢谢", "ASK"},
        {"什么是5G", "ASK"},
        {"鲁迅是谁", "ASK"},
        {"一公里等于多少米", "ASK"},
        {"把这段话翻译成英文", "ASK"},
        {"怎么截图", "ASK"},
        {"帮我搜一下附近的餐厅", "DO_ACT"},
        {"下载抖音", "DO_ACT"},
        {"嗨比西比，打开蓝牙", "DO_ACT"},
        {"播放音乐", "DO_ACT"},
        {"来首歌", "DO_ACT"},
        {"卸载微信", "DO_ACT"},
        // ---- 第一轮盲测集（36 条，先盲测 26/36，再据此泛化规则后并入回归集）----
        {"把wifi打开", "DO_ACT"},
        {"关一下蓝牙", "DO_ACT"},
        {"声音大一点", "DO_ACT"},
        {"音量调到最大", "DO_ACT"},
        {"现在电池还有多少", "DO_READ"},
        {"手机还剩多少内存", "DO_READ"},
        {"我今天有什么安排", "DO_READ"},
        {"看看最新的短信", "DO_READ"},
        {"明天几点日出", "ASK"},
        {"长城有多长", "ASK"},
        {"番茄炒蛋怎么做", "ASK"},
        {"给我讲讲黑洞", "ASK"},
        {"帮我打开支付宝", "DO_ACT"},
        {"打个车", "DO_ACT"},
        {"拍张照片", "DO_ACT"},
        {"八点叫我起床", "DO_ACT"},
        {"设个闹钟", "CLARIFY"},
        {"来个倒计时", "CLARIFY"},
        {"屏幕太暗了", "DO_ACT"},
        {"夜间模式", "DO_ACT"},
        {"热点开着没", "DO_READ"},
        {"定位打开了吗", "DO_READ"},
        {"上海今天多少度", "ASK"},
        {"美元兑人民币汇率", "ASK"},
        {"什么叫区块链", "ASK"},
        {"帮我想个名字", "ASK"},
        {"打开微信然后扫一扫", "DO_ACT"},
        {"截图并发给张三", "DO_ACT"},
        {"如果明天下雨就提醒我带伞", "MIXED"},
        {"查一下电量顺便告诉我怎么省电", "MIXED"},
        {"你会做什么", "ASK"},
        {"返回桌面", "DO_ACT"},
        {"清理一下内存", "DO_ACT"},
        {"读一下最新的微信消息", "DO_READ"},
        {"我有几个未接来电", "DO_READ"},
        {"搜索周杰伦的歌", "DO_ACT"},
        // ---- [s2] 记得住（2026-10-08）：记一下 / 回忆 / 记忆管理 / 习惯默认值（记账默认关，不入集）----
        {"记一下车停在B2-17", "DO_ACT"},
        {"记住护照放在书房抽屉", "DO_ACT"},
        {"帮我记一下明天天气不好要带伞", "DO_ACT"},
        {"我车停哪了", "ASK"},
        {"我把护照放哪了", "ASK"},
        {"上次记的停车位置", "ASK"},
        {"我记了什么", "ASK"},
        {"删掉停车那条", "DO_ACT"},
        {"忘掉所有记录", "DO_ACT"},
        {"清空记忆", "DO_ACT"},
        {"以后打车都用滴滴", "DO_ACT"},
        {"别默认用滴滴了", "DO_ACT"},
        {"用高德打车", "DO_ACT"},
        // ---- [roles] 默认应用角色（2026-10-08）：查询 = DO_READ（读本机状态），设置/重置 = DO_ACT ----
        {"打车用哪个", "DO_READ"},
        {"导航用哪个app", "DO_READ"},
        {"默认应用有哪些", "DO_READ"},
        {"看看我的默认应用", "DO_READ"},
        {"以后导航用百度", "DO_ACT"},
        {"重置打车默认", "DO_ACT"},
        {"重置所有默认应用", "DO_ACT"},
        {"打车", "DO_ACT"},
        {"用美团打车", "DO_ACT"},
        // ---- [diag] #9 设置诊断（2026-10-08）：症状描述 → DIAGNOSE；知识问句 / 显式命令 / 方法问句保持原标签 ----
        {"为什么口袋里屏幕总亮", "DIAGNOSE"},
        {"屏幕老是自己熄灭", "DIAGNOSE"},
        {"我看屏幕时别让它熄屏", "DIAGNOSE"},
        {"手机耗电好快", "DIAGNOSE"},
        {"通知收不到", "DIAGNOSE"},
        {"微信消息延迟", "DIAGNOSE"},
        {"声音太小", "DIAGNOSE"},
        {"来电没声音", "DIAGNOSE"},
        {"Wi-Fi老断", "DIAGNOSE"},
        {"自动旋转不灵", "DIAGNOSE"},
        {"字太小看不清", "DIAGNOSE"},
        {"晚上屏幕太刺眼", "DIAGNOSE"},
        {"为什么一直振动", "DIAGNOSE"},
        {"勿扰是不是开着", "DIAGNOSE"},
        {"亮度老是自己变", "DIAGNOSE"},
        {"手机时间不准", "DIAGNOSE"},
        {"定位老是不准", "DIAGNOSE"},
        {"来电的时候手机不响", "DIAGNOSE"},
        {"帮我看看为什么耗电这么快", "DIAGNOSE"},
        {"为什么屏幕有时候会闪", "DIAGNOSE"},
        {"什么是勿扰模式", "ASK"},
        {"勿扰模式有什么用", "ASK"},
        {"防误触怎么开", "ASK"},
        {"开启防误触", "DO_ACT"},
        {"打开勿扰模式", "DO_ACT"},
        {"记一下手机耗电好快", "DO_ACT"},
        // ---- [s2] round3 #2 免前缀记忆（2026-10-08）：事实陈述 = 记一下（DO_ACT）；指令 / 问句保持原标签 ----
        {"我车停在B2-17", "DO_ACT"},
        {"车停B2-17了", "DO_ACT"},
        {"护照放在书房第二个抽屉", "DO_ACT"},
        {"我把钥匙放鞋柜上了", "DO_ACT"},
        {"车位是B2-17", "DO_ACT"},
        {"钥匙在鞋柜上", "DO_ACT"},
        {"别忘了护照放在书房", "DO_ACT"},
        {"存一下车位B2-17", "DO_ACT"},
        {"车停哪了", "ASK"},
        {"护照在哪", "ASK"},
        {"把音量调到5", "DO_ACT"},
        {"打开高德", "DO_ACT"},
        {"导航去B2", "DO_ACT"},
        {"wifi在哪设置", "ASK"},
        // ---- [imgsave] 语音存图（2026-10-08）：存图句 = 单步 DO_ACT（6i-image-save，本地接管）；方法/位置问句、截图、别的宾语保持原标签 ----
        {"保存图片", "DO_ACT"},
        {"存这张图", "DO_ACT"},
        {"把图存下来", "DO_ACT"},
        {"保存这张图片", "DO_ACT"},
        {"存一下图片", "DO_ACT"},
        {"全部保存", "DO_ACT"},
        {"保存第二张图", "DO_ACT"},
        {"把这几张照片都存下来", "DO_ACT"},
        {"图片怎么保存", "ASK"},
        {"怎么保存图片", "ASK"},
        {"图片保存在哪里", "ASK"},
        {"图片保存不了怎么办", "ASK"},
        {"保存截图", "DO_ACT"},
        {"存一下这个号码", "DO_ACT"},
        // R5（2026-10-08）抱怨式否定：留出集 2 条漏判的「没见过的相似说法」（不是留出集原句），以及必须保持原路由的指令句
        {"QQ来消息没提示", "DIAGNOSE"},
        {"短信来了不响", "DIAGNOSE"},
        {"有人给我打电话我都听不见", "DIAGNOSE"},
        {"朋友打过来我听不到铃声", "DIAGNOSE"},
        {"钉钉来消息手机没反应", "DIAGNOSE"},
        {"以后不要提醒我喝水", "DO_ACT"},
        {"别提醒我了", "DO_ACT"},
        {"给妈妈打电话", "DO_ACT"},
        {"提醒我晚上给他打电话", "DO_ACT"},
        {"帮我把通知设成不弹出", "DO_ACT"},
        {"微信消息不要提醒", "DO_ACT"},
        // 2026-10-11 晚：通讯录查找（人名当 number → 本机解析）与「我刚记的那个是什么」（长按记一记的回忆）
        {"打电话给老妈", "DO_ACT"},
        {"给我爸发个短信说晚点回", "DO_ACT"},
        {"我刚记的那个是什么", "ASK"},
        {"我刚才记了什么", "ASK"},
        // #18 长内容摘要（2026-10-08）：读屏幕 → 只读单步 DO_READ（5s-screen-summary，永不进 AgentLoop）；负例保持 #18 之前的判法
        {"总结一下", "DO_READ"},
        {"总结这页", "DO_READ"},
        {"这篇讲了什么", "DO_READ"},
        {"帮我概括一下", "DO_READ"},
        {"太长不看", "DO_READ"},
        {"这个页面说了啥", "DO_READ"},
        {"概括一下聊天记录", "DO_READ"},
        {"把这篇总结一下", "DO_READ"},
        {"总结我的记忆", "AMBIGUOUS"},
        {"总结一下我的记忆", "ASK"},
        {"总结今天的邮件", "AMBIGUOUS"},
        {"帮我总结一下今天的邮件", "ASK"},
        {"总结一下二战的原因", "ASK"},
        {"概括一下红楼梦", "AMBIGUOUS"},
        {"年终总结怎么写", "ASK"},
        {"总结一下今天的新闻", "ASK"},
        // D-3b（2026-10-09 第 4 阶段，用户拍板「四条bug要修」）：旧盲测的 4 条错判，修完移进回归集
        {"北京到上海多远", "ASK"},
        {"电量低于百分之二十的时候提醒我", "MIXED"},
        {"看看屏幕上写的什么然后翻译一下", "MIXED"},
        {"再大声一点", "DO_ACT"},
    };

    /**
     * 旧盲测（已用于调参）：原第二轮盲测集 30 条。2026-10-09 D-3b 据其中 4 条错判改了规则，
     * 这 4 条已移进回归集（EXTRA 末尾），剩下 26 条留作「不退化」守护（≥80%），<b>不再代表泛化能力</b>。
     * 真正的盲测是 {@link TriageBlindV2Test}（冻结的 v2 集）。
     */
    static final String[][] BLIND_30 = {
        {"把铃声调成静音", "DO_ACT"},
        {"打开设置", "DO_ACT"},
        {"关掉自动旋转", "DO_ACT"},
        {"亮度多少", "DO_READ"},
        {"今天有几个闹钟", "DO_READ"},
        {"相册里有多少张照片", "DO_READ"},
        {"帮我看看有没有新邮件", "DO_READ"},
        {"我的位置在哪", "DO_READ"},
        {"人为什么要睡觉", "ASK"},
        {"给我讲个故事", "ASK"},
        {"唐诗三百首有哪些名篇", "ASK"},
        {"2的10次方是多少", "ASK"},
        {"周末去哪玩好", "ASK"},
        {"给爸爸发短信说我晚上不回家吃饭", "DO_ACT"},
        {"打开抖音搜索美食", "DO_ACT"},
        {"十分钟后叫我", "DO_ACT"},
        {"提醒我明天交报告", "DO_ACT"},
        {"帮我定个明早六点半的闹钟", "DO_ACT"},
        {"音量调到一半", "DO_ACT"},
        {"开一下手电筒", "DO_ACT"},
        {"把wifi和蓝牙都关了", "DO_ACT"},
        {"省电模式开了没", "DO_READ"},
        {"今天适合洗车吗", "ASK"},
        {"你叫什么名字", "ASK"},
        {"拍个视频", "DO_ACT"},
        {"打开导航去公司", "DO_ACT"},
    };

    /**
     * [diag] 设置诊断留出集（16 条）：规则写完后才编写、<b>从未据此调过规则</b>，只打印不设门槛（估计泛化能力）。
     */
    static final String[][] DIAG_BLIND = {
        {"手机放兜里老是自己亮", "DIAGNOSE"},
        {"屏幕怎么一会儿就黑了", "DIAGNOSE"},
        {"电池掉得特别快怎么回事", "DIAGNOSE"},
        {"微信来消息不提醒", "DIAGNOSE"},
        {"别人打电话我听不到", "DIAGNOSE"},
        {"家里wifi动不动就断", "DIAGNOSE"},
        {"看视频横不过来", "DIAGNOSE"},
        {"字有点小", "DIAGNOSE"},
        {"夜里看手机眼睛疼", "DIAGNOSE"},
        {"手机一直在震", "DIAGNOSE"},
        {"免打扰开着没", "DIAGNOSE"},
        {"手机时间慢了五分钟", "DIAGNOSE"},
        {"免打扰是什么意思", "ASK"},
        {"把字体调大", "DO_ACT"},
        {"屏幕超时设成两分钟", "DO_ACT"},
        {"为什么天空是蓝的", "ASK"},
    };

    @Test public void diagBlindReport() {
        StringBuilder fails = new StringBuilder();
        int pass = run(DIAG_BLIND, fails);
        System.out.println("[TRIAGE] 诊断留出集 " + pass + "/" + DIAG_BLIND.length + (fails.length() > 0 ? "\n失败：\n" + fails : ""));
    }

    static String[][] all() {
        String[][] out = new String[AUDIT_70.length + EXTRA.length][];
        System.arraycopy(AUDIT_70, 0, out, 0, AUDIT_70.length);
        System.arraycopy(EXTRA, 0, out, AUDIT_70.length, EXTRA.length);
        return out;
    }

    static int run(String[][] cases, StringBuilder fails) {
        int pass = 0;
        for (String[] c : cases) {
            TriageDecider.Decision d = TriageDecider.decide(c[0], TriageDecider.Signals.offline());
            if (d.label.name().equals(c[1])) pass++;
            else fails.append("  ").append(c[0]).append(" 期望=").append(c[1])
                      .append(" 实际=").append(d.label).append(" (").append(d.rule).append(")\n");
        }
        return pass;
    }

    @Test public void labelledSet() {
        String[][] cases = all();
        assertTrue("用例数应 ≥90，实际 " + cases.length, cases.length >= 90);
        StringBuilder fails = new StringBuilder();
        int pass = run(cases, fails);
        StringBuilder f70 = new StringBuilder();
        int pass70 = run(AUDIT_70, f70);
        System.out.println("[TRIAGE] 审计 70 条通过 " + pass70 + "/" + AUDIT_70.length
                + "；全集通过 " + pass + "/" + cases.length
                + (fails.length() > 0 ? "\n失败：\n" + fails : ""));
        assertTrue("通过率 " + pass + "/" + cases.length + "\n" + fails, pass * 100 >= cases.length * 95);
    }

    @Test public void blindSetNoRegression() {
        StringBuilder fails = new StringBuilder();
        int pass = run(BLIND_30, fails);
        System.out.println("[TRIAGE] 旧盲测（已用于调参）通过 " + pass + "/" + BLIND_30.length
                + (fails.length() > 0 ? "\n失败：\n" + fails : ""));
        assertTrue("旧盲测 " + pass + "/" + BLIND_30.length + "\n" + fails, pass * 100 >= BLIND_30.length * 80);
    }

    /** 关键不变量：ASK / CLARIFY / DIAGNOSE 永不进 AgentLoop。 */
    @Test public void askNeverGoesToAgentLoop() {
        for (String[] c : all()) {
            if ("ASK".equals(c[1]) || "CLARIFY".equals(c[1]) || "DIAGNOSE".equals(c[1])) {
                TriageDecider.Decision d = TriageDecider.decide(c[0], TriageDecider.Signals.offline());
                assertFalse(c[0] + " → " + d, d.useAgentLoop);
            }
        }
    }

    /** [diag] 诊断开关关闭：症状句回到 #9 之前的判法（不产出 DIAGNOSE）；L0/缓存信号也不会把诊断句送进 AgentLoop。 */
    /**
     * [s2] round3 #2：免前缀记忆的负例 —— 不管开关开不开，标签都不变、规则永远不是 6m-memory-implicit
     * （「北京在华北」「我在家」这类本来就是 AMBIGUOUS → LLM 兜底的句子也不能被抢成「记一下」）。
     */
    static final String[] IMPLICIT_NEGATIVE = {
        "北京在华北", "把音量调到5", "打开高德", "导航去B2", "我在家", "我车停哪了", "把文件放到桌面",
        "放首歌", "停一下", "手机放兜里老是自己亮", "下午在会议室开会", "北京是中国的首都", "把车停到车库然后导航回家",
    };

    @Test public void implicitMemoryNegativesKeepLabels() {
        for (String q : IMPLICIT_NEGATIVE) {
            TriageDecider.Decision on = TriageDecider.decide(q, TriageDecider.Signals.offline(), true, true);
            TriageDecider.Decision off = TriageDecider.decide(q, TriageDecider.Signals.offline(), true, false);
            assertNotEquals(q, "6m-memory-implicit", on.rule);
            assertEquals(q, off.label, on.label);
        }
        // 开关关闭：正例回到 round3 之前的判法（不是记忆写入）
        TriageDecider.Decision off = TriageDecider.decide("我车停在B2-17", TriageDecider.Signals.offline(), true, false);
        assertNotEquals("6m-memory-implicit", off.rule);
        // 与「记一下…」同一口径（单步 DO_ACT）；生产里 S2 在分诊前接管、S2Hooks.claims 保证不进 AgentLoop
        assertEquals(TriageDecider.decide("记一下车停在B2-17", TriageDecider.Signals.offline()).useAgentLoop,
                TriageDecider.decide("我车停在B2-17", TriageDecider.Signals.offline()).useAgentLoop);
    }

    @Test public void diagnoseSwitchAndSignals() {
        for (String[] c : all()) {
            if (!"DIAGNOSE".equals(c[1])) continue;
            TriageDecider.Decision off = TriageDecider.decide(c[0], TriageDecider.Signals.offline(), false);
            assertNotEquals(c[0], TriageDecider.Label.DIAGNOSE, off.label);
            TriageDecider.Decision on = TriageDecider.decide(c[0], new TriageDecider.Signals("settings.brightness.up", true, false), true);
            assertEquals(c[0], TriageDecider.Label.DIAGNOSE, on.label);
            assertFalse(c[0], on.useAgentLoop);
        }
        assertEquals("诊断关闭时「声音太小」回到抱怨式 DO_ACT", TriageDecider.Label.DO_ACT,
                TriageDecider.decide("声音太小", TriageDecider.Signals.offline(), false).label);
        assertNull("LLM 不能判出 DIAGNOSE", TriageDecider.parseLlmLabel("{\"label\":\"DIAGNOSE\",\"confidence\":0.99}", null));
        assertNull("显式命令不是诊断", TriageDecider.diagnoseMatch("把屏幕超时改成5分钟"));
    }

    @Test public void mixedAlwaysAgentLoop() {
        assertTrue(TriageDecider.decide("如果电量低于20就打开省电模式", TriageDecider.Signals.offline()).useAgentLoop);
    }

    /** 单步 DO：本地层接得住（L0 命中 / 缓存命中）就不进循环；接不住才进。 */
    @Test public void singleStepDoUsesLocalLayerFirst() {
        TriageDecider.Decision off = TriageDecider.decide("亮度调高一点", TriageDecider.Signals.offline());
        assertTrue(off.useAgentLoop);
        TriageDecider.Decision l0 = TriageDecider.decide("亮度调高一点",
                new TriageDecider.Signals("settings.brightness.up", false, false));
        assertFalse(l0.useAgentLoop);
        TriageDecider.Decision cached = TriageDecider.decide("天太黑了屏幕刺眼帮我调低点亮度",
                new TriageDecider.Signals(null, true, false));
        assertFalse(cached.useAgentLoop);
    }

    /** 多步 / 达成型 DO 即使 L0 命中也进循环（L0 只做一步）。 */
    @Test public void multiStepAndGoalDoUseLoop() {
        TriageDecider.Signals l0 = new TriageDecider.Signals("store.search.market", false, false);
        assertTrue(TriageDecider.decide("下载微信", l0).useAgentLoop);
        assertTrue(TriageDecider.decide("打开wifi然后打开蓝牙", l0).useAgentLoop);
    }

    @Test public void politeCommandIsAction() {
        assertEquals(TriageDecider.Label.DO_ACT, TriageDecider.decide("能不能把亮度调高", TriageDecider.Signals.offline()).label);
        assertEquals(TriageDecider.Label.DO_ACT, TriageDecider.decide("可以把wifi关了吗", TriageDecider.Signals.offline()).label);
    }

    @Test public void howToIsAsk() {
        assertEquals(TriageDecider.Label.ASK, TriageDecider.decide("怎么设置闹钟", TriageDecider.Signals.offline()).label);
        assertEquals(TriageDecider.Label.ASK, TriageDecider.decide("wifi是什么", TriageDecider.Signals.offline()).label);
    }

    @Test public void coreStripsFrames() {
        assertEquals("现在几点", TriageDecider.decide("帮我看看现在几点", TriageDecider.Signals.offline()).core);
        assertEquals("亮度调高", TriageDecider.decide("能不能把亮度调高", TriageDecider.Signals.offline()).core);
    }

    @Test public void deterministic() {
        for (String[] c : all()) {
            assertEquals(TriageDecider.decide(c[0], TriageDecider.Signals.offline()).toString(),
                    TriageDecider.decide(c[0], TriageDecider.Signals.offline()).toString());
        }
    }

    @Test public void emptyAndWakeOnlyAreAmbiguousAndNoLoop() {
        assertEquals(TriageDecider.Label.AMBIGUOUS, TriageDecider.decide("", null).label);
        assertEquals(TriageDecider.Label.AMBIGUOUS, TriageDecider.decide("嗨比西比", null).label);
        assertFalse(TriageDecider.decide("嗨比西比", null).useAgentLoop);
    }

    @Test public void l0SignalResolvesUnknownPhrasing() {
        TriageDecider.Decision d = TriageDecider.decide("屏幕旋转锁",
                new TriageDecider.Signals("settings.rotation.state", false, false));
        assertEquals(TriageDecider.Label.DO_READ, d.label);
    }

    @Test public void llmFallbackParsing() {
        double[] conf = new double[1];
        assertEquals(TriageDecider.Label.DO_ACT,
                TriageDecider.parseLlmLabel("{\"label\":\"DO_ACT\",\"confidence\":0.9}", conf));
        assertEquals(0.9, conf[0], 1e-9);
        assertEquals(TriageDecider.Label.ASK,
                TriageDecider.parseLlmLabel("```json\n{\"label\":\"ask\",\"confidence\":0.8}\n```", conf));
        assertNull("低置信不采信", TriageDecider.parseLlmLabel("{\"label\":\"DO_ACT\",\"confidence\":0.5}", conf));
        assertNull(TriageDecider.parseLlmLabel("{\"label\":\"WHAT\",\"confidence\":0.99}", conf));
        assertNull(TriageDecider.parseLlmLabel("我觉得是 ASK", conf));
        assertNull(TriageDecider.parseLlmLabel(null, conf));
    }

    /** D-3b：4 条修正的泛化正例 + 不能被抢走的负例（规则不是只认原句）。 */
    @Test public void d3bGeneralizesWithoutStealing() {
        String[][] pos = {
            {"从家到公司有多远", "ASK"}, {"广州到深圳多少公里", "ASK"},
            {"温度超过三十度的时候告诉我", "MIXED"}, {"内存不足的时候提醒我清理", "MIXED"},
            {"读一下这条短信然后解释一下", "MIXED"},
            {"小声点", "DO_ACT"}, {"稍微大声一点", "DO_ACT"},
        };
        StringBuilder f = new StringBuilder();
        assertEquals(f.toString(), pos.length, run(pos, f));
        // 负例：带动作的距离句不是 ASK；没有「时候/时」的阈值句不是 MIXED；「亮点」「再来一首」不被调节规则抢走
        assertNotEquals("3-distance", TriageDecider.decide("导航到公司多远", TriageDecider.Signals.offline()).rule);
        assertNotEquals("2-threshold-when", TriageDecider.decide("电量低于百分之二十了吗", TriageDecider.Signals.offline()).rule);
        assertNotEquals("7-implicit-adjust", TriageDecider.decide("这个功能的亮点是什么", TriageDecider.Signals.offline()).rule);
        assertNotEquals("7-implicit-adjust", TriageDecider.decide("再来一首", TriageDecider.Signals.offline()).rule);
        assertEquals(TriageDecider.Label.ASK, TriageDecider.decide("翻译一下你好", TriageDecider.Signals.offline()).label);
    }
}
