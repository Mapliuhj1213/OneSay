package com.onesay.assistant.v2.pclink;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.*;

/**
 * [已知缺陷 P1] 硬规则①「付款 / 转账的最后一下只能你亲手点」靠 LinkPolicy.finalPayLabel 认按钮文字（SensitiveGuard.userMustTap 用它挡机器人代点）。
 * 它只去掉空白 / NBSP / U+200B 后小写匹配，不做 SafetyText 那套归一化：繁体（港台 App 很常见）、西里尔 / 希腊同形字、全角字母、
 * U+200D / U+2060 / U+FEFF 夹在中间，都认不出 —— 机器人就会把它当普通「提交」类按钮，经一次确认后代点。
 * 期望：和简体 / 半角写法一样认成付款最后一下（SafetyText.squash 已有这套归一化，可直接复用）。
 */
@RunWith(Parameterized.class)
public class AdvBugFinalPayLabelTest {

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{{"確認支付"}, {"確認付款"}, {"立即轉賬"}, {"確認轉賬"}, {"提交訂單並支付"}, {"Pаy Now"}, {"ΡAY NOW"},
                {"ＰａｙＮｏｗ"}, {"Ｃｏｎｆｉｒｍ　Ｐａｙｍｅｎｔ"}, {"确认\u200d支付"}, {"确认\u2060支付"}, {"确认\ufeff支付"}, {"Plаce Order"}});
    }

    private final String label;

    public AdvBugFinalPayLabelTest(String label) {
        this.label = label;
    }

    @Test public void recognized() {
        assertTrue("[" + label + "] 没认成付款最后一下", LinkPolicy.finalPayLabel(label));
    }
}
