package com.onesay.assistant.v2.s1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.TimeZone;

import org.junit.Test;

/** [s1] #10 取件码/取餐码识别（验证码绝不出卡）+ 浮卡仓库（去重、过期、持久化）。 */
public class PickupCodeDetectorTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");
    static final long NOW = 1791590400000L;   // 2026-10-08 左右，取值无关紧要

    static List<PickupCodeDetector.Pickup> d(String body) {
        return PickupCodeDetector.detect(body, NOW, TZ);
    }

    @Test public void cainiaoPickupWithStation() {
        List<PickupCodeDetector.Pickup> r = d("【菜鸟驿站】您的中通包裹已到科技园南区菜鸟驿站，请凭取件码5-2-1234取件，详询0755-86012345。");
        assertEquals(1, r.size());
        assertEquals("5-2-1234", r.get(0).code);
        assertEquals("取件码", r.get(0).kind);
        assertNotNull(r.get(0).station);
        assertTrue(r.get(0).station, r.get(0).station.contains("驿站"));
    }

    @Test public void fengchaoLockerCode() {
        List<PickupCodeDetector.Pickup> r = d("【丰巢】您的包裹已存入南山花园3栋丰巢柜，取件码【83920417】，请及时取件。");
        assertEquals(1, r.size());
        assertEquals("83920417", r.get(0).code);
        assertTrue(r.get(0).station.contains("丰巢"));
    }

    @Test public void mealCode() {
        List<PickupCodeDetector.Pickup> r = d("【瑞幸咖啡】您的订单已制作完成，取餐码 A123，请到门店取餐台取餐。");
        assertEquals(1, r.size());
        assertEquals("A123", r.get(0).code);
        assertEquals("取餐码", r.get(0).kind);
    }

    @Test public void signatureUsedWhenNoStationName() {
        List<PickupCodeDetector.Pickup> r = d("【兔喜生活】取件码 6-3-5678，请凭码取件。");
        assertEquals(1, r.size());
        assertEquals("兔喜生活", r.get(0).station);
    }

    // ---------- 验证码（OTP）绝不出卡 ----------
    @Test public void otpMessagesNeverProduceCards() {
        assertTrue(d("【某银行】您的验证码为 482913，5分钟内有效，请勿泄露。").isEmpty());
        assertTrue(d("【淘宝】验证码 123456，您正在登录，切勿告知他人。").isEmpty());
        assertTrue(d("Your verification code is 908172").isEmpty());
        assertTrue(d("【支付宝】校验码 5566，您正在进行支付操作。").isEmpty());
        assertTrue(d("动态密码 778899，用于转账确认").isEmpty());
        assertTrue(PickupCodeDetector.looksLikeOtp("【某银行】您的验证码为 482913，请勿泄露。"));
        assertFalse(PickupCodeDetector.looksLikeOtp("【丰巢】取件码 12345678"));
    }

    @Test public void mixedMessageKeepsOnlyPickupCode() {
        List<PickupCodeDetector.Pickup> r = d("【菜鸟】包裹已到驿站，取件码 3-1-0042；如需改约，验证码 567812（请勿泄露）。");
        assertEquals(1, r.size());
        assertEquals("3-1-0042", r.get(0).code);
    }

    @Test public void couponAndNoiseIgnored() {
        assertTrue(d("【美团】您的兑换码 8812-3341 已到账").isEmpty());
        assertTrue(d("取件码是多少？我忘了").isEmpty());
        assertTrue(d("今天天气不错").isEmpty());
        assertTrue(d("").isEmpty());
        assertTrue(d(null).isEmpty());
    }

    @Test public void duplicateCodesDeduped() {
        List<PickupCodeDetector.Pickup> r = d("取件码 5-2-1234，再次提醒：取件码 5-2-1234，驿站营业至 21 点");
        assertEquals(1, r.size());
    }

    // ---------- 仓库 ----------
    @Test public void storeDedupDismissExpire() {
        PickupCardStore s = new PickupCardStore();
        PickupCodeDetector.Pickup p = new PickupCodeDetector.Pickup("5-2-1234", "取件码", "菜鸟驿站");
        PickupCardStore.Card c = s.add(p, 1000L, 10000L);
        assertNotNull(c);
        assertEquals("取件码 5-2-1234 · 菜鸟驿站", c.display());
        assertNull("同码重复到达不重复弹出", s.add(p, 2000L, 10000L));
        assertEquals(1, s.active(2000L).size());
        assertTrue(s.dismiss(c.id));
        assertFalse(s.dismiss(c.id));
        assertEquals(0, s.active(3000L).size());
        assertNull("关掉的码在有效期内再来也不弹", s.add(p, 4000L, 10000L));
        assertEquals(0, s.active(12000L).size());
        assertEquals(1, s.purge(12000L));
        assertNotNull("过期后同码再来 = 新的一次取件", s.add(p, 13000L, 10000L));
    }

    @Test public void storeCapAndNextExpiry() {
        PickupCardStore s = new PickupCardStore();
        for (int i = 0; i < PickupCardStore.MAX_CARDS + 2; i++) {
            s.add(new PickupCodeDetector.Pickup("10" + i + "0", "取件码", null), 1000L + i, 100000L);
        }
        assertEquals(PickupCardStore.MAX_CARDS, s.size());
        assertEquals("取件码 1020", s.active(2000L).get(0).display());
        assertEquals(1000L + 2 + 100000L, s.nextExpiry(2000L));
    }

    @Test public void storeSurvivesRestartViaJson() {
        PickupCardStore s = new PickupCardStore();
        PickupCardStore.Card a = s.add(new PickupCodeDetector.Pickup("A123", "取餐码", "瑞幸"), 1000L, 50000L);
        s.add(new PickupCodeDetector.Pickup("83920417", "取件码", null), 1000L, 5000L);
        s.dismiss(a.id);
        PickupCardStore r = PickupCardStore.fromJson(s.toJson(), 2000L);
        assertEquals(2, r.size());
        assertEquals(1, r.active(2000L).size());
        assertEquals("83920417", r.active(2000L).get(0).code);
        assertNull("恢复后仍记得已关闭", r.add(new PickupCodeDetector.Pickup("A123", "取餐码", "瑞幸"), 3000L, 50000L));
        PickupCardStore expired = PickupCardStore.fromJson(s.toJson(), 9000L);
        assertEquals(1, expired.size());
        assertEquals(0, PickupCardStore.fromJson("not json", 0L).size());
        assertEquals(0, PickupCardStore.fromJson(null, 0L).size());
    }
}
