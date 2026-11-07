package com.onesay.assistant.v2.imgsave;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.vision.screenref.UiDumpParser;
import com.onesay.assistant.v2.vision.screenref.UiNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** [imgsave] 图片节点挑选：类名 / 面积阈值 / 与可见屏幕求交 / 排除文字与按钮 / 去重 / 阅读顺序编号。 */
public class ImageNodeSelectorTest {

    static final int W = 1080, H = 2220;

    static UiNode n(String cls, String rid, String text, String desc, int l, int t, int r, int b) {
        return new UiNode(text, desc, rid, cls, "com.xingin.xhs", false, false, l, t, r, b);
    }

    @Test public void feedWithTwoImagesAvatarAndButtons() {
        List<UiNode> nodes = new ArrayList<UiNode>();
        nodes.add(n("android.widget.FrameLayout", "", "", "", 0, 0, W, H));                       // 容器
        nodes.add(n("android.widget.ImageView", "com.xingin.xhs:id/avatar", "", "", 30, 200, 130, 300));   // 头像
        nodes.add(n("android.widget.ImageView", "com.xingin.xhs:id/iv_cover", "", "", 20, 400, 530, 1080)); // 左图
        nodes.add(n("android.widget.ImageView", "com.xingin.xhs:id/iv_cover", "", "", 550, 400, 1060, 1080)); // 右图
        nodes.add(n("android.widget.TextView", "", "标题", "", 20, 1090, 530, 1150));
        nodes.add(n("android.widget.ImageButton", "com.xingin.xhs:id/like_btn", "", "", 900, 2000, 1000, 2100));
        nodes.add(n("android.widget.ImageView", "com.xingin.xhs:id/iv_share", "", "", 30, 1200, 300, 1500));  // 分享图标（id 分词）
        List<ImageNodeSelector.Target> t = ImageNodeSelector.select(nodes, W, H);
        assertEquals(t.toString(), 2, t.size());
        assertEquals("同一行先左后右", 20, t.get(0).left);
        assertEquals(550, t.get(1).left);
    }

    @Test public void viewerFullScreenAndOffscreenPages() {
        List<UiNode> nodes = new ArrayList<UiNode>();
        nodes.add(n("com.github.chrisbanes.photoview.PhotoView", "", "", "", 0, 0, W, H));
        nodes.add(n("com.github.chrisbanes.photoview.PhotoView", "", "", "", W, 0, 2 * W, H));   // ViewPager 下一页：完全在屏外
        nodes.add(n("com.facebook.drawee.view.SimpleDraweeView", "", "", "", -800, 0, 280, H));   // 只露出 26%：可见不足一半
        List<ImageNodeSelector.Target> t = ImageNodeSelector.select(nodes, W, H);
        assertEquals(t.toString(), 1, t.size());
        assertEquals(W, t.get(0).width());
    }

    @Test public void nestedKeepsInnerAndDuplicatesCollapse() {
        List<UiNode> nodes = new ArrayList<UiNode>();
        nodes.add(n("android.widget.ImageView", "", "", "", 0, 300, W, 1500));     // 外层（带边距的容器式 ImageView）
        nodes.add(n("android.widget.ImageView", "", "", "", 40, 340, 1040, 1460)); // 内层
        nodes.add(n("android.widget.ImageView", "", "", "", 40, 340, 1040, 1460)); // 完全相同的重复节点
        List<ImageNodeSelector.Target> t = ImageNodeSelector.select(nodes, W, H);
        assertEquals(t.toString(), 1, t.size());
        assertEquals(40, t.get(0).left);
    }

