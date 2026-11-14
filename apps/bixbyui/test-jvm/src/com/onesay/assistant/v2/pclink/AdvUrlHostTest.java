package com.onesay.assistant.v2.pclink;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.*;

/**
 * [2026-11-14 已修，原「已知缺陷 P2」] LinkPolicy.checkUrl 的空白检查只看 ASCII（&lt; 0x21），主机名再交给 IDN.toASCII（不开 USE_STD3_ASCII_RULES）：
 * 不换行空格 / 全角空格 / 窄空格被 nameprep 映射成普通空格留在主机名里，结果 ok=true、host="a .com"；端口只看是不是 0–5 位数字，
 * 99999 这种超出 65535 的也算合格。注释：「主机名不合法、格式不对 = malformed（坏输入，任何时候都不开）」。
 * 影响：手机上会把这种网址交给浏览器（浏览器自己会失败），「显示给人看的主机」里带空格，易被拿来做视觉混淆。
 */
@RunWith(Parameterized.class)
public class AdvUrlHostTest {

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{{"https://a\u00a0.com/"}, {"https://a.com\u3000/"}, {"https://exa\u2009mple.com/"}, {"https://a.com:99999/"},
                {"https://a.com:65536/"}, {"https://bank.com\u00a0.evil.example/"}});
    }

    private final String url;

    public AdvUrlHostTest(String url) {
        this.url = url;
    }

    @Test public void malformed() {
        LinkPolicy.UrlCheck u = LinkPolicy.checkUrl(url, null);
        assertTrue("[" + url + "] ok=" + u.ok + " host=[" + u.host + "]", u.malformed);
    }
}
