package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.s2.S2Clock;
import com.onesay.assistant.v2.soul.Claim;
import com.onesay.assistant.v2.soul.InMemorySoulRepo;
import com.onesay.assistant.v2.soul.SoulEngine;
import com.onesay.assistant.v2.soul.SoulHooks;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;
import java.util.TimeZone;

import static com.onesay.assistant.v2.router.AgentLoopTest.*;
import static org.junit.Assert.*;

/**
 * ★ device-1102 问题 3b：两轮对话，第一轮写记忆，第二轮各路径（L2 ask / compile / plan / AgentLoop / 流式）
 * 系统提示词都含该记忆与人设片段；改人设 / 记忆后不重启下一轮生效；超长记忆截注不丢；memdump 可读。
 *
 * <p>L2 四条路（ask/stream/compile/plan）的 HTTP 层要 Context 才能构造，这里分两层验：
 * (1) 源码接线：四个方法都用对应 PATH 常量过 {@link SoulHooks#withPrefix(String, String)}；
 * (2) 功能：同一个 withPrefix + {@link L2LLMCompiler#buildBody} 组出真实请求体，查 system 消息与历史。
 * AgentLoop 是真跑（ScriptedLlm 收到的 messages）。
 */
public class MemorySoulPathsTest {

    SoulEngine en;

    @Before public void setUp() {
        en = new SoulEngine(new InMemorySoulRepo(), TimeZone.getTimeZone("Asia/Shanghai"));
        SoulHooks.installForTest(en);
        ConversationMemory.getInstance().clear();
    }

    @After public void tearDown() {
        SoulHooks.installForTest(null);
        ConversationMemory.getInstance().clear();
    }

    static final String MEM = "我喝咖啡只要无糖燕麦拿铁";

    private static String sysOf(String body) throws Exception {
        JSONArray m = new JSONObject(body).getJSONArray("messages");
        assertEquals("system", m.getJSONObject(0).getString("role"));
        return m.getJSONObject(0).getString("content");
    }

    private String l2Body(String path, String q) throws Exception {
        String system = SoulHooks.withPrefix(path, "【BASE-" + path + "】");
        return L2LLMCompiler.buildBody(system, ConversationMemory.getInstance().snapshot(), q, 200, SoulHooks.PATH_STREAM.equals(path));
    }

