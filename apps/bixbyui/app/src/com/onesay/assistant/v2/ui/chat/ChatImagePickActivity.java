package com.onesay.assistant.v2.ui.chat;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * ★ ui-K1 对话窗「+」→ 拍照 / 从相册选图 的透明跳板页（悬浮窗服务没法 startActivityForResult）。
 *
 * <p>拍照：先在媒体库建一条临时记录当 EXTRA_OUTPUT（没有 FileProvider；建不了就退回相机给的缩略图），读完立即删掉这条记录
 * （不在相册里留一张）；相册：ACTION_GET_CONTENT image/*。读到的图按 EXIF 摆正、长边缩到 1280、JPEG 85 写进 App 私有缓存
 * {@code cache/chat_upload/}（只留最近几张，问完由服务删掉），再交回 {@link com.onesay.assistant.v2.ui.FloatingSheetService#chatImageResult}。
 * 用户主动选的图才会在发送时上传到视觉模型。
 */
public class ChatImagePickActivity extends Activity {

    public static final String EXTRA_CAMERA = "camera";
    private static final String TAG = "ChatWindow";
    private static final int REQ = 7301;
    private static final int LONG_EDGE = 1280;
    private static final int THUMB_EDGE = 360;

    private Uri mOut;
    private boolean mCamera;
    private boolean mDelivered;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        mCamera = getIntent() != null && getIntent().getBooleanExtra(EXTRA_CAMERA, false);
        if (saved != null) {
            mOut = saved.getParcelable("out");
            return;   // 被系统回收后重建：等 onActivityResult
        }
        try {
            Intent it;
            if (mCamera) {
                it = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                try {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Images.Media.DISPLAY_NAME, "bixby_chat_" + System.currentTimeMillis() + ".jpg");
                    v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    mOut = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                } catch (Throwable t) {
                    mOut = null;
                    Log.w(TAG, "[CHAT-IMG] 建临时照片记录失败，退回缩略图: " + t.getClass().getSimpleName());
                }
                if (mOut != null) {
                    it.putExtra(MediaStore.EXTRA_OUTPUT, mOut);
                    it.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            } else {
                it = new Intent(Intent.ACTION_GET_CONTENT);
                it.setType("image/*");
                it.addCategory(Intent.CATEGORY_OPENABLE);
            }
            startActivityForResult(it, REQ);
        } catch (Throwable t) {
            Log.w(TAG, "[CHAT-IMG] 打开" + (mCamera ? "相机" : "相册") + "失败: " + t.getClass().getSimpleName());
            Toast.makeText(this, mCamera ? "打不开相机" : "打不开相册", Toast.LENGTH_SHORT).show();
            deliver(null, null);
            finish();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (mOut != null) out.putParcelable("out", mOut);
    }

    @Override
    protected void onActivityResult(int req, int res, final Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ) return;
        final Uri out = mOut;
        if (res != RESULT_OK) {
            if (out != null) deleteRow(out);
            deliver(null, null);
            finish();
            return;
        }
        final Uri src = mCamera ? out : (data != null ? data.getData() : null);
        Bitmap small = null;
        if (mCamera && out == null && data != null && data.getExtras() != null) {
            Object o = data.getExtras().get("data");
            if (o instanceof Bitmap) small = (Bitmap) o;
        }
        final Bitmap camThumb = small;
        final android.content.ContentResolver cr = getContentResolver();
        final File dir = new File(getCacheDir(), "chat_upload");
        com.onesay.assistant.v2.util.Threads.start("chat-img", new Runnable() {
            @Override
            public void run() {
                String path = null;
                Bitmap thumb = null;
                try {
                    Bitmap full = src != null ? decode(cr, src) : camThumb;
                    if (full != null) {
                        if (!dir.isDirectory()) dir.mkdirs();
                        prune(dir, 4);
                        File f = new File(dir, "up_" + System.currentTimeMillis() + ".jpg");
                        FileOutputStream fo = new FileOutputStream(f);
                        try {
                            full.compress(Bitmap.CompressFormat.JPEG, 85, fo);
                        } finally {
                            fo.close();
                        }
                        path = f.getAbsolutePath();
                        thumb = scale(full, THUMB_EDGE);
                        Log.i(TAG, "[CHAT-IMG] 图片就绪 " + full.getWidth() + "x" + full.getHeight() + " " + (f.length() / 1024) + "KB");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "[CHAT-IMG] 读图失败: " + t.getClass().getSimpleName());
                } finally {
                    if (out != null) deleteRow(out);   // 临时照片记录：读完就删，相册里不留
                }
                final String p = path;
                final Bitmap th = thumb;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        if (p == null) Toast.makeText(getApplicationContext(), "没读到图片", Toast.LENGTH_SHORT).show();
                        deliver(p, th);
                    }
                });
            }
        });
        finish();
    }

    private void deliver(String path, Bitmap thumb) {
        if (mDelivered && path == null) return;
        mDelivered = true;
        com.onesay.assistant.v2.ui.FloatingSheetService.chatImageResult(path, thumb);
    }

    private void deleteRow(Uri u) {
        try {
            getContentResolver().delete(u, null, null);
        } catch (Throwable ignore) {
        }
    }

    static Bitmap decode(android.content.ContentResolver cr, Uri u) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = cr.openInputStream(u);
        try {
            BitmapFactory.decodeStream(in, null, o);
        } finally {
            if (in != null) in.close();
        }
        int sample = 1;
        int longEdge = Math.max(o.outWidth, o.outHeight);
        while (longEdge / (sample * 2) >= LONG_EDGE) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        in = cr.openInputStream(u);
        Bitmap bm;
        try {
            bm = BitmapFactory.decodeStream(in, null, d);
        } finally {
            if (in != null) in.close();
        }
        if (bm == null) return null;
        int rot = 0;
        try {
            in = cr.openInputStream(u);
            if (in != null) {
                android.media.ExifInterface ex = new android.media.ExifInterface(in);
                int ori = ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
                rot = ori == android.media.ExifInterface.ORIENTATION_ROTATE_90 ? 90
                        : ori == android.media.ExifInterface.ORIENTATION_ROTATE_180 ? 180
                        : ori == android.media.ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            }
        } catch (Throwable ignore) {
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
        }
        Bitmap s = scale(bm, LONG_EDGE);
        if (rot != 0) {
            Matrix m = new Matrix();
            m.postRotate(rot);
            s = Bitmap.createBitmap(s, 0, 0, s.getWidth(), s.getHeight(), m, true);
        }
        return s;
    }

    static Bitmap scale(Bitmap b, int edge) {
        int w = b.getWidth(), h = b.getHeight();
        int le = Math.max(w, h);
        if (le <= edge) return b;
        float k = edge / (float) le;
        return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
    }

    /** 缓存里只留最近 keep 张（问完服务也会删当次那张）。 */
    static void prune(File dir, int keep) {
        File[] fs = dir.listFiles();
        if (fs == null || fs.length < keep) return;
        java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        for (int i = keep - 1; i < fs.length; i++) {
            try { fs[i].delete(); } catch (Throwable ignore) { }
        }
    }
}
