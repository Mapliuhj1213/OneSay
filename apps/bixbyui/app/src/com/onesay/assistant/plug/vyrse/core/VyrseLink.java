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

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * [vyrse 兼容 core] 手机端长轮询（对齐 VYRSE VyrseLinkService.poll）：
 * {@code GET /phone/commands}（读超时 35 s）→ 逐条交给 {@link VyrseDispatcher} → {@code POST /phone/command-result {id, result}}。
 * 失败退避 2 s → 60 s；401 = 电脑把这台手机移除了 → 回调 {@link Listener#onUnauthorized} 并退出（不再重试，等用户重新配对）。
 * 同一个命令 id 只执行一次（最近 200 个）。只连配对时保存的局域网地址。
 */
public final class VyrseLink implements Runnable {

    public enum State { CONNECTING, ONLINE, OFFLINE, UNAUTHORIZED, STOPPED }

    public interface Listener {
        void onState(State s);

        void onUnauthorized();

        /** 执行了一条命令（只给动作名和成败，不给参数 / 结果内容）。 */
        void onCommand(String action, boolean ok);
    }

    public interface Sleeper {
        /** @return false = 被打断（该退出了） */
        boolean sleep(long ms);
    }

    static final int SEEN_MAX = 200;
    static final long EMPTY_PAUSE_MS = 500L;

    private final VyrseHttp mHttp;
    private final String mBase, mToken;
    private final VyrseDispatcher mDispatcher;
    private final Sleeper mSleeper;
    private final Listener mListener;
    private final LinkedHashSet<String> mSeen = new LinkedHashSet<String>();
    private volatile boolean mStop;
    private volatile State mState = State.CONNECTING;
    private long mBackoff = VyrseProtocol.BACKOFF_MIN_MS;

    public static final class Unauthorized extends IOException {
        Unauthorized() {
            super("401");
        }
    }

    public VyrseLink(VyrseHttp http, String base, String token, VyrseDispatcher dispatcher, Sleeper sleeper, Listener listener) {
        if (!VyrseProtocol.validBase(base)) throw new IllegalArgumentException("not a home-network address");
        if (token == null || token.length() == 0) throw new IllegalArgumentException("no key");
        mHttp = http;
        mBase = base;
        mToken = token;
        mDispatcher = dispatcher;
        mSleeper = sleeper;
        mListener = listener;
    }

    public State state() {
        return mState;
    }

    public void stop() {
        mStop = true;
    }

    public boolean stopped() {
        return mStop;
    }

    long backoff() {
        return mBackoff;
    }

    private void set(State s) {
        if (mState == s) return;
        mState = s;
        if (mListener != null) mListener.onState(s);
    }

    /** 一轮：取命令、执行、回结果。@return 执行的命令数 */
    public int pollOnce() throws IOException {
        VyrseHttp.Response r = mHttp.send("GET", mBase + VyrseProtocol.PATH_COMMANDS, mToken, null, VyrseProtocol.POLL_READ_TIMEOUT_MS);
        if (r.code == 401) throw new Unauthorized();
        if (r.code >= 400) throw new IOException("HTTP " + r.code);
        JSONArray cmds;
        try {
            cmds = new JSONObject(r.body).optJSONArray("commands");
        } catch (Exception e) {
            throw new IOException("bad json");
        }
        set(State.ONLINE);
        int done = 0;
        for (int i = 0; cmds != null && i < cmds.length() && i < VyrseProtocol.MAX_COMMANDS_PER_POLL && !mStop; i++) {
            JSONObject c = cmds.optJSONObject(i);
            if (c == null) continue;
            String id = VyrseProtocol.clip(c.optString("id", ""), 100);
            if (id.length() == 0 || !firstTime(id)) continue;
            String action = c.optString("action", "");
            JSONObject args = c.optJSONObject("args");
            JSONObject result = mDispatcher.run(action, args == null ? new JSONObject() : args);
            boolean ok = result.optBoolean("ok", false);
            if (mListener != null) mListener.onCommand(VyrseProtocol.clip(action, 40), ok);
            JSONObject post = new JSONObject();
            try {
                post.put("id", id);
                post.put("result", result);
            } catch (Exception ignored) {
            }
            VyrseHttp.Response pr = mHttp.send("POST", mBase + VyrseProtocol.PATH_RESULT, mToken, post.toString(), VyrseProtocol.RESULT_TIMEOUT_MS);
            if (pr.code == 401) throw new Unauthorized();
            done++;
        }
        return done;
    }

    private boolean firstTime(String id) {
        synchronized (mSeen) {
            if (mSeen.contains(id)) return false;
            mSeen.add(id);
            if (mSeen.size() > SEEN_MAX) {
                Iterator<String> it = mSeen.iterator();
                it.next();
                it.remove();
            }
            return true;
        }
    }

    @Override public void run() {
        mDispatcher.setLinkOn(true);
        try {
            while (!mStop) {
                try {
                    int n = pollOnce();
                    mBackoff = VyrseProtocol.BACKOFF_MIN_MS;
                    // 空表：正常是电脑挂满 25 s 才回；对面若不挂起就立即回，这里歇半秒，避免空转
                    if (n == 0 && !mStop && !mSleeper.sleep(EMPTY_PAUSE_MS)) break;
                } catch (Unauthorized u) {
                    set(State.UNAUTHORIZED);
                    if (mListener != null) mListener.onUnauthorized();
                    return;
                } catch (Exception e) {
                    // 电脑关了 / 换了 Wi-Fi / VYRSE 没开：慢慢重试
                    set(State.OFFLINE);
                    if (mStop || !mSleeper.sleep(mBackoff)) break;
                    mBackoff = Math.min(mBackoff * 2, VyrseProtocol.BACKOFF_MAX_MS);
                }
            }
        } finally {
            mDispatcher.setLinkOn(false);
            if (mState != State.UNAUTHORIZED) set(State.STOPPED);
        }
    }
}
