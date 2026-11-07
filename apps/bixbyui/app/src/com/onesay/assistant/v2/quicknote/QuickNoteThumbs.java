package com.onesay.assistant.v2.quicknote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.onesay.assistant.v2.executor.ShellExecutor;
import com.onesay.assistant.v2.util.PrivateScratch;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Comparator;

/**
 * [quicknote] R10 D7 截图缩略图（默认关，控制台「记一记」卡可开）。
 * 只在读屏成功、<b>没被敏感规则拒收</b>、且来源不在银行 / 支付 / 密码管理器黑名单里时才截；
 * root {@code screencap -p} → 私有目录 cache/scratch/quicknote → 缩成宽 {@link #WIDTH} JPEG q70 → files/quicknote/thumbs/&lt;id&gt;.jpg → 删原图。
 * 上限 {@link #MAX_FILES} 张 / {@link #MAX_BYTES}，超了删最旧的缩略图（条目保留）。不上传、不进相册。
 */
public final class QuickNoteThumbs {

    private static final String TAG = "QuickNote";
    public static final int WIDTH = 360;
    public static final int MAX_FILES = 300;
    public static final long MAX_BYTES = 30L * 1024L * 1024L;

    private QuickNoteThumbs() {}

    public static File dir(Context ctx) {
        File d = new File(new File(ctx.getFilesDir(), "quicknote"), "thumbs");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** 速记缩略图截屏命令（单条，exec 守护 v4 白名单内）。 */
    static String captureCmd(String path) {
        return "screencap -p " + PrivateScratch.quote(path);
    }

    /** 后台线程：截一张，返回缩略图路径（失败 null）。 */
    public static String capture(Context ctx, long id) {
        Context app = ctx.getApplicationContext();
        File scratch = PrivateScratch.dir(app, "quicknote");
        if (scratch == null) return null;
        File raw = new File(scratch, "qn_raw.png");
        String p = raw.getAbsolutePath();
        try {
            // 2026-10-25 exec 守护 v4：App 先建好空文件（替代原来的 rm -f），root 单条 screencap 原地写；
            //   读不了才退回属主修正（复合脚本，v4 下需 allow_raw）。见 PrivateScratch.prepare / needsFixup。
            // ★ r2/fast-screen：先走常驻读屏助手（JPEG q90，约 0.1 s）；不可用才走 root screencap -p（约 1–2 s）
            if (!com.onesay.assistant.v2.fastscreen.FsCapture.jpegTo(app, raw, 90, 0)) {
                ShellExecutor sh = new ShellExecutor(app);
                PrivateScratch.prepare(raw);
                sh.execute(captureCmd(p));
                if (PrivateScratch.needsFixup(raw)) sh.execute(PrivateScratch.fixupCmd(p, scratch.getAbsolutePath()));
            }
            if (!raw.exists() || raw.length() == 0) {
                Log.w(TAG, "[QUICKNOTE] 缩略图：screencap 没出文件");
                return null;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(p, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= WIDTH) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(p, o2);
            if (b == null) return null;
            int h = Math.max(1, Math.round(b.getHeight() * (WIDTH / (float) b.getWidth())));
            Bitmap s = Bitmap.createScaledBitmap(b, WIDTH, h, true);
            if (s != b) b.recycle();
            File out = new File(dir(app), id + ".jpg");
            FileOutputStream fo = new FileOutputStream(out);
            try {
                s.compress(Bitmap.CompressFormat.JPEG, 70, fo);
            } finally {
                fo.close();
                s.recycle();
            }
            trim(app);
            Log.i(TAG, "[QUICKNOTE] 缩略图已存（私有目录，" + out.length() / 1024 + " KB）");
            return out.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "[QUICKNOTE] 缩略图失败: " + t.getMessage());
            return null;
        } finally {
            raw.delete();
        }
    }

    public static void delete(String path) {
        if (path == null || path.length() == 0) return;
        try {
            new File(path).delete();
        } catch (Throwable ignored) {
        }
    }

    /** 超过上限删最旧的。 */
    static void trim(Context ctx) {
        File[] fs = dir(ctx).listFiles();
        if (fs == null) return;
        Arrays.sort(fs, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        long total = 0;
        for (File f : fs) total += f.length();
        int n = fs.length;
        for (int i = 0; i < fs.length && (n > MAX_FILES || total > MAX_BYTES); i++) {
            total -= fs[i].length();
            fs[i].delete();
            n--;
        }
    }
}
