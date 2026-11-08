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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [vyrse 兼容 core] 电脑发来的动作 → OneSay 能力。<b>白名单</b>：
 * <ul>
 *   <li>支持：find（响铃 30 s）、flashlight、notifications、notify、battery、status、open_app（支付 / 银行 / 密码类一律拒绝）；</li>
 *   <li>VYRSE 有、本版不支持（回 {@code unsupported:true}）：media、volume 与完整操控 screen / screenshot / tap / long_press /
 *       type / swipe / key / open_url；send_file 由电脑端转成 open_url，同样不支持；</li>
 *   <li>其它一律 {@code Unknown phone action}（与 VYRSE 手机端原文一致）。</li>
 * </ul>
 * 结果格式与 VYRSE 一致：成功 {@code {ok:true, ...}}，失败 {@code {ok:false, error}}。任何异常都变成 {ok:false}，不外抛。
 */
public final class VyrseDispatcher {

    public static final Set<String> SUPPORTED = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "find", "flashlight", "notifications", "notify", "battery", "status", "open_app")));
    public static final Set<String> UNSUPPORTED = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "media", "volume", "screen", "screenshot", "tap", "long_press", "type", "swipe", "key", "open_url", "send_file")));

    static final int TITLE_MAX = 80, TEXT_MAX = 1000, NOTICE_TEXT_MAX = 300, NAME_MAX = 60;
    /** 验证码打码：通知里出现「验证码 / 校验码 / code / OTP」时把 4–8 位数字换成 ****。 */
    static final Pattern OTP_HINT = Pattern.compile("验证码|校验码|动态码|动态密码|确认码|\\bcode\\b|\\botp\\b|passcode|verification", Pattern.CASE_INSENSITIVE);
    static final Pattern DIGITS = Pattern.compile("(?<![0-9])[0-9]{4,8}(?![0-9])");

    private final PhoneAbilities mPhone;
    private final RingController mRing;
    private volatile boolean mLinkOn;

    public VyrseDispatcher(PhoneAbilities phone) {
        mPhone = phone;
        mRing = new RingController(phone.alarmAudio(), phone.scheduler());
    }

    public RingController ring() {
        return mRing;
    }

    public void setLinkOn(boolean on) {
        mLinkOn = on;
    }

    public static boolean allowed(String action) {
        return action != null && SUPPORTED.contains(action);
    }

    /** 执行一个动作。永不抛异常。 */
    public JSONObject run(String action, JSONObject args) {
        String a = action == null ? "" : action;
        JSONObject x = args == null ? new JSONObject() : args;
        try {
            if (UNSUPPORTED.contains(a)) return unsupported(a);
            if (!SUPPORTED.contains(a)) return error("Unknown phone action: " + VyrseProtocol.clip(a, 40));
            if ("find".equals(a)) return find(x.optString("state", "start"));
            if ("flashlight".equals(a)) return flashlight(x.optString("state", "toggle"));
            if ("notifications".equals(a)) return notifications(x.optInt("limit", 12));
            if ("notify".equals(a)) return notifyUser(x.optString("title", "VYRSE"), x.optString("text", ""));
            if ("battery".equals(a)) return battery();
            if ("status".equals(a)) return status();
            return openApp(x.optString("name", ""));
        } catch (Throwable t) {
            return error(t.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ 动作

    JSONObject find(String state) throws Exception {
        if ("stop".equals(state)) {
            mRing.stop();
            return ok().put("ringing", false);
        }
        if (!"start".equals(state)) return error("find state must be start or stop");
        int secs = mRing.start();
        return ok().put("ringing", true).put("seconds", secs);
    }

    JSONObject flashlight(String state) throws Exception {
        if (!"on".equals(state) && !"off".equals(state) && !"toggle".equals(state)) return error("flashlight state must be on, off or toggle");
        if (!mPhone.hasFlashlight()) return error("This phone has no flashlight.");
        boolean on = "on".equals(state) || ("toggle".equals(state) && !Boolean.TRUE.equals(mPhone.flashlightOn()));
        mPhone.setFlashlight(on);
        return ok().put("flashlight", on ? "on" : "off");
    }

    JSONObject notifications(int limit) throws Exception {
        int n = Math.max(1, Math.min(30, limit));
        List<PhoneAbilities.Notice> list = mPhone.notifications(n + 20);
        if (list == null) return error("Notification access is off. On the phone: OneSay → allow notification access.");
        JSONArray out = new JSONArray();
        long now = System.currentTimeMillis();
        for (PhoneAbilities.Notice ntc : list) {
            if (out.length() >= n) break;
            if (ntc == null || AppGuard.blocked(ntc.pkg, ntc.app, null)) continue;   // 银行 / 支付 / 密码类：不交给电脑
            if (mPhone.selfPackage() != null && mPhone.selfPackage().equals(ntc.pkg)) continue;
            String title = VyrseProtocol.clip(ntc.title, 200);
            String body = ntc.text == null ? "" : ntc.text;
            body = body.length() > NOTICE_TEXT_MAX ? body.substring(0, NOTICE_TEXT_MAX) + "…" : body;
            if (OTP_HINT.matcher(title + " " + body).find()) {
                title = mask(title);
                body = mask(body);
            }
            if (title.length() == 0 && body.length() == 0) continue;
            out.put(new JSONObject().put("app", ntc.app == null ? ntc.pkg : ntc.app).put("title", title).put("text", body)
                    .put("minutesAgo", Math.max(0, (now - ntc.postTime) / 60000)));
        }
        return ok().put("count", out.length()).put("notifications", out);
    }

    /** 通知里像验证码的数字打码（标题 + 正文一起判断）。 */
    static String redact(String s) {
        if (s == null || !OTP_HINT.matcher(s).find()) return s == null ? "" : s;
        return mask(s);
    }

    static String mask(String s) {
        Matcher m = DIGITS.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, "****");
        m.appendTail(sb);
        return sb.toString();
    }

    JSONObject notifyUser(String title, String text) throws Exception {
        String t = VyrseProtocol.clip(title.trim(), TITLE_MAX);
        if (!mPhone.postNotice(t.length() == 0 ? "VYRSE" : t, VyrseProtocol.clip(text, TEXT_MAX))) {
            return error("OneSay isn't allowed to show notifications on this phone.");
        }
        return ok().put("shown", true);
    }

    JSONObject battery() throws Exception {
        int p = mPhone.batteryPercent();
        return ok().put("percent", p < 0 ? JSONObject.NULL : (Object) p).put("charging", mPhone.charging());
    }

    JSONObject status() throws Exception {
        return ok().put("notificationAccess", mPhone.notifications(1) != null).put("link", mLinkOn)
                .put("accessibility", false).put("fullControl", false).put("model", VyrseProtocol.clip(mPhone.model(), 80))
                .put("client", "OneSay");
    }

    JSONObject openApp(String name) throws Exception {
        String want = name.trim().toLowerCase(Locale.ROOT);
        if (want.length() == 0) return error("Which app?");
        if (want.length() > NAME_MAX) return error("App name too long.");
        if (AppGuard.NAME.matcher(want).find()) return offLimits();
        PhoneAbilities.AppEntry best = null;
        int bestScore = 0;
        for (PhoneAbilities.AppEntry e : mPhone.launcherApps()) {
            if (e == null || e.label == null) continue;
            String label = e.label.toLowerCase(Locale.ROOT).trim();
            int score = label.equals(want) ? 3 : label.startsWith(want) ? 2 : label.contains(want) ? 1 : 0;
            if (score > bestScore) {
                bestScore = score;
                best = e;
            }
        }
        if (best == null) return error("No app called \"" + VyrseProtocol.clip(name, NAME_MAX) + "\" on this phone.");
        if (AppGuard.blocked(best.pkg, best.label, mPhone.selfPackage())) return offLimits();
        if (!mPhone.launch(best.pkg)) return error("That app can't be opened directly.");
        return ok().put("opened", best.label);
    }

    // ------------------------------------------------------------------ 结果

    static JSONObject offLimits() throws Exception {
        return error("That app is off limits: payments, banking, passwords and installing apps stay with you.");
    }

    static JSONObject unsupported(String action) {
        JSONObject o = error("OneSay doesn't support \"" + action + "\" yet: it only does ring (find), flashlight, notifications, notify, battery, status and open_app. Full phone control is off.");
        try {
            o.put("unsupported", true);
        } catch (Exception ignored) {
        }
        return o;
    }

    static JSONObject ok() throws Exception {
        return new JSONObject().put("ok", true);
    }

    static JSONObject error(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", message);
        } catch (Exception ignored) {
        }
        return o;
    }
}
