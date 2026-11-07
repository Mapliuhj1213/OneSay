package com.onesay.assistant.v2.conn;

import org.json.JSONObject;

import java.util.List;

/**
 * [conn] 连接器统一接口。MCP 服务器（{@link com.onesay.assistant.v2.conn.mcp.McpConnector}）、Tavily 适配
 * （{@link TavilyConnector}）、Google（{@link com.onesay.assistant.v2.conn.google.GoogleConnector}）都实现它。
 *
 * <p>线程：{@link #call} / {@link #refreshTools} 可能触网，只在后台线程调；{@link #tools()} 必须廉价（只读缓存）。
 */
public interface Connector {

    String id();

    String title();

    /** 配置齐全（有地址 / 有令牌 / 已登录）。廉价。 */
    boolean isReady();

    /** 是否把工具交给 AgentLoop（Tavily 适配为 false：现有搜索链路不变）。 */
    boolean exposeToAgent();

    /** 已过白名单的工具（缓存）。廉价，不触网。 */
    List<ConnTool> tools();

    /** 触网刷新工具清单（MCP tools/list）；本地定义工具的连接器直接返回 OK。 */
    ConnResult refreshTools();

    /**
     * 执行一个工具。调用方负责门禁：READ 由 AgentLoop 门禁放行后直接调；有副作用的只经 {@link ConnBackend}
     * （UnifiedExecutor 已校验并消费确认令牌）调用。
     */
    ConnResult call(String toolName, JSONObject args);

    /** 一行状态（不含任何密钥）。 */
    String status();
}
