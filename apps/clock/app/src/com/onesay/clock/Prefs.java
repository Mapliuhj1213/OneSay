package com.onesay.clock;

import android.content.Context;
import android.content.SharedPreferences;

import com.onesay.clock.core.Alarm;
import com.onesay.clock.core.Flags;
import com.onesay.clock.core.SnoozeBook;

import java.util.List;

/** App 私有设置与状态（SharedPreferences "bc"）。闹钟、贪睡、开关、百炼 key 都在这里；allowBackup=false。 */
public final class Prefs {
    private final SharedPreferences sp;
    private static final Object LOCK = new Object();

    public Prefs(Context c) { sp = c.getApplicationContext().getSharedPreferences("bc", Context.MODE_PRIVATE); }

    public boolean flag(String k) {
        if (Flags.notImplemented(k)) return false;
        return sp.getBoolean(k, Flags.boolDefault(k));
    }

    public void setFlag(String k, boolean v) { if (!Flags.notImplemented(k)) sp.edit().putBoolean(k, v).apply(); }

    public int i(String k, int def) { return sp.getInt(k, def); }

    public void setI(String k, int v) { sp.edit().putInt(k, v).apply(); }

    public String s(String k, String def) { return sp.getString(k, def); }

    public void setS(String k, String v) { sp.edit().putString(k, v).apply(); }

    public long l(String k, long def) { return sp.getLong(k, def); }

    public void setL(String k, long v) { sp.edit().putLong(k, v).apply(); }

    // ---- 闹钟
    public List<Alarm> alarms() { synchronized (LOCK) { return Alarm.listFromJson(sp.getString("alarms", "")); } }

    public void saveAlarms(List<Alarm> as) {
        synchronized (LOCK) {
            try { sp.edit().putString("alarms", Alarm.listToJson(as)).commit(); } catch (Exception ignored) { }
        }
    }

    public Alarm alarm(int id) {
        for (Alarm a : alarms()) if (a.id == id) return a;
        return null;
    }

    public void put(Alarm a) {
        synchronized (LOCK) {
            List<Alarm> as = alarms();
            boolean found = false;
            for (int k = 0; k < as.size(); k++) if (as.get(k).id == a.id) { as.set(k, a); found = true; }
            if (!found) {
                int max = 0;
                for (Alarm x : as) max = Math.max(max, x.id);
                if (a.id <= 0) a.id = max + 1;
                as.add(a);
            }
            saveAlarms(as);
        }
    }

    public void delete(int id) {
        synchronized (LOCK) {
            List<Alarm> as = alarms();
            for (int k = as.size() - 1; k >= 0; k--) if (as.get(k).id == id) as.remove(k);
            saveAlarms(as);
            SnoozeBook b = snoozes();
            if (b.remove(id)) saveSnoozes(b);
        }
    }

    // ---- 贪睡（独立记录）
    public SnoozeBook snoozes() { synchronized (LOCK) { return SnoozeBook.parse(sp.getString("snoozes", "")); } }

    public void saveSnoozes(SnoozeBook b) { synchronized (LOCK) { sp.edit().putString("snoozes", b.serialize()).commit(); } }

    // ---- [borrow-1112] 错过的闹钟：上次排上的那一次 / 上次真响了的那一次（core/Missed.Mark 串）
    public com.onesay.clock.core.Missed.Mark schedMark() { return com.onesay.clock.core.Missed.Mark.parse(sp.getString("schedMark", "")); }

    public void setSchedMark(com.onesay.clock.core.Missed.Mark m) { sp.edit().putString("schedMark", m == null ? "" : m.serialize()).commit(); }

    public com.onesay.clock.core.Missed.Mark firedMark() { return com.onesay.clock.core.Missed.Mark.parse(sp.getString("firedMark", "")); }

    public void setFiredMark(com.onesay.clock.core.Missed.Mark m) { sp.edit().putString("firedMark", m == null ? "" : m.serialize()).commit(); }

    public int lastFiredDay() { return sp.getInt("lastFiredDay", 0); }

    public void setLastFiredDay(int d) { sp.edit().putInt("lastFiredDay", d).commit(); }

    // ---- 百炼 key（只在内存与私有 prefs；日志只记长度）
    public String bailianKey() { return secret("bailianKey"); }

    public void setBailianKey(String k) { setSecret("bailianKey", k); }

    // ---- 和风天气 key / host（0.1.6；同百炼 key：只在私有 prefs，日志只记长度）
    public String qweatherKey() { return secret(Flags.QW_KEY); }

    public void setQweatherKey(String k) { setSecret(Flags.QW_KEY, k); }

    public String qweatherHost() { return com.onesay.clock.core.RainLine.cleanHost(sp.getString(Flags.QW_HOST, "")); }

    public void setQweatherHost(String h) { sp.edit().putString(Flags.QW_HOST, com.onesay.clock.core.RainLine.cleanHost(h)).apply(); }

    /** 降雨提醒不可用的原因；null = 可用（开关开 + key + host 都有）。 */
    public String rainUnavailable() {
        return com.onesay.clock.core.RainLine.unavailableReason(flag(Flags.X_RAIN_LINE), qweatherKey().length() > 0, qweatherHost().length() > 0);
    }

    /** 开源版：云端语音音色 ID / 模型（用户填写，不内置）。 */
    // ---- 开源版：密钥一律经 AndroidKeyStore 加密后存（SecretBox）；旧明文值读到时透明迁移
    private String secret(String k) {
        String v = sp.getString(k, "");
        if (v == null || v.length() == 0) return "";
        if (!v.startsWith(SecretBox.PREFIX)) {           // 旧版明文：迁移成密文（加密失败则保持原样，不丢用户配置）
            String sealed = SecretBox.seal(v);
            if (sealed != null) sp.edit().putString(k, sealed).apply();
            return v.trim();
        }
        return SecretBox.open(v).trim();
    }

    private void setSecret(String k, String v) {
        String t = v == null ? "" : v.trim();
        if (t.length() == 0) { sp.edit().remove(k).apply(); return; }
        String sealed = SecretBox.seal(t);
        if (sealed != null) sp.edit().putString(k, sealed).apply();
    }

    /** 开源版：云端语音服务地址（用户可改，必须 https）。 */
    public String ttsEndpoint() { return com.onesay.clock.core.Endpoint.clean(sp.getString(Flags.TTS_ENDPOINT, Flags.TTS_ENDPOINT_DEF)); }

    public String cosyVoice() { return sp.getString(com.onesay.clock.core.Flags.COSY_VOICE, com.onesay.clock.core.Flags.COSY_VOICE_DEF).trim(); }

    public String cosyModel() { return sp.getString(com.onesay.clock.core.Flags.COSY_MODEL, com.onesay.clock.core.Flags.COSY_MODEL_DEF).trim(); }

    public boolean onboarded() { return sp.getBoolean("onboarded", false); }

    public void setOnboarded() { sp.edit().putBoolean("onboarded", true).apply(); }
}
