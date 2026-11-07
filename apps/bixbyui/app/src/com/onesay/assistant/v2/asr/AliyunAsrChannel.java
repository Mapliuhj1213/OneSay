package com.onesay.assistant.v2.asr;

import android.os.SystemClock;
import android.util.Log;

import com.onesay.assistant.v2.net.HttpClient;
import com.onesay.assistant.v2.util.PcmUtil;
import com.onesay.assistant.v2.util.TextUtil;

import org.json.JSONObject;


/**
 * 阿里云「一句话识别」通道（RESTful，零第三方依赖）。
 *
 * <pre>
 *   POST https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr
 *        ?appkey=&lt;AppKey&gt;&amp;format=pcm&amp;sample_rate=16000
 *        &amp;enable_punctuation_prediction=true
 *        &amp;enable_inverse_text_normalization=true
 *   Headers: X-NLS-Token / Content-Type: application/octet-stream
 *   Body   : 原始 PCM（16-bit LE 单声道）
 *   响应    : {"task_id":"","result":"北京的天气","status":20000000,"message":"SUCCESS"}
 * </pre>
 *
 * <h3>为什么选「一句话识别」而不是「实时语音识别」</h3>
 * 实时识别是 WebSocket 流式（"边说边出字"），但要自己实现 WebSocket 客户端或引入官方 SDK，
 * 而这个工程的语音交互本来就是"唤醒 → 说一句指令 → 执行"的分段模式，
 * 一句话识别（≤60 秒音频，整段 POST）语义完全吻合，且只需 {@code HttpURLConnection}。
 *
 * <p>将来若要做"边说边显示"的实时字幕，应新增一个 {@link AsrChannel} 实现，
 * 上层无需改动 —— 这正是把通道抽象成接口的原因。
 *
 * <h3>本类不改音频格式</h3>
 * 设备侧 {@code AudioRecord} 采的就是 16kHz / 单声道 / PCM-16bit，
 * 与接口要求天然一致，**不需要重采样或转码**，直接把字节流发出去。
 * 如果将来换了采样率，在 {@link #recognize} 里加转换，别去动采集端。
 */
public final class AliyunAsrChannel implements AsrChannel {

    private static final String TAG = "AliyunAsr";

    /** 阿里云成功状态码 */
    private static final int STATUS_SUCCESS = 20000000;

    /** 一句话识别单次音频上限：官方要求 ≤60 秒 */
    private static final int MAX_AUDIO_SECONDS = 60;

    /**
     * 疑似鉴权失效的状态码 —— 命中就重签 Token 并重试一次。
     * 这些码在官方文档里分散在"Token 过期""权限不足""长时间未使用"几处，
     * 与其逐条精确匹配（文档会变），不如按"与鉴权相关"归类后统一自愈。
     */
    private static final int[] AUTH_STATUS = {
            40000001, // API 调用超出限制 / 鉴权失败
            40000012, // 无效的 Token / 权限不足
            40020105, // AppKey 不存在或与 Token 不匹配
            40020503  // 长时间未使用，Token 已过期
    };

    private final AliyunAsrConfig mConfig;
    private final AliyunTokenManager mTokenManager;

    public AliyunAsrChannel(AliyunAsrConfig config, AliyunTokenManager tokenManager) {
        mConfig = config;
        mTokenManager = tokenManager;
    }

    @Override
    public String name() {
        return "aliyun-oneshot";
    }

    /**
     * 廉价就绪判断：开关 + 配置完整 + Token 看似可用。
     * <b>不发起网络</b> —— 因为它会在热路径被查询。
     */
    @Override
    public boolean isReady() {
        if (!mConfig.isEnabled()) return false;
        if (!mConfig.isComplete()) return false;
        return mTokenManager.hasValidToken();
    }

    @Override
    public AsrResult recognize(byte[] pcm, int sampleRate) {
        return recognize(pcm, sampleRate, null);
    }

