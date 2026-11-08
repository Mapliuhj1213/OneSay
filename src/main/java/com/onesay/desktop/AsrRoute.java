package com.onesay.desktop;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 电脑语音识别用哪个：默认「先看云端」——启动时和每次识别前看云端能不能用（填了识别 Key 且连得上，短超时，结果缓存一会儿），
 * 能用走云端，不能用自动回落本机离线识别；设置里也可以强制「仅云端」或「仅本机」。
 *
 * <p>探测不带 Key、不带音频、不带任何内容：只对识别服务的地址发一个空的 HEAD 请求，收到任何 HTTP 回应（哪怕 401 / 404）就算连得上。
 * 「仅云端」不探测（直接用，失败就报错）；「仅本机」从不联网。
 */
public final class AsrRoute {

    /** 设置里的选择（配置键 asr_pref）。 */
    public enum Pref {
        AUTO("auto", "自动（先看云端）"), CLOUD("cloud", "仅云端"), LOCAL("local", "仅本机");
        public final String id, zh;

        Pref(String id, String zh) {
            this.id = id;
            this.zh = zh;
        }

        /** 认不出来的当「自动」（默认）。 */
        public static Pref of(String s) {
            if (s != null) for (Pref p : values()) if (p.id.equalsIgnoreCase(s.trim())) return p;
            return AUTO;
        }
    }

    /** 实际用的识别。 */
    public enum Engine {
        CLOUD("cloud", "云端"), LOCAL("local", "本机离线"), NONE("none", "没有可用的识别");
        public final String id, zh;

        Engine(String id, String zh) {
            this.id = id;
            this.zh = zh;
        }
    }

    /** 云端能不能连上（测试里换掉）。 */
    public interface Probe {
        boolean reachable(String base, int timeoutMs) throws Exception;
    }

    public interface Recognizer {
        String recognize(byte[] pcm, int rate) throws Exception;
    }

    public interface CloudFactory {
        Recognizer make(String base, String key);
    }

    /** 一次识别的结果：文字 + 实际用的识别（界面显示）+ 是否从云端回落到了本机。 */
    public static final class Result {
        public final String text;
        public final Engine engine;
        public final boolean fellBack;

        Result(String text, Engine engine, boolean fellBack) {
            this.text = text;
            this.engine = engine;
            this.fellBack = fellBack;
        }
    }

    public static final int PROBE_TIMEOUT_MS = 1500;
    /** 探测结果缓存：连得上记 60 秒，连不上记 15 秒（网络恢复后很快回到云端）。 */
    public static final long OK_TTL_MS = 60_000, FAIL_TTL_MS = 15_000;

    private final Config cfg;
    public volatile Probe probe = AsrRoute::httpProbe;
    public volatile CloudFactory cloud = (base, key) -> new Asr(base, key)::recognize;
    public volatile Recognizer local = LocalAsr::recognize;
    public volatile BooleanSupplier localAvailable = LocalAsr::available;
    public volatile LongSupplier clock = System::currentTimeMillis;
    public volatile int probeTimeoutMs = PROBE_TIMEOUT_MS;

    private final Object lock = new Object();
    private Boolean cloudOk;
    private long cachedAt;
    private String cachedFor = "";
    /** 探测次数（测试看缓存有没有生效）。 */
    volatile int probes;

