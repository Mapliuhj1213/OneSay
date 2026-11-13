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

import org.json.JSONObject;

import java.io.IOException;

/**
 * [vyrse 兼容 core] 手机端配对：先 {@code GET /phone/hello} 确认对面是 VYRSE（{@code app == "vyrse"}），
 * 再 {@code POST /phone/pair {code, name}} 换取本机专属 key（电脑只存它的 SHA-256）。
 * 配对码 2 分钟有效、错 5 次作废——由电脑端执行，手机侧只做格式校验和错误翻译。
 */
public final class VyrsePairing {

    private VyrsePairing() {}

    public static final class Result {
        public final boolean ok;
        public final String base, token, deviceId, pcName, error;

        Result(boolean ok, String base, String token, String deviceId, String pcName, String error) {
            this.ok = ok;
            this.base = base;
            this.token = token;
            this.deviceId = deviceId;
            this.pcName = pcName;
            this.error = error;
        }

        static Result fail(String error) {
            return new Result(false, null, null, null, null, error);
        }

        /** 不含 token 的描述（可进日志）。 */
        @Override public String toString() {
            return ok ? "paired pc=" + pcName + " base=" + base : "fail: " + error;
        }
    }

    public static Result pair(VyrseHttp http, String address, String code, String phoneName) {
        String base = VyrseProtocol.normalizeBase(address);
        if (base == null) return Result.fail("地址不对：填电脑在家里 Wi-Fi 上的地址，例如 192.168.1.20（只支持局域网地址）");
        if (!VyrseProtocol.validCode(code)) return Result.fail("配对码是电脑上显示的 6 位数字");
        try {
            VyrseHttp.Response hello = http.send("GET", base + VyrseProtocol.PATH_HELLO, null, null, 8000);
            JSONObject h = parse(hello.body);
            if (hello.code != 200 || h == null || !"vyrse".equals(h.optString("app"))) {
                return Result.fail("这个地址上不是 VYRSE 电脑端（检查地址和端口）");
            }
            JSONObject body = new JSONObject();
            body.put("code", code.trim());
            body.put("name", VyrseProtocol.cleanName(phoneName));
            VyrseHttp.Response r = http.send("POST", base + VyrseProtocol.PATH_PAIR, null, body.toString(), 10000);
            JSONObject o = parse(r.body);
            String token = o == null ? "" : o.optString("token", "");
            if (r.code == 200 && token.length() >= 16 && token.length() <= 200) {
                String pc = o.optString("pc", h.optString("pc", "PC"));
                return new Result(true, base, token, o.optString("deviceId", ""), VyrseProtocol.clip(pc, 60), null);
            }
            return Result.fail(translate(r.code, o == null ? "" : o.optString("error", "")));
        } catch (IOException e) {
            return Result.fail("连不上电脑：确认电脑上的 VYRSE 开着、已打开手机访问，两边在同一个 Wi-Fi");
        } catch (Exception e) {
            return Result.fail("配对失败（" + e.getClass().getSimpleName() + "）");
        }
    }

    static String translate(int code, String err) {
        String e = err == null ? "" : err;
        if (e.startsWith("Wrong code")) return "配对码不对（错 5 次后要在电脑上重新点「Pair a phone」）";
        if (e.startsWith("Pairing isn")) return "电脑端没在配对（或配对码已过 2 分钟），在电脑上重新点「Pair a phone」";
        return "配对失败（HTTP " + code + "）";
    }

    static JSONObject parse(String s) {
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }
}
