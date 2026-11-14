package com.onesay.assistant.v2.net;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.*;

/**
 * [2026-11-14 已修，原「已知缺陷 P2」] 备用模型的地址只由 LlmFallback.Backup.usable() 核对（LlmClient.send 对备用客户端跳过 keyAllowedFor），而 usable()
 * 只看前缀 startsWith("https://") || startsWith("http://127.0.0.1:")：
 * <ul>
 *   <li>http://127.0.0.1:1@evil.example/… 前缀对上，真实主机是 evil.example —— Key 以明文 http 发往外部主机，release 包也一样
 *       （主模型地址那边 http 只给 debug 包）；</li>
 *   <li>https://u:p@host、带 ? 查询串 / # 片段的也算可用，比主模型地址（MainModelEndpoint.check）宽。</li>
 * </ul>
 * 设置界面只要求以 https:// 开头，所以第一条要改 prefs 才能触发；第二条从界面就能填。期望：与主模型同一套校验（release 只收 https、
 * 不带用户信息 / 查询串 / 片段；回环地址按解析后的主机判断）。
 */
@RunWith(Parameterized.class)
public class AdvBackupUrlTest {

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{{"http://127.0.0.1:1@evil.example/v1/chat/completions"}, {"http://127.0.0.1:80@10.0.0.8/v1/chat/completions"},
                {"https://user:pw@api.example.com/v1/chat/completions"}, {"https://api.example.com/v1/chat/completions?key=x"},
                {"https://api.example.com/v1/chat/completions#x"}, {"https://api.example.com /v1"}});
    }

    private final String url;

    public AdvBackupUrlTest(String url) {
        this.url = url;
    }

    @Test public void rejected() {
        LlmFallback.Backup b = new LlmFallback.Backup(url, "m", "", new LlmClient.KeyProvider() {
            @Override public String apiKey() { return "sk-test"; }
        });
        assertFalse("[" + url + "] 不该当成可用的备用地址", b.usable());
    }
}
