package com.onesay.assistant.v2.net;

import static org.junit.Assert.*;

import org.junit.Test;

/** ★ a11y-labels 顺修真机 P1：debug 变体（没设 android:debuggable）也要允许备用 / 主模型的 http 地址；release 不变。 */
public class DebugBuildHttpTest {

    @Test public void debugVariantAllowsHttpEvenWithoutDebuggableFlag() {
        assertTrue(MainModelEndpoint.debugBuild(true, 0));
        assertTrue("自己加了 debuggable 的包照样算", MainModelEndpoint.debugBuild(false, 2));
        assertFalse(MainModelEndpoint.debugBuild(false, 0));
        assertFalse("其他标志位不算", MainModelEndpoint.debugBuild(false, 1 | 4 | 8));
        assertTrue(MainModelEndpoint.check("http://192.168.1.20:8000/v1", MainModelEndpoint.debugBuild(true, 0)).ok);
        assertFalse(MainModelEndpoint.check("http://192.168.1.20:8000/v1", MainModelEndpoint.debugBuild(false, 0)).ok);
    }

    @Test public void variantConstantsAreWhatTheFixReliesOn() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String dbg = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(root, "app/variant/debug/com/onesay/assistant/v2/BuildVariant.java")), "UTF-8");
        String rel = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(root, "app/variant/release/com/onesay/assistant/v2/BuildVariant.java")), "UTF-8");
        assertTrue(dbg.contains("public static final boolean IS_DEBUG = true;"));
        assertTrue(rel.contains("public static final boolean IS_DEBUG = false;"));
        assertFalse("单测按 release 口径编译", com.onesay.assistant.v2.BuildVariant.IS_DEBUG);
        String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(root, "app/src/com/onesay/assistant/v2/net/MainModelEndpoint.java")), "UTF-8");
        assertTrue(src.contains("return debugBuild(com.onesay.assistant.v2.BuildVariant.IS_DEBUG, flags);"));
    }
}
