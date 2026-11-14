package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.SideEffect;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * 找茬 · 令牌绑定的参数摘要（规范化 JSON 的 SHA-256）：同一参数的不同写法（键序、30 与 30.0、嵌套键序）必须当成同一份，
 * 能让人改变动作含义的改写（字符串 "30" 对数字 30、null 对缺省、全角数字、零宽字符、同形字、尾随空格、嵌套里改一个值、
 * 数组换序、大小写）必须当成不同 —— 确认之后被这样改过的参数一律退回「需要确认」。
 * 每条：用 a 签发并批准令牌，再拿 b 去执行。
 */
@RunWith(Parameterized.class)
public class AdvArgsDigestTest {

    @Parameterized.Parameters(name = "{index}: {0} vs {1} same={2}")
    public static Collection<Object[]> data() {
        List<Object[]> out = new ArrayList<Object[]>();
        Set<String> seen = new HashSet<String>();
        String[][] same = {
                {"{\"a\":1,\"b\":2}", "{\"b\":2,\"a\":1}"},
                {"{\"n\":30}", "{\"n\":30.0}"},
                {"{\"n\":-5}", "{\"n\":-5.0}"},
                {"{\"n\":0}", "{\"n\":0.0}"},
                {"{\"o\":{\"x\":1,\"y\":2}}", "{\"o\":{\"y\":2,\"x\":1}}"},
                {"{\"l\":[{\"p\":1,\"q\":2}]}", "{\"l\":[{\"q\":2,\"p\":1}]}"},
                {"{}", "{}"},
                {"{\"t\":\"发给妈妈\"}", "{\"t\":\"\\u53d1\\u7ed9\\u5988\\u5988\"}"},
                {"{\"a\":true,\"b\":false}", "{\"b\":false,\"a\":true}"},
                {"{\"z\":1,\"y\":1,\"x\":1,\"w\":1}", "{\"w\":1,\"x\":1,\"y\":1,\"z\":1}"},
                {"{\"num\":\"10086\",\"text\":\"hi\"}", "{\"text\":\"hi\",\"num\":\"10086\"}"},
                {"{\"deep\":{\"a\":{\"b\":{\"c\":3}}}}", "{\"deep\":{\"a\":{\"b\":{\"c\":3.0}}}}"},
        };
        String[][] diff = {
                {"{\"n\":30}", "{\"n\":\"30\"}"},
                {"{\"n\":30}", "{\"n\":31}"},
                {"{\"n\":30}", "{\"n\":30.5}"},
                {"{\"n\":30}", "{\"n\":-30}"},
                {"{\"a\":null}", "{}"},
                {"{\"a\":null}", "{\"a\":\"null\"}"},
                {"{\"a\":false}", "{\"a\":\"false\"}"},
                {"{\"a\":0}", "{\"a\":false}"},
                {"{\"num\":\"10086\"}", "{\"num\":\"１００８６\"}"},
                {"{\"num\":\"10086\"}", "{\"num\":\"1008\\u200b6\"}"},
                {"{\"num\":\"10086\"}", "{\"num\":\"10086 \"}"},
                {"{\"num\":\"10086\"}", "{\"num\":\" 10086\"}"},
                {"{\"to\":\"pay@example.com\"}", "{\"to\":\"раy@example.com\"}"},
                {"{\"to\":\"Mom\"}", "{\"to\":\"mom\"}"},
                {"{\"o\":{\"x\":1}}", "{\"o\":{\"x\":2}}"},
                {"{\"o\":{\"x\":1}}", "{\"o\":{\"x\":1,\"y\":0}}"},
                {"{\"l\":[1,2]}", "{\"l\":[2,1]}"},
                {"{\"l\":[1,2]}", "{\"l\":[1,2,3]}"},
                {"{\"l\":[]}", "{\"l\":[null]}"},
                {"{\"a\":1}", "{\"b\":1}"},
                {"{\"a\":1}", "{\"A\":1}"},
                {"{\"a\":1}", "{\"a \":1}"},
                {"{\"t\":\"hi\"}", "{\"t\":\"hi\\n\"}"},
                {"{\"t\":\"é\"}", "{\"t\":\"e\\u0301\"}"},
                {"{\"t\":\"a\"}", "{\"t\":[\"a\"]}"},
                {"{\"t\":\"a\"}", "{\"t\":{\"0\":\"a\"}}"},
                {"{\"amt\":100}", "{\"amt\":1000}"},
                {"{\"amt\":0.1}", "{\"amt\":0.10000001}"},
                {"{\"path\":\"a/b\"}", "{\"path\":\"a/../b\"}"},
                {"{\"url\":\"https://a.com\"}", "{\"url\":\"https://a.com/\"}"},
                {"{\"k\":\"\"}", "{}"},
                {"{\"k\":\"\"}", "{\"k\":\" \"}"},
        };
        for (String[] p : same) add(out, seen, p[0], p[1], true);
        for (String[] p : diff) {
            add(out, seen, p[0], p[1], false);
            add(out, seen, p[1], p[0], false);
        }
        return out;
    }

    static void add(List<Object[]> out, Set<String> seen, String a, String b, boolean same) {
        if (!seen.add(a + "|" + b)) throw new IllegalStateException("dup " + a + " " + b);
        out.add(new Object[]{a, b, same});
    }

    private final String a, b;
    private final boolean same;

    public AdvArgsDigestTest(String a, String b, boolean same) {
        this.a = a;
        this.b = b;
        this.same = same;
    }

    @After public void reset() {
        ExecutionGate.setStrictConfirm(true);
    }

    @Test public void boundToArgs() throws Exception {
        Capability cap = new Capability.Builder("adv.digest", "adv").risk(2, true).sideEffect(SideEffect.DEVICE_CONTROL).cmd("echo").build();
        JSONObject ja = new JSONObject(a), jb = new JSONObject(b);
        assertEquals(same, ExecutionGate.digest(ja).equals(ExecutionGate.digest(jb)));
        ExecutionGate.Decision d = ExecutionGate.evaluate(cap, ja, false);
        assertTrue(ExecutionGate.approve(d.token));
        ExecutionResult r = ExecutionGate.authorize(cap, jb, false, d.token);
        if (same) {
            assertNull("同一参数的不同写法应放行", r);
        } else {
            assertNotNull("改过的参数不能放行", r);
            assertEquals(ErrorCode.RISK_GATED, r.code);
            assertTrue(r.detail, r.detail.contains("args changed"));
            assertEquals("没执行令牌仍是已批准", ExecutionGate.TokenState.APPROVED, d.token.state());
            ExecutionGate.reject(d.token);
        }
        ExecutionGate.takeConsumedReceipt();
        ExecutionGate.reject(d.token);   // 已消费 / 已拒绝的再拒一次是空操作；保证不在登记表里留令牌
        assertEquals(ExecutionGate.TokenState.valueOf(same ? "CONSUMED" : "REJECTED"), d.token.state());
    }
}
