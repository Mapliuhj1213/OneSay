package com.onesay.assistant.v2.diag;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ShellCommand;
import com.onesay.assistant.v2.router.TriageDecider;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * [diag] #9 设置诊断纯 Java 核心：知识表完整性、短语匹配、规则求值（假快照）、芯片生成、白名单、执行与撤销（假 shell）。
 */
public class DiagEngineTest {

    /** 生产同款的现有能力解析（注册表 + ShellCommand.fill）。 */
    static final DiagEngine.CapResolver CAPS = new DiagEngine.CapResolver() {
        @Override public String command(String sign, JSONObject args) {
            Capability c = CapabilityRegistry.getInstance().describe(sign);
            if (c == null || c.cmdTemplate == null) return null;
            return ShellCommand.fill(c.cmdTemplate, ShellCommand.slotValues(args, null, null));
        }
    };

    static final long T0 = 1_760_000_000_000L;
    DiagUndoStore store;

    @Before public void setUp() {
        store = new DiagUndoStore();
    }

    static DiagKnowledge.Symptom sym(String q) {
        DiagMatcher.Match m = TriageDecider.diagnoseMatch(q);
        assertNotNull("应判为症状句: " + q, m);
        return m.symptom;
    }

    // ------------------------------------------------------------------ 知识表

    @Test public void tableIsConsistent() {
        // ★ int/1102 审计：+ hotspot_no_client（热点连不上），上限 15 → 16
        // ★ 11-04 C：+ screen_stays_on（屏幕一直亮着不熄，用户实报），上限 16 → 17
        assertTrue("症状 12~17 个，实际 " + DiagKnowledge.SYMPTOMS.size(),
                DiagKnowledge.SYMPTOMS.size() >= 12 && DiagKnowledge.SYMPTOMS.size() <= 17);
        for (DiagKnowledge.Symptom s : DiagKnowledge.SYMPTOMS) {
            assertFalse(s.id, s.triggers.isEmpty());
            assertFalse(s.id, s.rules.isEmpty());
            for (String k : s.keyIds) assertTrue(s.id + " 读的键不在白名单: " + k, DiagKnowledge.isReadable(k));
            for (DiagKnowledge.Rule r : s.rules) {
                for (DiagKnowledge.Cond c : r.conds) assertTrue(s.id + " 规则键未列入读取: " + c.keyId, s.keyIds.contains(c.keyId));
                for (String f : r.fixIds) assertNotNull(s.id + " 修复不存在: " + f, DiagKnowledge.fix(f));
            }
            assertNotNull(s.id + " 兜底芯片", DiagKnowledge.fix(s.fallbackFix));
        }
        Pattern safe = Pattern.compile("^[a-z][a-z0-9_.]*$");
        for (DiagKnowledge.Key k : DiagKnowledge.KEYS.values()) {
            if (!k.isProbe()) {
                assertTrue(k.id, safe.matcher(k.ns).matches() && safe.matcher(k.key).matches());
                assertEquals(k.ns + "/" + k.key, k.id);
            } else {
                assertFalse("探针只读: " + k.id, k.probeCmd.matches(".*(settings put|settings delete|\\+|rm |> ).*"));
            }
        }
        for (DiagKnowledge.Fix f : DiagKnowledge.FIXES.values()) {
            assertTrue(f.id + " 芯片文案过长", f.label.length() <= 12);
            if (f.kind == DiagKnowledge.FixKind.SETTING) {
                DiagKnowledge.Key k = DiagKnowledge.key(f.keyId);
                assertNotNull(f.id, k);
                assertFalse(f.id + " 不能写探针", k.isProbe());
                assertNull(f.id, DiagKnowledge.checkWrite(k.ns, k.key, f.target));
            }
            if (f.capSign != null) assertNotNull(f.id + " 复用的现有能力不存在: " + f.capSign, CapabilityRegistry.getInstance().describe(f.capSign));
            if (f.prevGetSign != null) assertNotNull(f.id, CapabilityRegistry.getInstance().describe(f.prevGetSign));
        }
    }

    @Test public void existingCapabilitiesArePreferred() {
        assertEquals("settings.screentimeout.set", DiagKnowledge.fix("timeout_5m").capSign);
        assertEquals("settings.rotation.on", DiagKnowledge.fix("rotation_on").capSign);
        assertEquals("settings.autobrightness.on", DiagKnowledge.fix("autobright_on").capSign);
        assertEquals("battery.saver.on", DiagKnowledge.fix("saver_on").capSign);
        assertEquals("volume.ring.set", DiagKnowledge.fix("ring_vol_12").capSign);
        assertEquals("settings.aod.off", DiagKnowledge.fix("aod_off").capSign);
    }

