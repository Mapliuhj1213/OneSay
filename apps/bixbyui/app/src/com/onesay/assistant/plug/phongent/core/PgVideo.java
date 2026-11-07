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
 * app/src/main/java/com/wangzan101/mika/remote/VirtualVideo.kt（RGB→YUV420 换算、4 字节大端长度 + Annex B 的分包格式）
 * 与 VideoCapture.kt（/video/status 字段、30 秒没人看自动停、最多 2 个观看者）（Kotlin → Java 7）。
 * 改动：画面来源换成 OneSay 的 root 读屏助手截图（Android 8 没有 MediaProjection 免弹窗的路子，也没有虚拟屏），
 * 帧率降到 ≤ 5 fps；本机同意改成「本次会话允许」的通知（不走 MediaProjection 授权框）；换算 / 分包 / 观看闸拆成纯 Java。
 */
package com.onesay.assistant.plug.phongent.core;

import org.json.JSONObject;

import java.io.DataOutputStream;
import java.io.IOException;

/** [phongent] 实时画面的纯逻辑：分包、颜色换算、本机同意与观看者闸。 */
public final class PgVideo {

    /** 一次同意管多久（没人看 30 秒自动停，与上游一致；同意本身最长 10 分钟，到点要重新同意）。 */
    public static final long IDLE_STOP_MS = 30000L;
    public static final long CONSENT_MAX_MS = 10L * 60L * 1000L;
    public static final int MAX_VIEWERS = 2;

    private PgVideo() {}

    /** 写一包：4 字节大端长度 + 数据（iOS RemoteVideo / VideoReceiver 按这个拆）。 */
    public static void packet(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

    /** ARGB → YUV420（planar = I420，否则 NV12），BT.601 有限范围，公式与上游一致。w、h 须为偶数。 */
    public static void argbToYuv420(int[] argb, int w, int h, byte[] yuv, boolean planar) {
        int frame = w * h;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = argb[y * w + x];
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                yuv[y * w + x] = (byte) clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
                if ((y & 1) == 0 && (x & 1) == 0) {
                    int u = clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                    int v = clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
                    int n = (y / 2) * (w / 2) + x / 2;
                    if (planar) {
                        yuv[frame + n] = (byte) u;
                        yuv[frame + frame / 4 + n] = (byte) v;
                    } else {
                        yuv[frame + n * 2] = (byte) u;
                        yuv[frame + n * 2 + 1] = (byte) v;
                    }
                }
            }
        }
    }

    static int clamp(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    /** 把屏幕尺寸缩到长边 ≤ maxLong、两边取偶数。 */
    public static int[] scaled(int w, int h, int maxLong) {
        double s = Math.min(1.0, (double) maxLong / Math.max(w, h));
        int sw = ((int) (w * s)) / 2 * 2, sh = ((int) (h * s)) / 2 * 2;
        return new int[]{Math.max(2, sw), Math.max(2, sh)};
    }

    /** 主控传来的 0..1 归一化坐标 → 像素（上游 coord()）。 */
    public static int coord(double v, int max) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0.0 || v > 1.0) throw new IllegalArgumentException("bad_coordinate");
        return (int) Math.round(v * (max - 1));
    }

    /**
     * 本机同意 + 观看者闸。状态：没请求 → 等本机同意 → 已同意（active）→（没人看 30 秒 / 10 分钟到 / 本机停）→ 关。
     */
    public static final class Gate {
        private long consentUntil;
        private long lastViewer;
        private int viewers;
        private boolean pending;
        private String error = "";
        private int width, height;

        public synchronized void request(long now) {
            if (active(now)) return;
            pending = true;
            error = "";
        }

        public synchronized boolean pending() {
            return pending;
        }

        public synchronized void decide(boolean allow, long now) {
            if (!pending) return;
            pending = false;
            if (allow) {
                consentUntil = now + CONSENT_MAX_MS;
                lastViewer = now;
            } else {
                error = "屏幕共享未获允许";
            }
        }

        public synchronized void stop(String why) {
            consentUntil = 0;
            pending = false;
            if (why != null) error = why;
        }

        public synchronized boolean active(long now) {
            if (consentUntil == 0) return false;
            if (now > consentUntil || viewers == 0 && now - lastViewer > IDLE_STOP_MS) {
                consentUntil = 0;
                return false;
            }
            return true;
        }

        public synchronized boolean join(long now) {
            if (!active(now) || viewers >= MAX_VIEWERS) return false;
            viewers++;
            lastViewer = now;
            return true;
        }

        public synchronized void touch(long now) {
            lastViewer = now;
        }

        public synchronized void leave(long now) {
            if (viewers > 0) viewers--;
            lastViewer = now;
        }

        public synchronized void size(int w, int h) {
            width = w;
            height = h;
        }

        public synchronized JSONObject status(long now) {
            JSONObject o = new JSONObject();
            boolean a = active(now);
            try {
                o.put("active", a);
                o.put("width", a ? width : 0);
                o.put("height", a ? height : 0);
                o.put("error", a ? "" : pending ? "请在安卓上允许查看屏幕" : error);
            } catch (Exception ignored) {
            }
            return o;
        }
    }
}
