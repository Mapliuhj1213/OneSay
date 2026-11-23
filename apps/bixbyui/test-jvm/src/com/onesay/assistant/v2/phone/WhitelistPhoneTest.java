package com.onesay.assistant.v2.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.pclink.PcLanClient;
import com.onesay.assistant.v2.pclink.PcRx;
import com.onesay.assistant.v2.whitelist.AppWhitelist;
import com.onesay.assistant.v2.whitelist.PhoneWhitelistGate;
import com.onesay.assistant.v2.whitelist.WhitelistState;

import java.io.File;
import java.util.Map;
import java.util.TreeMap;

import org.junit.After;
import org.junit.Test;

/**
 * [app-whitelist-1116] 手机端接线：phone.operate 每一步的白名单分层（放行 / 支付仍问 / 隐私外发仍问 / 读过隐私后外发问 / 密钥类和外部内容照旧）、
 * ExecutionGate 的带包名工具、电脑推来的同步、存盘。规则用例在 whitelist/AppWhitelistSharedTest（和电脑同一份）。
 */
public class WhitelistPhoneTest {

    @After public void reset() { WhitelistState.setForTest(null); }

    static WhitelistState wl(String... pkgs) {
        WhitelistState s = new WhitelistState(null, "ph-t");
        for (String p : pkgs) assertNull(s.add(AppWhitelist.ANDROID, p, ""));
        return s;
    }

    static PhoneAgent agent(FakeScreens.Phone ph, FakeScreens.Script pl, FakeScreens.Host h, WhitelistState s) {
        PhoneAgent a = new PhoneAgent(ph, pl, h);
        a.whitelist = s;
        return a;
    }

