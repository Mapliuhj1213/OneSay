package com.onesay.assistant.v2.search;

import java.util.List;

/**
 * 联网搜索数据源抽象。
 *
 * <p>把它做成接口而不是直接写死一家，是因为搜索服务是最容易失效的一环：
 * 境外服务可能某天连不上、免费额度可能耗尽、接口可能改版。
 * 上层（{@link WebSearchClient}）只管按优先级依次问，谁的答案先到手就用谁的 ——
 * 换服务商只需要新增一个实现类 + 配置里改个名字，业务代码零改动。
 *
 * <h3>实现约定</h3>
 * <ul>
 *   <li>{@link #search} 是**阻塞**调用，调用方在后台线程使用；</li>
 *   <li>实现方**不得抛异常** —— 任何失败（断网、超时、401、解析失败）
 *       都要收敛成**空列表**。搜索失败不该让"问答"整条链路挂掉，
 *       模型拿不到资料顶多退回"如实说不知道"，而不是报错；</li>
 *   <li>{@link #isReady} 必须廉价（只读内存/SharedPreferences），不做 IO。</li>
 * </ul>
 */
public interface SearchProvider {

    /** 数据源名（日志与自测展示用，如 "tavily" / "bocha" / "bing-html"）。 */
    String name();

    /** 当前是否具备发起搜索的条件（通常是"Key 已配置"）。必须廉价。 */
    boolean isReady();

    /**
     * 执行一次搜索。
     *
     * @param query      查询词
     * @param maxResults 期望返回条数上限（实现方可自行收敛）
     * @return 结果列表；失败或无结果时返回**空列表**，永不返回 null
     */
    List<SearchResult> search(String query, int maxResults);
}
