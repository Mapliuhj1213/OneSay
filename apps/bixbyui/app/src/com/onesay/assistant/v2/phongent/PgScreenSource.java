/*
 * Copyright 2026 wangzan101 (Mika) and mylea (phongent, https://github.com/mylea1/phongent)
 * Modifications Copyright 2026 OneSay
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * 改动声明（OneSay，2026-11-02）：编码循环改写自 phongent
 * app/src/main/java/com/wangzan101/mika/remote/VirtualVideo.kt 的 serve()（Kotlin → Java 7）：
 * H.264 Baseline、无 B 帧、I 帧间隔 1 秒、按编码器支持选 I420 / NV12、ByteBuffer 输入、输出每包一个访问单元。
 * 改动：画面来源从 Mika 的虚拟屏帧换成 OneSay root 读屏助手（FastScreen）的 JPEG 截图（Android 8 无虚拟屏 /
 * 免授权投屏），≤ 4 fps、长边 720、宽高对齐到 16。
 */
package com.onesay.assistant.v2.phongent;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.onesay.assistant.plug.phongent.core.PgVideo;
import com.onesay.assistant.plug.phongent.core.ScreenSource;

import java.io.IOException;
import java.nio.ByteBuffer;

/** [phongent 适配层] {@link ScreenSource}：root 读屏助手截图 → MediaCodec H.264。 */
final class PgScreenSource implements ScreenSource {

    static final int FRAME_MS = 250;
    static final int MAX_LONG = 720;

    private final Context mCtx;

    PgScreenSource(Context ctx) {
        mCtx = ctx.getApplicationContext();
    }

    @Override public int[] screenSize() {
        DisplayMetrics m = new DisplayMetrics();
        ((WindowManager) mCtx.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
        return new int[]{m.widthPixels, m.heightPixels};
    }

    @Override public int[] streamSize() {
        int[] real = screenSize();
        int[] s = PgVideo.scaled(real[0], real[1], MAX_LONG);
        return new int[]{align16(s[0]), align16(s[1])};
    }

    static int align16(int v) {
        return Math.max(16, v / 16 * 16);
    }

    @Override public void stream(PacketSink sink) throws IOException {
        int[] ss = streamSize();
        int w = ss[0], h = ss[1];
        com.onesay.assistant.v2.fastscreen.FastScreen fs = com.onesay.assistant.v2.fastscreen.FastScreen.get(mCtx);
        MediaCodec codec = MediaCodec.createEncoderByType("video/avc");
        try {
            int[] colors = codec.getCodecInfo().getCapabilitiesForType("video/avc").colorFormats;
            boolean planar = false, semi = false;
            for (int c : colors) {
                if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) planar = true;
                if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) semi = true;
            }
            if (!planar && !semi) throw new IOException("video_encoder_unavailable");
            MediaFormat f = MediaFormat.createVideoFormat("video/avc", w, h);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, planar ? MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                    : MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 800000);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, 4);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            sink.size(w, h);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int[] px = new int[w * h];
            byte[] yuv = new byte[w * h * 3 / 2];
            long started = SystemClock.elapsedRealtime();
            while (sink.open()) {
                long tick = SystemClock.elapsedRealtime();
                byte[] jpeg = fs.shotJpeg(70, w);
                if (jpeg != null) {
                    Bitmap src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                    if (src != null) {
                        Bitmap bm = src.getWidth() == w && src.getHeight() == h ? src : Bitmap.createScaledBitmap(src, w, h, true);
                        bm.getPixels(px, 0, w, 0, 0, w, h);
                        if (bm != src) bm.recycle();
                        src.recycle();
                        PgVideo.argbToYuv420(px, w, h, yuv, planar);
                        int in = codec.dequeueInputBuffer(20000);
                        if (in >= 0) {
                            ByteBuffer b = codec.getInputBuffer(in);
                            b.clear();
                            b.put(yuv);
                            codec.queueInputBuffer(in, 0, yuv.length, (tick - started) * 1000L, 0);
                        }
                    }
                }
                drain(codec, info, sink);
                long left = FRAME_MS - (SystemClock.elapsedRealtime() - tick);
                if (left > 0) SystemClock.sleep(left);
            }
        } finally {
            try {
                codec.stop();
            } catch (Throwable ignored) {
            }
            codec.release();
        }
    }

    private static void drain(MediaCodec codec, MediaCodec.BufferInfo info, PacketSink sink) throws IOException {
        while (true) {
            int n = codec.dequeueOutputBuffer(info, 10000);
            if (n == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = codec.getOutputFormat();
                java.io.ByteArrayOutputStream cfg = new java.io.ByteArrayOutputStream();
                for (String k : new String[]{"csd-0", "csd-1"}) {
                    ByteBuffer bb = of.getByteBuffer(k);
                    if (bb != null) {
                        byte[] a = new byte[bb.remaining()];
                        bb.get(a);
                        cfg.write(a, 0, a.length);
                    }
                }
                if (cfg.size() > 0) sink.packet(cfg.toByteArray());
            } else if (n >= 0) {
                if (info.size > 0) {
                    ByteBuffer ob = codec.getOutputBuffer(n);
                    ob.position(info.offset);
                    ob.limit(info.offset + info.size);
                    byte[] a = new byte[info.size];
                    ob.get(a);
                    sink.packet(a);
                }
                codec.releaseOutputBuffer(n, false);
            } else {
                break;
            }
        }
    }
}
