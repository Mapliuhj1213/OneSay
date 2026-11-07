package com.onesay.assistant.v2.fastscreen;

/**
 * 快速读屏助手（root）认可的 App 证书指纹。开源版为空：不内置任何人的证书，助手 fail closed。
 * 自行签名构建：填 {"com.onesay.assistant", "<你的 release 证书 SHA-256 小写 hex>"} 等，
 * 并在设备上给 exec 守护设置同样的 BIXBY_EXEC_SIG_REL / BIXBY_EXEC_SIG_DBG。
 */
final class FsPins {
    private FsPins() {}

    static final String[][] PINNED = {};
}
