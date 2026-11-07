package com.onesay.assistant.v2.conn;

import java.util.HashMap;
import java.util.Map;

/** 测试用内存密钥库。 */
public class MemSecrets implements SecretStore {
    public final Map<String, String> map = new HashMap<String, String>();

    @Override public String get(String key) {
        String v = map.get(key);
        return v == null ? "" : v;
    }

    @Override public void put(String key, String value) {
        if (value == null || value.length() == 0) map.remove(key);
        else map.put(key, value);
    }

    @Override public String state(String key) {
        return map.containsKey(key) ? "ENCRYPTED" : "EMPTY";
    }
}
