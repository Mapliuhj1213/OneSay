package com.onesay.assistant.v2.island;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

/**
 * [island-cutout-1118] 不录屏的演示：用 <b>同一套</b> {@link CutoutGeometry} + {@link IslandMorph}（同一个 Spring.step）在 JVM 上算出每一帧，
 * Java2D 画成 PNG 帧序列 + GIF。四格：魅族 21 Pro 中间孔（包孔）/ 同机关掉包孔（状态栏下，看 metaball 颈）/ 左上角孔 / Note 8 无孔。
 *
 * <pre>java -cp test-jvm/build/test:test-jvm/build/prod com.onesay.assistant.v2.island.IslandMorphDemo &lt;输出目录&gt; [CJK 字体 .otf]</pre>
 * 不是单测（类名不以 Test 结尾），run_unit_tests.sh 只编译不运行。
 */
public final class IslandMorphDemo {

    static final int PANE_W = 360;
    static final float VIEW_DP = 300f;   // 每格画屏幕最上面 300dp
    static final int FPS = 30;
    static final float T_SHOW = 0.45f, T_EXPAND = 1.75f, T_COLLAPSE = 3.6f, T_HIDE = 4.85f, T_END = 6.0f;

    static Font sFont, sBold;

    static final class Pane {
        final String label;
        final int sw, sh, sb;
        final float d;
        final List<int[]> rects;
        final IslandGeometry.Anchor a;
        final IslandMorph m = new IslandMorph();
        final IslandGeometry.Spring shapeA = new IslandGeometry.Spring(0f, IslandGeometry.omegaOf(0.28f), 1f);
        final IslandGeometry.Spring curA = new IslandGeometry.Spring(0f, 20f, 1f);
        final IslandGeometry.Spring prevA = new IslandGeometry.Spring(0f, 50f, 1f);
        int layer, prevLayer;   // 0 隐藏 1 紧凑 2 展开
        float gate = 0.8f;
        boolean gateArmed;

        Pane(String label, int sw, int sh, float d, int sb, List<int[]> rects, boolean wrap) {
            this.label = label;
            this.sw = sw;
            this.sh = sh;
            this.d = d;
            this.sb = sb;
            this.rects = rects;
            this.a = CutoutGeometry.anchor(sw, sh, d, sb, false, rects, wrap);
            m.setAnchor(a, d);
        }

        float sp(float v) {
            return v * d;
        }

        float[] compactSize() {
            Font f = sFont.deriveFont(sp(13));
            FontMetrics fm = metrics(f);
            float left = sp(12) + sp(6) + fm.stringWidth("整理邮件");
            float right = metrics(sFont.deriveFont(sp(12))).stringWidth("12 s");
            float plain = CutoutGeometry.compactWidth(left + sp(10) + right, sw, d);
            float w = a.wrap ? CutoutGeometry.wrapCompactWidth(a, left, right, plain, sw, d) : plain;
            float h = a.wrap ? a.compactH : sp(28);
            return new float[] {w, h, h / 2f};
        }

        float[] expandedSize() {
            float w = CutoutGeometry.expandedWidth(sw, d);
            return new float[] {w, sp(150) + CutoutGeometry.expandedInsetTop(a, d), sp(30)};
        }

        void go(int newLayer) {
            float[] t = newLayer == 1 ? compactSize() : newLayer == 2 ? expandedSize() : new float[] {0, 0, 0};
            boolean visible = m.w.v >= 1f && shapeA.v > 0.02f;
            IslandGeometry.Motion mo = IslandGeometry.motionFor(visible ? m.w.v : 0f, visible ? m.h.v : 0f, t[0], t[1]);
            m.setPhase(LiquidMotion.phaseOf(mo, false));
            gate = mo == IslandGeometry.Motion.APPEAR ? IslandGeometry.CONTENT_GATE : IslandGeometry.CONTENT_GATE_R11;
            if (layer != 0) {
                prevLayer = layer;
                prevA.v = curA.v;
                prevA.t = 0f;
            }
            layer = newLayer;
            curA.v = 0f;
            curA.vel = 0f;
            gateArmed = true;
            if (newLayer == 0) {
                m.targetOrigin();
                curA.t = 0f;
                shapeA.t = m.originIsHole() ? 1f : 0f;
                return;
            }
            m.targetShape(t[0], t[1], t[2], CutoutGeometry.cxFor(a, t[0], sw, d), a.top);
            curA.t = 1f;
            shapeA.t = 1f;
            if (!visible) {
                m.seedAtOrigin();
                shapeA.v = 1f;
            }
        }