    // ------------------------------------------------------------------ 短语匹配

    @Test public void phraseMatching() {
        String[][] cases = {
                {"为什么口袋里屏幕总亮", "pocket_screen_on"},
                {"屏幕老是自己熄灭", "screen_off_fast"},
                {"我看屏幕时别让它熄屏", "screen_off_fast"},
                {"手机耗电好快", "battery_drain"},
                {"通知收不到", "notification_delay"},
                {"微信消息延迟", "notification_delay"},
                {"声音太小", "volume_low"},
                {"来电没声音", "volume_low"},
                {"Wi-Fi 老断", "wifi_drops"},
                {"自动旋转不灵", "rotation"},
                {"字太小看不清", "font_small"},
                {"晚上屏幕太刺眼", "night_glare"},
                {"为什么一直振动", "vibrate_always"},
                {"勿扰是不是开着", "dnd_check"},
                {"亮度老是自己变", "brightness_jumps"},
                {"手机时间不准", "time_wrong"},
                {"导航定位老是飘", "location_bad"},
        };
        for (String[] c : cases) {
            DiagMatcher.Match m = TriageDecider.diagnoseMatch(c[0]);
            assertNotNull(c[0], m);
            assertEquals(c[0], c[1], m.id());
        }
        // 不是症状
        for (String q : new String[]{"什么是勿扰模式", "勿扰模式有什么用", "把屏幕超时改成5分钟", "怎么关掉自动旋转", "自动旋转怎么关",
                "为什么蓝牙连不上", "太亮了", "屏幕太暗了", "天太黑了屏幕刺眼帮我调低点亮度", "打开勿扰模式", "记一下手机耗电好快",
                "蓝牙开着吗", "屏幕上有什么", "人为什么要睡觉", "亮度调高一点", "关掉振动", "防误触怎么开"}) {
            assertNull(q, TriageDecider.diagnoseMatch(q));
        }
        // 未知症状：强语气 + 领域词
        DiagMatcher.Match u = TriageDecider.diagnoseMatch("为什么屏幕有时候会闪");
        assertNotNull(u);
        assertTrue(u.unknown);
        assertNull(u.symptom);
    }

    @Test public void undoPhrases() {
        for (String q : new String[]{"撤销", "改回去", "把刚才的改回去", "恢复原来的设置", "撤销刚才的设置", "还原"}) {
            assertTrue(q, DiagMatcher.isUndo(TriageDecider.normalize(q)));
        }
        for (String q : new String[]{"撤销订单", "改回去上班", "恢复出厂设置", "打开设置"}) {
            assertFalse(q, DiagMatcher.isUndo(TriageDecider.normalize(q)));
        }
    }

    // ------------------------------------------------------------------ 规则 / 芯片

    @Test public void pocketWithPocketKeyOff() {
        Map<String, String> snap = DiagEngine.snap("system/screen_off_pocket", "0", "system/aod_mode", "1",
                "system/edge_lighting", "0", "secure/wake_gesture_enabled", null);
        DiagEngine.Report r = DiagEngine.diagnose(sym("为什么口袋里屏幕总亮"), snap);
        assertEquals(2, r.findings.size());
        assertTrue(r.text, r.text.contains("口袋模式") && r.text.contains("当前：关"));
        assertTrue(r.text, r.text.contains("息屏显示（AOD）开着（当前：开）"));
        assertEquals(Arrays.asList("pocket_on", "aod_off"), ids(r.chips));
        assertTrue(r.chips.get(0).summary, r.chips.get(0).summary.contains("关 → 开") && r.chips.get(0).summary.contains("未在本机核实"));
        assertTrue(r.text.contains("撤销"));
    }

    @Test public void pocketKeyMissingFallsBackToPage() {
        Map<String, String> snap = DiagEngine.snap("system/screen_off_pocket", null, "system/aod_mode", "0",
                "system/edge_lighting", "0", "secure/wake_gesture_enabled", null);
        DiagEngine.Report r = DiagEngine.diagnose(sym("为什么口袋里屏幕总亮"), snap);
        assertEquals(Arrays.asList("page_display"), ids(r.chips));
        assertTrue(r.text, r.text.contains("没读到"));
        assertTrue(r.missing.contains("system/screen_off_pocket"));
        // 键不存在时绝不生成写它的芯片
        assertNull(DiagEngine.chip(DiagKnowledge.fix("pocket_on"), snap));
    }

