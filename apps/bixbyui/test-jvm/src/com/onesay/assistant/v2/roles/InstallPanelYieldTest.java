package com.onesay.assistant.v2.roles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/** [round7] 第 3 项：点安装提示拉起商店后，面板让位（10-10 真机：面板还盖在三星商店详情页上）。 */
public class InstallPanelYieldTest {

    @Test public void policy() {
        assertTrue("商店真的打开了 → 让位", InstallLinkPlanner.yieldsPanel(InstallLinkPlanner.Step.OPEN, true));
        assertFalse("什么都没打开（没有商店 / 拉起失败）→ 留着说明", InstallLinkPlanner.yieldsPanel(InstallLinkPlanner.Step.OPEN, false));
        assertFalse("只给了「打开代理 App」芯片 → 留着让用户点", InstallLinkPlanner.yieldsPanel(InstallLinkPlanner.Step.OFFER_VPN_CHIP, true));
        assertEquals("与「打开应用 / 跳转界面」同口径 220 ms", 220L, InstallLinkPlanner.YIELD_DELAY_MS);
    }

    @Test public void everyExternalLaunchInRoleHooksYields() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String src = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/roles/RoleHooks.java").toPath()), StandardCharsets.UTF_8);
        // 每一处 startStore( / launchInstalled( 调用（定义除外）都包在 yieldsPanel(...) 里并接 yieldPanel(svc, pkg)
        Matcher m = Pattern.compile("(startStore|launchInstalled)\\(ctx, pkg").matcher(src);
        int calls = 0;
        while (m.find()) {
            int lineStart = src.lastIndexOf('\n', m.start()) + 1;
            int lineEnd = src.indexOf('\n', m.start());
            String line = src.substring(lineStart, lineEnd);
            if (line.contains("private static")) continue;
            calls++;
            assertTrue("外部拉起后没有让位：" + line.trim(), line.contains("InstallLinkPlanner.yieldsPanel(") && line.contains("yieldPanel(svc, pkg)"));
        }
        assertEquals("openInstall 已装直开 + 决策 OPEN + 「改用三星商店」芯片 = 3 处", 3, calls);
        int y = src.indexOf("private static void yieldPanel(");
        assertTrue(y > 0 && src.indexOf("svc.hideSheet()", y) > y && src.indexOf("InstallLinkPlanner.YIELD_DELAY_MS", y) > y);
    }
}
