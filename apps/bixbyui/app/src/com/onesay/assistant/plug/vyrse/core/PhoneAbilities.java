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

import java.util.List;

/**
 * [vyrse 兼容 core] 插件要的手机能力（宿主 OneSay 实现；core 不认识 Android）。
 * 只有这些：手电筒、闹钟音频（响铃找手机）、读通知、发通知、电量、列出 / 打开桌面 App。没有点击、输入、截图。
 */
public interface PhoneAbilities {

    /** 手电筒：没有闪光灯返回 false。 */
    boolean hasFlashlight();

    /** 设手电筒；失败抛异常。 */
    void setFlashlight(boolean on) throws Exception;

    /** 当前手电筒状态（不知道 = null）。 */
    Boolean flashlightOn();

    /** 响铃用的闹钟音频端口。 */
    RingController.AlarmAudio alarmAudio();

    /** 定时器（30 秒自动停）。 */
    RingController.Scheduler scheduler();

    /** 当前通知，新的在前，最多 limit 条；没有通知使用权 = null。 */
    List<Notice> notifications(int limit);

    /** 发一条本机通知；没权限 = false。 */
    boolean postNotice(String title, String text);

    /** 电量百分比（不知道 = -1）。 */
    int batteryPercent();

    boolean charging();

    /** 桌面上能打开的 App。 */
    List<AppEntry> launcherApps();

    /** 打开 App；打不开 = false。 */
    boolean launch(String pkg);

    /** 本 App 包名（不许远程打开自己）。 */
    String selfPackage();

    /** 机型字符串（status 用）。 */
    String model();

    final class Notice {
        public final String pkg, app, title, text;
        public final long postTime;

        public Notice(String pkg, String app, String title, String text, long postTime) {
            this.pkg = pkg;
            this.app = app;
            this.title = title;
            this.text = text;
            this.postTime = postTime;
        }
    }

    final class AppEntry {
        public final String pkg, label;

        public AppEntry(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }
}
