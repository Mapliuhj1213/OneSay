package com.onesay.assistant.v2.wake;

import android.content.Context;
import android.content.SharedPreferences;

/** 本地唤醒词设置的读写入口（能力页只调这里，不直接写 prefs）。 */
public final class WakeWordSettings {
    private WakeWordSettings() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(WakeWords.PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean(WakeWords.KEY_ENABLED, WakeWords.ENABLED_DEFAULT);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(WakeWords.KEY_ENABLED, v).apply();
    }

    public static String getLines(Context c) {
        return sp(c).getString(WakeWords.KEY_LINES, "");
    }

    /** 校验后保存；成功返回 null，否则返回给人看的错误。空串 = 清空。 */
    public static String saveLines(Context c, String text) {
        String v = text == null ? "" : text.trim();
        if (v.length() > 0) {
            String err = WakeWords.validate(v, readTokens(new java.io.File(c.getFilesDir(), "kws/tokens.txt")));
            if (err != null) return err;
        }
        sp(c).edit().putString(WakeWords.KEY_LINES, v).apply();
        return null;
    }

    static java.util.Set<String> readTokens(java.io.File f) {
        if (!f.isFile()) return null;
        java.util.Set<String> s = new java.util.HashSet<String>();
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    String t = line.trim();
                    if (t.length() == 0) continue;
                    int sp = t.indexOf(' ');
                    s.add(sp < 0 ? t : t.substring(0, sp));
                }
            } finally { br.close(); }
        } catch (Exception e) {
            return null;
        }
        return s;
    }
}
