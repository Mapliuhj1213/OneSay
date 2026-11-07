package com.onesay.plan.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.onesay.plan.core.Defaults;

/** 计划 App 的设置（prefs plan_settings；键名是用户数据格式的一部分，不要改名）。默认值见 {@link Defaults}。 */
public final class Settings {
    private Settings() {}

    static final String P = "plan_settings";

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    public static boolean notify(Context c) {
        return sp(c).getBoolean("notify", Defaults.NOTIFY);
    }

    public static void setNotify(Context c, boolean on) {
        sp(c).edit().putBoolean("notify", on).apply();
    }

    public static int eventLead(Context c) {
        return sp(c).getInt("event_lead", Defaults.EVENT_LEAD_MIN);
    }

    public static void setEventLead(Context c, int m) {
        sp(c).edit().putInt("event_lead", m).apply();
    }

    /** 票临期提醒档位（分钟，逗号分隔），默认「120,30」；空串 = 票不提醒。 */
    public static String ticketLeads(Context c) {
        return sp(c).getString("ticket_leads", Defaults.TICKET_LEADS);
    }

    public static void setTicketLeads(Context c, String s) {
        sp(c).edit().putString("ticket_leads", s).apply();
    }

    public static long lastCheck(Context c) {
        return sp(c).getLong("remind_last_check", 0);
    }

    public static void setLastCheck(Context c, long t) {
        sp(c).edit().putLong("remind_last_check", t).apply();
    }

    public static void markImported(Context c, String summary) {
        sp(c).edit().putString("last_import", summary).putLong("last_import_at", System.currentTimeMillis()).apply();
    }

    public static String lastImport(Context c) {
        return sp(c).getString("last_import", "");
    }

    public static long lastImportAt(Context c) {
        return sp(c).getLong("last_import_at", 0);
    }

    public static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ 快捷指令动作清单（OneSay 推送）

    static java.io.File actionsFile(Context c) {
        return new java.io.File(c.getFilesDir(), "shortcut_actions.json");
    }

    /** 存 OneSay 推送的动作清单（先写临时文件再改名，进程被杀不留半截）。 */
    public static void saveShortcutActions(Context c, String json) throws java.io.IOException {
        java.io.File f = actionsFile(c);
        java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
        java.io.FileOutputStream o = new java.io.FileOutputStream(tmp);
        try {
            o.write(json.getBytes("UTF-8"));
        } finally {
            o.close();
        }
        if (!tmp.renameTo(f)) throw new java.io.IOException("动作清单改名失败");
    }

    /** 当前动作清单：OneSay 推送过就用推送的（探测过的可用性），否则默认表（上机版专属项置灰）。 */
    public static com.onesay.plan.core.Shortcuts.Actions.Catalog shortcutCatalog(Context c) {
        try {
            java.io.File f = actionsFile(c);
            if (f.exists()) {
                com.onesay.plan.core.Shortcuts.Actions.Catalog cat = com.onesay.plan.core.Shortcuts.Actions.parse(Store.readAll(f));
                if (cat != null) return cat;
            }
        } catch (Exception ignored) {
        }
        return com.onesay.plan.core.Shortcuts.Actions.defaults();
    }
}
