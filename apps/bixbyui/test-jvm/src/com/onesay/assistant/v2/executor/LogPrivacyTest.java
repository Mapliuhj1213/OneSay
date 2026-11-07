package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.util.TextUtil;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * 日志隐私。
 * 实测日志 {@code [MAIL] 当前配置: 邮件已配置 → 311***qq.com @ imap.qq.com:993，凭据长度=16，服务商=qq，代理=直连}
 * 暴露了邮箱前缀、完整 IMAP 服务器与授权码长度。现在日志只允许「已配置/未配置 + 服务商」。
 */
public class LogPrivacyTest {

    @Test public void mailLogSummaryOnlyStateAndProvider() {
        assertEquals("邮件已配置，服务商=qq", MailBridge.logSummary(true, "qq"));
        assertEquals("邮件未配置，服务商=未指定", MailBridge.logSummary(false, ""));
        assertEquals("邮件已配置，服务商=未指定", MailBridge.logSummary(true, null));
        String junk = MailBridge.logSummary(true, "someone@qq.com imap.qq.com");
        assertFalse("服务商字段被塞进地址时也不打", junk.contains("@") || junk.contains("imap"));
        String s = MailBridge.logSummary(true, "gmail");
        for (String bad : new String[]{"@", "imap.", ":993", "凭据长度", "***", "代理"}) {
            assertFalse(bad, s.contains(bad));
        }
    }

    @Test public void secretStateRevealsNothing() {
        assertEquals("未配置", TextUtil.secretState(null));
        assertEquals("未配置", TextUtil.secretState("  "));
        assertEquals("已配置", TextUtil.secretState("sk" + "-0123456789abcdef"));
        assertEquals("长度不同输出相同", TextUtil.secretState("abcd"), TextUtil.secretState("abcdefghijklmnopqrstuvwxyz"));
    }

    static List<File> javaFiles(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.isDirectory()) javaFiles(f, out);
            else if (f.getName().endsWith(".java")) out.add(f);
        }
        return out;
    }

    /** 源码守卫：任何 Log.x(...) / log(cb, ...) 语句都不能带出账号、服务器、凭据长度或密钥前后缀。 */
    @Test public void noLogStatementLeaksAccountHostOrCredentialLength() throws Exception {
        File src = new File(System.getProperty("bixby.root", "."), "app/src");
        List<File> files = javaFiles(src, new ArrayList<File>());
        assertTrue(files.size() > 50);
        Pattern stmt = Pattern.compile("(Log\\.[diwe]\\(|log\\(cb,)([^;]*);", Pattern.DOTALL);
        Pattern bad = Pattern.compile(
                "configInfo\\(|凭据长度|pass\\(ctx\\)|user\\(ctx\\)|host\\(ctx\\)|getRemoteTokenUrl\\(|"
                        + "Key\\(\\)\\.length\\(\\)|Key\\(\\)\\.substring|Token\\(\\)\\.substring|"
                        + "subject=\" \\+ subject|to=\" \\+ to\\b");
        List<String> hits = new ArrayList<String>();
        for (File f : files) {
            String s = new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
            assertFalse(f.getName() + " 仍出现「凭据长度」", s.contains("凭据长度="));
            Matcher m = stmt.matcher(s);
            while (m.find()) {
                if (bad.matcher(m.group(2)).find()) hits.add(f.getName() + ": " + m.group(0).replaceAll("\\s+", " "));
            }
        }
        assertTrue(hits.toString(), hits.isEmpty());
    }

    @Test public void maskHelpersNoLongerKeepPrefixOrSuffix() throws Exception {
        File root = new File(System.getProperty("bixby.root", "."), "app/src/com/onesay/assistant/v2");
        for (String p : new String[]{"search/SearchConfig.java", "asr/AliyunAsrConfig.java"}) {
            String s = new String(Files.readAllBytes(new File(root, p).toPath()), Charset.forName("UTF-8"));
            int i = s.indexOf("private static String mask(");
            assertTrue(p, i > 0);
            String body = s.substring(i, s.indexOf('}', i));
            assertFalse(p + " mask 不应再截取前后缀", body.contains("substring"));
            assertTrue(p, body.contains("secretState"));
        }
    }
}