    @Test public void screenOffFastCitesTimeout() {
        Map<String, String> snap = DiagEngine.snap("system/screen_off_timeout", "15000", "system/intelligent_sleep_mode", "0");
        DiagEngine.Report r = DiagEngine.diagnose(sym("屏幕老是自己熄灭"), snap);
        assertTrue(r.text, r.text.contains("屏幕超时只有 15 秒"));
        assertEquals(Arrays.asList("timeout_2m", "timeout_5m", "smart_stay_on"), ids(r.chips));
        assertEquals("15 秒", r.chips.get(1).before);
        assertEquals("5 分钟", r.chips.get(1).after);
    }

    @Test public void alreadyAtTargetNoChip() {
        Map<String, String> snap = DiagEngine.snap("system/screen_off_timeout", "300000", "system/intelligent_sleep_mode", "1");
        DiagEngine.Report r = DiagEngine.diagnose(sym("屏幕老是自己熄灭"), snap);
        assertTrue(r.findings.isEmpty());
        assertEquals("全部正常 → 兜底设置页", Arrays.asList("page_display"), ids(r.chips));
        assertTrue(r.text, r.text.contains("看起来都正常") && r.text.contains("5 分钟"));
    }

    @Test public void batteryDrainRulesAndChipCap() {
        Map<String, String> snap = DiagEngine.snap("system/screen_brightness_mode", "0", "system/screen_brightness", "230",
                "system/screen_off_timeout", "600000", "system/aod_mode", "1", "global/low_power", "0",
                "secure/location_mode", "3", "global/wifi_scan_always_enabled", "1", "probe/battery_level", "18", "probe/battery_temp", "425");
        DiagEngine.Report r = DiagEngine.diagnose(sym("手机耗电好快"), snap);
        assertTrue(r.text, r.text.contains("电量只剩 18%，省电模式却是关着的（当前：关）"));
        assertTrue(r.text, r.text.contains("230/255"));
        assertEquals(DiagEngine.MAX_CHIPS, r.chips.size());
        assertEquals(Arrays.asList("saver_on", "autobright_on", "timeout_30s"), ids(r.chips));
        assertTrue(r.findings.size() >= 5);
    }

    @Test public void notificationDelayWechat() {
        Map<String, String> snap = DiagEngine.snap("global/zen_mode", "0", "global/low_power", "0",
                "probe/wechat_installed", "1", "probe/idle_wechat", "0", "probe/restrict_bg", "disabled");
        DiagEngine.Report r = DiagEngine.diagnose(sym("微信消息延迟"), snap);
        assertEquals(1, r.findings.size());
        assertTrue(r.text, r.text.contains("微信不在电池优化白名单"));
        assertEquals(Arrays.asList("wechat_unrestrict"), ids(r.chips));
        // 没装微信 → 不给微信芯片
        snap.put("probe/wechat_installed", "0");
        assertNull(DiagEngine.chip(DiagKnowledge.fix("wechat_unrestrict"), snap));
    }

    @Test public void volumeVibrateModeOffersRing() {
        Map<String, String> snap = DiagEngine.snap("global/mode_ringer", "1", "global/zen_mode", "0",
                "system/volume_ring_speaker", "13", "system/volume_music_speaker", "9", "system/all_sound_off", null);
        DiagEngine.Report r = DiagEngine.diagnose(sym("来电没声音"), snap);
        assertTrue(r.text, r.text.contains("振动模式"));
        assertEquals(Arrays.asList("ring_vol_12"), ids(r.chips));
        assertEquals("振动模式", r.chips.get(0).before);
    }

    @Test public void dndOn() {
        Map<String, String> snap = DiagEngine.snap("global/zen_mode", "1", "global/mode_ringer", "2");
        DiagEngine.Report r = DiagEngine.diagnose(sym("勿扰是不是开着"), snap);
        assertTrue(r.text, r.text.contains("勿扰模式开着：仅优先通知"));
        assertEquals(Arrays.asList("page_notification"), ids(r.chips));
        snap.put("global/zen_mode", "0");
        r = DiagEngine.diagnose(sym("勿扰是不是开着"), snap);
        assertTrue(r.text, r.text.contains("勿扰模式是关着的（当前：关闭）"));
    }

