package com.onesay.clock.api;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import com.onesay.clock.L;
import com.onesay.clock.core.AlarmApi;
import com.onesay.clock.core.LinkContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * 伴侣连接（{@link LinkContract}）：只有 call()，没有表。exported，清单里 android:permission = signature 级 ALARM_CONTROL；
 * 代码里再验一次：本 App、持该权限、或与本 App 同证书，其余（含 root / adb shell）拒绝。不内置任何证书指纹。
 */
public class LinkProvider extends ContentProvider {

    @Override public boolean onCreate() { return true; }

    void enforce() {
        Context c = getContext();
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        boolean perm = c.checkCallingPermission(AlarmApi.PERMISSION) == PackageManager.PERMISSION_GRANTED;
        boolean same = false;
        if (uid >= 10000) {
            try { same = c.getPackageManager().checkSignatures(Process.myUid(), uid) == PackageManager.SIGNATURE_MATCH; } catch (Exception ignored) { }
        }
        if (!perm || !same) {
            L.i(c, "LINK 拒绝 uid=" + uid);
            throw new SecurityException("OneSay 时钟：调用方不是同证书的 OneSay 助手");
        }
    }

    @Override public Bundle call(String method, String arg, Bundle x) {
        enforce();
        Context c = getContext();
        Bundle out = new Bundle();
        if (x == null) x = new Bundle();
        if (!LinkContract.isLinkMethod(method)) return code(out, LinkContract.RC_UNKNOWN, "不认识的函数 " + method);
        if (LinkContract.M_HELLO.equals(method)) {
            String ver = "";
            try { ver = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception ignored) { }
            for (Map.Entry<String, Object> e : LinkContract.hello(ver).entrySet()) {
                Object v = e.getValue();
                if (v instanceof Integer) out.putInt(e.getKey(), (Integer) v);
                else out.putString(e.getKey(), String.valueOf(v));
            }
            out.putBoolean("client_ok", LinkContract.clientOk(x.getInt(LinkContract.K_CLIENT_VERSION, 0)));
            L.i(c, "LINK hello from v" + x.getInt(LinkContract.K_CLIENT_VERSION, 0));
            return ok(out);
        }
        if (LinkContract.F_GET_BRIEF_STATUS.equals(method)) {
            String s = read(new File(c.getFilesDir(), "state.json"));
            if (s == null || !LinkContract.looksLikeState(s)) return code(out, LinkContract.RC_NOT_FOUND, "还没有播报状态");
            out.putString(LinkContract.K_DATA, s);
            L.i(c, "LINK getBriefStatus " + s.length() + "B");
            return ok(out);
        }
        String action = LinkContract.alarmAction(method);
        AlarmApiReceiver.Reply r = AlarmApiReceiver.handle(c, action, x.getInt(LinkContract.X_ID, -1), x.getInt(LinkContract.X_HOUR, -1),
                x.getInt(LinkContract.X_MINUTE, -1), x.getInt(LinkContract.X_DAYS, 0), x.getBoolean(LinkContract.X_ON, false),
                x.getString(LinkContract.X_LABEL), "LINK");
        out.putInt(LinkContract.K_CODE, r.code);
        out.putBoolean(LinkContract.K_OK, r.code == LinkContract.RC_OK);
        out.putString(LinkContract.K_DATA, r.data);
        return out;
    }

    static Bundle ok(Bundle out) {
        out.putInt(LinkContract.K_CODE, LinkContract.RC_OK);
        out.putBoolean(LinkContract.K_OK, true);
        return out;
    }

    static Bundle code(Bundle out, int code, String err) {
        out.putInt(LinkContract.K_CODE, code);
        out.putBoolean(LinkContract.K_OK, false);
        out.putString(LinkContract.K_ERROR, err);
        return out;
    }

    static String read(File f) {
        if (!f.exists() || f.length() > 64 * 1024) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                return b.toString("UTF-8");
            } finally { in.close(); }
        } catch (Exception e) {
            return null;
        }
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { enforce(); return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException("只支持 call()"); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException("只支持 call()"); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException("只支持 call()"); }
}
