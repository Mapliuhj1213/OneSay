package com.onesay.assistant.v2.phone;

import com.onesay.assistant.v2.fastscreen.FsTree;

/**
 * [exec-ground-1124] 截图定位模式（纯 Java，JVM 可测）。
 *
 * <p>真机（魅族 21 Pro · Android 16 · 只开 Shizuku，2026-11-24 日志）：读屏助手读微信 117 次全是「元素=0」，读美团 214 次是「元素=1」
 * （根节点 + 5 个报告看不见的子节点），模型只能对着一段 200 字的文字描述猜，结果乱按、连着要截图。读不到的原因在 App 那边：
 * 新版微信（8.0.5x 起）给不在它白名单里的读屏方（包括系统自带的 uiautomator）只交一个空壳根节点（没类名、没范围、没孩子），
 * 美团大部分页面是自绘 / 报告看不见；这边把读屏标志（不重要的视图、交互窗口、资源 id）都开着也读不出来。所以树不可用时以截图为准：
 * <ul>
 *   <li>把截图本身发给主模型（原生看图），不再只给文字描述；</li>
 *   <li>模型按截图归一化坐标（0–999）点，写明点的是什么（target），敏感动作照 target 文字过闸；</li>
 *   <li>每一轮先说清「在哪一页、离目标差什么」，认不出 / 不在起点就先 reset_app 退回这个 App 首页，不瞎点；</li>
 *   <li>等待条件看不了文字时按画面指纹（亮度格子）变没变、稳没稳判断，超时不当失败；</li>
 *   <li>下一张截图上画出上一下点的位置，模型先核对点中没有。</li>
 * </ul>
 * 思路参考 Mika 的截图兜底（AndroidScreen 的 frameSignature / StillFrameRead / PhoneOcr，Apache-2.0），这里是自己写的，没有搬代码。
 */
public final class ScreenGround {

    private ScreenGround() {}

    /** 树里元素少于这么多、且质量不是 ok：按读不到算（美团 23 个元素的 partial 页仍按树走）。 */
    public static final int THIN_ELS = 6;
    /** 模型连着只要截图不动手：第 3 次起不再截，第 4 次停下。 */
    public static final int MAX_SHOT_STREAK = 2, STOP_SHOT_STREAK = 4;
    /** 一次任务里最多退回首页几次（卡住循环两次即停）。 */
    public static final int MAX_RESETS = 2;
    /** reset_app 最多按几次返回键。 */
    public static final int RESET_BACKS = 4;
    /** 截图给模型的最大宽度（像素）。 */
    public static final int SHOT_MAX_W = 720;
    /** 画面指纹格子（列 × 行）。 */
    public static final int GRID_COLS = 9, GRID_ROWS = 16;
    /** 按画面判断的等待上限：没写 timeout 的按 4 秒，最多不超过步骤给的时长。 */
    public static final int FRAME_WAIT_MS = 4000;

    /** 树不可用（要以截图为准）：读不到、unusable、或 partial 但元素太少。 */
    public static boolean treeBlind(UiTree u) {
        if (u == null) return true;
        String q = u.quality();
        if (UiTree.Q_UNUSABLE.equals(q)) return true;
        return UiTree.Q_PARTIAL.equals(q) && u.els.size() < THIN_ELS;
    }

    /**
     * App 主动把界面藏起来（新版微信的做法）：整棵树只有根，根没类名、范围是空的、没有孩子。
     * 和「这一页自己画的」（有几层容器但没字）分开记，方便看日志时知道该怪谁。
     */
    public static boolean withheld(FsTree t) {
        if (t == null || t.nodes.isEmpty()) return false;
        int roots = 0, real = 0;
        for (FsTree.Node n : t.nodes) {
            if (n.parent < 0) {
                roots++;
                boolean empty = n.r <= n.l || n.b <= n.t;
                boolean noCls = n.cls == null || n.cls.length() == 0;
                if (!(empty && noCls)) real++;
            } else real++;
        }
        return roots > 0 && real == 0;
    }

    /** 日志用：树为什么不可用。 */
    public static String blindWhy(UiTree u) {
        if (u == null) return "没读到界面";
        if (u.tree != null && withheld(u.tree)) return "App 不把界面内容交给读屏（只给空壳）";
        if (u.tree != null && u.tree.hidden > 0 && u.els.size() <= 1) return "界面节点都报告看不见（" + u.tree.hidden + " 个）";
        if (UiTree.Q_UNUSABLE.equals(u.quality())) return "界面是画出来的，没有文字节点";
        return "元素太少（" + u.els.size() + " 个）";
    }

