package com.onesay.assistant.v2.ux;

import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.ui.wake.WakeCopy;
import com.onesay.assistant.v2.util.SpeechText;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * [r2/uxcompare] 失败提示可操作：原因 + 下一步。
 * 对标：Google 对话设计（出错给下一步 / rapid reprompt）、Apple HIG「Help people understand errors」、
 * 小爱「这个我还不会，你可以试试说…」。
 */
public class ActionableFailureTest {

    @Test public void appendsNextStepByCode() {
        assertEquals("操作已执行，但系统状态未发生变化。可以再说一次，或者说「打开设置」自己调。",
                WakeCopy.failureLine(ErrorCode.VERIFY_FAILED, "操作已执行，但系统状态未发生变化"));
        assertEquals("抱歉，手机上没有找到应用「抖音」。可以换个叫法再说一次，或先去应用商店安装。",
                WakeCopy.failureLine(ErrorCode.NOT_FOUND_APP, "抱歉，手机上没有找到应用「抖音」"));
        assertEquals("连不上服务器。检查一下网络再说一次。",
                WakeCopy.failureLine(ErrorCode.NETWORK_ERROR, "连不上服务器。"));
        assertTrue(WakeCopy.failureLine(ErrorCode.PERMISSION_DENIED, "缺少通讯录权限").endsWith("开权限后再试。"));
    }

    @Test public void doesNotDoubleUpOrBlameUserChoices() {
        // 原文案已经有下一步 → 不再追加
        assertEquals("邮箱还没配置，请先填写授权码",
                WakeCopy.failureLine(ErrorCode.NOT_CONFIGURED, "邮箱还没配置，请先填写授权码"));
        // 用户拒绝 / 取消 / 安全策略 → 不追加
        assertEquals("已取消", WakeCopy.failureLine(ErrorCode.USER_REJECTED, "已取消"));
        assertEquals("任务已取消", WakeCopy.failureLine(ErrorCode.CANCELLED, "任务已取消"));
        assertEquals("该操作被安全策略禁止", WakeCopy.failureLine(ErrorCode.GATE_DENIED, "⚠️ 该操作被安全策略禁止"));
        // 空文案 / 空码
        assertEquals("没能完成", WakeCopy.failureLine(null, null));
        assertEquals("没能完成。稍等几秒再说一次。", WakeCopy.failureLine(ErrorCode.TIMEOUT, ""));
    }

    @Test public void failureNeverLeaksStatusCode() {
        for (ErrorCode c : ErrorCode.values()) {
            String s = WakeCopy.failureLine(c, "没打开");
            assertFalse(c + " → " + s, s.contains(c.name()));
            assertFalse(s, s.contains("状态码"));
        }
    }

    @Test public void spokenFailureIsShort() {
        String line = WakeCopy.failureLine(ErrorCode.VERIFY_FAILED, "操作已执行，但系统状态未发生变化");
        String spoken = SpeechText.forSpeech(line, SpeechText.FAILURE_MAX);
        assertTrue(spoken, spoken.length() <= SpeechText.FAILURE_MAX + 1);
        assertTrue(spoken, spoken.startsWith("操作已执行"));
    }

    @Test public void unrecognizedOffersExamples() {
        String s = WakeCopy.unrecognizedLine("帮我把那个弄一下");
        assertTrue(s, s.startsWith("这句我没听懂：「帮我把那个弄一下」"));
        assertTrue(s, s.contains("可以换个说法"));
        String[] hb = WakeCopy.splitHeadline(s);
        assertEquals("这句我没听懂：「帮我把那个弄一下」", hb[0]);
        assertNotNull(hb[1]);
        // 太长的原话截断，不撑爆首行
        String longCmd = "一二三四五六七八九十一二三四五六七八九十一二三四五";
        assertTrue(WakeCopy.unrecognizedLine(longCmd).startsWith("这句我没听懂：「一二三四五六七八九十一二三四五六七八九十…」"));
        assertTrue(WakeCopy.unrecognizedLine(null).startsWith("这句我没听懂\n"));
        // 不是调试文案：新界面会显示它
        assertFalse(WakeCopy.isDebugCopy(s));
    }

    @Test public void wiredIntoPanel() throws Exception {
        String view = read("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java");
        assertTrue(view.contains("WakeCopy.failureLine(result.code, result.userMessage)"));
        assertTrue("失败仍出声（r2/voice-templates 起带错误码挑模板）", view.contains("speakFailure(mV2Body, result.code)"));
        String svc = read("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertTrue(svc.contains("WakeCopy.unrecognizedLine(command)"));
        assertTrue("徽标不变（WakeStage 靠它分类成 RESULT）", svc.contains("updateState(\"未能识别\""));
    }

    static String read(String rel) throws Exception {
        File f = new File(rel);
        if (!f.exists()) f = new File("../" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
