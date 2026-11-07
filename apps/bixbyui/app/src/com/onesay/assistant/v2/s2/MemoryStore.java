package com.onesay.assistant.v2.s2;

import java.util.List;

/**
 * [s2] 记忆库存储接口。生产实现 {@code SqliteMemoryStore}（独立库 {@code s2_memory.db}，与技能缓存分开），
 * JVM 单测与自测用 {@link InMemoryMemoryStore}。
 */
public interface MemoryStore {

    /** 写入，返回新 id（&gt;0）；失败返回 -1。 */
    long insert(MemoryEntry e);

    /** 某类全部记录，<b>新的在前</b>（createdAt 降序，同刻按 id 降序）。kind 为 null = 全部。 */
    List<MemoryEntry> list(String kind);

    MemoryEntry get(long id);

    /** @return 实际删除条数 */
    int delete(long[] ids);

    /** kind 为 null = 全部清空。@return 删除条数 */
    int clear(String kind);

    int count(String kind);
}
