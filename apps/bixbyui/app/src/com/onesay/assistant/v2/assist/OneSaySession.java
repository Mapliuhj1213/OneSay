package com.onesay.assistant.v2.assist;

import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;

import com.onesay.assistant.v2.entry.BuiltinEntries;
import com.onesay.assistant.v2.entry.Entries;
import com.onesay.assistant.v2.vision.screenref.ScreenContext;
import com.onesay.assistant.v2.vision.screenref.ScreenContextCapture;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * ★ r2/sysux P2-1/P2-2：不画界面的 session（setUiEnabled(false)）。
 * <ol>
 *   <li>系统召唤（长按 Home / 电源键 / 角落上滑）→ {@link Entries#summonVoice}（和快捷开关 / 小部件点一下同一条路），弹出现有唤醒卡片；
 *       ★ feat/assist-role：onShow 和 onHandleAssist 都可能来，经 {@link AssistTriggerGate} 只唤起一次；</li>
 *   <li>{@link #onHandleAssist}：AssistStructure → {@link AssistNodeFlattener} → {@link ScreenContext#fromAssistNodes} →
 *       {@link ScreenContextCapture#put}（识屏管线优先用它；拿不到 / 太空时 ScreenRefHooks 照旧 root uiautomator 回退）；</li>
 *   <li>{@link #onHandleScreenshot}：截图只留在内存（{@link AssistScreenshot}，给 VL 兜底用，过期即丢，不落盘）；</li>
 *   <li>数据收齐或 {@link AssistEntryPolicy#SESSION_MAX_MS} 到点 → hide()。</li>
 * </ol>
 */
public class OneSaySession extends VoiceInteractionSession {

    private static final String TAG = "BixbyVIS";
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private boolean mGotAssist, mGotShot;
    private long mShownAt;
    private String mSrc;
    /** ★ feat/assist-role：这次显示是不是系统召唤（要唤起卡片）；只取上下文的显示不唤起。 */
    private boolean mWakeMode;
    private AssistTriggerGate.Once mOnce = new AssistTriggerGate.Once();

    public OneSaySession(Context ctx) {
        super(ctx);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try { setUiEnabled(false); } catch (Throwable t) { Log.w(TAG, "[SESSION] setUiEnabled(false) 失败: " + t); }
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        mShownAt = SystemClock.elapsedRealtime();
        mGotAssist = (showFlags & SHOW_WITH_ASSIST) == 0;
        mGotShot = (showFlags & SHOW_WITH_SCREENSHOT) == 0;
        String ctxSrc = args != null ? args.getString(AssistEntryPolicy.ARG_SRC) : null;
        mOnce = new AssistTriggerGate.Once();
        if (AssistEntryPolicy.onShow(ctxSrc) == AssistEntryPolicy.OnShow.WAKE) {
            mSrc = AssistEntryPolicy.SRC_SYSTEM;
            mWakeMode = true;
            summonOnce("onShow flags=0x" + Integer.toHexString(showFlags));
        } else {
            mSrc = ctxSrc;
            mWakeMode = false;
            Log.i(TAG, "[SESSION] 只取上下文（唤醒 src=" + ctxSrc + "，flags=0x" + Integer.toHexString(showFlags) + "）");
        }
        mMain.removeCallbacks(mHideRunnable);
        mMain.postDelayed(mHideRunnable, AssistEntryPolicy.SESSION_MAX_MS);
        maybeHide();
    }

    private final Runnable mHideRunnable = new Runnable() {
        @Override public void run() {
            Log.i(TAG, "[SESSION] " + AssistEntryPolicy.SESSION_MAX_MS + "ms 到点收起（assist=" + mGotAssist + " shot=" + mGotShot + "）");
            hideSafe();
        }
    };

    @Override
    public void onHandleAssist(Bundle data, AssistStructure structure, AssistContent content) {
        long t0 = SystemClock.elapsedRealtime();
        if (mWakeMode) summonOnce("onHandleAssist");   // 个别系统不走 onShow 直接给数据：这里兜一次（已唤起过会被闸门挡掉）
        try {
            if (structure == null) {
                Log.i(TAG, "[ASSIST] 系统没给 AssistStructure（安全窗口 / 设置关 / 锁屏）→ 识屏回退 uiautomator");
            } else {
                ScreenContext sc = toScreenContext(getContext(), structure, t0 - mShownAt);
                Log.i(TAG, "[ASSIST] 结构 " + (SystemClock.elapsedRealtime() - t0) + "ms（show→到达 " + (t0 - mShownAt) + "ms）src=" + mSrc
                        + " app=" + sc.pkg + "/" + sc.activity + " texts=" + sc.texts.size() + " entities=" + sc.entities.size()
                        + " redacted=" + sc.redacted + " source=" + sc.source + (sc.error != null ? " note=" + sc.error : ""));
                if (ScreenContext.SRC_ASSIST.equals(sc.source)) ScreenContextCapture.put(sc);
            }
        } catch (Throwable t) {
            Log.w(TAG, "[ASSIST] 处理结构异常（回退 uiautomator）: " + t);
        }
        mGotAssist = true;
        maybeHide();
    }

    @Override
    public void onHandleScreenshot(Bitmap screenshot) {
        if (screenshot != null) {
            AssistScreenshot.put(screenshot);
            Log.i(TAG, "[ASSIST] 截图 " + screenshot.getWidth() + "x" + screenshot.getHeight() + "（只在内存，" + AssistScreenshot.TTL_MS + "ms 后丢弃）");
        } else {
            Log.i(TAG, "[ASSIST] 系统没给截图（FLAG_SECURE / 设置关）");
        }
        mGotShot = true;
        maybeHide();
    }

    /** ★ feat/assist-role：系统召唤 → 唤起现有唤醒卡片，同一次按键只唤起一次。 */
    private void summonOnce(String why) {
        Context c = getContext();
        if (!Entries.canFire(c, BuiltinEntries.ASSISTANT)) {
            Log.i(TAG, "[SESSION] 系统召唤（" + why + "），但「默认数字助理」入口已在设置里关闭 → 忽略");
            return;
        }
        if (!mOnce.tryFire(AssistTriggerGate.SHARED, SystemClock.elapsedRealtime())) {
            Log.i(TAG, "[SESSION] 系统召唤（" + why + "）重复，已唤起过 → 忽略");
            return;
        }
        Log.i(TAG, "[SESSION] 系统召唤（" + why + "）→ 唤起唤醒卡片");
        try {
            Entries.summonVoice(c, AssistEntryPolicy.SRC_SYSTEM);
        } catch (Throwable t) {
            Log.w(TAG, "[SESSION] 唤起失败: " + t);
        }
    }

    private void maybeHide() {
        if (mGotAssist && mGotShot) {
            mMain.removeCallbacks(mHideRunnable);
            hideSafe();
        }
    }

    private void hideSafe() {
        try { hide(); } catch (Throwable ignore) {}
    }

    @Override
    public void onHide() {
        mMain.removeCallbacks(mHideRunnable);
        super.onHide();
    }

    /** 遍历窗口 / 视图树 → 绝对坐标的 Raw 节点 → 识屏上下文。 */
    static ScreenContext toScreenContext(Context c, AssistStructure st, long costMs) {
        List<AssistNodeFlattener.Raw> raws = new ArrayList<AssistNodeFlattener.Raw>();
        ComponentName act = st.getActivityComponent();
        String pkg = act != null ? act.getPackageName() : null;
        int wn = st.getWindowNodeCount();
        for (int i = 0; i < wn; i++) {
            AssistStructure.WindowNode w = st.getWindowNodeAt(i);
            AssistStructure.ViewNode root = w.getRootViewNode();
            if (root != null) walk(root, w.getLeft(), w.getTop(), pkg, raws, 0);
        }
        AssistNodeFlattener.Result r = AssistNodeFlattener.flatten(raws, c.getPackageName());
        return ScreenContext.fromAssistNodes(pkg, act != null ? act.getClassName() : null, r.nodes, r.redacted,
                System.currentTimeMillis(), TimeZone.getDefault(), costMs);
    }

    private static void walk(AssistStructure.ViewNode n, int x, int y, String pkg, List<AssistNodeFlattener.Raw> out, int depth) {
        if (n == null || depth > 80 || out.size() > 3000) return;
        int left = x + n.getLeft();
        int top = y + n.getTop();
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        boolean pw = AssistNodeFlattener.isPasswordInputType(n.getInputType());
        String text = t != null ? t.toString() : null;
        out.add(new AssistNodeFlattener.Raw(text, d != null ? d.toString() : null, n.getIdEntry(), n.getClassName(),
                n.getIdPackage() != null ? n.getIdPackage() : pkg, pw, n.isClickable(), n.getVisibility() == android.view.View.VISIBLE,
                left, top, left + n.getWidth(), top + n.getHeight()));
        int cc = n.getChildCount();
        int cx = left - n.getScrollX();
        int cy = top - n.getScrollY();
        for (int i = 0; i < cc; i++) walk(n.getChildAt(i), cx, cy, pkg, out, depth + 1);
    }
}
