package com.onesay.assistant.v2.pclink;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.*;

/**
 * [2026-11-14 已修，原「已知缺陷 P2」] PcJson 收了不合 JSON 语法的写法：\\u 后面用 Integer.parseInt 解 4 位（接受 +041 / -001）、数字接受前导 + 和前导 0、
 * 1e999 解成 Infinity。注释：「逐字节照抄电脑端，签名对规范串算」——签名是对解析后的规范串算的，不构成签名绕过，但
 * (1) 畸形帧被当成合法帧往下走；(2) 带 1e999 的帧进 PcProtocol.verify 时 stringify 抛 IllegalArgumentException（不是返回 BAD_SIG），
 * 在 PcRx 常连循环「在途指令已满」那条同步分支里没有 try，会把整条常连打断。期望：解析时就拒（IllegalArgumentException），verify 永远返回 Check。
 */
@RunWith(Parameterized.class)
public class AdvPcJsonLenientTest {

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{{"\"\\u+041\""}, {"\"\\u-001\""}, {"+1"}, {"01"}, {"-01"}, {"1e999"}, {"{\"ts\":1e999}"}, {"verify"}});
    }

    private final String in;

    public AdvPcJsonLenientTest(String in) {
        this.in = in;
    }

    @Test public void strict() {
        if (in.equals("verify")) {
            Map<String, Object> body = new TreeMap<String, Object>();
            body.put("sig", "x");
            body.put("ts", Double.POSITIVE_INFINITY);
            PcProtocol.Check c = PcProtocol.verify(new byte[32], "m", "a", "b", body, 0, null);   // 期望返回 BAD_SIG 而不是抛异常
            assertEquals(PcProtocol.Check.BAD_SIG, c);
            return;
        }
        try {
            Object v = PcJson.parse(in);
            fail("[" + in + "] 应拒绝，得到 " + v);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
