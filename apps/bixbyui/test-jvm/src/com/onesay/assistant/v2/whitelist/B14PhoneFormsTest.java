package com.onesay.assistant.v2.whitelist;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ rel-1118 B14：0086 前缀、点分写法的手机号要认出，日志打码不能漏（t20k privacy.scan / scrub 180 条复现）。 */
public class B14PhoneFormsTest {

    @Test public void prefixAndDotFormsDetected() {
        for (String t : new String[]{"008617600784870", "0086 17600784870", "0086-176-0078-4870", "176.0078.4870", "打 180.0346.5092 找我",
                "+86 138 0013 8000", "86 13800138000", "０．０８６ １３８００１３８０００".replace("．", ""), "+86.138.0013.8000"}) {
            assertTrue(t, PrivacyScan.text(t).contains(PrivacyScan.PHONE));
        }
    }

    @Test public void scrubMasksPrefixAndDotForms() {
        for (String n : new String[]{"197.6089.9615", "0086 17600784870", "008617600784870", "0086-176-0078-4870", "+86.138.0013.8000", "138 0013 8000"}) {
            String out = PrivacyScan.scrub("tap send to " + n);
            assertFalse(out, out.replaceAll("[^0-9]", "").contains("0013800") || out.replaceAll("[^0-9]", "").matches(".*\\d{7,}.*"));
        }
    }

    @Test public void noFalsePositivesOnOrdinaryNumbers() {
        for (String t : new String[]{"版本 1.3.9 发布于 2026.11.18", "IP 192.168.1.100", "价格 13.80 元", "订单号 20261116000123", "今天下午三点开会", "温度 23.5 度"}) {
            assertFalse(t, PrivacyScan.text(t).contains(PrivacyScan.PHONE));
        }
    }
}
