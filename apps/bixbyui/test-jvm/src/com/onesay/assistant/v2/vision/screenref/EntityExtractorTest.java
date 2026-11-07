package com.onesay.assistant.v2.vision.screenref;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import org.junit.Test;

/** [screenref] 实体抽取：电话/地址/时间/链接/单号/取件码/邮箱/金额（≥30 例）。 */
public class EntityExtractorTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");
    /** 2026-10-07 15:00 星期三（Asia/Shanghai） */
    static final long NOW;
    static {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(2026, Calendar.OCTOBER, 7, 15, 0, 0);
        NOW = c.getTimeInMillis();
    }

    static List<ScreenEntity> ex(String... lines) {
        return EntityExtractor.extractFromTexts(Arrays.asList(lines), NOW, TZ);
    }

    static List<ScreenEntity> of(List<ScreenEntity> all, ScreenEntity.Type t) {
        List<ScreenEntity> r = new ArrayList<ScreenEntity>();
        for (ScreenEntity e : all) if (e.type == t) r.add(e);
        return r;
    }

    static ScreenEntity one(ScreenEntity.Type t, String... lines) {
        List<ScreenEntity> r = of(ex(lines), t);
        assertEquals("应恰好 1 个 " + t + "，实际 " + ex(lines), 1, r.size());
        return r.get(0);
    }

    static void none(ScreenEntity.Type t, String... lines) {
        List<ScreenEntity> r = of(ex(lines), t);
        assertTrue("不应识别出 " + t + "，实际 " + r, r.isEmpty());
    }

    static long ms(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    // ---------------- 电话 ----------------
    @Test public void phoneMobilePlain() { assertEquals("13800138000", one(ScreenEntity.Type.PHONE, "联系人：张先生 13800138000").value); }
    @Test public void phoneMobileSpaced() { assertEquals("13912345678", one(ScreenEntity.Type.PHONE, "手机 139 1234 5678").value); }
    @Test public void phoneMobileDashedPlus86() { assertEquals("15012345678", one(ScreenEntity.Type.PHONE, "+86-150-1234-5678").value); }
    @Test public void phoneLandline() {
        ScreenEntity e = one(ScreenEntity.Type.PHONE, "前台电话：020-38881234（9:00-18:00）");
        assertEquals("02038881234", e.value);
        assertEquals("座机", e.subLabel);
    }
    @Test public void phoneLandlineParen() { assertEquals("075586668888", one(ScreenEntity.Type.PHONE, "订餐热线（0755）86668888").value); }
    @Test public void phone400() { assertEquals("4008123123", one(ScreenEntity.Type.PHONE, "客服 400-812-3123").value); }
    @Test public void phoneServiceShort() { assertEquals("95533", one(ScreenEntity.Type.PHONE, "如有疑问请致电95533").value); }
    @Test public void phoneNotInsideTrackingNumber() {
        none(ScreenEntity.Type.PHONE, "顺丰快递 SF1380013800012 已签收");
        assertEquals("SF1380013800012", one(ScreenEntity.Type.TRACKING, "顺丰快递 SF1380013800012 已签收").value);
    }
    @Test public void phoneNotFromOrderNo() { none(ScreenEntity.Type.PHONE, "订单编号 202610071380013800"); }

    // ---------------- 取件码 / 验证码 ----------------
    @Test public void pickupCodeCainiao() {
        List<ScreenEntity> all = ex("【菜鸟驿站】您的快递已到深圳市南山区科技园南区高新南一道8号菜鸟驿站，请凭取件码5-2-1234取件。");
        ScreenEntity code = of(all, ScreenEntity.Type.CODE).get(0);
        assertEquals("5-2-1234", code.value);
        assertEquals("取件码", code.subLabel);
        ScreenEntity addr = of(all, ScreenEntity.Type.ADDRESS).get(0);
        assertEquals("深圳市南山区科技园南区高新南一道8号菜鸟驿站", addr.value);
    }
    @Test public void pickupCodeFengchao() {
        ScreenEntity e = one(ScreenEntity.Type.CODE, "【丰巢】凭取件码【83920417】至万科城北门丰巢柜取件");
        assertEquals("83920417", e.value);
    }
    @Test public void pickupCodePing() { assertEquals("A12-3456", one(ScreenEntity.Type.CODE, "请凭A12-3456到驿站领取").value); }
    @Test public void mealCode() {
        ScreenEntity e = one(ScreenEntity.Type.CODE, "取餐号：A108，请留意叫号");
        assertEquals("A108", e.value);
        assertEquals("取餐码", e.subLabel);
    }
    @Test public void verifyCode() {
        ScreenEntity e = one(ScreenEntity.Type.CODE, "【某银行】验证码 384912，5分钟内有效，请勿泄露。");
        assertEquals("384912", e.value);
        assertEquals("验证码", e.subLabel);
        none(ScreenEntity.Type.DATETIME, "【某银行】验证码 384912，5分钟内有效，请勿泄露。");
    }
    @Test public void codeLabelInSeparateNode() {
        ScreenEntity e = one(ScreenEntity.Type.CODE, "取件码", "6-3-2019");
        assertEquals("6-3-2019", e.value);
    }
    @Test public void codeQuestionIsNotCode() { none(ScreenEntity.Type.CODE, "取件码是多少？"); }

    // ---------------- 快递单号 ----------------
    @Test public void trackingLabeled() {
        ScreenEntity e = one(ScreenEntity.Type.TRACKING, "中通快递 运单号：75312345678901");
        assertEquals("75312345678901", e.value);
        assertEquals("中通", e.subLabel);
    }
    @Test public void trackingJT() { assertEquals("极兔", one(ScreenEntity.Type.TRACKING, "JT3012345678901 派送中").subLabel); }
    @Test public void trackingDigitsNeedContext() {
        none(ScreenEntity.Type.TRACKING, "会员号 773012345678");
        assertEquals("773012345678", one(ScreenEntity.Type.TRACKING, "您的包裹 773012345678 正在派送").value);
    }

    // ---------------- URL / 邮箱 ----------------
    @Test public void urlHttps() { assertEquals("https://example.com/a?b=1&c=2", one(ScreenEntity.Type.URL, "详情见 https://example.com/a?b=1&c=2。").value); }
    @Test public void urlWww() { assertEquals("http://www.12306.cn", one(ScreenEntity.Type.URL, "官网www.12306.cn购票").value); }
    @Test public void urlShortBare() { assertEquals("http://t.cn/A6xYz9", one(ScreenEntity.Type.URL, "点击 t.cn/A6xYz9 查看").value); }
    @Test public void email() {
        List<ScreenEntity> all = ex("简历请投递 hr@example.com.cn 谢谢");
        assertEquals("hr@example.com.cn", one(ScreenEntity.Type.EMAIL, "简历请投递 hr@example.com.cn 谢谢").value);
        assertTrue(of(all, ScreenEntity.Type.URL).isEmpty());
    }

    // ---------------- 金额 ----------------
    @Test public void amountYen() { assertEquals("1288.00", one(ScreenEntity.Type.AMOUNT, "实付款 ¥1,288.00").value); }
    @Test public void amountYuan() { assertEquals("35.5", one(ScreenEntity.Type.AMOUNT, "本月话费 35.5元").value); }
    @Test public void amountNotYuandan() { none(ScreenEntity.Type.AMOUNT, "1元旦活动"); }

    // ---------------- 日期时间 ----------------
    @Test public void dateTimeFull() {
        ScreenEntity e = one(ScreenEntity.Type.DATETIME, "会议时间：2026年10月9日 14:30-16:00");
        assertEquals(ms(2026, 10, 9, 14, 30), e.beginMs);
        assertEquals(ms(2026, 10, 9, 16, 0), e.endMs);
        assertFalse(e.allDay);
    }
    @Test public void dateMonthDayAfternoon() { assertEquals(ms(2026, 10, 12, 15, 0), one(ScreenEntity.Type.DATETIME, "10月12日下午3点开会").beginMs); }
    @Test public void dateTomorrowHalf() { assertEquals(ms(2026, 10, 8, 9, 30), one(ScreenEntity.Type.DATETIME, "明天上午9点半体检").beginMs); }
    @Test public void dateTonightChinese() { assertEquals(ms(2026, 10, 7, 20, 0), one(ScreenEntity.Type.DATETIME, "今晚八点老地方见").beginMs); }
    @Test public void dateMingwan() { assertEquals(ms(2026, 10, 8, 20, 15), one(ScreenEntity.Type.DATETIME, "明晚8点15分").beginMs); }
    @Test public void dateWeekday() { assertEquals(ms(2026, 10, 9, 19, 0), one(ScreenEntity.Type.DATETIME, "周五晚上7点聚餐").beginMs); }
    @Test public void dateNextMonday() { assertEquals(ms(2026, 10, 12, 10, 0), one(ScreenEntity.Type.DATETIME, "下周一 10:00 面试").beginMs); }
    @Test public void dateOnlyAllDay() {
        ScreenEntity e = one(ScreenEntity.Type.DATETIME, "截止日期 2026-10-20");
        assertTrue(e.allDay);
        assertEquals(ms(2026, 10, 20, 0, 0), e.beginMs);
    }
    @Test public void dateIsoSlash() { assertEquals(ms(2026, 11, 1, 8, 5), one(ScreenEntity.Type.DATETIME, "出发 2026/11/1 08:05").beginMs); }
    @Test public void timeOnlyLowConfidence() {
        ScreenEntity e = one(ScreenEntity.Type.DATETIME, "12:45");
        assertTrue(e.confidence < 0.5);
    }
    @Test public void bareDianIsNotTime() { none(ScreenEntity.Type.DATETIME, "这3点建议很重要"); }

    // ---------------- 地址 ----------------
    @Test public void addressFull() {
        assertEquals("广东省广州市天河区天河路385号太古汇", one(ScreenEntity.Type.ADDRESS, "广东省广州市天河区天河路385号太古汇").value);
    }
    @Test public void addressLabeled() {
        assertEquals("北京市朝阳区建国路88号SOHO现代城A座1203", one(ScreenEntity.Type.ADDRESS, "收货地址：北京市朝阳区建国路88号SOHO现代城A座1203").value);
    }
    @Test public void addressSpaced() {
        assertEquals("上海浦东新区世纪大道100号", one(ScreenEntity.Type.ADDRESS, "上海 浦东新区 世纪大道100号").value);
    }
    @Test public void addressDistrictPoi() { assertEquals("南山区科技园", one(ScreenEntity.Type.ADDRESS, "南山区科技园").value); }
    @Test public void addressStripVerbAndTail() {
        assertEquals("杭州市西湖区文三路90号东部软件园", one(ScreenEntity.Type.ADDRESS, "周六上午请到杭州市西湖区文三路90号东部软件园集合").value);
    }
    @Test public void addressInMapCard() {
        List<ScreenEntity> all = ex("海底捞火锅(万象城店)", "4.8分 · 火锅 · 人均¥120", "深圳市罗湖区宝安南路1881号华润万象城5层", "营业中 10:00-次日02:00");
        assertEquals("深圳市罗湖区宝安南路1881号华润万象城5层", of(all, ScreenEntity.Type.ADDRESS).get(0).value);
        assertEquals("120", of(all, ScreenEntity.Type.AMOUNT).get(0).value);
    }
    @Test public void notAddressSentence() {
        none(ScreenEntity.Type.ADDRESS, "我明天去万达广场吃饭吗");
        none(ScreenEntity.Type.ADDRESS, "设置");
        none(ScreenEntity.Type.ADDRESS, "微信支付");
    }

    // ---------------- 综合 ----------------
    @Test public void mixedSmsAllTypes() {
        List<ScreenEntity> all = ex("【顺丰速运】您的快件SF1234567890123已由快递员李师傅（13711112222）派送至广州市海珠区新港中路397号，"
                + "请于10月8日18:00前领取，详询95338。");
        assertEquals(1, of(all, ScreenEntity.Type.TRACKING).size());
        List<ScreenEntity> phones = of(all, ScreenEntity.Type.PHONE);
        assertEquals(2, phones.size());
        assertEquals("13711112222", phones.get(0).value);
        assertEquals("95338", phones.get(1).value);
        assertEquals(ms(2026, 10, 8, 18, 0), of(all, ScreenEntity.Type.DATETIME).get(0).beginMs);
        assertEquals("广州市海珠区新港中路397号", of(all, ScreenEntity.Type.ADDRESS).get(0).value);
    }
    @Test public void dedupSameValue() {
        assertEquals(1, of(ex("13800138000", "拨打 13800138000"), ScreenEntity.Type.PHONE).size());
    }
    @Test public void emptyInput() {
        assertTrue(ex().isEmpty());
        assertTrue(EntityExtractor.extractFromTexts(null, NOW, TZ).isEmpty());
        assertNull(null);
        assertNotNull(EntityExtractor.extractLine("", 0, NOW, TZ));
    }
}