    @Test public void fontAndRotationAndNight() {
        DiagEngine.Report f = DiagEngine.diagnose(sym("字太小看不清"), DiagEngine.snap("system/font_scale", "1.0"));
        assertEquals(Arrays.asList("font_l", "font_xl"), ids(f.chips));
        assertTrue(f.text, f.text.contains("标准（1.0）"));
        DiagEngine.Report ro = DiagEngine.diagnose(sym("自动旋转不灵"), DiagEngine.snap("system/accelerometer_rotation", "0", "system/user_rotation", "0"));
        assertEquals(Arrays.asList("rotation_on"), ids(ro.chips));
        DiagEngine.Report n = DiagEngine.diagnose(sym("晚上屏幕太刺眼"), DiagEngine.snap("system/screen_brightness_mode", "0",
                "system/screen_brightness", "200", "system/blue_light_filter", "0", "secure/night_display_activated", null));
        assertEquals(Arrays.asList("autobright_on", "bright_40", "blue_light_on"), ids(n.chips));
    }

    @Test public void unknownSymptomUsesOnlyReadKeys() {
        List<String> keys = DiagMatcher.keysForUnknown(TriageDecider.normalize("为什么屏幕有时候会闪"));
        assertFalse(keys.isEmpty());
        for (String k : keys) assertTrue(DiagKnowledge.isReadable(k));
        Map<String, String> snap = DiagEngine.snap("system/screen_brightness_mode", "1", "global/low_power", "0");
        DiagEngine.Report r = DiagEngine.diagnoseKeys("为什么屏幕有时候会闪", Arrays.asList("system/screen_brightness_mode", "global/low_power"), snap);
        assertTrue(r.unknown);
        assertTrue(r.text, r.text.contains("自动亮度开着"));
        assertTrue(ids(r.chips).contains("autobright_off"));
    }

    @Test public void llmKeySelectionIsWhitelisted() {
        List<String> k = DiagLlm.parseKeys("```json\n{\"keys\":[\"system/screen_off_timeout\",\"global/adb_enabled\",\"secure/android_id\","
                + "\"system/screen_off_timeout\",\"global/zen_mode\"]}\n```");
        assertEquals(Arrays.asList("system/screen_off_timeout", "global/zen_mode"), k);
        assertTrue(DiagLlm.parseKeys("不知道").isEmpty());
        assertTrue(DiagLlm.parseKeys("{\"keys\":\"system/font_scale\"}").isEmpty());
        StringBuilder many = new StringBuilder("{\"keys\":[");
        int i = 0;
        for (String id : DiagKnowledge.KEYS.keySet()) many.append(i++ == 0 ? "" : ",").append('"').append(id).append('"');
        many.append("]}");
        assertEquals(DiagLlm.MAX_KEYS, DiagLlm.parseKeys(many.toString()).size());
        assertTrue(DiagLlm.systemPrompt().contains("system/screen_off_timeout"));
    }

    // ------------------------------------------------------------------ 快照命令

    @Test public void snapshotCommandIsReadOnlyAndWhitelisted() {
        String cmd = DiagSnapshot.buildReadCommand(Arrays.asList("system/screen_off_timeout", "global/adb_enabled", "probe/battery_level", "x; rm -rf /"));
        assertTrue(cmd.contains("settings get system screen_off_timeout"));
        assertTrue(cmd.contains("dumpsys battery"));
        assertFalse("不在白名单的 id 被丢弃", cmd.contains("adb_enabled") || cmd.contains("rm -rf"));
        assertFalse(cmd.contains("settings put"));
        String out = "@@system/screen_off_timeout\n30000\n@@probe/battery_level\n  level: 57\n@@system/screen_off_pocket\nnull\n";
        Map<String, String> m = DiagSnapshot.parse(Arrays.asList("system/screen_off_timeout", "probe/battery_level", "system/screen_off_pocket", "global/zen_mode"), out);
        assertEquals("30000", m.get("system/screen_off_timeout"));
        assertEquals("57", m.get("probe/battery_level"));
        assertTrue(m.containsKey("system/screen_off_pocket"));
        assertNull(m.get("system/screen_off_pocket"));
        assertNull("没输出的键 = null", m.get("global/zen_mode"));
        assertEquals("5 分钟", DiagSnapshot.human("system/screen_off_timeout", "300000"));
        assertEquals("仅闹钟", DiagSnapshot.human("global/zen_mode", "3"));
        assertEquals("-82 dBm（很弱）", DiagSnapshot.human("probe/wifi_rssi", "-82"));
    }

    // ------------------------------------------------------------------ 白名单守卫