    /** 界面显示：最近一次选到 / 用到的识别和原因。 */
    public volatile Engine current = Engine.NONE;
    public volatile String why = "还没识别过";

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "onesay-asr-probe");
        t.setDaemon(true);
        return t;
    });

    public AsrRoute(Config cfg) {
        this.cfg = cfg;
    }

    public Pref pref() {
        return Pref.of(cfg.asrPref);
    }

    /** 改了 Key / 地址 / 选择后清掉缓存。 */
    public void invalidate() {
        synchronized (lock) {
            cloudOk = null;
            cachedFor = "";
        }
    }

    /** 云端现在能不能用：没填 Key = 不能；否则看缓存，过期了就探测（最多 probeTimeoutMs，超时 = 不能）。 */
    public boolean cloudAvailable() {
        if (cfg.asrKey == null || cfg.asrKey.isEmpty()) return false;
        String base = cfg.asrBase == null || cfg.asrBase.isBlank() ? Asr.DEFAULT_BASE : cfg.asrBase.trim();
        String tag = base + "|" + Integer.toHexString(cfg.asrKey.hashCode());
        long now = clock.getAsLong();
        synchronized (lock) {
            if (cloudOk != null && tag.equals(cachedFor) && now - cachedAt < (cloudOk ? OK_TTL_MS : FAIL_TTL_MS)) return cloudOk;
        }
        boolean ok = probeOnce(base);
        remember(tag, ok);
        return ok;
    }

    private void remember(String tag, boolean ok) {
        synchronized (lock) {
            cloudOk = ok;
            cachedAt = clock.getAsLong();
            cachedFor = tag;
        }
    }

    /** 识别时云端出了错（网络断 / Key 失效）：记成「连不上」，接下来一会儿直接走本机。 */
    void markCloudDown() {
        String base = cfg.asrBase == null || cfg.asrBase.isBlank() ? Asr.DEFAULT_BASE : cfg.asrBase.trim();
        remember(base + "|" + Integer.toHexString(cfg.asrKey.hashCode()), false);
    }

    private boolean probeOnce(String base) {
        probes++;
        final Probe p = probe;
        final int ms = probeTimeoutMs;
        CompletableFuture<Boolean> f = CompletableFuture.supplyAsync(() -> {
            try {
                return p.reachable(base, ms);
            } catch (Exception e) {
                return false;
            }
        }, POOL);
        try {
            return Boolean.TRUE.equals(f.get(ms + 100L, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            f.cancel(true);
            return false; // 超时 / 出错 = 不能用
        }
    }

    /** 现在该用哪个（不识别，只选）。 */
    public Engine choose() {
        Pref p = pref();
        boolean keySet = cfg.asrKey != null && !cfg.asrKey.isEmpty();
        Engine e;
        String w;
        switch (p) {
            case CLOUD:
                e = keySet ? Engine.CLOUD : Engine.NONE;
                w = keySet ? "设置为仅云端" : "设置为仅云端，但没填识别 Key";
                break;
            case LOCAL:
                e = localAvailable.getAsBoolean() ? Engine.LOCAL : Engine.NONE;
                w = e == Engine.LOCAL ? "设置为仅本机" : "设置为仅本机，但这台电脑没有可用的中文离线识别";
                break;
            default:
                if (cloudAvailable()) {
                    e = Engine.CLOUD;
                    w = "云端可用";
                } else {
                    String c = keySet ? "云端连不上" : "没填识别 Key";
                    boolean l = localAvailable.getAsBoolean();
                    e = l ? Engine.LOCAL : Engine.NONE;
                    w = l ? c + "，改用本机离线识别" : c + "，本机也没有可用的中文离线识别";
                }
        }
        current = e;
        why = w;
        return e;
    }

    /** 识别一段 16 位单声道 PCM。「自动」下云端出错会回落本机（同一段录音，不再上传别处）。 */
    public Result recognize(byte[] pcm, int rate) throws Exception {
        return recognize(pcm, rate, choose());
    }

    public Result recognize(byte[] pcm, int rate, Engine e) throws Exception {
        if (e == Engine.NONE) throw new IllegalStateException(why);
        if (e == Engine.LOCAL) return new Result(local.recognize(pcm, rate), Engine.LOCAL, false);
        try {
            return new Result(cloud.make(cfg.asrBase, cfg.asrKey).recognize(pcm, rate), Engine.CLOUD, false);
        } catch (Exception ex) {
            if (pref() != Pref.AUTO || !localAvailable.getAsBoolean()) throw ex;
            markCloudDown();
            current = Engine.LOCAL;
            why = "云端识别出错（" + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()) + "），这次改用本机离线识别";
            return new Result(local.recognize(pcm, rate), Engine.LOCAL, true);
        }
    }

    /** 启动时在后台先看一遍（不阻塞启动）。 */
    public void warmUp() {
        POOL.execute(() -> {
            try {
                if (pref() != Pref.CLOUD) localAvailable.getAsBoolean(); // 本机能力检查也顺便做掉（结果会缓存）
                choose();
            } catch (Throwable ignored) {
            }
        });
    }

    /** 给界面：设置、当前、原因。 */
    public java.util.Map<String, Object> view() {
        java.util.Map<String, Object> m = new java.util.TreeMap<>();
        m.put("asr_pref", pref().id);
        m.put("asr_pref_zh", pref().zh);
        m.put("asr_engine", current.id);
        m.put("asr_engine_zh", current.zh);
        m.put("asr_why", why);
        return m;
    }

    /** 默认探测：对识别地址发一个空的 HEAD（不带 Key、不带内容），有任何 HTTP 回应就算连得上。 */
    static boolean httpProbe(String base, int timeoutMs) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/models"))
                .timeout(Duration.ofMillis(timeoutMs))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Void> r = http.send(req, HttpResponse.BodyHandlers.discarding());
        return r.statusCode() >= 100 && r.statusCode() < 600;
    }
}
