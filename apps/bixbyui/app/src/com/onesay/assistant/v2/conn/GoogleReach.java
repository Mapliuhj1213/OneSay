package com.onesay.assistant.v2.conn;

/**
 * [conn] Google 可达性（纯逻辑，可单测）。手机在大陆网络下经常连不上 Google，这里负责「别卡死、说清楚、退回本机」：
 *
 * <ul>
 *   <li>任何一次 Google 调用回报 UNREACHABLE / TIMEOUT → 记为「不可达」，接下来 {@link #COOLDOWN_MS}（10 分钟）内
 *       {@link #tryGoogle()} 返回 false：邮件直接走 IMAP、日历直接读本机日历，<b>不再每次都等 8–20 秒超时</b>；</li>
 *   <li>冷却期满后放行一次试探：成功 → 恢复；又失败 → 再冷却（连续失败冷却时间翻倍，上限 1 小时）；</li>
 *   <li>任何一次成功（含授权错误这类「连得上但被拒」）都立即恢复为可达；</li>
 *   <li>{@link #probe}：主动探测（短超时 3 s / 3 s），给控制台「检测」按钮和登录前预检用。</li>
 * </ul>
 * {@link #notice()} 给用户 / 模型的一句话说明（不可达时非空），由调用方拼到回答里，保证用户知道为什么换了路。
 */
public final class GoogleReach implements com.onesay.assistant.v2.conn.mail.MailConnector.NetPolicy {

    public static final long COOLDOWN_MS = 10 * 60 * 1000L;
    public static final long MAX_COOLDOWN_MS = 60 * 60 * 1000L;
    /** 探测地址：Google OAuth 端点根路径，任何 HTTP 状态（含 404）都说明 TLS 到得了。 */
    public static final String PROBE_URL = "https://oauth2.googleapis.com/";

    public interface Clock {
        long now();
    }

    private final Clock mClock;
    private long mDownSince;
    private long mRetryAt;
    private long mLastFailAt;
    private int mFails;
    private String mLastReason = "";

    public GoogleReach(Clock clock) {
        mClock = clock != null ? clock : new Clock() {
            @Override public long now() { return System.currentTimeMillis(); }
        };
    }

    /** 现在要不要试 Google（不可达冷却期内返回 false）。 */
    @Override
    public synchronized boolean tryGoogle() {
        return mFails == 0 || mClock.now() >= mRetryAt;
    }

    public synchronized boolean down() {
        return mFails > 0;
    }

    @Override
    public synchronized void reportGoogle(ConnResult r) {
        if (r == null) return;
        if (r.code == ConnResult.Code.CANCELLED) return;   // 取消不说明网络通不通
        if (r.code == ConnResult.Code.UNREACHABLE || r.code == ConnResult.Code.TIMEOUT || r.code == ConnResult.Code.TLS_ERROR) {
            long now = mClock.now();
            if (mFails > 0 && now - mLastFailAt < 5000) return;   // 同一次调用被多层回报（GoogleApi + 邮件选路）只算一次
            mLastFailAt = now;
            if (mFails == 0) mDownSince = now;
            mFails++;
            long cd = COOLDOWN_MS;
            for (int i = 1; i < mFails && cd < MAX_COOLDOWN_MS; i++) cd *= 2;
            mRetryAt = now + Math.min(cd, MAX_COOLDOWN_MS);
            mLastReason = r.code == ConnResult.Code.TIMEOUT ? "超时" : r.code == ConnResult.Code.TLS_ERROR ? "证书校验失败" : "连不上";
        } else if (r.code != ConnResult.Code.NOT_CONFIGURED) {
            // 拿到了 HTTP 响应（成功 / 4xx / 授权失效）= 网络是通的
            mFails = 0;
            mRetryAt = 0;
            mLastReason = "";
        }
    }

    /** 不可达时给用户的一句话；可达返回 ""。 */
    public synchronized String notice() {
        if (mFails == 0) return "";
        long mins = Math.max(1, (mClock.now() - mDownSince) / 60000);
        return "手机现在连不上 Google（" + mLastReason + "，已持续约 " + mins + " 分钟，可能需要开代理 / VPN）";
    }

    public synchronized String status() {
        if (mFails == 0) return "google_reach=ok";
        long left = Math.max(0, mRetryAt - mClock.now()) / 1000;
        return "google_reach=down fails=" + mFails + " retry_in=" + left + "s";
    }

    /** 主动探测（阻塞，最多约 6 秒；后台线程调）。结果同样记入状态。 */
    public ConnResult probe(ConnHttp http) {
        ConnHttp.Resp r = http.send(new ConnHttp.Req("GET", PROBE_URL).timeouts(3000, 3000));
        ConnResult out;
        if (r.networkError()) {
            ConnResult.Code c = ConnErrors.classify(r.error);
            out = ConnResult.fail(c == ConnResult.Code.TIMEOUT || c == ConnResult.Code.TLS_ERROR || c == ConnResult.Code.CANCELLED
                    ? c : ConnResult.Code.UNREACHABLE, "连不上 Google：" + Redact.t(r.error));
        } else {
            out = ConnResult.ok("Google 可达（HTTP " + r.code + "）");
        }
        reportGoogle(out);
        return out;
    }
}
