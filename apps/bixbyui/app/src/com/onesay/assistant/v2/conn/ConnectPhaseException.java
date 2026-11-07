package com.onesay.assistant.v2.conn;

import java.io.IOException;

/**
 * [conn] 标记：异常发生在「建立连接」阶段（TCP 连接 / TLS 握手），请求一个字节都还没发出去。
 * {@link ConnErrors#classify} 据此把连接阶段超时归为 UNREACHABLE（确定没发出），
 * 只有读阶段超时才算 TIMEOUT（结果未知）。
 */
public final class ConnectPhaseException extends IOException {
    private static final long serialVersionUID = 1L;

    public ConnectPhaseException(Throwable cause) {
        super(cause == null ? "connect failed" : cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
    }
}
