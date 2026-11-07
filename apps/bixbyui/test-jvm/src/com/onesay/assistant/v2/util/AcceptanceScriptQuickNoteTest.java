package com.onesay.assistant.v2.util;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/** [quicknote] 验收脚本 cmd_quicknote：用驱动真实的日志格式实跑（非交互）。 */
public class AcceptanceScriptQuickNoteTest {

    private static String script() throws Exception {
        File f = new File("acceptance/device_acceptance.sh");
        if (!f.exists()) f = new File("../acceptance/device_acceptance.sh");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static final String GOOD = ""
            + "I QuickNoteTestDriver: [QUICKNOTE-TEST] reset → enabled=true flag=true\n"
            + "I QuickNoteTestDriver: [QUICKNOTE-TEST] on → enabled=true flag=true\n"
            + "I QuickNoteTestDriver: [QUICKNOTE-TEST] classify PICKUP title=取件码 5-2-1234\n"
            + "I QuickNoteTestDriver: [QUICKNOTE-TEST] classify REFUSED title= refused=验证码\n"
            + "I QuickNoteTestDriver: [QUICKNOTE-TEST] intent RUN ← 把这个记一记\n"
            + "I QuickNote: [QUICKNOTE] source=debug app=launcher texts=9 entities=0 → OTHER saved id=3 title=屏幕 设置\n"
            + "I QuickNoteTestDriver: [QUICKNOTE-TEST] run OTHER saved=true title=屏幕 设置\n"
            + "I QuickNoteTestDriver: [QUICKNOTE-TEST] recall 刚刚记一记的：设置 / 相机\n"
            + "I QuickNote: [QUICKNOTE] source=long app=launcher → OTHER saved id=4\n"
            + "I QuickNoteTestDriver: [QUICKNOTE-TEST] gesture handled=true\n"
            + "I QuickNoteTestDriver: [QUICKNOTE-TEST] list 记一记共 2 条 1. 刚刚 设置\n"
            + "I QuickNoteTestDriver: [QUICKNOTE-TEST] off → enabled=false flag=false\n";

    @Test public void debugAllPassOnRealFormat() throws Exception {
        Map<String, String> r = AcceptanceScriptRound5Test.runModule(script(), "cmd_quicknote", "VARIANT=debug", GOOD, "");
        for (String id : new String[]{"QN-0", "QN-1", "QN-2", "QN-3", "QN-4", "QN-5", "QN-6", "QN-7", "QN-8", "QN-9", "QN-9b"}) {
            assertEquals(id + " " + r, "PASS", r.get(id));
        }
        assertEquals("MANUAL", r.get("QN-M1"));
        assertEquals("INFO", r.get("QN-env"));
    }

    @Test public void failsWhenDefaultOffOrNothingLogged() throws Exception {
        Map<String, String> r = AcceptanceScriptRound5Test.runModule(script(), "cmd_quicknote", "VARIANT=debug",
                "[QUICKNOTE-TEST] reset → enabled=false flag=false\n", "");
        assertEquals("2026-10-11 晚起默认开：reset 后仍关 = FAIL", "FAIL", r.get("QN-0"));
        assertEquals("FAIL", r.get("QN-9b"));
        assertEquals("FAIL", r.get("QN-5"));
        assertEquals("FAIL", r.get("QN-7"));
    }

    @Test public void releaseSkips() throws Exception {
        Map<String, String> r = AcceptanceScriptRound5Test.runModule(script(), "cmd_quicknote", "VARIANT=release", "", "");
        assertEquals("SKIP", r.get("QN-0"));
        assertEquals("SKIP", r.get("QN-9"));
        assertEquals("MANUAL", r.get("QN-M1"));
    }

    /** [2026-10-11] il_fresh 先看子命令自己回的 dump：失败展开 6 s 自动收起前已判定（R7 IL-12 远程慢链路 SKIP）。 */
    @Test public void ilFreshUsesCommandDumpFirst() throws Exception {
        String log = "I IslandTestDriver: [ISLAND-TEST] fail → mode=ISLAND sheet=COLLAPSED view=EXP_FAIL(TASK) status=FAIL fullscreen=false\n"
                + "I IslandTestDriver: [ISLAND-TEST] status → mode=ISLAND view=COMPACT_TASK(TASK) status=FAIL fullscreen=false\n";
        Map<String, String> r = AcceptanceScriptRound5Test.runModule(script(), "il_fresh IL-T '^EXP_FAIL' fail && record IL-T PASS ok", "VARIANT=debug", log, "");
        assertEquals(r.toString(), "PASS", r.get("IL-T"));
        // 没有子命令 dump → 退回 status（老行为）
        String st = "I IslandTestDriver: [ISLAND-TEST] status mode=ISLAND view=HIDDEN status=NONE fullscreen=false\n";
        r = AcceptanceScriptRound5Test.runModule(script(), "il_fresh IL-U '^HIDDEN' && record IL-U PASS ok", "VARIANT=debug", st, "");
        assertEquals(r.toString(), "PASS", r.get("IL-U"));
    }

    /** lc_save 按 TAGS_ALL 过滤 logcat：记一记两个 tag 必须在里面，否则真机上 QN-* 全是假 FAIL。 */
    @Test public void logTagsIncluded() throws Exception {
        String s = script();
        int a = s.indexOf("TAGS_ALL=\"");
        String tags = s.substring(a, s.indexOf('\n', a));
        assertTrue(tags, tags.contains("QuickNote:V") && tags.contains("QuickNoteTestDriver:V") && tags.contains("SecretPrefs:V"));
    }

    // ---------------------------------------------------------------- contacts（2026-10-11 晚）

    @Test public void contactsModuleRecognisesPaths() throws Exception {
        String log = "I Contacts: [CONTACTS] 预筛 via=resolver 词数=11 → 1 条 cost=12ms\n"
                + "I Contacts: [CONTACTS] phone.call 目标长度=2 → ONE 1\n"
                + "I Contacts: [CONTACTS] 选定 Contact(2字, ***8000) → 过闸（确认卡）\n"
                + "I Contacts: [CONTACTS] phone.call 目标长度=6 → NONE 0\n"
                + "I Contacts: [CONTACTS] sms.send 目标长度=2 → ONE 1\n";
        Map<String, String> r = AcceptanceScriptRound5Test.runModule(script(), "cmd_contacts", "VARIANT=release", log, "");
        // 假 adb 没有 uiautomator dump → 卡上看不到「呼叫：」= FAIL（真机 dump 才判 PASS）；其余按日志
        assertEquals(r.toString(), "FAIL", r.get("CT-1"));
        assertEquals(r.toString(), "PASS", r.get("CT-2"));
        assertEquals(r.toString(), "PASS", r.get("CT-3"));
        assertEquals("同一份日志里有 [CONTACTS] → CT-4 判 FAIL（真机每段前 lc_clear）", "FAIL", r.get("CT-4"));
        assertEquals("MANUAL", r.get("CT-M1"));
        String sc = script();
        assertTrue(sc.contains("contacts)  cmd_contacts ;;"));
        assertTrue("TAGS_ALL 含 Contacts", sc.contains("Contacts:V"));
        assertFalse("只点拒绝，从不自动点允许", sc.substring(sc.indexOf("cmd_contacts() {"), sc.indexOf("CT-M1 \"")).contains("autotap_text 允许"));
        assertFalse("红卡从不点「确认执行」", sc.substring(sc.indexOf("cmd_contacts() {"), sc.indexOf("CT-M1 \"")).contains("确认执行 ||"));
        assertTrue("红卡点取消", sc.contains("autotap_text 取消 || true"));
    }
}

