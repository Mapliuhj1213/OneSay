package com.onesay.assistant.v2.soul;

import java.util.List;

/**
 * [soul] 人设与长期记忆的存储接口。生产实现 {@code SqliteSoulRepo}（扩展现有 s2_memory.db，不另起库），
 * JVM 单测用 {@link InMemorySoulRepo}。所有方法失败时不抛异常（返回 -1 / false / 空）。
 */
public interface SoulRepo {

    /** 三张记忆表共用一个 id 序列、从这里起号：和旧 memory 表（账目、迁移剩下的记一记行）的小 id 不重叠，S2 删除按 id 分流。 */
    long ID_BASE = 1000000000L;

    // ---- memory_core / user_profile / daily_log（同一套列） ----
    /** 写入，id 由仓库分配（三表共用序列，≥ {@link #ID_BASE}）；失败 -1。 */
    long insert(Claim c);
    boolean update(Claim c);
    Claim get(String table, long id);
    /** 全部行，id 升序。 */
    List<Claim> list(String table);
    int delete(String table, long id);
    int clear(String table);

    // ---- soul（含版本与提议） ----
    long insertSoul(SoulVersion v);
    boolean updateSoul(SoulVersion v);
    /** 全部版本，id 升序。 */
    List<SoulVersion> souls();
    int deleteSoul(long id);

    // ---- identity（name / call / vibe / emoji） ----
    String identity(String key);
    void setIdentity(String key, String value);

    // ---- ops_notes（≈AGENTS/TOOLS：设备/应用怪癖，不注入） ----
    long addOpsNote(String scope, String text, long now);
    List<String[]> opsNotes();

    // ---- forgotten（被忘内容的键哈希：整理不得再晋升回来） ----
    void addForgotten(String keyHash, long now);
    boolean isForgotten(String keyHash);

    // ---- 整理前快照（可回滚） ----
    long addSnapshot(String json, long now);
    String latestSnapshot();
    /** 「忘记」后快照里还留着旧副本 → 一并清掉（忘记要真删，研究 §6.6）。 */
    int clearSnapshots();

    // ---- 小型键值（上次整理时间等） ----
    String meta(String key);
    void setMeta(String key, String value);
}
