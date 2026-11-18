package com.onesay.assistant.v2.whitelist;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ rel-1118 B21：中文数字写的手机号、[at] / (at) 写法的邮箱也认；普通句子不误判。 */
public class B21CnDigitsEmailTest {

    static boolean has(String s, String kind) { return PrivacyScan.text(s).contains(kind); }

    @Test public void chineseDigitPhone() {
        assertTrue(has("一三八零零一三八零零零", PrivacyScan.PHONE));
        assertTrue(has("我电话幺三九〇〇幺三九〇〇〇", PrivacyScan.PHONE));
        assertTrue(has("号码一三八 零零一三 八零零零", PrivacyScan.PHONE));
        assertTrue(has("零零八六一三八零零一三八零零零", PrivacyScan.PHONE));
    }

    @Test public void chineseDigitNoFalsePositive() {
        assertFalse(has("一二三四五六", PrivacyScan.PHONE));
        assertFalse(has("三四个人一起去", PrivacyScan.PHONE));
        assertFalse(has("二〇二六年十一月十八日", PrivacyScan.PHONE));
        assertFalse(has("一二三四五六七八九零一", PrivacyScan.PHONE));   // 12345678901：不是 1[3-9] 开头
    }

    @Test public void obfuscatedEmail() {
        assertTrue(has("alice[at]example[dot]com", PrivacyScan.EMAIL));
        assertTrue(has("bob (at) mail.qq.com", PrivacyScan.EMAIL));
        assertTrue(has("c.d{AT}corp{dot}cn", PrivacyScan.EMAIL));
        assertTrue(has("x[at]163.com", PrivacyScan.EMAIL));
    }

    @Test public void emailNoFalsePositive() {
        assertFalse(has("meet me at home dot com later", PrivacyScan.EMAIL));
        assertFalse(has("look at this", PrivacyScan.EMAIL));
        assertFalse(has("[at]", PrivacyScan.EMAIL));
    }
}
