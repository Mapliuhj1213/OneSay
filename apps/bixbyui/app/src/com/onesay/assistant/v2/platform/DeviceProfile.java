package com.onesay.assistant.v2.platform;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * 一次设备能力探测的结果（纯 Java，JVM 可测；refactor/oss 2026-11-06）。
 *
 * <p>不可变：用 {@link Builder} 构造。能力没探到的一律是 {@link Tri#UNKNOWN}。
 * 可序列化成 JSON 存进 prefs（{@link DeviceCapabilityProbe#PREFS}），下次打开控制台先画缓存。
 */
public final class DeviceProfile {

    public final String manufacturer;
    public final String brand;
    public final String model;
    public final int sdkInt;
    /** 首选 ABI（Build.SUPPORTED_ABIS[0]），可空。 */
    public final String abi;
    /** 探测时间（毫秒），0 = 未知。 */
    public final long probedAtMs;
    private final Map<DeviceCap, Tri> mCaps;

    private DeviceProfile(Builder b) {
        manufacturer = b.manufacturer == null ? "" : b.manufacturer;
        brand = b.brand == null ? "" : b.brand;
        model = b.model == null ? "" : b.model;
        sdkInt = b.sdkInt;
        abi = b.abi;
        probedAtMs = b.probedAtMs;
        mCaps = Collections.unmodifiableMap(new EnumMap<DeviceCap, Tri>(b.caps));
    }

    /** 某项能力的探测结果（没探的是 UNKNOWN）。 */
    public Tri get(DeviceCap c) {
        Tri t = mCaps.get(c);
        return t == null ? Tri.UNKNOWN : t;
    }

    /** 全部已记录的能力（只读）。 */
    public Map<DeviceCap, Tri> caps() {
        return mCaps;
    }

    /** 厂商是否魅族（manufacturer / brand 为 meizu）。 */
    public boolean isMeizu() {
        return isVendor("meizu");
    }

    private boolean isVendor(String v) {
        return v.equalsIgnoreCase(manufacturer.trim()) || v.equalsIgnoreCase(brand.trim());
    }

    /** 一行摘要（开发者页 / 诊断用）：「Google Pixel 8 · API 34 · arm64-v8a」。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(manufacturer.length() > 0 ? manufacturer : "?").append(' ').append(model.length() > 0 ? model : "?");
        sb.append(" · API ").append(sdkInt);
        if (abi != null && abi.length() > 0) sb.append(" · ").append(abi);
        return sb.toString();
    }

    // ------------------------------------------------------------------ JSON

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("manufacturer", manufacturer);
            o.put("brand", brand);
            o.put("model", model);
            o.put("sdk", sdkInt);
            if (abi != null) o.put("abi", abi);
            o.put("at", probedAtMs);
            JSONObject c = new JSONObject();
            for (Map.Entry<DeviceCap, Tri> e : mCaps.entrySet()) c.put(e.getKey().name(), e.getValue().name());
            o.put("caps", c);
        } catch (JSONException ignored) {
            // put 只在 key 为 null / 数值非法时抛，这里不会发生
        }
        return o;
    }

    /** 解析 {@link #toJson()} 的输出；坏数据返回 null。不认识的能力名 / 取值忽略（版本升级兼容）。 */
    public static DeviceProfile fromJson(String json) {
        if (json == null || json.length() == 0) return null;
        try {
            JSONObject o = new JSONObject(json);
            Builder b = new Builder()
                    .manufacturer(o.optString("manufacturer", ""))
                    .brand(o.optString("brand", ""))
                    .model(o.optString("model", ""))
                    .sdkInt(o.optInt("sdk", 0))
                    .abi(o.has("abi") ? o.optString("abi", null) : null)
                    .probedAtMs(o.optLong("at", 0L));
            JSONObject c = o.optJSONObject("caps");
            if (c != null) {
                Iterator<String> it = c.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    try {
                        b.set(DeviceCap.valueOf(k), Tri.valueOf(c.optString(k, "UNKNOWN")));
                    } catch (IllegalArgumentException ignored) {
                        // 旧版本 / 新版本的能力名，跳过
                    }
                }
            }
            return b.build();
        } catch (JSONException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return summary() + " " + mCaps;
    }

    // ------------------------------------------------------------------ Builder

    public static final class Builder {
        String manufacturer = "", brand = "", model = "", abi;
        int sdkInt;
        long probedAtMs;
        final Map<DeviceCap, Tri> caps = new EnumMap<DeviceCap, Tri>(DeviceCap.class);

        public Builder manufacturer(String v) { manufacturer = v; return this; }
        public Builder brand(String v) { brand = v; return this; }
        public Builder model(String v) { model = v; return this; }
        public Builder sdkInt(int v) { sdkInt = v; return this; }
        public Builder abi(String v) { abi = v; return this; }
        public Builder probedAtMs(long v) { probedAtMs = v; return this; }

        public Builder set(DeviceCap c, Tri t) {
            if (c != null) caps.put(c, t == null ? Tri.UNKNOWN : t);
            return this;
        }

        /**
         * 由厂商 / API / ABI 推得出的能力（{@link DeviceCap#API_26}、{@link DeviceCap#API_30}、{@link DeviceCap#API_34}、
         * {@link DeviceCap#ARM64}）。已显式设过的不覆盖。
         */
        public Builder deriveStatic() {
            if (sdkInt > 0) {
                putIfAbsent(DeviceCap.API_26, Tri.of(sdkInt >= 26));
                putIfAbsent(DeviceCap.API_30, Tri.of(sdkInt >= 30));
                putIfAbsent(DeviceCap.API_34, Tri.of(sdkInt >= 34));
            }
            if (abi != null && abi.length() > 0) putIfAbsent(DeviceCap.ARM64, Tri.of(abi.toLowerCase(Locale.ROOT).startsWith("arm64")));
            return this;
        }

        private void putIfAbsent(DeviceCap c, Tri t) {
            if (!caps.containsKey(c)) caps.put(c, t);
        }

        public DeviceProfile build() {
            return new DeviceProfile(this);
        }
    }
}
