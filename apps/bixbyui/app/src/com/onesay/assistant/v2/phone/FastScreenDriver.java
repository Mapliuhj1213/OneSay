package com.onesay.assistant.v2.phone;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
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
public final class FastScreenDriver implements PhoneAgent.MikaDriver {

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

    /** 助手可用（必要时经守护拉起）。 */
    /** ★ mika-plug（mika.a11y）：B2 无障碍服务后端可用（插件开 + 用户在系统设置里打开了服务）。 */
    public boolean b2Ready() {
        MikaExec m = mMika;
        return m != null && m.f.a11y && com.onesay.assistant.v2.a11y.A11yBackend.connected();
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
        if (b2Ready()) {   // ★ mika-plug：B2 后端（可选，默认关）；读不到退回助手
            t = com.onesay.assistant.v2.a11y.A11yBackend.instance().readTree(1500);
            mLastB2 = t != null;
        }
        if (t == null) t = mx != null && mx.f.screen ? mFs.tree(mx.f.allWindows) : mFs.tree();
        long ms = SystemClock.elapsedRealtime() - t0;
        observes++;
        observeMsTotal += ms;
        observeMsMax = Math.max(observeMsMax, ms);
        if (t == null) {
            Log.w(TAG, "[PHONE] 读屏失败：" + mFs.lastError());
            return null;
        }
        UiTree u = mx != null && mx.f.screen ? mx.build(t, ids, mW, mH) : UiTree.from(t, ids, mW, mH);   // ★ mika-plug：Mika 元素表
        Log.i(TAG, "[PHONE] 读屏 " + ms + "ms（助手内 " + t.ms + "ms）pkg=" + u.pkg + " 元素=" + u.els.size() + " 质量=" + u.quality()
                + (u.ime ? " ime" : ""));
        return u;
    }

    @Override public boolean tap(UiTree.El e) {
        if (e == null) return false;
        com.onesay.assistant.v2.pclink.PcConfirm.noteRobotInput();   // approve-not-block：机器人在点，这一会儿确认卡上的触摸不算真人
        if (mk(1)) {   // ★ mika-plug（mika.actchain）：节点或 ≤8 层可点祖先 ACTION_CLICK → （B1 无手势）→ 注入
            String how = com.onesay.assistant.plug.action.core.ActionChain.tap(port(), e.src, new int[]{e.l, e.t, e.r, e.b}, e.cls);
            Log.i(TAG, "[PHONE] 点按 " + how);
            return !com.onesay.assistant.plug.action.core.ActionChain.NONE.equals(how);
        }
        if (mFs.click(e.cx(), e.cy())) {
            a11yClicks++;
            return true;
        }
        return injectTap(e.cx(), e.cy());
    }

    /** 注入点按（护罩开孔 → 注入 → 补孔）。测试也直接调。 */
    public boolean injectTap(int x, int y) {
        com.onesay.assistant.v2.pclink.PcConfirm.noteRobotInput();
        ShieldOverlay sh = mShield;
        Rect hole = new Rect(x - 32, y - 32, x + 32, y + 32);
        if (sh != null) sh.openHole(hole);
        try {
            injectedTaps++;
            return mFs.tap(x, y);
        } finally {
            if (sh != null) sh.closeHole();
        }
    }

    @Override public boolean longPress(UiTree.El e, int ms) {
        if (e == null) return false;
        ShieldOverlay sh = mShield;
        if (sh != null) sh.openHole(new Rect(e.cx() - 32, e.cy() - 32, e.cx() + 32, e.cy() + 32));
        try {
            return mFs.longPress(e.cx(), e.cy(), ms);
        } finally {
            if (sh != null) sh.closeHole();
        }
    }

