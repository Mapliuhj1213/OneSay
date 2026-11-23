package com.onesay.desktop;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** ★ 1123-dui：B1 已配对但不在线不再说「没配对过」；B2 通知分类标签不被 .app 整页样式拉满。 */
public class Dui1123Test {

    static String html() throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/resources/onesay/ui/index.html")), StandardCharsets.UTF_8);
    }

    @Test public void b1_pairedButAway_notNever() {
        LanServer.Seen s = new LanServer.Seen();
        long now = 10_000_000L;
        OfflineWhy.Why w = OfflineWhy.of(true, "", false, "", OfflineWhy.pairedBefore("", "dev1=AB:CD", s), s, now);
        assertEquals(OfflineWhy.PAIRED_AWAY, w.code);
        assertTrue(w.text.startsWith("已配对，手机当前不在线"));
        assertFalse(w.text.contains("还没有手机连过"));
    }

    @Test public void b1_pairedLongAgo_inThisRun_notNever() {
        LanServer.Seen s = new LanServer.Seen();
        s.paired("SM-N950N", 1000L);   // 早就配对过，「刚配对好」已过期
        OfflineWhy.Why w = OfflineWhy.of(true, "", false, "", s, 1000L + 24L * 3600_000L);
        assertEquals(OfflineWhy.PAIRED_AWAY, w.code);
    }

    @Test public void b1_neverPaired_stillNever() {
        LanServer.Seen s = new LanServer.Seen();
        assertFalse(OfflineWhy.pairedBefore("", "", s));
        assertEquals(OfflineWhy.NEVER, OfflineWhy.of(true, "", false, "", false, s, 5000L).code);
    }

    @Test public void b1_ui_usesPairedFlag() throws Exception {
        String h = html();
        assertTrue(h.contains("S.phone_paired?'已配对，手机当前不在线':'等待手机连接'"));
        assertTrue(h.contains("$('pgo').textContent=on||S.phone_paired?'':'配对'"));
    }

    @Test public void b2_appClassScopedToRootOnly() throws Exception {
        String h = html();
        assertTrue(h.contains("div.app{display:flex;height:100vh}"));
        assertFalse("整页 .app 样式不能再命中通知标签 .nk.app", h.replace("div.app{", "").contains("\n.app{"));
        assertTrue(h.contains(".nk{font-size:11.5px;padding:3px 8px;border-radius:10px;flex:none;font-weight:600;display:inline-block;height:auto;align-self:center"));
    }
}
