package com.onesay.assistant.v2.phone;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * ★ sec-audit-1118 S2 / S3 回归：
 * S2 —— mika.dosteps 的 0–999 坐标点（没给 @eN 编号）以前三道闸（付款亲手点 / 敏感步骤 / 污点确认）都拿不到元素，一律放行；
 * S3 —— 读过外部内容后「type 不带回车 + 单独 key enter」= 回车发送，以前污点确认不看 key 这一步。
 */
public class SecAudit1118GateTest {

    static UiTree.El el(int id, String label, String rid, int l, int t, int r, int b) {
        UiTree.El e = new UiTree.El();
        e.id = id;
        e.label = label;
        e.rid = rid;
        e.l = l;
        e.t = t;
        e.r = r;
        e.b = b;
        e.click = true;
        return e;
    }

    static UiTree screen() {
        UiTree t = new UiTree();
        t.screenW = 1080;
        t.screenH = 2220;
        UiTree.El page = el(1, "", "com.shop:id/root", 0, 0, 1080, 2220);
        page.click = false;
        t.els.add(page);
        t.els.add(el(5, "立即支付", "com.shop:id/pay_btn", 300, 2000, 780, 2100));
        t.els.add(el(6, "发送", "com.chat:id/send", 900, 1000, 1060, 1100));
        UiTree.El input = el(7, "输入消息", "com.chat:id/input", 0, 1000, 880, 1100);
        input.edit = true;
        input.focused = true;
        t.els.add(input);
        return t;
    }

    static PhonePlan.Step xy(int x, int y) {
        PhonePlan.Step s = new PhonePlan.Step();
        s.op = "tap";
        s.x = x;
        s.y = y;
        s.target = "继续";
        return s;
    }

    @Test public void xyTapResolvesToInnermostClickable() {
        UiTree t = screen();
        // 「立即支付」中心 (540, 2050) → 0–999：x = 540*999/1079 ≈ 500，y = 2050*999/2219 ≈ 923
        assertSame(t.find(5), SensitiveGuard.target(xy(500, 923), t));
        assertSame(t.find(5), SensitiveGuard.target(byId(5), t));
        assertNull(SensitiveGuard.target(new PhonePlan.Step(), t));
    }

    @Test public void xyTapOnFinalPayButtonMustBeUserTapped() {
        UiTree t = screen();
        assertNotNull("坐标点到「立即支付」上也要用户亲手点", SensitiveGuard.userMustTap(xy(500, 923), t));
        assertNotNull("坐标点到「立即支付」上也算敏感步骤", SensitiveGuard.check(xy(500, 923), t));
    }

    @Test public void xyTapOnSendIsTaintConfirmedEvenIfModelSaysContinue() {
        UiTree t = screen();
        // 「发送」中心 (980, 1050) → 0–999：x ≈ 907，y ≈ 473
        assertNotNull(RiskLabel.taintedSendLike(xy(907, 473), t));
        assertNotNull(SensitiveGuard.check(xy(907, 473), t));
    }

    @Test public void xyTapOnPlainAreaStillAllowed() {
        UiTree t = screen();
        assertNull(SensitiveGuard.userMustTap(xy(500, 100), t));
        assertNull(SensitiveGuard.check(xy(500, 100), t));
        assertNull(RiskLabel.taintedSendLike(xy(500, 100), t));
    }

    @Test public void separateEnterInChatInputNeedsConfirmWhenTainted() {
        UiTree t = screen();
        PhonePlan.Step k = new PhonePlan.Step();
        k.op = "key";
        k.key = "enter";
        assertNotNull(RiskLabel.taintedSendLike(k, t));
    }

    @Test public void separateEnterInSearchBoxOrWithoutFocusStillAllowed() {
        UiTree t = new UiTree();
        UiTree.El q = el(3, "搜索", "com.x:id/search_src_text", 0, 0, 1000, 100);
        q.edit = true;
        q.focused = true;
        t.els.add(q);
        PhonePlan.Step k = new PhonePlan.Step();
        k.op = "key";
        k.key = "enter";
        assertNull(RiskLabel.taintedSendLike(k, t));
        assertNull(RiskLabel.taintedSendLike(k, new UiTree()));
        assertEquals(null, SensitiveGuard.check(k, t));   // 不带污点的回车：维持原决定（搜索回车常见）
    }

    static PhonePlan.Step byId(int id) {
        PhonePlan.Step s = new PhonePlan.Step();
        s.op = "tap";
        s.id = id;
        return s;
    }
}
