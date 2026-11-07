package com.onesay.assistant.v2.vision.screenref;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

/** [screenref] uiautomator dump 解析（夹具文件 test-jvm/fixtures/screenref/*.xml）+ ScreenContext 构建。 */
public class UiDumpParserTest {

    static final String OWN = "com.onesay.assistant";

    static String fixture(String name) throws Exception {
        // 由测试类所在位置（test-jvm/build/test）回溯到 test-jvm/fixtures
        File loc = com.onesay.assistant.v2.testutil.TestRoot.start(UiDumpParserTest.class);
        File dir = loc;
        for (int i = 0; i < 6 && dir != null; i++) {
            File f = new File(dir, "fixtures/screenref/" + name);
            if (f.exists()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            dir = dir.getParentFile();
        }
        File f = new File("test-jvm/fixtures/screenref/" + name);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static ScreenContext ctx(String name, String dumpsys) throws Exception {
        return ScreenContext.fromDump(fixture(name), OWN, dumpsys, EntityExtractorTest.NOW, EntityExtractorTest.TZ, 123);
    }

    @Test public void smsSingleLineDumpParses() throws Exception {
        UiDumpParser.Result r = UiDumpParser.parse(fixture("sms_pickup.xml"), OWN);
        assertEquals(6, r.totalNodes);
        assertEquals("com.samsung.android.messaging", r.rootPackage);
        UiNode body = r.nodes.get(3);
        assertTrue(body.text.startsWith("【菜鸟驿站】"));
        assertTrue(body.clickable);
        assertEquals(60, body.left);
        assertEquals(420, body.top);
        assertEquals(1200, body.right);
        assertEquals(760, body.bottom);
        assertEquals("com.samsung.android.messaging:id/content_text_view", body.resourceId);
        assertEquals("输入信息", r.nodes.get(5).contentDesc);
    }

    @Test public void smsContextEntities() throws Exception {
        ScreenContext c = ctx("sms_pickup.xml",
                "  mResumedActivity: ActivityRecord{5d3e2f1 u0 com.samsung.android.messaging/com.android.mms.ui.ConversationComposer t812}");
        assertEquals(ScreenContext.SRC_UIAUTOMATOR, c.source);
        assertEquals("com.samsung.android.messaging", c.pkg);
        assertEquals("com.android.mms.ui.ConversationComposer", c.activity);
        assertEquals("5-2-1234", c.entitiesOf(ScreenEntity.Type.CODE).get(0).value);
        assertEquals("深圳市南山区科技园南区高新南一道8号菜鸟驿站", c.entitiesOf(ScreenEntity.Type.ADDRESS).get(0).value);
        assertEquals("075586012345", c.entitiesOf(ScreenEntity.Type.PHONE).get(0).value);
        assertTrue(c.entitiesOf(ScreenEntity.Type.PHONE).get(0).screenY > 0);
        assertFalse(c.needsVl);
    }

    @Test public void amapLabelAndValueInSeparateNodes() throws Exception {
        ScreenContext c = ctx("amap_poi.xml", null);
        assertEquals("com.autonavi.minimap", c.pkg);   // 无 dumpsys 时用根节点包名
        assertEquals("深圳市罗湖区宝安南路1881号华润万象城5层", c.entitiesOf(ScreenEntity.Type.ADDRESS).get(0).value);
        // content-desc 里的电话也要识别
        assertEquals("075582668888", c.entitiesOf(ScreenEntity.Type.PHONE).get(0).value);
    }

    @Test public void wechatMultipleAddresses() throws Exception {
        ScreenContext c = ctx("wechat_chat.xml", null);
        List<ScreenEntity> addrs = c.entitiesOf(ScreenEntity.Type.ADDRESS);
        assertEquals(2, addrs.size());
        assertEquals("广州市天河区珠江新城华夏路10号富力中心28楼", addrs.get(0).value);
        assertEquals("北京市海淀区中关村大街27号", addrs.get(1).value);
        assertTrue(addrs.get(0).screenY < addrs.get(1).screenY);
        assertEquals(1, c.entitiesOf(ScreenEntity.Type.URL).size());
        assertEquals(1, c.entitiesOf(ScreenEntity.Type.DATETIME).size());
    }

    @Test public void passwordAndSecretsRedacted() throws Exception {
        UiDumpParser.Result r = UiDumpParser.parse(fixture("login_password.xml"), OWN);
        for (UiNode n : r.nodes) {
            assertFalse("密码节点必须丢弃", n.text.contains("••"));
            assertFalse("支付密码 id 节点必须丢弃", n.text.equals("123456"));
            assertFalse("银行卡号必须打码", n.text.replace(" ", "").contains("6222021234567890128"));
        }
        assertTrue(r.redacted >= 3);
        boolean masked = false;
        for (UiNode n : r.nodes) if (n.text.contains("****0128")) masked = true;
        assertTrue(masked);
        // &amp; 解码
        boolean amp = false;
        for (UiNode n : r.nodes) if (n.text.contains("95588 & 在线客服")) amp = true;
        assertTrue(amp);
    }

    @Test public void ownPanelOnlyMeansFallback() throws Exception {
        ScreenContext c = ctx("own_panel.xml", null);
        assertTrue(c.needsVl);
        assertEquals(ScreenContext.SRC_NONE, c.source);
        assertTrue(c.entities.isEmpty());
        assertTrue(c.error.contains("自家面板"));
    }

    @Test public void truncatedDumpStillYieldsEarlierNodes() throws Exception {
        UiDumpParser.Result r = UiDumpParser.parse(fixture("truncated.xml"), OWN);
        assertTrue(r.nodes.size() >= 3);
        assertEquals("订单详情 <已发货>", r.nodes.get(1).text);
        assertTrue(r.nodes.get(2).text.contains("\n"));   // &#10; 换行
        ScreenContext c = ctx("truncated.xml", null);
        assertEquals("SF1234567890123", c.entitiesOf(ScreenEntity.Type.TRACKING).get(0).value);
    }

    @Test public void webviewWithoutTextNeedsVl() throws Exception {
        ScreenContext c = ctx("webview_empty.xml", null);
        assertTrue(c.needsVl);
        assertEquals("com.UCMobile", c.pkg);
    }

    @Test public void nullOrEmptyDump() {
        ScreenContext c = ScreenContext.fromDump(null, OWN, null, 0, EntityExtractorTest.TZ, 0);
        assertTrue(c.needsVl);
        assertNull(c.pkg);
        assertEquals(0, UiDumpParser.parse("", OWN).nodes.size());
        assertEquals(0, UiDumpParser.parse("ERROR: could not get idle state.", OWN).nodes.size());
    }

    @Test public void resumedActivityParsing() {
        assertArrayEquals(new String[] {"com.tencent.mm", "com.tencent.mm.ui.LauncherUI"},
                UiDumpParser.parseResumedActivity("    mResumedActivity: ActivityRecord{3a1b2c u0 com.tencent.mm/.ui.LauncherUI t123}"));
        assertArrayEquals(new String[] {"com.autonavi.minimap", "com.autonavi.map.activity.NewMapActivity"},
                UiDumpParser.parseResumedActivity("mFocusedActivity: ActivityRecord{1 u0 com.autonavi.minimap/com.autonavi.map.activity.NewMapActivity t9}"));
        assertNull(UiDumpParser.parseResumedActivity("nothing here"));
    }

    @Test public void compactJsonAndHint() throws Exception {
        ScreenContext c = ctx("sms_pickup.xml", null);
        JSONObject o = c.toCompactJson(8, 5);
        assertTrue(o.getBoolean("available"));
        assertEquals("uiautomator", o.getString("source"));
        assertTrue(o.getJSONArray("entities").length() >= 3);
        assertTrue(o.getJSONArray("texts").length() <= 5);
        String hint = c.promptHint(6);
        assertTrue(hint.contains("取件码：5-2-1234"));
        assertTrue(hint.contains("screen__context"));
    }

    @Test public void vlFallbackText() {
        ScreenContext base = ScreenContext.fromDump(null, OWN, null, 0, EntityExtractorTest.TZ, 0);
        ScreenContext v = ScreenContext.fromVlText(base, "1. 顺丰速运\n2. 运单号：SF1122334455667\n- 客服电话 95338",
                EntityExtractorTest.NOW, EntityExtractorTest.TZ, 3000);
        assertEquals(ScreenContext.SRC_VL, v.source);
        assertEquals("SF1122334455667", v.entitiesOf(ScreenEntity.Type.TRACKING).get(0).value);
        assertEquals("95338", v.entitiesOf(ScreenEntity.Type.PHONE).get(0).value);
        assertTrue(v.isFresh(EntityExtractorTest.NOW + 1000, 60000));
        assertFalse(v.isFresh(EntityExtractorTest.NOW + 61000, 60000));
    }

    @Test public void luhnAndMask() {
        assertTrue(UiDumpParser.luhn("6222021234567890128"));
        assertEquals("卡 ****0128 余额", UiDumpParser.maskSecrets("卡 6222021234567890128 余额"));
        assertEquals("身份证 ****123X", UiDumpParser.maskSecrets("身份证 44010619900307123X"));
        assertEquals("订单 202610071380013801", UiDumpParser.maskSecrets("订单 202610071380013801"));
    }
}
