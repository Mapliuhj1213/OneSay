package com.onesay.assistant.v2.quicknote;

import com.onesay.assistant.v2.s2.MemoryEntry;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * [quicknote] 记一记的写 / 查 / 删（纯 Java）。★ R10 起存在独立库 quicknote.db（{@link QuickNoteStore}），
 * 删除改为软删（「最近删除」保留 {@link #KEEP_DELETED_MS} = 30 天，D12）。
 * S2 的「我记了哪些 / 我刚记的是什么 / 清空记忆」通过 {@link #s2View()} 仍看得到记一记（D10）。
 */
public final class QuickNoteBook {

    public static final String SUBJECT = "记一记";
    public static final String RELATION = "quicknote";
    public static final long KEEP_DELETED_MS = 30L * 24L * 3600L * 1000L;

    private final QuickNoteStore mStore;
    private final TimeZone mTz;

    public QuickNoteBook(QuickNoteStore store, TimeZone tz) {
        mStore = store;
        mTz = tz == null ? TimeZone.getDefault() : tz;
    }

    public QuickNoteStore store() {
        return mStore;
    }

    /** 分类结果 → 条目（不入库）。 */
    public QuickNoteEntry toEntry(QuickNoteClassifier.Note n, long now, String source) {
        QuickNoteEntry e = new QuickNoteEntry();
        e.createdAt = now;
        e.updatedAt = now;
        e.day = day(now);
        e.source = source == null ? QuickNoteEntry.SRC_LONG : source;
        e.category = n.label();
        e.tags = new ArrayList<String>(n.tags);
        e.title = n.title;
        e.summary = n.summary;
        e.body = n.body == null ? "" : n.body;
        e.entities = QuickNoteEntry.entsJson(n.ents);
        e.appPkg = n.app == null ? "" : n.app;
        e.codeKind = n.codeKind;
        e.codeValue = n.codeValue;
        e.amountFen = n.amountFen;
        e.eventAt = n.eventAt;
        return e;
    }

    /** 存一条。拒收的返回 -1。 */
    public long save(QuickNoteClassifier.Note n, long now) {
        return save(n, now, QuickNoteEntry.SRC_LONG);
    }

    public long save(QuickNoteClassifier.Note n, long now, String source) {
        if (n == null || !n.saved()) return -1;
        return mStore.insert(toEntry(n, now, source));
    }

    String day(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(mTz);
        return f.format(new Date(t));
    }

    /** 记一记的条目（不含已删），新的在前。 */
    public List<QuickNoteEntry> list() {
        return mStore.list(false);
    }

    public List<QuickNoteEntry> deletedList() {
        return mStore.list(true);
    }

    /** 最新一条（跳过时间在未来超过 1 分钟的：验收假时钟写的残留）。 */
    public QuickNoteEntry latest(long now) {
        List<QuickNoteEntry> l = list();
        if (l.isEmpty()) return null;
        for (QuickNoteEntry e : l) if (e.createdAt <= now + 60000L) return e;
        return l.get(0);
    }

    public QuickNoteEntry latest() {
        return latest(Long.MAX_VALUE / 2);
    }

    public String recallReply(long now) {
        QuickNoteEntry e = latest(now);
        if (e == null) return "你还没用过记一记。长按 Bixby 键（或说「记一记」）就会把当前屏幕记下来。";
        return when(e.createdAt, now) + "记一记的：" + e.summary;
    }

    public String listReply(long now, int max) {
        List<QuickNoteEntry> l = list();
        if (l.isEmpty()) return "记一记里还没有东西。";
        StringBuilder sb = new StringBuilder("记一记共 ").append(l.size()).append(" 条");
        for (int i = 0; i < Math.min(max, l.size()); i++) {
            QuickNoteEntry e = l.get(i);
            sb.append("\n").append(i + 1).append(". ").append(when(e.createdAt, now)).append(" ").append(clip(e.summary, 40));
        }
        if (l.size() > max) sb.append("\n（全部在 App 控制台「记一记」里）");
        return sb.toString();
    }

    /** 「我记了哪些取件码」：按大类列（类别名 / 「码」/ 标签）。 */
    public String categoryReply(String cat, long now, int max) {
        List<QuickNoteEntry> hit = new ArrayList<QuickNoteEntry>();
        for (QuickNoteEntry e : list()) if (QuickNoteSearch.inCategory(e, cat)) hit.add(e);
        if (hit.isEmpty()) return "记一记里没有" + cat + "。";
        StringBuilder sb = new StringBuilder("记一记里的").append(cat).append("共 ").append(hit.size()).append(" 条");
        for (int i = 0; i < Math.min(max, hit.size()); i++) {
            QuickNoteEntry e = hit.get(i);
            sb.append("\n").append(i + 1).append(". ").append(when(e.createdAt, now)).append(" ").append(clip(e.summary, 40))
                    .append(codeStateText(e));
        }
        return sb.toString();
    }

    public static String codeStateText(QuickNoteEntry e) {
        if (!e.hasCode()) return "";
        switch (e.codeState) {
            case QuickNoteEntry.CODE_ON_ISLAND: return "（在灵动岛上）";
            case QuickNoteEntry.CODE_TAKEN: return "（已取）";
            case QuickNoteEntry.CODE_EXPIRED: return "（已过期）";
            default: return "";
        }
    }

    /** 删掉最近一条（软删，可在「最近删除」恢复）。 */
    public String deleteLastReply() {
        QuickNoteEntry e = latest();
        if (e == null) return "记一记里没有可删的。";
        mStore.softDelete(e.id, System.currentTimeMillis());
        return "已删掉最近一条记一记：" + clip(e.summary, 30) + "（30 天内可在「最近删除」里恢复）";
    }

    /** 按 id 软删一条（只删未删的）。 */
    public boolean deleteById(long id, long now) {
        return mStore.softDelete(id, now);
    }

    public void deleteById(long id) {
        deleteById(id, System.currentTimeMillis());
    }

    /** 清掉超过 30 天的「最近删除」，返回被清掉的条目。 */
    public List<QuickNoteEntry> purge(long now) {
        return mStore.purgeDeletedBefore(now - KEEP_DELETED_MS);
    }

    /** 给 S2 看的视图（D10）：每条记一记映射成一条 S2 笔记（id 取负，避免与 S2 自己的 id 撞）。 */
    public List<MemoryEntry> s2View() {
        List<MemoryEntry> out = new ArrayList<MemoryEntry>();
        for (QuickNoteEntry q : list()) {
            MemoryEntry e = new MemoryEntry();
            e.id = -q.id;
            e.kind = MemoryEntry.KIND_NOTE;
            e.subject = SUBJECT;
            e.relation = RELATION;
            e.value = q.category;
            e.content = q.summary;
            e.createdAt = q.createdAt;
            e.day = q.day;
            out.add(e);
        }
        return out;
    }

    public String when(long t, long now) {
        long d = now - t;
        if (d >= 0 && d < 60000L) return "刚刚";
        if (d >= 0 && d < 3600000L) return (d / 60000L) + " 分钟前";
        Calendar a = Calendar.getInstance(mTz);
        a.setTimeInMillis(now);
        Calendar b = Calendar.getInstance(mTz);
        b.setTimeInMillis(t);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.CHINA);
        hm.setTimeZone(mTz);
        if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)) return "今天 " + hm.format(new Date(t)) + " ";
        a.add(Calendar.DAY_OF_YEAR, -1);
        if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)) return "昨天 " + hm.format(new Date(t)) + " ";
        SimpleDateFormat f = new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA);
        f.setTimeZone(mTz);
        return f.format(new Date(t)) + " ";
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