    @Test public void whitelistedAppSkipsSendAndDeleteConfirms() {
        for (boolean on : new boolean[]{false, true}) {
            FakeScreens.Phone ph = new FakeScreens.Phone();
            ph.add("a", FakeScreens.screen("com.example.chat", "A").button("删除").button("发送").edit("说点什么", "input"));
            FakeScreens.Script pl = new FakeScreens.Script(
                    "{\"steps\":[{\"do\":\"tap\",\"id\":{id:删除}}]}",
                    "{\"steps\":[{\"do\":\"type\",\"id\":{id:说点什么},\"text\":\"今晚七点见\"}]}",
                    "{\"steps\":[{\"do\":\"tap\",\"id\":{id:发送}}]}", "{\"done\":\"好\"}");
            FakeScreens.Host h = new FakeScreens.Host();
            PhoneAgent a = agent(ph, pl, h, on ? wl("com.example.chat") : null);
            a.run("x", null);
            if (on) {
                assertEquals(h.confirms.toString(), 0, h.confirms.size());
                assertEquals(2, a.wlPassed);
                assertEquals(2, a.wlTask.passes);
            } else {
                assertEquals("不启用白名单：和改前一样问两次（删除、发送）", 2, h.confirms.size());
            }
        }
        // 别的 App 不受影响
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("a", FakeScreens.screen("com.other", "A").button("删除"));
        FakeScreens.Host h = new FakeScreens.Host();
        agent(ph, new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:删除}}]}", "{\"done\":\"好\"}"), h, wl("com.example.chat")).run("x", null);
        assertEquals(1, h.confirms.size());
    }

    @Test public void payStillConfirmsAndFinalPayStillUserTap() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("a", FakeScreens.screen("com.example.shop", "A").button("去结算").button("立即支付"));
        FakeScreens.Script pl = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:去结算}}]}",
                "{\"steps\":[{\"do\":\"tap\",\"id\":{id:立即支付}}]}", "{\"done\":\"好\"}");
        FakeScreens.Host h = new FakeScreens.Host();
        PhoneAgent a = agent(ph, pl, h, wl("com.example.shop"));
        PhoneAgent.Result r = a.run("x", null);
        assertEquals(h.confirms.toString(), 1, h.confirms.size());
        assertTrue(h.confirms.get(0), h.confirms.get(0).contains("付款"));
        assertTrue("最后一下仍要本人点", r.trace.toString().contains("pay_final_user_tap"));
        assertEquals(0, a.wlPassed);
    }

    @Test public void typingPrivacyStillConfirmsInWhitelist() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("a", FakeScreens.screen("com.example.form", "A").edit("手机号", "phone_input").edit("备注", "note"));
        FakeScreens.Script pl = new FakeScreens.Script(
                "{\"steps\":[{\"do\":\"type\",\"id\":{id:手机号},\"text\":\"13812345678\"}]}",
                "{\"steps\":[{\"do\":\"type\",\"id\":{id:备注},\"text\":\"[身份证#1]\"}]}",
                "{\"steps\":[{\"do\":\"type\",\"id\":{id:备注},\"text\":\"放门口\"}]}", "{\"done\":\"好\"}");
        FakeScreens.Host h = new FakeScreens.Host();
        PhoneAgent a = agent(ph, pl, h, wl("com.example.form"));
        a.run("x", null);
        assertEquals(h.confirms.toString(), 2, h.confirms.size());
        assertTrue(h.confirms.get(0), h.confirms.get(0).contains("隐私资料（手机号）"));
        assertTrue(h.confirms.get(1), h.confirms.get(1).contains("已脱敏的个人信息"));
        assertEquals(2, a.wlAsked);
    }

    @Test public void afterReadingPrivacyOutboundConfirms() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("a", FakeScreens.screen("com.example.chat", "A").text("对方的卡号 6222 0212 3456 7890 128").button("转发").button("收藏"));
        FakeScreens.Script pl = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:收藏}}]}",
                "{\"steps\":[{\"do\":\"tap\",\"id\":{id:转发}}]}", "{\"done\":\"好\"}");
        FakeScreens.Host h = new FakeScreens.Host();
        PhoneAgent a = agent(ph, pl, h, wl("com.example.chat"));
        a.run("x", null);
        assertEquals(h.confirms.toString(), 1, h.confirms.size());
        assertTrue(h.confirms.get(0), h.confirms.get(0).contains("读到过隐私资料（银行卡号）"));
        assertTrue(a.wlTask.read.contains("bank_card"));
    }

    @Test public void secretsAndExternalContentStillConfirm() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("a", FakeScreens.screen("com.example.chat", "A").edit("请输入密码", "et_pwd").button("发表"));
        FakeScreens.Script pl = new FakeScreens.Script("{\"steps\":[{\"do\":\"type\",\"id\":{id:请输入密码},\"text\":\"abc\"}]}",
                "{\"steps\":[{\"do\":\"tap\",\"id\":{id:发表}}]}", "{\"done\":\"好\"}");
        FakeScreens.Host h = new FakeScreens.Host();
        PhoneAgent a = agent(ph, pl, h, wl("com.example.chat"));
        a.tainted = true;
        a.run("x", null);
        assertEquals(h.confirms.toString(), 2, h.confirms.size());
        assertTrue(h.confirms.get(0).contains("密码"));
        assertTrue(h.confirms.get(1).contains("外部内容"));
    }

    /** 2026-11-16 用户定：电脑推来的白名单 App，手机上确认前照旧逐步问。 */
    @Test public void pushedFromPcNotEffectiveUntilConfirmed() {
        WhitelistState s = new WhitelistState(null, "ph-t");
        AppWhitelist pc = new AppWhitelist();
        pc.add("android", "com.example.chat", "聊天", "pc-1", "pc", 100);
        s.merge(pc.toJson("pc-1"), "pc");
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("a", FakeScreens.screen("com.example.chat", "A").button("删除"));
        FakeScreens.Script pl = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:删除}}]}", "{\"done\":\"好\"}");
        FakeScreens.Host h = new FakeScreens.Host();
        agent(ph, pl, h, s).run("x", null);
        assertEquals("待确认：照旧问", 1, h.confirms.size());
        assertNull(s.confirm("android", "com.example.chat"));
        FakeScreens.Phone ph2 = new FakeScreens.Phone();
        ph2.add("a", FakeScreens.screen("com.example.chat", "A").button("删除"));
        FakeScreens.Host h2 = new FakeScreens.Host();
        agent(ph2, new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:删除}}]}", "{\"done\":\"好\"}"), h2, s).run("x", null);
        assertEquals("确认后放行", 0, h2.confirms.size());
    }

    /** 2026-11-16 用户定：卸载白名单里的 App 必须确认（界面上点「卸载」、app.uninstall 工具）。 */
    @Test public void uninstallInWhitelistStillConfirms() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("a", FakeScreens.screen("com.example.chat", "A").button("删除").button("卸载"));
        FakeScreens.Script pl = new FakeScreens.Script("{\"steps\":[{\"do\":\"tap\",\"id\":{id:删除}}]}",
                "{\"steps\":[{\"do\":\"tap\",\"id\":{id:卸载}}]}", "{\"done\":\"好\"}");
        FakeScreens.Host h = new FakeScreens.Host();
        PhoneAgent a = agent(ph, pl, h, wl("com.example.chat"));
        a.run("x", null);
        assertEquals(h.confirms.toString(), 1, h.confirms.size());
        assertTrue(h.confirms.get(0), h.confirms.get(0).contains("卸载"));
        assertEquals("删除照样放行", 1, a.wlPassed);
        WhitelistState s = wl("com.example.maps");
        assertFalse(PhoneWhitelistGate.capability(s, "app.uninstall", "DEVICE_CONTROL", "com.example.maps", "{\"package\":\"com.example.maps\"}", false, false));
        assertTrue(s.recentAudit(10).toString(), s.recentAudit(10).toString().contains("UNINSTALL"));
    }

    /** 规则保持：读过外部内容后单纯往框里打字不加问，发送仍问（与不在白名单时同口径）。 */
    @Test public void externalTypingNotAskedButSendAsked() {
        FakeScreens.Phone ph = new FakeScreens.Phone();
        ph.add("a", FakeScreens.screen("com.example.chat", "A").edit("说点什么", "input").button("发送"));
        FakeScreens.Script pl = new FakeScreens.Script("{\"steps\":[{\"do\":\"type\",\"id\":{id:说点什么},\"text\":\"今晚七点见\"}]}",
                "{\"steps\":[{\"do\":\"tap\",\"id\":{id:发送}}]}", "{\"done\":\"好\"}");
        FakeScreens.Host h = new FakeScreens.Host();
        PhoneAgent a = agent(ph, pl, h, wl("com.example.chat"));
        a.tainted = true;
        a.run("x", null);
        assertEquals(h.confirms.toString(), 1, h.confirms.size());
        assertTrue(h.confirms.get(0), h.confirms.get(0).contains("外部内容"));
        assertTrue(a.whitelist.recentAudit(10).toString().contains("READ_EXTERNAL"));
    }

    @Test public void passLimit() {
        WhitelistState s = wl("com.example.chat");
        PhoneWhitelistGate.Task t = new PhoneWhitelistGate.Task();
        t.limit = 2;
        UiTree cur = FakeScreens.screen("com.example.chat", "A").button("删除").ui(new UiTree.Ids());
        PhonePlan.Step st = new PhonePlan.Step();
        st.op = "tap";
        st.id = cur.els.get(0).id;
        assertNull(PhoneWhitelistGate.step(s, t, st, cur, "这一步是删除"));
        assertNull(PhoneWhitelistGate.step(s, t, st, cur, "这一步是删除"));
        String r = PhoneWhitelistGate.step(s, t, st, cur, "这一步是删除");
        assertNotNull(r);
        assertTrue(r, r.contains("已放行 2 步"));
        assertTrue(s.recentAudit(10).toString().contains("android:com.example.chat\tWHITELIST\tALLOW\ttap"));
    }

    @Test public void capabilityGate() {
        WhitelistState s = wl("com.example.maps");
        assertTrue(PhoneWhitelistGate.capability(s, "app.launch", "DEVICE_CONTROL", "com.example.maps", "{\"package\":\"com.example.maps\"}", false, false));
        assertFalse("污点不放行", PhoneWhitelistGate.capability(s, "app.launch", "DEVICE_CONTROL", "com.example.maps", "{}", true, false));
        assertFalse("对外发送不放行", PhoneWhitelistGate.capability(s, "share.text", "PUBLIC_COMMS", "com.example.maps", "{}", false, false));
        assertFalse("红卡 / 通信类不放行", PhoneWhitelistGate.capability(s, "phone.call", "DEVICE_CONTROL", "com.example.maps", "{}", false, true));
        assertFalse("ROOT 不放行", PhoneWhitelistGate.capability(s, "x", "ROOT", "com.example.maps", "{}", false, false));
        assertFalse("参数里有隐私不放行", PhoneWhitelistGate.capability(s, "app.launch", "DEVICE_CONTROL", "com.example.maps", "{\"q\":\"someone@example.com\"}", false, false));
        assertFalse("不在白名单", PhoneWhitelistGate.capability(s, "app.launch", "DEVICE_CONTROL", "com.other", "{}", false, false));
        String audit = s.recentAudit(20).toString();
        assertFalse(audit, audit.contains("someone@example.com"));
    }

    @Test public void syncFromPcAndStore() throws Exception {
        File d = java.nio.file.Files.createTempDirectory("wlp").toFile();
        WhitelistState s = new WhitelistState(d, "ph-1");
        WhitelistState.setForTest(s);
        AppWhitelist pc = new AppWhitelist();
        pc.add("windows", "code.exe", "VS Code", "pc-1", "pc", 100);
        Map<String, Object> a = new TreeMap<String, Object>();
        a.put("doc", pc.toJson("pc-1"));
        PcLanClient.Result r = PcRx.whitelistSync(a);
        assertTrue(r.text, r.ok);
        assertFalse("电脑推来的在手机上先待确认（2026-11-16 用户定）", s.contains("windows", "Code.exe"));
        assertEquals(1, s.pending().size());
        assertNull(s.confirm("windows", "Code.exe"));
        assertTrue(s.contains("windows", "Code.exe"));
        assertNotNull("确认过不再确认", s.confirm("windows", "Code.exe"));
        assertTrue("确认记录存在本机", new WhitelistState(d, "ph-1").contains("windows", "code.exe"));
        assertTrue(s.recentAudit(10).toString().contains("confirm"));
        pc.merge(AppWhitelist.parse(String.valueOf(r.data.get("doc"))));
        assertEquals(pc.toJson("x"), new WhitelistState(d, "ph-1").json().replace("\"device\":\"ph-1\"", "\"device\":\"x\""));
        a.put("doc", "{坏");
        assertFalse(PcRx.whitelistSync(a).ok);
        // 存盘读坏 → 当空、不合并
        java.io.FileOutputStream o = new java.io.FileOutputStream(new File(d, "app_whitelist.json"));
        o.write("{坏的".getBytes("UTF-8"));
        o.close();
        WhitelistState b = new WhitelistState(d, "ph-1");
        WhitelistState.setForTest(b);
        assertTrue(b.broken());
        assertFalse(b.contains("windows", "code.exe"));
        a.put("doc", pc.toJson("pc-1"));
        assertFalse(PcRx.whitelistSync(a).ok);
        assertNotNull(b.add("android", "com.a.b", ""));
        b.reset();
        assertNull(b.add("android", "com.a.b", ""));
        // 远程单独加白名单 = 自授权；同步指令归查询
        assertEquals(com.onesay.assistant.v2.pclink.LinkPolicy.Cat.SELF_AUTHORIZE, com.onesay.assistant.v2.pclink.LinkPolicy.pcCat("whitelist_add"));
        assertEquals(com.onesay.assistant.v2.pclink.LinkPolicy.Cat.QUERY, com.onesay.assistant.v2.pclink.LinkPolicy.pcCat("whitelist_sync"));
    }
}