    @Test public void readingOrderGrid() {
        List<UiNode> nodes = new ArrayList<UiNode>();
        // 九宫格倒序给出
        for (int row = 2; row >= 0; row--) {
            for (int col = 2; col >= 0; col--) {
                int l = 20 + col * 350, tp = 300 + row * 350;
                nodes.add(n("android.widget.ImageView", "com.tencent.mm:id/img", "", "", l, tp + (col == 1 ? 6 : 0), l + 330, tp + 330));
            }
        }
        List<ImageNodeSelector.Target> t = ImageNodeSelector.select(nodes, W, H);
        assertEquals(9, t.size());
        for (int i = 0; i < 9; i++) {
            assertEquals("第" + (i + 1) + "张的列", 20 + (i % 3) * 350, t.get(i).left);
            assertEquals("第" + (i + 1) + "张的行", 300 + (i / 3) * 350, t.get(i).top - (i % 3 == 1 ? 6 : 0));
        }
        assertEquals(Arrays.asList("第1张", "第2张", "第3张", "全部", "取消"), ImageNodeSelector.chipLabels(3));
        assertEquals(Arrays.asList("第1张", "取消"), ImageNodeSelector.chipLabels(1));
    }

    @Test public void looksLikeImageRules() {
        assertTrue(ImageNodeSelector.looksLikeImage(n("android.widget.ImageView", "com.a:id/iv_photo_background", "", "", 0, 0, 1, 1)));
        assertFalse("头像", ImageNodeSelector.looksLikeImage(n("android.widget.ImageView", "com.a:id/userAvatar", "", "", 0, 0, 1, 1)));
        assertFalse("带文字", ImageNodeSelector.looksLikeImage(n("android.widget.ImageView", "", "广告", "", 0, 0, 1, 1)));
        assertFalse("按钮", ImageNodeSelector.looksLikeImage(n("android.widget.Button", "", "", "", 0, 0, 1, 1)));
        assertFalse("TextView", ImageNodeSelector.looksLikeImage(n("android.widget.TextView", "", "", "", 0, 0, 1, 1)));
        assertTrue("WebView 图片", ImageNodeSelector.looksLikeImage(n("android.widget.Image", "", "", "", 0, 0, 1, 1)));
        assertTrue("Compose 图片（desc）", ImageNodeSelector.looksLikeImage(n("android.view.View", "", "", "图片", 0, 0, 1, 1)));
        assertFalse("普通 View", ImageNodeSelector.looksLikeImage(n("android.view.View", "", "", "", 0, 0, 1, 1)));
        assertFalse("desc 是按钮", ImageNodeSelector.looksLikeImage(n("android.widget.ImageView", "", "", "分享按钮", 0, 0, 1, 1)));
        assertTrue("Fresco", ImageNodeSelector.looksLikeImage(n("com.facebook.drawee.view.SimpleDraweeView", "", "", "", 0, 0, 1, 1)));
    }

    @Test public void smallImagesFilteredAndParserRoundTrip() {
        // 非压缩 dump 片段：整屏容器 + 小表情 + 一张大图；自家面板节点被丢弃
        String xml = "<hierarchy rotation=\"0\">"
                + "<node index=\"0\" text=\"\" resource-id=\"\" class=\"android.widget.FrameLayout\" package=\"com.twitter.android\" content-desc=\"\" bounds=\"[0,0][1080,2220]\">"
                + "<node index=\"0\" text=\"\" resource-id=\"\" class=\"android.widget.ImageView\" package=\"com.twitter.android\" content-desc=\"\" bounds=\"[100,100][160,160]\"/>"
                + "<node index=\"1\" text=\"\" resource-id=\"com.twitter.android:id/image\" class=\"android.widget.ImageView\" package=\"com.twitter.android\" content-desc=\"图片\" bounds=\"[0,500][1080,1300]\"/>"
                + "<node index=\"2\" text=\"\" resource-id=\"\" class=\"android.widget.ImageView\" package=\"com.onesay.assistant\" content-desc=\"\" bounds=\"[0,1400][1080,2200]\"/>"
                + "</node></hierarchy>";
        UiDumpParser.Result r = UiDumpParser.parse(xml, "com.onesay.assistant");
        List<ImageNodeSelector.Target> t = ImageNodeSelector.select(r.nodes, W, H);
        assertEquals(t.toString(), 1, t.size());
        assertEquals(500, t.get(0).top);
        assertEquals("com.twitter.android", r.rootPackage);
    }
}