        void step(float dt) {
            m.step(dt);
            shapeA.step(dt);
            curA.step(dt);
            prevA.step(dt);
            if (m.toOrigin() && layer == 0 && shapeA.t > 0f && m.nearOrigin()) shapeA.t = 0f;
            if (curA.t == 1f && gateArmed) {
                if (!IslandGeometry.contentGateOpen(m.w.v, m.w.t, m.h.v, m.h.t, gate)) {
                    curA.v = 0f;
                    curA.vel = 0f;
                } else {
                    gateArmed = false;
                }
            }
        }
    }

    static FontMetrics metrics(Font f) {
        BufferedImage im = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        FontMetrics fm = g.getFontMetrics(f);
        g.dispose();
        return fm;
    }

    /** IslandMorph → Java2D 路径。 */
    static final class Awt implements LiquidMotion.PathSink {
        final Path2D.Float p = new Path2D.Float();

        @Override public void moveTo(float x, float y) {
            p.moveTo(x, y);
        }

        @Override public void lineTo(float x, float y) {
            p.lineTo(x, y);
        }

        @Override public void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            p.curveTo(x1, y1, x2, y2, x3, y3);
        }

        @Override public void close() {
            p.closePath();
        }
    }

    static void drawPane(Graphics2D g, Pane p, int ox, int oy, float time) {
        float s = PANE_W / (float) p.sw;
        int ph = Math.round(VIEW_DP * p.d * s);
        Shape oldClip = g.getClip();
        g.setClip(ox, oy, PANE_W, ph);
        // 壁纸 + 状态栏
        g.setPaint(new GradientPaint(ox, oy, new Color(0xE9E6DF), ox, oy + ph, new Color(0xCFD8C6)));
        g.fillRect(ox, oy, PANE_W, ph);
        AffineTransform keep = g.getTransform();
        g.translate(ox, oy);
        g.scale(s, s);
        g.setColor(new Color(0x1C1B19));
        Font sbf = sBold.deriveFont(p.d * 13f);
        g.setFont(sbf);
        FontMetrics fm = g.getFontMetrics();
        int sbBase = Math.round(p.sb / 2f + fm.getAscent() / 2.6f);
        g.drawString("10:10", p.d * 16f, sbBase);
        float bx = p.sw - p.d * 16f;
        g.fill(new RoundRectangle2D.Float(bx - p.d * 24f, p.sb / 2f - p.d * 6f, p.d * 22f, p.d * 12f, p.d * 4f, p.d * 4f));
        for (int i = 0; i < 4; i++) {
            float bh = p.d * (4f + 2.5f * i);
            g.fill(new RoundRectangle2D.Float(bx - p.d * (56f - 5f * i), p.sb / 2f + p.d * 6f - bh, p.d * 3.2f, bh, p.d, p.d));
        }
        // 岛
        float alpha = Math.max(0f, Math.min(1f, p.shapeA.v));
        if (p.m.w.v > 0.5f && p.m.h.v > 0.5f && alpha > 0.003f) {
            Awt out = new Awt();
            LiquidMotion.outline(out, p.m.left(), p.m.top(), p.m.right(), p.m.bottom(), p.m.radius(), p.m.sag());
            Area area = new Area(out.p);
            Awt neck = new Awt();
            if (p.m.buildNeck(neck, 0, 0)) area.add(new Area(neck.p));
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            g.setColor(Color.BLACK);
            g.fill(area);
            Shape clip0 = g.getClip();
            g.clip(out.p);
            float ca = Math.max(0f, Math.min(1f, p.curA.v)) * alpha;
            float pa = Math.max(0f, Math.min(1f, p.prevA.v)) * alpha;
            if (pa > 0.01f) drawContent(g, p, p.prevLayer, pa, time);
            if (ca > 0.01f) drawContent(g, p, p.layer, ca, time);
            g.setClip(clip0);
            g.setComposite(AlphaComposite.SrcOver);
        }
        // 摄像头（物理的，画在最上面：黑玻璃 + 镜头反光）
        CutoutGeometry.Hole hole = CutoutGeometry.pick(p.rects, p.sw, p.sh, p.d, p.sb);
        if (CutoutGeometry.isHole(hole)) {
            g.setColor(new Color(0x050506));
            g.fill(new RoundRectangle2D.Float(hole.left(), hole.top(), hole.w, hole.h, hole.h, hole.h));
            g.setColor(new Color(0x1A2440));
            float lr = hole.h * 0.26f;
            g.fill(new Ellipse2D.Float(hole.cx - lr, hole.cy - lr, 2 * lr, 2 * lr));
            g.setColor(new Color(0x3A4A78));
            g.fill(new Ellipse2D.Float(hole.cx - lr * 0.35f, hole.cy - lr * 0.6f, lr * 0.5f, lr * 0.5f));
        }
        g.setTransform(keep);
        g.setClip(oldClip);
        // 标签
        g.setColor(new Color(0x3A3936));
        g.setFont(sFont.deriveFont(12f));
        g.drawString(p.label, ox + 8, oy + ph + 16);
    }

    static void drawContent(Graphics2D g, Pane p, int layer, float a, float time) {
        if (layer == 0) return;
        java.awt.Composite c0 = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a * ((AlphaComposite) c0).getAlpha()));
        float d = p.d;
        float x0 = p.m.left(), y0 = p.m.top(), w = p.m.w.v;
        if (layer == 1) {
            float[] t = p.compactSize();
            float cw = t[0];
            float cx = p.m.x.v;
            float left = cx - cw / 2f, right = cx + cw / 2f;
            int mode = CutoutGeometry.laneMode(p.a, p.sw);
            if (mode == CutoutGeometry.LANE_LEFT) left = p.a.laneR - d * 4f;
            if (mode == CutoutGeometry.LANE_RIGHT) right = p.a.laneL + d * 2.5f;
            float h = p.a.wrap ? p.a.compactH : d * 28f;
            float cy = y0 + h / 2f;
            float lx = left + d * 8f;
            double br = 0.55 + 0.45 * Math.cos(time * Math.PI * 2 / 1.4);
            g.setColor(new Color(132, 174, 100, (int) (255 * br)));
            g.fill(new Ellipse2D.Float(lx + d * 6f - d * 4.5f, cy - d * 4.5f, d * 9f, d * 9f));
            g.setColor(Color.WHITE);
            Font f = sFont.deriveFont(d * 13f);
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();
            g.drawString("整理邮件", lx + d * 18f, cy + fm.getAscent() / 2.8f);
            g.setColor(new Color(0xCFCBC4));
            Font tf = sFont.deriveFont(d * 12f);
            g.setFont(tf);
            FontMetrics tm = g.getFontMetrics();
            String sec = Math.max(1, Math.round(time * 2)) + " s";
            g.drawString(sec, right - d * 8f - tm.stringWidth(sec), cy + tm.getAscent() / 2.8f);
        } else {
            float pad = d * 16f;
            float y = y0 + CutoutGeometry.expandedInsetTop(p.a, d) + pad;
            float lx = x0 + pad;
            g.setColor(new Color(132, 174, 100));
            g.fill(new Ellipse2D.Float(lx, y + d * 3f, d * 10f, d * 10f));
            g.setColor(Color.WHITE);
            g.setFont(sBold.deriveFont(d * 16f));
            g.drawString("整理邮件", lx + d * 18f, y + d * 13f);
            g.setColor(new Color(0xA3A09A));
            g.setFont(sFont.deriveFont(d * 12f));
            g.drawString("2/4 · 12 s", x0 + w - pad - g.getFontMetrics().stringWidth("2/4 · 12 s"), y + d * 12f);
            String[] rows = {"✓ 读取收件箱", "● 归类 38 封邮件", "○ 写摘要"};
            for (int i = 0; i < rows.length; i++) {
                g.setColor(i == 1 ? Color.WHITE : new Color(0xA3A09A));
                g.setFont(sFont.deriveFont(d * 13f));
                g.drawString(rows[i], lx, y + d * (40f + 20f * i));
            }
            float by = y + d * 94f, bw = (w - 2 * pad - d * 10f) / 2f;
            g.setColor(new Color(0x262624));
            g.fill(new RoundRectangle2D.Float(lx, by, bw, d * 34f, d * 34f, d * 34f));
            g.fill(new RoundRectangle2D.Float(lx + bw + d * 10f, by, bw, d * 34f, d * 34f, d * 34f));
            g.setColor(Color.WHITE);
            g.setFont(sFont.deriveFont(d * 14f));
            FontMetrics fm = g.getFontMetrics();
            g.drawString("停止", lx + bw / 2f - fm.stringWidth("停止") / 2f, by + d * 22f);
            g.drawString("打开", lx + bw * 1.5f + d * 10f - fm.stringWidth("打开") / 2f, by + d * 22f);
        }
        g.setComposite(c0);
    }

    static List<int[]> rl(int[]... r) {
        return new ArrayList<int[]>(Arrays.asList(r));
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "island-demo");
        File frames = new File(out, "frames");
        frames.mkdirs();
        sFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        sBold = new Font(Font.SANS_SERIF, Font.BOLD, 12);
        if (args.length > 1) {
            sFont = Font.createFont(Font.TRUETYPE_FONT, new File(args[1]));
            File b = new File(args[1].replace("Regular", "Bold"));
            sBold = b.exists() ? Font.createFont(Font.TRUETYPE_FONT, b) : sFont.deriveFont(Font.BOLD);
        }
        Pane[] panes = {
                new Pane("魅族 21 Pro · 中间挖孔 · 包孔", 1440, 3200, 3.5f, 136, rl(new int[] {688, 42, 752, 106}), true),
                new Pane("同机 · 关掉包孔（状态栏下，看颈）", 1440, 3200, 3.5f, 136, rl(new int[] {688, 42, 752, 106}), false),
                new Pane("左上角挖孔 1080×2400", 1080, 2400, 2.625f, 110, rl(new int[] {50, 40, 110, 100}), true),
                new Pane("Note 8 · 无挖孔（上方居中）", 1080, 2220, 2.625f, 63, null, true),
        };
        int ph = Math.round(VIEW_DP * 3.5f * PANE_W / 1440f);
        int gap = 10, labelH = 24, titleH = 30;
        int W = PANE_W * 2 + gap * 3, H = titleH + (ph + labelH) * 2 + gap * 3;
        List<BufferedImage> gif = new ArrayList<BufferedImage>();
        int steps = Math.round(T_END * 60);
        boolean shown = false, expanded = false, collapsed = false, hidden = false;
        int frameNo = 0;
        for (int i = 0; i <= steps; i++) {
            float t = i / 60f;
            for (Pane p : panes) {
                if (!shown && t >= T_SHOW) p.go(1);
                if (!expanded && t >= T_EXPAND) p.go(2);
                if (!collapsed && t >= T_COLLAPSE) p.go(1);
                if (!hidden && t >= T_HIDE) p.go(0);
            }
            shown |= t >= T_SHOW;
            expanded |= t >= T_EXPAND;
            collapsed |= t >= T_COLLAPSE;
            hidden |= t >= T_HIDE;
            for (Pane p : panes) p.step(1f / 60f);
            if (i % (60 / FPS) != 0) continue;
            BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = im.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(new Color(0xF5F4F1));
            g.fillRect(0, 0, W, H);
            g.setColor(new Color(0x1C1B19));
            g.setFont(sBold.deriveFont(14f));
            String phase = t < T_SHOW ? "隐藏" : t < T_EXPAND ? "从孔里长出 → 紧凑" : t < T_COLLAPSE ? "展开（液态回弹 + 底边下垂）"
                    : t < T_HIDE ? "收起" : "缩回孔里";
            g.drawString(String.format("灵动岛 · 弹簧模型逐帧渲染（JVM，非录屏）  t = %.2f s  · %s", t, phase), gap, 20);
            for (int k = 0; k < panes.length; k++) {
                int ox = gap + (k % 2) * (PANE_W + gap);
                int oy = titleH + (k / 2) * (ph + labelH + gap);
                drawPane(g, panes[k], ox, oy, t);
            }
            g.setStroke(new BasicStroke(1f));
            g.dispose();
            ImageIO.write(im, "png", new File(frames, String.format("f%03d.png", frameNo++)));
            gif.add(im);
        }
        writeGif(gif, new File(out, "island-cutout-demo.gif"), 1000 / FPS);
        System.out.println("frames=" + frameNo + " → " + out.getAbsolutePath());
        for (Pane p : panes) System.out.println(p.label + " anchor{" + p.a + "}");
    }

    static void writeGif(List<BufferedImage> frames, File f, int delayMs) throws Exception {
        ImageWriter w = ImageIO.getImageWritersByFormatName("gif").next();
        ImageOutputStream os = ImageIO.createImageOutputStream(f);
        w.setOutput(os);
        w.prepareWriteSequence(null);
        for (int i = 0; i < frames.size(); i++) {
            BufferedImage im = frames.get(i);
            ImageWriteParam prm = w.getDefaultWriteParam();
            IIOMetadata md = w.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(im), prm);
            String fmt = md.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode) md.getAsTree(fmt);
            IIOMetadataNode gce = child(root, "GraphicControlExtension");
            gce.setAttribute("disposalMethod", "none");
            gce.setAttribute("userInputFlag", "FALSE");
            gce.setAttribute("transparentColorFlag", "FALSE");
            gce.setAttribute("delayTime", String.valueOf(Math.max(2, (i == frames.size() - 1 ? 800 : delayMs) / 10)));
            gce.setAttribute("transparentColorIndex", "0");
            if (i == 0) {
                IIOMetadataNode app = child(root, "ApplicationExtensions");
                IIOMetadataNode ext = new IIOMetadataNode("ApplicationExtension");
                ext.setAttribute("applicationID", "NETSCAPE");
                ext.setAttribute("authenticationCode", "2.0");
                ext.setUserObject(new byte[] {1, 0, 0});
                app.appendChild(ext);
            }
            md.setFromTree(fmt, root);
            w.writeToSequence(new IIOImage(im, null, md), prm);
        }
        w.endWriteSequence();
        os.close();
    }

    static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) return (IIOMetadataNode) root.item(i);
        }
        IIOMetadataNode n = new IIOMetadataNode(name);
        root.appendChild(n);
        return n;
    }
}
