package com.onesay.assistant.v2.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * TriageDecider.build 单步 DO 无 L0 → AgentLoop。probe（triage_corpus + audit_extra 541 句）坐实 10 句单步 DO 进循环，
 * 其中「微信一打开就闪退」是误判（描述故障被动词「打开」判成动作，循环可能真去打开微信）→ 改判 ASK 3-app-crash；其余 9 句（导航 / 打车到这里或
 * 刚复制的地址、分析截图、记下复制内容、连接蓝牙耳机、分享 wifi 密码、未读短信）要读屏 / 剪贴板 / GUI，本地没有对应能力，进循环是对的。
 */
public class AppCrashTriageTest {

    static TriageDecider.Decision d(String q) { return TriageDecider.decide(q, TriageDecider.Signals.offline()); }

    @Test public void crashComplaintIsAskNotLoop() {
        String[] q = {"微信一打开就闪退", "抖音老是闪退", "淘宝一点开就崩溃", "支付宝怎么卡死了", "微信闪退了怎么办"};
        for (String s : q) {
            TriageDecider.Decision x = d(s);
            assertEquals(s, TriageDecider.Label.ASK, x.label);
            assertFalse(s, x.useAgentLoop);
        }
    }

    @Test public void imperativeStillAction() {
        assertFalse(TriageDecider.isAppCrashComplaint(TriageDecider.normalize("微信闪退了帮我清一下缓存")));
        assertFalse(TriageDecider.isAppCrashComplaint(TriageDecider.normalize("打开微信")));
        assertTrue(d("打开微信").isDo());
        assertTrue(d("微信老是闪退帮我重启一下微信").isDo());
    }
}
