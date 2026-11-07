package com.onesay.assistant.v2.ui.live;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.onesay.assistant.v2.s1.S1Hooks;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.vision.LiveVisionPrompt;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ★ 11-04 相机对话（照 Gemini Live 的相机模式）：全屏 Camera2 实时取景（App 内预览，无新增依赖）+ 进来就开始的语音对话。
 *
 * <p>每轮取 TextureView 当前画面（getBitmap 直接缩到长边 768，内存 JPEG）+ 用户这句话 → qwen3-vl → TTS 念 + 画面上叠字幕。
 * 底部：翻转 / 静音 / 手电筒（后摄有闪光灯时）/ 挂断。挂断、返回、切走（onPause）都结束对话、关相机、回到原来的输入模式。
 * 帧和音频一律不落盘、不进日志。
 */
public class CameraLiveActivity extends Activity implements LiveConversation.Host {

    private static final String TAG = "CameraLive";
    private static final int REQ_PERMS = 41;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private TextureView mTexture;
    private TextView mStatus, mUserLine, mAnswerLine, mNotice;
    private LinearLayout mSubtitle;
    private LiveCtlButton mBtnFlip, mBtnMic, mBtnTorch, mBtnHang;
    private View mTorchCell;
    private FrameLayout mDenied;

    private HandlerThread mCamThread;
    private Handler mCam;
    private CameraDevice mDevice;
    private CameraCaptureSession mSession;
    private CaptureRequest.Builder mReq;
    private volatile boolean mPreviewOn;
    private boolean mFront;
    private boolean mHasFlash;
    private volatile int mContentW = 3, mContentH = 4;

