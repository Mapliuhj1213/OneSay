package com.onesay.assistant.v2.s1;

import com.onesay.assistant.v2.vision.screenref.EntityExtractor;
import com.onesay.assistant.v2.vision.screenref.ScreenContext;
import com.onesay.assistant.v2.vision.screenref.ScreenEntity;
import com.onesay.assistant.v2.vision.screenref.UiNode;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * [s1] #8 圈选即问的纯 Java 部分（JVM 可测）：触发词、框选矩形归一化、截图坐标换算、按矩形筛 UI 节点、构建「区域上下文」。
 *
 * <p>流程（Android 侧见 {@code RegionSelectController}）：用户说「圈一下（这是什么）」→ 面板让位 → 全屏遮罩里拖一个框 →
 * 松手后 root {@code uiautomator dump} 整屏 → {@link #nodesInRect} 只留框内节点 → {@link #regionContext} 抽实体 →
 * 写进识屏会话缓存（之后「复制这个/导航去这里」只在框内消解）+ 面板显示动作芯片；
 * 话里带了问题（或框内没文字，比如是图片）且 VL 兜底开着 → 截图裁到框再问 Qwen-VL。
 */
public final class RegionSelection {

    private RegionSelection() {}

    /** 框选最小边长（像素）：小于它视为误触。 */
    public static final int MIN_SIDE_PX = 48;
    /** 节点有多大比例落在框内才算「框住了」。 */
    public static final double MIN_OVERLAP = 0.5;

    private static final Pattern TRIGGER = Pattern.compile(
            "^(?:帮我|给我|请)?(?:圈一下|圈一圈|圈下|圈选|圈出来|圈个|画个圈|画圈|框一下|框选|框出来|框个|划一下|划个框)");
    private static final Pattern TRIGGER_ANY = Pattern.compile("圈一下|圈选|框选|框一下|画个圈|划个框");
    private static final Pattern Q_STRIP = Pattern.compile("^[\\s，,。.：:、]*(?:然后|再|并且|并|看看|问一下|问问|告诉我)?[\\s，,。.：:、]*");

    /** 是不是「圈选即问」的触发句（句首是圈/框类动词）。 */
    public static boolean isTrigger(String command) {
        if (command == null) return false;
        String c = command.trim();
        if (c.length() == 0 || c.length() > 60) return false;
        return TRIGGER.matcher(c).find() || (c.length() <= 6 && TRIGGER_ANY.matcher(c).find());
    }

    /** 触发句里附带的问题（「圈一下 这是什么」→「这是什么」）；没有返回 null。 */
    public static String questionOf(String command) {
        if (!isTrigger(command)) return null;
        String c = command.trim();
        java.util.regex.Matcher m = TRIGGER.matcher(c);
        String rest = m.find() ? c.substring(m.end()) : TRIGGER_ANY.matcher(c).replaceFirst("");
        rest = Q_STRIP.matcher(rest).replaceFirst("").trim();
        rest = rest.replaceAll("^[吧呗啊呀]+", "").trim();
        return rest.length() >= 2 ? rest : null;
    }

    /** 两个拖拽端点 → 归一化矩形 {l, t, r, b}（钳到屏幕内）。 */
    public static int[] normalize(float x0, float y0, float x1, float y1, int screenW, int screenH) {
        int l = (int) Math.floor(Math.min(x0, x1));
        int t = (int) Math.floor(Math.min(y0, y1));
        int r = (int) Math.ceil(Math.max(x0, x1));
        int b = (int) Math.ceil(Math.max(y0, y1));
        l = clamp(l, 0, screenW);
        r = clamp(r, 0, screenW);
        t = clamp(t, 0, screenH);
        b = clamp(b, 0, screenH);
        return new int[] {l, t, r, b};
    }

    public static boolean bigEnough(int[] rect, int minSidePx) {
        return rect != null && rect.length == 4 && rect[2] - rect[0] >= minSidePx && rect[3] - rect[1] >= minSidePx;
    }

    /**
     * 遮罩视图坐标 → 截图像素坐标。遮罩是全屏 overlay（与屏幕同尺寸）时比例为 1；
     * 截图分辨率与视图不同（如截图被缩放）时按比例换算并钳到位图内。返回 {x, y, w, h}；无效返回 null。
     */
    public static int[] mapToBitmap(int[] rect, int viewW, int viewH, int bmpW, int bmpH) {
        if (rect == null || viewW <= 0 || viewH <= 0 || bmpW <= 0 || bmpH <= 0) return null;
        double sx = (double) bmpW / viewW;
        double sy = (double) bmpH / viewH;
        int l = clamp((int) Math.round(rect[0] * sx), 0, bmpW);
        int t = clamp((int) Math.round(rect[1] * sy), 0, bmpH);
        int r = clamp((int) Math.round(rect[2] * sx), 0, bmpW);
        int b = clamp((int) Math.round(rect[3] * sy), 0, bmpH);
        if (r - l < 1 || b - t < 1) return null;
        return new int[] {l, t, r - l, b - t};
    }

    /** 节点与矩形的重叠面积 / 节点面积（节点面积为 0 时按中心点是否在框内给 1 或 0）。 */
    public static double overlapRatio(UiNode n, int[] rect) {
        int nw = n.right - n.left;
        int nh = n.bottom - n.top;
        if (nw <= 0 || nh <= 0) {
            int cx = (n.left + n.right) / 2;
            int cy = (n.top + n.bottom) / 2;
            return contains(rect, cx, cy) ? 1.0 : 0.0;
        }
        int il = Math.max(n.left, rect[0]);
        int it = Math.max(n.top, rect[1]);
        int ir = Math.min(n.right, rect[2]);
        int ib = Math.min(n.bottom, rect[3]);
        if (ir <= il || ib <= it) return 0.0;
        return (double) (ir - il) * (ib - it) / ((double) nw * nh);
    }

    /**
     * 框内节点：有文字，且（重叠比例 ≥ minOverlap 或 节点中心在框内）。
     * 宽节点（一整行聊天气泡）用户往往只框住一部分，所以「中心在框内」也算；完全没重叠的一律不算。
     */
    public static List<UiNode> nodesInRect(List<UiNode> nodes, int[] rect, double minOverlap) {
        List<UiNode> out = new ArrayList<UiNode>();
        if (nodes == null || rect == null) return out;
        for (UiNode n : nodes) {
            if (n.label().trim().length() == 0) continue;
            double ov = overlapRatio(n, rect);
            if (ov <= 0) continue;
            int cx = (n.left + n.right) / 2;
            int cy = (n.top + n.bottom) / 2;
            if (ov >= minOverlap || contains(rect, cx, cy)) out.add(n);
        }
        return out;
    }

    /**
     * 由整屏上下文 + 框选矩形构建「区域上下文」：只含框内节点、文字与实体；来源沿用整屏的来源；
     * 框内没有文字时 needsVl=true（交给截图裁剪 + VL）。整屏上下文为 null 时返回只带 needsVl 的空上下文。
     */
    public static ScreenContext regionContext(ScreenContext full, int[] rect, long nowMs, TimeZone tz) {
        if (full == null || rect == null) {
            return new ScreenContext(null, null, ScreenContext.SRC_NONE, nowMs, 0L, null, null, null,
                    "整屏文字树不可用", true, 0);
        }
        List<UiNode> in = nodesInRect(full.nodes, rect, MIN_OVERLAP);
        List<String> texts = new ArrayList<String>();
        for (UiNode n : in) {
            String l = n.label().trim();
            if (l.length() > 0 && !texts.contains(l)) texts.add(l);
        }
        List<ScreenEntity> ents = EntityExtractor.extractFromNodes(in, nowMs, tz);
        boolean empty = texts.isEmpty();
        return new ScreenContext(full.pkg, full.activity, empty ? ScreenContext.SRC_NONE : full.source, nowMs, full.costMs,
                in, texts, ents, empty ? "圈选区域里没有可读文字（可能是图片）" : null, empty, full.redacted);
    }

    /** 给 VL 的提问：用户问了就用用户的，否则默认「说明框里是什么 + 可做的下一步」。 */
    public static String vlPrompt(String userQuestion) {
        String base = "这是用户在手机屏幕上圈选出来的一块区域的截图。";
        if (userQuestion != null && userQuestion.trim().length() > 0) return base + "请结合图中内容回答：" + userQuestion.trim();
        return base + "请用两三句话说明里面是什么，并给出一两个用户可能想做的下一步（例如复制、导航、拨打、搜索）。";
    }

    private static boolean contains(int[] r, int x, int y) {
        return x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3];
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
