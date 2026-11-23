package com.onesay.assistant.v2.phone;

import com.onesay.assistant.v2.util.SafeLog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;

import com.onesay.assistant.v2.exec.ShieldOverlay;
import com.onesay.assistant.v2.fastscreen.FastScreen;
import com.onesay.assistant.v2.fastscreen.FsProtocol;
import com.onesay.assistant.v2.fastscreen.FsTree;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ★ r2/fast-screen：{@link PhoneAgent.Driver} 的真机实现 —— 常驻 root 读屏助手 + 防误触护罩配合注入。
 *
 * <ul>
 *   <li>点按优先走无障碍 ACTION_CLICK（不经过触摸管线，护罩挡不住、也不会和用户手指抢）；没有可点节点才注入触摸，
 *       注入前护罩在落点开 64px 小孔（等 WMS 生效）、注入后补上；</li>
 *   <li>输入优先 ACTION_SET_TEXT（中文可）；失败退回「自家剪贴板 + 注入 KEYCODE_PASTE」；</li>
 *   <li>滚动优先无障碍 SCROLL_FORWARD/BACKWARD；没有可滚动节点才注入滑动（滑动路径整条开孔）。</li>
 * </ul>
 */
public final class FastScreenDriver implements PhoneAgent.LaunchInfo, PhoneAgent.MikaDriver {

    private static final String TAG = "PhoneAgent";

    private final Context mApp;
    private final FastScreen mFs;
    private volatile ShieldOverlay mShield;
    private final int mW, mH;
    /** 统计（报告用）：读屏次数 / 总耗时、无障碍点击 / 注入点按次数。 */
    public int observes, a11yClicks, injectedTaps, setTexts, pastes;
    /** ★ mika-plug 统计：祖先点击、核对后的输入、粘贴后还原剪贴板、ChangeWait 次数。 */
    public int boundsClicks, verifiedTypes, clipRestores, changeWaits;
    /** ★ mika-plug：执行层插件（null = 全关，行为与改前一致）。 */
    private volatile MikaExec mMika;
    public long observeMsTotal, observeMsMax;

