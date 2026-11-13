/*
 * Copyright 2026 OneSay
 * Portions derived from VYRSE (https://github.com/felixthosharon/vyrse, reference commit 41c5651),
 * Copyright (c) 2026 VYRSE contributors, MIT License (full text: THIRD_PARTY_NOTICES.md).
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); see LICENSE.
 *
 * 改动声明（OneSay，2026-11-08）：VYRSE 手机端 VyrseLinkService.java / PhoneActions.java / PhoneControl.java
 * 与电脑端 desktop/src/main/phone-bridge.ts 的协议常量、动作语义、结果格式按原样对齐，代码为 Java 7 重写；
 * 只实现动作白名单，完整操控（tap / type …）不实现。
 */
package com.onesay.assistant.plug.vyrse.core;

/**
 * [vyrse 兼容 core] 响铃找手机（语义同 VYRSE PhoneActions.find）：记下闹钟音量 → 拉满 → 循环播放闹钟铃声 + 振动 →
 * 30 秒后自动停并<b>恢复原音量</b>。再次 start 先停掉上一轮（音量按第一次记下的恢复，不会把「拉满」当成原音量）。
 * stop 随时可调。线程安全。
 */
public final class RingController {

    public static final long AUTO_STOP_MS = 30000L;

    public interface AlarmAudio {
        int alarmVolume();

        int maxAlarmVolume();

        void setAlarmVolume(int v);

        /** 用 USAGE_ALARM 循环播放闹钟铃声；失败抛异常。 */
        void startAlarmSound() throws Exception;

        void stopAlarmSound();

        void vibrate(boolean on);
    }

    public interface Scheduler {
        Object schedule(Runnable r, long delayMs);

        void cancel(Object handle);
    }

    private final AlarmAudio mAudio;
    private final Scheduler mSched;
    private int mRestore = -1;
    private Object mTimer;
    private boolean mRinging;

    public RingController(AlarmAudio audio, Scheduler sched) {
        mAudio = audio;
        mSched = sched;
    }

    public synchronized boolean ringing() {
        return mRinging;
    }

    /** @return 自动停止的秒数 */
    public synchronized int start() throws Exception {
        int restore = mRinging ? mRestore : mAudio.alarmVolume();
        stopLocked(false);
        mRestore = restore;
        mAudio.setAlarmVolume(mAudio.maxAlarmVolume());
        try {
            mAudio.startAlarmSound();
        } catch (Exception e) {
            mAudio.setAlarmVolume(mRestore);
            mRestore = -1;
            throw e;
        }
        mRinging = true;
        try {
            mAudio.vibrate(true);
        } catch (Throwable ignored) {
        }
        mTimer = mSched.schedule(new Runnable() {
            @Override public void run() {
                stop();
            }
        }, AUTO_STOP_MS);
        return (int) (AUTO_STOP_MS / 1000);
    }

    public synchronized void stop() {
        stopLocked(true);
    }

    private void stopLocked(boolean restore) {
        if (mTimer != null) {
            try {
                mSched.cancel(mTimer);
            } catch (Throwable ignored) {
            }
            mTimer = null;
        }
        if (mRinging) {
            try {
                mAudio.stopAlarmSound();
            } catch (Throwable ignored) {
            }
            try {
                mAudio.vibrate(false);
            } catch (Throwable ignored) {
            }
        }
        mRinging = false;
        if (restore && mRestore >= 0) {
            mAudio.setAlarmVolume(mRestore);
            mRestore = -1;
        }
    }
}
