package com.onesay.assistant.v2.mikaplug;

import android.content.Context;
import android.content.SharedPreferences;

import com.onesay.assistant.plug.api.Storage;
import com.onesay.assistant.plug.outbox.core.Outbox;

import java.util.HashMap;
import java.util.Map;

/**
 * ★ mika-plug（mika.outbox）：Outbox 的宿主适配。记录存在 SharedPreferences {@code bixby_outbox}（commit 同步写，
 * 先记账再发送），超过 {@link #MAX} 条时整体清掉最旧的一半（记录只为去重，丢旧的无妨）。
 */
public final class MikaOutbox {
    private MikaOutbox() {
    }

    static final int MAX = 300;
    private static volatile Outbox sBox;

    public static Outbox get(Context ctx) {
        Outbox b = sBox;
        if (b != null) return b;
        synchronized (MikaOutbox.class) {
            if (sBox == null) sBox = new Outbox(new Prefs(ctx.getApplicationContext().getSharedPreferences("bixby_outbox", Context.MODE_PRIVATE)));
            return sBox;
        }
    }

    static final class Prefs implements Storage {
        final SharedPreferences sp;

        Prefs(SharedPreferences sp) {
            this.sp = sp;
        }

        @SuppressWarnings("unchecked")
        @Override public Map<String, String> all() {
            Map<String, String> out = new HashMap<String, String>();
            for (Map.Entry<String, ?> e : sp.getAll().entrySet()) if (e.getValue() instanceof String) out.put(e.getKey(), (String) e.getValue());
            return out;
        }

        @Override public boolean put(String k, String v) {
            if (!sp.contains(k) && sp.getAll().size() >= MAX) prune();
            return sp.edit().putString(k, v).putLong("t:" + k, System.currentTimeMillis()).commit();
        }

        @Override public boolean remove(String k) {
            return sp.edit().remove(k).remove("t:" + k).commit();
        }

        void prune() {
            Map<String, ?> all = sp.getAll();
            java.util.List<Map.Entry<String, ?>> ts = new java.util.ArrayList<Map.Entry<String, ?>>();
            for (Map.Entry<String, ?> e : all.entrySet()) if (e.getKey().startsWith("t:")) ts.add(e);
            java.util.Collections.sort(ts, new java.util.Comparator<Map.Entry<String, ?>>() {
                @Override public int compare(Map.Entry<String, ?> a, Map.Entry<String, ?> b) {
                    long x = a.getValue() instanceof Long ? (Long) a.getValue() : 0, y = b.getValue() instanceof Long ? (Long) b.getValue() : 0;
                    return x < y ? -1 : x == y ? 0 : 1;
                }
            });
            SharedPreferences.Editor ed = sp.edit();
            for (int i = 0; i < ts.size() / 2; i++) {
                String k = ts.get(i).getKey();
                ed.remove(k).remove(k.substring(2));
            }
            ed.commit();
        }
    }
}
