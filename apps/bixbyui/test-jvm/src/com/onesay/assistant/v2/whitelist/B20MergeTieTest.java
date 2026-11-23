package com.onesay.assistant.v2.whitelist;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ rel-1118 B20：完全打平（同键同时间同 origin / name / addedAt）时合并仍满足交换律：id 大小写、origin_kind 也参与比较。 */
public class B20MergeTieTest {

    static AppWhitelist.Entry e(String platform, String id, String kind) {
        return new AppWhitelist.Entry(platform, id, "名", 100L, 200L, "dev-a", kind, false);
    }

    static void antisym(AppWhitelist.Entry a, AppWhitelist.Entry b) {
        boolean ab = AppWhitelist.Entry.beats(a, b), ba = AppWhitelist.Entry.beats(b, a);
        assertTrue(a.id + "/" + a.originKind + " vs " + b.id + "/" + b.originKind, ab ^ ba);
    }

    @Test public void idCaseBreaksTie() {
        antisym(e("windows", "Notepad.exe", "pc"), e("windows", "notepad.exe", "pc"));
    }

    @Test public void originKindBreaksTie() {
        antisym(e("android", "com.tencent.mm", "pc"), e("android", "com.tencent.mm", "phone"));
    }

    @Test public void identicalNeitherBeats() {
        AppWhitelist.Entry a = e("android", "com.tencent.mm", "phone"), b = e("android", "com.tencent.mm", "phone");
        assertFalse(AppWhitelist.Entry.beats(a, b));
        assertFalse(AppWhitelist.Entry.beats(b, a));
    }

    @Test public void earlierRulesUnchanged() {
        AppWhitelist.Entry newer = new AppWhitelist.Entry("android", "a.b", "x", 1, 300, "dev-a", "a", false);
        AppWhitelist.Entry older = new AppWhitelist.Entry("android", "A.B", "z", 9, 200, "dev-z", "z", true);
        assertTrue(AppWhitelist.Entry.beats(newer, older));
        assertFalse(AppWhitelist.Entry.beats(older, newer));
    }
}
