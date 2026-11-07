package com.onesay.assistant.v2.vision.screenref;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.vision.screenref.DeixisResolver.Action;
import com.onesay.assistant.v2.vision.screenref.DeixisResolver.Kind;
import com.onesay.assistant.v2.vision.screenref.DeixisResolver.Resolution;

/** [screenref] 指代消解 + 动作映射 + 芯片挑选 + 能力目录。 */
public class DeixisResolverTest {

    static List<ScreenEntity> screen(String... lines) {
        return EntityExtractor.extractFromTexts(Arrays.asList(lines), EntityExtractorTest.NOW, EntityExtractorTest.TZ);
    }

    static final List<ScreenEntity> SMS = screen(
            "【菜鸟驿站】您的中通包裹已到深圳市南山区科技园南区高新南一道8号菜鸟驿站，请凭取件码5-2-1234取件，详询0755-86012345。");
    static final List<ScreenEntity> CHAT = screen(
            "明天下午3点在公司开会，地址：广州市天河区珠江新城华夏路10号富力中心28楼",
            "有事打我电话 13612345678",
            "资料在这 https://pan.example.com/s/1AbC?pwd=x&from=wx",
            "或者去分公司：北京市海淀区中关村大街27号");

    // ---------- 是否指代 ----------
    @Test public void deixisDetection() {
        assertTrue(DeixisResolver.hasDeixis("复制这个"));
        assertTrue(DeixisResolver.hasDeixis("导航去这里"));
        assertTrue(DeixisResolver.hasDeixis("打这个电话"));
        assertTrue(DeixisResolver.hasDeixis("把屏幕上的时间加到日历"));
        assertTrue(DeixisResolver.hasDeixis("刚才那个地址怎么走"));
        assertTrue(DeixisResolver.hasDeixis("复制取件码"));
        assertTrue(DeixisResolver.hasDeixis("查快递"));
        assertFalse(DeixisResolver.hasDeixis("打开蓝牙"));
        assertFalse(DeixisResolver.hasDeixis("导航到天安门"));
        assertFalse(DeixisResolver.hasDeixis("这个周末天气怎么样"));
        assertFalse(DeixisResolver.hasDeixis("这周有什么安排"));
        assertFalse(DeixisResolver.hasDeixis("打电话给妈妈"));
        assertFalse(DeixisResolver.hasDeixis("查快递 SF1234567890123"));
        assertFalse(DeixisResolver.hasDeixis(""));
        assertFalse(DeixisResolver.hasDeixis(null));
    }

    @Test public void verbToAction() {
        assertEquals(Action.NAVIGATE, DeixisResolver.actionOf("导航去这里"));
        assertEquals(Action.NAVIGATE, DeixisResolver.actionOf("去这个地方"));
        assertEquals(Action.CALL, DeixisResolver.actionOf("打这个电话"));
        assertEquals(Action.CALL, DeixisResolver.actionOf("拨一下上面的号码"));
        assertEquals(Action.CALENDAR, DeixisResolver.actionOf("把这个加到日历"));
        assertEquals(Action.CALENDAR, DeixisResolver.actionOf("这个时间提醒我"));
        assertEquals(Action.COPY, DeixisResolver.actionOf("复制这个"));
        assertEquals(Action.OPEN_URL, DeixisResolver.actionOf("打开这个链接"));
        assertEquals(Action.TRACK, DeixisResolver.actionOf("查一下这个单号"));
        assertEquals(Action.EMAIL, DeixisResolver.actionOf("给这个邮箱发邮件"));
        assertEquals(Action.NONE, DeixisResolver.actionOf("这个是什么意思"));
    }

    // ---------- 典型验收语句 ----------
    @Test public void copyThisPrefersPickupCode() {
        Resolution r = DeixisResolver.resolve("复制这个", SMS);
        assertEquals(Kind.RESOLVED, r.kind);
        assertEquals(ScreenEntity.Type.CODE, r.entity.type);
        assertEquals("5-2-1234", r.entity.value);
    }

    @Test public void navigateHereSingleAddress() {
        Resolution r = DeixisResolver.resolve("导航去这里", SMS);
        assertEquals(Kind.RESOLVED, r.kind);
        assertEquals("深圳市南山区科技园南区高新南一道8号菜鸟驿站", r.entity.value);
    }

    @Test public void callThisPhone() {
        Resolution r = DeixisResolver.resolve("打这个电话", SMS);
        assertEquals(Kind.RESOLVED, r.kind);
        assertEquals("075586012345", r.entity.value);
    }

