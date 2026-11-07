package com.onesay.assistant.v2.s2;

import java.util.List;

/** [s2] 习惯默认值（#12）的存储接口：使用记录 + 显式偏好 + 重置时刻。 */
public interface HabitStore {

    /** 记一次「这个意图实际用了哪个 App」。 */
    void recordUse(String group, String app, long ts);

    /** 某意图组在 sinceTs 之后的使用记录（App id），<b>新的在前</b>，最多 limit 条。 */
    List<String> recentUses(String group, long sinceTs, int limit);

    /** 用户明说的默认（「以后打车都用滴滴」）；没有返回 null。 */
    String explicitDefault(String group);

    /** app 为 null = 清除。 */
    void setExplicitDefault(String group, String app);

    /** 用户说「别默认用 X 了」的时刻：之前的使用记录不再参与推断。 */
    long resetAt(String group);

    void setResetAt(String group, long ts);

    /** 清空全部习惯数据（「忘掉所有记录」不清习惯；调试用）。 */
    void clearHabits();
}
