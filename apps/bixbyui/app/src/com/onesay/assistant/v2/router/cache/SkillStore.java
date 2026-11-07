package com.onesay.assistant.v2.router.cache;

import java.util.List;

/**
 * 技能缓存的存储抽象（）。
 *
 * <p>生产实现 {@link SqliteSkillStore}（Android SQLite），JVM 测试用 {@link InMemorySkillStore}。
 * 策略（写什么、何时命中、何时失效）全部在 {@link SkillCache}，存储层只做增删查。
 */
public interface SkillStore {

    SkillEntry get(String key, String grp);

    /** 插入或覆盖（主键 key+grp）。 */
    void put(SkillEntry e);

    void delete(String key, String grp);

    /** 该组里所有含文本槽的模板（数量受容量上限约束，线性扫描可接受）。 */
    List<SkillEntry> listTextTemplates(String grp);

    /** 全部条目（调试/迁移/统计用）。 */
    List<SkillEntry> listAll();

    int count();

    /** 超过 max 时按 lastHitTs（无则 createdTs）从旧到新淘汰，返回淘汰条数。 */
    int evictLru(int max);

    /** 负缓存：写入 / 读取（过期判断由调用方做）/ 删除。 */
    void putNegative(String key, String grp, String reason, long untilTs);

    /** @return {reason, untilTs} 或 null */
    Object[] getNegative(String key, String grp);

    void deleteNegative(String key, String grp);

    /** 清空全部（含负缓存），返回删除的正向条目数。 */
    int clear();
}
