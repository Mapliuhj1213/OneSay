package com.onesay.assistant.v2.s1;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

import org.junit.Test;

import com.onesay.assistant.v2.vision.screenref.DeixisResolver;
import com.onesay.assistant.v2.vision.screenref.ScreenContext;
import com.onesay.assistant.v2.vision.screenref.ScreenEntity;
import com.onesay.assistant.v2.vision.screenref.UiNode;

/** [s1] #8 圈选即问：触发词、矩形几何、截图换算、按框筛节点、区域上下文进入指代消解。 */
public class RegionSelectionTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");
    static final long NOW = 1791590400000L;

    static UiNode node(String text, int l, int t, int r, int b) {
        return new UiNode(text, "", "", "android.widget.TextView", "com.tencent.mm", false, false, l, t, r, b);
    }

    static String fixture(String name) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        File f = new File(root, "test-jvm/fixtures/screenref/" + name);
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
    }

    @Test public void triggerPhrases() {
        assertTrue(RegionSelection.isTrigger("圈一下"));
        assertTrue(RegionSelection.isTrigger("帮我圈一下这是什么"));
        assertTrue(RegionSelection.isTrigger("框选"));
        assertTrue(RegionSelection.isTrigger("框一下 这个多少钱"));
        assertTrue(RegionSelection.isTrigger("圈选"));
        assertFalse(RegionSelection.isTrigger("打开蓝牙"));
        assertFalse(RegionSelection.isTrigger("复制这个"));
        assertFalse(RegionSelection.isTrigger("他在朋友圈一下子发了很多"));
        assertFalse(RegionSelection.isTrigger(null));
        assertNull(RegionSelection.questionOf("圈一下"));
        assertEquals("这是什么", RegionSelection.questionOf("圈一下这是什么"));
        assertEquals("这个多少钱", RegionSelection.questionOf("框一下，这个多少钱"));
        assertEquals("翻译成英文", RegionSelection.questionOf("帮我圈一下然后翻译成英文"));
        assertNull(RegionSelection.questionOf("打开蓝牙"));
    }

    @Test public void normalizeClampAndSize() {
        assertArrayEquals(new int[] {100, 200, 500, 800}, RegionSelection.normalize(500f, 800f, 100f, 200f, 1440, 2960));
        assertArrayEquals(new int[] {0, 0, 1440, 2960}, RegionSelection.normalize(-50f, -9f, 5000f, 9999f, 1440, 2960));
        assertTrue(RegionSelection.bigEnough(new int[] {0, 0, 100, 100}, RegionSelection.MIN_SIDE_PX));
        assertFalse(RegionSelection.bigEnough(new int[] {0, 0, 30, 400}, RegionSelection.MIN_SIDE_PX));
        assertFalse(RegionSelection.bigEnough(null, 10));
    }

    @Test public void mapToBitmapScalesAndClamps() {
        assertArrayEquals(new int[] {100, 200, 400, 600}, RegionSelection.mapToBitmap(new int[] {100, 200, 500, 800}, 1440, 2960, 1440, 2960));
        assertArrayEquals(new int[] {50, 100, 200, 300}, RegionSelection.mapToBitmap(new int[] {100, 200, 500, 800}, 1440, 2960, 720, 1480));
        assertArrayEquals(new int[] {1300, 0, 140, 50}, RegionSelection.mapToBitmap(new int[] {1300, -10, 1600, 50}, 1440, 2960, 1440, 2960));
        assertNull(RegionSelection.mapToBitmap(new int[] {10, 10, 10, 10}, 1440, 2960, 1440, 2960));
        assertNull(RegionSelection.mapToBitmap(new int[] {0, 0, 10, 10}, 0, 2960, 1440, 2960));
    }

    @Test public void nodeFilterByOverlapOrCenter() {
        List<UiNode> nodes = Arrays.asList(
                node("完全在框内", 120, 220, 300, 260),
                node("一大半在框内", 50, 300, 400, 340),       // 宽 350，框内 300 → 0.86
                node("只擦到边", 450, 400, 1400, 440),         // 框内 50/950 ≈ 0.05，中心不在 → 不算
                node("宽气泡中心在框内", 0, 500, 900, 560),     // 框内 400/900 ≈ 0.44 < 0.5，但中心 450 在框内 → 算
                node("框外", 600, 1000, 900, 1100),
                node("", 150, 230, 200, 250));                 // 无文字
        int[] rect = {100, 200, 500, 800};
        List<UiNode> in = RegionSelection.nodesInRect(nodes, rect, RegionSelection.MIN_OVERLAP);
        assertEquals(3, in.size());
        assertEquals("完全在框内", in.get(0).text);
        assertEquals("一大半在框内", in.get(1).text);
        assertEquals("宽气泡中心在框内", in.get(2).text);
        assertEquals(1.0, RegionSelection.overlapRatio(node("零面积", 200, 300, 200, 300), rect), 1e-9);
        assertEquals(0.0, RegionSelection.overlapRatio(node("零面积框外", 900, 900, 900, 900), rect), 1e-9);
    }

    @Test public void regionContextOnlyKeepsSelectedAddressForDeixis() throws Exception {
        ScreenContext full = ScreenContext.fromDump(fixture("wechat_chat.xml"), "com.onesay.assistant", null, NOW, TZ, 1200L);
        // 整屏有两个地址 → 「导航去这个地址」是歧义
        assertEquals(DeixisResolver.Kind.AMBIGUOUS, DeixisResolver.resolve("导航去这个地址", full.entities).kind);
        // 只框住第二个地址（北京那条，bounds y=1060..1160）
        ScreenContext region = RegionSelection.regionContext(full, new int[] {150, 1040, 1250, 1180}, NOW, TZ);
        assertEquals(1, region.texts.size());
        assertFalse(region.needsVl);
        assertEquals(1, region.entitiesOf(ScreenEntity.Type.ADDRESS).size());
        DeixisResolver.Resolution r = DeixisResolver.resolve("导航去这个地址", region.entities);
        assertEquals(DeixisResolver.Kind.RESOLVED, r.kind);
        assertTrue(r.entity.value, r.entity.value.contains("北京市海淀区中关村大街27号"));
        assertEquals("com.tencent.mm", region.pkg);
    }

    @Test public void emptyRegionNeedsVl() throws Exception {
        ScreenContext full = ScreenContext.fromDump(fixture("wechat_chat.xml"), "com.onesay.assistant", null, NOW, TZ, 1200L);
        ScreenContext region = RegionSelection.regionContext(full, new int[] {0, 2000, 1440, 2900}, NOW, TZ);
        assertTrue(region.needsVl);
        assertTrue(region.texts.isEmpty());
        assertEquals(ScreenContext.SRC_NONE, region.source);
        assertTrue(RegionSelection.regionContext(null, new int[] {0, 0, 1, 1}, NOW, TZ).needsVl);
        assertTrue(RegionSelection.vlPrompt(null).contains("圈选"));
        assertTrue(RegionSelection.vlPrompt("这是什么花").endsWith("这是什么花"));
    }
}
