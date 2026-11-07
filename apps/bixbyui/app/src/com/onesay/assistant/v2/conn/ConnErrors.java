package com.onesay.assistant.v2.conn;

/** [conn] 异常 / HTTP 状态 → {@link ConnResult.Code}（纯函数）。 */
public final class ConnErrors {

    private ConnErrors() {}

    /**
     * 异常 → 错误码。整条 cause 链先扫一遍再按优先级判（2026-10-24 r2/fixes）：
     * <ol>
     *   <li>取消（线程被中断：非超时的 InterruptedIOException、ClosedByInterruptException、InterruptedException）→ CANCELLED；</li>
     *   <li>证书 / 主机名校验失败（CertificateException、CertPathValidatorException、SSLPeerUnverifiedException）→ TLS_ERROR
     *       （可能有中间人，不要说成「可能在大陆网络」）；</li>
     *   <li>连接阶段（{@link ConnectPhaseException}）的其它失败，包括连接超时、握手被掐 → UNREACHABLE（请求肯定没发出）；</li>
     *   <li>读阶段 SocketTimeoutException → TIMEOUT（请求可能已到对端，结果未知）；</li>
     *   <li>DNS / 连接被拒 / 路由 / 握手中途断开 / SocketException / EOF → UNREACHABLE。</li>
     * </ol>
     */
    public static ConnResult.Code classify(Throwable t) {
        if (t == null) return ConnResult.Code.REMOTE_ERROR;
        boolean cancelled = false, tls = false, connectPhase = false, timeout = false, unreachable = false;
        int depth = 0;
        for (Throwable c = t; c != null && depth < 32; c = c.getCause(), depth++) {
            String n = c.getClass().getName();
            if (c instanceof ConnectPhaseException) connectPhase = true;
            if (c instanceof java.net.SocketTimeoutException) {
                timeout = true;
            } else if (c instanceof java.io.InterruptedIOException || c instanceof InterruptedException
                    || n.endsWith("ClosedByInterruptException")) {
                cancelled = true;
            }
            if (c instanceof java.security.cert.CertificateException || n.endsWith("CertPathValidatorException")
                    || n.endsWith("SSLPeerUnverifiedException")) {
                tls = true;
            }
            if (c instanceof java.net.UnknownHostException || c instanceof java.net.ConnectException
                    || c instanceof java.net.NoRouteToHostException || c instanceof java.net.PortUnreachableException
                    || n.endsWith("SSLHandshakeException") || c instanceof java.net.SocketException
                    || c instanceof java.io.EOFException) {
                unreachable = true;
            }
            if (c.getCause() == c) break;
        }
        if (cancelled) return ConnResult.Code.CANCELLED;
        if (tls) return ConnResult.Code.TLS_ERROR;
        if (connectPhase) return ConnResult.Code.UNREACHABLE;
        if (timeout) return ConnResult.Code.TIMEOUT;
        if (unreachable) return ConnResult.Code.UNREACHABLE;
        return ConnResult.Code.REMOTE_ERROR;
    }

    public static ConnResult.Code classifyHttp(int code) {
        if (code < 0) return ConnResult.Code.UNREACHABLE;
        if (code == 401 || code == 403) return ConnResult.Code.AUTH_REVOKED;
        if (code == 429) return ConnResult.Code.RATE_LIMITED;
        if (code == 408 || code == 504) return ConnResult.Code.TIMEOUT;
        if (code == 400 || code == 422) return ConnResult.Code.BAD_ARGS;
        if (code >= 200 && code < 300) return ConnResult.Code.OK;
        return ConnResult.Code.REMOTE_ERROR;
    }

    /** 给用户看的短句。 */
    public static String human(ConnResult.Code c) {
        switch (c) {
            case UNREACHABLE: return "连不上服务器（断网，或当前网络到不了这个服务）";
            case TIMEOUT: return "服务器响应超时";
            case CANCELLED: return "请求已取消";
            case TLS_ERROR: return "安全连接校验失败（证书不对，网络可能被劫持）";
            case AUTH_REVOKED: return "授权已失效，需要重新登录 / 重新填令牌";
            case NOT_CONFIGURED: return "还没配置";
            case RATE_LIMITED: return "请求太频繁，被服务器限流";
            case DENIED: return "已被本机安全策略拒绝";
            case BAD_ARGS: return "参数不对";
            case OK: return "成功";
            default: return "服务器返回了错误";
        }
    }
}
