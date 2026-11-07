package com.onesay.assistant.v2.conn;

import android.content.Context;
import android.content.SharedPreferences;

import com.onesay.assistant.v2.security.SecretPrefs;

/** [conn] 用 {@link SecretPrefs}（Keystore AES-GCM，ks1: 前缀）存连接器密钥，独立 prefs 文件 bixby_conn_secrets。 */
public final class PrefsSecretStore implements SecretStore {

    public static final String PREFS = "bixby_conn_secrets";

    private final SharedPreferences mSp;

    public PrefsSecretStore(Context ctx) {
        this(ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    public PrefsSecretStore(SharedPreferences sp) {
        mSp = sp;
    }

    @Override
    public String get(String key) {
        return SecretPrefs.get(mSp, key);
    }

    @Override
    public void put(String key, String value) {
        SharedPreferences.Editor e = mSp.edit();
        if (value == null || value.length() == 0) e.remove(key);
        else SecretPrefs.put(e, key, value);
        e.commit();
    }

    @Override
    public String state(String key) {
        return String.valueOf(SecretPrefs.state(mSp, key));
    }
}
