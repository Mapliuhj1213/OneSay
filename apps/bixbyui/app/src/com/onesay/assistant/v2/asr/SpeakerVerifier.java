package com.onesay.assistant.v2.asr;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 声纹（说话人验证）通道 —— 回答"**谁在说**"，不改善"说了什么"。
 *
 * 定位（与调研方案一致）：家庭级身份过滤 + 敏感操作闸门，**不做支付级认证**。
 * 隐私红线：embedding 与音频全部本地计算/存储，永不上行；不触碰
 * {@code WAKE_LOCAL_GUARD_MS} 那条"唤醒前音频不上行"的硬闸门。
 *
 * <p>P0（本次）：加载模型 + 提向量 + 算分 + 基础注册持久化（供探针实测 JNI/RTF/方向）。
 * P1（后续）：注册向导、"声纹不符"闸门、与 ASR 并行竞速。
 *
 * <p>模型文件：{@code filesDir/voiceprint/} 下（P0 由 adb 推送；P1 改 assets 解包）。
 */
public class SpeakerVerifier {

    private static final String TAG = "SpeakerVerifier";

    /** 模型候选（按序取第一个存在的）——ERes2Net-base 为主、CAM++ 为备。 */
    private static final String[] MODEL_CANDIDATES = {
            "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
            "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx",
    };

    /** 太短的音频提的向量不可靠：低于此秒数直接拒绝（方案 §5.4 最短时长门）。 */
    public static final float MIN_AUDIO_SEC = 1.2f;

    /** 单次提取最多消费的音频秒数（防止误喂超大文件拖死）。 */
    private static final float MAX_AUDIO_SEC = 30f;

    private static SpeakerVerifier sInstance;

    private final Context mCtx;
    private File mVpDir;

    private SpeakerEmbeddingExtractor mExtractor;
    private SpeakerEmbeddingManager mManager;
    private int mDim = 0;

    /** 引擎**构造**失败过（JNI/模型损坏等）—— 这类失败重试无意义，缓存之。 */
    private boolean mEngineFailed = false;
    private String mLoadError = "";
    private String mModelPath = "";

    /**
     * 注册向量的**内存副本**（name → 各段向量）。
     *
     * <p>原生 Manager 只提供 verify/search（阈值制布尔值），拿不到"裸分数"。
     * 而"专项拟合"（唤醒阈值校准）恰恰需要每次验证的**余弦分数分布** ——
     * 本副本就是为此保留的：scoreName() 直接对其求最大余弦。
     *
     * <p>注册（persist）与恢复（restoreAll）时同步维护；删除时移除。
     * 注册/删除发生在后台线程、验证发生在唤醒线程 → 用 ConcurrentHashMap；
     * 列表本身写入后不再修改（整体替换语义），读取天然安全。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, float[][]> mRegistry =
            new java.util.concurrent.ConcurrentHashMap<String, float[][]>();

    private SpeakerVerifier(Context ctx) {
        mCtx = ctx;
        mVpDir = new File(ctx.getFilesDir(), "voiceprint");
        // 目录若不存在则由此处创建 —— 由 App 进程创建的目录其 SELinux MLS 类别
        // （s0:c512,c768）是内核自动赋予的；外部（adb/su）创建的目录**缺类别**，
        // App 会静默地 File.exists()==false（2026-10-04 实测踩到，须 chcon 修复）。
        if (!mVpDir.exists()) {
            mVpDir.mkdirs();
        }
    }

    public static synchronized SpeakerVerifier get(Context ctx) {
        if (sInstance == null) {
            sInstance = new SpeakerVerifier(ctx.getApplicationContext());
        }
        return sInstance;
    }

    // ---------------------------------------------------------------- 初始化

    /**
     * 懒加载：找模型 → 建 Extractor + Manager → 恢复已注册声纹。
     *
     * <p>两类失败分开处理（2026-10-04 实测踩到）：
     * <ul>
     *   <li>**模型文件缺失** —— 不算致命，**每次都重新查**。文件常是外部推进来的
     *       （adb/修复权限），若一并缓存失败，就必须重启 App 才能恢复 —— 实测被这条坑过一次。</li>
     *   <li>**引擎构造异常**（JNI 签名不匹配/模型损坏）—— 重试无意义，缓存失败。</li>
     * </ul>
     */
    private synchronized boolean ensureInit() {
        if (mExtractor != null) return true;
        if (mEngineFailed) return false;

        // 1. 找模型（每次 stat —— 极便宜，换来"文件后到也能自动启用"）
        File modelFile = null;
        for (int i = 0; i < MODEL_CANDIDATES.length; i++) {
            File f = new File(mVpDir, MODEL_CANDIDATES[i]);
            if (f.exists() && f.length() > 0) { modelFile = f; break; }
        }
        if (modelFile == null) {
            mLoadError = "模型未就绪（需推送到 " + mVpDir.getAbsolutePath() + "/）";
            return false;
        }
        mModelPath = modelFile.getAbsolutePath();

        // 2. 构造引擎（从这里往下失败才算致命）
        try {
            long t0 = SystemClock.elapsedRealtime();
            SpeakerEmbeddingExtractorConfig cfg = SpeakerEmbeddingExtractorConfig.builder()
                    .setModel(mModelPath)
                    .setNumThreads(1)
                    .setDebug(false)
                    .setProvider("cpu")
                    .build();
            mExtractor = new SpeakerEmbeddingExtractor(cfg);
            mDim = mExtractor.dim();
            long loadMs = SystemClock.elapsedRealtime() - t0;

            if (mDim <= 0) {
                throw new IllegalStateException("dim=" + mDim + " 非法");
            }
            mManager = new SpeakerEmbeddingManager(mDim);

            Log.i(TAG, "[SPK] 声纹引擎就绪: 模型=" + modelFile.getName()
                    + " dim=" + mDim + " 加载耗时=" + loadMs + "ms");

            restoreAll();
            return true;

        } catch (Throwable t) {
            // 最常见 = JNI 签名不匹配（UnsatisfiedLinkError / NoSuchMethodError）
            mEngineFailed = true;
            mLoadError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.e(TAG, "[SPK] 声纹引擎初始化失败: " + mLoadError, t);
            mExtractor = null;
            mManager = null;
            return false;
        }
    }

