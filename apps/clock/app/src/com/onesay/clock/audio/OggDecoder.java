package com.onesay.clock.audio;

import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import com.onesay.clock.core.PcmJoin;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** assets 里的 Ogg Vorbis 件 → 24 kHz 单声道 PCM（MediaExtractor 直接读 APK 内偏移，不落临时文件）。只在预热/生成时用，响铃路径不解码。 */
public final class OggDecoder {
    private OggDecoder() { }

    public static short[] decode(AssetFileDescriptor afd) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { ex.selectTrack(i); fmt = f; break; }
            }
            if (fmt == null) return null;
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();
            int sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int ch = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inEos = false, outEos = false;
            long until = System.currentTimeMillis() + 5000;
            while (!outEos && System.currentTimeMillis() < until) {
                if (!inEos) {
                    int ii = codec.dequeueInputBuffer(5000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int n = ex.readSampleData(ib, 0);
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true; }
                        else { codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 5000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    byte[] b = new byte[info.size];
                    ob.position(info.offset);
                    ob.get(b, 0, info.size);
                    pcm.write(b, 0, b.length);
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                }
            }
            byte[] raw = pcm.toByteArray();
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            int c = Math.max(1, ch), frames = raw.length / (2 * c);
            short[] s = new short[frames];
            for (int i = 0; i < frames; i++) {
                int acc = 0;
                for (int k = 0; k < c; k++) acc += bb.getShort((i * c + k) * 2);
                s[i] = (short) (acc / c);
            }
            return PcmJoin.resample(s, sr, PcmJoin.SR);
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignored) { }
            ex.release();
            try { afd.close(); } catch (Throwable ignored) { }
        }
    }
}
