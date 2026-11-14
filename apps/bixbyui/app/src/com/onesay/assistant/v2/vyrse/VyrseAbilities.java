/*
 * Copyright 2026 OneSay
 * Portions derived from VYRSE (https://github.com/felixthosharon/vyrse, reference commit 41c5651),
 * Copyright (c) 2026 VYRSE contributors, MIT License (full text: THIRD_PARTY_NOTICES.md).
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); see LICENSE.
 *
 * 改动声明（OneSay，2026-11-08）：响铃（闹钟铃声 + STREAM_ALARM 拉满 + USAGE_ALARM + 振动）、手电筒、读通知字段的做法
 * 参照 VYRSE PhoneActions.java / VyrseNotificationListener.java，Java 7 重写；读通知改用 OneSay 已有的通知监听。
 */
package com.onesay.assistant.v2.vyrse;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.service.notification.StatusBarNotification;

import com.onesay.assistant.plug.vyrse.core.PhoneAbilities;
import com.onesay.assistant.plug.vyrse.core.RingController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * [vyrse 适配层] {@link PhoneAbilities} 的 Android 实现：只碰手电筒、闹钟音频、通知、电量、桌面 App 列表。
 * 不需要无障碍、不读屏、不点击。
 */
final class VyrseAbilities implements PhoneAbilities, RingController.AlarmAudio, RingController.Scheduler {

    static final String CH_MSG = "onesay_vyrse_messages";
    private static volatile Boolean sTorch;

    private final Context mCtx;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private MediaPlayer mPlayer;

    VyrseAbilities(Context ctx) {
        mCtx = ctx.getApplicationContext();
    }

    // ------------------------------------------------------------------ 手电筒

    private String torchId() throws Exception {
        CameraManager cm = (CameraManager) mCtx.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return null;
        for (String c : cm.getCameraIdList()) {
            Boolean flash = cm.getCameraCharacteristics(c).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (Boolean.TRUE.equals(flash)) return c;
        }
        return null;
    }

    @Override public boolean hasFlashlight() {
        try {
            return torchId() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public void setFlashlight(boolean on) throws Exception {
        String id = torchId();
        if (id == null) throw new IllegalStateException("no flashlight");
        ((CameraManager) mCtx.getSystemService(Context.CAMERA_SERVICE)).setTorchMode(id, on);
        sTorch = on;
    }

    @Override public Boolean flashlightOn() {
        return sTorch;
    }

    // ------------------------------------------------------------------ 响铃

    @Override public RingController.AlarmAudio alarmAudio() {
        return this;
    }

    @Override public RingController.Scheduler scheduler() {
        return this;
    }

    private AudioManager am() {
        return (AudioManager) mCtx.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override public int alarmVolume() {
        return am().getStreamVolume(AudioManager.STREAM_ALARM);
    }

    @Override public int maxAlarmVolume() {
        return am().getStreamMaxVolume(AudioManager.STREAM_ALARM);
    }

    @Override public void setAlarmVolume(int v) {
        try {
            am().setStreamVolume(AudioManager.STREAM_ALARM, v, 0);
        } catch (SecurityException e) {
            // 勿扰模式下部分机型不让改音量：照样响（按当前闹钟音量）
        }
    }

    @Override public synchronized void startAlarmSound() throws Exception {
        stopAlarmSound();
        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            mp.setDataSource(mCtx, uri);
            mp.setLooping(true);
            mp.prepare();
            mp.start();
        } catch (Exception e) {
            mp.release();
            throw e;
        }
        mPlayer = mp;
    }

    @Override public synchronized void stopAlarmSound() {
        MediaPlayer mp = mPlayer;
        mPlayer = null;
        if (mp == null) return;
        try {
            mp.stop();
        } catch (Throwable ignored) {
        }
        mp.release();
    }

    @SuppressWarnings("deprecation")
    @Override public void vibrate(boolean on) {
        Vibrator v = (Vibrator) mCtx.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null) return;
        if (!on) {
            v.cancel();
            return;
        }
        long[] pattern = {0, 600, 400};
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, 0));
        else v.vibrate(pattern, 0);
    }

    @Override public Object schedule(Runnable r, long delayMs) {
        mMain.postDelayed(r, delayMs);
        return r;
    }

    @Override public void cancel(Object handle) {
        if (handle instanceof Runnable) mMain.removeCallbacks((Runnable) handle);
    }

    // ------------------------------------------------------------------ 通知

    @Override public List<Notice> notifications(int limit) {
        StatusBarNotification[] active = com.onesay.assistant.v2.events.EventNotificationListener.activeSnapshot();
        if (active == null) return null;
        Arrays.sort(active, new Comparator<StatusBarNotification>() {
            @Override public int compare(StatusBarNotification a, StatusBarNotification b) {
                return a.getPostTime() < b.getPostTime() ? 1 : a.getPostTime() > b.getPostTime() ? -1 : 0;
            }
        });
        PackageManager pm = mCtx.getPackageManager();
        List<Notice> out = new ArrayList<Notice>();
        for (StatusBarNotification sbn : active) {
            if (out.size() >= limit) break;
            Notification n = sbn.getNotification();
            if (n == null) continue;
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0 || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
            Bundle e = n.extras;
            if (e == null) continue;
            CharSequence title = e.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = e.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (text == null) text = e.getCharSequence(Notification.EXTRA_TEXT);
            if (title == null && text == null) continue;
            String pkg = sbn.getPackageName();
            String app = pkg;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                app = pm.getApplicationLabel(ai).toString();
            } catch (Throwable ignored) {
            }
            out.add(new Notice(pkg, app, title == null ? "" : title.toString(), text == null ? "" : text.toString(), sbn.getPostTime()));
        }
        return out;
    }

    @Override public boolean postNotice(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) mCtx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled())) return false;
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CH_MSG, "来自 VYRSE 电脑的消息", NotificationManager.IMPORTANCE_HIGH));
                b = new Notification.Builder(mCtx, CH_MSG);
            } else {
                b = new Notification.Builder(mCtx);
            }
            Intent open = new Intent(mCtx, com.onesay.assistant.v2.ui.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Notification n = b.setSmallIcon(mCtx.getApplicationInfo().icon).setContentTitle(title).setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(mCtx, 9402, open, PendingIntent.FLAG_UPDATE_CURRENT)).build();
            nm.notify(9410 + (int) (System.currentTimeMillis() % 50), n);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 电量 / App / 机型

    @Override public int batteryPercent() {
        Intent b = mCtx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return -1;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
    }

    @Override public boolean charging() {
        Intent b = mCtx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int s = b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL;
    }

    @Override public List<AppEntry> launcherApps() {
        PackageManager pm = mCtx.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<AppEntry> out = new ArrayList<AppEntry>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            if (ri.activityInfo == null) continue;
            out.add(new AppEntry(ri.activityInfo.packageName, String.valueOf(ri.loadLabel(pm))));
        }
        return out;
    }

    @Override public boolean launch(String pkg) {
        try {
            Intent open = mCtx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (open == null) return false;
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mCtx.startActivity(open);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public String selfPackage() {
        return mCtx.getPackageName();
    }

    @Override public String model() {
        return Build.MANUFACTURER + " " + Build.MODEL;
    }
}