    @Test public void copyAddressByNoun() {
        Resolution r = DeixisResolver.resolve("复制这个地址", SMS);
        assertEquals(ScreenEntity.Type.ADDRESS, r.entity.type);
        Resolution r2 = DeixisResolver.resolve("复制这个号码", SMS);
        assertEquals(ScreenEntity.Type.PHONE, r2.entity.type);   // "号码"不能被当成"码"
    }

    @Test public void twoAddressesAmbiguous() {
        Resolution r = DeixisResolver.resolve("导航去这个地址", CHAT);
        assertEquals(Kind.AMBIGUOUS, r.kind);
        assertEquals(2, r.candidates.size());
        assertTrue(r.candidates.size() <= DeixisResolver.MAX_CANDIDATES);
    }

    @Test public void ordinalAndHintBreakTie() {
        assertEquals("北京市海淀区中关村大街27号", DeixisResolver.resolve("导航去第二个地址", CHAT).entity.value);
        assertEquals("北京市海淀区中关村大街27号", DeixisResolver.resolve("导航去北京那个地址", CHAT).entity.value);
        assertEquals("广州市天河区珠江新城华夏路10号富力中心28楼", DeixisResolver.resolve("去上面第一个地方", CHAT).entity.value);
        assertEquals("北京市海淀区中关村大街27号", DeixisResolver.resolve("导航去最后一个地址", CHAT).entity.value);
    }

    @Test public void followUpPickAfterAmbiguity() {
        Resolution r = DeixisResolver.resolve("导航去这个地址", CHAT);
        assertEquals("北京市海淀区中关村大街27号", DeixisResolver.pickFromCandidates("第二个", r.candidates).value);
        assertEquals("广州市天河区珠江新城华夏路10号富力中心28楼", DeixisResolver.pickFromCandidates("天河那个", r.candidates).value);
        assertNull(DeixisResolver.pickFromCandidates("随便", r.candidates));
    }

    @Test public void calendarFromChat() {
        Resolution r = DeixisResolver.resolve("把这个时间加到日历", CHAT);
        assertEquals(Kind.RESOLVED, r.kind);
        ScreenAction a = ActionMapper.map(r.action, r.entity);
        assertEquals(ScreenRefCapabilities.SIGN_CALENDAR, a.sign);
        assertEquals(String.valueOf(EntityExtractorTest.ms(2026, 10, 8, 15, 0)), a.args.optString("begin_ms"));
        assertEquals(String.valueOf(EntityExtractorTest.ms(2026, 10, 8, 16, 0)), a.args.optString("end_ms"));
        assertTrue(a.args.optString("title").contains("开会"));
    }

    @Test public void noMatchAndNeedsAgent() {
        Resolution r = DeixisResolver.resolve("打开这个链接", SMS);
        assertEquals(Kind.NO_MATCH, r.kind);
        assertEquals(ScreenEntity.Type.URL, r.wantedType);
        Resolution r2 = DeixisResolver.resolve("这个是什么意思", SMS);
        assertEquals(Kind.NEEDS_AGENT, r2.kind);
        Resolution r3 = DeixisResolver.resolve("导航去这里", screen("设置", "蓝牙"));
        assertEquals(Kind.NO_MATCH, r3.kind);
        assertEquals(Kind.NOT_DEIXIS, DeixisResolver.resolve("打开蓝牙", SMS).kind);
    }

    @Test public void implicitTrackingAndCode() {
        List<ScreenEntity> s = screen("顺丰速运 运单号：SF1234567890123 派送中", "取件码 8-8-8888");
        Resolution r = DeixisResolver.resolve("查快递", s);
        assertEquals(Kind.RESOLVED, r.kind);
        ScreenAction a = ActionMapper.map(r.action, r.entity);
        assertEquals("browser.search", a.sign);
        assertEquals("顺丰快递 SF1234567890123", a.args.optString("keyword"));
        assertEquals("8-8-8888", DeixisResolver.resolve("复制取件码", s).entity.value);
    }

    // ---------- 映射 ----------
    @Test public void mappingSignsAndArgs() {
        ScreenAction call = ActionMapper.map(Action.CALL, DeixisResolver.resolve("打这个电话", SMS).entity);
        assertEquals("phone.call", call.sign);
        assertEquals("075586012345", call.args.optString("number"));
        ScreenAction url = ActionMapper.map(Action.OPEN_URL, DeixisResolver.resolve("打开这个链接", CHAT).entity);
        assertEquals(ScreenRefCapabilities.SIGN_OPEN_URL, url.sign);
        assertEquals("https://pan.example.com/s/1AbC?pwd=x&from=wx", url.args.optString("url"));   // & 必须保留
        ScreenAction copy = ActionMapper.map(Action.COPY, DeixisResolver.resolve("复制这个", SMS).entity);
        assertTrue(copy.isLocalCopy());
        assertEquals("复制取件码 5-2-1234", copy.label);
        assertNull(ActionMapper.map(Action.NAVIGATE, call.entity));   // 类型不符
    }

