package com.onesay.assistant.v2.wake;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 唤醒提示音「系统提示音」来源的 Android 侧——用户在控制台从 RingtoneManager 列出的通知音 / 铃声里选一个（pref {@link #K_URI}），
 * 没选用系统默认通知音；解码（MediaExtractor + MediaCodec；WAV 直接读 PCM），
 * 解码结果缓存在应用私有目录 {@code files/wakecue/}（只存本机解码后的 PCM，不外传、不进 APK）。选来源 / 回退逻辑在 {@link WakeCue}。
 */
public final class WakeCueLoader {

    private static final String TAG = "WakeCue";
    /** 与 WakeTuning 同一个 prefs 文件。 */
    static final String PREF_NAME = "wake_tuning";
    /** 用户选的系统提示音 uri（空 = 系统默认通知音）。 */
    public static final String K_URI = "wake_cue_uri";

    /** 候选顺序：用户选的 → 系统默认通知音（纯函数，JVM 可测）。 */
    public static WakeCue.Candidate[] candidatesFor(String userUri, String defaultUri) {
        java.util.List<WakeCue.Candidate> l = new java.util.ArrayList<WakeCue.Candidate>();
        if (userUri != null && userUri.trim().length() > 0) l.add(new WakeCue.Candidate("uri", null, userUri.trim()));
        if (defaultUri != null && defaultUri.length() > 0 && (userUri == null || !defaultUri.equals(userUri.trim())))
            l.add(new WakeCue.Candidate("uri", null, defaultUri));
        return l.toArray(new WakeCue.Candidate[0]);
    }

    /** 记下用户选的系统提示音并重新解码（后台线程）。 */
    public static String setUri(Context ctx, String uri) {
        ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(K_URI, uri == null ? "" : uri).commit();
        WakeCue.setVendor(null, null);
        new File(new File(ctx.getFilesDir(), "wakecue"), "vendor.src").delete();
        return set(ctx, "system");
    }

    /** 列出手机自带的通知音（标题, uri），给控制台选择。 */
    public static java.util.List<String[]> listSystemSounds(Context ctx) {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        try {
            android.media.RingtoneManager rm = new android.media.RingtoneManager(ctx);
            rm.setType(android.media.RingtoneManager.TYPE_NOTIFICATION);
            android.database.Cursor cur = rm.getCursor();
            while (cur != null && cur.moveToNext() && out.size() < 200) {
                out.add(new String[]{cur.getString(android.media.RingtoneManager.TITLE_COLUMN_INDEX),
                        String.valueOf(rm.getRingtoneUri(cur.getPosition()))});
            }
        } catch (Throwable t) {
            Log.w(TAG, "[WAKE-CUE] 列系统提示音失败: " + t.getClass().getSimpleName());
        }
        return out;
    }

    private static void fillCandidates(Context ctx) {
        String user = ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(K_URI, "");
        String def = null;
        try {
            android.net.Uri d = android.media.RingtoneManager.getActualDefaultRingtoneUri(ctx, android.media.RingtoneManager.TYPE_NOTIFICATION);
            if (d == null) d = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION);
            def = d == null ? null : d.toString();
        } catch (Throwable ignored) {
        }
        WakeCue.setCandidates(candidatesFor(user, def));
    }

    private WakeCueLoader() {}

    /** 后台线程调用：读 pref，按需加载系统提示音，打一行 [WAKE-CUE]。 */
    public static void init(final Context ctx) {
        if (ctx == null) return;
        try {
            String v = ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(WakeCue.K_WAKE_CUE, "a");
            WakeCue.setPref(WakeCue.parse(v));
            if (WakeCue.pref() == WakeCue.Source.SYSTEM && WakeCue.vendorFrom() == null) load(ctx);
            Log.i(TAG, "[WAKE-CUE] " + WakeCue.describe());
        } catch (Throwable t) {
            Log.w(TAG, "[WAKE-CUE] 初始化异常，用方案 A: " + t.getMessage());
        }
    }

    /** 设置来源（控制台 / debug 广播）。@return describe() */
    public static String set(Context ctx, String v) {
        WakeCue.Source s = WakeCue.parse(v);
        ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(WakeCue.K_WAKE_CUE, WakeCue.key(s)).commit();
        WakeCue.setPref(s);
        if (s == WakeCue.Source.SYSTEM && WakeCue.vendorFrom() == null) load(ctx);
        String d = WakeCue.describe();
        Log.i(TAG, "[WAKE-CUE] 设为 " + d);
        return d;
    }

    static void load(final Context ctx) {
        fillCandidates(ctx);
        if (WakeCue.candidates().length == 0) return;
        final File dir = new File(ctx.getFilesDir(), "wakecue");
        final File pcmF = new File(dir, "vendor.pcm"), srcF = new File(dir, "vendor.src");
        // 缓存：同一出处的文件 / 包还在就直接用
        try {
            if (pcmF.isFile() && srcF.isFile()) {
                String src = new String(readAll(srcF), "UTF-8").trim();
                if (stillThere(ctx, src)) {
                    byte[] b = readAll(pcmF);
                    short[] p = new short[b.length / 2];
                    ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(p);
                    WakeCue.setVendor(p, src);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        String used = WakeCue.loadVendor(new WakeCue.Decoder() {
            @Override public WakeCue.Decoded decode(WakeCue.Candidate c) {
                return decodeCandidate(ctx, c);
            }
        });
        if (used == null) {
            Log.i(TAG, "[WAKE-CUE] 系统提示音不可用（候选 " + WakeCue.candidates().length + " 个），退回方案 A");
            return;
        }
        try {
            if (!dir.isDirectory()) dir.mkdirs();
            short[] p = WakeCue.pcm();
            ByteBuffer bb = ByteBuffer.allocate(p.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            bb.asShortBuffer().put(p);
            FileOutputStream o = new FileOutputStream(pcmF);
            o.write(bb.array());
            o.close();
            FileOutputStream s = new FileOutputStream(srcF);
            s.write(used.getBytes("UTF-8"));
            s.close();
        } catch (Throwable t) {
            Log.w(TAG, "[WAKE-CUE] 缓存写入失败（不影响使用）: " + t.getMessage());
        }
    }

    private static boolean stillThere(Context ctx, String src) {
        if (src == null || src.length() == 0) return false;
        if (src.startsWith("/")) return new File(src).canRead();
        if (src.startsWith("content:") || src.startsWith("file:")) {
            for (WakeCue.Candidate c : WakeCue.candidates()) if (src.equals(c.name)) return true;   // 还是当前选择
            return false;
        }
        int i = src.indexOf("/raw/");
        if (i <= 0) return false;
        try {
            ctx.getPackageManager().getPackageInfo(src.substring(0, i), 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static WakeCue.Decoded decodeCandidate(Context ctx, WakeCue.Candidate c) {
        AssetFileDescriptor afd = null;
        try {
            if ("uri".equals(c.kind)) return decodeUri(ctx, android.net.Uri.parse(c.name));
            if ("file".equals(c.kind)) {
                File f = new File(c.name);
                if (!f.canRead()) return null;
                if (c.name.endsWith(".wav")) return readWav(readAll(f));
                return decodeWith(c.name, null);
            }
            Context pc = ctx.createPackageContext(c.pkg, 0);
            int id = pc.getResources().getIdentifier(c.name, "raw", c.pkg);
            if (id == 0) return null;
            afd = pc.getResources().openRawResourceFd(id);
            if (afd == null) return null;
            // WAV：先试直接读 RIFF
            java.io.InputStream is = pc.getResources().openRawResource(id);
            byte[] head = new byte[12];
            int n = is.read(head);
            is.close();
            if (n == 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F') {
                java.io.InputStream all = pc.getResources().openRawResource(id);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int k;
                while ((k = all.read(buf)) > 0) bo.write(buf, 0, k);
                all.close();
                return readWav(bo.toByteArray());
            }
            return decodeWith(null, afd);
        } catch (Throwable t) {
            Log.i(TAG, "[WAKE-CUE] 候选 " + c + " 不可用: " + t.getMessage());
            return null;
        } finally {
            if (afd != null) try { afd.close(); } catch (Throwable ignored) {}
        }
    }

    private static WakeCue.Decoded decodeUri(Context ctx, android.net.Uri u) throws Exception {
        android.content.res.AssetFileDescriptor fd = ctx.getContentResolver().openAssetFileDescriptor(u, "r");
        if (fd == null) return null;
        try {
            return decodeWith(null, fd);
        } finally {
            try { fd.close(); } catch (Throwable ignored) {}
        }
    }

    /** MediaExtractor + MediaCodec 解码成 16-bit PCM（下混单声道）。 */
    private static WakeCue.Decoded decodeWith(String path, AssetFileDescriptor afd) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            if (path != null) ex.setDataSource(path);
            else ex.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0) return null;
            ex.selectTrack(track);
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            long deadline = System.currentTimeMillis() + 3000;
            while (!outDone && System.currentTimeMillis() < deadline) {
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int sz = ex.readSampleData(ib, 0);
                        if (sz < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    byte[] chunk = new byte[info.size];
                    ob.position(info.offset);
                    ob.get(chunk, 0, info.size);
                    out.write(chunk);
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
            byte[] b = out.toByteArray();
            short[] s = new short[b.length / 2];
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
            return new WakeCue.Decoded(WakeCue.downmix(s, ch), rate);
        } finally {
            if (codec != null) try { codec.stop(); codec.release(); } catch (Throwable ignored) {}
            ex.release();
        }
    }

    /** 最小 WAV（PCM16）读取。 */
    static WakeCue.Decoded readWav(byte[] b) {
        if (b == null || b.length < 44) return null;
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 12, ch = 1, rate = 0, bits = 16;
        while (pos + 8 <= b.length) {
            int id = bb.getInt(pos), sz = bb.getInt(pos + 4);
            if (id == 0x20746d66) {   // "fmt "
                ch = bb.getShort(pos + 10);
                rate = bb.getInt(pos + 12);
                bits = bb.getShort(pos + 22);
            } else if (id == 0x61746164) {   // "data"
                if (bits != 16 || rate <= 0) return null;
                int len = Math.min(sz, b.length - pos - 8);
                short[] s = new short[len / 2];
                ByteBuffer.wrap(b, pos + 8, len).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
                return new WakeCue.Decoded(WakeCue.downmix(s, ch), rate);
            }
            pos += 8 + sz + (sz & 1);
        }
        return null;
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return b;
        } finally {
            in.close();
        }
    }
}
