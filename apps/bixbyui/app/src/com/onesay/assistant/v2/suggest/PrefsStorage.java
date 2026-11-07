package com.onesay.assistant.v2.suggest;

import android.content.Context;
import android.content.SharedPreferences;

import com.onesay.assistant.plug.api.Storage;

import java.util.HashMap;
import java.util.Map;

/** ★ feat/suggest：插件 Storage = SharedPreferences {@code bixby_suggest_store}（频控计数、去重散列、场景私有状态；不同步、不上报）。 */
final class PrefsStorage implements Storage {
    static final String NAME = "bixby_suggest_store";
    private final SharedPreferences mSp;

    PrefsStorage(Context ctx) {
        mSp = ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    @Override public Map<String, String> all() {
        Map<String, String> out = new HashMap<String, String>();
        for (Map.Entry<String, ?> e : mSp.getAll().entrySet()) if (e.getValue() instanceof String) out.put(e.getKey(), (String) e.getValue());
        return out;
    }

    @Override public boolean put(String key, String value) {
        mSp.edit().putString(key, value).apply();
        return true;
    }

    @Override public boolean remove(String key) {
        mSp.edit().remove(key).apply();
        return true;
    }
}
