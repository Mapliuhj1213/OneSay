package com.onesay.assistant.v2.conn;

/** [conn] 连接器调用结果（不抛异常；错误分类让上层决定「去配置 / 换路 / 别重试」）。 */
public final class ConnResult {

    public enum Code {
        OK,
        /** 没配置（没 Client ID / 没登录 / 没令牌）。 */
        NOT_CONFIGURED,
        /** 授权失效（refresh_token 被吊销 / 过期 / 401 刷新后仍 401）→ 需要重新登录。 */
        AUTH_REVOKED,
        /** 网络不可达（断网 / 墙 / DNS）→ 退回本机能力。 */
        UNREACHABLE,
        /** 读阶段超时：请求可能已到对端，结果未知（连接阶段超时归 UNREACHABLE）。 */
        TIMEOUT,
        /** 请求被取消（线程中断 / 用户停止），不是网络问题。 */
        CANCELLED,
        /** TLS 证书 / 主机名校验失败（可能有中间人）。请求没发出。 */
        TLS_ERROR,
        /** 远端返回错误（4xx/5xx、JSON-RPC error、tool isError）。 */
        REMOTE_ERROR,
        /** 本机拒绝（不在白名单 / 缺确认令牌 / 草稿被改过）。 */
        DENIED,
        BAD_ARGS,
        RATE_LIMITED
    }

    public final Code code;
    /** 给模型 / 用户看的文本（成功时是结果，失败时是原因）。已脱敏。 */
    public final String text;
    /** 可选的结构化数据（JSON 字符串）。 */
    public final String data;

    private ConnResult(Code code, String text, String data) {
        this.code = code;
        this.text = text == null ? "" : text;
        this.data = data;
    }

    public static ConnResult ok(String text) {
        return new ConnResult(Code.OK, text, null);
    }

    public static ConnResult ok(String text, String data) {
        return new ConnResult(Code.OK, text, data);
    }

    public static ConnResult fail(Code code, String text) {
        return new ConnResult(code, Redact.s(text, 400), null);
    }

    public boolean ok() {
        return code == Code.OK;
    }

    /** 这类失败是否该退回本机能力（IMAP / 本机日历）。 */
    public boolean shouldFallback() {
        return code == Code.UNREACHABLE || code == Code.TIMEOUT || code == Code.TLS_ERROR
                || code == Code.NOT_CONFIGURED || code == Code.AUTH_REVOKED;
    }

    public boolean retryable() {
        return code == Code.TIMEOUT || code == Code.RATE_LIMITED || code == Code.UNREACHABLE;
    }

    @Override
    public String toString() {
        return code + ": " + Redact.s(text, 120);
    }
}
