package com.onesay.assistant.v2.s3;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/** D-1c（用户 14:25 拍板）：第一次建定时任务弹一次白名单引导；只给文字、绝不静默 root。 */
public class S3WhitelistGuideTest {

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), rel).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void showsOnlyOnFirstSuccessfulCreateWhenNotWhitelisted() {
        assertTrue(S3WhitelistGuide.shouldShow(S3Capabilities.SIGN_TASK_ADD, true, false, false));
        assertTrue(S3WhitelistGuide.shouldShow(S3Capabilities.SIGN_TASK_ADD_CONFIRM, true, false, false));
        assertFalse("已经显示过", S3WhitelistGuide.shouldShow(S3Capabilities.SIGN_TASK_ADD, true, true, false));
        assertFalse("已在白名单", S3WhitelistGuide.shouldShow(S3Capabilities.SIGN_TASK_ADD, true, false, true));
        assertFalse("失败不显示", S3WhitelistGuide.shouldShow(S3Capabilities.SIGN_TASK_ADD, false, false, false));
        assertFalse("待办不显示", S3WhitelistGuide.shouldShow("s3.todo.add", true, false, false));
        assertFalse("列表不显示", S3WhitelistGuide.shouldShow("s3.task.list", true, false, false));
    }

    @Test public void markedOnFirstCreateEvenIfWhitelisted() {
        assertTrue(S3WhitelistGuide.shouldMark(S3Capabilities.SIGN_TASK_ADD, true, false));
        assertFalse(S3WhitelistGuide.shouldMark(S3Capabilities.SIGN_TASK_ADD, true, true));
        assertFalse(S3WhitelistGuide.shouldMark(S3Capabilities.SIGN_TASK_ADD, false, false));
        assertFalse(S3WhitelistGuide.shouldMark("s3.todo.add", true, false));
    }

    @Test public void textOnlyNeverRunsRoot() throws Exception {
        String g = src("app/src/com/onesay/assistant/v2/s3/S3WhitelistGuide.java");
        assertFalse("引导里不能执行 root", g.contains("ShellExecutor") || g.contains("whitelistByRoot(ctx)") || g.contains("deviceidle whitelist +"));
        assertTrue(S3WhitelistGuide.TEXT.contains("只在你点的时候执行") && S3WhitelistGuide.TEXT.contains("只出现一次"));
        assertTrue(src("app/src/com/onesay/assistant/v2/s3/S3Hooks.java").contains("S3WhitelistGuide.apply(ctx, cap.sign, true, x.message)"));
        assertTrue("调试可重置", src("app/src/com/onesay/assistant/v2/test/S3TestDriver.java").contains("S3WhitelistGuide.reset(ctx, on)"));
        assertTrue(src("acceptance/device_acceptance.sh").contains("record S3-13 PASS"));
    }
}
