package com.onesay.assistant.v2.quicknote;

import com.onesay.assistant.v2.s2.MemoryEntry;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * [quicknote] R10 一次性迁移（D3，纯 Java）：s2_memory.db 里 kind=note、relation=quicknote 的旧记一记 → quicknote.db。
 * 原行<b>保留</b>（回装旧包仍看得到）；幂等（按 created_at + 内容去重）。
 */
public final class QuickNoteMigration {

    private QuickNoteMigration() {}

    public static int migrate(List<MemoryEntry> s2Notes, QuickNoteStore dst, TimeZone tz) {
        if (s2Notes == null || dst == null) return 0;
        Set<String> have = new HashSet<String>();
        for (QuickNoteEntry e : dst.list(false)) have.add(e.createdAt + "|" + e.summary);
        for (QuickNoteEntry e : dst.list(true)) have.add(e.createdAt + "|" + e.summary);
        QuickNoteBook b = new QuickNoteBook(dst, tz);
        int n = 0;
        for (MemoryEntry m : s2Notes) {
            if (m == null || !MemoryEntry.KIND_NOTE.equals(m.kind) || !QuickNoteBook.RELATION.equals(m.relation)) continue;
            String key = m.createdAt + "|" + m.content;
            if (have.contains(key)) continue;
            QuickNoteEntry e = new QuickNoteEntry();
            e.createdAt = m.createdAt;
            e.updatedAt = m.createdAt;
            e.day = m.day != null && m.day.length() > 0 ? m.day : b.day(m.createdAt);
            e.source = QuickNoteEntry.SRC_MIGRATED;
            e.category = m.value == null || m.value.length() == 0 ? "屏幕" : m.value;
            e.title = QuickNoteBook.clip(m.content, 24);
            e.summary = m.content == null ? "" : m.content;
            e.tags.add(e.category);
            java.util.regex.Matcher cm = java.util.regex.Pattern.compile("^(取件码|取餐码|券码|入园码)\\s+([^\\s（(]+)").matcher(e.summary);
            if (cm.find()) {
                e.codeKind = cm.group(1);
                e.codeValue = cm.group(2);
                e.codeState = QuickNoteEntry.CODE_TAKEN;   // 旧条目不再上岛
            }
            if (dst.insert(e) > 0) {
                have.add(key);
                n++;
            }
        }
        return n;
    }
}
