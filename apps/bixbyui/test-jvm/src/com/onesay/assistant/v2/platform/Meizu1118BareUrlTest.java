package com.onesay.assistant.v2.platform;

import com.onesay.assistant.v2.executor.ShellCommand;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * ★ meizu-1118 B7（真机 Meizu 21 Pro，无 root）：「打开网页www.baidu.com」路由对了（browser.open url=www.baidu.com），
 * 执行却报 SHIZUKU_PROG_NOT_WHITELISTED(am)：App 内打开链接只认 http(s):// / market://，没写协议的网址掉到 Shizuku。
 * 现在主机名带点、没有冒号的裸网址补 https:// 后 App 内打开。
 */
public class Meizu1118BareUrlTest {

    static String cmd(String url) throws Exception {
        return ShellCommand.fill("am start -a android.intent.action.VIEW -d {url}",
                ShellCommand.slotValues(new JSONObject().put("url", url), null, null));
    }

    @Test public void bareHostGetsHttps() throws Exception {
        assertEquals("https://www.baidu.com", ShizukuCompat.viewUri(cmd("www.baidu.com")));
        assertEquals("https://例子.中国", ShizukuCompat.viewUri(cmd("例子.中国")));
        assertEquals("https://新华网.中国/news", ShizukuCompat.viewUri(cmd("新华网.中国/news")));
        assertEquals("https://bilibili.com/v?a=1", ShizukuCompat.viewUri(cmd("bilibili.com/v?a=1")));
        assertEquals("https://example.com/a?b=1", ShizukuCompat.viewUri(cmd("https://example.com/a?b=1")));
    }

    @Test public void otherSchemesStillNotLocal() throws Exception {
        assertNull("没有点的不是网址", ShizukuCompat.viewUri(cmd("localhost")));
        String[] raw = {"intent:#Intent;end", "tel:10086", "javascript:alert(1)", "file:///sdcard/a.html", "content://x.y/z"};
        for (String r : raw) {
            assertNull(r, ShizukuCompat.viewUri("am start -a android.intent.action.VIEW -d '" + r + "'"));
            assertNull(r, ShizukuCompat.viewUri("am start -a android.intent.action.VIEW -d " + r));
        }
        assertNull("带端口不补", ShizukuCompat.viewUri(cmd("a.com:8080/x")));
        assertNull("尾部是点不算", ShizukuCompat.viewUri(cmd("abc.")));
        assertNull(ShizukuCompat.viewUri("am start -a android.intent.action.VIEW -d 'www.a.com' -n x/y"));
    }

    /** ★ meizu-1118 B8：媒体音量命令认出来走 App 内 AudioManager；别的流 / 拼接不认。 */
    @Test public void mediaVolumeLocal() {
        assertEquals(-2, ShizukuCompat.mediaVolume("media volume --stream 3 --get"));
        assertEquals(30, ShizukuCompat.mediaVolume("media volume --stream 3 --set 30"));
        assertEquals(0, ShizukuCompat.mediaVolume(" media  volume --stream 3 --set 0 "));
        assertEquals(-1, ShizukuCompat.mediaVolume("media volume --stream 2 --set 3"));
        assertEquals(-1, ShizukuCompat.mediaVolume("media volume --stream 3 --set 3; reboot"));
        assertEquals(-1, ShizukuCompat.mediaVolume("media volume --stream 3 --set -1"));
        assertEquals(-1, ShizukuCompat.mediaVolume("media volume --stream 3 --set 1000"));
        assertEquals("volume is 30 in range [0..60]", ShizukuCompat.mediaVolumeOut(30, 60));
    }
}
