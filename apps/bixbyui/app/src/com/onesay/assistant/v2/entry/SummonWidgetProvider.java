package com.onesay.assistant.v2.entry;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** 桌面小部件：一个按钮，点一下开始聆听（经不导出的跳板 {@link EntryLaunchActivity}）。 */
public class SummonWidgetProvider extends AppWidgetProvider {

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        int layout = res(c, "onesay_entry_widget", "layout");
        int btn = res(c, "onesay_entry_widget_root", "id");
        if (layout == 0) return;
        Intent i = new Intent(c, EntryLaunchActivity.class)
                .putExtra(EntryLaunchActivity.EXTRA_ENTRY, BuiltinEntries.WIDGET)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        PendingIntent pi = PendingIntent.getActivity(c, 0x0E17, i, PendingIntent.FLAG_UPDATE_CURRENT);
        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), layout);
            if (btn != 0) v.setOnClickPendingIntent(btn, pi);
            m.updateAppWidget(id, v);
        }
    }

    /** 资源 id（不引用生成的 R：资源表包名固定为 Java 包名，debug 变体只改了 applicationId）。 */
    static int res(Context c, String name, String type) {
        int id = c.getResources().getIdentifier(name, type, Entries.JAVA_PKG);
        return id != 0 ? id : c.getResources().getIdentifier(name, type, c.getPackageName());
    }
}