    public FastScreenDriver(Context ctx) {
        mApp = ctx.getApplicationContext();
        mFs = FastScreen.get(mApp);
        Point p = new Point();
        ((WindowManager) mApp.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(p);
        mW = p.x;
        mH = p.y;
    }

    /**
     * ★ mika-plug（mika.a11y）：B2 无障碍服务后端可用 = 服务连着，且（插件开着，或 ★ a11y-1118 读屏助手不可用、本次改用无障碍兜底）。
     */
    public boolean b2Ready() {
        if (!com.onesay.assistant.v2.a11y.A11yBackend.connected()) return false;
        if (mA11yFallback) return true;
        MikaExec m = mMika;
        return m != null && m.f.a11y;
    }

    /** ★ a11y-1118：读屏助手不可用、无障碍服务连着 → 整个任务都走无障碍（读树 / 点按 / 手势 / 输入），不再碰助手。 */
    private volatile boolean mA11yFallback;

    public boolean a11yFallback() {
        return mA11yFallback;
    }

    /** 测试 / 调试用。 */
    public void setA11yFallback(boolean on) {
        mA11yFallback = on;
    }

    /** 兜底时拿无障碍后端（连着才非 null）。★ plan-1118：正在重新绑定 → 最多等 {@link com.onesay.assistant.v2.a11y.RebindWait#MAX_WAIT_MS}。 */
    private com.onesay.assistant.v2.a11y.A11yBackend fb() {
        return mA11yFallback ? com.onesay.assistant.v2.a11y.A11yBackend.awaitInstance(com.onesay.assistant.v2.a11y.RebindWait.MAX_WAIT_MS) : null;
    }

    /** ★ plan-1118：无障碍后端（兜底模式下跨重绑间隙等待；B2 插件模式不等）。 */
    private com.onesay.assistant.v2.a11y.A11yBackend a11y() {
        return com.onesay.assistant.v2.a11y.A11yBackend.awaitInstance(mA11yFallback ? com.onesay.assistant.v2.a11y.RebindWait.MAX_WAIT_MS : 0);
    }

    /**
     * ★ a11y-1118：开局选读屏后端：助手 ping 通 → 助手；否则无障碍服务连着（重新绑定中最多等 1.5 s）→ 无障碍兜底；
     * 都不行 → NONE，原因里带「打开无障碍」的指引。
     */
    public com.onesay.assistant.v2.a11y.A11yState.Choice chooseBackend() {
        boolean killed = com.onesay.assistant.v2.mikaplug.MikaPlugs.a11yBackendKilled(mApp);
        boolean helperOk = FastScreen.enabled(mApp) && observeOk();
        boolean connected = !killed && com.onesay.assistant.v2.a11y.A11yBackend.awaitConnected(mApp, helperOk ? 0 : 1500);
        boolean inSettings = com.onesay.assistant.v2.a11y.A11yBackend.enabledInSettings(mApp);
        com.onesay.assistant.v2.a11y.A11yState.Choice c = com.onesay.assistant.v2.a11y.A11yState.choose(helperOk, connected, inSettings, killed, mFs.lastError());
        mA11yFallback = c.backend == com.onesay.assistant.v2.a11y.A11yState.Backend.A11Y;
        SafeLog.i(TAG, "[PHONE] 读屏后端：" + c.backend + " helper=" + helperOk + " a11y=" + connected + " inSettings=" + inSettings
                + (killed ? " killed" : "") + (helperOk ? "" : " helperErr=" + mFs.lastError()));
        return c;
    }

    /** 上一次读屏来自 B2（动作要用同一个后端的节点句柄）。 */
    private volatile boolean mLastB2;

    public boolean observeOk() {
        return mFs.ping();
    }

    public void setMika(MikaExec m) {
        mMika = m;
    }

    private boolean mk(int which) {
        MikaExec m = mMika;
        if (m == null) return false;
        switch (which) {
            case 0: return m.f.screen;
            case 1: return m.f.actchain;
            default: return m.f.changewait;
        }
    }

    public void setShield(ShieldOverlay s) {
        mShield = s;
    }

    @Override public UiTree observe(UiTree.Ids ids) {
        long t0 = SystemClock.elapsedRealtime();
        MikaExec mx = mMika;
        FsTree t = null;
        mLastB2 = false;
        if (mA11yFallback || b2Ready()) {   // ★ mika-plug：B2 后端（可选，默认关）；读不到退回助手
            // ★ plan-1118：兜底模式下重新绑定的间隙（约 1 s）等新实例；读到一半旧实例被解绑 → 在新实例上再读一次
            t = mA11yFallback ? com.onesay.assistant.v2.a11y.A11yBackend.call(new com.onesay.assistant.v2.a11y.RebindWait.Op<com.onesay.assistant.v2.a11y.A11yBackend, FsTree>() {
                @Override public FsTree run(com.onesay.assistant.v2.a11y.A11yBackend b) {
                    return b.readTree(1500);
                }
            }, com.onesay.assistant.v2.a11y.RebindWait.MAX_WAIT_MS) : readB2();
            mLastB2 = t != null;
        }
        if (t == null && mA11yFallback) {   // ★ a11y-1118：兜底模式下不再去拉助手（每次都白等）
            SafeLog.w(TAG, "[PHONE] 无障碍读屏失败（服务" + (com.onesay.assistant.v2.a11y.A11yBackend.connected() ? "连着" : "没连上") + "）");
            observes++;
            return null;
        }
        if (t == null) t = mx != null && mx.f.screen ? mFs.tree(mx.f.allWindows) : mFs.tree();
        long ms = SystemClock.elapsedRealtime() - t0;
        observes++;
        observeMsTotal += ms;
        observeMsMax = Math.max(observeMsMax, ms);
        if (t == null) {
            SafeLog.w(TAG, "[PHONE] 读屏失败：" + mFs.lastError());
            return null;
        }
        UiTree u = mx != null && mx.f.screen ? mx.build(t, ids, mW, mH) : UiTree.from(t, ids, mW, mH);   // ★ mika-plug：Mika 元素表
        SafeLog.i(TAG, "[PHONE] 读屏 " + ms + "ms（助手内 " + t.ms + "ms）pkg=" + u.pkg + " 元素=" + u.els.size() + " 质量=" + u.quality()
                + (u.ime ? " ime" : ""));
        // [fix-1123-dexec B7] 助手读到的不完整（被自家悬浮窗挡住、只读到最上层）且无障碍连着 → 用无障碍再读一次，更好就换
        if (!mLastB2 && ObserveFallback.suspect(u.quality(), u.pkg, mApp.getPackageName())) {
            try {
                FsTree t2 = readB2();
                if (t2 != null) {
                    UiTree u2 = mx != null && mx.f.screen ? mx.build(t2, ids, mW, mH) : UiTree.from(t2, ids, mW, mH);
                    if (ObserveFallback.preferA11y(u.quality(), u.pkg, u.els.size(), u2.quality(), u2.pkg, u2.els.size(), mApp.getPackageName())) {
                        a11yRescues++;
                        mLastB2 = true;   // 之后的动作用同一个后端的节点句柄
                        SafeLog.i(TAG, "[PHONE] 助手读屏不完整（" + u.quality() + " pkg=" + u.pkg + "）→ 改用无障碍读屏 pkg=" + u2.pkg
                                + " 元素=" + u2.els.size() + " 质量=" + u2.quality());
                        return u2;
                    }
                }
            } catch (Throwable e) {
                SafeLog.w(TAG, "[PHONE] 无障碍补读失败: " + e);
            }
        }
        return u;
    }

    private FsTree readB2() {
        com.onesay.assistant.v2.a11y.A11yBackend b2 = com.onesay.assistant.v2.a11y.A11yBackend.instance();
        return b2 == null ? null : b2.readTree(1500);
    }

    /** [fix-1123-dexec B7] 助手读屏不完整、改用无障碍读屏救回来的次数。 */
    public volatile int a11yRescues;

    /** [fix-1123-dexec B9] 用户点了停止：之后这台驱动的一切动作直接作废（模型刚回来的一批步骤、点按核对里的补点都不再执行）。 */
    private volatile boolean mAborted;

    public void abort() {
        mAborted = true;
        SafeLog.i(TAG, "[PHONE] 用户停止：挂起的动作全部作废");
    }

    public boolean aborted() {
        return mAborted;
    }

    private boolean dead(String op) {
        if (!mAborted) return false;
        SafeLog.i(TAG, "[PHONE] 已停止，作废动作 " + op);
        return true;
    }

    /** ★ plan-1118：护罩孔已由外层开着（点按核对期间），内层手势不再开 / 关。 */
    private volatile boolean mHoleHeld;

    /**
     * ★ plan-1118（真机 Flyme 计算器）：无障碍后端的点按 —— 整个过程在元素范围开护罩孔（坐标手势、以及 ROM 把节点点击转成的触摸
     * 都不再被自家护罩吞掉，以前日志里的「护罩吞掉误触」就是这么来的）；节点点击报成功但界面指纹没变 → 中心补一次坐标手势点按。
     */
    private boolean tapA11yVerified(final UiTree.El e) {
        ShieldOverlay sh = mShield;
        int[] h = TapVerify.holeFor(e.l, e.t, e.r, e.b, mW, mH, 8);
        if (sh != null) sh.openHole(new Rect(h[0], h[1], h[2], h[3]));
        mHoleHeld = sh != null;
        try {
            long before = TapVerify.sig(readNoKeep());
            String how = com.onesay.assistant.plug.action.core.ActionChain.tap(port(), e.src, new int[]{e.l, e.t, e.r, e.b}, e.cls);
            if (com.onesay.assistant.plug.action.core.ActionChain.A11Y.equals(how) && before != 0L) {
                waitIdle(150, TapVerify.SETTLE_MAX_MS);
                long after = TapVerify.sig(readNoKeep());
                if (TapVerify.needGestureRetry(how, before, after)) {
                    SystemClock.sleep(TapVerify.RECHECK_MS);
                    after = TapVerify.sig(readNoKeep());
                }
                if (TapVerify.needGestureRetry(how, before, after)) {
                    boolean g = com.onesay.assistant.v2.a11y.A11yBackend.act(new com.onesay.assistant.v2.a11y.RebindWait.Op<com.onesay.assistant.v2.a11y.A11yBackend, Boolean>() {
                        @Override public Boolean run(com.onesay.assistant.v2.a11y.A11yBackend b) {
                            return b.gestureTap(e.cx(), e.cy());
                        }
                    }, com.onesay.assistant.v2.a11y.RebindWait.MAX_WAIT_MS);
                    SafeLog.i(TAG, "[PHONE] 节点点击报成功但界面没变 → 坐标手势点按 @" + e.cx() + "," + e.cy() + (g ? " ok" : " 失败"));
                    if (g) {
                        injectedTaps++;
                        how = com.onesay.assistant.plug.action.core.ActionChain.A11Y + "+" + com.onesay.assistant.plug.action.core.ActionChain.GESTURE;
                    }
                }
            }
            SafeLog.i(TAG, "[PHONE] 点按 " + how);
            return !com.onesay.assistant.plug.action.core.ActionChain.NONE.equals(how);
        } finally {
            mHoleHeld = false;
            if (sh != null) sh.closeHole();
        }
    }

    /** ★ plan-1118：读一次树只为比对（不换节点句柄）；读不到返回 null。 */
    private FsTree readNoKeep() {
        com.onesay.assistant.v2.a11y.A11yBackend b = a11y();
        return b == null ? null : b.readTree(1500, false);
    }

    @Override public boolean tap(final UiTree.El e) {
        if (e == null || dead("tap")) return false;
        com.onesay.assistant.v2.pclink.PcConfirm.noteRobotInput();   // approve-not-block：机器人在点，这一会儿确认卡上的触摸不算真人
        if (mA11yFallback || (mk(1) && mLastB2)) return tapA11yVerified(e);   // ★ plan-1118
        if (mk(1)) {   // ★ mika-plug（mika.actchain）：节点或 ≤8 层可点祖先 ACTION_CLICK → （B1 无手势）→ 注入
            String how = com.onesay.assistant.plug.action.core.ActionChain.tap(port(), e.src, new int[]{e.l, e.t, e.r, e.b}, e.cls);
            SafeLog.i(TAG, "[PHONE] 点按 " + how);
            return !com.onesay.assistant.plug.action.core.ActionChain.NONE.equals(how);
        }
        com.onesay.assistant.v2.a11y.A11yBackend b = fb();
        if (b != null) {   // ★ a11y-1118
            boolean ok = b.clickAt(e.cx(), e.cy());
            if (ok) a11yClicks++;
            return ok;
        }
        if (mFs.click(e.cx(), e.cy())) {
            a11yClicks++;
            return true;
        }
        return injectTap(e.cx(), e.cy());
    }

    /** 注入点按（护罩开孔 → 注入 → 补孔）。测试也直接调。 */
    public boolean injectTap(int x, int y) {
        if (dead("injectTap")) return false;
        com.onesay.assistant.v2.pclink.PcConfirm.noteRobotInput();
        ShieldOverlay sh = mHoleHeld ? null : mShield;   // ★ plan-1118：外层点按核对已开着孔就不再开 / 关
        Rect hole = new Rect(x - 32, y - 32, x + 32, y + 32);
        if (sh != null) sh.openHole(hole);
        try {
            injectedTaps++;
            if (mA11yFallback) {   // ★ a11y-1118：没有助手就用无障碍手势（★ plan-1118：跨重绑间隙）
                final int fx = x, fy = y;
                return com.onesay.assistant.v2.a11y.A11yBackend.act(new com.onesay.assistant.v2.a11y.RebindWait.Op<com.onesay.assistant.v2.a11y.A11yBackend, Boolean>() {
                    @Override public Boolean run(com.onesay.assistant.v2.a11y.A11yBackend b) {
                        return b.gestureTap(fx, fy);
                    }
                }, com.onesay.assistant.v2.a11y.RebindWait.MAX_WAIT_MS);
            }
            return mFs.tap(x, y);
        } finally {
            if (sh != null) sh.closeHole();
        }
    }

    @Override public boolean longPress(UiTree.El e, int ms) {
        if (e == null || dead("longPress")) return false;
        ShieldOverlay sh = mShield;
        if (sh != null) sh.openHole(new Rect(e.cx() - 32, e.cy() - 32, e.cx() + 32, e.cy() + 32));
        try {
            if (mA11yFallback) {   // ★ a11y-1118（★ plan-1118：跨重绑间隙）
                final int fx = e.cx(), fy = e.cy(), fms = ms;
                return com.onesay.assistant.v2.a11y.A11yBackend.act(new com.onesay.assistant.v2.a11y.RebindWait.Op<com.onesay.assistant.v2.a11y.A11yBackend, Boolean>() {
                    @Override public Boolean run(com.onesay.assistant.v2.a11y.A11yBackend b) {
                        return b.longPressAt(fx, fy, fms);
                    }
                }, com.onesay.assistant.v2.a11y.RebindWait.MAX_WAIT_MS);
            }
            return mFs.longPress(e.cx(), e.cy(), ms);
        } finally {
            if (sh != null) sh.closeHole();
        }
    }

    @Override public String type(UiTree.El e, String text) {
        if (dead("type")) return null;
        if (mk(1)) return typeVerified(e, text);
        int x = e == null ? 0 : e.cx(), y = e == null ? 0 : e.cy();
        String r = setTextAny(x, y, text);
        if (r != null && r.startsWith("set:")) {
            setTexts++;
            return r.substring(4);
        }
        // 退回：剪贴板 + 粘贴（先点一下让输入框拿到焦点）。★ a11y-labels：粘完还原用户原来的剪贴板（ClipboardGuard），
        // 放进剪贴板的内容标记为敏感（系统剪贴板预览不展示）
        if (e != null) tap(e);
        com.onesay.assistant.plug.action.core.ClipboardGuard.Result cr = com.onesay.assistant.plug.action.core.ClipboardGuard.pasteAndRestore(
                new com.onesay.assistant.plug.action.core.ClipboardGuard.ClipboardPort() {
                    @Override public String get() {
                        return getClip();
                    }

                    @Override public boolean set(String t) {
                        return setClip(t);
                    }
                }, text, new com.onesay.assistant.plug.action.core.ClipboardGuard.Paste() {
                    @Override public boolean paste() {
                        SystemClock.sleep(120);
                        boolean ok = pasteAny();
                        if (ok) waitIdle(200, 1000);
                        return ok;
                    }
                });
        if (!cr.ok) return null;
        pastes++;
        if (cr.restored) clipRestores++;
        FsTree t = treeAny();
        if (t == null) return null;
        for (FsTree.Node n : t.nodes) if (n.has(FsTree.F_EDIT) && n.text.contains(text)) return n.text;
        return "";
    }

    /**
     * ★ mika-plug（mika.actchain）：SET_TEXT + 读回核对（Mika typeAndVerify：不一致再写一次，共 2 次）；
     * 写不进 / 对不上且不是密码框 → 剪贴板粘贴，粘完还原用户原来的剪贴板（ClipboardGuard，全局锁）。返回读回（null = 失败）。
     */
    String typeVerified(final UiTree.El e, final String text) {
        final int x = e == null ? 0 : e.cx(), y = e == null ? 0 : e.cy();
        final String[] last = new String[1];
        final boolean secret = e != null && e.password;
        com.onesay.assistant.plug.screen.core.Accuracy.Verification v = com.onesay.assistant.plug.screen.core.Accuracy.typeAndVerify(
                new com.onesay.assistant.plug.screen.core.Accuracy.TextField() {
                    @Override public String read() {
                        return readField(e);
                    }

                    @Override public boolean write(String t) {
                        String r = setTextAny(x, y, t);
                        if (r != null && r.startsWith("set:")) {
                            last[0] = r.substring(4);
                            setTexts++;
                            return true;
                        }
                        return false;
                    }
                }, text, 2, new Runnable() {
                    @Override public void run() {
                        waitIdle(150, 600);
                    }
                }, null, secret);
        if (v == com.onesay.assistant.plug.screen.core.Accuracy.Verification.MATCH) {
            verifiedTypes++;
            String now = readField(e);
            return now != null ? now : text;
        }
        if (v == com.onesay.assistant.plug.screen.core.Accuracy.Verification.UNKNOWN && last[0] != null) return last[0];
        if (secret) return null;   // 密码框不经剪贴板
        if (e != null) tap(e);
        com.onesay.assistant.plug.action.core.ClipboardGuard.Result r = com.onesay.assistant.plug.action.core.ClipboardGuard.pasteAndRestore(
                new com.onesay.assistant.plug.action.core.ClipboardGuard.ClipboardPort() {
                    @Override public String get() {
                        return getClip();
                    }

                    @Override public boolean set(String t) {
                        return setClip(t);
                    }
                }, text, new com.onesay.assistant.plug.action.core.ClipboardGuard.Paste() {
                    @Override public boolean paste() {
                        SystemClock.sleep(120);
                        boolean ok = pasteAny();
                        if (ok) waitIdle(200, 1000);
                        return ok;
                    }
                });
        if (!r.ok) return null;
        pastes++;
        if (r.restored) clipRestores++;
        String got = readField(e);
        return got == null ? "" : got;
    }

    /** 读回输入框里用户可见的文字（同范围的输入框优先，其次有焦点的输入框；提示语算空）。 */
    private String readField(UiTree.El e) {
        FsTree t = treeAny();
        if (t == null) return null;
        FsTree.Node best = null;
        for (FsTree.Node n : t.nodes) {
            if (!n.has(FsTree.F_EDIT)) continue;
            if (e != null && n.l == e.l && n.t == e.t && n.r == e.r && n.b == e.b) {
                best = n;
                break;
            }
            if (best == null && n.has(FsTree.F_FOCUSED)) best = n;
        }
        if (best == null) return null;
        if (best.has(FsTree.F_PASSWORD)) return null;
        if (best.has(FsTree.F_HINT_SHOWING)) return "";
        if (best.hint != null && best.hint.length() > 0 && best.text.trim().equals(best.hint.trim())) return "";
        return best.text;
    }

    /** ★ a11y-1118：输入 / 粘贴 / 读树按当前后端走（兜底模式只用无障碍）。 */
    private String setTextAny(int x, int y, String text) {
        com.onesay.assistant.v2.a11y.A11yBackend b = fb();
        if (b != null) return b.setTextAt(x, y, text);
        return mFs.setText(x, y, text);
    }

    private boolean pasteAny() {
        com.onesay.assistant.v2.a11y.A11yBackend b = fb();
        if (b != null) return b.pasteFocused();
        return mFs.paste();
    }

    private FsTree treeAny() {
        if (mA11yFallback) {
            com.onesay.assistant.v2.a11y.A11yBackend b = fb();   // ★ plan-1118：跨重绑间隙
            return b == null ? null : b.readTree(1500);
        }
        return mFs.tree();
    }

    private String getClip() {
        final String[] out = {null};
        final CountDownLatch l = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) mApp.getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData c = cm.getPrimaryClip();
                    if (c != null && c.getItemCount() > 0 && c.getItemAt(0).getText() != null) out[0] = c.getItemAt(0).getText().toString();
                } catch (Throwable ignored) {
                }
                l.countDown();
            }
        });
        try {
            l.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return out[0];
    }

    private boolean setClip(final String text) {
        final boolean[] ok = {false};
        final CountDownLatch l = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) mApp.getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData cd = ClipData.newPlainText("onesay", text);
                    try {   // ★ a11y-labels：标记敏感（Android 13 起系统据此不弹剪贴板预览；低版本忽略这个附加信息）
                        android.os.PersistableBundle ex = new android.os.PersistableBundle();
                        ex.putBoolean(SENSITIVE_EXTRA, true);
                        cd.getDescription().setExtras(ex);
                    } catch (Throwable ignored) {
                    }
                    cm.setPrimaryClip(cd);
                    ok[0] = true;
                } catch (Throwable t) {
                    SafeLog.w(TAG, "[PHONE] 设剪贴板失败: " + t);
                }
                l.countDown();
            }
        });
        try {
            l.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return ok[0];
    }

    /** ClipDescription.EXTRA_IS_SENSITIVE（API 33 常量，按字面写，低版本编译也能用）。 */
    static final String SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE";

    @Override public boolean scroll(UiTree.El e, int dir) {
        if (dead("scroll")) return false;
        if (mk(1)) {   // ★ mika-plug：ref 所在的可滚祖先（助手按落点找最深可滚节点）→ 屏中线滑动
            String r = com.onesay.assistant.plug.action.core.ActionChain.scroll(port(), e == null ? -1 : e.src, e == null ? null : new int[]{e.l, e.t, e.r, e.b}, dir, mW, mH);
            return com.onesay.assistant.plug.action.core.ActionChain.SCROLLED.equals(r) || com.onesay.assistant.plug.action.core.ActionChain.SWIPED.equals(r);
        }
        if (e != null && e.scroll) {
            com.onesay.assistant.v2.a11y.A11yBackend b = fb();
            String r = b != null ? b.scrollAt(e.cx(), e.cy(), dir) : mFs.scroll(e.cx(), e.cy(), dir);   // ★ a11y-1118
            if (b != null && r == null) r = swipeDir(e.cx(), dir) ? "swipe" : null;
            return r != null && !r.equals("edge");
        }
        int x = e != null ? e.cx() : mW / 2;
        return swipeDir(x, dir);
    }

    private boolean swipeDir(int x, int dir) {
        return dir > 0 ? swipe(x, mH * 70 / 100, x, mH * 30 / 100, 350) : swipe(x, mH * 30 / 100, x, mH * 70 / 100, 350);
    }

    @Override public boolean swipe(int x1, int y1, int x2, int y2, int ms) {
        if (dead("swipe")) return false;
        com.onesay.assistant.v2.pclink.PcConfirm.noteRobotInput();
        ShieldOverlay sh = mShield;
        if (sh != null) sh.openHole(new Rect(Math.min(x1, x2) - 48, Math.min(y1, y2) - 48, Math.max(x1, x2) + 48, Math.max(y1, y2) + 48));
        try {
            if (mA11yFallback) {   // ★ a11y-1118（★ plan-1118：跨重绑间隙）
                final int a1 = x1, b1 = y1, a2 = x2, b2y = y2, fms = ms;
                return com.onesay.assistant.v2.a11y.A11yBackend.act(new com.onesay.assistant.v2.a11y.RebindWait.Op<com.onesay.assistant.v2.a11y.A11yBackend, Boolean>() {
                    @Override public Boolean run(com.onesay.assistant.v2.a11y.A11yBackend b) {
                        return b.gestureSwipe(a1, b1, a2, b2y, fms);
                    }
                }, com.onesay.assistant.v2.a11y.RebindWait.MAX_WAIT_MS);
            }
            return mFs.swipe(x1, y1, x2, y2, ms);
        } finally {
            if (sh != null) sh.closeHole();
        }
    }

    @Override public boolean key(String key) {
        if (dead("key")) return false;
        if (b2Ready()) {   // ★ mika-plug（B2）：返回 / 主页走 performGlobalAction，不需要 root
            com.onesay.assistant.v2.a11y.A11yBackend b2 = com.onesay.assistant.v2.a11y.A11yBackend.instance();
            if ("back".equals(key) && b2 != null && b2.globalBack()) return true;
            if ("home".equals(key) && b2 != null && b2.globalHome()) return true;
        }
        if (mA11yFallback) {   // ★ a11y-1118：没有助手时回车 / 搜索走输入框的 IME 动作（API 30+），其余键做不了
            com.onesay.assistant.v2.a11y.A11yBackend b = fb();   // ★ plan-1118：跨重绑间隙
            if (b == null) return false;
            if ("back".equals(key)) return b.globalBack();
            if ("home".equals(key)) return b.globalHome();
            if ("enter".equals(key) || "search".equals(key)) return b.imeEnter();
            return false;
        }
        int code;
        if ("back".equals(key)) code = FsProtocol.KEY_BACK;
        else if ("home".equals(key)) code = FsProtocol.KEY_HOME;
        else if ("enter".equals(key)) code = FsProtocol.KEY_ENTER;
        else if ("search".equals(key)) code = 84;
        else if ("delete".equals(key)) code = 67;
        else return false;
        return mFs.key(code);
    }

    /** [fix-1123-dexec B40] 上一次 launch 因为目标已在前台而没重新拉起。 */
    private volatile boolean mLaunchSkipped;

    @Override public boolean lastLaunchSkipped() {
        return mLaunchSkipped;
    }

    /** 当前前台包名（助手 top / 无障碍读树；读不到 = null）。 */
    String foregroundPkg() {
        try {
            if (!mA11yFallback) {
                String top = mFs.top();
                if (top != null && top.length() > 0) {
                    String p = new org.json.JSONObject(top).optString("pkg", "");
                    if (p.length() > 0) return p;
                }
            }
            com.onesay.assistant.v2.a11y.A11yBackend b = com.onesay.assistant.v2.a11y.A11yBackend.instance();
            FsTree t = b == null ? null : b.readTree(1200, false);
            return t == null || t.pkg == null || t.pkg.length() == 0 ? null : t.pkg;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override public boolean launch(String pkg) {
        if (dead("launch")) return false;
        mLaunchSkipped = false;
        String fg = foregroundPkg();
        if (StateFirst.skipLaunch(fg, pkg)) {   // [fix-1123-dexec B40] 已经在前台：不重新拉起（拉起可能把它带回首页），从当前页面接着做
            mLaunchSkipped = true;
            SafeLog.i(TAG, "[PHONE] " + pkg + " 本来就在前台，不重新打开，从当前页面接着做");
            return true;
        }
        try {
            Intent it = mApp.getPackageManager().getLaunchIntentForPackage(pkg);
            if (it != null) {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mApp.startActivity(it);
                return true;
            }
        } catch (Throwable t) {
            SafeLog.w(TAG, "[PHONE] 启动 " + pkg + " 失败，改走助手: " + t);
        }
        if (mA11yFallback) return false;   // ★ a11y-1118
        return FsProtocol.isPkg(pkg) && mFs.launch(pkg);
    }

    @Override public void waitIdle(int idleMs, int timeoutMs) {
        if (mA11yFallback) {   // ★ a11y-1118：无障碍事件安静 idleMs 即返回，最多等 timeoutMs
            long end = SystemClock.elapsedRealtime() + Math.max(0, timeoutMs);
            while (SystemClock.elapsedRealtime() < end && com.onesay.assistant.v2.a11y.A11yBackend.quietForMs() < idleMs) SystemClock.sleep(50);
            return;
        }
        mFs.idle(idleMs, timeoutMs);
    }

    // ------------------------------------------------------------------ ★ mika-plug

    /** B1（UiAutomation 助手）的动作原语。 */
    private com.onesay.assistant.plug.action.core.ActionChain.ActionPort port() {
        return new com.onesay.assistant.plug.action.core.ActionChain.ActionPort() {
            @Override public boolean clickNode(int src, int[] b, String cls, int max) {
                if (dead("clickNode")) return false;
                com.onesay.assistant.v2.a11y.A11yBackend b2 = mLastB2 ? com.onesay.assistant.v2.a11y.A11yBackend.instance() : null;
                if (b2 != null && b2.clickNode(src, b, max)) {
                    a11yClicks++;
                    return true;
                }
                if (mA11yFallback) {   // ★ a11y-1118：没有编号句柄 / 句柄过期 → 按范围中心找节点（找不到返回 false，链条接着走手势）
                    com.onesay.assistant.v2.a11y.A11yBackend f = fb();   // ★ plan-1118：跨重绑间隙
                    if (f != null && f.clickNodeAt((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)) {
                        a11yClicks++;
                        return true;
                    }
                    return false;
                }
                String r = mFs.clickBounds(b[0], b[1], b[2], b[3], cls);
                if (r != null && r.startsWith("clicked")) {
                    boundsClicks++;
                    a11yClicks++;
                    return true;
                }
                // 旧助手不认 clickb / 找不到同范围节点：退回原来的「落点最深的可点节点」
                if (mFs.click((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)) {
                    a11yClicks++;
                    return true;
                }
                return false;
            }

            @Override public boolean gestureTap(int x, int y) {
                if (dead("gestureTap")) return false;
                com.onesay.assistant.v2.a11y.A11yBackend b2 = mA11yFallback ? fb() : b2Ready() ? com.onesay.assistant.v2.a11y.A11yBackend.instance() : null;
                if (b2 == null) return false;   // B1 没有 dispatchGesture，只有 B2 有
                // ★ plan-1118：dispatchGesture 走触摸管线，会被自家护罩吞掉（真机「护罩吞掉误触 @416,1663」）→ 落点开孔
                ShieldOverlay sh = mHoleHeld ? null : mShield;
                if (sh != null) sh.openHole(new Rect(x - 32, y - 32, x + 32, y + 32));
                try {
                    return b2.gestureTap(x, y);
                } finally {
                    if (sh != null) sh.closeHole();
                }
            }

            @Override public boolean injectTap(int x, int y) {
                return FastScreenDriver.this.injectTap(x, y);
            }

            @Override public String scrollNode(int src, int[] b, int dir) {
                com.onesay.assistant.v2.a11y.A11yBackend b2 = mLastB2 ? com.onesay.assistant.v2.a11y.A11yBackend.instance() : null;
                if (b2 != null) {
                    String r2 = b2.scrollNode(src, b, dir);
                    if (r2 != null) return r2;
                }
                if (b == null) return null;
                if (mA11yFallback) {   // ★ a11y-1118
                    com.onesay.assistant.v2.a11y.A11yBackend f = fb();   // ★ plan-1118：跨重绑间隙
                    return f == null ? null : f.scrollAt((b[0] + b[2]) / 2, (b[1] + b[3]) / 2, dir);
                }
                String r = mFs.scroll((b[0] + b[2]) / 2, (b[1] + b[3]) / 2, dir);
                return r == null ? null : r.equals("edge") ? "edge" : "scrolled";
            }

            @Override public boolean swipe(int x1, int y1, int x2, int y2, int ms) {
                return FastScreenDriver.this.swipe(x1, y1, x2, y2, ms);
            }
        };
    }

    @Override public boolean tapXY(int x999, int y999) {
        if (dead("tapXY")) return false;
        int x = com.onesay.assistant.plug.screen.core.UiElement.denormalize(x999, mW), y = com.onesay.assistant.plug.screen.core.UiElement.denormalize(y999, mH);
        return !com.onesay.assistant.plug.action.core.ActionChain.NONE.equals(com.onesay.assistant.plug.action.core.ActionChain.tapXY(port(), x, y));
    }

    @Override public UiTree awaitChange(final UiTree before, final UiTree.Ids ids) {
        changeWaits++;
        final UiTree[] last = new UiTree[1];
        final String sig = before == null ? null : before.signature();
        com.onesay.assistant.plug.screen.core.ChangeWait.awaitStableChange(new com.onesay.assistant.plug.api.Clock() {
            @Override public long elapsed() {
                return SystemClock.elapsedRealtime();
            }

            @Override public long wall() {
                return System.currentTimeMillis();
            }

            @Override public void sleep(long ms) {
                if (ms > 0) SystemClock.sleep(ms);
            }
        }, new com.onesay.assistant.plug.screen.core.ChangeWait.Quiet() {
            @Override public long quietForMs() {
                if (b2Ready()) {
                    long q = com.onesay.assistant.v2.a11y.A11yBackend.quietForMs();
                    return q;
                }
                if (mA11yFallback) return com.onesay.assistant.v2.a11y.A11yBackend.quietForMs();   // ★ a11y-1118
                String r = mFs.idle((int) com.onesay.assistant.plug.screen.core.ChangeWait.QUIET_MS, 600);
                return r != null && r.startsWith("idle") ? com.onesay.assistant.plug.screen.core.ChangeWait.QUIET_MS : 0;
            }
        }, new com.onesay.assistant.plug.screen.core.ChangeWait.TreeChanged() {
            @Override public boolean changed() {
                UiTree t = observe(ids);
                if (t != null) last[0] = t;
                return t != null && sig != null && !t.signature().equals(sig);
            }
        }, 0);
        return last[0] != null ? last[0] : observe(ids);
    }

    @Override public String describeScreen() {
        try {
            if (!com.onesay.assistant.v2.vision.screenref.ScreenRefSettings.vlFallbackEnabled(mApp)) return null;
            // ★ overnight-1119 魅族：没配识屏 Key 就不截屏（截一张要 1 s，截完也传不出去）；PhoneAgent 见到 null 会停止再要识屏
            String key = new com.onesay.assistant.v2.asr.AliyunAsrConfig(mApp).getDashScopeKey();
            if (key == null || key.trim().length() == 0) return null;
            com.onesay.assistant.v2.vision.ScreenVisionEngine.VisionResult vr = com.onesay.assistant.v2.vision.ScreenVisionEngine.getInstance(mApp)
                    .recognizeScreenSync("简要描述当前手机屏幕（忽略边缘的蓝色光晕和顶部的黑色灵动岛）：在哪个 App 的什么页面、主要内容、"
                            + "能点的按钮和它们的大概位置（上/中/下、左/中/右）。不超过 200 字。", 15000);
            return vr != null && vr.ok ? vr.text : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override public long now() {
        return SystemClock.elapsedRealtime();
    }

    @Override public void sleep(long ms) {
        SystemClock.sleep(ms);
    }
}
