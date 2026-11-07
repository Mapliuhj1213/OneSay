/*
 * [富内容输出插件 · 宿主适配] OneSay 自写。把图卡片 / 全屏图存成 PNG 进相册。
 */
package com.onesay.assistant.plug.richout.host;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.onesay.assistant.v2.imgsave.ImageSavePlan;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 与「保存图片」（imgsave）同一套落点和命名：{@code Pictures/<OneSay 目录>/richout/OneSay_<时间>_1.png}，写完媒体扫描进相册。
 * 存的是视图当前画出来的样子（全屏页面里先缩放到想要的范围再存）。不走 root 拷贝兜底：没有存储权限就如实提示。
 */
final class RichImageSaver {

    private RichImageSaver() {}

    private static final String TAG = "RichOut";
    static final String SUBDIR = "richout";

    static void saveView(final Context ctx, View v, boolean dark) {
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        final Bitmap bmp;
        try {
            int w = v.getWidth(), h = v.getHeight();
            if (w <= 0 || h <= 0) {
                toast(app, "图还没画好");
                return;
            }
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawColor(dark ? 0xFF1C1C1E : 0xFFFFFFFF);
            v.draw(c);
        } catch (Throwable t) {
            Log.w(TAG, "[RICHOUT] 截图失败: " + t.getMessage());
            toast(app, "保存失败");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                            ImageSavePlan.relativeDir(SUBDIR));
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("建目录失败");
                    File f = new File(dir, ImageSavePlan.fileName(stamp, 1, ImageSavePlan.Tier.CROP, "png"));
                    FileOutputStream out = new FileOutputStream(f);
                    try {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                    } finally {
                        out.close();
                    }
                    MediaScannerConnection.scanFile(app, new String[] {f.getAbsolutePath()}, new String[] {"image/png"}, null);
                    msg = "已保存到相册：Pictures/" + ImageSavePlan.relativeDir(SUBDIR);
                    Log.i(TAG, "[RICHOUT] 图已保存 " + f.getAbsolutePath());
                } catch (Throwable t) {
                    Log.w(TAG, "[RICHOUT] 存图失败: " + t.getMessage());
                    msg = "保存失败（可能没有存储权限）";
                } finally {
                    bmp.recycle();
                }
                final String m = msg;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        toast(app, m);
                    }
                });
            }
        }, "richout-save").start();
    }

    static void toast(Context ctx, String s) {
        try {
            Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
