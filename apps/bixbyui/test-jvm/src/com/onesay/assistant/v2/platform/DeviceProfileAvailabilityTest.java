package com.onesay.assistant.v2.platform;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * refactor/oss：已 root 的旧机画像（Android 8.0 / 9，能力齐全）下现有功能全部可用、默认值不变；
 * 缺能力（无 root、无障碍没开，如一台未 root 的 Android 14 手机）时置灰并给出原因与无 root 替代；「未确认」不置灰。
 */
public class DeviceProfileAvailabilityTest {

    static DeviceProfile note8(int sdk) {
        DeviceProfile.Builder b = new DeviceProfile.Builder().manufacturer("Generic").brand("generic").model("Phone-8")
                .sdkInt(sdk).abi("arm64-v8a").probedAtMs(1L);
        for (DeviceCap c : new DeviceCap[]{DeviceCap.ROOT, DeviceCap.EXEC_DAEMON,
                DeviceCap.MIC, DeviceCap.OVERLAY, DeviceCap.NOTIFICATION_LISTENER, DeviceCap.DEFAULT_ASSISTANT,
                DeviceCap.ACCESSIBILITY}) b.set(c, Tri.YES);
        b.set(DeviceCap.SHIZUKU, Tri.NO);
        return b.deriveStatic().build();
    }

    /** 魅族 21 Pro 式画像：Android 14、没 root、无障碍没开。 */
    static DeviceProfile meizuNoRoot() {
        DeviceProfile.Builder b = new DeviceProfile.Builder().manufacturer("Meizu").brand("meizu").model("MEIZU 21 Pro")
                .sdkInt(34).abi("arm64-v8a");
        for (DeviceCap c : new DeviceCap[]{DeviceCap.ROOT, DeviceCap.EXEC_DAEMON,
                DeviceCap.SHIZUKU, DeviceCap.ACCESSIBILITY, DeviceCap.DEFAULT_ASSISTANT}) b.set(c, Tri.NO);
        for (DeviceCap c : new DeviceCap[]{DeviceCap.MIC, DeviceCap.OVERLAY, DeviceCap.NOTIFICATION_LISTENER}) b.set(c, Tri.YES);
        return b.deriveStatic().build();
    }

    @Test public void note8AllShippedFeaturesAvailable() {
        for (int sdk : new int[]{26, 28}) {
            DeviceProfile p = note8(sdk);
            assertEquals(Tri.YES, p.get(DeviceCap.API_26));
            assertEquals(Tri.NO, p.get(DeviceCap.API_30));
            assertEquals(Tri.YES, p.get(DeviceCap.ARM64));
            for (FeatureSpec f : FeatureRegistry.all()) {
                FeatureAvailability a = FeatureAvailability.evaluate(f, p);
                if (f.status == FeatureSpec.Status.NOT_BUILT) {
                    assertFalse(f.id, a.switchEnabled());
                    continue;
                }
                assertEquals("root 旧机 API " + sdk + " 上应可用：" + f.id, FeatureAvailability.State.AVAILABLE, a.state);
                assertTrue(f.id, a.switchEnabled());
                assertNull(f.id, a.reason());
            }
        }
    }

    @Test public void note8ExistingDefaultsAllOn() {
        // 能力页只登记：有开关的现有功能默认值全部仍是「开」
        for (FeatureSpec f : FeatureRegistry.all()) if (f.hasSwitch() && !"errand.browser".equals(f.id)) assertTrue("默认值变了：" + f.id, f.def);
        assertFalse("浏览器兜底档默认关", FeatureRegistry.get("errand.browser").def);
    }

    @Test public void note8MissingGrantDoesNotGreyOut() {
        DeviceProfile.Builder b = new DeviceProfile.Builder().manufacturer("samsung").sdkInt(26).abi("arm64-v8a");
        for (DeviceCap c : DeviceCap.values()) b.set(c, c.grantable() ? Tri.NO : Tri.YES);
        b.set(DeviceCap.API_30, Tri.NO);
        DeviceProfile p = b.build();
        FeatureAvailability island = FeatureAvailability.evaluate(FeatureRegistry.get("island"), p);
        assertEquals(FeatureAvailability.State.NEEDS_GRANT, island.state);
        assertTrue("缺权限只提示，不置灰", island.switchEnabled());
        assertTrue(island.reason().contains("悬浮窗"));
    }

