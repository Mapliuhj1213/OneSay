package com.onesay.assistant.v2.applog;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * [settings-diag-1124] 只读分享导出的日志 zip（不导出组件；分享时用一次性读授权）。
 *
 * <p>只认 {@code files/applog/export/} 下名字形如 {@code OneSay-log-*.zip} 的文件，别的路径、写模式一律拒绝。
 * 进程启动时系统先建 Provider，借这个时机初始化应用内日志（{@link AppLogAndroid#init}）。
 */
public final class LogShareProvider extends ContentProvider {

    @Override public boolean onCreate() {
        try {
            AppLogAndroid.init(getContext());
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** 文件名白名单（纯 Java，单测）。 */
    static boolean nameOk(String name) {
        return name != null && name.matches("OneSay-log-[0-9]{8}-[0-9]{4}\\.zip");
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri == null ? null : uri.getLastPathSegment();
        if (!nameOk(name) || uri.getPathSegments().size() != 1) throw new FileNotFoundException("not allowed");
        File f = new File(AppLogAndroid.exportDir(getContext()), name);
        if (!f.isFile()) throw new FileNotFoundException("gone");
        return f;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode == null || !"r".equals(mode)) throw new FileNotFoundException("read only");
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override public String getType(Uri uri) {
        return "application/zip";
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
