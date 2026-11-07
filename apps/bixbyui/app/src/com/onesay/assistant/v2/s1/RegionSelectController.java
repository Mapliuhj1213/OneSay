package com.onesay.assistant.v2.s1;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.Log;
import android.widget.Toast;

import com.onesay.assistant.v2.router.ConversationMemory;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.ui.FloatingSheetView;
import com.onesay.assistant.v2.ui.RegionSelectOverlay;
import com.onesay.assistant.v2.vision.ScreenVisionEngine;
import com.onesay.assistant.v2.vision.screenref.ScreenContext;
import com.onesay.assistant.v2.vision.screenref.ScreenContextCapture;
import com.onesay.assistant.v2.vision.screenref.ScreenRefSettings;

import android.view.WindowManager;

import java.util.TimeZone;

/**
 * [s1] #8 圈选即问的 Android 侧（最小版）。
 *
 * <h3>触发</h3>
 * 语音/文字「圈一下」「框选」「圈一下这是什么」（{@link RegionSelection#isTrigger}）。选语音触发是因为：Bixby 键的
 * 长按/短按已被键守护（hijack_daemon.sh，仓库外）占用并统一走「唤醒」，在仓库内加新的按键手势做不到闭环；
 * 面板按钮会挡住要圈的内容。调试包另有 {@code --es s1 circle} 广播入口。
 *
 * <h3>流程</h3>
 * 面板让位（hideSheet）→ 全屏遮罩拖框 → 松手：遮罩移除、震动 → 后台 root {@code uiautomator dump}（复用识屏采集）
 * → 只留框内节点抽实体 → 写入识屏会话缓存（之后「复制这个 / 导航去这里」只在框内消解）→ 面板显示框内文字摘要 + 动作芯片；
 * 话里带了问题、或框里没有文字（图片）且 VL 兜底开着 → 截图裁到框里再问 Qwen-VL，<b>截图完成后</b>才把面板挂回来（面板不进图）。
 * 圈选内容是用户亲手框的，但文字来自屏幕：之后芯片按识屏芯片同口径（D1，tainted=false）执行，AgentLoop 路径照旧 tainted。
 */
final class RegionSelectController {

    private static final String TAG = "S1";
    /** 遮罩最长停留时间，超时自动取消。 */
    static final long OVERLAY_TIMEOUT_MS = 30000L;

    private final FloatingSheetService mSvc;
    private final WindowManager mWm;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private RegionSelectOverlay mOverlay;
    private String mQuestion;
    private boolean mBusy;