    @Test public void unknownNeverGreysOut() {
        DeviceProfile empty = new DeviceProfile.Builder().build();
        for (DeviceProfile p : new DeviceProfile[]{null, empty}) {
            for (FeatureSpec f : FeatureRegistry.all()) {
                FeatureAvailability a = FeatureAvailability.evaluate(f, p);
                if (f.status == FeatureSpec.Status.NOT_BUILT) continue;
                assertTrue("未探到不应置灰：" + f.id, a.switchEnabled());
                // [ci-1114] 开源版新增了不依赖任何设备能力的项（wake.cue 等，requires 为空）：没东西可探 → 直接可用；其余仍是未知
                assertEquals(f.id, f.requires.isEmpty() ? FeatureAvailability.State.AVAILABLE : FeatureAvailability.State.UNKNOWN, a.state);
            }
        }
    }

    @Test public void missingCapsGreyOutWithReasonAndAlternative() {
        DeviceProfile p = meizuNoRoot();
        assertTrue(p.isMeizu());
        FeatureAvailability key = FeatureAvailability.evaluate(FeatureRegistry.get("wake.key"), p);
        assertNotEquals("无障碍没开：按键唤醒不算可用", FeatureAvailability.State.AVAILABLE, key.state);
        assertNotNull(key.reason());
        FeatureAvailability exec = FeatureAvailability.evaluate(FeatureRegistry.get("exec.system"), p);
        assertTrue(exec.reason().contains("需要 root"));
        FeatureAvailability screen = FeatureAvailability.evaluate(FeatureRegistry.get("screen.ref"), p);
        assertFalse(screen.switchEnabled());
        assertTrue(screen.reason().contains("需要 root"));
        String alt = FeatureRegistry.get("screen.ref").alternative;
        assertTrue(alt.contains("无障碍") && alt.contains("Shizuku"));
        // [ci-1114] errand.samsung（厂商浏览器跑腿）已随去厂商化删除，改为 errand.browser（默认浏览器，无设备要求）
        assertNull(FeatureRegistry.get("errand.samsung"));
        assertTrue(FeatureAvailability.evaluate(FeatureRegistry.get("errand.browser"), p).switchEnabled());
        // 不依赖 root / 三星的照样可用
        assertTrue(FeatureAvailability.evaluate(FeatureRegistry.get("wake.kws"), p).switchEnabled());
        assertEquals(FeatureAvailability.State.AVAILABLE, FeatureAvailability.evaluate(FeatureRegistry.get("island"), p).state);
        // 未实现的 Android 11+ 项在魅族上能力齐了也照样置灰，原因以「未实现」开头
        FeatureAvailability va = FeatureAvailability.evaluate(FeatureRegistry.get("agent.vision_a11y"), p);
        assertEquals(FeatureAvailability.State.NOT_BUILT, va.state);
        assertTrue(va.reason().startsWith("未实现"));
    }

    @Test public void everyHardRequirementHasAlternative() {
        for (FeatureSpec f : FeatureRegistry.all()) {
            boolean hard = false;
            for (DeviceCap c : f.requires) hard |= !c.grantable() && c != DeviceCap.ARM64;
            if (!hard) continue;
            assertNotNull("缺替代：" + f.id, f.alternative);
            assertFalse("缺替代：" + f.id, f.alternative.trim().isEmpty() || "无".equals(f.alternative.trim()));
        }
        assertTrue(DeviceCap.ROOT.alternative.contains("无障碍") && DeviceCap.ROOT.alternative.contains("Shizuku"));
        for (DeviceCap c : DeviceCap.values()) assertTrue(c.name(), c.missingReason.length() > 0 && c.title.length() > 0);
    }

    @Test public void rootDecision() {
        assertEquals(Tri.YES, DeviceCapabilityProbe.rootFrom(Tri.YES, false));
        assertEquals(Tri.NO, DeviceCapabilityProbe.rootFrom(Tri.NO, false));
        assertEquals("守护没起但有 su → 不确定，不置灰", Tri.UNKNOWN, DeviceCapabilityProbe.rootFrom(Tri.NO, true));
        assertEquals(Tri.UNKNOWN, DeviceCapabilityProbe.rootFrom(Tri.UNKNOWN, false));
        assertEquals(Tri.UNKNOWN, DeviceCapabilityProbe.tri(null));
        assertEquals(Tri.UNKNOWN, DeviceCapabilityProbe.tri("garbage"));
        assertEquals(Tri.YES, DeviceCapabilityProbe.tri("YES"));
        assertEquals(Tri.UNKNOWN, Tri.of((Boolean) null));
    }

