package com.onesay.assistant.v2.router;

/**
 * 路由器统一契约接口。
 */
public interface IRouter {
    Invocation route(String query);
}
