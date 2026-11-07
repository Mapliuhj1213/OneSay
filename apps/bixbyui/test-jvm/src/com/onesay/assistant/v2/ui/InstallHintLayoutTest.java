package com.onesay.assistant.v2.ui;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * R7 遗留「安装提示条不在面板 bounds 里」：
 * 真因 1（布局，已修）：FloatingSheetView.onMeasure 的固定区写死 4 个子 View，后加的提示条不预留 → 长答案时会被挤出面板；
 * 真因 2（环境，非产品）：本机 policy_control=immersive.full 隐藏导航栏，面板画到 2094–2220，
 * 但无障碍 / uiautomator 仍按导航栏 inset 裁剪节点（2094 以下 bounds 为空）→ 见 测试说明书 R7-L。
 * Android View 在 JVM 跑不了，这里锁源码结构。
 */
public class InstallHintLayoutTest {

    static String src(String rel) {
        return com.onesay.assistant.v2.kernel.KernelWiring.src("app/src/com/onesay/assistant/v2/" + rel);
    }

    @Test public void chromeCountsEveryNonScrollChild() {
        String v = src("ui/FloatingSheetView.java");
        int m = v.indexOf("protected void onMeasure(");
        assertTrue(m > 0);
        String body = v.substring(m, v.indexOf("super.onMeasure(widthMeasureSpec, heightMeasureSpec);\n    }", m));
        assertTrue("固定区应遍历子 View", body.contains("for (int i = 0; i < getChildCount(); i++)"));
        assertTrue(body.contains("if (c == mContentScroll) continue;"));
        assertFalse("不再写死 4 个固定件", body.contains("measureFixedHeight(mSuggestionScroll, innerW)"));
    }

    @Test public void installPromptIsDirectChildOfSheet() {
        String p = src("ui/InstallPromptView.java");
        assertTrue(p.contains("sheet.addView(v, lp);"));
        assertTrue(p.contains("LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)"));
    }
}
