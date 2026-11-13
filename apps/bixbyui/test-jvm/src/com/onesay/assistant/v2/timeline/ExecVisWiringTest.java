package com.onesay.assistant.v2.timeline;

import com.onesay.assistant.v2.kernel.KernelWiring;
import com.onesay.assistant.v2.ui.chat.ChatTranscript;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * ★ feat/api-first：执行可视化的接线（面板服务 / 输入条 / 对话窗）。Android 类在 JVM 里跑不起来，按源码查接点；
 * 对话历史里的时间轴字段是纯 Java，直接测。
 */
public class ExecVisWiringTest {

    private static String svc() {
        return KernelWiring.src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
    }

    @Test public void threeTaskThreadsBindStopScope() {
        String s = svc();
        assertTrue(s.contains("Threads.start(\"RouteStream\", com.onesay.assistant.v2.net.StopScope.wrap(cancel.scope(),"));
        assertTrue(s.contains("Threads.start(\"AgentLoop\", com.onesay.assistant.v2.net.StopScope.wrap(cancel.scope(),"));
        assertTrue(s.contains("Threads.start(\"ExecSingle\", com.onesay.assistant.v2.net.StopScope.wrap(execScope,"));
        assertTrue("CRLF 保持", s.contains("\r\n") && !s.replace("\r\n", "").contains("\n"));
    }

    @Test public void stopCurrentTaskStopsEverything() {
        String s = svc();
        int a = s.indexOf("public void stopCurrentTask(final String why) {");
        assertTrue(a > 0);
        String body = s.substring(a, s.indexOf("private boolean stopGuiAgent", a));
        assertTrue("取消复合任务", body.contains("agent.cancel();"));
        assertTrue("断在途流式", body.contains("stream.cancel();"));
        assertTrue("单步范围", body.contains("es.stop(why);"));
        assertTrue("点屏代理", body.contains("stopGuiAgent(why)"));
        assertTrue("挂起确认按未授权", body.contains("resolvePendingLoopConfirm(PendingLoopConfirm.VERDICT_DENY);"));
        assertTrue("时间轴「已停止」", body.contains("timelineStopped();"));
        assertTrue("状态行「已停止」", body.contains("updateState(\"已停止\""));
        assertTrue("复合任务取消也停点屏代理", s.contains("stopGuiAgent(\"复合任务取消\")"));
    }

    @Test public void loopCallbackFeedsTimeline() {
        String s = svc();
        assertTrue(s.contains("public void onToolResult(String sign, boolean ok, String reasonOrDetail) {"));
        assertTrue(s.contains("if (counts) tl.toolStart(text);"));
        assertTrue(s.contains("tl.thinking(on);"));
        assertTrue("确认卡挂起", s.contains("tl.approval("));
        assertTrue("确认卡裁决", s.contains("tl.approvalResolved(decided && pending.verdict == PendingLoopConfirm.VERDICT_ALLOW);"));
        assertTrue("结局", s.contains("if (cancel.isCancelled()) tl.stopped();") && s.contains("else tl.finish(fr != null && fr.finished);"));
        assertTrue("单步路径", s.contains("stl.toolStart(") && s.contains("stl.finish(result != null && result.ok);"));
        assertTrue(KernelWiring.src("app/src/com/onesay/assistant/v2/router/AgentLoop.java")
                .contains("interface LoopAwareCallback extends BudgetAskingCallback, LoopEvents, StepEvents, PhaseEvents, StepResultEvents"));
    }

    @Test public void taskNotificationOpensPanel() {
        String s = svc();
        assertTrue(s.contains("ACTION_OPEN_PANEL = \"com.onesay.assistant.v2.action.OPEN_PANEL\""));
        assertTrue(s.contains("} else if (ACTION_OPEN_PANEL.equals(action)) {"));
        assertTrue(s.contains("open.setAction(ACTION_OPEN_PANEL);"));
        assertTrue(s.contains(".setContentIntent(pi)"));
    }

    @Test public void panelNotificationSaysOneSay() {
        String s = svc();
        int a = s.indexOf("private Notification buildServiceNotification() {");
        String b = s.substring(a, s.indexOf("private void initView()", a));
        assertTrue("channel id 不变", b.contains("\"bixby_sheet_channel\", \"OneSay 悬浮面板\""));
        assertFalse("显示名没有旧称", b.contains("\"BixbyUI"));
        assertTrue("开源版：任务通知标题与常驻通知同一个应用名", s.contains("PANEL_NOTIFY_TITLE = com.onesay.assistant.v2.BuildVariant.APP_TITLE_V2;"));
    }

    @Test public void ossKeyedApisDefaultOff() {
        String st = KernelWiring.src("app/src/com/onesay/assistant/v2/apifirst/ApiFirstSettings.java");
        assertTrue("开源版：第 ② 档「已配置接口」默认关", st.contains("public static final boolean KEYED_DEFAULT = false;"));
    }

    @Test public void inputBarsHaveStopKey() {
        String pill = KernelWiring.src("app/src/com/onesay/assistant/v2/ui/widget/PillSearchBar.java");
        assertTrue(pill.contains("public void setRunning(boolean on) {") && pill.contains("tap == SendMicPolicy.Mode.STOP"));
        String bar = KernelWiring.src("app/src/com/onesay/assistant/v2/ui/chat/ChatBottomBar.java");
        assertTrue(bar.contains("public void setRunning(boolean on) {") && bar.contains("SendMicPolicy.mode(mEdit.getText(), hasAttachment(), false, mRunning)"));
        String ctl = KernelWiring.src("app/src/com/onesay/assistant/v2/ui/chat/ChatWindowController.java");
        assertTrue(ctl.contains("public void setTaskRunning(boolean on) {") && ctl.contains("mHost.stopTask();"));
        assertTrue("可点追问 = 用户说了这句", ctl.contains("public void onFollowup(String text) {") && ctl.contains("mHost.submit(text.trim());"));
        String ad = KernelWiring.src("app/src/com/onesay/assistant/v2/ui/chat/ChatAdapter.java");
        assertTrue(ad.contains("mActions.onFollowup(say);") && ad.contains("it.round.timeline != null"));
    }

    @Test public void transcriptRoundCarriesTimeline() {
        ChatTranscript t = new ChatTranscript();
        ChatTranscript.Round r = t.upsert("查下明天去上海的高铁", "有 3 趟", "AgentLoop", 1200L);
        assertNull("没有工具调用 → 没有时间轴", r.timeline);
        ExecTimeline tl = new ExecTimeline();
        tl.toolStart("查车次：上海");
        tl.toolResult(true, "");
        tl.finish(true);
        r.timeline = tl;
        ChatTranscript.Round f = t.freeze();
        assertNotNull(f.timeline);
        assertFalse("冻结进历史时是折叠的", f.timeline.expanded());
        assertTrue(f.timeline.header().startsWith("已完成"));
    }
}
