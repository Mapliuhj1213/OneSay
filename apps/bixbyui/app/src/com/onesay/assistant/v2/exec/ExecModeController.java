package com.onesay.assistant.v2.exec;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.onesay.assistant.v2.fastscreen.FastScreen;
import com.onesay.assistant.v2.island.IslandView;
import com.onesay.assistant.v2.phone.FastScreenDriver;
import com.onesay.assistant.v2.phone.PhoneAgent;
import com.onesay.assistant.v2.s1.IslandController;
import com.onesay.assistant.v2.s1.S1Hooks;

import java.util.concurrent.TimeUnit;

/**
 * ★ r2/fast-screen：执行模式（用户 2026-10-31 批准「灵动岛取代小窗」）——{@link PhoneAgent.Host} 的真机实现。
 *
 * <ol>
 *   <li>开始：结果卡 / 小窗（FloatingSheet）收起；状态全进灵动岛（红点 + 被操作 App 图标 + 步骤短词 + 进度环）；</li>
 *   <li>前台执行时铺全屏透明护罩（{@link ShieldOverlay}，FLAG_NOT_FOCUSABLE，吞误触 + 边缘光晕，不盖岛）；</li>
 *   <li>点岛展开「补充 / 接管 / 停止」：接管 = 暂停 + 撤护罩（光晕变细边框）+ 岛显示「继续」；继续 = 重新读屏后恢复；
 *       停止 = 结束并撤掉一切覆盖层；导航栏返回 / 主页键（助手读触摸屏原始事件看到）= 紧急接管，不是停止；</li>
 *   <li>敏感步骤（付款 / 登录 / 验证 / 发送 / 删除）自动暂停，岛展开确认卡；</li>
 *   <li>完成：岛显示「完成」+ 模板短句（{@link ExecVoice}），点岛展开文字结果卡。</li>
 * </ol>
 * 不依赖无障碍服务：岛 / 护罩都是 TYPE_APPLICATION_OVERLAY；读屏 / 注入 / 返回键观察全在 root 助手里。
 */
public final class ExecModeController implements PhoneAgent.Host, ExecBridge.Listener, com.onesay.assistant.v2.kernel.IslandHost.Listener {

    private static final String TAG = "ExecMode";
    public static final String OWNER = "phone";

    private final Context mApp;
    private final WindowManager mWm;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    public final ExecState state = new ExecState();
    private final String mId;
    private final String mTitle;
    private volatile String mPkg;
    private ShieldOverlay mShield;
    private FastScreenDriver mDriver;
    private Thread mNavPoll;
    private final Object mWait = new Object();
    private volatile Boolean mConfirmAnswer;
    private View mSupplementView;
    /** 统计（报告）：导航键接手、护罩吞掉的误触、确认次数。 */
    public volatile int navBack, navHome, confirms;
    public final java.util.List<String> events = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

    private static volatile ExecModeController sCurrent;

    public static ExecModeController current() {
        return sCurrent;
    }

    public ExecModeController(Context ctx, String title, String pkg) {
        mApp = ctx.getApplicationContext();
        mWm = (WindowManager) mApp.getSystemService(Context.WINDOW_SERVICE);
        mId = "exec-" + SystemClock.elapsedRealtime();
        mTitle = title == null ? "替你操作" : title;
        mPkg = pkg;
    }

    public String id() {
        return mId;
    }

    void ev(String s) {
        events.add(SystemClock.elapsedRealtime() + " " + s);
        Log.i(TAG, "[EXEC] " + s);
    }

    // ------------------------------------------------------------------ 生命周期

