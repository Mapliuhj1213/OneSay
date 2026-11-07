package com.onesay.assistant.v2.r2;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/** r2/integrate 的集成补丁接线核对（源码扫描）。 */
public class R2IntegrateWiringTest {
    private static String src(String rel) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        return new String(Files.readAllBytes(Paths.get(root, rel)), StandardCharsets.UTF_8);
    }

    /** 流式两条成功路径（单段 handleRealtimeFinal / 分段 commitRealtimeText）都清 AsrHealth，和非流式一致。 */
    @Test public void streamingSuccessMarksAsrHealthOk() throws Exception {
        String ws = src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        int n = 0, i = -1;
        while ((i = ws.indexOf("AsrHealth.markOk(WakeService.this)", i + 1)) >= 0) n++;
        assertTrue("非流式 + 流式单段 + 流式分段 = 至少 3 处 markOk，实际 " + n, n >= 3);
        int commit = ws.indexOf("private boolean commitRealtimeText(");
        int mark = ws.indexOf("AsrHealth.markOk", commit);
        int handle = ws.indexOf("handleVoiceCommand(text)", commit);
        assertTrue("commitRealtimeText 里 markOk 在 handleVoiceCommand 之前", commit > 0 && mark > commit && mark < handle);
    }

    /** 版本号由集成线程统一改：2.5.0 / 18。 */
    @Test public void versionBumped() throws Exception {
        String m = src("app/AndroidManifest.xml");
        // 上机版每轮 +1（vc19 / vc20…），这里只断言不回退到 r2 整合版之前
        java.util.regex.Matcher vc = java.util.regex.Pattern.compile("android:versionCode=\"(\\d+)\"").matcher(m);
        assertTrue(vc.find());
        assertTrue(Integer.parseInt(vc.group(1)) >= 18);
        assertTrue(m.contains("android:versionName=\"2.5.0\""));
    }
}