    @Test public void whitelistRejectsNonTableKeys() {
        assertNotNull(DiagKnowledge.checkWrite("global", "adb_enabled", "1"));
        assertNotNull(DiagKnowledge.checkWrite("secure", "enabled_accessibility_services", "x"));
        assertNotNull("只读键不可写", DiagKnowledge.checkWrite("system", "volume_ring_speaker", "15"));
        assertNotNull("值注入", DiagKnowledge.checkWrite("system", "screen_off_timeout", "1;reboot"));
        assertNull(DiagKnowledge.checkWrite("system", "screen_off_timeout", "300000"));
        assertFalse(DiagKnowledge.isIdlePkgAllowed("com.evil"));
        DiagEngine.ExecOutcome x = DiagEngine.executeFix("adb_on", new FakeSettingsShell(), CAPS, store, T0);
        assertFalse(x.ok);
        // 签名与参数里的修复 id 必须一致
        Capability cap = DiagCapabilities.fix(DiagKnowledge.fix("timeout_5m"), "x");
        assertEquals("timeout_5m", DiagCapabilities.fixIdOf(cap, DiagCapabilities.fixArgs("timeout_5m")));
        assertNull(DiagCapabilities.fixIdOf(cap, DiagCapabilities.fixArgs("saver_on")));
        JSONObject inj = DiagCapabilities.fixArgs("timeout_5m");
        try {
            inj.put("key", "adb_enabled");
            inj.put("value", "1");
        } catch (Exception ignored) {
        }
        assertEquals("多余参数被忽略，只认表内 id", "timeout_5m", DiagCapabilities.fixIdOf(cap, inj));
        assertNull(DiagCapabilities.fixIdOf(DiagCapabilities.fix(DiagKnowledge.fix("page_display"), "x"), DiagCapabilities.fixArgs("page_display")));
    }

    // ------------------------------------------------------------------ 执行与撤销

    @Test public void settingFixViaExistingCapAndUndo() {
        FakeSettingsShell sh = new FakeSettingsShell().put("system/screen_off_timeout", "30000");
        final java.util.List<String> used = new java.util.ArrayList<String>();
        DiagEngine.CapResolver spy = new DiagEngine.CapResolver() {
            @Override public String command(String sign, JSONObject args) {
                used.add(sign);
                return CAPS.command(sign, args);
            }
        };
        DiagEngine.ExecOutcome x = DiagEngine.executeFix("timeout_5m", sh, spy, store, T0);
        assertTrue(x.toString(), x.ok && x.verified);
        assertEquals("300000", sh.settings.get("system/screen_off_timeout"));
        assertEquals("正向写入复用现有能力 settings.screentimeout.set", java.util.Arrays.asList("settings.screentimeout.set"), used);
        assertTrue(sh.cmds.toString(), sh.anyCmdContains("settings put system screen_off_timeout 300000"));
        assertTrue(x.message, x.message.contains("30 秒 → 5 分钟") && x.message.contains("撤销"));
        assertNotNull(x.undo);
        assertEquals(1, store.size(T0));
        DiagEngine.ExecOutcome u = DiagEngine.undo(null, sh, CAPS, store, T0 + 60_000);
        assertTrue(u.toString(), u.ok);
        assertEquals("30000", sh.settings.get("system/screen_off_timeout"));
        assertTrue(u.message, u.message.contains("改回 30 秒"));
        assertEquals("撤销只能一次", 0, store.size(T0 + 60_000));
        assertFalse(DiagEngine.undo(null, sh, CAPS, store, T0 + 61_000).ok);
    }

    @Test public void undoExpiresAfterTenMinutes() {
        FakeSettingsShell sh = new FakeSettingsShell().put("system/font_scale", "1.0");
        assertTrue(DiagEngine.executeFix("font_l", sh, CAPS, store, T0).ok);
        assertEquals("1.1", sh.settings.get("system/font_scale"));
        assertNotNull(store.latest(T0 + DiagUndoStore.TTL_MS - 1));
        DiagEngine.ExecOutcome u = DiagEngine.undo(null, sh, CAPS, store, T0 + DiagUndoStore.TTL_MS + 1);
        assertFalse(u.ok);
        assertEquals("1.1", sh.settings.get("system/font_scale"));
    }

    @Test public void missingKeyIsNeverCreated() {
        FakeSettingsShell sh = new FakeSettingsShell();
        DiagEngine.ExecOutcome x = DiagEngine.executeFix("pocket_on", sh, CAPS, store, T0);
        assertFalse(x.ok);
        assertTrue(x.message, x.message.contains("这个设置项"));
        assertFalse(sh.anyCmdContains("settings put"));
        assertEquals(0, store.size(T0));
    }