    /** 进入执行模式（后台线程调用；内部切主线程）。 */
    public void begin(final FastScreenDriver driver) {
        mDriver = driver;
        state.start();
        sCurrent = this;
        ExecBridge.attach(mId, this);
        final IslandController isl = S1Hooks.island();
        runMainSync(new Runnable() {
            @Override public void run() {
                // ① 小窗收起
                com.onesay.assistant.v2.ui.FloatingSheetService svc = com.onesay.assistant.v2.ui.FloatingSheetService.getInstance();
                if (svc != null) {
                    try {
                        svc.s1YieldSheetForExec();   // ★ 11-04 B：只让位，不把正在等这一步结果的 AgentLoop 当成「用户关面板」取消
                    } catch (Throwable t) {
                        Log.w(TAG, "[EXEC] 收起小窗失败: " + t);
                    }
                }
                // ② 岛
                if (isl != null) {
                    isl.setScopedListener(OWNER, ExecModeController.this);
                    isl.execStarted(OWNER, mId, mTitle, mPkg);
                }
                // ③ 护罩
                mShield = new ShieldOverlay(mApp, mWm, new ShieldOverlay.Env() {
                    @Override public Rect islandRect() {
                        IslandController i = S1Hooks.island();
                        return i == null ? new Rect() : i.islandWindowRect();
                    }

                    @Override public void onStray(float x, float y) {
                        ev("护罩吞掉误触 @" + (int) x + "," + (int) y);
                        if (state.onStrayTouch(SystemClock.elapsedRealtime())) {
                            Toast.makeText(mApp, "AI 正在操作，点灵动岛可接管", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
                mShield.show(false);
            }
        });
        driver.setShield(mShield);
        // ④ 导航键观察（返回 / 主页 = 紧急接管）
        String w = FastScreen.get(mApp).backWatch();
        ev("导航键观察：" + w);
        startNavPoll();
        ev("进入执行模式 " + mTitle + " pkg=" + mPkg);
    }

    private void startNavPoll() {
        mNavPoll = com.onesay.assistant.v2.util.Threads.start("ExecNavPoll", new Runnable() {
            @Override public void run() {
                FastScreen fs = FastScreen.get(mApp);
                while (state.state() != ExecState.S.ENDED && !Thread.currentThread().isInterrupted()) {
                    SystemClock.sleep(300);
                    if (state.state() == ExecState.S.ENDED) break;
                    String r = fs.takeNavTouches();
                    if (r == null) continue;
                    int b = num(r, "back="), h = num(r, "home=");
                    if (b > 0 || h > 0) {
                        navBack += b;
                        navHome += h;
                        String which = b > 0 ? "按了返回键" : "按了主页键";
                        if (state.navKey(which)) {
                            ev("导航键紧急接管：" + which);
                            applyPaused(which);
                        } else ev("导航键（" + which + "）：当前状态 " + state.state() + "，不叠加接手");
                    }
                }
            }
        });
    }

    static int num(String s, String key) {
        int k = s.indexOf(key);
        if (k < 0) return 0;
        int e = k + key.length();
        while (e < s.length() && Character.isDigit(s.charAt(e))) e++;
        try {
            return Integer.parseInt(s.substring(k + key.length(), e));
        } catch (NumberFormatException x) {
            return 0;
        }
    }

    /** 结束：撤掉一切覆盖层、岛显示完成 / 没完成 + 结果卡、播模板短句。 */
    public void finish(final boolean ok, final String resultText) {
        state.end();
        if (mNavPoll != null) mNavPoll.interrupt();
        FastScreen.get(mApp).backWatchOff();
        ExecBridge.detach(mId);
        if (sCurrent == this) sCurrent = null;
        synchronized (mWait) {
            mWait.notifyAll();
        }
        runMainSync(new Runnable() {
            @Override public void run() {
                removeSupplement();
                if (mShield != null) mShield.hide();
                IslandController isl = S1Hooks.island();
                if (isl != null) isl.execDone(mId, ok, ok ? "完成" : "没完成", resultText == null || resultText.length() == 0
                        ? (ok ? "已完成" : "没有完成") : resultText);
            }
        });
        // ★ arch/voice-sync：AgentLoop 里的点界面（phone.operate）收尾不单独说 —— 任务结局统一说一次，免得「好了」连说两遍
        if (!S1Hooks.voiceSyncOwnsOutcome()) ExecVoice.play(mApp, ExecVoice.forResult(ok, state.stopped()));
        ev("结束 ok=" + ok + " 误触=" + state.strayTouches + " 导航接手=" + state.navTakeovers + " 结果=" + resultText);
    }

    // ------------------------------------------------------------------ PhoneAgent.Host

    @Override public void onStep(String shortWord, int stepNo) {
        IslandController isl = S1Hooks.island();
        if (isl != null) isl.execStep(mId, shortWord, mPkg);
    }

    /** 被操作 App 换了（岛上图标跟着换）。 */
    public void setPkg(String pkg) {
        if (pkg != null && pkg.length() > 0 && !pkg.equals(mApp.getPackageName())) mPkg = pkg;
    }

    @Override public boolean confirm(String title, String detail, String[] answerOut) {
        if (!state.confirmBegin()) {
            // 正在接手 / 补充：先等用户继续
            if (state.paused()) {
                String[] sup = new String[1];
                if (awaitResume("等你继续", sup) == PhoneAgent.Resume.STOP) return false;
                if (!state.confirmBegin()) return false;
            } else return false;
        }
        confirms++;
        mConfirmAnswer = null;
        ev("敏感步骤暂停：" + detail);
        IslandController isl = S1Hooks.island();
        if (isl != null) isl.bgConfirm(OWNER, mId, title, detail);
        ExecVoice.play(mApp, com.onesay.assistant.v2.tts.VoiceTemplate.CONFIRM);
        long end = SystemClock.elapsedRealtime() + 5 * 60_000L;
        synchronized (mWait) {
            while (mConfirmAnswer == null && !state.stopped() && SystemClock.elapsedRealtime() < end) {
                try {
                    mWait.wait(500);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        boolean ok = Boolean.TRUE.equals(mConfirmAnswer);
        state.confirmEnd();
        if (isl != null) isl.bgConfirmResolved(OWNER, mId);
        ev("确认结果：" + (mConfirmAnswer == null ? "超时 / 停止" : ok ? "允许（单次）" : "拒绝"));
        return ok && !state.stopped();
    }

    @Override public String takeoverRequested() {
        return state.takePendingTakeover();
    }

    @Override public PhoneAgent.Resume awaitResume(String why, String[] supplementOut) {
        if (!state.paused() && !state.stopped() && state.consumeResumedEarly()) {
            // ★ fast-screen-v3：用户在代理取走接手 / 补充之前已经继续了 → 不再暂停，直接带补充继续
            supplementOut[0] = state.takeSupplement();
            applyResumed();
            ev("继续（用户已提前继续）" + (supplementOut[0] != null ? "（补充：" + supplementOut[0] + "）" : ""));
            return PhoneAgent.Resume.RESUME;
        }
        if (!state.paused()) {
            // 卡住 / 预算到了：代理主动请求暂停
            state.takeover(why);
            state.takePendingTakeover();
            applyPaused(why);
        }
        ev("暂停等待继续：" + why);
        synchronized (mWait) {
            while (state.paused() && !state.stopped()) {
                try {
                    mWait.wait(500);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        if (state.stopped()) return PhoneAgent.Resume.STOP;
        supplementOut[0] = state.takeSupplement();
        applyResumed();
        ev("继续" + (supplementOut[0] != null ? "（补充：" + supplementOut[0] + "）" : ""));
        return PhoneAgent.Resume.RESUME;
    }

    @Override public boolean stopRequested() {
        return state.stopped();
    }

    // ------------------------------------------------------------------ 岛按钮 / 确认 / 停止

    @Override public void onIslandAction(String id, String action) {
        if (IslandView.A_EXEC_TAKEOVER.equals(action)) {
            if (state.takeover("你点了接管")) {
                ev("岛上点了接管");
                applyPaused("你在操作，好了点继续");
            }
        } else if (IslandView.A_EXEC_RESUME.equals(action)) {
            if (state.resume()) {
                ev("岛上点了继续");
                applyResumed();   // ★ fast-screen-v3：马上收回护罩 / 刷新岛，不等代理主循环
                synchronized (mWait) {
                    mWait.notifyAll();
                }
            }
        } else if (IslandView.A_EXEC_SUPPLEMENT.equals(action)) {
            if (state.supplement()) {
                ev("岛上点了补充");
                applyPaused("补充说明中");
                mMain.post(new Runnable() {
                    @Override public void run() {
                        showSupplement();
                    }
                });
            }
        }
    }

    @Override public void onBgConfirm(String id, boolean allow) {
        mConfirmAnswer = allow;
        synchronized (mWait) {
            mWait.notifyAll();
        }
    }

    @Override public void onBgStop(String id) {
        stop("岛上点了停止");
    }

    public void stop(String why) {
        ev("停止：" + why);
        state.stop();
        synchronized (mWait) {
            mWait.notifyAll();
        }
        mMain.post(new Runnable() {
            @Override public void run() {
                removeSupplement();
                if (mShield != null) mShield.hide();   // 停止 = 立刻撤掉一切覆盖层（finish 会再做一次，幂等）
            }
        });
    }

    @Override public void onRemindDone(String id) {
    }

    @Override public void onRemindSnooze(String id) {
    }

    @Override public void onTodoCheck(String id) {
    }

    private void applyPaused(final String why) {
        IslandController isl = S1Hooks.island();
        if (isl != null) isl.execPaused(mId, why);
        mMain.post(new Runnable() {
            @Override public void run() {
                if (mShield != null && state.state() != ExecState.S.ENDED) mShield.show(true);   // 细边框、不拦触摸
            }
        });
    }

    private void applyResumed() {
        IslandController isl = S1Hooks.island();
        if (isl != null) isl.execResumed(mId);
        runMainSync(new Runnable() {
            @Override public void run() {
                removeSupplement();
                if (mShield != null && state.state() == ExecState.S.RUNNING) mShield.show(false);
            }
        });
    }

    // ------------------------------------------------------------------ 补充输入框（暂停时才弹，可获焦）

    private void showSupplement() {
        if (mSupplementView != null || state.state() != ExecState.S.SUPPLEMENT) return;
        float d = mApp.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(mApp);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xF0202020);
        int pad = (int) (14 * d);
        box.setPadding(pad, pad, pad, pad);
        TextView tv = new TextView(mApp);
        tv.setText("补充说明（会带着它继续做）");
        tv.setTextColor(0xFFFFFFFF);
        box.addView(tv);
        final EditText et = new EditText(mApp);
        et.setTextColor(0xFFFFFFFF);
        et.setHint("比如：选第二个 / 用北京的");
        et.setHintTextColor(0x99FFFFFF);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        box.addView(et);
        Button ok = new Button(mApp);
        ok.setText("带着补充继续");
        box.addView(ok);
        final Runnable submit = new Runnable() {
            @Override public void run() {
                submitSupplement(et.getText().toString());
            }
        };
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                submit.run();
            }
        });
        et.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                submit.run();
                return true;
            }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT, com.onesay.assistant.v2.s1.S1Overlay.windowType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        // ★ r2/kb-fix：Android 8 overlay 上 ADJUST_RESIZE 不抬贴底 wrap 窗（真机：键盘盖住整个补充框）——
        //   改成 ADJUST_NOTHING，由探针窗量键盘上沿，自己把 y（贴底时 = 离屏底的距离）设成键盘高度
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;
        lp.setTitle("OneSay-ExecSupplement");
        try {
            mWm.addView(box, lp);
            mSupplementView = box;
            mSupplementLp = lp;
            com.onesay.assistant.v2.ui.ime.ImeProbeWindow.get(mApp).acquire(mSupplementIme);
            et.requestFocus();
            et.postDelayed(new Runnable() {
                @Override public void run() {
                    InputMethodManager imm = (InputMethodManager) mApp.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 150);
        } catch (Throwable t) {
            Log.w(TAG, "[EXEC] 补充输入框挂不出: " + t);
        }
    }

    /** 补充提交（输入框 / 测试广播）。 */
    public void submitSupplement(String text) {
        state.supplementText(text == null ? "" : text.trim());
        ev("补充提交：" + text);
        if (state.resume()) {
            applyResumed();   // ★ fast-screen-v3：马上恢复全屏护罩 / 刷新岛（真机：提交后岛仍显示「补充说明中」、护罩仍是细边框）
            synchronized (mWait) {
                mWait.notifyAll();
            }
        }
        mMain.post(new Runnable() {
            @Override public void run() {
                removeSupplement();
            }
        });
    }

    private WindowManager.LayoutParams mSupplementLp;
    private int mSupplementRestBottom = -1;

    /** ★ r2/kb-fix：键盘变化 → 补充框 y = 抬升量（键盘收起 → 0，复位不留空白）。 */
    private final com.onesay.assistant.v2.ui.ime.ImeProbeWindow.Listener mSupplementIme =
            new com.onesay.assistant.v2.ui.ime.ImeProbeWindow.Listener() {
                @Override public void onImeChanged(int imeTop, int baseBottom, int screenH) {
                    View v = mSupplementView;
                    WindowManager.LayoutParams lp = mSupplementLp;
                    if (v == null || lp == null) return;
                    int rest = baseBottom;   // 贴底窗不抬时的底边 = 可见区基线（导航栏上沿）
                    if (v.getWindowToken() != null && v.getHeight() > 0) {
                        int[] loc = new int[2];
                        v.getLocationOnScreen(loc);
                        int actual = loc[1] + v.getHeight() + lp.y;   // 扣掉自己加的偏移还原「不抬」时的底边
                        if (actual > 0) rest = actual;
                    }
                    if (rest > 0) mSupplementRestBottom = rest;
                    int lift = com.onesay.assistant.v2.ui.ime.ImeGeometry.liftFor(mSupplementRestBottom, imeTop, 0, screenH * 3 / 4);
                    if (!com.onesay.assistant.v2.ui.ime.ImeGeometry.changed(lp.y, lift)) return;
                    lp.y = lift;
                    try {
                        mWm.updateViewLayout(v, lp);
                    } catch (Throwable t) {
                        return;
                    }
                    Log.i(TAG, "[EXEC] 补充框让位 → y=" + lift + "（键盘上沿 " + imeTop + "，基线 " + mSupplementRestBottom + "）");
                }
            };

    private void removeSupplement() {
        if (mSupplementView == null) return;
        com.onesay.assistant.v2.ui.ime.ImeProbeWindow.get(mApp).release(mSupplementIme);
        mSupplementLp = null;
        mSupplementRestBottom = -1;
        try {
            mWm.removeViewImmediate(mSupplementView);
        } catch (Throwable ignored) {
        }
        mSupplementView = null;
    }

    public boolean supplementShowing() {
        return mSupplementView != null;
    }

    public ShieldOverlay shield() {
        return mShield;
    }

    // ------------------------------------------------------------------ 工具

    private void runMainSync(final Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
            return;
        }
        final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
        mMain.post(new Runnable() {
            @Override public void run() {
                try {
                    r.run();
                } finally {
                    l.countDown();
                }
            }
        });
        try {
            l.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }
}
