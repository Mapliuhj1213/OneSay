package com.onesay.assistant.v2.roles;

import org.junit.Test;
import static org.junit.Assert.*;

/** ★ 11-04 I：未安装软件白盒审计——常见 App 名能反查包名（安装卡不再是死代码）、兜底话术不甩锅。 */
public class MissingAppAuditTest {
    @Test public void knownAppsResolve() {
        assertEquals("com.ss.android.ugc.aweme", KnownApps.pkgOf("抖音"));
        assertEquals("com.xingin.xhs", KnownApps.pkgOf("小红书"));
        assertEquals("com.taobao.idlefish", KnownApps.pkgOf(" 闲鱼 "));
        assertEquals("tv.danmaku.bili", KnownApps.pkgOf("B站"));
        assertEquals("com.lemon.lv", KnownApps.pkgOf("剪映APP"));
        assertNull(KnownApps.pkgOf("不存在的应用"));
        assertNull(KnownApps.pkgOf(""));
        for (String n : new String[]{"微信", "淘宝", "京东", "拼多多", "快手", "微博", "知乎", "钉钉", "飞书", "腾讯会议"})
            assertTrue(n, RoleCatalog.PKG.matcher(KnownApps.pkgOf(n)).matches());
    }

    @Test public void planForNoLongerBlamesUser() {

        String obs = "{\"ok\":false,\"code\":\"NOT_FOUND_APP\",\"result\":\"抱歉，手机上没有找到应用「闲鱼」\"}";
        String t = com.onesay.assistant.v2.router.AgentLoopTestAccess.planFor(obs, "打开闲鱼");
        assertFalse(t, t.contains("确认这个应用已经安装"));
        assertTrue(t, t.contains("闲鱼") && t.contains("安装卡"));
        String t2 = com.onesay.assistant.v2.router.AgentLoopTestAccess.planFor("{\"code\":\"NOT_FOUND_APP\",\"result\":\"抱歉，手机上没有找到应用「某某工具」\"}", "打开某某工具");
        assertFalse(t2, t2.contains("安装卡"));
    }
}
