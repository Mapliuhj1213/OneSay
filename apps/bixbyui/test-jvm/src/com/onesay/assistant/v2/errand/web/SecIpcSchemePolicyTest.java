package com.onesay.assistant.v2.errand.web;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 2026-10-25 安全修复 IPC-4：跑腿接手页的外部跳转白名单。 */
public class SecIpcSchemePolicyTest {

    static ExternalSchemePolicy.Decision d(String u) {
        return ExternalSchemePolicy.decide(u);
    }

    @Test public void knownPaySchemesAllowed() {
        ExternalSchemePolicy.Decision x = d("alipays://platformapi/startapp?appId=20000067&url=x");
        assertTrue(x.allowed);
        assertEquals("alipays://platformapi/startapp?appId=20000067&url=x", x.viewUri);
        assertNull(x.pkg);
        assertTrue(d("weixin://wap/pay?prepayid=1").allowed);
        assertTrue(d("taobao://item.taobao.com/item.htm?id=1").allowed);
        assertTrue(d("openapp.jdmobile://virtual?params=%7B%7D").allowed);
        assertTrue("scheme 大小写不敏感", d("AliPays://x").allowed);
    }

    @Test public void otherSchemesBlocked() {
        for (String u : new String[]{"tel:10086", "sms:10086?body=x", "market://details?id=evil", "file:///sdcard/x",
                "content://com.onesay.assistant/x", "javascript:alert(1)", "evilapp://pwn", "mailto:a@b.c", "data:text/html,x",
                "", "noscheme", ":x", "alipays\n://x"}) {
            assertFalse(u, d(u).allowed);
        }
        assertFalse(d(null).allowed);
    }

    @Test public void intentUriRebuiltWithoutExtras() {
        ExternalSchemePolicy.Decision x = d("intent://platformapi/startapp?saId=10000007#Intent;scheme=alipays;package=com.eg.android.AlipayGphone;S.browser_fallback_url=https%3A%2F%2Fevil.example;end");
        assertTrue(x.reason, x.allowed);
        assertEquals("alipays://platformapi/startapp?saId=10000007", x.viewUri);
        assertEquals("com.eg.android.AlipayGphone", x.pkg);
        assertFalse("extras / fallback 不带过去", x.viewUri.contains("fallback") || x.viewUri.contains("Intent;"));
        ExternalSchemePolicy.Decision y = d("intent://wap/pay#Intent;scheme=weixin;end");
        assertTrue(y.allowed);
        assertEquals("weixin://wap/pay", y.viewUri);
        assertTrue("action=VIEW 可以", d("intent://x#Intent;scheme=taobao;action=android.intent.action.VIEW;end").allowed);
    }

    @Test public void intentUriAttacksRejected() {
        assertFalse("component", d("intent://x#Intent;scheme=alipays;component=com.onesay.assistant/.v2.ui.MailConfirmActivity;end").allowed);
        assertFalse("selector", d("intent://x#Intent;scheme=alipays;SEL;component=com.evil/.A;end").allowed);
        assertFalse("未知 package", d("intent://x#Intent;scheme=alipays;package=com.evil;end").allowed);
        assertFalse("scheme 不在白名单", d("intent://x#Intent;scheme=evilapp;end").allowed);
        assertFalse("没 scheme（会按 package 拉 launcher）", d("intent:#Intent;package=com.tencent.mm;end").allowed);
        assertFalse("非 VIEW action", d("intent://x#Intent;scheme=alipays;action=android.intent.action.SEND;end").allowed);
        assertFalse("http scheme 的 intent 也不放（应由 WebView 自己加载）", d("intent://evil.example#Intent;scheme=https;end").allowed);
        assertFalse("缺 end", d("intent://x#Intent;scheme=alipays;").allowed);
        assertFalse("缺 #Intent", d("intent://x").allowed);
    }

    @Test public void activityUsesPolicy() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String a = new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/errand/ErrandBrowserActivity.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(a.contains("ExternalSchemePolicy.decide(u)"));
        assertFalse("不再 Intent.parseUri 网页给的 intent:", a.contains("Intent.parseUri("));
        assertTrue(a.contains("new Intent(Intent.ACTION_VIEW, Uri.parse(d.viewUri))"));
    }
}
