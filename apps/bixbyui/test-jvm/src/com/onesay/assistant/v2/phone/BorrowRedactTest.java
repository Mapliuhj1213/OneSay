package com.onesay.assistant.v2.phone;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.privacy.Redactor;

import org.junit.Test;

/** ★ borrow-1112 ③：点屏代理发给模型的读屏文字已脱敏，模型写占位符输入时本地换回真实值；答复给用户看时也换回。 */
public class BorrowRedactTest {

    @Test public void screenTextRedactedAndTypingRestoresRealValue() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("form", FakeScreens.screen("com.x", "Form").text("收件人电话 13800138000").text("验证码：552781").edit("电话", "phone_input"));
        FakeScreens.Script pl = new FakeScreens.Script(
                "{\"steps\":[{\"do\":\"type\",\"id\":{id:电话},\"text\":\"[手机号#1]\"}]}", "{\"done\":\"已填 [手机号#1]，验证码是 [验证码#1]\"}");
        PhoneAgent a = new PhoneAgent(ph, pl, new FakeScreens.Host());
        a.redactor = new Redactor();
        PhoneAgent.Result r = a.run("把收件人电话填进去", null);
        for (String u : pl.users) {
            assertFalse(u, u.contains("13800138000"));
            assertFalse(u, u.contains("552781"));
        }
        assertTrue(pl.users.get(0), pl.users.get(0).contains("[手机号#1]"));
        assertTrue(ph.log.toString(), ph.log.contains("type 13800138000"));
        assertEquals("已填 13800138000，验证码是 552781", r.answer);
        assertFalse("轨迹里没有真实值", r.trace.toString().contains("13800138000"));
        assertTrue(pl.users.get(1), pl.users.get(1).contains("已输入"));
    }

    @Test public void withoutRedactorNothingChanges() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("form", FakeScreens.screen("com.x", "Form").text("电话 13800138000"));
        FakeScreens.Script pl = new FakeScreens.Script("{\"done\":\"看到了\"}");
        new PhoneAgent(ph, pl, new FakeScreens.Host()).run("看", null);
        assertTrue(pl.users.get(0).contains("13800138000"));
    }
}
