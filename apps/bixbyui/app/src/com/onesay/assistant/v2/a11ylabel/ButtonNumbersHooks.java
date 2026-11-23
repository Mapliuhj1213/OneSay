package com.onesay.assistant.v2.a11ylabel;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.onesay.assistant.v2.phone.FastScreenDriver;
import com.onesay.assistant.v2.phone.UiTree;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.util.SafeLog;

/**
 * ★ a11y-labels：「显示按钮」/「N号」的宿主侧（读屏、悬浮层、点按）。语句判别、编号、绑定、敏感判别都在纯 Java 的 {@link ButtonNumbers}。
 * 由 PhonePlugin.interceptCommand 调用（主线程）；读屏与点按在后台线程。
 */
public final class ButtonNumbersHooks {

    private ButtonNumbersHooks() {
    }

    static final String TAG = "BtnNumbers";
    /** 收起面板到读屏之间等多久（面板动画收完，读到的是底下的 App）。 */
    static final long SHEET_GONE_MS = 450L;

    private static volatile ButtonNumbers.Session sSession;
    private static ButtonNumbersOverlay sOverlay;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable AUTO_HIDE = new Runnable() {
        @Override public void run() {
            hideOverlay();
        }
    };

    /** @return true = 这句话归编号层处理了。 */
    public static boolean intercept(final FloatingSheetService svc, String command) {
        try {
            if (svc == null || command == null) return false;
            final Context app = svc.getApplicationContext();
            long now = System.currentTimeMillis();
            ButtonNumbers.Session s = sSession;
            boolean live = s != null && !s.expired(now) && com.onesay.assistant.v2.vision.screenref.NumberedRefs.isLatest(ButtonNumbers.OWNER, now);
            if (ButtonNumbers.isHide(command)) {
                if (!live && (sOverlay == null || !sOverlay.showing())) return false;
                hideOverlay();
                reply(svc, command, "编号收起了。");
                return true;
            }
            if (ButtonNumbers.isShow(command)) {
                svc.s1HideSheetNow();
                com.onesay.assistant.v2.util.Threads.start("btn-numbers-show", new Runnable() {
                    @Override public void run() {
                        sleep(SHEET_GONE_MS);
                        toast(app, showNow(app));
                    }
                });
                return true;
            }
            if (!live) return false;
            final int n = ButtonNumbers.pick(command, s.size());
            if (n == ButtonNumbers.NONE) return false;
            if (n == ButtonNumbers.OUT_OF_RANGE) {
                reply(svc, command, "没有这个号，这页只有 1 到 " + s.size() + " 号。");
                return true;
            }
            svc.s1HideSheetNow();
            com.onesay.assistant.v2.util.Threads.start("btn-numbers-pick", new Runnable() {
                @Override public void run() {
                    sleep(SHEET_GONE_MS);
                    String say = pickNow(app, n);
                    if (say != null && say.length() > 0) toast(app, say);
                }
            });
            return true;
        } catch (Throwable t) {
            SafeLog.w(TAG, "[BTN] 处理失败：" + t.getMessage());
            return false;
        }
    }

    /** 读屏 → 贴标签 → 开编号层 → 画。返回给用户的一句话。后台线程。 */
    static String showNow(Context app) {
        if (com.onesay.assistant.v2.phone.PhoneHooks.busy()) return "正在替你操作手机，做完再标号。";
        if (ScreenLease.get().holder() != null) return ScreenLease.get().busySay();
        FastScreenDriver drv = driver(app);
        UiTree u = drv == null ? null : drv.observe(new UiTree.Ids());
        if (u == null) return "读不到当前界面，没法标号。";
        A11yLabeler.attach(u, null, System.currentTimeMillis());
        final ButtonNumbers.Session s = ButtonNumbers.open(u, System.currentTimeMillis());
        sSession = s;
        SafeLog.i(TAG, "[BTN] 标号 " + s.size() + " 个 snap=" + u.snapshotId + " pkg=" + u.pkg);
        if (s.size() == 0) {
            com.onesay.assistant.v2.vision.screenref.NumberedRefs.withdraw(ButtonNumbers.OWNER);
            postHide();
            return ButtonNumbers.openSay(s);
        }
        com.onesay.assistant.v2.vision.screenref.NumberedRefs.publish(ButtonNumbers.OWNER, s.size(), s.at, ButtonNumbers.TTL_MS);
        final Context ctx = app;
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (sOverlay == null) sOverlay = new ButtonNumbersOverlay(ctx);
                if (!sOverlay.show(s)) {
                    toast(ctx, "没有悬浮窗权限，画不出编号；可以照常说「几号」。");
                }
                MAIN.removeCallbacks(AUTO_HIDE);
                MAIN.postDelayed(AUTO_HIDE, ButtonNumbers.TTL_MS);
            }
        });
        return ButtonNumbers.openSay(s);
    }

    /** 重读 → 绑定 → 点。返回给用户的一句话（点成了返回空串）。后台线程。 */
    static String pickNow(Context app, int n) {
        ButtonNumbers.Session s = sSession;
        FastScreenDriver drv = driver(app);
        UiTree fresh = drv == null ? null : drv.observe(new UiTree.Ids());
        if (fresh != null) A11yLabeler.attach(fresh, null, System.currentTimeMillis());
        ButtonNumbers.Pick p = ButtonNumbers.choose(s, n, fresh, System.currentTimeMillis(), ButtonNumbers.DEFAULT_GUARD);
        if (p.reopen) {
            SafeLog.i(TAG, "[BTN] " + n + " 号绑定失败，重标");
            String again = showNow(app);
            return p.say + (again.startsWith("标好了") ? "" : again);
        }
        if (!p.ok()) return p.say;
        SafeLog.i(TAG, "[BTN] 点 " + n + " 号 " + LabelRender.logLine(fresh, p.target));
        ScreenLease.Lease lease = ScreenLease.get().tryAcquire("编号点选", ScreenLease.SHORT_TTL_MS);
        if (lease == null) return ScreenLease.get().busySay();
        try {
            postHide();
            boolean ok = drv.tap(p.target);
            return ok ? "" : "没点上 " + n + " 号。";
        } finally {
            ScreenLease.get().release(lease);
        }
    }

    static FastScreenDriver driver(Context app) {
        FastScreenDriver drv = new FastScreenDriver(app);
        com.onesay.assistant.v2.phone.MikaExec m = com.onesay.assistant.v2.mikaplug.MikaPlugs.phoneExec(app);
        if (m != null) drv.setMika(m);
        return drv.chooseBackend().backend != com.onesay.assistant.v2.a11y.A11yState.Backend.NONE ? drv : null;   // ★ a11y-1118
    }

    static void hideOverlay() {
        sSession = null;
        com.onesay.assistant.v2.vision.screenref.NumberedRefs.withdraw(ButtonNumbers.OWNER);
        postHide();
    }

    static void postHide() {
        MAIN.post(new Runnable() {
            @Override public void run() {
                MAIN.removeCallbacks(AUTO_HIDE);
                if (sOverlay != null) sOverlay.hide();
            }
        });
    }

    static void reply(FloatingSheetService svc, String q, String a) {
        com.onesay.assistant.v2.ui.FloatingSheetView v = svc.getSheetView();
        if (v != null) v.showAskReply(q, a);
    }

    static void toast(final Context app, final String text) {
        if (text == null || text.length() == 0) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(app, text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
