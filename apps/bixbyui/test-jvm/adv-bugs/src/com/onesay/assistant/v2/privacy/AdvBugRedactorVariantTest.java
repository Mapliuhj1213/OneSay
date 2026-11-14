package com.onesay.assistant.v2.privacy;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.*;

/**
 * [已知缺陷 P2] 发给云端前的本地脱敏（Redactor）只认 ASCII 数字、ASCII 空格 / 短横分隔、ASCII @：读屏文字里常见的全角数字、
 * 夹在号码中间的零宽字符 / 不换行空格 / 全角空格、全角 ＠ 都原样发出去（模型照样读得懂）。注释的目标是「读屏文字 / 用户原话 / 工具结果里的
 * 手机号…发出去之前换成稳定占位符」。期望：先做 NFKC + 去零宽（SafetyText 已有）再匹配，restore 仍逐字还原。
 * （base64 / URL 编码之类的「编码后」写法属于规则型识别本来就不覆盖的，不算在这里。）
 */
@RunWith(Parameterized.class)
public class AdvBugRedactorVariantTest {

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{{"电话 １３８００１３８０００"}, {"电话 138\u200b0013\u200b8000"}, {"电话 138\u00a00013\u00a08000"},
                {"电话 138\u30000013\u30008000"}, {"电话 1\u200d3800138000"}, {"邮箱 alice＠example.com"}, {"验证码 ４８２９１３"}, {"电话 138\ufeff00138000"}});
    }

    private final String text;

    public AdvBugRedactorVariantTest(String text) {
        this.text = text;
    }

    @Test public void redacted() {
        Redactor r = new Redactor();
        String out = r.redact(text);
        assertTrue("[" + text + "] 原样发出：" + out, Redactor.hasPlaceholder(out));
        assertEquals(text, r.restore(out));
    }
}
