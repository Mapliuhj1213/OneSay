package com.onesay.assistant.v2.notice;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/** 接线：回调中枢岛渠道走提醒（提醒关着退回旧完成卡）、共用闹钟、服务生命周期、岛视图认新种类、只复用岛窗口（LC-1）。 */
public class NoticeWiringTest {

    static String src(String rel) throws Exception {
        return NoticeSourcesTest.read(rel);
    }

    @Test public void callbackIslandChannelGoesThroughNotices() throws Exception {
        String hub = src("app/src/com/onesay/assistant/v2/callback/CallbackHub.java");
        int i = hub.indexOf("if (ch.contains(DeliveryPolicy.Channel.ISLAND)) {");
        String block = hub.substring(i, hub.indexOf("if (ch.contains(DeliveryPolicy.Channel.TTS))", i));
        assertTrue(block.contains("NoticeHooks.postCallback(app, islandId"));
        assertTrue("r2/fixes 7：提醒关着不再退回旧的完成卡", !block.contains("bgEnded("));
        assertTrue("TTS / 通知仍归回调中枢", hub.contains("speakProactive(text, 400)") && hub.contains("notify(app, 0x5C000000"));
        String mail = src("app/src/com/onesay/assistant/v2/callback/OutboundMailHooks.java");
        assertTrue(mail.contains("NoticeHooks.postDraftPending(app, s.id"));
        assertTrue("草稿待确认的通知照发（点开 = 确认卡）", mail.contains("notifyCard(app, s);"));
    }

    @Test public void sharesTheSingleAlarmAndServiceLifecycle() throws Exception {
        String sched = src("app/src/com/onesay/assistant/v2/s3/S3Scheduler.java");
        assertTrue(sched.contains("NoticeHooks.earliestNext(ctx)"));
        String run = src("app/src/com/onesay/assistant/v2/s3/S3Runner.java");
        assertTrue(run.contains("NoticeHooks.onServiceCreate(app)"));
        assertTrue(run.indexOf("NoticeHooks.onFire(mCtx, why)") < run.indexOf("S3Scheduler.reschedule(mCtx, \"after-fire(\""));
        assertTrue(run.contains("NoticeHooks.onTasksChanged(app)"));
    }

    @Test public void islandRendersNoticeKindsInExistingWindow() throws Exception {
        String v = src("app/src/com/onesay/assistant/v2/island/IslandView.java");
        assertTrue(v.contains("case COMPACT_NOTICE: return renderCompactNotice(") && v.contains("case EXP_NOTICE: return renderNotices("));
        assertTrue("左滑忽略只在提醒上；右滑仍是暂避", v.contains("&& dx < 0") && v.contains("IslandModel.K_NOTICE.equals(mDownKey)"));
        String ctl = src("app/src/com/onesay/assistant/v2/s1/IslandController.java");
        assertTrue("投递前取样面板状态（BUG-I2 起走 panelShown：让位 / 恢复）", ctl.contains("panel = mSvc.panelOnScreen();") && ctl.contains("mModel.panelShown(panel)"));
        assertTrue(ctl.contains("case NOTICE_ACTION:") && ctl.contains("case NOTICE_DISMISS:") && ctl.contains("case NOTICE_DISMISS_ALL:"));
        // LC-1：提醒不另开窗口，复用岛的固定窗 + 触摸区域（卡片外点击穿透）
        for (String f : new String[]{"NoticeHooks", "NoticeCenter", "NoticeSettings", "SqliteNoticeStore"}) {
            String s = src("app/src/com/onesay/assistant/v2/notice/" + f + ".java");
            assertTrue(f + " 不挂窗口", !s.contains("addView(") && !s.contains("WindowManager"));
        }
    }
}
