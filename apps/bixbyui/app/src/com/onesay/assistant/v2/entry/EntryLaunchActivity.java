package com.onesay.assistant.v2.entry;

import android.app.Activity;
import android.os.Bundle;


/**
 * 不导出的透明跳板：快捷开关（startActivityAndCollapse）、桌面小部件（PendingIntent）、桌面快捷方式（静态 shortcut，
 * 由系统以本 App 身份启动）都打到这里，再交给 {@link Entries#fire}。只接受这三个入口 id。
 */
public class EntryLaunchActivity extends Activity {
    public static final String EXTRA_ENTRY = "entry";
    /** 可选：voice（默认）/ text / screen（识屏快捷方式用）。 */
    public static final String EXTRA_MODE = "mode";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String id = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ENTRY);
        if (BuiltinEntries.QS_TILE.equals(id) || BuiltinEntries.WIDGET.equals(id) || BuiltinEntries.SHORTCUT.equals(id)) {
            String m = getIntent().getStringExtra(EXTRA_MODE);
            Entries.fire(this, id, m != null ? BuiltinEntries.parseMode(m)
                    : com.onesay.assistant.v2.entry.keywake.KeyWake.modeFor(com.onesay.assistant.v2.entry.keywake.KeyWakeSettings.entryAction(this, id)));
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
