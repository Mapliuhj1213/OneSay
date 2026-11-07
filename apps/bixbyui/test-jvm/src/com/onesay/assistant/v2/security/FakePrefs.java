package com.onesay.assistant.v2.security;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** JVM 用的 SharedPreferences 替身（只实现凭据代码用到的部分）。 */
public class FakePrefs implements SharedPreferences {
    public final Map<String, Object> map = new HashMap<String, Object>();
    public int commits;

    @Override public Map<String, ?> getAll() { return new HashMap<String, Object>(map); }
    @Override public String getString(String k, String d) { Object v = map.get(k); return v == null ? d : (String) v; }
    @Override public Set<String> getStringSet(String k, Set<String> d) { return d; }
    @Override public int getInt(String k, int d) { Object v = map.get(k); return v == null ? d : (Integer) v; }
    @Override public long getLong(String k, long d) { return d; }
    @Override public float getFloat(String k, float d) { return d; }
    @Override public boolean getBoolean(String k, boolean d) { Object v = map.get(k); return v == null ? d : (Boolean) v; }
    @Override public boolean contains(String k) { return map.containsKey(k); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

    @Override public Editor edit() {
        final Map<String, Object> pending = new HashMap<String, Object>();
        final Object REMOVE = new Object();
        return new Editor() {
            @Override public Editor putString(String k, String v) { pending.put(k, v); return this; }
            @Override public Editor putStringSet(String k, Set<String> v) { return this; }
            @Override public Editor putInt(String k, int v) { pending.put(k, v); return this; }
            @Override public Editor putLong(String k, long v) { pending.put(k, v); return this; }
            @Override public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
            @Override public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
            @Override public Editor remove(String k) { pending.put(k, REMOVE); return this; }
            @Override public Editor clear() { map.clear(); return this; }
            @Override public boolean commit() {
                for (Map.Entry<String, Object> e : pending.entrySet()) {
                    if (e.getValue() == REMOVE) map.remove(e.getKey()); else map.put(e.getKey(), e.getValue());
                }
                commits++;
                return true;
            }
            @Override public void apply() { commit(); }
        };
    }
}
