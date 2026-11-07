package com.onesay.clock.voice;

import android.content.Context;

import com.onesay.clock.L;
import com.onesay.clock.core.Bank;
import com.onesay.clock.core.CacheJanitor;
import com.onesay.clock.core.PcmJoin;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 新闻标题件：files/news/{sha1(文本#语速)}.wav。命中缓存直接用（并刷新 mtime 记「最近使用」）；没有就音色 C 合成。
 * 合成失败返回 false —— 不用本机 TTS 兜底（用户 11-05 10:58 定：音色不一致就不念）。
 */
public final class NewsVoice {
    private final Context c;
    private final File dir;
    private final CosyVoice cosy;   // null = 没配 key 或音色（只能用缓存）
    public int hits, synth, fails;
    private final Set<String> usedToday = new HashSet<String>();

    public NewsVoice(Context c, String key) {
        this(c, key, com.onesay.clock.core.Flags.COSY_MODEL_DEF, "");
    }

    /** 开源版：model / voice 由设置给出；voice 为空 = 不做云端合成。 */
    public NewsVoice(Context c, String key, String model, String voice) {
        this.c = c;
        dir = new File(c.getFilesDir(), "news");
        dir.mkdirs();
        PhraseVoice.checkStamp(c, dir);   // 音色 / 地址 / 模型变了 → 旧音色的新闻件作废
        String ep = new com.onesay.clock.Prefs(c).ttsEndpoint();
        cosy = key == null || key.length() == 0 || voice == null || voice.length() == 0 || ep.length() == 0 ? null
                : new CosyVoice(ep, key, model == null || model.length() == 0 ? com.onesay.clock.core.Flags.COSY_MODEL_DEF : model, voice);
    }

    public static String cacheKey(String text, double rate) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest((text + "#" + String.format(Locale.US, "%.2f", rate)).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public File file(String spoken) { return new File(dir, cacheKey(spoken, Bank.RN) + ".wav"); }

    /** spoken = 标题 + 「。」（与 Template 的新闻件文本一致）。 */
    public boolean ensure(String spoken, int timeoutMs) {
        File f = file(spoken);
        if (f.isFile() && f.length() > 2000) {
            f.setLastModified(System.currentTimeMillis());
            synchronized (this) { hits++; usedToday.add(f.getName()); }
            return true;
        }
        if (cosy == null) { synchronized (this) { fails++; } return false; }
        try {
            byte[] b = cosy.synth(spoken, Bank.RN, timeoutMs);
            if (b == null || b.length < 2000 || PcmJoin.fromWav(b) == null) throw new Exception("bad audio");
            File tmp = new File(f.getPath() + ".tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            try { o.write(b); } finally { o.close(); }
            tmp.renameTo(f);
            synchronized (this) { synth++; usedToday.add(f.getName()); }
            return true;
        } catch (Exception e) {
            synchronized (this) { fails++; }
            L.w(c, "NEWS 合成失败 " + e.getMessage());
            return false;
        }
    }

    public short[] pcm(String spoken) {
        try { return PcmJoin.fromWav(com.onesay.clock.content.Http.readAll(new java.io.FileInputStream(file(spoken)))); }
        catch (Exception e) { return null; }
    }

    /** 限额清理（今天用到的不删）。 */
    public int janitor() {
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        List<CacheJanitor.F> l = new ArrayList<CacheJanitor.F>();
        for (File f : fs) l.add(new CacheJanitor.F(f.getName(), f.length(), f.lastModified()));
        List<String> v = CacheJanitor.victims(l, usedToday, System.currentTimeMillis(), CacheJanitor.MAX_FILES, CacheJanitor.MAX_BYTES);
        for (String n : v) new File(dir, n).delete();
        return v.size();
    }
}