    private final Runnable mTimeout = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "[CIRCLE] 遮罩 " + (OVERLAY_TIMEOUT_MS / 1000) + " 秒无操作，取消");
            removeOverlay();
        }
    };

    RegionSelectController(FloatingSheetService svc, WindowManager wm) {
        mSvc = svc;
        mWm = wm;
    }

    /** 主线程。true = 已接管（触发词命中且功能开启）。 */
    boolean intercept(String command) {
        if (!RegionSelection.isTrigger(command) || !S1Settings.circleEnabled(mSvc)) return false;
        begin(RegionSelection.questionOf(command));
        return true;
    }

    /** 开始一次圈选（语音触发 / 调试广播）。主线程。 */
    void begin(String question) {
        if (mBusy) {
            Log.i(TAG, "[CIRCLE] 上一次圈选还在处理，忽略");
            return;
        }
        mQuestion = question;
        Log.i(TAG, "[CIRCLE] 开始圈选，问题=" + (question != null ? question : "（无）"));
        mSvc.s1HideSheetNow();
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                showOverlay();
            }
        }, 280L);   // 等面板滑走，遮罩里不要出现面板
    }

    void destroy() {
        mMain.removeCallbacksAndMessages(null);
        removeOverlay();
    }

    private void showOverlay() {
        if (mOverlay == null) {
            mOverlay = new RegionSelectOverlay(mSvc);
            mOverlay.setListener(new RegionSelectOverlay.Listener() {
                @Override
                public void onSelected(float x0, float y0, float x1, float y1) {
                    Point scr = S1Overlay.realSize(mSvc);
                    int[] rect = RegionSelection.normalize(x0, y0, x1, y1, scr.x, scr.y);
                    if (!RegionSelection.bigEnough(rect, RegionSelection.MIN_SIDE_PX)) {
                        mOverlay.setHint("框太小了，再拖一次（或点取消）");
                        return;
                    }
                    removeOverlay();
                    vibrate();
                    onRegion(rect, scr.x, scr.y);
                }

                @Override
                public void onCancel() {
                    Log.i(TAG, "[CIRCLE] 用户取消圈选");
                    removeOverlay();
                }
            });
        }
        mOverlay.setHint(mQuestion != null ? "拖动框选要问的区域 ·「" + mQuestion + "」" : "拖动框选要问的区域 · 松手确认");
        if (S1Overlay.add(mWm, mOverlay, S1Overlay.fullScreenParams())) {
            mMain.removeCallbacks(mTimeout);
            mMain.postDelayed(mTimeout, OVERLAY_TIMEOUT_MS);
        } else {
            Toast.makeText(mSvc, "无法显示圈选遮罩（悬浮窗权限？）", Toast.LENGTH_SHORT).show();
        }
    }

    private void removeOverlay() {
        mMain.removeCallbacks(mTimeout);
        S1Overlay.remove(mWm, mOverlay);
    }

    private void onRegion(final int[] rect, final int scrW, final int scrH) {
        mBusy = true;
        Log.i(TAG, "[CIRCLE] 框选 [" + rect[0] + "," + rect[1] + "][" + rect[2] + "," + rect[3] + "] 屏幕 " + scrW + "x" + scrH);
        final String question = mQuestion;
        com.onesay.assistant.v2.util.Threads.start("S1Circle", new Runnable() {
            @Override
            public void run() {
                try {
                    try {
                        Thread.sleep(200L);   // 遮罩移除后再 dump / 截图
                    } catch (InterruptedException ignored) {
                    }
                    ScreenContext full = ScreenContextCapture.captureSync(mSvc);
                    final ScreenContext region = RegionSelection.regionContext(full, rect, System.currentTimeMillis(), TimeZone.getDefault());
                    if (!region.texts.isEmpty()) ScreenContextCapture.put(region);
                    Log.i(TAG, "[CIRCLE] 区域上下文 texts=" + region.texts.size() + " entities=" + region.entities
                            + " needsVl=" + region.needsVl + " (整屏 " + (full != null ? full.source + "/" + full.texts.size() : "null") + ")");
                    boolean wantVl = (question != null || region.needsVl) && ScreenRefSettings.vlFallbackEnabled(mSvc);
                    if (wantVl) {
                        askVl(question, rect, scrW, scrH, region);
                    } else {
                        mMain.post(new Runnable() {
                            @Override
                            public void run() {
                                showTextResult(question, region);
                                mBusy = false;
                            }
                        });
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "[CIRCLE] 处理异常: " + t.getMessage());
                    mBusy = false;
                }
            }
        });
    }

    private void askVl(final String question, int[] rect, int scrW, int scrH, final ScreenContext region) {
        Log.i(TAG, "[CIRCLE] 截图裁剪 + VL 提问");
        ScreenVisionEngine.getInstance(mSvc).recognizeRegion(RegionSelection.vlPrompt(question), rect, scrW, scrH,
                new Runnable() {
                    @Override
                    public void run() {
                        mMain.post(new Runnable() {
                            @Override
                            public void run() {
                                mSvc.s1AttachSheet();
                                FloatingSheetView sheet = mSvc.getSheetView();
                                if (sheet != null) {
                                    sheet.beginAskStream(question != null ? question : "圈选区域", "正在识别圈选区域…");
                                    sheet.updateState("正在识屏", Color.parseColor("#5E5CE6"), "圈选区域");
                                    sheet.setDismissLabel("✕ 取消");
                                }
                            }
                        });
                    }
                },
                new ScreenVisionEngine.Callback() {
                    @Override
                    public void onProgress(String stage) {
                    }

                    @Override
                    public void onSuccess(final String answer) {
                        mMain.post(new Runnable() {
                            @Override
                            public void run() {
                                mBusy = false;
                                mSvc.s1AttachSheet();
                                FloatingSheetView sheet = mSvc.getSheetView();
                                if (sheet == null) return;
                                String q = question != null ? question : "圈选区域是什么";
                                sheet.showAskReply(q, answer);
                                sheet.updateState("识屏完成", Color.parseColor("#30D158"), "圈选区域");
                                sheet.setDismissLabel("✕ 关闭");
                                com.onesay.assistant.v2.kernel.FeatureKernel.get().regionChips(mSvc, region);
                                try {
                                    ConversationMemory.getInstance().record(q, "[圈选识屏回执] " + answer, true);   // ★ LOGIC-1：识屏内容不可信
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                    }

                    @Override
                    public void onError(final String errorMsg) {
                        mMain.post(new Runnable() {
                            @Override
                            public void run() {
                                mBusy = false;
                                Log.w(TAG, "[CIRCLE] VL 失败: " + errorMsg);
                                showTextResult(question, region);
                                FloatingSheetView sheet = mSvc.getSheetView();
                                if (sheet != null && region.texts.isEmpty()) {
                                    sheet.showAskReply(question != null ? question : "圈选区域", "没能识别圈选区域：" + errorMsg);
                                }
                            }
                        });
                    }
                });
    }

    /** 不走 VL：面板显示框内文字摘要 + 动作芯片。主线程。 */
    private void showTextResult(String question, ScreenContext region) {
        mSvc.s1AttachSheet();
        FloatingSheetView sheet = mSvc.getSheetView();
        if (sheet == null) return;
        StringBuilder sb = new StringBuilder();
        if (region.texts.isEmpty()) {
            sb.append("圈选区域里没有读到文字（可能是图片）。");
            if (!ScreenRefSettings.vlFallbackEnabled(mSvc)) sb.append("识屏兜底（VL）已关闭，开启后可以识别图片。");
        } else {
            sb.append("框里有：");
            int n = 0;
            for (String t : region.texts) {
                if (sb.length() > 140) break;
                if (n++ > 0) sb.append(" / ");
                sb.append(t.length() > 60 ? t.substring(0, 59) + "…" : t);
            }
            sb.append("\n\n可以点下面的动作，或说「复制这个」「导航去这里」（只在框里找）。");
        }
        sheet.showAskReply(question != null ? question : "圈选区域", sb.toString());
        sheet.updateState("圈选完成", Color.parseColor("#30D158"), region.entities.size() + " 个可操作对象");
        sheet.setDismissLabel("✕ 关闭");
        int chips = com.onesay.assistant.v2.kernel.FeatureKernel.get().regionChips(mSvc, region);
        Log.i(TAG, "[CIRCLE] 结果已上屏 chips=" + chips);
    }

    @SuppressWarnings("deprecation")
    private void vibrate() {
        try {
            Vibrator v = (Vibrator) mSvc.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(30L);
        } catch (Throwable ignored) {
        }
    }
}
