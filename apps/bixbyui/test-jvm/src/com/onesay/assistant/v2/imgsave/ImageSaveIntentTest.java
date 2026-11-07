package com.onesay.assistant.v2.imgsave;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

/** [imgsave] 触发句正负例 + 多图选择口令（序号 / 全部 / 取消）。 */
public class ImageSaveIntentTest {

    static final String[] POSITIVE = {
        "保存图片", "存这张图", "把图存下来", "保存这张图片", "存一下图片", "全部保存",
        "帮我保存图片", "把这张照片存下来", "这张图存一下", "保存所有图片", "都存下来", "下载这张图片",
        "把图片保存到相册", "存图", "保存原图", "帮我把这几张图都存下来", "保存第二张图", "第二张存一下", "存个图", "保存这张图片。",
    };

    static final String[] NEGATIVE = {
        "保存联系人", "图片怎么保存", "保存文件", "图片保存在哪里", "怎么保存图片", "保存一下这个网页", "保存截图", "截图",
        "存一下", "保存", "保存视频", "图片保存不了怎么办", "存一下这个号码", "打开图片", "图片", "我喜欢这张图", "把图发给他",
        "为什么图片保存不了", "保存设置", "存一下车位B2-17", "记一下车停在B2-17", "这张图片是什么", "把图片发到微信",
    };

    @Test public void positives() {
        for (String q : POSITIVE) assertNotNull(q, ImageSaveIntent.match(q));
    }

    @Test public void negatives() {
        for (String q : NEGATIVE) assertNull(q, ImageSaveIntent.match(q));
    }

    @Test public void allAndOrdinalInTrigger() {
        assertTrue(ImageSaveIntent.match("全部保存").all);
        assertTrue(ImageSaveIntent.match("保存所有图片").all);
        assertTrue(ImageSaveIntent.match("帮我把这几张图都存下来").all);
        assertFalse(ImageSaveIntent.match("保存图片").all);
        assertEquals(0, ImageSaveIntent.match("保存图片").ordinal);
        assertEquals(2, ImageSaveIntent.match("保存第二张图").ordinal);
        assertEquals(3, ImageSaveIntent.match("把第3张图存下来").ordinal);
        assertFalse("点了序号就不是全部", ImageSaveIntent.match("保存第二张图").all);
    }

    @Test public void selection() {
        assertEquals(Arrays.asList(2), ImageSaveIntent.parseSelection("第二张", 3).picks);
        assertEquals(Arrays.asList(2), ImageSaveIntent.parseSelection("第2张", 3).picks);
        assertEquals(Arrays.asList(2), ImageSaveIntent.parseSelection("二", 3).picks);
        assertEquals(Arrays.asList(2), ImageSaveIntent.parseSelection("2号", 3).picks);
        assertEquals(Arrays.asList(2), ImageSaveIntent.parseSelection("就第二张吧", 3).picks);
        assertEquals(Arrays.asList(2), ImageSaveIntent.parseSelection("两", 3).picks);
        assertEquals(Arrays.asList(3), ImageSaveIntent.parseSelection("最后一张", 3).picks);
        assertEquals(Arrays.asList(1, 3), ImageSaveIntent.parseSelection("第一张和第三张", 3).picks);
        assertEquals(Arrays.asList(1, 3), ImageSaveIntent.parseSelection("3和1", 3).picks);
        assertEquals(Arrays.asList(1), ImageSaveIntent.parseSelection("第1张", 2).picks);
        assertTrue(ImageSaveIntent.parseSelection("全部", 3).all);
        assertTrue(ImageSaveIntent.parseSelection("都要", 3).all);
        assertTrue(ImageSaveIntent.parseSelection("全部保存", 3).all);
        assertTrue(ImageSaveIntent.parseSelection("都存", 3).all);
        assertTrue(ImageSaveIntent.parseSelection("取消", 3).cancel);
        assertTrue(ImageSaveIntent.parseSelection("算了", 3).cancel);
        assertTrue(ImageSaveIntent.parseSelection("第五张", 3).outOfRange);
        // 芯片文字回放（芯片点击走同一个解析）
        for (String chip : ImageNodeSelector.chipLabels(3)) assertNotNull(chip, ImageSaveIntent.parseSelection(chip, 3));
        // 不是选择口令 → 交回原流程
        assertNull(ImageSaveIntent.parseSelection("打开微信", 3));
        assertNull(ImageSaveIntent.parseSelection("现在几点", 3));
        assertNull(ImageSaveIntent.parseSelection("第二个路口左转", 3));
        assertNull(ImageSaveIntent.parseSelection("", 3));
    }

    @Test public void digits() {
        assertEquals(1, ImageSaveIntent.digit("一"));
        assertEquals(2, ImageSaveIntent.digit("两"));
        assertEquals(10, ImageSaveIntent.digit("十"));
        assertEquals(7, ImageSaveIntent.digit("7"));
        assertEquals(0, ImageSaveIntent.digit("x"));
    }
}