    @Override public String type(UiTree.El e, String text) {
        if (mk(1)) return typeVerified(e, text);
        int x = e == null ? 0 : e.cx(), y = e == null ? 0 : e.cy();
        String r = mFs.setText(x, y, text);
        if (r != null && r.startsWith("set:")) {
            setTexts++;
            return r.substring(4);
        }
        // 退回：剪贴板 + 粘贴（先点一下让输入框拿到焦点）
        if (e != null) tap(e);
        if (!setClip(text)) return null;
        SystemClock.sleep(120);
        if (!mFs.paste()) return null;
        pastes++;
        mFs.idle(200, 1000);
        FsTree t = mFs.tree();
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
                        String r = mFs.setText(x, y, t);
                        if (r != null && r.startsWith("set:")) {
                            last[0] = r.substring(4);
                            setTexts++;
                            return true;
                        }
                        return false;
                    }
                }, text, 2, new Runnable() {
                    @Override public void run() {
                        mFs.idle(150, 600);
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
                        boolean ok = mFs.paste();
                        if (ok) mFs.idle(200, 1000);
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
        FsTree t = mFs.tree();
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
                    cm.setPrimaryClip(ClipData.newPlainText("bixbyui", text));
                    ok[0] = true;
                } catch (Throwable t) {
                    Log.w(TAG, "[PHONE] 设剪贴板失败: " + t);
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

    @Override public boolean scroll(UiTree.El e, int dir) {
        if (mk(1)) {   // ★ mika-plug：ref 所在的可滚祖先（助手按落点找最深可滚节点）→ 屏中线滑动
            String r = com.onesay.assistant.plug.action.core.ActionChain.scroll(port(), e == null ? -1 : e.src, e == null ? null : new int[]{e.l, e.t, e.r, e.b}, dir, mW, mH);
            return com.onesay.assistant.plug.action.core.ActionChain.SCROLLED.equals(r) || com.onesay.assistant.plug.action.core.ActionChain.SWIPED.equals(r);
        }
        if (e != null && e.scroll) {
            String r = mFs.scroll(e.cx(), e.cy(), dir);
            return r != null && !r.equals("edge");
        }
        int x = e != null ? e.cx() : mW / 2;
        return dir > 0 ? swipe(x, mH * 70 / 100, x, mH * 30 / 100, 350) : swipe(x, mH * 30 / 100, x, mH * 70 / 100, 350);
    }

    @Override public boolean swipe(int x1, int y1, int x2, int y2, int ms) {
        com.onesay.assistant.v2.pclink.PcConfirm.noteRobotInput();
        ShieldOverlay sh = mShield;
        if (sh != null) sh.openHole(new Rect(Math.min(x1, x2) - 48, Math.min(y1, y2) - 48, Math.max(x1, x2) + 48, Math.max(y1, y2) + 48));
        try {
            return mFs.swipe(x1, y1, x2, y2, ms);
        } finally {
            if (sh != null) sh.closeHole();
        }
    }

    @Override public boolean key(String key) {
        if (b2Ready()) {   // ★ mika-plug（B2）：返回 / 主页走 performGlobalAction，不需要 root
            com.onesay.assistant.v2.a11y.A11yBackend b2 = com.onesay.assistant.v2.a11y.A11yBackend.instance();
            if ("back".equals(key) && b2 != null && b2.globalBack()) return true;
            if ("home".equals(key) && b2 != null && b2.globalHome()) return true;
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

    @Override public boolean launch(String pkg) {
        try {
            Intent it = mApp.getPackageManager().getLaunchIntentForPackage(pkg);
            if (it != null) {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mApp.startActivity(it);
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "[PHONE] 启动 " + pkg + " 失败，改走助手: " + t);
        }
        return FsProtocol.isPkg(pkg) && mFs.launch(pkg);
    }

    @Override public void waitIdle(int idleMs, int timeoutMs) {
        mFs.idle(idleMs, timeoutMs);
    }

    // ------------------------------------------------------------------ ★ mika-plug

    /** B1（UiAutomation 助手）的动作原语。 */
    private com.onesay.assistant.plug.action.core.ActionChain.ActionPort port() {
        return new com.onesay.assistant.plug.action.core.ActionChain.ActionPort() {
            @Override public boolean clickNode(int src, int[] b, String cls, int max) {
                com.onesay.assistant.v2.a11y.A11yBackend b2 = mLastB2 ? com.onesay.assistant.v2.a11y.A11yBackend.instance() : null;
                if (b2 != null && b2.clickNode(src, b, max)) {
                    a11yClicks++;
                    return true;
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
                com.onesay.assistant.v2.a11y.A11yBackend b2 = b2Ready() ? com.onesay.assistant.v2.a11y.A11yBackend.instance() : null;
                return b2 != null && b2.gestureTap(x, y);   // B1 没有 dispatchGesture，只有 B2 有
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
                String r = mFs.scroll((b[0] + b[2]) / 2, (b[1] + b[3]) / 2, dir);
                return r == null ? null : r.equals("edge") ? "edge" : "scrolled";
            }

            @Override public boolean swipe(int x1, int y1, int x2, int y2, int ms) {
                return FastScreenDriver.this.swipe(x1, y1, x2, y2, ms);
            }
        };
    }

    @Override public boolean tapXY(int x999, int y999) {
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
