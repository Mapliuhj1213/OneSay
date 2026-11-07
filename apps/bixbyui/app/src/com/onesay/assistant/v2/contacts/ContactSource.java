package com.onesay.assistant.v2.contacts;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.provider.ContactsContract;
import android.util.Log;

import com.onesay.assistant.v2.executor.ShellExecutor;
import com.onesay.assistant.v2.executor.UnifiedExecutor;

import java.util.ArrayList;
import java.util.List;

/**
 * 本机通讯录读取（只按词预筛，不读全量；结果只在内存里给 {@link ContactMatcher} 用，不落盘、不上传、不进日志）。
 *
 * <ol>
 *   <li>首选 ContentResolver：Manifest 本来就有 READ_CONTACTS（运行时已授权时才用）；</li>
 *   <li>没授权 / 查询异常 → root 守护跑 {@code content query --uri content://com.android.contacts/data/phones}（Manifest 不动）。</li>
 * </ol>
 * 日志 tag Contacts，只写「途径 + 条数」。
 */
public final class ContactSource implements ContactMatcher.Directory {

    public static final String TAG = "Contacts";

    private final Context mApp;

    public ContactSource(Context ctx) {
        mApp = ctx.getApplicationContext();
    }

    /** 装成 AgentLoop 可用的本机通讯录（面板服务 / 唤醒服务 onCreate 调一次即可）。 */
    public static void installFor(Context ctx) {
        if (ctx == null || ContactMatcher.directory() != null) return;
        ContactMatcher.install(new ContactSource(ctx));
    }

    @Override
    public List<ContactMatcher.Contact> lookup(List<String> terms) {
        if (terms == null || terms.isEmpty()) return new ArrayList<ContactMatcher.Contact>();
        long t0 = System.currentTimeMillis();
        List<ContactMatcher.Contact> l = null;
        String via = "resolver";
        if (granted()) l = viaResolver(terms);
        if (l == null) {
            via = "root";
            l = viaRoot(terms);
        }
        Log.i(TAG, "[CONTACTS] 预筛 via=" + via + " 词数=" + terms.size() + " → " + (l == null ? "失败" : l.size() + " 条")
                + " cost=" + (System.currentTimeMillis() - t0) + "ms");
        return l;
    }

    private boolean granted() {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            return mApp.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private List<ContactMatcher.Contact> viaResolver(List<String> terms) {
        StringBuilder sel = new StringBuilder();
        List<String> argv = new ArrayList<String>();
        for (String t : terms) {
            if (t == null || t.length() == 0) continue;
            if (sel.length() > 0) sel.append(" OR ");
            sel.append(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME).append(" LIKE ?");
            argv.add("%" + t + "%");
        }
        if (argv.isEmpty()) return new ArrayList<ContactMatcher.Contact>();
        Cursor c = null;
        try {
            c = mApp.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                    sel.toString(), argv.toArray(new String[0]), null);
            if (c == null) return null;
            List<ContactMatcher.Contact> l = new ArrayList<ContactMatcher.Contact>();
            while (c.moveToNext() && l.size() < 200) {
                l.add(new ContactMatcher.Contact(c.getString(0), c.getString(1)));
            }
            return l;
        } catch (Throwable t) {
            Log.w(TAG, "[CONTACTS] ContentResolver 查询失败，改走 root: " + t.getClass().getSimpleName());
            return null;
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
    }

    private List<ContactMatcher.Contact> viaRoot(List<String> terms) {
        String where = ContactMatcher.likeWhere(terms);
        if (where == null) return new ArrayList<ContactMatcher.Contact>();
        try {
            ShellExecutor sh = UnifiedExecutor.getInstance(mApp).getShellExecutor();
            ShellExecutor.ShellResponse r = sh.execute("content query --uri content://com.android.contacts/data/phones"
                    + " --projection display_name:data1 --where \"" + where + "\"");
            if (r == null || r.isDown || r.isTimeout) return null;
            return ContactMatcher.parseContentQuery(r.output);
        } catch (Throwable t) {
            Log.w(TAG, "[CONTACTS] root 查询失败: " + t.getClass().getSimpleName());
            return null;
        }
    }
}
