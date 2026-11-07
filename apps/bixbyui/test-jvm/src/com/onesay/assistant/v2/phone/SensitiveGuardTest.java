package com.onesay.assistant.v2.phone;

import static org.junit.Assert.*;

import org.junit.Test;

/** ★ fast-screen-v2：敏感步骤只拦真正的提交，不拦导航条目。 */
public class SensitiveGuardTest {

    static String tap(UiTree u, String label) {
        PhonePlan.Step s = new PhonePlan.Step();
        s.op = "tap";
        UiTree.El e = u.findText(label);
        assertNotNull("界面里没有 " + label, e);
        s.id = e.id;
        return SensitiveGuard.check(s, u);
    }

    static String tapButton(String label) {
        return tap(FakeScreens.screen("p", "a").button(label).ui(null), label);
    }

    @Test public void settingsNavigationRowsDoNotTrigger() {
        UiTree u = FakeScreens.screen("com.android.settings", "Settings")
                .row("密码与安全", "锁屏、指纹")
                .row("隐私和安全", null)
                .row("生物识别和安全", null)
                .row("恢复出厂设置", null)
                .row("支付管理", null)
                .row("账户", "三星帐户")
                .row("删除帐户", null)
                .row("锁屏密码", null)
                .row("应用权限", null)
                .ui(null);
        for (String n : new String[]{"密码与安全", "隐私和安全", "生物识别和安全", "恢复出厂设置", "支付管理", "账户", "删除帐户", "应用权限"})
            assertNull(n, tap(u, n));
    }

    @Test public void plainButtonsAndTogglesDoNotTrigger() {
        for (String n : new String[]{"WLAN", "搜索", "返回", "确定", "下一步", "取消", "密码", "我的文件", "相册", "设置"})
            assertNull(n, tapButton(n));
        UiTree u = FakeScreens.screen("p", "a").toggle("自动删除旧文件", false).ui(null);
        assertNull(tap(u, "自动删除旧文件"));
    }

    @Test public void realCommitButtonsTrigger() {
        assertTrue(tapButton("立即支付").contains("付款"));
        assertTrue(tapButton("提交订单").contains("付款"));
        assertTrue(tapButton("确认付款").contains("付款"));
        assertTrue(tapButton("发送").contains("发送"));
        assertTrue(tapButton("发布").contains("发送"));
        assertTrue(tapButton("确认发送").contains("发送"));
        assertTrue(tapButton("Send").contains("发送"));
        assertTrue(tapButton("获取验证码").contains("验证"));
        assertTrue(tapButton("验证").contains("验证"));
        assertTrue(tapButton("删除").contains("删除"));
        assertTrue(tapButton("全部删除").contains("删除"));
        assertTrue(tapButton("重置").contains("删除"));
        assertTrue(tapButton("卸载").contains("删除"));
        assertTrue(tapButton("清除数据").contains("删除"));
        assertTrue(tapButton("登录").contains("登录"));
        assertTrue(tapButton("一键登录").contains("登录"));
        assertTrue(tapButton("同意并继续").contains("登录"));
    }

    @Test public void resourceIdCommitTriggersEvenWithoutText() {
        UiTree u = FakeScreens.screen("com.tencent.mobileqq", "Chat").node("android.widget.ImageButton", "", "com.tencent.mobileqq:id/btnSendMsg", FakeScreens.C).ui(null);
        PhonePlan.Step s = new PhonePlan.Step();
        s.op = "tap";
        s.id = u.els.get(0).id;
        String r = SensitiveGuard.check(s, u);
        assertNotNull(r);
        assertTrue(r, r.contains("发送"));
        // id 是 title / item 这类导航性质的不算
        UiTree v = FakeScreens.screen("p", "a").node("android.widget.TextView", "", "pay_item_title", FakeScreens.C).ui(null);
        s.id = v.els.get(0).id;
        assertNull(SensitiveGuard.check(s, v));
    }

    @Test public void typingIntoSecretFieldsTriggersOthersDont() {
        UiTree u = FakeScreens.screen("p", "a").edit("请输入验证码", "et_code").edit("搜索", "search").password("pwd").ui(null);
        PhonePlan.Step s = new PhonePlan.Step();
        s.op = "type";
        s.text = "1234";
        s.id = u.findText("请输入验证码").id;
        assertNotNull(SensitiveGuard.check(s, u));
        s.id = u.findText("搜索").id;
        assertNull(SensitiveGuard.check(s, u));
        s.id = u.els.get(2).id;
        assertNotNull(SensitiveGuard.check(s, u));
    }

