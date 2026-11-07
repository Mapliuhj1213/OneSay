package com.onesay.assistant.v2.ui.console;

import android.app.Activity;
import android.content.Context;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.onesay.assistant.v2.island.IslandGeometry;

/**
 * [console R12] 控制台页面栈（不加 Activity / Fragment：全部是 MainActivity 里的 View，方案 §8.1）。
 *
 * <p>切页动效复用灵动岛的弹簧 {@link IslandGeometry.Spring}（ω22 ζ0.86，约 0.24 s 收敛）：
 * 进二级页 = 新页从右 24dp 滑入 + 透明度 0→1，旧页左移 8dp、淡到 0.6；返回反向。
 * 系统「动画时长缩放」= 0 时瞬间完成。
 */
public final class ConsoleNav {

    /** 一个页面。view 懒建；主题切换时 {@link #rebuildAll()} 会让所有页面重建。 */
    public abstract static class Page {
        protected ConsoleNav nav;
        View view;

        /** 建页面视图（主线程）。 */
        protected abstract View create();

        /** 页面露出来（push 完成 / 返回到它 / Activity onResume）：刷新状态。 */
        public void onShown() {
        }

        /** 返回键：页面自己处理了返回 true（例如记一记详情 → 列表）。 */
        public boolean back() {
            return false;
        }

        /** 被弹出栈。 */
        public void onRemoved() {
        }

        public View view() {
            return view;
        }
    }

    private final Activity mAct;
    private final FrameLayout mBox;
    private final PageStack<Page> mStack = new PageStack<Page>();
    private final float mD;
    private Runnable mAnim;
    private Runnable mFinish;

    public ConsoleNav(Activity act) {
        mAct = act;
        mD = act.getResources().getDisplayMetrics().density;
        mBox = new FrameLayout(act);
    }

    public View root() {
        return mBox;
    }

    public Activity activity() {
        return mAct;
    }

    public int depth() {
        return mStack.size();
    }

    public Page top() {
        return mStack.top();
    }

    public static boolean animationsOn(Context ctx) {
        try {
            return Settings.Global.getFloat(ctx.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (Throwable t) {
            return true;
        }
    }

    private View viewOf(Page p) {
        if (p.view == null) {
            p.nav = this;
            p.view = p.create();
        }
        return p.view;
    }

    private void attach(View v, int index) {
        if (v.getParent() == mBox) return;
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        if (index < 0) mBox.addView(v, lp);
        else mBox.addView(v, index, lp);
    }

    private static void reset(View v) {
        v.setTranslationX(0f);
        v.setAlpha(1f);
    }

    /** 栈底页（首页）。 */
    public void setRoot(Page home) {
        finishAnim();
        for (Page p : mStack.popToRoot()) p.onRemoved();
        mStack.replaceTop(home);
        mBox.removeAllViews();
        View v = viewOf(home);
        reset(v);
        attach(v, -1);
        home.onShown();
    }

    public void push(final Page p) {
        finishAnim();
        final Page below = mStack.top();
        mStack.push(p);
        final View in = viewOf(p);
        attach(in, -1);
        final View out = below != null ? below.view : null;
        animate(in, out, true, new Runnable() {
            @Override public void run() {
                if (out != null) mBox.removeView(out);
                p.onShown();
            }
        });
    }

    /** 弹出栈顶；只剩首页时什么都不做，返回 false。 */
    public boolean pop() {
        if (!mStack.canPop()) return false;
        finishAnim();
        final Page gone = mStack.pop();
        final Page now = mStack.top();
        final View in = viewOf(now);
        attach(in, 0);
        final View out = gone.view;
        animate(in, out, false, new Runnable() {
            @Override public void run() {
                if (out != null) mBox.removeView(out);
                gone.onRemoved();
                now.onShown();
            }
        });
        return true;
    }

    /** 栈顶换内容（同一外来页面内部跳转，例如记一记列表 → 详情）：不做位移动画，直接换。 */
    public void replaceTop(Page p) {
        finishAnim();
        Page old = mStack.top();
        if (old == null || !mStack.canPop()) {
            push(p);
            return;
        }
        mStack.replaceTop(p);
        View in = viewOf(p);
        reset(in);
        attach(in, -1);
        if (old.view != null && old.view != in) mBox.removeView(old.view);
        p.onShown();
    }

    /** 返回键：栈顶页自己处理 → 否则弹栈 → 只剩首页时返回 false（交给 Activity）。 */
    public boolean back() {
        Page p = mStack.top();
        if (p != null && p.back()) return true;
        return pop();
    }

    /** 回首页（例如从岛直达记一记后，用户点首页入口）。 */
    public void popToRoot() {
        finishAnim();
        for (Page p : mStack.popToRoot()) {
            if (p.view != null) mBox.removeView(p.view);
            p.onRemoved();
        }
        Page home = mStack.top();
        if (home != null) {
            View v = viewOf(home);
            reset(v);
            attach(v, -1);
            home.onShown();
        }
    }

    /** 主题切换：所有页面重建视图，只显示栈顶。 */
    public void rebuildAll() {
        finishAnim();
        mBox.removeAllViews();
        for (Page p : mStack.all()) p.view = null;
        Page top = mStack.top();
        if (top != null) {
            View v = viewOf(top);
            reset(v);
            attach(v, -1);
            top.onShown();
        }
    }

    // ------------------------------------------------------------------ 弹簧

    private void finishAnim() {
        if (mAnim != null) {
            mBox.removeCallbacks(mAnim);
            mAnim = null;
        }
        Runnable f = mFinish;
        mFinish = null;
        if (f != null) f.run();
        for (int i = 0; i < mBox.getChildCount(); i++) reset(mBox.getChildAt(i));
    }

    private void apply(View in, View out, boolean forward, float p) {
        float a = Math.max(0f, Math.min(1f, p));
        if (forward) {
            in.setTranslationX(24 * mD * (1f - p));
            in.setAlpha(a);
            if (out != null) {
                out.setTranslationX(-8 * mD * p);
                out.setAlpha(1f - 0.4f * a);
            }
        } else {
            in.setTranslationX(-8 * mD * (1f - p));
            in.setAlpha(0.6f + 0.4f * a);
            if (out != null) {
                out.setTranslationX(24 * mD * p);
                out.setAlpha(1f - a);
            }
        }
    }

    private void animate(final View in, final View out, final boolean forward, final Runnable done) {
        mFinish = new Runnable() {
            @Override public void run() {
                reset(in);
                if (out != null) reset(out);
                done.run();
            }
        };
        if (!animationsOn(mAct) || !mBox.isAttachedToWindow()) {
            finishAnim();
            return;
        }
        final IslandGeometry.Spring s = new IslandGeometry.Spring(0f, IslandGeometry.SPRING_OMEGA, IslandGeometry.SPRING_ZETA);
        s.t = 1f;
        apply(in, out, forward, 0f);
        final long[] last = {0L};
        mAnim = new Runnable() {
            @Override public void run() {
                long now = System.nanoTime();
                float dt = last[0] == 0 ? 1f / 60f : Math.min(0.05f, (now - last[0]) / 1e9f);
                last[0] = now;
                s.step(dt);
                if (s.settled(0.003f)) {
                    mAnim = null;
                    Runnable f = mFinish;
                    mFinish = null;
                    if (f != null) f.run();
                    return;
                }
                apply(in, out, forward, s.v);
                mBox.postOnAnimation(this);
            }
        };
        mBox.postOnAnimation(mAnim);
    }
}
