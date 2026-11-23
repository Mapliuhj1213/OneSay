package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.util.MediaVolumeScale;
import com.onesay.plugin.exec.shizuku.ShizukuWhitelist;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * ★ meizu-1118 B6：Meizu 21 Pro 媒体流满格 60，「音量调到50%」原先按 0–15 算成 8（实际 13%）。
 * 路由、参数校验、追问话术都按 AudioManager 读到的真实满格；读不到时仍按 15（原有用例不变）。
 * 另：Shizuku 白名单放行只读 getprop ro.build.version.*（「看看安卓版本是多少」）。
 */
public class Meizu1118VolumeScaleTest {

    @After public void reset() {
        MediaVolumeScale.reset();
    }

    static int level(String q) {
        FastIntents.Hit h = FastIntents.match(q);
        assertNotNull(q, h);
        assertEquals(q, "volume.media.set", h.sign);
        return h.args.optInt("level");
    }

    static Capability cap() {
        CapabilityRegistry reg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(reg);
        return reg.describe("volume.media.set");
    }

    static boolean valid(Capability c, int lv) throws Exception {
        return c.paramsSchema.validateAndApplyDefaults(new JSONObject().put("level", lv), new StringBuilder());
    }

    @Test public void percentUsesRealMax() throws Exception {
        assertEquals("默认 15 档不变", 8, level("音量调到50%"));
        MediaVolumeScale.set(60);
        assertEquals(30, level("音量调到50%"));
        assertEquals(30, level("音量调到百分之五十"));
        assertEquals(60, level("音量调到最大"));
        assertEquals(30, level("把音量调到一半"));
        assertEquals("60 档下 30 是绝对值", 30, level("音量调到30"));
        assertEquals(18, level("音量调到百分之三十"));
    }

    @Test public void schemaFollowsRealMax() throws Exception {
        Capability c = cap();
        assertTrue(valid(c, 15));
        assertFalse("默认 15 档：16 越界", valid(c, 16));
        MediaVolumeScale.set(60);
        assertTrue(valid(c, 30));
        assertTrue(valid(c, 60));
        assertFalse(valid(c, 61));
        assertEquals(60, c.paramsSchema.toJsonSchema().getJSONObject("properties").getJSONObject("level").getLong("maximum"));
        assertEquals("静态快照值不变", Long.valueOf(15), c.paramsSchema.getRules().get("level").max);
    }

    @Test public void scaleRejectsNonsense() {
        assertEquals(15, MediaVolumeScale.set(0));
        assertEquals(15, MediaVolumeScale.set(-3));
        assertEquals(15, MediaVolumeScale.set(MediaVolumeScale.SCHEMA_CEILING + 1));
        assertEquals(25, MediaVolumeScale.set(25));
        assertEquals(25, MediaVolumeScale.init(null));
    }

    @Test public void shizukuGetpropBuildVersionOnly() {
        ShizukuWhitelist w = new ShizukuWhitelist("com.onesay.assistant.debug");
        assertTrue(w.check("getprop ro.build.version.release").allowed);
        assertTrue(w.check("getprop ro.build.version.sdk").allowed);
        assertTrue(w.check("getprop  ro.build.version.security_patch").allowed);
        assertFalse("全量 dump 不放", w.check("getprop").allowed);
        assertFalse(w.check("getprop ro.serialno").allowed);
        assertFalse(w.check("getprop persist.sys.timezone").allowed);
        assertFalse(w.check("getprop ro.build.version.release extra").allowed);
        assertFalse(w.check("getprop ro.build.version.Release").allowed);
        assertFalse(w.check("getprop ro.build.version.release;id").allowed);
        assertFalse(w.check("getprop ro.build.version.*").allowed);
        assertFalse("setprop 照旧拒", w.check("setprop ro.build.version.release 1").allowed);
    }
}