    public boolean isAvailable() {
        return ensureInit();
    }

    public String getLoadError() {
        return mLoadError;
    }

    /** 一行状态摘要（探针用）。 */
    public String describeStatus() {
        if (!ensureInit()) {
            return "不可用: " + mLoadError;
        }
        String[] names = listNames();
        StringBuilder sb = new StringBuilder();
        sb.append("就绪 dim=").append(mDim)
                .append(" 模型=").append(new File(mModelPath).getName())
                .append(" 已注册=").append(names.length).append("人[");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(names[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    // ------------------------------------------------------------ 提向量（核心）

    /**
     * 从 PCM float 样本提 embedding。
     * 本方法**同步阻塞**（几百 ms 量级）；调用方须自行放到工作线程，并与 ASR 并行。
     *
     * @return 192 维向量；失败返回 null（原因见日志）。
     */
    public float[] extractFromSamples(float[] samples, int sampleRate) {
        if (samples == null || samples.length == 0) return null;
        if (!ensureInit()) return null;

        int maxSamples = (int) (MAX_AUDIO_SEC * sampleRate);
        int n = Math.min(samples.length, maxSamples);
        if (n < (int) (MIN_AUDIO_SEC * sampleRate)) {
            Log.w(TAG, "[SPK] 音频过短(" + (n / (float) sampleRate) + "s < " + MIN_AUDIO_SEC + "s)，不验证");
            return null;
        }

        OnlineStream stream = null;
        try {
            float[] use = samples;
            if (n < samples.length) {
                use = new float[n];
                System.arraycopy(samples, 0, use, 0, n);
            }
            stream = mExtractor.createStream();
            stream.acceptWaveform(use, sampleRate);
            stream.inputFinished();
            if (!mExtractor.isReady(stream)) {
                Log.w(TAG, "[SPK] 帧数不足，extractor 未 ready（音频 " + (n / (float) sampleRate) + "s）");
                return null;
            }
            return mExtractor.compute(stream);
        } catch (Throwable t) {
            Log.e(TAG, "[SPK] compute 异常: " + t, t);
            return null;
        } finally {
            if (stream != null) {
                try { stream.release(); } catch (Throwable ignored) { }
            }
        }
    }

    /** 提取结果（带耗时诊断的包装）。 */
    public static class ExtractResult {
        public float[] emb;
        public float seconds;
        public long costMs;
        public float rtf;
        public String err;

        public boolean ok() {
            return emb != null;
        }
    }

    /** 从 wav 文件提向量（16k/mono/16bit PCM，自动剥 RIFF 头）。 */
    public ExtractResult extractFromWav(String wavPath) {
        ExtractResult r = new ExtractResult();
        try {
            float[] samples = readWavAsFloat(wavPath);
            r.seconds = samples.length / 16000.0f;
            long t0 = SystemClock.elapsedRealtime();
            r.emb = extractFromSamples(samples, 16000);
            r.costMs = SystemClock.elapsedRealtime() - t0;
            if (r.emb != null) {
                r.rtf = (r.seconds > 0) ? (r.costMs / 1000.0f / r.seconds) : -1f;
            } else {
                r.err = "提取失败（详见日志）";
            }
        } catch (Throwable t) {
            r.err = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return r;
    }

    // ------------------------------------------------------------ 余弦与判定

    /** 余弦相似度。任一为空或长度不等返回 -2（哨兵值，正常范围 [-1,1]）。 */
    public static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return -2f;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na <= 0 || nb <= 0) return -2f;
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }

    // ------------------------------------------------------------ 注册/验证

    /**
     * 用一段或多段 wav 注册某人的声纹。
     * 音频 > 9s 时自动切成 ~3s 段（多段平均更稳）；否则整段。
     *
     * @return 成功注册的段数；0/-1 为失败。
     */
    public int enrollFromWav(String name, String wavPath) {
        if (!ensureInit()) return -1;
        try {
            float[] samples = readWavAsFloat(wavPath);
            return enrollFromSamples(name, samples, 16000);
        } catch (Throwable t) {
            Log.e(TAG, "[SPK] 注册异常: " + t, t);
            return -1;
        }
    }

    /**
     * 用 PCM float 样本注册（面板「录制声纹」按钮的路径）。
     * 语义与 {@link #enrollFromWav} 完全一致：> 9s 自动切 3s 段，同名覆盖。
     *
     * @return 成功注册的段数；-1 为失败。
     */
    public int enrollFromSamples(String name, float[] samples, int sampleRate) {
        if (!ensureInit()) return -1;
        if (samples == null || samples.length == 0) {
            Log.w(TAG, "[SPK] 注册样本为空");
            return -1;
        }
        try {
            List<float[]> embs = new ArrayList<float[]>();

            float totalSec = samples.length / (float) sampleRate;
            if (totalSec > 9.0f) {
                int segLen = 3 * sampleRate;
                int maxSegs = 4;
                for (int off = 0; off + segLen <= samples.length && embs.size() < maxSegs; off += segLen) {
                    float[] seg = new float[segLen];
                    System.arraycopy(samples, off, seg, 0, segLen);
                    float[] e = extractFromSamples(seg, sampleRate);
                    if (e != null) embs.add(e);
                }
            }
            if (embs.isEmpty()) {
                float[] e = extractFromSamples(samples, sampleRate);
                if (e != null) embs.add(e);
            }
            if (embs.isEmpty()) return -1;

            float[][] arr = new float[embs.size()][];
            embs.toArray(arr);

            // 先清旧的同名（若有），避免 add 语义歧义（单人语义：每次录制覆盖 owner）
            if (mManager.contains(name)) {
                mManager.remove(name);
            }
            boolean ok = mManager.addList(name, arr);
            if (!ok) {
                Log.e(TAG, "[SPK] addList 返回 false（name=" + name + "）");
                return -1;
            }
            persist(name, arr);
            Log.i(TAG, "[SPK] 注册完成: " + name + " 段数=" + arr.length
                    + " 总时长=" + String.format("%.1fs", totalSec)
                    + " 已注册人数=" + mManager.numSpeakers());
            return arr.length;

        } catch (Throwable t) {
            Log.e(TAG, "[SPK] 注册异常: " + t, t);
            return -1;
        }
    }

    /**
     * 用**已算好的向量列表**直接注册（面板引导式录制的路径 ——
     * 逐句录制时每段已提过向量，全部通过后一次性落盘，避免重复计算）。
     */
    public int enrollFromEmbeddings(String name, List<float[]> embs) {
        if (!ensureInit() || embs == null || embs.isEmpty()) return -1;
        try {
            float[][] arr = new float[embs.size()][];
            embs.toArray(arr);
            if (mManager.contains(name)) {
                mManager.remove(name);
            }
            if (!mManager.addList(name, arr)) {
                Log.e(TAG, "[SPK] addList 返回 false（name=" + name + "）");
                return -1;
            }
            persist(name, arr);
            Log.i(TAG, "[SPK] 注册完成(引导式): " + name + " 段数=" + arr.length
                    + " 已注册人数=" + mManager.numSpeakers());
            return arr.length;
        } catch (Throwable t) {
            Log.e(TAG, "[SPK] 注册异常: " + t, t);
            return -1;
        }
    }

    /**
     * 16bit LE PCM 字节流 → float[-1,1) 样本。
     * （面板录制从 WakeService 取回的是字节流，用这条转换。）
     */
    public static float[] pcm16leToFloat(byte[] pcm) {
        if (pcm == null || pcm.length < 2) return new float[0];
        int n = pcm.length / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            int lo = pcm[i * 2] & 0xff;
            int hi = pcm[i * 2 + 1];
            out[i] = (short) ((hi << 8) | lo) / 32768.0f;
        }
        return out;
    }

    /** 验证 embedding 是否属于 name。 */
    public boolean verifyName(String name, float[] emb, float threshold) {
        if (!ensureInit() || emb == null) return false;
        if (emb.length != mDim) {
            Log.e(TAG, "[SPK] 维度不符 drop: got=" + emb.length + " want=" + mDim);
            return false;
        }
        if (!mManager.contains(name)) return false;
        return mManager.verify(name, emb, threshold);
    }

    /** 在已注册人中找最相似者（返回姓名或空串）。 */
    public String searchName(float[] emb, float threshold) {
        if (!ensureInit() || emb == null) return "";
        if (emb.length != mDim) return "";
        String r = mManager.search(emb, threshold);
        return (r == null) ? "" : r;
    }

    /**
     * 与已注册某人的**最大余弦分数**（纯分数，不做阈值判定）。
     *
     * <p>用途：唤醒专项拟合 —— 每次声纹兜底验证都要把"裸分数"记进日志，
     * 之后按实际分布校准阈值（原生 Manager 的 verify 只吐布尔值，拿不到分数）。
     *
     * @return 最大余弦；未注册 / 引擎不可用 / 维度不符时返回 {@code -2f}。
     */
    public float scoreName(String name, float[] emb) {
        if (!ensureInit() || emb == null || emb.length != mDim) return -2f;
        float[][] list = mRegistry.get(name);
        if (list == null || list.length == 0) return -2f;
        float best = -1f;
        for (int i = 0; i < list.length; i++) {
            float c = cosine(list[i], emb);
            if (c > best) best = c;
        }
        return best;
    }

    /** 是否已注册过某人（轻量查询：内存优先，未初始化时退化查磁盘文件）。 */
    public boolean hasName(String name) {
        if (name == null) return false;
        if (mRegistry.containsKey(name)) return true;
        return new File(mVpDir, name + ".emb").exists();
    }

    public boolean removeName(String name) {
        if (!ensureInit()) return false;
        boolean ok = mManager.contains(name) && mManager.remove(name);
        mRegistry.remove(name);
        File f = new File(mVpDir, name + ".emb");
        if (f.exists()) f.delete();
        Log.i(TAG, "[SPK] 删除声纹: " + name + " ok=" + ok + " 剩余=" + mManager.numSpeakers());
        return ok;
    }

    public String[] listNames() {
        if (mManager == null) return new String[0];
        String[] r = mManager.allSpeakerNames();
        return (r == null) ? new String[0] : r;
    }

    // ------------------------------------------------------------ 持久化

    private void persist(String name, float[][] embs) {
        DataOutputStream os = null;
        try {
            File f = new File(mVpDir, name + ".emb");
            os = new DataOutputStream(new FileOutputStream(f));
            os.writeInt(mDim);
            os.writeInt(embs.length);
            for (int i = 0; i < embs.length; i++) {
                os.writeInt(embs[i].length);
                for (int j = 0; j < embs[i].length; j++) {
                    os.writeFloat(embs[i][j]);
                }
            }
            os.flush();
            // ★ 同步内存副本（供 scoreName 求"裸分数"，唤醒专项拟合用）
            mRegistry.put(name, embs);
            Log.i(TAG, "[SPK] 声纹已落盘: " + f.getName() + " (" + f.length() + " bytes)");
        } catch (Throwable t) {
            Log.e(TAG, "[SPK] 落盘失败: " + t, t);
        } finally {
            if (os != null) try { os.close(); } catch (Throwable ignored) { }
        }
    }

    /** 把磁盘上已注册的声纹恢复到内存 manager（App 重启后仍在）。 */
    private void restoreAll() {
        if (mManager == null) return;
        File[] fs = mVpDir.listFiles();
        if (fs == null) return;
        int okCount = 0;
        for (int i = 0; i < fs.length; i++) {
            String nm = fs[i].getName();
            if (!nm.endsWith(".emb")) continue;
            try {
                DataInputStream is = new DataInputStream(new FileInputStream(fs[i]));
                int dim = is.readInt();
                int n = is.readInt();
                float[][] arr = new float[n][];
                for (int k = 0; k < n; k++) {
                    int len = is.readInt();
                    arr[k] = new float[len];
                    for (int j = 0; j < len; j++) arr[k][j] = is.readFloat();
                }
                is.close();
                if (dim != mDim) {
                    Log.w(TAG, "[SPK] 跳过 " + nm + "（dim " + dim + " != " + mDim + "）");
                    continue;
                }
                String name = nm.substring(0, nm.length() - 4);
                mManager.addList(name, arr);
                mRegistry.put(name, arr);   // ★ 同步内存副本（scoreName 用）
                okCount++;
            } catch (Throwable t) {
                Log.w(TAG, "[SPK] 恢复 " + nm + " 失败: " + t);
            }
        }
        if (okCount > 0) {
            Log.i(TAG, "[SPK] 已从磁盘恢复 " + okCount + " 个声纹");
        }
    }

    // ------------------------------------------------------------ WAV 解析

    /**
     * 读 wav（16k/mono/16bit PCM）→ float[-1,1)。
     * 遍历 chunk 找 fmt/data（比硬编码 44 字节稳，兼容带 LIST 块的 wav）。
     */
    public static float[] readWavAsFloat(String path) throws Exception {
        FileInputStream is = new FileInputStream(path);
        byte[] data;
        try {
            int total = is.available();
            data = new byte[total];
            int off = 0;
            while (off < total) {
                int r = is.read(data, off, total - off);
                if (r <= 0) break;
                off += r;
            }
        } finally {
            is.close();
        }

        if (data.length < 44 || !(data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F')) {
            throw new IllegalArgumentException("不是合法 WAV（长度 " + data.length + "）");
        }

        int sampleRate = 16000;
        int bitsPerSample = 16;
        int channels = 1;
        int dataOff = -1;
        int dataLen = 0;

        int pos = 12;
        while (pos + 8 <= data.length) {
            int id0 = data[pos] & 0xff, id1 = data[pos + 1] & 0xff,
                    id2 = data[pos + 2] & 0xff, id3 = data[pos + 3] & 0xff;
            int sz = (data[pos + 4] & 0xff) | ((data[pos + 5] & 0xff) << 8)
                    | ((data[pos + 6] & 0xff) << 16) | ((data[pos + 7] & 0xff) << 24);
            if (sz < 0) sz = 0;
            boolean isFmt = (id0 == 'f' && id1 == 'm' && id2 == 't');
            boolean isData = (id0 == 'd' && id1 == 'a' && id2 == 't' && id3 == 'a');
            if (isFmt && pos + 8 + 16 <= data.length) {
                int p = pos + 8;
                int fmt = (data[p] & 0xff) | ((data[p + 1] & 0xff) << 8);
                channels = (data[p + 2] & 0xff) | ((data[p + 3] & 0xff) << 8);
                sampleRate = (data[p + 4] & 0xff) | ((data[p + 5] & 0xff) << 8)
                        | ((data[p + 6] & 0xff) << 16) | ((data[p + 7] & 0xff) << 24);
                bitsPerSample = (data[p + 14] & 0xff) | ((data[p + 15] & 0xff) << 8);
                if (fmt != 1) throw new IllegalArgumentException("非 PCM wav（format=" + fmt + "）");
                if (bitsPerSample != 16) throw new IllegalArgumentException("仅支持 16bit（当前 " + bitsPerSample + "）");
            } else if (isData) {
                dataOff = pos + 8;
                dataLen = Math.min(sz, data.length - dataOff);
                break;
            }
            pos += 8 + sz + (sz & 1);
        }

        if (dataOff < 0 || dataLen <= 0) {
            throw new IllegalArgumentException("找不到 data 块");
        }
        if (channels > 1) {
            // 下混到 mono：按声道平均
            int frames = dataLen / 2 / channels;
            float[] out = new float[frames];
            for (int f = 0; f < frames; f++) {
                int acc = 0;
                for (int c = 0; c < channels; c++) {
                    int idx = dataOff + (f * channels + c) * 2;
                    int lo = data[idx] & 0xff;
                    int hi = data[idx + 1];
                    acc += (short) ((hi << 8) | lo);
                }
                out[f] = acc / (float) channels / 32768.0f;
            }
            if (sampleRate != 16000) {
                throw new IllegalArgumentException("非 16k（当前 " + sampleRate + "），P0 不做重采样");
            }
            return out;
        }

        if (sampleRate != 16000) {
            throw new IllegalArgumentException("非 16k（当前 " + sampleRate + "），P0 不做重采样");
        }

        int n = dataLen / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            int lo = data[dataOff + i * 2] & 0xff;
            int hi = data[dataOff + i * 2 + 1];
            out[i] = (short) ((hi << 8) | lo) / 32768.0f;
        }
        return out;
    }
}