    @Test public void safeUrlRejectsDangerousSchemes() {
        assertNull(ActionMapper.safeUrl("javascript:alert(1)"));
        assertNull(ActionMapper.safeUrl("intent://scan/#Intent;scheme=zxing;end"));
        assertNull(ActionMapper.safeUrl("file:///sdcard/a.html"));
        assertNull(ActionMapper.safeUrl("https://a.com/'; reboot"));
        assertEquals("mailto:a@b.cn", ActionMapper.safeUrl("mailto:a@b.cn"));
    }

    // ---------- 芯片 ----------
    @Test public void chipsForPickupSms() {
        List<ScreenAction> chips = ActionMapper.planChips(SMS);
        assertTrue(chips.size() >= 2 && chips.size() <= 4);
        assertEquals("复制取件码 5-2-1234", chips.get(0).label);
        assertEquals(ScreenRefCapabilities.SIGN_NAV, chips.get(1).sign);
        assertEquals("phone.call", chips.get(2).sign);
    }

    @Test public void chipsCappedAtFourAndNoTimestamps() {
        List<ScreenAction> chips = ActionMapper.planChips(CHAT);
        assertEquals(4, chips.size());
        List<ScreenAction> onlyTimes = ActionMapper.planChips(screen("12:30", "13:45", "昨天"));
        assertTrue("聊天列表时间戳不应出芯片", onlyTimes.isEmpty());
    }

    @Test public void singleEntityGetsCopyFallback() {
        List<ScreenAction> chips = ActionMapper.planChips(screen("客服电话 400-812-3123"));
        assertEquals(2, chips.size());
        assertEquals("phone.call", chips.get(0).sign);
        assertEquals(ScreenRefCapabilities.SIGN_COPY, chips.get(1).sign);
        assertTrue(ActionMapper.planChips(screen("设置", "通用")).isEmpty());
    }

    // ---------- 能力目录 ----------
    @Test public void catalogAndNormalize() throws Exception {
        Capability nav = ScreenRefCapabilities.get(ScreenRefCapabilities.SIGN_NAV);
        assertNotNull(nav);
        assertTrue(nav.cmdTemplate.contains("'geo:0,0?q={address}'"));
        assertEquals(0, nav.risk);
        assertTrue(ScreenRefCapabilities.isLocal(ScreenRefCapabilities.SIGN_COPY));
        assertFalse(ScreenRefCapabilities.isLocal(ScreenRefCapabilities.SIGN_NAV));
        assertFalse(ScreenRefCapabilities.isScreenSign("phone.call"));
        assertEquals(5, ScreenRefCapabilities.all().size());

        JSONObject a = new JSONObject().put("begin_ms", "明天下午3点").put("title", "体检");
        JSONObject n = ScreenRefCapabilities.normalizeArgs(ScreenRefCapabilities.SIGN_CALENDAR, a, EntityExtractorTest.NOW, EntityExtractorTest.TZ);
        assertEquals(String.valueOf(EntityExtractorTest.ms(2026, 10, 8, 15, 0)), n.getString("begin_ms"));
        assertEquals(String.valueOf(EntityExtractorTest.ms(2026, 10, 8, 16, 0)), n.getString("end_ms"));
        assertNull(ScreenRefCapabilities.normalizeArgs(ScreenRefCapabilities.SIGN_CALENDAR, new JSONObject().put("begin_ms", "随便"),
                EntityExtractorTest.NOW, EntityExtractorTest.TZ));
        assertNull(ScreenRefCapabilities.normalizeArgs(ScreenRefCapabilities.SIGN_OPEN_URL, new JSONObject().put("url", "javascript:x"),
                EntityExtractorTest.NOW, EntityExtractorTest.TZ));
        assertEquals("http://t.cn/x", ScreenRefCapabilities.normalizeArgs(ScreenRefCapabilities.SIGN_OPEN_URL,
                new JSONObject().put("url", "t.cn/x"), EntityExtractorTest.NOW, EntityExtractorTest.TZ).getString("url"));
    }
}