    private LiveConversation mConv;
    private boolean mAskingPerms;
    private boolean mEnded;
    private boolean mThinkingShown;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        // 不用 FLAG_FULLSCREEN：状态栏留着（透明、画面铺到底下），灵动岛「相机对话中 · 计时」才不会被「全屏应用自动隐藏」藏掉
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        buildUi();
        Log.i(TAG, "[LIVE] 进入相机对话");
        if (missingPerms().length == 0) {
            begin();
        } else {
            mAskingPerms = true;
            requestPermissions(missingPerms(), REQ_PERMS);
        }
    }

    private String[] missingPerms() {
        java.util.List<String> m = new java.util.ArrayList<String>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) m.add(Manifest.permission.CAMERA);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) m.add(Manifest.permission.RECORD_AUDIO);
        return m.toArray(new String[0]);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        mAskingPerms = false;
        if (code != REQ_PERMS) return;
        boolean cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        Log.i(TAG, "[LIVE] 权限结果 camera=" + cam + " mic=" + mic);
        if (cam && mic) {
            begin();
            if (mTexture.isAvailable()) openCamera();
        } else {
            showDenied(cam, mic);
        }
    }

    private void begin() {
        if (mConv != null || mEnded) return;
        mConv = new LiveConversation(this, this);
        mConv.start();
        S1Hooks.onLiveStart("相机对话中");
        mMain.post(mTick);
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onResume() {
        super.onResume();
        if (mConv != null && mTexture.isAvailable() && mDevice == null) openCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 切走 / 锁屏 / 来电 = 挂断（不在后台偷偷开着相机和麦克风）；申请权限弹窗引起的 onPause 除外
        if (!mAskingPerms && !isChangingConfigurations()) endSession("离开页面");
    }

    @Override
    protected void onDestroy() {
        endSession("销毁");
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        endSession("返回键");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return super.onKeyDown(keyCode, event);
    }

    /** 唯一的退出点（幂等）。 */
    private void endSession(String why) {
        if (mEnded) return;
        mEnded = true;
        Log.i(TAG, "[LIVE] 挂断（" + why + "）");
        mMain.removeCallbacks(mTick);
        if (mConv != null) mConv.stop();
        closeCamera();
        S1Hooks.onLiveEnd();
        FloatingSheetService fs = FloatingSheetService.getInstance();
        if (fs != null) fs.onCameraModeExit();
        else LiveInputMode.get().exitCamera();
        if (!isFinishing()) finish();
        overridePendingTransition(0, 0);
    }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (mEnded || mConv == null) return;
            long el = SystemClock.elapsedRealtime() - mConv.startedAt();
            LiveTurnPolicy.State st = mConv.mPolicy.state();
            boolean thinking = st == LiveTurnPolicy.State.THINKING;
            S1Hooks.onLiveLine(LiveTurnPolicy.islandLine(thinking, el));
            mStatus.setText("相机对话 · " + LiveTurnPolicy.clock(el) + " · " + stateWord(st, mConv.muted()));
            mMain.postDelayed(this, 1000L);
        }
    };

    private static String stateWord(LiveTurnPolicy.State st, boolean muted) {
        if (st == LiveTurnPolicy.State.THINKING) return "思考中";
        if (st == LiveTurnPolicy.State.SPEAKING) return "正在回答（开口即可打断）";
        return muted ? "麦克风已静音" : "正在听，直接说";
    }

    // ------------------------------------------------------------------ LiveConversation.Host

    @Override
    public void onLiveState(LiveTurnPolicy.State st, boolean muted) {
        boolean thinking = st == LiveTurnPolicy.State.THINKING;
        if (thinking != mThinkingShown) {
            mThinkingShown = thinking;
            S1Hooks.onThinking(thinking);
        }
        if (mConv != null) {
            long el = SystemClock.elapsedRealtime() - mConv.startedAt();
            S1Hooks.onLiveLine(LiveTurnPolicy.islandLine(thinking, el));
            mStatus.setText("相机对话 · " + LiveTurnPolicy.clock(el) + " · " + stateWord(st, muted));
        }
        mBtnMic.setOn(muted);
    }

    @Override
    public void onUserText(String text, boolean fin) {
        if (text == null || text.trim().length() == 0) return;
        mUserLine.setText("「" + text.trim() + "」");
        mUserLine.setVisibility(View.VISIBLE);
        mSubtitle.setVisibility(View.VISIBLE);
        if (fin) {
            mAnswerLine.setText("思考中…");
            mAnswerLine.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onAnswerText(String text) {
        mAnswerLine.setText(text);
        mAnswerLine.setVisibility(View.VISIBLE);
        mSubtitle.setVisibility(View.VISIBLE);
    }

    @Override
    public void onNotice(String text) {
        if (text == null || text.length() == 0) {
            mNotice.setVisibility(View.GONE);
        } else {
            mNotice.setText(text);
            mNotice.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public LiveFrame grabFrame() {
        final Bitmap[] out = new Bitmap[1];
        final CountDownLatch latch = new CountDownLatch(1);
        mMain.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!mEnded && mPreviewOn && mTexture.isAvailable()) {
                        int[] sz = LiveVisionPrompt.targetSize(mContentW, mContentH, LiveVisionPrompt.TARGET_LONG_EDGE);
                        out[0] = mTexture.getBitmap(sz[0], sz[1]);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "[LIVE] 取帧异常: " + t.getClass().getSimpleName());
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignore) {
            return null;
        }
        Bitmap bmp = out[0];
        if (bmp == null) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(96 * 1024);
            bmp.compress(Bitmap.CompressFormat.JPEG, LiveVisionPrompt.JPEG_QUALITY, bos);
            LiveFrame f = new LiveFrame(bos.toByteArray(), bmp.getWidth(), bmp.getHeight());
            Log.i(TAG, "[LIVE] 取帧 " + f.describe() + "（仅内存）");
            return f;
        } finally {
            bmp.recycle();
        }
    }

    // ------------------------------------------------------------------ Camera2

    private void openCamera() {
        if (mEnded || mDevice != null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (mCamThread == null) {
            mCamThread = new HandlerThread("live-cam");
            mCamThread.start();
            mCam = new Handler(mCamThread.getLooper());
        }
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            CameraCharacteristics ch = null;
            int want = mFront ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
            for (String cid : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(cid);
                Integer f = c.get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == want) { id = cid; ch = c; break; }
            }
            if (id == null) {
                String[] ids = cm.getCameraIdList();
                if (ids.length == 0) { onNotice("这台手机没有可用的摄像头"); return; }
                id = ids[0];
                ch = cm.getCameraCharacteristics(id);
            }
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            final Size ps = choosePreview(map == null ? null : map.getOutputSizes(SurfaceTexture.class));
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            boolean swap = so == null || so == 90 || so == 270;   // 竖屏锁定：传感器横着装 → 内容宽高对调
            mContentW = swap ? ps.getHeight() : ps.getWidth();
            mContentH = swap ? ps.getWidth() : ps.getHeight();
            Boolean fl = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            mHasFlash = !mFront && fl != null && fl;
            mTorchCell.setVisibility(mHasFlash ? View.VISIBLE : View.GONE);
            if (!mHasFlash) mBtnTorch.setOn(false);
            applyTransform();
            Log.i(TAG, "[LIVE] 打开" + (mFront ? "前" : "后") + "摄 预览 " + ps.getWidth() + "x" + ps.getHeight() + " 方向=" + so);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    if (mEnded) { d.close(); return; }
                    mDevice = d;
                    startPreview(d, ps);
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    d.close();
                    if (mDevice == d) mDevice = null;
                    mPreviewOn = false;
                    postNotice("相机被别的应用占用了");
                }

                @Override
                public void onError(CameraDevice d, int error) {
                    d.close();
                    if (mDevice == d) mDevice = null;
                    mPreviewOn = false;
                    Log.w(TAG, "[LIVE] 相机错误 " + error);
                    postNotice("相机打不开（错误 " + error + "）");
                }
            }, mCam);
        } catch (SecurityException e) {
            onNotice("没有相机权限");
        } catch (Throwable e) {
            Log.w(TAG, "[LIVE] 打开相机失败: " + e.getClass().getSimpleName());
            onNotice("相机打不开");
        }
    }

    private void startPreview(final CameraDevice d, Size ps) {
        try {
            SurfaceTexture st = mTexture.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(ps.getWidth(), ps.getHeight());
            final Surface s = new Surface(st);
            mReq = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mReq.addTarget(s);
            mReq.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            mReq.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            mReq.set(CaptureRequest.FLASH_MODE, mBtnTorch.isOn() && mHasFlash ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            d.createCaptureSession(Arrays.asList(s), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    if (mDevice != d || mEnded) { session.close(); return; }
                    mSession = session;
                    try {
                        session.setRepeatingRequest(mReq.build(), null, mCam);
                        mPreviewOn = true;
                    } catch (Throwable t) {
                        Log.w(TAG, "[LIVE] 预览失败: " + t.getClass().getSimpleName());
                        postNotice("相机预览失败");
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    postNotice("相机预览失败");
                }
            }, mCam);
        } catch (CameraAccessException e) {
            postNotice("相机打不开");
        } catch (Throwable e) {
            Log.w(TAG, "[LIVE] 预览异常: " + e.getClass().getSimpleName());
            postNotice("相机预览失败");
        }
    }

    private void closeCamera() {
        mPreviewOn = false;
        try { if (mSession != null) mSession.close(); } catch (Throwable ignore) { }
        mSession = null;
        try { if (mDevice != null) mDevice.close(); } catch (Throwable ignore) { }
        mDevice = null;
        if (mCamThread != null && mEnded) {
            mCamThread.quitSafely();
            mCamThread = null;
            mCam = null;
        }
    }

    private void setTorch(boolean on) {
        mBtnTorch.setOn(on);
        if (mSession == null || mReq == null || !mHasFlash) return;
        try {
            mReq.set(CaptureRequest.FLASH_MODE, on ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            mSession.setRepeatingRequest(mReq.build(), null, mCam);
        } catch (Throwable t) {
            Log.w(TAG, "[LIVE] 手电筒切换失败: " + t.getClass().getSimpleName());
        }
    }

    /** 4:3、长边不超过 1440 里最大的；没有 4:3 就取长边 ≤1920 里最大的。 */
    static Size choosePreview(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1280, 960);
        Size best = null;
        for (Size s : sizes) {
            int lw = Math.max(s.getWidth(), s.getHeight()), sh = Math.min(s.getWidth(), s.getHeight());
            if (lw <= 1440 && lw * 3 == sh * 4 && (best == null || lw > Math.max(best.getWidth(), best.getHeight()))) best = s;
        }
        if (best != null) return best;
        for (Size s : sizes) {
            int lw = Math.max(s.getWidth(), s.getHeight());
            if (lw <= 1920 && (best == null || lw > Math.max(best.getWidth(), best.getHeight()))) best = s;
        }
        return best != null ? best : sizes[0];
    }

    /** 居中裁剪铺满（TextureView 默认把画面拉伸到视图大小）；前摄左右镜像（只影响显示，送模型的帧不镜像）。 */
    private void applyTransform() {
        mMain.post(new Runnable() {
            @Override
            public void run() {
                int vw = mTexture.getWidth(), vh = mTexture.getHeight();
                if (vw == 0 || vh == 0) return;
                float scale = Math.max((float) vw / mContentW, (float) vh / mContentH);
                float sx = mContentW * scale / vw, sy = mContentH * scale / vh;
                Matrix m = new Matrix();
                m.setScale(mFront ? -sx : sx, sy, vw / 2f, vh / 2f);
                mTexture.setTransform(m);
            }
        });
    }

    private void postNotice(final String t) {
        mMain.post(new Runnable() {
            @Override public void run() { onNotice(t); }
        });
    }

    // ------------------------------------------------------------------ 界面

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        mTexture = new TextureView(this);
        mTexture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
                applyTransform();
                if (mConv != null) openCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {
                applyTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
                mPreviewOn = false;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture s) { }
        });
        root.addView(mTexture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mStatus = new TextView(this);
        mStatus.setTextColor(Color.WHITE);
        mStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mStatus.setPadding(dp(14), dp(7), dp(14), dp(7));
        mStatus.setBackground(pill(0x66000000, dp(18)));
        mStatus.setText("相机对话 · 0:00 · 正在准备");
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        slp.topMargin = dp(44);
        root.addView(mStatus, slp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);

        mNotice = new TextView(this);
        mNotice.setTextColor(0xFFFFD60A);
        mNotice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mNotice.setGravity(Gravity.CENTER);
        mNotice.setPadding(dp(12), dp(6), dp(12), dp(6));
        mNotice.setBackground(pill(0x99000000, dp(14)));
        mNotice.setVisibility(View.GONE);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.bottomMargin = dp(10);
        bottom.addView(mNotice, nlp);

        mSubtitle = new LinearLayout(this);
        mSubtitle.setOrientation(LinearLayout.VERTICAL);
        mSubtitle.setPadding(dp(16), dp(10), dp(16), dp(12));
        mSubtitle.setBackground(pill(0xB3000000, dp(16)));
        mSubtitle.setVisibility(View.GONE);
        mUserLine = new TextView(this);
        mUserLine.setTextColor(0xCCFFFFFF);
        mUserLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mUserLine.setMaxLines(2);
        mUserLine.setVisibility(View.GONE);
        mSubtitle.addView(mUserLine);
        mAnswerLine = new TextView(this);
        mAnswerLine.setTextColor(Color.WHITE);
        mAnswerLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        mAnswerLine.setLineSpacing(dp(2), 1f);
        mAnswerLine.setMaxLines(5);
        mAnswerLine.setVisibility(View.GONE);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(4);
        mSubtitle.addView(mAnswerLine, alp);
        LinearLayout.LayoutParams sublp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sublp.leftMargin = dp(16);
        sublp.rightMargin = dp(16);
        sublp.bottomMargin = dp(18);
        bottom.addView(mSubtitle, sublp);

        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER);
        mBtnFlip = new LiveCtlButton(this, LiveCtlButton.FLIP);
        mBtnMic = new LiveCtlButton(this, LiveCtlButton.MIC);
        mBtnTorch = new LiveCtlButton(this, LiveCtlButton.TORCH);
        mBtnHang = new LiveCtlButton(this, LiveCtlButton.HANGUP);
        ctl.addView(cell(mBtnFlip, "翻转"));
        ctl.addView(cell(mBtnMic, "静音"));
        mTorchCell = cell(mBtnTorch, "手电筒");
        mTorchCell.setVisibility(View.GONE);
        ctl.addView(mTorchCell);
        ctl.addView(cell(mBtnHang, "挂断"));
        bottom.addView(ctl);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        blp.bottomMargin = dp(36);
        root.addView(bottom, blp);

        mBtnFlip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mConv == null || mEnded) return;
                mFront = !mFront;
                if (mFront) mBtnTorch.setOn(false);
                closeCamera();
                openCamera();
            }
        });
        mBtnMic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mConv == null || mEnded) return;
                mConv.setMuted(!mConv.muted());
            }
        });
        mBtnTorch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setTorch(!mBtnTorch.isOn());
            }
        });
        mBtnHang.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                endSession("挂断钮");
            }
        });
        setContentView(root);
        mRoot = root;
    }

    private FrameLayout mRoot;

    private View cell(LiveCtlButton b, String label) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(b, new LinearLayout.LayoutParams(dp(60), dp(60)));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xE6FFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(6);
        c.addView(t, tlp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        c.setLayoutParams(lp);
        return c;
    }

    private static GradientDrawable pill(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    /** 权限被拒：如实说缺哪个、怎么开；可去设置或退出。 */
    private void showDenied(boolean cam, boolean mic) {
        if (mDenied != null) return;
        mDenied = new FrameLayout(this);
        mDenied.setBackgroundColor(0xF0101010);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(28), 0, dp(28), 0);
        TextView msg = new TextView(this);
        msg.setTextColor(Color.WHITE);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        msg.setGravity(Gravity.CENTER);
        String what = !cam && !mic ? "相机和麦克风" : !cam ? "相机" : "麦克风";
        msg.setText("没有" + what + "权限，相机对话用不了。\n"
                + (!cam ? "没有相机就看不到画面；" : "") + (!mic ? "没有麦克风就听不到你说话；" : "")
                + "\n可以在 设置 › 应用 › OneSay › 权限 里打开。");
        box.addView(msg);
        TextView go = new TextView(this);
        go.setText("去设置开启");
        go.setTextColor(Color.BLACK);
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        go.setGravity(Gravity.CENTER);
        go.setBackground(pill(0xFFF2F2F2, dp(22)));
        go.setPadding(dp(24), dp(10), dp(24), dp(10));
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent it = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(it);
                } catch (Throwable ignore) { }
                endSession("去设置");
            }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(24);
        box.addView(go, glp);
        TextView quit = new TextView(this);
        quit.setText("退出");
        quit.setTextColor(Color.WHITE);
        quit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        quit.setGravity(Gravity.CENTER);
        quit.setPadding(dp(24), dp(10), dp(24), dp(10));
        quit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                endSession("权限被拒退出");
            }
        });
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qlp.topMargin = dp(8);
        box.addView(quit, qlp);
        mDenied.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        mRoot.addView(mDenied, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Log.i(TAG, "[LIVE] 权限被拒提示：缺 " + what);
    }
}