    @Test public void systemRevertIsReportedHonestly() {
        FakeSettingsShell sh = new FakeSettingsShell().put("global/wifi_sleep_policy", "0");
        sh.sticky.add("global/wifi_sleep_policy");
        DiagEngine.ExecOutcome x = DiagEngine.executeFix("wifi_keep_on", sh, CAPS, store, T0);
        assertFalse(x.verified);
        assertTrue(x.message, x.message.contains("系统没有接受"));
        assertEquals("没改成就不留撤销记录", 0, store.size(T0));
    }

    @Test public void noopWhenAlreadyTarget() {
        FakeSettingsShell sh = new FakeSettingsShell().put("system/accelerometer_rotation", "1");
        DiagEngine.ExecOutcome x = DiagEngine.executeFix("rotation_on", sh, CAPS, store, T0);
        assertTrue(x.ok);
        assertFalse(sh.anyCmdContains("settings put"));
        assertEquals(0, store.size(T0));
    }

    @Test public void capFixVolumeAndUndo() {
        FakeSettingsShell sh = new FakeSettingsShell();
        sh.volume.put("2", 3);
        DiagEngine.ExecOutcome x = DiagEngine.executeFix("ring_vol_12", sh, CAPS, store, T0);
        assertTrue(x.toString(), x.ok && x.verified);
        assertEquals(Integer.valueOf(12), sh.volume.get("2"));
        assertTrue(x.message, x.message.contains("3/15 → 12/15"));
        assertTrue(DiagEngine.undo(null, sh, CAPS, store, T0 + 1000).ok);
        assertEquals(Integer.valueOf(3), sh.volume.get("2"));
    }

    @Test public void deviceIdleFixAndUndo() {
        FakeSettingsShell sh = new FakeSettingsShell();
        DiagEngine.ExecOutcome x = DiagEngine.executeFix("wechat_unrestrict", sh, CAPS, store, T0);
        assertTrue(x.toString(), x.ok && x.verified);
        assertTrue(sh.idle.contains("com.tencent.mm"));
        assertTrue(DiagEngine.undo(null, sh, CAPS, store, T0 + 1000).ok);
        assertFalse(sh.idle.contains("com.tencent.mm"));
        // 原本就在白名单：不改、不留撤销记录（撤销不会把用户自己加的移除）
        sh.idle.add("com.tencent.mm");
        assertTrue(DiagEngine.executeFix("wechat_unrestrict", sh, CAPS, store, T0).ok);
        assertEquals(0, store.size(T0));
    }

    @Test public void readFailureDoesNotWrite() {
        FakeSettingsShell sh = new FakeSettingsShell().put("system/screen_off_timeout", "30000");
        sh.down = true;
        assertFalse(DiagEngine.executeFix("timeout_5m", sh, CAPS, store, T0).ok);
        assertEquals("30000", sh.settings.get("system/screen_off_timeout"));
    }

    @Test public void endToEndOnFakeDevice() {
        // 症状句 → 快照命令 → 假 shell 应答 → 诊断 → 点芯片 → 改 → 撤销
        FakeSettingsShell sh = new FakeSettingsShell().put("system/screen_off_timeout", "15000").put("system/intelligent_sleep_mode", "0");
        DiagKnowledge.Symptom s = sym("我看屏幕时别让它熄屏");
        String cmd = DiagSnapshot.buildReadCommand(s.keyIds);
        Map<String, String> snap = DiagSnapshot.parse(s.keyIds, sh.execute(cmd).output);
        assertEquals("15000", snap.get("system/screen_off_timeout"));
        DiagEngine.Report r = DiagEngine.diagnose(s, snap);
        assertEquals("smart_stay_on", r.chips.get(2).fixId);
        assertTrue(DiagEngine.executeFix("smart_stay_on", sh, CAPS, store, T0).ok);
        assertEquals("1", sh.settings.get("system/intelligent_sleep_mode"));
        assertTrue(DiagEngine.undo(store.latest(T0).id, sh, CAPS, store, T0 + 5000).ok);
        assertEquals("0", sh.settings.get("system/intelligent_sleep_mode"));
    }

    static List<String> ids(List<DiagEngine.Chip> chips) {
        String[] a = new String[chips.size()];
        for (int i = 0; i < a.length; i++) a[i] = chips.get(i).fixId;
        return Arrays.asList(a);
    }
}
