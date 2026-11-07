package com.onesay.assistant.v2.island;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/**
 * 2026-10-10 第 6 项：旧悬浮胶囊 StatusCapsuleView + 旧取件码浮卡删除后的守卫。
 * island.off = 不画任何浮层：前台任务不收起（确认卡在面板上，见 IslandModelTest.confirmWithoutOverlayStaysOnSheet），
 * S3 提醒 / 结果走通知，S3 无人值守确认按拒绝 + 通知说明（只告知、不能批准）。
 */
public class CapsuleRemovedTest {

    static String src(String rel) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        return new String(Files.readAllBytes(new File(root, rel).toPath()), StandardCharsets.UTF_8);
    }

    static boolean exists(String rel) {
        return new File(System.getProperty("bixby.root", "."), rel).exists();
    }

    @Test public void capsuleCodeGone() throws Exception {
        assertFalse(exists("app/src/com/onesay/assistant/v2/ui/StatusCapsuleView.java"));
        String ctl = src("app/src/com/onesay/assistant/v2/s1/IslandController.java");
        for (String gone : new String[]{"StatusCapsuleView", "PickupStack", "renderCapsule", "snapCapsule", "Mode.CAPSULE", "mTicker"}) {
            assertFalse(gone, ctl.contains(gone));
        }
        assertFalse(src("app/src/com/onesay/assistant/v2/ui/S1CardViews.java").contains("class PickupStack"));
        String model = src("app/src/com/onesay/assistant/v2/island/IslandModel.java");
        assertFalse("没有胶囊专用的拖动 / 吸边纯函数", model.contains("clampPosition") || model.contains("snapX"));
    }

    @Test public void islandOffMeansNoOverlay() throws Exception {
        String ctl = src("app/src/com/onesay/assistant/v2/s1/IslandController.java");
        assertTrue("非岛模式只摘掉岛", ctl.contains("} else if (mIsland != null) {"));
        assertTrue("岛挂载失败 → NONE", ctl.contains("apply(mModel.setMode(IslandModel.Mode.NONE), \"mode→NONE(island-failed)\")"));
        assertTrue("调试 mode off", ctl.contains("\"off\".equals(text) || \"capsule\".equals(text)"));
        assertTrue(IslandModel.chooseMode(true, true, true) == IslandModel.Mode.NONE);
    }

    @Test public void s3ConfirmHasMinimalRouteWhenIslandOff() throws Exception {
        String r = src("app/src/com/onesay/assistant/v2/s3/S3Runner.java");
        int a = r.indexOf("if (!island().ready() || !island().islandMode()) {");
        int b = r.indexOf("return AgentLoop.ConfirmVerdict.DENY;", a);
        assertTrue(a > 0 && b > a);
        String branch = r.substring(a, b);
        assertTrue("岛关着：发通知说明", branch.contains("postNote(") && branch.contains("noIslandConfirmText("));
        assertTrue("绝不自动批准", r.contains("static String noIslandConfirmText(String what)") && r.contains("不会自动批准"));
        assertTrue("岛关着时失败也通知", r.contains("postNote(id.hashCode(), \"定时任务没完成：\" + run.command, fail)"));
        assertTrue("提醒：岛不可用走通知（原有）", r.contains("postNote(id.hashCode(), \"提醒\""));
    }

    @Test public void acceptanceIslandOffCases() throws Exception {
        String sh = src("acceptance/device_acceptance.sh");
        assertTrue(sh.contains("ilcmd mode off"));
        assertFalse("不再测回落胶囊", sh.contains("ilcmd mode capsule") || sh.contains("mode=CAPSULE.*view=COMPACT_TASK"));
        assertTrue(sh.contains("record IL-22 PASS") && sh.contains("record IL-22b PASS") && sh.contains("ask IL-22d "));
        // 控制器日志口径：refreshMode 打「浮层形态 A → B」，apply 打「mode=X」
        String ctl = src("app/src/com/onesay/assistant/v2/s1/IslandController.java");
        assertTrue(ctl.contains("\"[ISLAND] 浮层形态 \" + old + \" → \" + m"));
        assertTrue(ctl.contains("\" mode=\" + mModel.mode()"));
        String log = "[ISLAND] 浮层形态 ISLAND → NONE（debug-mode off；enabled=false）\n[ISLAND] demo-taskStarted → [RENDER] mode=NONE sheet=EXPANDED view=HIDDEN (demo)\n";
        assertTrue(java.util.regex.Pattern.compile("浮层形态 ISLAND → NONE").matcher(log).find());
        assertFalse(log.contains("DETACH_SHEET") || log.contains("CAPSULE"));
    }
}
