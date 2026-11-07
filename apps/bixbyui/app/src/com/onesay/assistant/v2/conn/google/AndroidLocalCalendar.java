package com.onesay.assistant.v2.conn.google;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import java.util.ArrayList;
import java.util.List;

/** [conn/google] {@link LocalCalendar} 的 Android 实现（只读查询 + 预填新建页）。 */
public final class AndroidLocalCalendar implements LocalCalendar {

    private final Context mApp;

    public AndroidLocalCalendar(Context ctx) {
        mApp = ctx.getApplicationContext();
    }

    @Override
    public List<Event> events(long from, long to) throws Exception {
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, from);
        ContentUris.appendId(b, to);
        String[] proj = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION};
        List<Event> out = new ArrayList<Event>();
        Cursor c = mApp.getContentResolver().query(b.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC");
        if (c == null) return out;
        try {
            while (c.moveToNext() && out.size() < 50) {
                out.add(new Event(c.getString(0), c.getLong(1), c.getLong(2), c.getInt(3) != 0, c.getString(4)));
            }
        } finally {
            c.close();
        }
        return out;
    }

    @Override
    public boolean openInsert(String title, long begin, long end, boolean allDay, String location, String description) {
        try {
            Intent it = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                    .putExtra(CalendarContract.Events.TITLE, title);
            if (allDay) it.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true);
            if (location != null && location.length() > 0) it.putExtra(CalendarContract.Events.EVENT_LOCATION, location);
            if (description != null && description.length() > 0) it.putExtra(CalendarContract.Events.DESCRIPTION, description);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mApp.startActivity(it);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