    @Test public void nonTapOpsAndEnterNeverTrigger() {
        UiTree u = FakeScreens.screen("p", "a").button("发送").ui(null);
        for (String op : new String[]{"scroll", "back", "home", "launch", "wait", "swipe"}) {
            PhonePlan.Step s = new PhonePlan.Step();
            s.op = op;
            s.id = 1;
            assertNull(op, SensitiveGuard.check(s, u));
        }
        PhonePlan.Step k = new PhonePlan.Step();
        k.op = "key";
        k.key = "enter";
        assertNull(SensitiveGuard.check(k, u));
    }

    @Test public void longPressOnCommitTriggers() {
        UiTree u = FakeScreens.screen("p", "a").button("删除").ui(null);
        PhonePlan.Step s = new PhonePlan.Step();
        s.op = "long";
        s.id = 1;
        assertNotNull(SensitiveGuard.check(s, u));
    }

    @Test public void navigationHeuristics() {
        assertTrue(SensitiveGuard.isNavigation("密码与安全"));
        assertTrue(SensitiveGuard.isNavigation("恢复出厂设置"));
        assertTrue(SensitiveGuard.isNavigation("这是一段很长的说明文字不是按钮"));
        assertFalse(SensitiveGuard.isNavigation("立即支付"));
        assertFalse(SensitiveGuard.isNavigation("删除"));
        assertEquals("密码与安全", SensitiveGuard.title("密码与安全 · 锁屏、指纹"));
        assertEquals("发送", SensitiveGuard.title("发送（按钮）"));
        assertEquals("btn_send_msg", SensitiveGuard.splitRid("com.x:id/btnSendMsg"));
    }

    // ★ fast-screen-v3：导航放行的两个例外
    static String tapNode(String cls, String text, String rid, int flags) {
        UiTree u = FakeScreens.screen("p", "a").node(cls, text, rid, flags).ui(null);
        PhonePlan.Step s = new PhonePlan.Step();
        s.op = "tap";
        s.id = u.els.get(0).id;
        return SensitiveGuard.check(s, u);
    }

    @Test public void clickableButtonIsNotTreatedAsNavigationRegardlessOfLength() {
        // 长文字 / 名词收尾的可点 Button：照常按提交动词判
        String r = tapNode("android.widget.Button", "确认并立即支付这一笔订单", "", FakeScreens.C);
        assertNotNull(r);
        assertTrue(r, r.contains("付款"));
        assertNotNull(tapNode("android.widget.Button", "恢复出厂设置", "", FakeScreens.C));
        assertNotNull(tapNode("androidx.appcompat.widget.AppCompatButton", "删除帐户与全部数据", "", FakeScreens.C));
        // 可点 Button 但没有提交动词：不拦
        assertNull(tapNode("android.widget.Button", "WLAN 与网络设置", "", FakeScreens.C));
        // 同样的文字，不是 Button（设置列表行 / TextView）：仍按导航放行
        assertNull(tapNode("android.widget.TextView", "确认并立即支付这一笔订单", "", FakeScreens.C));
        assertNull(tapNode("android.widget.LinearLayout", "恢复出厂设置", "", FakeScreens.C));
        // 不可点的 Button 类：按导航放行
        assertNull(tapNode("android.widget.Button", "恢复出厂设置", "", 0));
    }

    @Test public void strongResourceIdBlocksNavigationLookingLabelsOfAnyLength() {
        String[][] cases = {
                {"com.x:id/sendEntry", "发送"}, {"com.x:id/pay_center_title", "付款"}, {"com.x:id/submitRow", "发送"},
                {"com.x:id/confirm_area", "发送"}, {"com.x:id/loginItem", "登录"}, {"com.x:id/verifyHint", "验证"},
                {"com.x:id/delete_account_entry", "删除"}};
        for (String[] c : cases) {
            String r = tapNode("android.widget.LinearLayout", "这是一个很长很长的设置条目文字与说明", c[0], FakeScreens.C);
            assertNotNull(c[0], r);
            assertTrue(c[0] + " → " + r, r.contains(c[1]));
        }
        // 子串匹配：resend / autoConfirm 也算
        assertNotNull(tapNode("android.widget.TextView", "隐私和安全", "com.x:id/autoConfirmSetting", FakeScreens.C));
        // 普通导航 id：放行
        assertNull(tapNode("android.widget.LinearLayout", "这是一个很长很长的设置条目文字与说明", "android:id/title", FakeScreens.C));
        assertTrue(SensitiveGuard.strongRid("com.x:id/btnResend"));
        assertFalse(SensitiveGuard.strongRid("com.x:id/title"));
        assertFalse(SensitiveGuard.strongRid(""));
    }
}
