package com.onesay.assistant.v2.roles;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * [roles] 安装链的生产环境（2026-10-10）：静态探测委托 {@link AndroidDeviceSnapshot}；连通性 = HTTPS GET
 * {@link InstallLinkPlanner#CONNECTIVITY_URL} 期望 204；VPN = 任一网络带 {@code TRANSPORT_VPN}（API 21+，Note 8 为 26）；
 * 启动 App 用 {@code getLaunchIntentForPackage}。<b>全部阻塞，只能在后台线程用。</b>不需要新权限（INTERNET /
 * ACCESS_NETWORK_STATE 已有），不需要 root。
 */
public final class AndroidInstallEnv implements InstallLinkPlanner.NetEnv {

    private static final String TAG = "Roles";

    private final Context mCtx;
    private final AndroidDeviceSnapshot mSnap;

    public AndroidInstallEnv(Context ctx, AndroidDeviceSnapshot snap) {
        this.mCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        this.mSnap = snap;
        InstallLinkPlanner.setVpnPackage(mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_VPN_PKG, ""));
    }

    /** 用户设置：国外 App 装不上时可自动打开的代理 / VPN App 包名（默认空 = 不打开任何 App）。 */
    public static final String PREFS = "onesay_roles", K_VPN_PKG = "vpn_app_pkg";

    @Override public boolean isInstalled(String pkg) {
        return mSnap.isInstalled(pkg);
    }

    @Override public boolean canResolve(String uri) {
        return mSnap.canResolve(uri);
    }

    @Override public boolean connectivityOk() {
        HttpURLConnection c = null;
        long t0 = SystemClock.elapsedRealtime();
        try {
            c = (HttpURLConnection) new URL(InstallLinkPlanner.CONNECTIVITY_URL).openConnection();
            c.setConnectTimeout(InstallLinkPlanner.CONNECTIVITY_TIMEOUT_MS);
            c.setReadTimeout(InstallLinkPlanner.CONNECTIVITY_TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            int code = c.getResponseCode();
            Log.i(TAG, "[ROLES] 连通性检查 code=" + code + " 用时=" + (SystemClock.elapsedRealtime() - t0) + "ms");
            return code == 204;
        } catch (Throwable t) {
            Log.i(TAG, "[ROLES] 连通性检查失败 用时=" + (SystemClock.elapsedRealtime() - t0) + "ms: " + t.getClass().getSimpleName());
            return false;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Override public boolean vpnActive() {
        ConnectivityManager cm = (ConnectivityManager) mCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) throw new IllegalStateException("no ConnectivityManager");
        Network[] all = cm.getAllNetworks();
        if (all != null) {
            for (Network n : all) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        }
        return false;
    }

    @Override public boolean launchApp(String pkg) {
        try {
            Intent li = mCtx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (li == null) return false;
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mCtx.startActivity(li);
            Log.i(TAG, "[ROLES] 已启动 " + pkg);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "[ROLES] 启动失败 " + pkg + ": " + t.getMessage());
            return false;
        }
    }

    @Override public long nowMs() {
        return SystemClock.elapsedRealtime();
    }

    @Override public void sleepMs(long ms) {
        SystemClock.sleep(ms);
    }
}
