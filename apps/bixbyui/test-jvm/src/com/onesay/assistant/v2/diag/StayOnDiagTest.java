package com.onesay.assistant.v2.diag;

import com.onesay.assistant.v2.router.TriageDecider;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** ★ 11-04 C：「我的手机怎么一直亮屏」→ screen_stays_on；读「充电时保持唤醒」/ 开发者选项 / 亮屏锁；修复关掉并可撤销。 */
public class StayOnDiagTest {

    static final long T0 = 1_760_000_000_000L;
    DiagUndoStore store;

    @Before public void setUp() {
        store = new DiagUndoStore();
    }

    static List<String> ids(List<DiagEngine.Chip> chips) {
        List<String> l = new ArrayList<String>();
        for (DiagEngine.Chip c : chips) l.add(c.fixId);
        return l;
    }

    @Test public void phraseMatches() {
        for (String q : new String[]{"我的手机怎么一直亮屏", "手机屏幕一直亮着不熄", "为什么屏幕不熄屏", "充电的时候屏幕一直亮", "屏幕常亮怎么回事"}) {
            DiagMatcher.Match m = TriageDecider.diagnoseMatch(q);
            assertNotNull(q, m);
            assertEquals(q, "screen_stays_on", m.symptom.id);
        }
        DiagMatcher.Match direct = DiagMatcher.match("我的手机怎么一直亮屏", "我的手机怎么一直亮屏", false);
        assertNotNull(direct);
        assertEquals("screen_stays_on", direct.symptom.id);
        // 不抢别的症状
        assertEquals("pocket_screen_on", TriageDecider.diagnoseMatch("为什么口袋里屏幕总亮").symptom.id);
        assertNotEquals("screen_stays_on", TriageDecider.diagnoseMatch("屏幕老是自己熄灭").symptom.id);
    }

    @Test public void stayOnSevenIsDiagnosed() {
        DiagKnowledge.Symptom sy = TriageDecider.diagnoseMatch("我的手机怎么一直亮屏").symptom;
        assertTrue(sy.keyIds.contains("global/stay_on_while_plugged_in"));
        assertTrue(sy.keyIds.contains("global/development_settings_enabled"));
        assertTrue(sy.keyIds.contains("probe/screen_wakelocks"));
        Map<String, String> snap = DiagEngine.snap("global/stay_on_while_plugged_in", "7", "global/development_settings_enabled", "1",
                "system/screen_off_timeout", "300000", "system/intelligent_sleep_mode", "0", "probe/screen_wakelocks", "0",
                "probe/screen_wakelock_tag", null, "system/screen_brightness_mode", "1");
        DiagEngine.Report r = DiagEngine.diagnose(sy, snap);
        assertTrue(r.text, r.text.contains("充电时保持唤醒") && r.text.contains("交流电 / USB / 无线充电"));
        assertEquals("只出关掉保持唤醒的修复", "stay_on_off", ids(r.chips).get(0));
        assertTrue(r.chips.get(0).summary, r.chips.get(0).summary.contains("关"));
    }

    @Test public void stayOnOffLooksElsewhere() {
        DiagKnowledge.Symptom sy = TriageDecider.diagnoseMatch("我的手机怎么一直亮屏").symptom;
        Map<String, String> snap = DiagEngine.snap("global/stay_on_while_plugged_in", "0", "global/development_settings_enabled", "0",
                "system/screen_off_timeout", "1800000", "system/intelligent_sleep_mode", "0", "probe/screen_wakelocks", "2",
                "probe/screen_wakelock_tag", "WindowManager", "system/screen_brightness_mode", "1");
        DiagEngine.Report r = DiagEngine.diagnose(sy, snap);
        assertFalse(ids(r.chips).contains("stay_on_off"));
        assertTrue(r.text, r.text.contains("屏幕超时") && r.text.contains("亮屏锁 2 个"));
        assertTrue(ids(r.chips).contains("timeout_2m"));
    }

    @Test public void fixWritesZeroAndUndoRestoresSeven() {
        assertNull(DiagKnowledge.checkWrite("global", "stay_on_while_plugged_in", "0"));
        FakeSettingsShell sh = new FakeSettingsShell().put("global/stay_on_while_plugged_in", "7");
        DiagEngine.ExecOutcome x = DiagEngine.executeFix("stay_on_off", sh, DiagEngineTest.CAPS, store, T0);
        assertTrue(x.toString(), x.ok && x.verified);
        assertEquals("0", sh.settings.get("global/stay_on_while_plugged_in"));
        DiagEngine.ExecOutcome u = DiagEngine.undo(null, sh, DiagEngineTest.CAPS, store, T0 + 60_000);
        assertTrue(u.toString(), u.ok);
        assertEquals("7", sh.settings.get("global/stay_on_while_plugged_in"));
    }

    @Test public void stayOnHumanFormat() {
        assertEquals("关", DiagSnapshot.stayOn(0.0, "0"));
        assertEquals("开（USB充电时不熄屏）", DiagSnapshot.stayOn(2.0, "2"));
        assertEquals("开（交流电 / USB / 无线充电充电时不熄屏）", DiagSnapshot.stayOn(7.0, "7"));
    }
}
