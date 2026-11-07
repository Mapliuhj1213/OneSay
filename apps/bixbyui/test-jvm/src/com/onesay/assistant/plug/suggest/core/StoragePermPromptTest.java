package com.onesay.assistant.plug.suggest.core;

import com.onesay.assistant.plug.suggest.core.scenes.shot.PermPrompt;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ fix/shot-1104 (b)：release 内引导授权——控制台常驻一行 + 岛上只提示一次「截图分析需要存储权限 · 去授权」。 */
public class StoragePermPromptTest {

    @Test public void onlyWhenMissingAndSceneOnAndVisible() {
        assertTrue(PermPrompt.shouldTry(false, true, false, 0, true, false));
        assertFalse("有权限不弹", PermPrompt.shouldTry(true, true, false, 0, true, false));
        assertFalse("截图场景 / 总开关关着不弹", PermPrompt.shouldTry(false, false, false, 0, true, false));
        assertFalse("息屏不弹", PermPrompt.shouldTry(false, true, false, 0, false, false));
        assertFalse("锁屏不弹", PermPrompt.shouldTry(false, true, false, 0, true, true));
    }

    @Test public void onlyOnceNoNagging() {
        assertFalse("成功显示过一次就永不再弹", PermPrompt.shouldTry(false, true, true, 1, true, false));
        assertFalse("没显示成也最多试 5 次", PermPrompt.shouldTry(false, true, false, PermPrompt.MAX_TRIES, true, false));
        assertTrue(PermPrompt.shouldTry(false, true, false, PermPrompt.MAX_TRIES - 1, true, false));
        assertTrue("系统卡走 sys: 前缀，不进引擎", RoutedPresenter.isSys(PermPrompt.ID));
        assertTrue(PermPrompt.TITLE.contains("存储权限"));
        assertTrue(PermPrompt.SUB.contains("去授权"));
        assertEquals("去授权", PermPrompt.LABEL_GRANT);
    }

    @Test public void consoleRowActivityAndManifest() throws Exception {
        String plugin = ShotSrcTestUtil.read("app/src/com/onesay/assistant/v2/kernel/plugins/SuggestPlugin.java");
        assertTrue(plugin.contains("addStoragePermRow(host, g, act)"));
        assertTrue("细项页也有", plugin.contains("addStoragePermRow(host, st, act)"));
        assertTrue(plugin.contains("\"截图分析需要存储权限\"") && plugin.contains("\"去授权\""));
        String act = ShotSrcTestUtil.read("app/src/com/onesay/assistant/v2/suggest/StoragePermActivity.java");
        assertTrue(act.contains("requestPermissions(PERMS, REQ)"));
        assertTrue("永久拒绝 → 跳应用权限页", act.contains("ACTION_APPLICATION_DETAILS_SETTINGS"));
        assertTrue("授权后立刻重查", act.contains("SuggestHost.permissionPoke()"));
        String mf = ShotSrcTestUtil.read("app/AndroidManifest.xml");
        int i = mf.indexOf("com.onesay.assistant.v2.suggest.StoragePermActivity");
        assertTrue(i > 0);
        String decl = mf.substring(i, mf.indexOf("/>", i));
        assertTrue("不导出", decl.contains("android:exported=\"false\""));
        String host = ShotSrcTestUtil.read("app/src/com/onesay/assistant/v2/suggest/SuggestHost.java");
        assertTrue("首次触发：没授权时来了新图", host.contains("maybePromptStorage(\"new_image\")"));
        assertTrue("显示成功才记 shown", host.contains("if (ok) sp.edit().putBoolean(K_PROMPT_SHOWN, true)"));
        assertFalse("不替用户授权（release 不 pm grant）", host.contains("pm grant"));
    }
}
