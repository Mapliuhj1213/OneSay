package com.onesay.assistant.v2.router;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.ui.chat.ChatWindowPolicy;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 本地规则（计划 App / 记一记）的答复下面不再挂「以上由 AI 生成，可能包含不准确的信息」。 */
public class LocalRuleDisclaimerTest {

    static Invocation reply(String q, String text) throws Exception {
        JSONObject a = new JSONObject();
        a.put("text", text);
        a.put("query", q);
        return new Invocation("ask.reply", a, 1.0, Invocation.RouteSource.L0_RULE, q);
    }

    static String route(Invocation inv) {
        return ChatWindowPolicy.routeLabel(String.valueOf(inv.source), inv.sign, UnifiedRouter.localRuleOf(inv));
    }

    @Test public void plannerAndQuickNoteRepliesHaveNoDisclaimer() throws Exception {
        Invocation p = reply("今天有什么安排", "今天没有日程。");
        assertTrue(UnifiedRouter.markLocalRule("planner", p));
        assertEquals("planner", UnifiedRouter.localRuleOf(p));
        assertEquals("L0_RULE · ask.reply · 本地规则（planner）", route(p));
        assertFalse(ChatWindowPolicy.showDisclaimer(route(p)));
        Invocation q = reply("车停哪了", "B2-117");
        assertTrue(UnifiedRouter.markLocalRule("quicknote", q));
        assertFalse(ChatWindowPolicy.showDisclaimer(route(q)));
        assertEquals("答复文字不变", "B2-117", q.args.optString("text"));
    }

    @Test public void modelAndOtherRepliesKeepDisclaimer() throws Exception {
        Invocation llm = new Invocation("ask.reply", new JSONObject().put("text", "x"), 0.95, Invocation.RouteSource.L2_LLM, "q");
        assertEquals("L2_LLM · ask.reply", route(llm));
        assertTrue(ChatWindowPolicy.showDisclaimer(route(llm)));
        Invocation brief = reply("晨间简报", "…");
        assertFalse("其他插件（如晨间简报）不标", UnifiedRouter.markLocalRule("brief", brief));
        assertTrue(ChatWindowPolicy.showDisclaimer(route(brief)));
        Invocation exec = new Invocation("clock.alarm.set", new JSONObject().put("hour", 7), 1.0, Invocation.RouteSource.L0_RULE, "q");
        assertFalse("只标 ask.reply", UnifiedRouter.markLocalRule("planner", exec));
        assertFalse(exec.args.has(UnifiedRouter.ARG_LOCAL_RULE));
        assertTrue(ChatWindowPolicy.showDisclaimer(null));
        assertFalse(UnifiedRouter.markLocalRule("planner", null));
    }

    @Test public void wired() throws Exception {
        String ur = new String(Files.readAllBytes(new File("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java").toPath()), StandardCharsets.UTF_8);
        int take = ur.indexOf("rememberTurn(take.plugin.id(), query, take.inv);");
        int mark = ur.indexOf("markLocalRule(take.plugin.id(), take.inv);");
        int fin = ur.indexOf("h.onFinal(take.inv);");
        assertTrue("接管分支先标再交付", take > 0 && mark > take && fin > mark);
        String ad = new String(Files.readAllBytes(new File("app/src/com/onesay/assistant/v2/ui/chat/ChatAdapter.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(ad.contains("if (ChatWindowPolicy.showDisclaimer(r.route)) col.addView(dis);"));
        String sv = new String(Files.readAllBytes(new File("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(sv.contains("UnifiedRouter.localRuleOf(inv)"));
    }
}
