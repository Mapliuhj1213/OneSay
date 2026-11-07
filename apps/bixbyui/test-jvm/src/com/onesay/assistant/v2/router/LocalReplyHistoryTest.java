package com.onesay.assistant.v2.router;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.kernel.KernelWiring;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/**
 * 插件本地接管（计划 App / 记一记）的答复写进会话历史——
 * 真机「今天有什么安排」（计划 App 本地答）之后追问取件码，下一轮走模型时看不到上一轮。
 */
public class LocalReplyHistoryTest {

    static Invocation reply(String q, String text) throws Exception {
        JSONObject a = new JSONObject();
        a.put("text", text);
        a.put("query", q);
        return new Invocation("ask.reply", a, 1.0, Invocation.RouteSource.L0_RULE, q);
    }

    @Test public void plannerAndQuickNoteRepliesAreRemembered() throws Exception {
        ConversationMemory mem = ConversationMemory.newForTest();
        assertTrue(UnifiedRouter.rememberTurn(mem, "planner", "今天有什么安排", reply("今天有什么安排", "今天没有日程。有 1 个件没取：取件码 A1-2345（测试菜鸟驿站）。")));
        assertTrue(UnifiedRouter.rememberTurn(mem, "quicknote", "我的取件码是多少", reply("我的取件码是多少", "取件码 A1-2345")));
        List<ConversationMemory.Turn> t = mem.snapshot();
        assertEquals(2, t.size());
        assertEquals("今天有什么安排", t.get(0).question);
        assertTrue(t.get(0).answer.contains("A1-2345"));
        assertFalse("计划 App 的答复是本地数据，不算污点", t.get(0).tainted);
        assertTrue("记一记内容来自识屏 → tainted（LOGIC-1）", t.get(1).tainted);
    }

    @Test public void othersAndNonRepliesAreNot() throws Exception {
        ConversationMemory mem = ConversationMemory.newForTest();
        assertFalse("其他插件不在这里记（S3 / 存图等有自己的执行回执）", UnifiedRouter.rememberTurn(mem, "s3", "记个待办", reply("记个待办", "好")));
        JSONObject a = new JSONObject();
        a.put("title", "x");
        assertFalse("不是 ask.reply", UnifiedRouter.rememberTurn(mem, "planner", "q", new Invocation("s3.todo.add", a, 1.0, Invocation.RouteSource.L0_RULE, "q")));
        assertFalse("空答复", UnifiedRouter.rememberTurn(mem, "planner", "q", reply("q", "")));
        assertFalse(UnifiedRouter.rememberTurn(mem, "planner", "q", null));
        assertEquals(0, mem.size());
    }

    @Test public void wiredInTakeoverBranch() {
        String src = KernelWiring.src("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java");
        int take = src.indexOf("FeatureKernel.get().takeover(mContext, query, mL2)");
        int rem = src.indexOf("rememberTurn(take.plugin.id(), query, take.inv);");
        int fin = src.indexOf("h.onFinal(take.inv);");
        assertTrue(take > 0 && rem > take && fin > rem);
        assertTrue("UnifiedRouter 保持 CRLF", src.contains("rememberTurn(take.plugin.id(), query, take.inv);   // 本地规则（计划 App / 记一记）的答复也进会话历史，追问有上下文\r\n")
                || !src.contains("\r\n"));
    }
}
