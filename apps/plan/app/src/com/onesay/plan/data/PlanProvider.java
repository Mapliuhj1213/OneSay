package com.onesay.plan.data;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import com.onesay.plan.core.AccessPolicy;
import com.onesay.plan.core.Contract;
import com.onesay.plan.core.RawImport;
import com.onesay.plan.core.Row;
import com.onesay.plan.core.Schema;
import com.onesay.plan.core.Today;
import com.onesay.plan.core.Validator;

import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * 后门：exported ContentProvider（每类一张表增删改查 + /today 聚合 + 票二维码文件 + call）。
 * 门卫见 {@link AccessPolicy}；字段校验见 {@link Validator}；每次写入后 notifyChange + 权限保护的变更广播 + 重排本地提醒。
 * App 自己的界面也走这里（同一套校验与广播），保证只有一个写入口。
 */
public final class PlanProvider extends ContentProvider {

    static final String TAG = "BixbyPlan";
    static final int M_TABLE = 1, M_ROW = 2, M_TODAY = 3, M_QR = 4;
    static final UriMatcher UM = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        for (String t : Contract.TABLES) {
            UM.addURI(Contract.AUTHORITY, t, M_TABLE);
            UM.addURI(Contract.AUTHORITY, t + "/#", M_ROW);
        }
        UM.addURI(Contract.AUTHORITY, Contract.P_TODAY, M_TODAY);
        UM.addURI(Contract.AUTHORITY, Contract.T_TICKETS + "/#/qr", M_QR);
    }

    @Override public boolean onCreate() {
        return true;
    }

    // ------------------------------------------------------------------ 门卫

    static final class Caller {
        final AccessPolicy.Who who;
        final String pkg;

        Caller(AccessPolicy.Who who, String pkg) { this.who = who; this.pkg = pkg; }

        String source() {
            return AccessPolicy.source(who, pkg);
        }
    }

    Caller enforce() {
        Context c = getContext();
        int uid = Binder.getCallingUid();
        boolean perm = c.checkCallingOrSelfPermission(Contract.PERMISSION) == PackageManager.PERMISSION_GRANTED;
        String pkg = null;
        boolean same = false;
        if (uid >= 10000) {
            String[] pk = c.getPackageManager().getPackagesForUid(uid);
            if (pk != null && pk.length > 0) pkg = pk[0];
            if (!perm && uid != Process.myUid()) {
                try { same = c.getPackageManager().checkSignatures(Process.myUid(), uid) == PackageManager.SIGNATURE_MATCH; }
                catch (Exception e) { same = false; }
            }
        }
        AccessPolicy.Who w = AccessPolicy.decide(uid, Process.myUid(), perm, same);
        if (!AccessPolicy.allowed(w)) {
            Log.w(TAG, "[DOOR] 拒绝 uid=" + uid + " pkg=" + pkg);
            throw new SecurityException("计划 App：调用方没有权限（需要 " + Contract.PERMISSION + "）");
        }
        if (w != AccessPolicy.Who.SELF) Log.i(TAG, "[DOOR] " + w + " uid=" + uid + " pkg=" + pkg);
        return new Caller(w, pkg);
    }

    @SuppressWarnings("deprecation")
    static String[] certs(Context c, String pkg) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            if (pi.signatures == null) return null;
            String[] out = new String[pi.signatures.length];
            for (int i = 0; i < out.length; i++) out[i] = sha256(pi.signatures[i]);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    static String sha256(Signature s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray());
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    // ------------------------------------------------------------------ 查询

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        enforce();
        int m = UM.match(uri);
        if (m == M_TODAY) return today();
        if (m != M_TABLE && m != M_ROW) throw new IllegalArgumentException("不认识的地址: " + uri);
        String table = uri.getPathSegments().get(0);
        Schema.Table t = Schema.table(table);
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(table);
        qb.setStrict(true);
        java.util.HashMap<String, String> pm = new java.util.HashMap<String, String>();
        for (String c : t.columnNames()) pm.put(c, c);
        qb.setProjectionMap(pm);
        if (m == M_ROW) qb.appendWhere(Contract.C_ID + "=" + ContentUris.parseId(uri));
        Cursor c = qb.query(db(), projection, selection, args, null, null, sort == null ? defaultSort(table) : sort);
        c.setNotificationUri(getContext().getContentResolver(), uri);
        return c;
    }

    static String defaultSort(String table) {
        if (Contract.T_EVENTS.equals(table)) return "start_at ASC";
        if (Contract.T_TICKETS.equals(table)) return "depart_at ASC";
        if (Contract.T_RUNS.equals(table) || Contract.T_SHORTCUT_RUNS.equals(table)) return "started_at DESC";
        return "_id ASC";
    }

    Cursor today() {
        Context c = getContext();
        Today.Data d = Store.today(c, System.currentTimeMillis());
        MatrixCursor mc = new MatrixCursor(Today.COLUMNS);
        for (Today.Line l : d.all()) mc.addRow(l.toArray());
        mc.setNotificationUri(c.getContentResolver(), Uri.parse(Contract.BASE_URI));
        return mc;
    }

    @Override public String getType(Uri uri) {
        int m = UM.match(uri);
        if (m == M_QR) return "image/*";
        if (m == M_ROW) return "vnd.android.cursor.item/vnd.bixbyplan." + uri.getPathSegments().get(0);
        if (m == M_TABLE) return "vnd.android.cursor.dir/vnd.bixbyplan." + uri.getPathSegments().get(0);
        if (m == M_TODAY) return "vnd.android.cursor.dir/vnd.bixbyplan.today";
        return null;
    }

    // ------------------------------------------------------------------ 写

    @Override public Uri insert(Uri uri, ContentValues cv) {
        Caller who = enforce();
        if (UM.match(uri) != M_TABLE) throw new IllegalArgumentException("只能往表地址插入: " + uri);
        String table = uri.getPathSegments().get(0);
        Row in = Store.fromValues(cv);
        if (!in.containsKey(Contract.C_SOURCE)) in.put(Contract.C_SOURCE, who.source());
        long id = Store.insert(getContext(), table, in, false, who.pkg);
        return ContentUris.withAppendedId(Uri.parse(Contract.BASE_URI + "/" + table), id);
    }

    @Override public int update(Uri uri, ContentValues cv, String selection, String[] args) {
        Caller who = enforce();
        int m = UM.match(uri);
        if (m != M_TABLE && m != M_ROW) throw new IllegalArgumentException("不认识的地址: " + uri);
        String table = uri.getPathSegments().get(0);
        List<Long> ids = m == M_ROW ? single(ContentUris.parseId(uri)) : Store.ids(getContext(), table, selection, args);
        return Store.update(getContext(), table, ids, Store.fromValues(cv), who.pkg);
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        Caller who = enforce();
        int m = UM.match(uri);
        if (m != M_TABLE && m != M_ROW) throw new IllegalArgumentException("不认识的地址: " + uri);
        String table = uri.getPathSegments().get(0);
        if (m == M_TABLE && (selection == null || selection.trim().length() == 0))
            throw new IllegalArgumentException("整表删除不允许：请给 _id 或条件");
        List<Long> ids = m == M_ROW ? single(ContentUris.parseId(uri)) : Store.ids(getContext(), table, selection, args);
        return Store.delete(getContext(), table, ids, who.pkg);
    }

    static List<Long> single(long id) {
        List<Long> l = new ArrayList<Long>();
        l.add(id);
        return l;
    }

    // ------------------------------------------------------------------ 二维码文件

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Caller who = enforce();
        if (UM.match(uri) != M_QR) throw new FileNotFoundException("只有 tickets/<id>/qr 能读写文件");
        long id = Long.parseLong(uri.getPathSegments().get(1));
        Row t = Store.get(getContext(), Contract.T_TICKETS, id);
        if (t == null) throw new FileNotFoundException("没有这张票: " + id);
        File f = Store.qrFile(getContext(), id);
        if (mode.contains("w")) {
            f.getParentFile().mkdirs();
            Store.setQrPath(getContext(), id, f.getAbsolutePath(), who.pkg);
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_WRITE_ONLY);
        }
        if (!f.exists()) throw new FileNotFoundException("这张票没有二维码图片");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    // ------------------------------------------------------------------ call

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Caller who = enforce();
        Context c = getContext();
        Bundle out = new Bundle();
        try {
            if (Contract.M_PING.equals(method)) {
                out.putInt("schema", Contract.SCHEMA_VERSION);
                out.putString("version", Settings.versionName(c));
                out.putString("who", who.who.name());
                out.putString("today", Today.summary(Store.today(c, System.currentTimeMillis())));
                for (String t : Contract.TABLES) out.putLong("count_" + t, Store.count(c, t));
                return out;
            }
            if (Contract.M_IMPORT_BUNDLE.equals(method)) {
                return importJson(c, arg, who, out);
            }
            if (Contract.M_IMPORT_FILE.equals(method)) {
                File dir = new File(c.getFilesDir(), "import");
                String name = arg == null || arg.length() == 0 ? "bixbyui.json" : new File(arg).getName();
                File f = new File(dir, name);
                if (!f.exists()) throw new FileNotFoundException("没有导入文件 files/import/" + name);
                String json = Store.readAll(f);
                out = importJson(c, json, who, out);
                File done = new File(dir, name + ".done");
                if (!f.renameTo(done)) Log.w(TAG, "导入文件改名失败（不影响结果）");
                return out;
            }
            if (Contract.M_LOG_RUN.equals(method)) {
                if (extras == null) throw new IllegalArgumentException("log_run 需要 extras");
                Row r = new Row();
                for (String k : new String[]{"automation_id", "started_at", "ended_at", "status", "result", "trigger_info"}) {
                    if (extras.containsKey(k)) r.put(k, extras.get(k));
                }
                long id = Store.logRun(c, r, who.pkg);
                out.putLong("run_id", id);
                return out;
            }
            if (Contract.M_SHORTCUT_ACTIONS.equals(method)) {
                if (com.onesay.plan.core.Shortcuts.Actions.parse(arg == null ? "" : arg) == null) throw new IllegalArgumentException("动作清单格式不对");
                Settings.saveShortcutActions(c, arg);
                out.putBoolean("ok", true);
                return out;
            }
            throw new IllegalArgumentException("不认识的 call: " + method);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    static Bundle importJson(Context c, String json, Caller who, Bundle out) throws Exception {
        JSONObject b = new JSONObject(json == null ? "{}" : json);
        if (!Contract.BUNDLE_FORMAT.equals(b.optString("format", ""))) throw new IllegalArgumentException("导入包格式不对（要 " + Contract.BUNDLE_FORMAT + "）");
        RawImport.Plan p = RawImport.map(b, System.currentTimeMillis(), TimeZone.getDefault());
        Store.ImportResult r = Store.importPlan(c, p, who.pkg);
        out.putInt("inserted", r.inserted);
        out.putInt("duplicate", r.duplicate);
        out.putInt("rejected", r.rejected);
        out.putString("counts", p.counts());
        out.putString("detail", r.detail());
        Settings.markImported(c, r.summary());
        return out;
    }

    SQLiteDatabase db() {
        return PlanDb.get(getContext()).getWritableDatabase();
    }

}
