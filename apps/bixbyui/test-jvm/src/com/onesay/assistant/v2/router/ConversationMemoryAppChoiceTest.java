package com.onesay.assistant.v2.router;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ 综合包（soul × bike-chooser）：会话历史总闸 —— 回答尾巴不进 ConversationMemory（下一轮回灌 / 会话整理都看不到）。 */
public class ConversationMemoryAppChoiceTest {
    @Test public void recordStripsAppChoiceTail() {
        ConversationMemory m = ConversationMemory.newForTest();
        m.record("附近有共享单车吗", "可以用哈啰或美团。\n<app_choice>{\"category\":\"共享单车\",\"store_query\":\"共享单车\",\"apps\":[]}</app_choice>");
        String a = m.snapshot().get(0).answer;
        assertEquals("可以用哈啰或美团。", a);
        assertFalse(a.contains("store_query"));
    }
}
