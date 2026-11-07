package com.onesay.assistant.v2.s1;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

import com.onesay.assistant.v2.ui.S1CardViews;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ fix/shot-1104：<b>非岛模式</b>时主动建议的可见反馈 —— 借剪贴板旧芯片卡（同一外观、同一位置：顶部居中下移 96 dp）。
 * 以前非岛模式下 IslandSuggestPresenter 直接返回 false（引擎记 SUPPRESSED），截图后什么都看不到。
 * 同时只有一张；按钮 / ✕ / 到时间分别回调；主线程画，任意线程调。
 */
final class SuggestChipCard {
    private static final String TAG = "S1";

    interface Listener {
        void onChip(String id, int index);

        void onClosed(String id, boolean timeout);
    }

    private final Context mCtx;
    private final WindowManager mWm;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private S1CardViews.ClipChipCard mView;
    private volatile String mId;
    private Listener mListener;

    SuggestChipCard(Context ctx) {
        mCtx = ctx;
        mWm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    private final Runnable mTimeout = new Runnable() {
        @Override public void run() {
            String id = mId;
            hideNow();
            Listener l = mListener;
            if (id != null && l != null) l.onClosed(id, true);
        }
    };

    void show(final String id, final String icon, final String title, final List<String> labels, final long ttlMs, final Listener l) {
        mMain.post(new Runnable() {
            @Override public void run() {
                mListener = l;
                if (mView == null) {
                    mView = new S1CardViews.ClipChipCard(mCtx);
                    mView.setListener(new S1CardViews.ClipChipCard.Listener() {
                        @Override public void onChip(int index) {
                            String cur = mId;
                            hideNow();
                            Listener ll = mListener;
                            if (cur != null && ll != null) ll.onChip(cur, index);
                        }

                        @Override public void onClose() {
                            String cur = mId;
                            hideNow();
                            Listener ll = mListener;
                            if (cur != null && ll != null) ll.onClosed(cur, false);
                        }

                        @Override public void onTouched() {
                            mMain.removeCallbacks(mTimeout);   // 碰过了：最多再留 30 s
                            mMain.postDelayed(mTimeout, 30000L);
                        }
                    });
                }
                mId = id;
                mView.bind(icon, title, labels == null ? new ArrayList<String>() : labels);
                boolean ok = S1Overlay.add(mWm, mView, S1Overlay.smallParams(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, S1Overlay.dp(mCtx, 96)));
                mMain.removeCallbacks(mTimeout);
                mMain.postDelayed(mTimeout, ttlMs > 0 ? ttlMs : S1Settings.CLIP_CHIP_TTL_MS);
                Log.i(TAG, "[SUGGEST-CHIP] 非岛模式 → 旧芯片卡 " + (ok ? "已显示" : "挂载失败"));
            }
        });
    }

    void update(final String id, final String title, final String icon, final List<String> labels) {
        mMain.post(new Runnable() {
            @Override public void run() {
                if (mView == null || id == null || !id.equals(mId)) return;
                mView.bind(icon, title, labels == null ? new ArrayList<String>() : labels);
            }
        });
    }

    void withdraw(final String id) {
        mMain.post(new Runnable() {
            @Override public void run() {
                if (id != null && id.equals(mId)) hideNow();
            }
        });
    }

    private void hideNow() {
        mMain.removeCallbacks(mTimeout);
        mId = null;
        S1Overlay.remove(mWm, mView);
    }
}
