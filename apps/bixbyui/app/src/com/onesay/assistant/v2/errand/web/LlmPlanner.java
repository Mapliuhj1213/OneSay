package com.onesay.assistant.v2.errand.web;

import com.onesay.assistant.v2.errand.ErrandIntent;
import com.onesay.assistant.v2.net.LlmClient;
import com.onesay.assistant.v2.net.LlmResponse;

import java.util.List;

/**
 * ★ lane C3-2：用 DeepSeek（与 L2 共用 Key / 端点）做每一步规划。没有 Key → 返回 INVALID（代理会以 FAILED 收工）。
 */
public final class LlmPlanner implements BrowserAgent.Planner {

    private final LlmClient mClient;

    /** client 为 null 或没 Key → 每步都 INVALID。 */
    public LlmPlanner(LlmClient client) {
        mClient = client;
    }

    public boolean ready() {
        return mClient != null && mClient.hasKey();
    }

    @Override
    public AgentAction next(ErrandIntent intent, PageSnapshot page, List<String> history, int step, int maxSteps) {
        if (!ready()) return AgentAction.invalid("没有配置大模型 Key");
        String c = call(PlannerPrompt.system(intent), PlannerPrompt.user(intent, page, history, step, maxSteps), PlannerPrompt.MAX_TOKENS);
        return c == null ? AgentAction.invalid("模型没有返回") : AgentAction.parse(c);
    }

    @Override
    public String wrapUp(ErrandIntent intent, PageSnapshot page, List<String> history) {
        if (!ready()) return null;
        return call(PlannerPrompt.wrapSystem(), PlannerPrompt.wrapUser(intent, page, history), PlannerPrompt.WRAP_MAX_TOKENS);
    }

    private String call(String system, String user, int maxTokens) {
        try {
            LlmClient c = mClient.withReadTimeout(PlannerPrompt.READ_TIMEOUT_MS);
            String body = LlmClient.body(PlannerPrompt.MODEL, PlannerPrompt.TEMPERATURE, maxTokens,
                    LlmClient.messages(system, user), null, false).toString();
            LlmResponse r = c.post(body);
            return r.ok() ? r.content() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
