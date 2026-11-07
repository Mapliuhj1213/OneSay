package com.onesay.assistant.v2.s1;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/**
 * 审计 B-8：S1Hooks / ImageSaveController / MailBridge 里吞掉业务异常的地方补日志（控制流不变）。
 * L0RuleRouter 的 11 处 catch 都包在 JSONObject.put 上（只在 key 为 null 或值为 NaN 时抛），保持原样。
 */
public class SwallowedExceptionLogTest {

    /** 测试时 classpath 上是 shims 的 Log（编译期是 android.jar 的桩），所以用反射设捕获。 */
    private static void capture(List<String> l) throws Exception {
        Class.forName("android.util.Log").getField("sCapture").set(null, l);
    }

    @After public void tearDown() throws Exception { capture(null); }

    private static String src(String rel) {
        return com.onesay.assistant.v2.kernel.KernelWiring.src("app/src/com/onesay/assistant/v2/" + rel);
    }

    @Test public void s1WarnLogsHookNameAndException() throws Exception {
        List<String> cap = Collections.synchronizedList(new ArrayList<String>());
        capture(cap);
        S1Hooks.warn("onTaskStep", new IllegalStateException("岛视图已销毁"));
        assertEquals(1, cap.size());
        assertTrue(cap.get(0), cap.get(0).startsWith("W/"));
        assertTrue(cap.get(0), cap.get(0).contains("[S1] onTaskStep 异常"));
        assertTrue(cap.get(0), cap.get(0).contains("岛视图已销毁"));
    }

    @Test public void s1HooksNoLongerSwallowSilently() {
        String s = src("s1/S1Hooks.java");
        assertFalse(s.contains("catch (Throwable ignored)"));
        for (String h : new String[]{"onServiceDestroy", "onTaskStart", "onTaskStep", "onConfirmInfo",
                "onConfirmResolved", "onTaskOutcome", "onTaskEnded"}) {
            assertTrue(h, s.contains("warn(\"" + h + "\", t)"));
        }
    }

    @Test public void imageSaveAndMailLogBusinessFailures() {
        String img = src("imgsave/ImageSaveController.java");
        assertTrue(img.contains("[IMGSAVE] 保存异常"));
        assertTrue(img.contains("[IMGSAVE] 框选遮罩挂载异常"));
        String mail = src("executor/MailBridge.java");
        assertTrue(mail.contains("[MAIL] 打开文件夹 "));
    }

    @Test public void l0CatchesOnlyWrapJsonPut() {
        String l0 = src("router/L0RuleRouter.java");
        int idx = 0, n = 0;
        while ((idx = l0.indexOf("catch (Exception ignored)", idx)) >= 0) {
            int lineStart = l0.lastIndexOf('\n', idx) + 1;
            String line = l0.substring(lineStart, l0.indexOf('\n', idx));
            assertTrue("L0 里吞异常的只应是 args.put：" + line, line.contains("args.put("));
            idx++;
            n++;
        }
        assertTrue(n > 0);
    }
}
