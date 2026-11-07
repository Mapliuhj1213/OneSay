/*
 * Copyright 2026 wangzan101 (Mika) and mylea (phongent, https://github.com/mylea1/phongent)
 * Modifications Copyright 2026 OneSay
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * 改动声明（OneSay，2026-11-02）：mDNS 注册部分改写自 phongent
 * app/src/main/java/com/wangzan101/mika/remote/LanService.kt（Kotlin → Java 7）：服务类型 _mika-agent._tcp.、
 * TXT id / name / transport=tcp-http-v1、注册失败指数退避重试（≤ 60 秒）、前台服务承载生命周期。
 * 改动：固定端口（默认 18790，Tailscale 手动配对要稳定端口）；允许 / 拒绝按钮直接发给本服务（不导出），不注册接收器；
 * 服务只在 OneSay 总开关开着时被启动。
 */
package com.onesay.assistant.v2.phongent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.onesay.assistant.plug.phongent.core.PhongentPlug;

import java.net.InetAddress;

/**
 * [phongent 适配层] 执行端前台服务。<b>只在总开关开着时启动</b>（{@link #sync}）；onDestroy 撤掉端口、mDNS、通知来源。
 */
public final class PhongentService extends Service {

    static final String TAG = "Phongent";
    static final String ACT_PAIR_ALLOW = "com.onesay.assistant.phongent.PAIR_ALLOW";
    static final String ACT_PAIR_DENY = "com.onesay.assistant.phongent.PAIR_DENY";
    static final String ACT_SCREEN_ALLOW = "com.onesay.assistant.phongent.SCREEN_ALLOW";
    static final String ACT_SCREEN_DENY = "com.onesay.assistant.phongent.SCREEN_DENY";
    static final String ACT_SCREEN_STOP = "com.onesay.assistant.phongent.SCREEN_STOP";
    static final String CH_SERVICE = "bixby_phongent_service";
    static final int NID_SERVICE = 9200;

    private static volatile PhongentPlug sPlug;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private NsdManager.RegistrationListener mReg;
    private int mRegAttempts;
    private volatile boolean mDestroyed;

    /** 当前运行中的插件（控制台读状态 / 撤销用）；没运行 = null。 */
    public static PhongentPlug plug() {
        return sPlug;
    }

    /** 按总开关起 / 停服务。任意线程。 */
    public static void sync(Context ctx) {
        Context app = ctx.getApplicationContext();
        Intent it = new Intent(app, PhongentService.class);
        try {
            if (PgSettings.enabled(app)) {
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(it);
                else app.startService(it);
            } else if (sPlug != null) {
                app.stopService(it);
            }
        } catch (Throwable t) {
            Log.w(TAG, "[PG] 起停服务失败: " + t.getMessage());
        }
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressWarnings("deprecation")
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH_SERVICE, "多设备执行端", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CH_SERVICE);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, com.onesay.assistant.v2.ui.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startForeground(NID_SERVICE, b.setSmallIcon(getApplicationInfo().icon).setOngoing(true)
                .setContentTitle("多设备执行端已开启").setContentText("已授权的主控可以连接；发消息 / 付款仍在本机确认")
                .setContentIntent(PendingIntent.getActivity(this, 9204, open, PendingIntent.FLAG_UPDATE_CURRENT)).build());
        if (!PgSettings.enabled(this)) {
            stopSelf();
            return;
        }
        final Context app = getApplicationContext();
        com.onesay.assistant.v2.util.Threads.start("pg-start", new Runnable() {
            @Override public void run() {
                PgOneSayHost host = new PgOneSayHost(app);
                PhongentPlug plug = new PhongentPlug(host, host, null, host, host, host, host, new PgNotices(),
                        new PgScreenSource(app), host);
                try {
                    final int port = plug.start(InetAddress.getByName("0.0.0.0"), PgSettings.port(app));
                    if (mDestroyed) {
                        plug.stop();
                        return;
                    }
                    sPlug = plug;
                    mMain.post(new Runnable() {
                        @Override public void run() {
                            register(port);
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "[PG] 监听失败（端口被占？）: " + t.getMessage());
                    mMain.post(new Runnable() {
                        @Override public void run() {
                            stopSelf();
                        }
                    });
                }
            }
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent == null ? null : intent.getAction();
        PhongentPlug p = sPlug;
        if (act != null && p != null) {
            String id = intent.getStringExtra("id");
            if (ACT_PAIR_ALLOW.equals(act)) p.decidePair(id, true);
            else if (ACT_PAIR_DENY.equals(act)) p.decidePair(id, false);
            else if (ACT_SCREEN_ALLOW.equals(act)) p.decideScreen(true);
            else if (ACT_SCREEN_DENY.equals(act)) p.decideScreen(false);
            else if (ACT_SCREEN_STOP.equals(act)) p.stopScreen();
        }
        return START_STICKY;
    }

    private void register(final int port) {
        if (mDestroyed) return;
        final NsdManager nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        String id = PgSettings.lanId(this);
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("Mika-" + id.substring(0, 8));
        info.setServiceType(PhongentPlug.SERVICE_TYPE);
        info.setPort(port);
        info.setAttribute("id", id);
        info.setAttribute("name", PgSettings.deviceName(this));
        info.setAttribute("transport", PhongentPlug.TRANSPORT);
        info.setAttribute("executor", "bixbyui");
        mReg = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo s) {
                mRegAttempts = 0;
                Log.i(TAG, "[PG] mDNS 已注册 " + s.getServiceName());
            }

            @Override public void onRegistrationFailed(NsdServiceInfo s, int err) {
                Log.w(TAG, "[PG] mDNS 注册失败 " + err + "，端口照常开着，稍后重试");
                retry(port);
            }

            @Override public void onServiceUnregistered(NsdServiceInfo s) {
            }

            @Override public void onUnregistrationFailed(NsdServiceInfo s, int err) {
            }
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, mReg);
        } catch (Throwable t) {
            retry(port);
        }
    }

    private void retry(final int port) {
        if (mDestroyed) return;
        mRegAttempts = Math.min(6, mRegAttempts + 1);
        mMain.postDelayed(new Runnable() {
            @Override public void run() {
                unregister();
                register(port);
            }
        }, Math.min(60000L, 1000L << mRegAttempts));
    }

    private void unregister() {
        if (mReg == null) return;
        try {
            ((NsdManager) getSystemService(Context.NSD_SERVICE)).unregisterService(mReg);
        } catch (Throwable ignored) {
        }
        mReg = null;
    }

    @Override public void onDestroy() {
        mDestroyed = true;
        mMain.removeCallbacksAndMessages(null);
        unregister();
        PhongentPlug p = sPlug;
        sPlug = null;
        if (p != null) p.stop();
        super.onDestroy();
    }
}
