package com.onesay.assistant.v2.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * ★ 11-04 ④：结论卡片缩略图加载 —— 只收公网 https（ResearchVisuals.publicHttps 已过滤），≤1.5 MB，内存里解码成 ~200 px，
 * 不写任何缓存文件；失败 / 超时 / 不是图片 → 保持隐藏。
 */
final class ThumbLoader {

    private ThumbLoader() {}

    static final int MAX_BYTES = 1536 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void load(final String url, final ImageView iv) {
        if (url == null || iv == null || com.onesay.assistant.v2.router.ResearchVisuals.publicHttps(url) == null) return;
        com.onesay.assistant.v2.util.Threads.start("thumb", new Runnable() {
            @Override public void run() {
                final Bitmap bm = fetch(url);
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (bm == null) {
                            iv.setVisibility(android.view.View.GONE);
                            return;
                        }
                        iv.setImageBitmap(bm);
                        iv.setVisibility(android.view.View.VISIBLE);
                    }
                });
            }
        });
    }

    static Bitmap fetch(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 8.0) AppleWebKit/537.36 Chrome/96 Mobile Safari/537.36");
            if (c.getResponseCode() != 200) return null;
            String ct = c.getContentType() == null ? "" : c.getContentType().toLowerCase(java.util.Locale.ROOT);
            if (ct.length() > 0 && !ct.startsWith("image/")) return null;
            if (!"https".equalsIgnoreCase(c.getURL().getProtocol())) return null;   // 跳转到 http 的不要
            InputStream is = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > MAX_BYTES) {
                    is.close();
                    return null;
                }
            }
            is.close();
            byte[] b = bo.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(b, 0, b.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 200 && o.outHeight / (sample * 2) >= 200) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(b, 0, b.length, o2);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
