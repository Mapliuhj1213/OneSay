package com.onesay.assistant.v2.security;

import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** JVM 替身：与 KeystoreCodec 同格式（ivLen + iv + GCM 密文），密钥在内存里。 */
public class SoftCodec implements SecretCodec {
    private final SecretKeySpec key;
    public int encrypts, decrypts;

    public SoftCodec() { this((byte) 7); }
    public SoftCodec(byte seed) {
        byte[] k = new byte[32];
        for (int i = 0; i < k.length; i++) k[i] = (byte) (seed + i);
        key = new SecretKeySpec(k, "AES");
    }

    @Override public String encrypt(String plain) throws Exception {
        encrypts++;
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
        byte[] out = new byte[1 + iv.length + ct.length];
        out[0] = (byte) iv.length;
        System.arraycopy(iv, 0, out, 1, iv.length);
        System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
        return Base64.getEncoder().encodeToString(out);
    }

    @Override public String decrypt(String blob) throws Exception {
        decrypts++;
        byte[] in = Base64.getDecoder().decode(blob);
        int ivLen = in[0] & 0xff;
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, in, 1, ivLen));
        return new String(c.doFinal(in, 1 + ivLen, in.length - 1 - ivLen), "UTF-8");
    }
}