    private String agentSys(String q) throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        llm.replies.add(say("好的。"));
        new AgentLoop(CapabilityRegistry.getInstance(), llm).run(q, new FakeExec(), 0);
        return llm.messagesSeen.get(0).getJSONObject(0).getString("content");
    }

    @Test public void secondTurnEveryPathCarriesMemoryAndSoul() throws Exception {
        // 第一轮：用户让记 → 写进核心记忆（与 SoulHooks.execute SIGN_PROFILE 同一调用）；问答进会话历史
        SoulEngine.WriteResult w = en.remember(MEM, Claim.SRC_USER, MEM, "s1", false, "", "", "", S2Clock.now());
        assertTrue(w.reason, w.ok);
        ConversationMemory.getInstance().record("记住我喝咖啡只要无糖燕麦拿铁", "好的，记住了：" + MEM + "。", false);

        String soulHead = en.activeSoul(S2Clock.now()).text.trim().substring(0, 8);
        String q2 = "帮我点杯平常喝的";
        String[] paths = { SoulHooks.PATH_ASK, SoulHooks.PATH_STREAM, SoulHooks.PATH_COMPILE, SoulHooks.PATH_PLAN };
        for (String p : paths) {
            String body = l2Body(p, q2);
            String sys = sysOf(body);
            assertTrue(p + " 含记忆", sys.contains(MEM));
            assertTrue(p + " 含人设", sys.startsWith("【人设】\n") && sys.contains(soulHead));
            assertTrue(p + " 前缀在 base 之前", sys.indexOf(MEM) < sys.indexOf("【BASE-" + p + "】"));
            assertTrue(p + " 带上一轮历史", body.contains("记住我喝咖啡只要无糖燕麦拿铁"));
        }
        String loopSys = agentSys(q2);
        assertTrue("AgentLoop 含记忆", loopSys.contains(MEM));
        assertTrue("AgentLoop 含人设", loopSys.startsWith("【人设】\n") && loopSys.contains(soulHead));

        // 五条路都记了账（memdump 用）
        java.util.Map<String, String> log = SoulHooks.pathLog();
        for (String p : paths) assertTrue("记账 " + p, log.containsKey(p) && log.get(p).contains("core="));
        assertTrue(log.containsKey(SoulHooks.PATH_AGENT));
    }

    @Test public void editsTakeEffectNextTurnWithoutRestart() throws Exception {
        en.remember(MEM, Claim.SRC_USER, MEM, "s1", false, "", "", "", S2Clock.now());
        assertTrue(sysOf(l2Body(SoulHooks.PATH_ASK, "q")).contains(MEM));

        // 改人设（控制台 / 语音「人设改成…」同一入口）+ 新记一条 + 删旧记忆 —— 同一引擎实例，不重建、不重启
        SoulEngine.SoulResult r = en.proposeSoul("你是一个说话简短的东北口音助手，叫小乐。", "测试", false, S2Clock.now());
        assertTrue(r.reason, r.ok);
        assertTrue(en.approveSoul(r.id, S2Clock.now()));
        en.remember("我对花生过敏", Claim.SRC_USER, "我对花生过敏", "s2", false, "", "", "", S2Clock.now());
        long memId = 0;
        for (Claim c : en.active(Claim.T_CORE)) if (c.text.equals(MEM)) memId = c.id;
        assertTrue(memId > 0);
        en.forget(new long[] { memId }, S2Clock.now());

        for (String p : new String[] { SoulHooks.PATH_ASK, SoulHooks.PATH_STREAM, SoulHooks.PATH_COMPILE, SoulHooks.PATH_PLAN }) {
            String sys = sysOf(l2Body(p, "q"));
            assertTrue(p + " 新人设下一轮生效", sys.contains("东北口音助手"));
            assertTrue(p + " 新记忆下一轮生效", sys.contains("花生过敏"));
            assertFalse(p + " 删掉的记忆下一轮消失", sys.contains(MEM));
        }
        String loopSys = agentSys("q");
        assertTrue(loopSys.contains("东北口音助手") && loopSys.contains("花生过敏") && !loopSys.contains(MEM));
    }

    @Test public void longMemoryIsClippedNotDropped() throws Exception {
        StringBuilder sb = new StringBuilder("我家的地址是");
        while (sb.length() < 120) sb.append("幸福路一百二十三号阳光小区七栋");
        String longMem = sb.toString();
        assertTrue(en.remember(longMem, Claim.SRC_USER, longMem, "s1", false, "", "", "", S2Clock.now()).ok);
        String sys = sysOf(l2Body(SoulHooks.PATH_ASK, "q"));
        assertTrue("超长记忆截到 itemMax 注入（旧版整条不注入）", sys.contains(longMem.substring(0, 40)));
        assertFalse("不整条塞进去", sys.contains(longMem));
        assertTrue(sys.contains("…"));
    }

    @Test public void memDumpListsSourcesSoulMemoryAndPaths() throws Exception {
        en.remember(MEM, Claim.SRC_USER, MEM, "s1", false, "", "", "", S2Clock.now());
        l2Body(SoulHooks.PATH_COMPILE, "q");
        List<String> d = SoulHooks.memDump(null);
        String all = String.valueOf(d);
        assertTrue(all, all.contains("来源: SQLite"));
        assertTrue(all, all.contains(Claim.T_CORE));
        assertTrue(all, all.contains("人设: v"));
        assertTrue(all, all.contains("本轮将注入"));
        assertTrue(all, all.contains(MEM));
        assertTrue(all, all.contains("路径 compile"));
        assertTrue(all, all.contains("会话: "));
    }

    @Test public void l2PathsAreWiredThroughWithPrefix() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String l2 = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/router/L2LLMCompiler.java").toPath()), Charset.forName("UTF-8"));
        String[] wired = {
                "system = com.onesay.assistant.v2.soul.SoulHooks.withPrefix(com.onesay.assistant.v2.soul.SoulHooks.PATH_ASK, system);",
                "SoulHooks.withPrefix(com.onesay.assistant.v2.soul.SoulHooks.PATH_STREAM, buildAskPrompt(searchCtx != null))",
                "String prompt = com.onesay.assistant.v2.soul.SoulHooks.withPrefix(com.onesay.assistant.v2.soul.SoulHooks.PATH_COMPILE, buildCompilerPrompt(query));",
                "String prompt = com.onesay.assistant.v2.soul.SoulHooks.withPrefix(com.onesay.assistant.v2.soul.SoulHooks.PATH_PLAN, buildPlanPrompt(query));",
        };
        for (String s : wired) assertTrue("缺接线: " + s, l2.contains(s));
        // compile / plan 发出去的就是这个 prompt（不是另起一份）
        assertTrue(l2.contains("callLlm(prompt, hist, TimeContext.withHint(query), COMPILE_MAX_TOKENS)"));
        assertTrue(l2.contains("callLlm(prompt, hist, TimeContext.withHint(query), PLAN_MAX_TOKENS)"));
    }
}
