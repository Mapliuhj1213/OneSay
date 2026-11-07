package com.onesay.assistant.v2.asr;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ASR 调度层 —— 决定"这次识别走本地还是走云端"，并负责云端调用的线程与生命周期。
 *
 * <h3>四种模式</h3>
 * <table border="1">
 *   <tr><th>模式</th><th>行为</th><th>适用</th></tr>
 *   <tr>
 *     <td>{@link Mode#OFFLINE_ONLY}</td>
 *     <td>完全走本地 vosk，云端一次都不碰</td>
 *     <td>离线优先 / 省额度 / 隐私敏感</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Mode#OFFLINE_FIRST_CLOUD_FALLBACK}</td>
 *     <td>本地先跑；本地**识别不出**或**路由不了**时才发云端</td>
 *     <td><b>默认</b>。保持现有秒级响应，只在本地搞不定时用云端补位</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Mode#CLOUD_FIRST_FALLBACK_OFFLINE}</td>
 *     <td>窗一结束就发云端（准确率最高），网络失败/超时才退回本地结果</td>
 *     <td>口音重、本地模型实在不行的场景。<b>代价是每次多 0.5~2 秒延迟</b></td>
 *   </tr>
 *   <tr>
 *     <td>{@link Mode#CLOUD_ONLY}</td>
 *     <td><b>本地 ASR 全屏蔽</b>：不依赖 vosk 文本触发/裁决，断句改由能量门判定，
 *         候选不执行、不显示、失败不降级 —— 识别结果**只认云端**</td>
 *     <td>试"全走云端"的体验 / 本地模型质量确实不行时的极端档。
 *         <b>代价：无实时转写、延迟最高、说话太长会顶到整窗上限</b></td>
 *   </tr>
 * </table>
 *
 * <p>默认选中间那档，是因为它刚好对症"识别率不行"这个痛点：
 * 能本地确认的（打开蓝牙、调亮度）继续秒级执行，本地听岔了就交给云端重判，
 * 而不是把所有指令都拖到云上走一遍。
 *
 * <h3>线程与配额</h3>
 * 云端识别走**单线程池**：语音指令天然是串行的，并发发多个请求既浪费额度
 * （按次计费）又会让结果乱序。队列满了直接丢弃并记日志，绝不阻塞麦克风线程。
 */
public final class AsrRouter {

    private static final String TAG = "AsrRouter";

    /** 调度模式 */
    public enum Mode {
        /** 只用本地 */
        OFFLINE_ONLY,
        /** 本地优先，本地搞不定才用云端 */
        OFFLINE_FIRST_CLOUD_FALLBACK,
        /** 云端优先，失败降级本地 */
        CLOUD_FIRST_FALLBACK_OFFLINE,
        /**
         * **全走云端** —— 本地 ASR（vosk）逻辑上完全屏蔽。
         *
         * <p>与 {@link #CLOUD_FIRST_FALLBACK_OFFLINE} 的三点差异（WakeService 实现）：
         * <ol>
         *   <li>断句判据改用**能量门**（说过话+静音1.6s），不依赖 vosk 是否吐字；</li>
         *   <li>**不设**离线兜底、**不启**竞速计时器 —— 一切等云端裁决，慢就慢；</li>
         *   <li>失败**不降级**本地（云端拿不回来就是"没听清"终态）。</li>
         * </ol>
         * 本地 vosk 文本在链路上被全面封口：不显示（无实时转写）、不执行、不参与判定。
         */
        CLOUD_ONLY
    }

    /** 识别结果回调（**在主线程**调用，方便直接碰 UI 与既有链路） */
    public interface Callback {
        void onResult(AsrResult result);
    }

    private static volatile AsrRouter sInstance;

    private final Context mContext;
    private final AliyunAsrConfig mConfig;
    private final AliyunTokenManager mTokenManager;
    /** NLS 通道（POP 签名 + Token + 一句话识别）。需要 AccessKeyId/Secret/AppKey 三件套。 */
    private final AliyunAsrChannel mNls;
    /** 百炼通道（Bearer + JSON）。只需要一个 {@code sk-...}，**比 NLS 简单得多**。 */
    private final DashScopeAsrChannel mDashScope;

    private final ExecutorService mExec = Executors.newSingleThreadExecutor(new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger(1);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "asr-cloud-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });
    private final Handler mMain = new Handler(Looper.getMainLooper());

    // ---- 轻量统计（不引入依赖，够用即可）----
    private final AtomicInteger mCloudOk = new AtomicInteger();
    private final AtomicInteger mCloudFail = new AtomicInteger();
    private volatile long mLastCloudLatencyMs = -1L;
    private volatile String mLastCloudText = "";

    private AsrRouter(Context context) {
        mContext = context.getApplicationContext();
        mConfig = new AliyunAsrConfig(context);
        mTokenManager = new AliyunTokenManager(mConfig);
        mNls = new AliyunAsrChannel(mConfig, mTokenManager);
        mDashScope = new DashScopeAsrChannel(mConfig);
    }

    public static AsrRouter getInstance(Context context) {
        if (sInstance == null) {
            synchronized (AsrRouter.class) {
                if (sInstance == null) {
                    sInstance = new AsrRouter(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    // ------------------------------------------------------------------
    // 对外
    // ------------------------------------------------------------------

    public AliyunAsrConfig config() {
        return mConfig;
    }

    public AliyunTokenManager tokenManager() {
        return mTokenManager;
    }

    public Mode getMode() {
        return mConfig.getMode();
    }

    /**
     * 当前生效的云端通道。
     *
     * <p><b>选择规则：配了百炼 Key 就用百炼，否则退回 NLS。</b>
     * 理由是百炼这条路的摩擦小得多 —— 一个 {@code sk-...} 就能跑，
     * 不需要 RAM 子账号、不需要自签 POP 签名、不需要 24h Token 续期。
     * NLS 通道保留着（实现完整、验证过），等你真要接企业版智能语音交互时再启用。
     *
     * <p>每次调用都重新判一次，所以运行期用广播写入 Key 后**立即生效**，不必重启。
     */
    private AsrChannel pickCloud() {
        return mConfig.hasDashScopeKey() ? mDashScope : mNls;
    }

    /** 当前云端通道名（日志/自测用）。 */
    public String activeCloudName() {
        return pickCloud().name();
    }

    /** 云端通道此刻是否可用（不发起网络）。 */
    public boolean isCloudReady() {
        return pickCloud().isReady();
    }

    /**
     * 凭据是否齐全到能真正发一次识别（按**当前选中的通道**判断，不混用两套）。
     *
     * <p>NLS 需要 AK/AppKey 三件套齐全（{@link AliyunAsrConfig#isComplete()}），
     * 百炼只需要一个 Key —— 反过来用 NLS 的完整性去卡百炼会误判。
     */
    public boolean isCloudConfigured() {
        return mConfig.hasDashScopeKey() || mConfig.isComplete();
    }

    /**
     * 异步云端识别。结果在主线程回调。
     *
     * <p>不满足条件时**不会**发起请求，而是立刻回调一个失败结果 ——
     * 调用方无需自己判断前置条件，统一在回调里按 ok 分支处理即可。
     */
    public void recognizeCloudAsync(final byte[] pcm, final Callback cb) {
        recognizeCloudAsync(pcm, null, cb);
    }

    /**
     * 带热词上下文的异步识别。
     *
     * @param hotwords 显式热词；传 {@code null} 表示**自动构建**
     *                 （静态词表 + 本机已安装应用名，见 {@link AsrHotwords}）。
     *                 自测里要跑"不带热词"的对照组时请传空串 {@code ""}。
     */
    public void recognizeCloudAsync(final byte[] pcm, String hotwords, final Callback cb) {
        if (!mConfig.isEnabled()) {
            post(cb, AsrResult.none("云端 ASR 未启用（开关关闭）"));
            return;
        }
        if (!isCloudConfigured()) {
            post(cb, AsrResult.fail("云端凭据不完整（当前通道=" + activeCloudName()
                    + "）：" + mConfig.describe(), AsrResult.Source.CLOUD, 0L));
            return;
        }

        // 通道在此刻定下来（不是在 lambda 里再查），保证一次请求只用一个通道
        final AsrChannel ch = pickCloud();
        // 热词也在发请求前定下来。★ R14：get() 只读缓存（过期在后台重建），不再在调用方线程（窗到点 = 主线程）上枚举 PackageManager
        final String hw = (hotwords == null) ? AsrHotwords.get(mContext) : hotwords;
        mExec.execute(new Runnable() {
            @Override
            public void run() {
                final AsrResult r = ch.recognize(pcm, 16000, hw);
                if (r.ok) {
                    mCloudOk.incrementAndGet();
                    mLastCloudLatencyMs = r.latencyMs;
                    mLastCloudText = r.text;
                } else {
                    mCloudFail.incrementAndGet();
                    mLastCloudLatencyMs = r.latencyMs;
                }
                post(cb, r);
            }
        });
    }

    /**
     * ★ R14（diag P1-3）：TLS 预热 —— 按下键时（流式不可用、要走非流式兜底时）先对 DashScope 发一个 HEAD，
     * 把 TCP + TLS 握手（沙箱实测约 360 ms）藏进应答音里；HttpClient 不再 disconnect()，连接留在系统连接池里给随后的识别请求复用。
     * 在识别单线程池里跑（与识别请求串行，不会两个连接抢着握手）。
     */
    public void prewarmHttpAsync() {
        if (!mConfig.isEnabled() || !mConfig.hasDashScopeKey()) return;
        mExec.execute(new Runnable() {
            @Override
            public void run() {
                com.onesay.assistant.v2.net.HttpClient.Response r = com.onesay.assistant.v2.net.HttpClient.prewarm(com.onesay.assistant.v2.net.LlmEndpoints.dashscopeChatUrl(), 3000);
                Log.i(TAG, "[ASR-T] 非流式 TLS 预热 " + r.latencyMs + "ms code=" + r.code + (r.error != null ? " err=" + r.errorText() : ""));
            }
        });
    }

    /** 同步云端识别 —— **只允许在后台线程调用**（WakeService 的自测/探针场景）。 */
    public AsrResult recognizeCloudSync(byte[] pcm) {
        return recognizeCloudSync(pcm, null);
    }

    /**
     * 同步云端识别（带热词）。
     *
     * @param hotwords null = 自动构建热词；空串 = 明确不带热词（自测对照组）
     */
    public AsrResult recognizeCloudSync(byte[] pcm, String hotwords) {
        if (!mConfig.isEnabled()) return AsrResult.none("云端 ASR 未启用");
        if (!isCloudConfigured()) {
            return AsrResult.fail("云端凭据不完整（当前通道=" + activeCloudName() + "）",
                    AsrResult.Source.CLOUD, 0L);
        }
        String hw = (hotwords == null) ? AsrHotwords.build(mContext) : hotwords;
        return pickCloud().recognize(pcm, 16000, hw);
    }

    /** 热词表预览（自测输出用，已截断）。 */
    public String hotwordsPreview() {
        String hw = AsrHotwords.build(mContext);
        if (hw == null) return "(空)";
        return hw.length() > 90 ? hw.substring(0, 90) + "..." : hw;
    }

    /** 一行统计摘要，供日志与自测输出。 */
    public String stats() {
        return "channel=" + activeCloudName()
                + ", enabled=" + mConfig.isEnabled()
                + ", ready=" + isCloudReady()
                + ", cloud(ok=" + mCloudOk.get() + ", fail=" + mCloudFail.get()
                + ", lastLatency=" + mLastCloudLatencyMs + "ms"
                + ", lastText=\"" + mLastCloudText + "\")";
    }

    private void post(final Callback cb, final AsrResult r) {
        if (cb == null) return;
        mMain.post(new Runnable() {
            @Override
            public void run() {
                try {
                    cb.onResult(r);
                } catch (Throwable t) {
                    Log.w(TAG, "回调异常: " + t.getMessage());
                }
            }
        });
    }
}
