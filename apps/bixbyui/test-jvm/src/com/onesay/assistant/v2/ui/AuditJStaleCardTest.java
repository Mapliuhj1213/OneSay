package com.onesay.assistant.v2.ui;

import com.onesay.assistant.v2.router.L2LLMCompiler;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** ★ audit-J 5：新一轮 query 入场清上一轮结果卡 / 回执；问答只答本轮、不补报上一轮闹钟；闹钟回执带真实时刻。 */
public class AuditJStaleCardTest {

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(Paths.get(System.getProperty("bixby.root", "."), rel)), "UTF-8");
    }

    @Test public void newQueryClearsPreviousRoundOnly() throws Exception {
        String s = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        int begin = s.indexOf("beginForegroundRequest();   // ★ LC-5");
        int clear = s.indexOf("clearPreviousRoundViews();   // ★ audit-J 5");
        int icpt = s.indexOf("interceptCommand(this, command)) return;", begin);
        int route = s.indexOf("final UnifiedRouter.Cancel cancel = new UnifiedRouter.Cancel();", begin);
        assertTrue(begin > 0 && icpt > begin && clear > icpt && route > clear);
        assertEquals("只在新 query 入场时调一次（不在本轮执行中清）", s.indexOf("clearPreviousRoundViews();   // ★ audit-J 5"),
                s.lastIndexOf("clearPreviousRoundViews();"));
        String v = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java");
        int m = v.indexOf("public void clearPreviousRound()");
        String body = v.substring(m, v.indexOf("\n    }\n", m));
        assertTrue(body.contains("mV2Body = null") && body.contains("clearAlarmCards()") && body.contains("mResultCard.setVisibility(GONE)"));
        assertFalse("本轮转写不清", body.contains("mV2Listen = null") || body.contains("mV2Echo = null"));
    }

    @Test public void askPromptAnswersThisTurnOnly() throws Exception {
        assertTrue(L2LLMCompiler.ASK_HISTORY_SCOPE_RULE.contains("不要再复述或补报"));
        assertTrue(L2LLMCompiler.ASK_HISTORY_SCOPE_RULE.contains("不要自己推算"));
        assertTrue(src("app/src/com/onesay/assistant/v2/router/L2LLMCompiler.java").contains("+ ASK_HISTORY_SCOPE_RULE;"));
    }

    @Test public void alarmReceiptCarriesRealTime() throws Exception {
        String s = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertTrue(s.contains("AlarmBridge.isAlarmSign(cap.sign) && r.userMessage != null"));
        assertTrue(s.contains("sb.append(\"；结果=\")"));
    }
}