    /** 系统提示补充：截图模式怎么输出。 */
    public static String addendum() {
        return "\n【截图模式】这一屏的控件树读不到（App 不给读或者是画出来的），截图就附在这条消息里，以截图为准："
                + "按截图上的位置点，写 0–999 归一化坐标（左上是 0,0，右下是 999,999），必须同时写 target 说明点的是什么，比如 "
                + "{\"do\":\"tap\",\"x\":500,\"y\":930,\"target\":\"底部「我的」\"}；输入先点输入框再 {\"do\":\"type\",\"text\":\"…\"}（不写 id = 当前输入框）。"
                + "截图里上一下点的位置画了红圈，先核对点中没有，没点中就换个位置，别原样再点。"
                + "看不到文字就别写 expect（代码会按画面变没变来等），一批只排 1–3 步。"
                + "截图边上的蓝色光晕、顶部黑色小岛是本机自己的提示，不是 App 的内容，别点。"
                + "截图已经给你了，不要再输出 {\"shot\":true}。"
                + "每次在 note 里先写两句：「在哪：哪个 App 的哪一页」「差什么：离任务目标还差哪几步」。"
                + "认不出这是哪一页、或者明显不在任务起点（停在某个子页面、弹窗、订单详情里），先排一步 {\"do\":\"reset_app\",\"app\":\"包名\"}，"
                + "代码会按返回键退回这个 App 的首页（退不回就从桌面重新打开）再给你新截图；不要在认不出的页面上瞎点。";
    }

    /** 开局那一轮（不管树读不读得到）：先判断现状，不在起点先回首页。 */
    public static String startNote(boolean resetLeft) {
        return "开局先判断：现在在哪个 App 的哪一页、离任务目标还差什么（写进 note）。"
                + (resetLeft ? "认不出当前页，或者停在任务起点以外的内部页面（详情页、子页面、弹窗）、从这里接着做不顺，"
                + "先排 {\"do\":\"reset_app\",\"app\":\"包名\"} 退回这个 App 的首页再开始；已经在合适的页面就直接接着做。" : "");
    }

    /** 用户消息补充：这一轮附了第几张截图、上一下点在哪。 */
    public static String userNote(int shotNo, int lastX, int lastY, String why) {
        StringBuilder b = new StringBuilder();
        b.append("（截图模式：").append(why == null || why.length() == 0 ? "控件树读不到" : why).append("，这条消息附了第 ").append(shotNo).append(" 张截图");
        if (lastX >= 0 && lastY >= 0) b.append("；红圈 = 上一下点的位置 (").append(lastX).append(',').append(lastY).append(")");
        b.append("）\n");
        return b.toString();
    }

    /** 连着要截图（第 3 次起）回给模型的话。 */
    public static String shotRefused(boolean ground) {
        return ground ? "截图已经连着给过你了（就在这条消息里），别再要截图：按截图上的位置写 x/y 坐标点，或者用 ask 问用户、用 done 如实说做不到。"
                : "已经连着看过画面描述了，别再要截图：按上面的界面和描述排动作，或者用 ask 问用户、用 done 如实说做不到。";
    }

    public static final String SHOT_STOP = "连着好几次只要看画面不动手，我先停在这里，没有乱点。你可以换个说法再让我做，或者自己先点到要开始的页面";

    // ------------------------------------------------------------------ 画面指纹（亮度格子）

    /**
     * 亮度格子 → 指纹：每格 0–255 量化成 16 档，一格一个十六进制字符。luma 长度 = cols × rows。
     * 顶部状态栏（时间 / 电量会变）那一行不算。
     */
    public static String frameSig(int[] luma, int cols, int rows) {
        if (luma == null || cols <= 0 || rows <= 0 || luma.length < cols * rows) return null;
        StringBuilder b = new StringBuilder(cols * rows);
        for (int r = 1; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int v = Math.max(0, Math.min(255, luma[r * cols + c]));
                b.append(Character.forDigit(v >> 4, 16));
            }
        }
        return b.toString();
    }

    /** 两张指纹差了几格（差 2 档以上才算；长度不同 / 有 null = 全不同）。 */
    public static int frameDiff(String a, String b) {
        if (a == null || b == null) return Integer.MAX_VALUE;
        if (a.length() != b.length()) return Math.max(a.length(), b.length());
        int d = 0;
        for (int i = 0; i < a.length(); i++) {
            int x = Character.digit(a.charAt(i), 16), y = Character.digit(b.charAt(i), 16);
            if (Math.abs(x - y) >= 2) d++;
        }
        return d;
    }

    /** 画面变了：差的格子 ≥ 3 格且 ≥ 2%（光标闪、小角标跳动不算）。任一个拿不到 = 不知道（false）。 */
    public static boolean frameChanged(String a, String b) {
        if (a == null || b == null) return false;
        int d = frameDiff(a, b);
        return d >= Math.max(3, a.length() / 50);
    }

    /** 画面稳了：两次几乎一样（≤ 1 格）。 */
    public static boolean frameSame(String a, String b) {
        return a != null && b != null && frameDiff(a, b) <= 1;
    }

    /** 跟在树签名后面的画面指纹（树不可用时，「界面没变」要靠它判断，不然每轮都像卡住）。 */
    public static String withFrame(String treeSig, String frame) {
        return frame == null ? treeSig : treeSig + "|f:" + frame;
    }
}
