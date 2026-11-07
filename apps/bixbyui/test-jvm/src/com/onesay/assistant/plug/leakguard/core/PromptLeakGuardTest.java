package com.onesay.assistant.plug.leakguard.core;

import com.onesay.assistant.v2.asr.AsrHotwords;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * ★ fix/leak-guard：提示词回吐守卫。
 * 命中：整段 / 中段 / 带前缀热词表、无分隔符热词长串、系统提示词片段；
 * 不误杀：「微信」「打开支付宝」「微信、支付宝哪个好」等正常指令，以及正常回答里列举 App。
 * 另有接线守卫（各接入点在源码里确实调了守卫）。
 */
public class PromptLeakGuardTest {

    private PromptLeakGuard g;
    private String agentPrompt;

    private static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), rel).toPath()), "UTF-8");
    }

    /** 与 AsrHotwords.compose 同口径拼一张热词表（只用静态词）。 */
    private static String corpus(boolean withPrefix) {
        StringBuilder sb = new StringBuilder(withPrefix ? AsrHotwords.HOTWORD_PROMPT_PREFIX : "");
        boolean first = true;
        for (String w : AsrHotwords.staticWords()) {
            if (sb.length() + w.length() + 1 > 460) break;
            if (!first) sb.append('、');
            sb.append(w);
            first = false;
        }
        return sb.toString();
    }

    @Before public void setUp() throws Exception {
        g = new PromptLeakGuard();
        g.setHotwordPrefix(AsrHotwords.HOTWORD_PROMPT_PREFIX);
        g.addHotwords(Arrays.asList(AsrHotwords.staticWords()));
        agentPrompt = src("app/assets/agent_prompt.md");
        g.registerSystemPrompt("agent_prompt.md", agentPrompt);
        g.registerSystemPrompt("L2.ask.output", com.onesay.assistant.v2.router.PromptLibrary.Defaults.ASK_OUTPUT);
        g.registerSystemPrompt("L2.do.manual", com.onesay.assistant.v2.router.PromptLibrary.Defaults.DO_MANUAL);
        g.registerSystemPrompt("research", com.onesay.assistant.v2.agent.ResearchRunner.SYSTEM);
        g.registerSystemPrompt("brief.detail", com.onesay.assistant.v2.brief.BriefDetail.systemPrompt());
        g.registerSystemPrompt("soul.flush", com.onesay.assistant.v2.soul.SoulPrompts.FLUSH_SYSTEM);
        g.registerSystemPrompt("agentloop.untrusted", com.onesay.assistant.v2.router.AgentLoop.UNTRUSTED_RULE);
        g.registerSystemPrompt("app_choice", com.onesay.assistant.v2.roles.AppChoiceSpec.PROMPT_RULE);
        g.registerSystemPrompt("asr.hotword_prefix", AsrHotwords.HOTWORD_PROMPT_PREFIX);
    }

    // ------------------------------------------------------------------ 命中：热词表

    @Test public void fullCorpusWithPrefixIsLeak() {
        String c = corpus(true);
        assertTrue(c.length() > 300);
        assertEquals(PromptLeakGuard.Reason.HOTWORD_PREFIX, g.check(c).reason);
        assertTrue(g.checkPartial(c).leak());
        assertTrue(g.checkOutput(c).leak());
        assertNotNull(g.isLeak(c));
    }

    @Test public void fullCorpusWithoutPrefixIsLeak() {
        String c = corpus(false);
        assertEquals(PromptLeakGuard.Reason.HOTWORD_RUN, g.check(c).reason);
        assertTrue("输出侧按表序连串也认得出", g.checkOutput(c).leak());
    }

    @Test public void midListEchoIsLeak() {
        // 只吐中段（前缀判据拦不住的那种）
        String mid = "微信、支付宝、淘宝、京东、拼多多、抖音、快手、小红书";
        PromptLeakGuard.Verdict v = g.check(mid);
        assertTrue(v.leak());
        assertEquals(PromptLeakGuard.Reason.HOTWORD_RUN, v.reason);
        assertTrue(g.checkOutput(mid).leak());
        // 6 个正好到阈值
        assertTrue(g.check("哔哩哔哩、微博、知乎、高德地图、百度地图、腾讯视频").leak());
        // 逗号分隔、句号收尾也算
        assertTrue(g.check("WiFi，蓝牙，飞行模式，热点，个人热点，移动数据。").leak());
        // 中段从系统控制词开始、跨到查询对象
        assertTrue(g.check("深色模式、省电模式、超级省电、比特币、以太坊、油价、汇率").leak());
    }

    @Test public void midListWithOneUnknownWordStillLeak() {
        // 动态应用名没注册上 / 识别错一个字：容 1 个夹词
        assertTrue(g.check("微信、支付宝、淘宝、某某商城、京东、拼多多、抖音").leak());
        // 夹两个连续的不认识的词 → 断开
        assertFalse(g.check("微信、支付宝、淘宝、甲乙、丙丁、京东、拼多多").leak());
    }

    @Test public void truncatedPrefixIsLeak() {
        assertEquals(PromptLeakGuard.Reason.HOTWORD_PREFIX, g.check("语音识别热词表，以下专有").reason);
        assertEquals(PromptLeakGuard.Reason.HOTWORD_PREFIX, g.check("以下专有名词请优先按原词识别：必应").reason);
        assertTrue("中间结果前 6 字就拦", g.checkPartial("语音识别热词").leak());
        assertFalse("最终结果要 7 字", g.check("语音识别热词").leak());
        assertTrue("前缀在句中也拦", g.check("嗯。语音识别热词表，以下专有名词请优先按原词识别：必应、bing").leak());
    }

    @Test public void denseRunWithoutSeparatorsIsLeak() {
        PromptLeakGuard.Verdict v = g.check("必应bing百度谷歌google搜狗夸克");
        assertTrue(v.leak());
        assertEquals(PromptLeakGuard.Reason.HOTWORD_DENSE, v.reason);
        assertTrue(g.check("微信支付宝淘宝京东拼多多抖音快手").leak());
    }

    @Test public void dynamicCorpusRegistered() {
        // AsrHotwords.compose 会把含动态应用名的整段交给守卫
        g.addHotwordCorpus(AsrHotwords.HOTWORD_PROMPT_PREFIX + "微信、闲鱼、得物、盒马、叮咚买菜、朴朴、山姆会员商店");
        assertTrue(g.check("闲鱼、得物、盒马、叮咚买菜、朴朴、山姆会员商店").leak());
        // 前缀没注册时按冒号切，不把前缀当热词
        PromptLeakGuard g2 = new PromptLeakGuard();
        g2.addHotwordCorpus("语音识别热词表，以下专有名词请优先按原词识别：甲、乙");
        assertEquals(2, g2.hotwordCount());
    }

    @Test public void partialThresholdLower() {
        assertTrue(g.checkPartial("必应、bing、百度、谷歌").leak());
        assertFalse("最终结果 4 个不算", g.check("必应、bing、百度、谷歌").leak());
    }

    // ------------------------------------------------------------------ 命中：系统提示词

    @Test public void systemPromptFragmentsAreLeak() {
        assertTrue(g.strongFingerprintCount() > 30);
        String[] leaks = {
                "【最重要的一条：你要为目标负责，不是为某一次调用负责】用户说的是一件事（目标），可能对应好几种做法（手段）。",
                "同一个目标常有几种做法，按下面的顺序选，前一档能做就不要用后一档",
                "好的，我的设定是：你是智能手机语音助手的**执行体**（不是翻译器，也不是脚本播放器）。你可以调用工具来真正操作这台手机、或读取本机真实数据。",
                "3. **只有在所有可行手段都试过、确实做不到时**才收工。",   // 行首编号去掉后照样认得
                "你是一台安卓手机的操作员。你的职责是把用户的口头指令翻译成对手机的精确操作。",
                "你是一个只读的调研助手，替主任务查清一个边界清楚的问题。",
        };
        for (String s : leaks) {
            PromptLeakGuard.Verdict v = g.checkOutput(s);
            assertTrue("应拦：" + s, v.leak());
            assertEquals(PromptLeakGuard.Reason.SYSTEM_PROMPT, v.reason);
            assertTrue("输入侧也拦：" + s, g.check(s).leak());
        }
    }

    @Test public void fingerprintsSkipExamplesDigitsAndPlaceholders() {
        for (String fp : g.strongFingerprints()) {
            assertFalse("指纹不含数字：" + fp, fp.matches(".*[0-9].*"));
            assertFalse("指纹不含占位符：" + fp, fp.contains("{{"));
        }
        // 引号里的示例话术不做指纹：回答原样说出示例不算泄漏
        assertFalse(g.checkOutput("这条暂时没查到更多细节").leak());
        // 引号里的内容不做指纹（这句提示词主体全在「」里，单独回吐认不出 —— 已知取舍，整段回吐靠别的句子拦）
        assertFalse(g.checkOutput("我试了什么（每一步及结果）为什么没成你（用户）下一步可以怎么做").leak());
        // 重复注册是空操作
        assertEquals(0, g.registerSystemPrompt("again", agentPrompt));
    }

    @Test public void htmlCommentHeaderNotFingerprinted() {
        // agent_prompt.md 文件头注释（Mika 出处说明等）不发给模型，不做指纹
        assertFalse(g.checkOutput("「needs 行」这个约定借鉴自 Mika").leak());
    }

    // ------------------------------------------------------------------ 不误杀

    @Test public void normalCommandsPass() {
        String[] ok = {
                "微信", "打开微信", "打开支付宝", "微信、支付宝哪个好", "微信和支付宝哪个好用",
                "打开蓝牙和WiFi", "把WiFi、蓝牙、热点都关了", "帮我把微信、支付宝、淘宝、京东、抖音都卸载",
                "百度一下比特币价格", "用高德地图导航去公司", "明天早上七点的闹钟", "今天天气怎么样",
                "打开设置里的深色模式", "调低亮度", "截个屏发给微信好友", "用必应搜索谷歌的新闻",
                "语音识别", "给我讲讲热词是什么", "淘宝京东拼多多哪个便宜",
        };
        for (String s : ok) {
            assertFalse("误杀输入：" + s + " → " + g.check(s), g.check(s).leak());
            assertFalse("误杀输出：" + s, g.checkOutput(s).leak());
        }
    }

    @Test public void normalAnswersPass() {
        String[] ok = {
                // 正常回答列举 App（6 个以上热词相连，但不按热词表顺序）—— 输出侧不能拦
                "国内常用的社交应用有微信、微博、抖音、快手、小红书、知乎和QQ。",
                "购物可以用淘宝、京东、拼多多、美团、饿了么、携程，各有侧重。",
                "比特币现价约 86,258 美元，24 小时内上涨约 3.28%（Yahoo 财经数据）。",
                "我可以帮你打开应用、调设置、定闹钟、查天气，也能替你在界面上点按。",
                "已经帮你把亮度调低了，现在是 30%。",
                "我试了两个商店都没搜到这个应用，你可以在浏览器里搜官网下载。",
                "这个问题需要实时数据，我这边没查到，建议用天气 App 看一下。",
                "好了",
        };
        for (String s : ok) {
            assertFalse("误杀回答：" + s + " → " + g.checkOutput(s), g.checkOutput(s).leak());
        }
    }

    @Test public void switchOffPassesEverything() {
        g.setEnabled(false);
        assertFalse(g.check(corpus(true)).leak());
        assertFalse(g.checkPartial(corpus(true)).leak());
        assertFalse(g.checkOutput("你是一台安卓手机的操作员。你的职责是把用户的口头指令翻译成对手机的精确操作。").leak());
        g.setEnabled(true);
        assertTrue(g.check(corpus(true)).leak());
    }

    @Test public void nullAndEmpty() {
        assertFalse(g.check(null).leak());
        assertFalse(g.check("  ").leak());
        assertFalse(g.checkOutput("").leak());
        assertNull(g.isLeak("打开微信"));
        assertEquals("", PromptLeakGuard.head20(null));
        assertEquals(21, PromptLeakGuard.head20("一二三四五六七八九十一二三四五六七八九十多出来的字").length());
    }

    @Test public void corpusEchoAlsoCaughtByLegacyCheck() {
        // 旧的 isCorpusEcho 仍在；新守卫是它的超集（中段 / 带前缀 / 无分隔符）
        String mid = "微信、支付宝、淘宝、京东、拼多多、抖音";
        assertTrue(com.onesay.assistant.v2.asr.RealtimeAsrSession.isCorpusEcho(mid, corpus(false)));
        assertTrue(g.check(mid).leak());
    }

    // ------------------------------------------------------------------ 接线守卫

    @Test public void wiring() throws Exception {
        String ws = src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        String lg = "com.onesay.assistant.v2.leakguard.LeakGuardHost.";
        assertTrue("pushPartialToUi 查回吐", between(ws, "private void pushPartialToUi(", "fs.updateTranscript(clean)").contains(lg + "blockPartial(this, \"pushPartialToUi\""));
        String hvc = between(ws, "private void handleVoiceCommand(final String command) {", "sPendingVoiceCommand = command;");
        assertTrue("handleVoiceCommand 入口第一道", hvc.indexOf(lg + "blockInput(this, \"handleVoiceCommand\"") < hvc.indexOf("mFollowUpActive = false"));
        assertTrue("命中按没听清收尾", hvc.contains("notifyCaptureMiss("));
        String commit = between(ws, "private boolean commitRealtimeText(", "handleVoiceCommand(text);");
        assertTrue("分段攒着的提交查回吐", commit.indexOf(lg + "blockInput(this, \"commitRealtimeText\"") < commit.indexOf("mGotCommand = true"));
        assertTrue("流式最终（含分段入账前）", between(ws, "private void handleRealtimeFinal(", "addSegment(text)").contains("\"asr.rt.final\""));
        assertTrue("在途半段不记", ws.contains("\"asr.rt.partial\""));
        assertTrue("非流式最终", ws.contains("\"asr.cloud.final\""));

        String view = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java");
        assertTrue(between(view, "public void showAskReply(String question, String answer, StatusLinePolicy.Source source) {", "if (mV2) {").contains(lg + "blockOutput("));
        assertTrue(between(view, "private void v2ShowAskReply(", "hideConfirmCard();").contains(lg + "blockOutput("));
        assertTrue(between(view, "public void setAskStreamText(", "mV2Body = textSoFar").contains(lg + "blockOutput("));

        String tts = src("app/src/com/onesay/assistant/v2/ui/TtsSpeaker.java");
        String speak = between(tts, "private synchronized void speak(", "playTemplateLocked(tpl);");
        assertTrue("TTS 在模板 / 云端之前判", speak.contains(lg + "blockOutput(mApp, \"TtsSpeaker.speak\", raw)"));
        assertTrue(tts.contains("\"tts.cloud\"") && tts.contains("\"tts.system\""));

        String fss = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertTrue("AgentLoop 最终回答", fss.contains(lg + "blockOutput(FloatingSheetService.this, \"agentloop.final\", fr.answer)"));
        int l2 = fss.indexOf("\"L2.final\"");
        int kernel = fss.indexOf("FeatureKernel.get().dispatch(this, inv, command)");
        int plan = fss.indexOf("if (inv.isPlan()) {\r\n            executePlan(inv);");
        assertTrue("L2 结果在派发 / 计划之前判", l2 > 0 && l2 < kernel && l2 < plan);
        assertTrue("FSS 仍是 CRLF", fss.contains("\r\n") && !fss.replace("\r\n", "").contains("\n"));

        String hw = src("app/src/com/onesay/assistant/v2/asr/AsrHotwords.java");
        assertTrue("动态热词交给守卫", hw.contains("PromptLeakGuard.global().addHotwordCorpus(out)"));

        String core = src("app/src/com/onesay/assistant/plug/leakguard/core/PromptLeakGuard.java");
        assertFalse("core 无 Android 依赖", core.contains("import android."));
        String host = src("app/src/com/onesay/assistant/v2/leakguard/LeakGuardHost.java");
        assertTrue("日志只打前 20 字", host.contains("PromptLeakGuard.head20(text)") && !host.contains("+ text +") && !host.contains("+ text)"));
        assertTrue("默认开", host.contains("getBoolean(KEY, true)"));
    }

    private static String between(String s, String a, String b) {
        int i = s.indexOf(a);
        assertTrue("找不到：" + a, i >= 0);
        int j = s.indexOf(b, i);
        assertTrue("找不到：" + b, j > i);
        return s.substring(i, j);
    }
}
