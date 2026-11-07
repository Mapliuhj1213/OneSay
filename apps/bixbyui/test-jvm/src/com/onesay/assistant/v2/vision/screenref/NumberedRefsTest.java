package com.onesay.assistant.v2.vision.screenref;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/** ★ lane C3-5：识屏结果编号 +「第三个」指代。 */
public class NumberedRefsTest {

    @After public void tearDown() {
        NumberedRefs.reset();
    }

    @Test public void labels_are_circled() {
        assertEquals("① 复制取件码 1234", NumberedRefs.label(0, "复制取件码 1234"));
        assertEquals("③ 导航到 北京", NumberedRefs.label(2, "导航到 北京"));
        assertEquals("21. x", NumberedRefs.label(20, "x"));
    }

    @Test public void picks_pure_ordinal_utterances() {
        assertEquals(2, NumberedRefs.pick("第三个", 4));
        assertEquals(2, NumberedRefs.pick("第三个。", 4));
        assertEquals(2, NumberedRefs.pick("第 3 个", 4));
        assertEquals(1, NumberedRefs.pick("选第二个", 4));
        assertEquals(1, NumberedRefs.pick("就第二个吧", 4));
        assertEquals(0, NumberedRefs.pick("1号", 4));
        assertEquals(1, NumberedRefs.pick("二号", 4));
        assertEquals(3, NumberedRefs.pick("最后一个", 4));
        assertEquals(3, NumberedRefs.pick("就最后那个吧", 4));
        assertEquals(2, NumberedRefs.pick("打开第三个", 4));
        assertEquals(2, NumberedRefs.pick("打开第三个链接", 4));
        assertEquals(0, NumberedRefs.pick("第一条", 4));
        assertEquals(9, NumberedRefs.pick("第十个", 12));
        assertEquals(11, NumberedRefs.pick("第十二个", 12));
    }

    @Test public void out_of_range_and_non_ordinals() {
        assertEquals(NumberedRefs.OUT_OF_RANGE, NumberedRefs.pick("第五个", 3));
        assertEquals(NumberedRefs.NONE, NumberedRefs.pick("第三个地址导航过去", 4));
        assertEquals(NumberedRefs.NONE, NumberedRefs.pick("第三季度财报", 4));
        assertEquals(NumberedRefs.NONE, NumberedRefs.pick("打开第三个应用然后发微信给妈妈", 4));
        assertEquals(NumberedRefs.NONE, NumberedRefs.pick("三个", 4));
        assertEquals(NumberedRefs.NONE, NumberedRefs.pick("第零个", 4));
        assertEquals(NumberedRefs.NONE, NumberedRefs.pick("第三个", 0));
        assertEquals(NumberedRefs.NONE, NumberedRefs.pick(null, 3));
    }

    @Test public void latest_list_owns_the_ordinal() {
        NumberedRefs.publish(NumberedRefs.OWNER_ERRAND, 5, 1000, 600000);
        assertTrue(NumberedRefs.isLatest(NumberedRefs.OWNER_ERRAND, 2000));
        NumberedRefs.publish(NumberedRefs.OWNER_SCREEN, 3, 3000, 60000);
        assertFalse("识屏芯片更近：跑腿不接", NumberedRefs.isLatest(NumberedRefs.OWNER_ERRAND, 4000));
        assertTrue(NumberedRefs.isLatest(NumberedRefs.OWNER_SCREEN, 4000));
        assertFalse("过期", NumberedRefs.isLatest(NumberedRefs.OWNER_SCREEN, 3000 + 60001));
        NumberedRefs.withdraw(NumberedRefs.OWNER_ERRAND);   // 不是自己的不撤
        assertTrue(NumberedRefs.isLatest(NumberedRefs.OWNER_SCREEN, 4000));
        NumberedRefs.withdraw(NumberedRefs.OWNER_SCREEN);
        assertFalse(NumberedRefs.isLatest(NumberedRefs.OWNER_SCREEN, 4000));
    }

    /** 接线守卫：芯片编号 / 按编号选中 / 歧义追问也认「2 号」/ 跑腿来源编号。 */
    @Test public void wiring() throws Exception {
        String h = ScreenRefHooksPresenceTest.src("com/onesay/assistant/v2/vision/screenref/ScreenRefHooks.java");
        int pick = h.indexOf("int idx = NumberedRefs.pick(command, shown.size())");
        int hide = h.indexOf("sSubmittedSinceWake = true;\n            hideChips(svc);");
        assertTrue("按编号选芯片", pick > 0);
        assertTrue("必须在 hideChips 清空芯片之前", hide > pick);
        assertTrue(h.contains("NumberedRefs.publish(NumberedRefs.OWNER_SCREEN"));
        assertTrue(h.contains("NumberedRefs.label(i, a.label)"));
        assertTrue(h.contains("int k = NumberedRefs.pick(command, sPendingCands.size())"));
        String e = ScreenRefHooksPresenceTest.src("com/onesay/assistant/v2/kernel/plugins/ErrandPlugin.java");
        assertTrue(e.contains("ErrandHooks.interceptOrdinal(svc, command)"));
        String eh = ScreenRefHooksPresenceTest.src("com/onesay/assistant/v2/errand/ErrandHooks.java");
        assertTrue(eh.contains("NumberedRefs.OWNER_ERRAND"));
    }
}
