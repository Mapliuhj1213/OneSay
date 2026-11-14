package com.onesay.assistant.v2.privacy;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.router.AgentLoop;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** ★ borrow-1112 ③：本地脱敏（稳定占位符、本地换回、映射只在内存）。 */
public class RedactorTest {

    static final String ID = "11010519491231002X";   // GB 11643 示例号，校验码对得上
    static final String CARD = "6222 0212 3456 7894";  // 构造的 Luhn 合法号（不是真卡）

    @Test public void checksums() {
        assertTrue(Redactor.idChecksumOk(ID));
        assertFalse(Redactor.idChecksumOk("110105194912310021"));
        assertTrue(Redactor.luhnOk(CARD.replace(" ", "")));
        assertFalse(Redactor.luhnOk("6222021234567890"));
    }

    @Test public void replacesEachKindWithStablePlaceholder() {
        Redactor r = new Redactor();
        String in = "张三 13800138000，邮箱 Zhang.San@Example.com，身份证 " + ID + "，卡号 " + CARD + "，验证码：482913，订单号 20261112000001";
        String out = r.redact(in);
        assertFalse(out, out.contains("13800138000"));
        assertFalse(out, out.contains("Example.com"));
        assertFalse(out, out.contains(ID));
        assertFalse(out, out.contains("7894"));
        assertFalse(out, out.contains("482913"));
        assertTrue(out, out.contains("[手机号#1]"));
        assertTrue(out, out.contains("[邮箱#1]"));
        assertTrue(out, out.contains("[身份证#1]"));
        assertTrue(out, out.contains("[银行卡#1]"));
        assertTrue(out, out.contains("[验证码#1]"));
        assertTrue("订单号不是卡号（Luhn 不过）也不是手机号", out.contains("20261112000001"));
        // 同一值同一占位符（不同写法也算同一个）
        assertEquals("[手机号#1] 和 [手机号#1]", r.redact("+86 138 0013 8000 和 13800138000"));
        assertEquals("[手机号#2]", r.redact("13900001111"));
        // 换回
        assertEquals(in, r.restore(out));
        assertEquals("拨 13800138000", r.restore("拨 手机号#1"));
        assertEquals("认不出的留着 [手机号#9]", r.restore("认不出的留着 [手机号#9]"));
        assertFalse("toString 不暴露值", r.toString().contains("138"));
        r.clear();
        assertEquals(0, r.size());
        assertEquals("[手机号#5]", r.restore("[手机号#5]"));
    }

    @Test public void doesNotTouchOrdinaryNumbers() {
        Redactor r = new Redactor();
        String s = "电量 85%，2026-11-12 18:30，价格 1299 元，取件码 5-2-1234";
        assertEquals(s, r.redact(s));
        assertEquals(0, r.size());
        assertNull(r.redact(null));
    }

    @Test public void placeholdersAreNotReRedacted() {
        Redactor r = new Redactor();
        String a = r.redact("验证码 1234");
        assertEquals(a, r.redact(a));
    }

    @Test public void defaultOnForBothEditions() {
        assertTrue(PrivacySettings.REDACT_DEFAULT);
        assertTrue(PrivacySettings.REDACT.def);
    }

    static final class FakeLlm implements AgentLoop.Llm {
        final java.util.List<String> sent = new java.util.ArrayList<String>();
        String reply;

        @Override public boolean hasApiKey() {
            return true;
        }

        @Override public JSONObject call(JSONArray messages, JSONArray tools, int maxTokens) {
            sent.add(messages.toString());
            try {
                return new JSONObject(reply);
            } catch (Exception e) {
                return null;
            }
        }
    }

    @Test public void agentLoopWrapperRedactsOutgoingAndRestoresToolArgs() throws Exception {
        FakeLlm f = new FakeLlm();
        f.reply = "{\"content\":\"好的，发给 [手机号#1]\",\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"sms_send\",\"arguments\":\"{\\\"number\\\":\\\"[手机号#1]\\\",\\\"text\\\":\\\"验证码是 [验证码#1]\\\"}\"}}]}";
        RedactingLlm w = new RedactingLlm(f, new RedactingLlm.Gate() {
            @Override public boolean on() {
                return true;
            }
        });
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "user").put("content", "把验证码 662211 发给 13800138000"));
        JSONObject out = w.call(msgs, null, 100);
        assertFalse(f.sent.get(0), f.sent.get(0).contains("13800138000"));
        assertFalse(f.sent.get(0), f.sent.get(0).contains("662211"));
        assertTrue("原数组不改", msgs.toString().contains("13800138000"));
        String args = out.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments");
        JSONObject a = new JSONObject(args);
        assertEquals("13800138000", a.getString("number"));
        assertEquals("验证码是 662211", a.getString("text"));
        assertEquals("好的，发给 13800138000", out.getString("content"));
        // 同一任务（同一数组）第二轮：同值同占位符
        msgs.put(new JSONObject().put("role", "tool").put("content", "已发送到 13800138000"));
        w.call(msgs, null, 100);
        assertTrue(f.sent.get(1), f.sent.get(1).contains("已发送到 [手机号#1]"));
        // 换任务：新映射
        JSONArray other = new JSONArray();
        other.put(new JSONObject().put("role", "user").put("content", "13900001111"));
        w.call(other, null, 100);
        assertTrue(f.sent.get(2), f.sent.get(2).contains("[手机号#1]"));
    }

    @Test public void wrapperOffPassesThrough() throws Exception {
        FakeLlm f = new FakeLlm();
        f.reply = "{\"content\":\"x\"}";
        RedactingLlm w = new RedactingLlm(f, new RedactingLlm.Gate() {
            @Override public boolean on() {
                return false;
            }
        });
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "user").put("content", "13800138000"));
        w.call(msgs, null, 10);
        assertTrue(f.sent.get(0).contains("13800138000"));
    }
}
