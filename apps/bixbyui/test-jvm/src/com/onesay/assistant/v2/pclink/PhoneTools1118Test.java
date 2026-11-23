package com.onesay.assistant.v2.pclink;

import org.junit.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.*;

/** [phone-tools-1118] 电脑对话「按目标操作手机」：默认关、要有目标、类别能放行（危险那一下由手机执行链弹卡）。 */
public class PhoneTools1118Test {
    @Test public void operateIsKnownOffByDefaultAndChecked() {
        assertTrue(PcRxPolicy.known("operate"));
        assertFalse("开源版默认关", PcRxPolicy.defaultOn("operate"));
        assertTrue(PcRxPolicy.dangerous("operate"));
        assertFalse(PcRxPolicy.extra("operate"));
        assertEquals("按目标操作手机", PcRxPolicy.label("operate"));
        assertFalse(PcRxPolicy.sub("operate").isEmpty());
        Map<String, Object> a = new TreeMap<String, Object>();
        assertNotNull(PcRxPolicy.check("operate", a));
        a.put("goal", "打开设置看看系统版本");
        assertNull(PcRxPolicy.check("operate", a));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 501; i++) b.append('字');
        a.put("goal", b.toString());
        assertNotNull(PcRxPolicy.check("operate", a));
    }

    @Test public void operateCategoryAllowsOnPhone() {
        assertEquals(LinkPolicy.Cat.APP_OPERATE, LinkPolicy.phoneCat("operate"));
        LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PHONE, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.APP_OPERATE, new LinkPolicy.Facts());
        assertEquals(LinkPolicy.Verdict.ALLOW, d.verdict);
    }
}