    /**
     * NLS 一句话识别**不支持热词上下文**（该能力在百炼的 qwen3-asr-flash 上），
     * 所以这里明确忽略 {@code hotwords} 参数 —— 忽略是契约允许的降级行为，
     * 不是错误。要热词纠错就切到 DashScope 通道。
     */
    @Override
    public AsrResult recognize(byte[] pcm, int sampleRate, String hotwords) {
        long t0 = SystemClock.elapsedRealtime();

        if (!mConfig.isEnabled()) {
            return AsrResult.none("云端 ASR 未启用");
        }
        if (!mConfig.isComplete()) {
            return AsrResult.fail("配置不完整（缺 AppKey 或凭据）", AsrResult.Source.CLOUD, 0L);
        }
        if (pcm == null || pcm.length < 3200) {
            // < 100ms 的音频（3200B = 1600 samples @16k）不可能是有效指令，直接拒发，省额度
            return AsrResult.fail("音频过短(" + (pcm == null ? 0 : pcm.length) + "B)，不发送", AsrResult.Source.CLOUD, 0L);
        }
        if (PcmUtil.exceeds(pcm, sampleRate, MAX_AUDIO_SECONDS)) {
            return AsrResult.fail("音频超长(" + PcmUtil.seconds(pcm, sampleRate) + "s > "
                    + MAX_AUDIO_SECONDS + "s)，一句话识别不接受", AsrResult.Source.CLOUD, 0L);
        }

        // 第一次尝试
        AsrResult r = doRecognize(pcm, sampleRate, t0);
        if (r.ok) return r;

        // 鉴权类失败 → 重签 Token 后重试一次（Token 过期是最常见的一次性失败）
        if (r.error != null && r.error.contains("AUTH")) {
            Log.i(TAG, "[ASR] 疑似 Token 失效，重签后重试一次");
            mTokenManager.invalidate();
            String fresh = mTokenManager.getToken(true);
            if (fresh.length() > 0) {
                return doRecognize(pcm, sampleRate, t0);
            }
        }
        return r;
    }

    /** 真正发一次请求。 */
    private AsrResult doRecognize(byte[] pcm, int sampleRate, long t0) {
        String token = mTokenManager.getToken();
        if (token.length() == 0) {
            return AsrResult.fail("无法获取 Token: " + mTokenManager.getLastError(),
                    AsrResult.Source.CLOUD, SystemClock.elapsedRealtime() - t0);
        }

        String url = mConfig.getAsrEndpoint()
                + "?appkey=" + AliyunTokenManager.percentEncode(mConfig.getAppKey())
                + "&format=pcm"
                + "&sample_rate=" + sampleRate
                + "&enable_punctuation_prediction=true"
                + "&enable_inverse_text_normalization=true";

        // HTTP 收敛到 net.HttpClient（超时/读 errorStream/异常收敛与原实现一致）
        HttpClient.Response resp = HttpClient.execute(new HttpClient.Request(url)
                .method("POST")
                .timeouts(mConfig.getTimeoutMs(), mConfig.getTimeoutMs())
                .header("X-NLS-Token", token)
                .header("Content-Type", "application/octet-stream")
                .body(pcm)
                .fixedLength(true));
        long cost = SystemClock.elapsedRealtime() - t0;
        if (resp.error != null) {
            // 网络抖动 / DNS 失败 / 超时都收敛到这里 —— 上层据此降级离线
            String msg = resp.errorText();
            Log.w(TAG, "[ASR] 请求异常 " + cost + "ms: " + msg);
            return AsrResult.fail("网络异常: " + msg, AsrResult.Source.CLOUD, cost);
        }
        int httpCode = resp.code;
        String body = resp.body;
        try {
            // HTTP 层鉴权失败（网关直接挡掉时不会带业务 status）
            if (httpCode == 401 || httpCode == 403) {
                return AsrResult.fail("AUTH: HTTP " + httpCode + " " + TextUtil.truncate(body, 120),
                        AsrResult.Source.CLOUD, cost);
            }
            if (httpCode != 200) {
                return AsrResult.fail("HTTP " + httpCode + ": " + TextUtil.truncate(body, 160),
                        AsrResult.Source.CLOUD, cost);
            }

            // 业务层解析
            JSONObject o = new JSONObject(body);
            int status = o.optInt("status", -1);
            String message = o.optString("message", "");
            String result = o.optString("result", "");

            if (status == STATUS_SUCCESS) {
                if (result.trim().length() == 0) {
                    return AsrResult.fail("识别成功但文本为空（可能是静音或纯噪声）",
                            AsrResult.Source.CLOUD, cost);
                }
                Log.i(TAG, "[ASR] 云端识别成功 " + cost + "ms: \"" + result + "\"");
                return AsrResult.ok(result.trim(), AsrResult.Source.CLOUD, cost);
            }

            String err = (isAuthStatus(status) ? "AUTH: " : "")
                    + "status=" + status + " msg=" + message;
            Log.w(TAG, "[ASR] 云端识别失败 " + cost + "ms: " + err);
            return AsrResult.fail(err, AsrResult.Source.CLOUD, cost);

        } catch (Throwable t) {
            String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "[ASR] 响应解析异常 " + cost + "ms: " + msg);
            return AsrResult.fail("网络异常: " + msg, AsrResult.Source.CLOUD, cost);
        }
    }

    private static boolean isAuthStatus(int status) {
        for (int s : AUTH_STATUS) {
            if (s == status) return true;
        }
        return false;
    }
}
