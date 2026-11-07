package com.onesay.assistant.v2.imgsave;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/**
 * [imgsave] 合并守卫：挂钩 I-H1~H6 被合并冲突「吃掉」时立刻报红（源码文本断言）；CRLF 文件保持 CRLF；
 * 识屏既有的 --compressed 采集路径未动；存图走非压缩 dump；image.save 不进能力表；验收脚本两个模块在、探针不读 dmesg。
 */
public class ImageSaveHooksPresenceTest {

    static String src(String rel) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        return new String(Files.readAllBytes(new File(root, rel).toPath()), StandardCharsets.UTF_8);
    }

    static void crlf(String rel, String s) {
        assertTrue(rel + " CRLF", s.contains("\r\n"));
        assertFalse(rel + " 不能混入 LF 行", s.replace("\r\n", "").contains("\n"));
    }

    @Test public void hooks() throws Exception {
        String l0 = src("app/src/com/onesay/assistant/v2/router/L0RuleRouter.java");
        assertTrue("I-H1 L0 规则（宿主 → 内核）", l0.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".l0(mContext, raw)"));
        String pl = com.onesay.assistant.v2.kernel.KernelWiring.plugin("ImageSavePlugin");
        assertTrue("I-H1 插件 → Hooks", pl.contains("ImageSaveHooks.l0(ctx, raw)"));
        assertTrue("I-H1 L0 存图在摘要之前", com.onesay.assistant.v2.kernel.KernelWiring.before(com.onesay.assistant.v2.kernel.FeatureHook.L0, "imgsave", "summary"));
        String ur = src("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java");
        crlf("UnifiedRouter.java", ur);
        assertTrue("K 分诊开关由内核汇总", com.onesay.assistant.v2.kernel.KernelWiring.hostCalls("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java", ".triageSwitches(mContext)"));
        assertTrue("K 本地接管", com.onesay.assistant.v2.kernel.KernelWiring.hostCalls("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java", ".takeover(mContext, query, mL2)"));
        assertTrue("K 不进 AgentLoop", com.onesay.assistant.v2.kernel.KernelWiring.hostCalls("app/src/com/onesay/assistant/v2/router/UnifiedRouter.java", ".claims(mContext, query)"));
        assertTrue("I-H2 级联", pl.contains("ImageSaveHooks.route(ctx, query)"));
        assertTrue("I-H2 在 S2 之前", com.onesay.assistant.v2.kernel.KernelWiring.before(com.onesay.assistant.v2.kernel.FeatureHook.TAKEOVER, "imgsave", "s2"));
        // ★ 11-04：roles（TAKEOVER 50）只在安装卡在屏时接管「不用了 / 算了」，排在存图前；存图仍是其余接管里的第一位
        assertEquals("I-H2 接管第一位（roles 安装卡关卡之后）", 1, com.onesay.assistant.v2.kernel.KernelWiring.pos(com.onesay.assistant.v2.kernel.FeatureHook.TAKEOVER, "imgsave"));
        assertEquals(0, com.onesay.assistant.v2.kernel.KernelWiring.pos(com.onesay.assistant.v2.kernel.FeatureHook.TAKEOVER, "roles"));
        assertTrue("I-H3 不进 AgentLoop", pl.contains("ImageSaveHooks.claims(ctx, query)"));
        assertTrue("I-H3 分诊开关", ur.contains("diagOn, implicitOn, imgOn") && pl.contains("TriageSwitches.IMAGE_SAVE"));
        assertTrue("I-H2 日志口径", pl.contains("\"[ROUTE-IMG] 语音存图接管: \"") && pl.contains("\"L0 命中规则: image.save \" + inv.args"));
        String f = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        crlf("FloatingSheetService.java", f);
        assertTrue("I-H4 待选口令", f.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".interceptCommand(this, command)") && pl.contains("ImageSaveHooks.interceptCommand(svc, command)"));
        assertTrue("I-H4 圈一下先于存图待选", com.onesay.assistant.v2.kernel.KernelWiring.before(com.onesay.assistant.v2.kernel.FeatureHook.INTERCEPT, "s1", "imgsave"));
        assertTrue("I-H4 绕过识屏", f.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".bypassScreenRef(this, command)") && pl.contains("ImageSaveHooks.bypassScreenRef(ctx, command)"));
        assertTrue("I-H5 dispatch", f.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".dispatch(this, inv, command)") && pl.contains("ImageSaveHooks.onDispatch(svc, inv, command)"));
        assertTrue("I-H6 销毁", f.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".onServiceDestroy(this)") && pl.contains("ImageSaveHooks.onServiceDestroy(svc)"));
        assertTrue("调试驱动", src("app/src/com/onesay/assistant/v2/test/PluginDebugDrivers.java").contains("ImageSaveTestDriver.handle"));
        String t = src("app/src/com/onesay/assistant/v2/router/TriageDecider.java");
        assertTrue("分诊规则在记忆规则之前", t.indexOf("6i-image-save") < t.indexOf("3m-memory-recall"));
    }

    @Test public void captureContract() throws Exception {
        String sc = src("app/src/com/onesay/assistant/v2/vision/screenref/ScreenContextCapture.java");
        assertTrue("识屏既有路径仍是 --compressed（不动）", sc.contains("uiautomator dump --compressed"));
        String c = src("app/src/com/onesay/assistant/v2/imgsave/ImageSaveController.java");
        // 2026-10-25 exec 守护 v4：v4 不执行「screencap & … wait」这类复合脚本，存图改成单条命令先后执行（captureSteps）
        String[] st = ImageSaveController.captureSteps(new java.io.File("/d/scr.raw"), new java.io.File("/d/ui.xml"));
        assertEquals("存图：截图（单条）", "screencap '/d/scr.raw'", st[1]);
        assertEquals("存图：非压缩 dump（单条）", "uiautomator dump '/d/ui.xml'", st[2]);
        assertTrue("存图：产物由 App 先建好", c.contains("PrivateScratch.prepare(raw)") && c.contains("PrivateScratch.prepare(xml)"));
        assertFalse("存图：不用 --compressed", c.contains("--compressed"));
        assertTrue("面板先让位", c.indexOf("s1HideSheetNow()") < c.indexOf("runCapture(hit, command)"));
        assertTrue("FLAG_SECURE 检测", c.contains("PixelHeuristics.isAllBlack"));
        assertTrue("手动框选复用圈一下遮罩", c.contains("new RegionSelectOverlay(mSvc)"));
        assertTrue("[IMGSAVE] 日志", c.contains("[IMGSAVE] 缓存层 #"));
        for (String bad : new String[] {"dmesg", "kmsg", "watermark", "去水印"}) {
            assertFalse(bad, c.contains(bad));
        }
        assertFalse("image.save 不注册进能力表", src("app/src/com/onesay/assistant/v2/capability/BuiltinCapabilities.java").contains("image.save"));
    }

    @Test public void acceptanceModules() throws Exception {
        String s = src("acceptance/device_acceptance.sh");
        assertTrue(s.contains("cmd_imgprobe() {"));
        assertTrue(s.contains("cmd_imgsave() {"));
        assertTrue(s.contains("  imgprobe)  cmd_imgprobe ;;"));
        assertTrue(s.contains("  imgsave)   cmd_imgsave ;;"));
        assertTrue("日志 tag", s.contains("ImageSave:V"));
        int a = s.indexOf("cmd_imgprobe() {");
        int b = s.indexOf("\n}\n", a);
        String probe = s.substring(a, b);
        // 探针正文（去掉提示文字后）不能出现任何 dmesg / kmsg 读取，也不能写 App 数据目录
        String code = probe.replaceAll("c_yel \"[^\"]*\"", "");
        assertFalse("探针不能开第二个 dmesg reader", code.contains("dmesg") || code.contains("/dev/kmsg") || code.contains("getevent"));
        assertFalse("探针不删 App 数据", code.contains("rm -rf /data/data") || code.contains("rm -f /data/data"));
        assertTrue("非压缩与压缩两种 dump", probe.contains("uiautomator dump $DEV_TMP/bxp_u.xml") && probe.contains("uiautomator dump --compressed"));
        assertTrue(src("acceptance/README.md").contains("### imgsave"));
        assertTrue(src("acceptance/README.md").contains("### imgprobe"));
    }
}
