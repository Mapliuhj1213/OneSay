package com.onesay.assistant.v2.ui.ime;

/**
 * ★ r2/kb-fix（LC-2 第三次）：键盘高度 / 窗口偏移的纯计算（不碰 android.*，JVM 单测覆盖）。
 *
 * <p>背景：Android 8 上 TYPE_APPLICATION_OVERLAY 窗口的 SOFT_INPUT_ADJUST_RESIZE / ADJUST_PAN 经常不生效
 * （贴底 wrap 窗不会被抬、全屏窗不会被缩），所以不能靠 softInputMode。我们自己量键盘上沿，再主动把输入栏抬上去。
 *
 * <p>读数来源（都是屏幕坐标，单位 px）：
 * <ul>
 *   <li><b>探针窗</b>（{@link ImeProbeWindow}）：一个全高、1px 宽、不可触摸、不可获焦的辅助 overlay，
 *       softInputMode 固定 ADJUST_RESIZE。它在输入法下面一层，WMS 布局时会把输入法的可见区扣给它，
 *       所以 getWindowVisibleDisplayFrame 的底边 = 键盘上沿。不依赖「谁是输入法目标」，最可靠。</li>
 *   <li><b>自身可见区</b>：卡片窗自己的 getWindowVisibleDisplayFrame（以前 LC-2 只靠这一路）。</li>
 *   <li><b>反射高度</b>：InputMethodManager.getInputMethodWindowVisibleHeight()（隐藏 API，读不到给 0）。</li>
 * </ul>
 * 每一路都可能读不到或滞后，所以取「可信读数里最高的键盘上沿」（宁可多让一点，也不让输入框被盖住）；
 * 全部读不到 = 键盘没弹，偏移归零（窗口复位，不留空白）。
 */
public final class ImeGeometry {

    private ImeGeometry() {}

    /** 键盘至少这么高才算弹出（过滤导航栏 / 状态栏变化带来的几十像素抖动）：屏高的 12%。 */
    static int minKeyboard(int screenH) {
        return Math.max(1, screenH * 12 / 100);
    }

    /** 键盘最多这么高（再高就是读数异常，比如可见区整个塌掉）：屏高的 80%。 */
    static int maxKeyboard(int screenH) {
        return screenH * 80 / 100;
    }

    /**
     * 由「基线底边」和「当前可见区底边」推出键盘上沿。
     *
     * @param baseBottom 键盘没弹时的可见区底边（导航栏上沿；锁屏托底全屏时是屏底）
     * @param visBottom  当前可见区底边
     * @return 键盘上沿；-1 = 这一路说键盘没弹 / 读数不可信
     */
    public static int topFromVisible(int baseBottom, int visBottom, int screenH) {
        if (baseBottom <= 0 || visBottom <= 0 || screenH <= 0) return -1;
        int h = baseBottom - visBottom;
        if (h < minKeyboard(screenH) || h > maxKeyboard(screenH)) return -1;
        return visBottom;
    }

    /** 由反射拿到的键盘可见高度推上沿：键盘贴在基线（导航栏上沿）之上。 */
    public static int topFromHeight(int baseBottom, int imeHeight, int screenH) {
        if (baseBottom <= 0 || imeHeight <= 0 || screenH <= 0) return -1;
        if (imeHeight < minKeyboard(screenH) || imeHeight > maxKeyboard(screenH)) return -1;
        return baseBottom - imeHeight;
    }

    /**
     * 综合各路读数：取最高（数值最小）的可信上沿。
     *
     * @return 键盘上沿（屏幕坐标）；-1 = 键盘没弹
     */
    public static int combineTop(int... tops) {
        int best = -1;
        if (tops == null) return -1;
        for (int t : tops) {
            if (t <= 0) continue;
            if (best < 0 || t < best) best = t;
        }
        return best;
    }

    /** 键盘高度（相对基线）；键盘没弹给 0。 */
    public static int keyboardHeight(int baseBottom, int imeTop) {
        if (imeTop <= 0 || baseBottom <= 0 || imeTop >= baseBottom) return 0;
        return baseBottom - imeTop;
    }

    /**
     * 输入栏要往上抬多少，才能让「窗口底边」落在键盘上沿 - gap 处。
     *
     * @param restBottom 窗口在<b>不抬</b>时的底边（屏幕坐标）。调用方要扣掉自己已经加上的偏移，
     *                   否则会抬两遍（真机：系统偶尔也会把窗口推一下，用实际底边 + 当前偏移还原）
     * @param imeTop     键盘上沿；-1 = 没弹 → 0（复位）
     * @param gap        输入栏和键盘之间留的缝
     * @param maxOffset  上限（防御：异常读数不至于把内容抬出屏幕）；&lt;=0 不设限
     */
    public static int liftFor(int restBottom, int imeTop, int gap, int maxOffset) {
        if (imeTop <= 0 || restBottom <= 0) return 0;
        int lift = restBottom - (imeTop - Math.max(0, gap));
        if (lift <= 0) return 0;
        if (maxOffset > 0 && lift > maxOffset) lift = maxOffset;
        return lift;
    }

    /**
     * ★ 横屏 + 贴底键盘（真机 v2：三星键盘横屏 603px）：卡片里「问句 + 答案 + 图标行 + 输入条」放不下，
     * 输入条（在最底下）被挤出卡片。键盘弹着且剩余高度不够时：先收起图标行（🎤 / ⌨ / 识屏，打字时用不上），
     * 还不够就把内容区压到 0（答案暂时看不见，收键盘就回来）—— 输入框和发送按钮必须完整可见。
     *
     * @param avail     卡片窗可用高度
     * @param chrome    除内容区外所有可见子 View 的高度（含内外边距、含让位的底部内边距），图标行可见时含图标行
     * @param iconRowH  图标行高度（含边距）；已经隐藏 / 没有给 0
     * @param imeLift   当前让位（&gt;0 = 键盘弹着）
     * @param minWithIme 键盘弹着时内容区的常规下限（40dp）
     * @return {hideIconRow(0/1), contentCap}
     */
    public static int[] tightLayout(int avail, int chrome, int iconRowH, int imeLift, int minWithIme) {
        int left = avail - chrome;
        if (imeLift <= 0 || left >= minWithIme) return new int[]{0, Math.max(minWithIme, left)};
        if (iconRowH > 0) {
            int left2 = left + iconRowH;
            return new int[]{1, Math.max(0, Math.min(minWithIme, left2))};
        }
        return new int[]{0, Math.max(0, left)};
    }

    /** 偏移有没有变到值得重排（≤ 2px 的抖动忽略，避免候选栏动画期间每帧 relayout）。 */
    public static boolean changed(int oldPx, int newPx) {
        if (oldPx == newPx) return false;
        if (oldPx == 0 || newPx == 0) return true;   // 弹出 / 收起一定要动
        return Math.abs(oldPx - newPx) > 2;
    }

    /**
     * 基线底边：取「键盘没弹」时见过的最大可见区底边。转屏 / 锁屏托底（无导航栏）时由调用方清零重记。
     * 只有在本路读数说「键盘没弹」（差值小于最小键盘）时才更新，免得键盘弹着时把基线拉低。
     */
    public static int updateBase(int base, int visBottom, int screenH) {
        if (visBottom <= 0) return base;
        if (base <= 0) return visBottom;
        if (visBottom > base) return visBottom;                       // 导航栏消失 / 窗口盖住导航栏：基线变大
        if (base - visBottom < minKeyboard(screenH)) return base;     // 小抖动：保持
        return base;                                                  // 键盘弹着：不动基线
    }
}
