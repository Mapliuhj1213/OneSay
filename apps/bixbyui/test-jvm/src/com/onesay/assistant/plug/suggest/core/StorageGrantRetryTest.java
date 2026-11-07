package com.onesay.assistant.plug.suggest.core;

import com.onesay.assistant.plug.suggest.core.scenes.shot.GrantPoll;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * ★ fix/shot-1104 (a)：截图 A 源没存储权限时不再「只查一次就放弃」——授权后自动注册，不用重启服务。
 * db1e0ce 真机：READ_EXTERNAL_STORAGE 未授予 → 「截图 A 源：没有存储权限，未注册」，之后再也不查（Android 8 授权不杀进程）。
 */
public class StorageGrantRetryTest {

    @Test public void pollFiresOnceOnTransitionToGranted() {
        GrantPoll p = new GrantPoll();
        assertFalse(p.onCheck(false));
        assertFalse(p.onCheck(false));
        assertFalse(p.granted());
        assertTrue("未授予 → 已授予：这一次注册", p.onCheck(true));
        assertTrue(p.granted());
        assertFalse("之后再查不再触发（不会重复注册）", p.onCheck(true));
        assertFalse(p.onCheck(false));
        assertEquals(3, p.tries());
    }

    @Test public void grantedAtStartRegistersImmediately() {
        GrantPoll p = new GrantPoll();
        assertTrue(p.onCheck(true));
        assertFalse(p.onCheck(true));
    }

    @Test public void cadenceFastThenSlow() {
        GrantPoll p = new GrantPoll();
        assertEquals(GrantPoll.FAST_MS, p.nextDelayMs());
        for (int i = 0; i < GrantPoll.FAST_TRIES; i++) p.onCheck(false);
        assertEquals("头 5 分钟每 15 s，之后每 60 s", GrantPoll.SLOW_MS, p.nextDelayMs());
        assertEquals(15000L, GrantPoll.FAST_MS);
        assertTrue(GrantPoll.SLOW_MS <= 60000L);
    }

    @Test public void hostWiresRetryAndPokes() throws Exception {
        String ms = ShotSrcTestUtil.read("app/src/com/onesay/assistant/v2/suggest/sources/MediaStoreScreenshotSource.java");
        assertTrue("没授权时进入等待重查", ms.contains("armWaitForGrant()"));
        assertTrue("亮屏 / 解锁立刻重查", ms.contains("Intent.ACTION_SCREEN_ON") && ms.contains("Intent.ACTION_USER_PRESENT"));
        assertTrue("定时重查按 GrantPoll 节奏", ms.contains("mPoll.nextDelayMs()"));
        assertTrue("授权后就地注册 + 通知宿主", ms.contains("mPoll.onCheck(storageGranted(mCtx))") && ms.contains("l.onGranted()"));
        assertTrue(ms.contains("public void poke()"));
        assertFalse("不再是「没权限就 return 永不重试」", ms.contains("未注册（只剩 root 快路径）"));
        String host = ShotSrcTestUtil.read("app/src/com/onesay/assistant/v2/suggest/SuggestHost.java");
        assertTrue(host.contains("public static void permissionPoke()"));
        assertTrue("授权后 B 源跟着起来", host.contains("fo.start()"));
        String console = ShotSrcTestUtil.read("app/src/com/onesay/assistant/v2/ui/console/Console.java");
        assertTrue("从系统权限页回控制台立刻重查", console.contains("SuggestHost.permissionPoke()"));
    }
}
