package com.onesay.assistant.v2.s1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/**
 * [s1] 合并守卫：FloatingSheetService / AgentLoop / ScreenRefHooks 里的 S1 挂钩被合并冲突"吃掉"时立刻报红
 * （清单 与 S1Hooks 类注释）。只做源码文本断言，不加载 Android 类。
 */
public class S1HooksPresenceTest {

    static String src(String rel) throws Exception {
        File loc = com.onesay.assistant.v2.testutil.TestRoot.start(S1HooksPresenceTest.class);
        File dir = loc;
        for (int i = 0; i < 6 && dir != null; i++) {
            File f = new File(dir, "app/src/" + rel);
            if (f.exists()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            dir = dir.getParentFile();
        }
        return new String(Files.readAllBytes(new File("app/src/" + rel).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void floatingSheetServiceHooks() throws Exception {
        String s = src("com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertTrue("文件必须保持 CRLF", s.contains("\r\n"));
        String pl = com.onesay.assistant.v2.kernel.KernelWiring.plugin("S1Plugin");
        assertTrue("S1-H1", s.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".onServiceCreate(this)") && pl.contains("S1Hooks.onServiceCreate(svc)"));
        assertTrue("S1-H1 S1 先于 S3", com.onesay.assistant.v2.kernel.KernelWiring.before(com.onesay.assistant.v2.kernel.FeatureHook.SERVICE_CREATE, "s1", "s3"));
        assertTrue("S1-H2", s.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".onServiceDestroy(this)") && pl.contains("S1Hooks.onServiceDestroy(svc)"));
        assertTrue("S1-H2 销毁顺序 S3Runner → S1 → 存图 → 摘要", com.onesay.assistant.v2.kernel.KernelWiring.before(com.onesay.assistant.v2.kernel.FeatureHook.SERVICE_DESTROY, "s3", "s1")
                && com.onesay.assistant.v2.kernel.KernelWiring.before(com.onesay.assistant.v2.kernel.FeatureHook.SERVICE_DESTROY, "s1", "imgsave") && com.onesay.assistant.v2.kernel.KernelWiring.before(com.onesay.assistant.v2.kernel.FeatureHook.SERVICE_DESTROY, "imgsave", "summary"));
        assertTrue("S1-H3", s.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".onWakeEarly(this)") && pl.contains("S1Hooks.onNewWake(svc)"));
        assertTrue("S1-H4", s.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".interceptCommand(this, command)") && pl.contains("S1Hooks.interceptCommand(svc, command)"));
        assertEquals("S1-H4 圈一下第一个拦截", 0, com.onesay.assistant.v2.kernel.KernelWiring.pos(com.onesay.assistant.v2.kernel.FeatureHook.INTERCEPT, "s1"));
        assertTrue("S1-H5", s.contains("S1Hooks.onOutsideTap(FloatingSheetService.this)"));
        // ★ arch/island-progress：S1-H6 带上分诊的「是否一开始就上岛」（单步请求不包成岛上的任务）
        assertTrue("S1-H6", s.contains("S1Hooks.onTaskStart(this, command, islandNow)"));
        assertTrue("S1-H6 上岛判断来自分诊", s.contains("TriageDecider.islandUpFront(mRouter.triage(command).decision)"));
        assertTrue("S1-H7", s.contains("S1Hooks.onTaskStep("));
        assertTrue("S1-H8a", s.contains("S1Hooks.onConfirmRequired(FloatingSheetService.this)"));
        assertTrue("S1-H8b", s.contains("S1Hooks.onConfirmResolved()"));
        assertTrue("S1-H9", s.contains("S1Hooks.onTaskEnded(FloatingSheetService.this, cancel.isCancelled(), navExecuted[0])"));
        assertTrue("S1-B 桥接块", s.contains("[s1] BEGIN") && s.contains("public synchronized void s1DetachForCapsule()")
                && s.contains("public synchronized void s1AttachSheet()") && s.contains("public void s1CancelActiveTask()")
                && s.contains("public void s1HideSheetNow()"));
        assertTrue("循环回调可取消（fast-screen-v2：BudgetAskingCallback 继承 CancellableGatedCallback）", (s.contains("new AgentLoop.BudgetAskingCallback()") || s.contains("new AgentLoop.LoopAwareCallback()"))   // ★ arch/agent-loop
                && com.onesay.assistant.v2.kernel.KernelWiring.src("app/src/com/onesay/assistant/v2/router/AgentLoop.java").contains("interface BudgetAskingCallback extends CancellableGatedCallback"));
        // [island] 岛上确认卡 / 结果信息挂钩：紧挨在对应 S1 挂钩之前
        int info = s.indexOf("S1Hooks.onConfirmInfo(cap, args)");
        int req = s.indexOf("S1Hooks.onConfirmRequired(FloatingSheetService.this)");
        assertTrue("onConfirmInfo 在 onConfirmRequired 之前", info > 0 && req > info && req - info < 200);
        int out = s.indexOf("S1Hooks.onTaskOutcome(");
        int end = s.indexOf("S1Hooks.onTaskEnded(FloatingSheetService.this, cancel.isCancelled(), navExecuted[0])");
        assertTrue("onTaskOutcome 在 onTaskEnded 之前", out > 0 && end > out && end - out < 400);
        assertTrue("岛上确认卡汇点", s.contains("public boolean s1ResolveLoopConfirm(boolean allow)"));
        assertTrue("失败重试", s.contains("public void s1RetryCommand(String command)"));
        // 确认卡强制展开必须先于 showConfirmCard（同一个 Runnable 内）
        int conf = s.indexOf("S1Hooks.onConfirmRequired(FloatingSheetService.this)");
        int card = s.indexOf("mSheetView.showConfirmCard(cap, args, tainted, tier,", conf);
        assertTrue("onConfirmRequired 必须紧挨在 showConfirmCard 之前", conf > 0 && card > conf && card - conf < 300);
        // 圈选拦截必须先于识屏指代拦截（「圈一下这个」不能被当成指代）
        int circle = s.indexOf(com.onesay.assistant.v2.kernel.KernelWiring.K + ".interceptCommand(this, command)");
        int sr = s.indexOf(com.onesay.assistant.v2.kernel.KernelWiring.K + ".screenReference(this, command)");
        assertTrue("圈选拦截先于识屏拦截", circle > 0 && sr > circle);
        // 新唤醒挂钩在识屏 onWake 之前
        int wake = s.indexOf(com.onesay.assistant.v2.kernel.KernelWiring.K + ".onWakeEarly(this)");
        int srWake = s.indexOf(com.onesay.assistant.v2.kernel.KernelWiring.K + ".onWake(this)");   // 第 4 阶段：识屏 onWake 在内核 WAKE 里排第一
        assertTrue(wake > 0 && srWake > wake);
    }

    @Test public void agentLoopAndScreenRefHooks() throws Exception {
        String a = src("com/onesay/assistant/v2/router/AgentLoop.java");
        assertTrue(a.contains("interface CancellableGatedCallback extends GatedCallback, Cancellable"));
        assertTrue(a.contains("return new Result(null, steps, false, REASON_CANCELLED)"));
        String r = src("com/onesay/assistant/v2/vision/screenref/ScreenRefHooks.java");
        assertTrue("复制前登记自家写入", r.contains("ClipboardMemory.markOwnWrite(text"));
        assertTrue("剪贴板兜底", r.contains("DeixisResolver.resolve(command, c.entities, clip)"));
        String u = src("com/onesay/assistant/v2/router/UnifiedRouter.java");
        assertTrue("取消不记负缓存", u.contains("AgentLoop.REASON_CANCELLED.equals(r.reason)"));
        assertTrue("UnifiedRouter.java 必须保持 CRLF（整文件行尾变化会淹没 diff）", u.contains("\r\n"));
    }
}
