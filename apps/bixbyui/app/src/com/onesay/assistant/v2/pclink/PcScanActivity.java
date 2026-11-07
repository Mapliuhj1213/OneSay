package com.onesay.assistant.v2.pclink;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * [pclink] 扫电脑端 OneSay 设置页上的配对二维码：android.hardware.Camera 预览 + ZXing core 解码（Android 8 没有 Play 服务也能用）。
 * 相机权限在这里运行时申请；拒绝了就提示改用「粘贴配对码」。扫到 {@code onesay://pair?…} 直接保存配对并关掉本页，
 * 回到控制台「电脑联动」页（页面 onResume 重画，会自动去找电脑）。不是配对码的二维码提示一下、继续扫。
 */
public final class PcScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private static final int REQ_CAM = 41;
    private SurfaceView mSurface;
    private TextView mHint;
    private Camera mCam;
    private boolean mHasSurface;
    private HandlerThread mDecodeThread;
    private Handler mDecode;
    private volatile boolean mDecoding, mDone;
    private final MultiFormatReader mReader = new MultiFormatReader();
    private long mLastWrongToast;
    private final Handler mMain = new Handler();
    private final Runnable mRefocus = new Runnable() {
        @Override public void run() {
            Camera c = mCam;
            if (c == null || mDone) return;
            try {
                c.autoFocus(null);
            } catch (Throwable ignored) {
            }
            mMain.postDelayed(this, 2500);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        mReader.setHints(hints);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        mSurface = new SurfaceView(this);
        root.addView(mSurface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View frame = new View(this);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setStroke(dp(3), 0xCCFFFFFF);
        gd.setCornerRadius(dp(16));
        frame.setBackground(gd);
        root.addView(frame, new FrameLayout.LayoutParams(dp(250), dp(250), Gravity.CENTER));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(20), dp(36), dp(20), dp(12));
        top.setBackgroundColor(0x88000000);
        TextView title = new TextView(this);
        title.setText("扫码配对电脑");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        top.addView(title);
        mHint = new TextView(this);
        mHint.setText("在电脑上打开 OneSay → 设置 → 配对手机，把二维码放进框里。手机和电脑要连同一个 Wi-Fi。");
        mHint.setTextColor(0xDDFFFFFF);
        mHint.setTextSize(14);
        mHint.setPadding(0, dp(6), 0, 0);
        top.addView(mHint);
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(16), dp(12), dp(16), dp(28));
        bottom.setBackgroundColor(0x88000000);
        Button paste = new Button(this);
        paste.setText("改用粘贴配对码");
        paste.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PcLinkUi.pasteDialog(PcScanActivity.this, new Runnable() {
                    @Override public void run() {
                        mDone = true;
                        setResult(RESULT_OK);
                        finish();
                    }
                });
            }
        });
        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                finish();
            }
        });
        bottom.addView(paste);
        bottom.addView(cancel);
        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);
        mSurface.getHolder().addCallback(this);
    }

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private boolean mAskedCam;

    @Override protected void onResume() {
        super.onResume();
        mDecodeThread = new HandlerThread("pclink-scan");
        mDecodeThread.start();
        mDecode = new Handler(mDecodeThread.getLooper());
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // 权限弹窗本身会让本页 onPause/onResume：只问一次，拒绝后停在提示上，不然会无限弹窗（oss-device-1108 真机发现）
            if (!mAskedCam) {
                mAskedCam = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
            }
            return;
        }
        if (mHasSurface) openCamera(mSurface.getHolder());
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ_CAM) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            if (mHasSurface) openCamera(mSurface.getHolder());
        } else {
            mHint.setText("没有相机权限，扫不了码。可以点下面「改用粘贴配对码」：在电脑端设置页点「复制配对串」，发到手机上再粘贴。");
        }
    }

    @Override protected void onPause() {
        closeCamera();
        if (mDecodeThread != null) {
            mDecodeThread.quit();
            mDecodeThread = null;
        }
        super.onPause();
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        mHasSurface = true;
        if (mCam == null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera(h);
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
    }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        mHasSurface = false;
        closeCamera();
    }

    private void openCamera(SurfaceHolder h) {
        if (mCam != null) return;
        try {
            int id = 0;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    id = i;
                    break;
                }
            }
            Camera.getCameraInfo(id, info);
            Camera c = Camera.open(id);
            Camera.Parameters p = c.getParameters();
            Camera.Size best = pickSize(p.getSupportedPreviewSizes());
            if (best != null) p.setPreviewSize(best.width, best.height);
            List<String> fm = p.getSupportedFocusModes();
            boolean continuous = fm != null && fm.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            if (continuous) p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            else if (fm != null && fm.contains(Camera.Parameters.FOCUS_MODE_AUTO)) p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            try {
                c.setParameters(p);
            } catch (RuntimeException e) {
                Log.w(PcLink.TAG, "[PCLINK] 相机参数不收，用默认: " + e.getClass().getSimpleName());
            }
            c.setDisplayOrientation(orientation(info));
            c.setPreviewDisplay(h);
            c.startPreview();
            mCam = c;
            if (!continuous) mMain.postDelayed(mRefocus, 800);
            c.setOneShotPreviewCallback(this);
        } catch (Throwable t) {
            Log.w(PcLink.TAG, "[PCLINK] 相机打不开: " + t.getClass().getSimpleName());
            mHint.setText("相机打不开（可能被别的应用占用）。可以改用粘贴配对码。");
            closeCamera();
        }
    }

    private int orientation(Camera.CameraInfo info) {
        int rot = getWindowManager().getDefaultDisplay().getRotation();
        int deg = rot == Surface.ROTATION_90 ? 90 : rot == Surface.ROTATION_180 ? 180 : rot == Surface.ROTATION_270 ? 270 : 0;
        return (info.orientation - deg + 360) % 360;
    }

    /** 选最接近 1280×720 的预览尺寸（太大解码慢，太小扫不清）。 */
    static Camera.Size pickSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;
        Camera.Size best = null;
        long bestScore = Long.MAX_VALUE;
        for (Camera.Size s : sizes) {
            long score = Math.abs((long) s.width * s.height - 1280L * 720L);
            if (s.width > 1920) score += 10_000_000L;
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private void closeCamera() {
        mMain.removeCallbacks(mRefocus);
        Camera c = mCam;
        mCam = null;
        if (c != null) {
            try {
                c.setOneShotPreviewCallback(null);
                c.stopPreview();
            } catch (Throwable ignored) {
            }
            try {
                c.release();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override public void onPreviewFrame(final byte[] data, Camera camera) {
        final Camera c = mCam;
        if (c == null || mDone || data == null) return;
        final Camera.Size s;
        try {
            s = c.getParameters().getPreviewSize();
        } catch (Throwable t) {
            return;
        }
        final Handler dh = mDecode;
        if (dh == null || mDecoding) {
            again();
            return;
        }
        mDecoding = true;
        final int w = s.width, h = s.height;
        dh.post(new Runnable() {
            @Override public void run() {
                String text = null;
                try {
                    PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false);
                    Result r = mReader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src)));
                    text = r == null ? null : r.getText();
                } catch (Throwable ignored) {
                    // 这一帧没扫到
                } finally {
                    mReader.reset();
                }
                final String found = text;
                mMain.post(new Runnable() {
                    @Override public void run() {
                        mDecoding = false;
                        if (found != null) onFound(found);
                        else again();
                    }
                });
            }
        });
    }

    private void again() {
        Camera c = mCam;
        if (c == null || mDone) return;
        try {
            c.setOneShotPreviewCallback(this);
        } catch (Throwable ignored) {
        }
    }

    private void onFound(String text) {
        if (mDone) return;
        if (!PcPairing.looksLike(text)) {
            long now = System.currentTimeMillis();
            if (now - mLastWrongToast > 3000) {
                mLastWrongToast = now;
                Toast.makeText(this, "这不是电脑端的配对码", Toast.LENGTH_SHORT).show();
            }
            again();
            return;
        }
        try {
            PcPairing p = PcLink.pair(this, text);
            mDone = true;
            Toast.makeText(this, "已配对 " + p.peer + "，正在找电脑…", Toast.LENGTH_LONG).show();
            setResult(RESULT_OK);
            finish();
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            again();
        }
    }
}
