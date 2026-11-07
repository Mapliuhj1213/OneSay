package com.onesay.assistant.v2.conn;

/**
 * [conn] 连接器的密钥存取（MCP 服务器令牌、Google refresh_token / client_secret）。
 * 真机实现 {@link PrefsSecretStore}（经 SecretPrefs → Android Keystore 加密，ks1: 前缀）；单测用内存实现。
 * 约定：值<b>永不</b>进日志。
 */
public interface SecretStore {

    /** 没有返回 ""。 */
    String get(String key);

    /** value 为 null 或空 = 删除。 */
    void put(String key, String value);

    /** 是否已加密落盘（给状态页 / 诊断看，不暴露值）。 */
    String state(String key);
}
