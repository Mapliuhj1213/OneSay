package com.onesay.assistant.v2.suggest;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

/**
 * ★ fix/shot-1104 release 内引导授权：透明 Activity，弹系统「允许访问照片、媒体和文件」授权框（READ + WRITE_EXTERNAL_STORAGE）。
 * 入口：控制台「截图分析需要存储权限 · 去授权」行、岛上一次性提示的「去授权」按钮。
 * 结果：授予 → 立刻 {@link SuggestHost#permissionPoke()}（A 源就地注册、B 源 FileObserver 启动，不用重启服务）；
 * 拒绝且勾了「不再询问」（系统不再弹框）→ 跳到本应用的系统权限页。不导出（Manifest exported=false）。
 */
public final class StoragePermActivity extends Activity {
    static final String TAG = "Suggest";
    static final int REQ = 0x5107;
    static final String[] PERMS = {Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};
    static final String PREF_ASKED = "storage_perm_asked";

    /** 从任何 Context 打开（服务里调要 NEW_TASK）。 */
    public static void open(Context ctx) {
        try {
            Intent it = new Intent(ctx, StoragePermActivity.class);
            if (!(ctx instanceof Activity)) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "[SUGGEST] 打开授权页失败: " + t.getClass().getSimpleName());
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (com.onesay.assistant.v2.suggest.sources.MediaStoreScreenshotSource.storageGranted(this)) {
            SuggestHost.permissionPoke();
            finish();
            return;
        }
        boolean askedBefore = getSharedPreferences(SuggestPlugs.PREFS, MODE_PRIVATE).getBoolean(PREF_ASKED, false);
        if (askedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            openAppSettings();   // 以前问过、系统已不再弹框（不再询问）→ 只能去权限页
            return;
        }
        getSharedPreferences(SuggestPlugs.PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ASKED, true).apply();
        requestPermissions(PERMS, REQ);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ) {
            finish();
            return;
        }
        boolean ok = res != null && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        Log.i(TAG, "[SUGGEST] 存储权限申请结果 granted=" + ok);
        if (ok) {
            SuggestHost.permissionPoke();
            Toast.makeText(this, "已授权：截屏后岛上会问要不要分析", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (!shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            openAppSettings();
            return;
        }
        finish();
    }

    private void openAppSettings() {
        try {
            Intent it = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
            Toast.makeText(this, "在「权限」里打开「存储」，截图分析就能用了", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.w(TAG, "[SUGGEST] 打开应用权限页失败: " + t.getClass().getSimpleName());
        }
        finish();
    }
}