    @Test public void jsonRoundTripAndTolerance() {
        DeviceProfile p = note8(28);
        DeviceProfile q = DeviceProfile.fromJson(p.toJson().toString());
        assertNotNull(q);
        assertEquals(p.summary(), q.summary());
        assertEquals(p.caps(), q.caps());
        assertNull(DeviceProfile.fromJson("{bad"));
        assertNull(DeviceProfile.fromJson(null));
        DeviceProfile r = DeviceProfile.fromJson("{\"manufacturer\":\"x\",\"caps\":{\"FUTURE_CAP\":\"YES\",\"ROOT\":\"MAYBE\",\"MIC\":\"YES\"}}");
        assertEquals(Tri.UNKNOWN, r.get(DeviceCap.ROOT));
        assertEquals(Tri.YES, r.get(DeviceCap.MIC));
    }

    @Test public void deriveStaticDoesNotOverride() {
        // [ci-1114] 原用厂商能力项（去厂商化时被机械替换成 API_26，自相矛盾）；改用 deriveStatic 也会推导的 ARM64 验「显式值不被覆盖」
        DeviceProfile p = new DeviceProfile.Builder().manufacturer("Generic").sdkInt(0).abi("arm64-v8a").set(DeviceCap.ARM64, Tri.NO).deriveStatic().build();
        assertEquals("显式设的值不被 deriveStatic 覆盖", Tri.NO, p.get(DeviceCap.ARM64));
        assertEquals("sdk 未知时不推版本能力", Tri.UNKNOWN, p.get(DeviceCap.API_26));
    }

    @Test public void highAndroidVersionsHandled() {
        // 包可见性：Android 11+ 且 targetSdk ≥ 30、无 QUERY_ALL_PACKAGES → 查不到记未确认（不置灰）
        assertEquals(Tri.NO, DeviceCapabilityProbe.notFound(28, 28, false));
        assertEquals(Tri.NO, DeviceCapabilityProbe.notFound(35, 28, false));
        assertEquals(Tri.UNKNOWN, DeviceCapabilityProbe.notFound(35, 34, false));
        assertEquals(Tri.NO, DeviceCapabilityProbe.notFound(35, 34, true));
        // 运行时门槛
        assertEquals(Tri.YES, DeviceCapabilityProbe.gate(28, 33, Tri.NO));
        assertEquals(Tri.NO, DeviceCapabilityProbe.gate(34, 33, Tri.NO));
        assertEquals(Tri.UNKNOWN, DeviceCapabilityProbe.gate(36, 31, null));
        assertTrue(DeviceCap.POST_NOTIFICATIONS.grantable() && DeviceCap.EXACT_ALARM.grantable());
        // API 34 / 35 / 36 画像：版本能力推导正确；没 root 也不 root 的功能照样可用；缺授权只提示
        for (int sdk : new int[]{34, 35, 36}) {
            DeviceProfile.Builder b = new DeviceProfile.Builder().manufacturer("Google").model("Pixel").sdkInt(sdk).abi("arm64-v8a");
            b.set(DeviceCap.ROOT, Tri.NO).set(DeviceCap.EXEC_DAEMON, Tri.NO).set(DeviceCap.MIC, Tri.YES)
                    .set(DeviceCap.OVERLAY, Tri.NO).set(DeviceCap.POST_NOTIFICATIONS, Tri.NO).set(DeviceCap.EXACT_ALARM, Tri.NO);
            DeviceProfile p = b.deriveStatic().build();
            assertEquals(Tri.YES, p.get(DeviceCap.API_30));
            assertEquals(Tri.YES, p.get(DeviceCap.API_34));
            assertEquals(FeatureAvailability.State.AVAILABLE, FeatureAvailability.evaluate(FeatureRegistry.get("wake.kws"), p).state);
            assertTrue(FeatureAvailability.evaluate(FeatureRegistry.get("island"), p).switchEnabled());
            assertFalse(FeatureAvailability.evaluate(FeatureRegistry.get("screen.ref"), p).switchEnabled());
        }
        DeviceProfile n8 = note8(28);
        assertEquals(Tri.NO, n8.get(DeviceCap.API_34));
    }

    @Test public void rootOpsDaemonDelegates() {
        final String[] got = {null};
        RootOps.Daemon d = new RootOps.Daemon(new com.onesay.assistant.v2.executor.ShellRunner() {
            @Override public com.onesay.assistant.v2.executor.ShellExecutor.ShellResponse execute(String command) {
                got[0] = command;
                return null;
            }
        });
        assertEquals(RootOps.Level.ROOT, d.level());
        assertEquals(Tri.UNKNOWN, d.available());
        d.setAvailable(Tri.YES);
        assertEquals(Tri.YES, d.available());
        d.setAvailable(null);
        assertEquals(Tri.UNKNOWN, d.available());
        d.execute("echo OK");
        assertEquals("echo OK", got[0]);
    }
}
