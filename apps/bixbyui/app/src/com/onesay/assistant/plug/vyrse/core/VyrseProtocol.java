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

import java.util.Locale;

/**
 * [vyrse 兼容 core] VYRSE 手机桥协议常量与输入校验（纯 Java 7）。协议全文：docs/COMPAT_VYRSE.md。
 *
 * <p>电脑端（phone-bridge.ts）监听 47630，明文 HTTP；手机带 {@code X-Hermes-Session-Token: <每台手机的 key>}。
 * 手机长轮询 {@code GET /phone/commands}（电脑挂住最多 25 秒），执行后 {@code POST /phone/command-result {id, result}}。
 */
public final class VyrseProtocol {

    private VyrseProtocol() {}

    public static final int PORT = 47630;
    public static final String HEADER_TOKEN = "X-Hermes-Session-Token";

    public static final String PATH_HELLO = "/phone/hello";
    public static final String PATH_PAIR = "/phone/pair";
    public static final String PATH_PING = "/phone/ping";
    public static final String PATH_COMMANDS = "/phone/commands";
    public static final String PATH_RESULT = "/phone/command-result";

    /** 手机侧：长轮询读超时（VYRSE 手机端 35 s；电脑端最多挂 25 s 后回空表）。 */
    public static final int POLL_READ_TIMEOUT_MS = 35000;
    public static final int CONNECT_TIMEOUT_MS = 5000;
    /** 手机侧：回结果读超时（VYRSE 15 s）。 */
    public static final int RESULT_TIMEOUT_MS = 15000;
    /** 失败退避 2 s → 60 s（翻倍）。 */
    public static final long BACKOFF_MIN_MS = 2000L;
    public static final long BACKOFF_MAX_MS = 60000L;

    /** 电脑端参数（只用于文档 / 测试对照）：挂起 25 s、单条命令等 20 s、每台手机队列 20 条、配对码 2 分钟 5 次。 */
    public static final int PC_POLL_HOLD_MS = 25000;
    public static final int PC_COMMAND_TIMEOUT_MS = 20000;
    public static final int PC_QUEUE_MAX = 20;
    public static final int PAIR_CODE_TTL_MS = 120000;
    public static final int PAIR_TRIES = 5;

    /** 每轮最多处理的命令（电脑端本来只排 20 条；多给的丢掉）。 */
    public static final int MAX_COMMANDS_PER_POLL = PC_QUEUE_MAX;
    public static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    /** 6 位数字配对码。 */
    public static boolean validCode(String code) {
        return code != null && code.trim().matches("[0-9]{6}");
    }

    /** VYRSE 只接受家庭网络来源；我们这边反过来也只把 key 发给家庭网络地址（RFC 1918 / 链路本地 / 回环）。 */
    public static boolean isPrivateIpv4(String ip) {
        if (ip == null) return false;
        String s = ip.trim();
        if (s.startsWith("::ffff:")) s = s.substring(7);
        String[] p = s.split("\\.", -1);
        if (p.length != 4) return false;
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!p[i].matches("[0-9]{1,3}")) return false;
            n[i] = Integer.parseInt(p[i]);
            if (n[i] > 255) return false;
        }
        if (n[0] == 127 && n[1] == 0 && n[2] == 0 && n[3] == 1) return true;
        return n[0] == 10 || (n[0] == 192 && n[1] == 168) || (n[0] == 172 && n[1] >= 16 && n[1] <= 31) || (n[0] == 169 && n[1] == 254);
    }

    /**
     * 用户填的电脑地址 → {@code http://host:port}（与 VYRSE app/src/services/phone.ts bridgeBase 同口径：
     * 「192.168.1.20」「192.168.1.20:47630」「http://192.168.1.20」都行，缺端口补 47630，路径丢掉）。
     * 只接受 http 和家庭网络 IPv4；其它（https、域名、公网地址）返回 null。
     */
    public static String normalizeBase(String address) {
        if (address == null) return null;
        String a = address.trim();
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        if (a.length() == 0 || a.length() > 100) return null;
        String lower = a.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://")) a = a.substring(7);
        else if (lower.contains("://")) return null;
        int slash = a.indexOf('/');
        if (slash >= 0) a = a.substring(0, slash);
        if (a.indexOf('@') >= 0) return null;
        String host = a;
        int port = PORT;
        int colon = a.lastIndexOf(':');
        if (colon >= 0) {
            host = a.substring(0, colon);
            String ps = a.substring(colon + 1);
            if (!ps.matches("[0-9]{1,5}")) return null;
            port = Integer.parseInt(ps);
            if (port < 1 || port > 65535) return null;
        }
        if (!isPrivateIpv4(host)) return null;
        return "http://" + host + ":" + port;
    }

    /** {@code http://host:port} 的 host（不合法返回 null）。 */
    public static String hostOf(String base) {
        if (base == null || !base.startsWith("http://")) return null;
        String a = base.substring(7);
        int c = a.lastIndexOf(':');
        return c < 0 ? a : a.substring(0, c);
    }

    /** 已保存的 base 是否仍合法（轮询前再查一次，防止 prefs 被改成公网地址）。 */
    public static boolean validBase(String base) {
        return base != null && base.equals(normalizeBase(base));
    }

    /** 电脑端显示的手机名：去控制字符和 &lt;&gt;，最长 40（与 bridge 一致）。 */
    public static String cleanName(String name) {
        String s = name == null ? "" : name.replaceAll("[\\u0000-\\u001f\\u007f<>]", "").trim();
        if (s.length() > 40) s = s.substring(0, 40);
        return s.length() == 0 ? "OneSay phone" : s;
    }

    /** 截断到 max 个字符。 */
    static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
