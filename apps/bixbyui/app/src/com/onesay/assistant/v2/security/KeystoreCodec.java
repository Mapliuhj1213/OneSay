package com.onesay.assistant.v2.security;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Android Keystore 实现（API 23+；Note 8 是 26）。AES-256-GCM，IV 由 Keystore 随机生成，
 * 存为 base64(ivLen(1 字节) + iv + 密文)。密钥别名固定；不要求用户认证（后台服务要能读）。
 * 卸载 App 时系统会删掉密钥（数据也一起没了）；把加密后的 prefs 还原到另一次安装上会解不开 → 见 {@link SecretValues}。
 */
public final class KeystoreCodec implements SecretCodec {

    public static final String ALIAS = "bixbyui_secret_v1";
    private static final String STORE = "AndroidKeyStore";
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(STORE);
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    @Override
    public String encrypt(String plain) throws Exception {
        Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
        byte[] out = new byte[1 + iv.length + ct.length];
        out[0] = (byte) iv.length;
        System.arraycopy(iv, 0, out, 1, iv.length);
        System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    @Override
    public String decrypt(String blob) throws Exception {
        byte[] in = Base64.decode(blob, Base64.NO_WRAP);
        int ivLen = in[0] & 0xff;
        if (ivLen < 12 || ivLen > 16 || in.length <= 1 + ivLen) throw new IllegalArgumentException("bad blob");
        Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, in, 1, ivLen));
        byte[] pt = c.doFinal(in, 1 + ivLen, in.length - 1 - ivLen);
        return new String(pt, "UTF-8");
    }
}
