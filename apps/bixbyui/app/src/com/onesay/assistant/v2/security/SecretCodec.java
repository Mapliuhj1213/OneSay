package com.onesay.assistant.v2.security;

/**
 * 凭据加解密的抽象（真机 = {@link KeystoreCodec}，JVM 测试 = 软件 AES 替身）。
 * 实现可以抛任何异常；调用方（{@link SecretValues}）负责降级，不让异常冒到业务层。
 */
public interface SecretCodec {
    /** 加密，返回不含前缀的 Base64 文本（IV + 密文）。 */
    String encrypt(String plain) throws Exception;

    /** 解密 {@link #encrypt} 的输出。 */
    String decrypt(String blob) throws Exception;
}
