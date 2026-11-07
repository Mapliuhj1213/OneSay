package com.onesay.assistant.v2.search;

/**
 * 可选接口：让调用方区分「请求成功但没有结果」和「请求失败」。
 *
 * <p>{@link SearchProvider#search} 按约定把失败也收敛成空列表；实现了本接口的数据源额外记下
 * **本线程**最近一次 search 是否失败（ThreadLocal，互不串线）。2026-10-24 r2/fixes：
 * TavilyConnector 据此把「没有结果」报成 OK + 空数据，不再说成网络不可达。
 */
public interface SearchOutcome {

    /** 本线程最近一次 {@link SearchProvider#search} 是否失败（网络 / HTTP / 解析）。没调用过返回 false。 */
    boolean lastSearchFailed();
}
