package com.onesay.assistant.v2.quicknote;

import java.util.List;

/**
 * [quicknote] R10：记一记的存储接口（生产 = {@link SqliteQuickNoteStore}，独立库 quicknote.db；JVM = {@link InMemoryQuickNoteStore}）。
 * 不碰 S2 的 s2_memory.db 版本号（回装旧包不会因降级崩溃）。
 */
public interface QuickNoteStore {

    /** 新增，返回 id（失败 -1）。 */
    long insert(QuickNoteEntry e);

    QuickNoteEntry get(long id);

    /** deleted=false：正常条目；true：「最近删除」。都按 created_at 新的在前。 */
    List<QuickNoteEntry> list(boolean deleted);

    boolean update(QuickNoteEntry e);

    /** 软删除（进「最近删除」）。 */
    boolean softDelete(long id, long now);

    boolean restore(long id);

    /** 彻底删除一条。 */
    boolean deleteForever(long id);

    /** 清掉删除时间早于 cutoff 的「最近删除」条目，返回被清掉的条目（调用方顺手删缩略图）。 */
    List<QuickNoteEntry> purgeDeletedBefore(long cutoff);

    /** 全清（S2「清空记忆」联动）。 */
    int clearAll();

    int count(boolean deleted);
}
