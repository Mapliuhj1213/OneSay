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
 * 改动声明（OneSay，2026-11-02）：本文件改写自 phongent
 * app/src/main/java/com/wangzan101/mika/remote/RemoteScreen.kt 的 input()（gesture / back / home，0..1 归一化坐标，
 * duration 50–1500 ms）与 RemoteBridge.kt 的 /video 分包循环（Kotlin → Java 7）。
 * 改动：画面 / 输入经 {@link ScreenSource} / {@link InputSink} 交给宿主；本机同意走 {@link Approver#askScreen}；
 * text 输入不支持（上游靠无障碍 ACTION_SET_TEXT，OneSay 执行端不开无障碍）。
 */
package com.onesay.assistant.plug.phongent.core;

import org.json.JSONObject;

import java.io.DataOutputStream;
import java.io.IOException;

/** [phongent] 实时画面 + 接管：本机同意闸、观看者计数、输入换算。纯 Java。 */
public final class PgScreen {

    private final ScreenSource mSource;
    private final InputSink mInput;
    private final Approver mApprover;
    private final Clock mClock;
    final PgVideo.Gate gate = new PgVideo.Gate();

    public PgScreen(ScreenSource source, InputSink input, Approver approver, Clock clock) {
        mSource = source;
        mInput = input;
        mApprover = approver;
        mClock = clock;
    }

    public JSONObject status() {
        return gate.status(mClock.elapsed());
    }

    public void start(String controllerName) {
        long now = mClock.elapsed();
        if (gate.active(now) || gate.pending()) return;
        gate.request(now);
        mApprover.askScreen(controllerName);
    }

    public void decide(boolean allow) {
        if (allow) {
            int[] s = mSource.streamSize();
            gate.size(s[0], s[1]);
        }
        gate.decide(allow, mClock.elapsed());
        mApprover.dismissScreen();
    }

    public void stop(String why) {
        gate.stop(why);
        mApprover.dismissScreen();
    }

    /** 已同意时推流到断开；没同意 / 观看者满返回 false。 */
    public boolean serve(final DataOutputStream out) throws IOException {
        if (!gate.join(mClock.elapsed())) return false;
        try {
            mSource.stream(new ScreenSource.PacketSink() {
                @Override public boolean open() {
                    return gate.active(mClock.elapsed());
                }

                @Override public void size(int width, int height) {
                    gate.size(width, height);
                }

                @Override public void packet(byte[] au) throws IOException {
                    PgVideo.packet(out, au);
                    gate.touch(mClock.elapsed());
                }
            });
            return true;
        } finally {
            gate.leave(mClock.elapsed());
        }
    }

    public void input(JSONObject d) throws Exception {
        if (!gate.active(mClock.elapsed())) throw new IllegalStateException("screen_stale");
        if (mInput == null) throw new IllegalStateException("input_unavailable");
        String action = d.getString("action");
        boolean ok;
        if ("gesture".equals(action)) {
            int[] size = mSource.screenSize();
            int x = PgVideo.coord(d.getDouble("x"), size[0]), y = PgVideo.coord(d.getDouble("y"), size[1]);
            int ex = PgVideo.coord(d.optDouble("endX", d.getDouble("x")), size[0]);
            int ey = PgVideo.coord(d.optDouble("endY", d.getDouble("y")), size[1]);
            int ms = (int) Math.max(50, Math.min(1500, d.optLong("duration", 100)));
            ok = x == ex && y == ey ? mInput.tap(x, y) : mInput.swipe(x, y, ex, ey, ms);
        } else if ("back".equals(action)) {
            ok = mInput.key(InputSink.KEY_BACK);
        } else if ("home".equals(action)) {
            ok = mInput.key(InputSink.KEY_HOME);
        } else if ("text".equals(action)) {
            throw new IllegalStateException("text_input_unsupported");
        } else {
            throw new IllegalStateException("unsupported_action");
        }
        if (!ok) throw new IllegalStateException("input_rejected");
        gate.touch(mClock.elapsed());
    }
}
