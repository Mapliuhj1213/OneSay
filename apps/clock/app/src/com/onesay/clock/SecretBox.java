package com.onesay.clock;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 密钥加密存储（OneSay 开源版）：AndroidKeyStore 里的 AES-256-GCM 密钥（不可导出）加密后再写 SharedPreferences。
 * 存储格式 "ks1:" + Base64(iv[12] + 密文)。旧版明文值读出后由 {@link Prefs} 透明迁移成密文。
 * 任何失败都返回空串 / 原样不写，绝不把明文或异常里的内容写日志。
 */
final class SecretBox {
    private SecretBox() { }

    static final String PREFIX = "ks1:";
    private static final String ALIAS = "onesay_clock_secrets";

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return g.generateKey();
    }

    static String seal(String plain) {
        if (plain == null || plain.length() == 0) return "";
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV(), ct = c.doFinal(plain.getBytes("UTF-8"));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return PREFIX + Base64.encodeToString(all, Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;   // 调用方：加密失败就不保存（宁可让用户重填，也不存明文）
        }
    }

    static String open(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored == null ? "" : stored;
        try {
            byte[] all = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }
}
