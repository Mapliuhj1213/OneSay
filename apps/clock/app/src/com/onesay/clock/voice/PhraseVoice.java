package com.onesay.clock.voice;

import android.content.Context;

import com.onesay.clock.L;
import com.onesay.clock.Prefs;
import com.onesay.clock.core.Bank;
import com.onesay.clock.core.Flags;
import com.onesay.clock.core.PcmJoin;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Map;

/**
 * 朗读表的本地语音缓存（OneSay 开源版，替代原先随包分发的预合成件库）。
 *
 * <ul>
 *   <li>每个朗读表件（{@link Bank#all()}）用用户选的云端音色合成一次，存 {@code files/phrases/{key}-{文本指纹}.wav}；</li>
 *   <li>「预合成朗读表」（密钥面板）一次合成全部件，之后闹钟播报的固定部分完全不用联网；</li>
 *   <li>运行时缺件：配了 Key + 音色就现合成并缓存；没配 = 不出声（没有系统 TTS 兜底）；</li>
 *   <li>服务地址 / 模型 / 音色任一变化 → 缓存作废（{@link #checkStamp}，同样作用于新闻件缓存目录）。</li>
 * </ul>
 */
public final class PhraseVoice {
    public interface Progress { void on(int done, int total, int fails); }

    private final Context c;
    private final File dir;
    private final CosyVoice cosy;   // null = 只读缓存（没配 Key / 音色，或调用方要求离线）
    private volatile boolean cancel;

    /** @param online false = 只读缓存，绝不联网（现拼开场用） */
    public PhraseVoice(Context c, boolean online) {
        this.c = c.getApplicationContext();
        dir = new File(this.c.getFilesDir(), "phrases");
        dir.mkdirs();
        checkStamp(this.c, dir);
        Prefs p = new Prefs(this.c);
        String key = p.bailianKey(), ep = p.ttsEndpoint(), voice = p.cosyVoice(), model = p.cosyModel();
        cosy = !online || key.length() == 0 || ep.length() == 0 || voice.length() == 0 ? null
                : new CosyVoice(ep, key, model.length() == 0 ? Flags.COSY_MODEL_DEF : model, voice);
    }

    /** 当前语音配置的指纹（不含 Key）：服务地址 | 模型 | 音色。 */
    static String stamp(Context c) {
        Prefs p = new Prefs(c);
        String s = p.ttsEndpoint() + "|" + p.cosyModel() + "|" + p.cosyVoice();
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) { return s.length() + ""; }
    }

    /** 指纹变了就清空目录里的音频并写新指纹。返回清掉的文件数。 */
    public static synchronized int checkStamp(Context c, File dir) {
        String now = stamp(c);
        File f = new File(dir, "voice.stamp");
        String old = "";
        try { if (f.isFile()) old = new String(com.onesay.clock.content.Http.readAll(new java.io.FileInputStream(f)), "UTF-8"); } catch (Exception ignored) { }
        if (now.equals(old)) return 0;
        int n = 0;
        File[] fs = dir.listFiles();
        if (fs != null) for (File x : fs) if (x.getName().endsWith(".wav") && x.delete()) n++;
        try {
            FileOutputStream o = new FileOutputStream(f);
            try { o.write(now.getBytes("UTF-8")); } finally { o.close(); }
        } catch (Exception ignored) { }
        if (n > 0) L.i(c, "PHRASE 语音配置变了，作废缓存 " + n + " 件（" + dir.getName() + "）");
        return n;
    }

    /** 语音配置变化后立刻作废两处缓存（密钥面板保存时调）。 */
    public static void invalidateAll(Context c) {
        File base = c.getApplicationContext().getFilesDir();
        File ph = new File(base, "phrases"), nw = new File(base, "news");
        ph.mkdirs(); nw.mkdirs();
        checkStamp(c, ph);
        checkStamp(c, nw);
    }

    public boolean online() { return cosy != null; }

    /** 文件名带上文本 + 语速的短指纹：改了朗读表里的说法，旧缓存自然不再命中。 */
    File file(String key) {
        String t = Bank.text(key);
        String h = NewsVoice.cacheKey(t == null ? "" : t, Bank.rate(key)).substring(0, 8);
        return new File(dir, key + "-" + h + ".wav");
    }

    public boolean cached(String key) { File f = file(key); return f.isFile() && f.length() > 2000; }

    public int cachedCount() {
        int n = 0;
        for (String k : Bank.all().keySet()) if (cached(k)) n++;
        return n;
    }

    /** 有缓存或合成成功返回 true。 */
    public boolean ensure(String key, int timeoutMs) {
        if (cached(key)) return true;
        String text = Bank.text(key);
        if (cosy == null || text == null || text.length() == 0) return false;
        try {
            byte[] b = cosy.synth(text, Bank.rate(key), timeoutMs);
            if (b == null || b.length < 2000 || PcmJoin.fromWav(b) == null) throw new Exception("bad audio");
            File f = file(key), tmp = new File(f.getPath() + ".tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            try { o.write(b); } finally { o.close(); }
            return tmp.renameTo(f);
        } catch (Exception e) {
            L.w(c, "PHRASE 合成失败 " + key + " " + e.getClass().getSimpleName());
            return false;
        }
    }

    public short[] pcm(String key) {
        if (!cached(key)) return null;
        try { return PcmJoin.fromWav(com.onesay.clock.content.Http.readAll(new java.io.FileInputStream(file(key)))); }
        catch (Exception e) { return null; }
    }

    public void cancel() { cancel = true; }

    /** 合成整张朗读表（已缓存的跳过）。在后台线程调。返回失败件数。 */
    public int prebuild(Progress cb) {
        cancel = false;
        Map<String, Object[]> all = Bank.all();
        int total = all.size(), done = 0, fails = 0;
        long t0 = System.currentTimeMillis();
        for (String k : all.keySet()) {
            if (cancel) break;
            if (!ensure(k, 20_000)) fails++;
            done++;
            if (cb != null) cb.on(done, total, fails);
        }
        L.i(c, "PHRASE 预合成 " + (done - fails) + "/" + total + " 失败 " + fails + " " + (System.currentTimeMillis() - t0) + "ms");
        return fails;
    }
}
