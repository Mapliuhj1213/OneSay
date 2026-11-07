package com.onesay.assistant.v2.vision.screenref;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.onesay.assistant.v2.vision.screenref.DeixisResolver.Action;
import com.onesay.assistant.v2.vision.screenref.DeixisResolver.Kind;
import com.onesay.assistant.v2.vision.screenref.DeixisResolver.Resolution;

/** [s1] 指代消解的剪贴板兜底：「导航到刚复制的地址」只用剪贴板；屏幕上没有地址时「导航去这里」才用剪贴板（低优先级）。 */
public class DeixisClipboardTest {

    static List<ScreenEntity> ents(String... lines) {
        return EntityExtractor.extractFromTexts(Arrays.asList(lines), EntityExtractorTest.NOW, EntityExtractorTest.TZ);
    }

    static final List<ScreenEntity> CLIP = ents("广州市天河区珠江新城华夏路10号富力中心28楼");
    static final List<ScreenEntity> SCREEN_WITH_ADDR = ents("或者去分公司：北京市海淀区中关村大街27号");
    static final List<ScreenEntity> SCREEN_NO_ADDR = ents("有事打我电话 13612345678", "今天下午开会");
    static final List<ScreenEntity> NONE = Collections.emptyList();

    @Test public void explicitClipboardReference() {
        assertTrue(DeixisResolver.refersToClipboard("导航到刚复制的地址"));
        assertTrue(DeixisResolver.hasDeixis("导航到刚复制的地址"));
        assertTrue(DeixisResolver.hasDeixis("打剪贴板里的电话"));
        assertEquals("「刚复制的」里的复制不是动词", Action.NAVIGATE, DeixisResolver.actionOf("导航到刚复制的地址"));
        assertFalse(DeixisResolver.refersToClipboard("复制这个"));
        assertEquals(Action.COPY, DeixisResolver.actionOf("复制这个"));
        assertFalse("没有动作的「复制的」不算指代", DeixisResolver.hasDeixis("刚复制的"));

        Resolution r = DeixisResolver.resolve("导航到刚复制的地址", SCREEN_WITH_ADDR, CLIP);
        assertEquals(Kind.RESOLVED, r.kind);
        assertTrue("明确说剪贴板 → 不取屏幕上的北京地址", r.entity.value.contains("广州市天河区"));
        Resolution none = DeixisResolver.resolve("导航到刚复制的地址", SCREEN_WITH_ADDR, NONE);
        assertEquals(Kind.NO_MATCH, none.kind);
        assertEquals(ScreenEntity.Type.ADDRESS, none.wantedType);
    }

    @Test public void screenWinsOverClipboard() {
        Resolution r = DeixisResolver.resolve("导航去这里", SCREEN_WITH_ADDR, CLIP);
        assertEquals(Kind.RESOLVED, r.kind);
        assertTrue(r.entity.value.contains("北京市海淀区"));
    }

    @Test public void clipboardFallbackWhenScreenHasNoAddress() {
        Resolution r = DeixisResolver.resolve("导航去这里", SCREEN_NO_ADDR, CLIP);
        assertEquals(Kind.RESOLVED, r.kind);
        assertEquals(Action.NAVIGATE, r.action);
        assertTrue(r.entity.value.contains("广州市天河区"));
        ScreenAction a = ActionMapper.map(r.action, r.entity);
        assertEquals(ScreenRefCapabilities.SIGN_NAV, a.sign);
        // 剪贴板也没有 → 仍是 NO_MATCH（行为与接入前一致）
        assertEquals(Kind.NO_MATCH, DeixisResolver.resolve("导航去这里", SCREEN_NO_ADDR, NONE).kind);
        assertEquals(Kind.NO_MATCH, DeixisResolver.resolve("导航去这里", SCREEN_NO_ADDR, null).kind);
    }

    @Test public void noFallbackForCopyOrOtherTypes() {
        // 「复制这个」不从剪贴板兜底（把剪贴板再复制一遍没有意义）
        assertEquals(Kind.NO_MATCH, DeixisResolver.resolve("复制这个", NONE, CLIP).kind);
        // 剪贴板里只有地址，要电话 → 仍 NO_MATCH
        assertEquals(Kind.NO_MATCH, DeixisResolver.resolve("打这个电话", ents("今天下午开会"), CLIP).kind);
        // 非指代句不受影响
        assertEquals(Kind.NOT_DEIXIS, DeixisResolver.resolve("打开蓝牙", NONE, CLIP).kind);
        // 两参版本行为不变
        assertEquals(Kind.NO_MATCH, DeixisResolver.resolve("导航去这里", SCREEN_NO_ADDR).kind);
    }
}
