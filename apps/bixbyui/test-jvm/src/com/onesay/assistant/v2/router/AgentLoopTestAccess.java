package com.onesay.assistant.v2.router;

/** 测试用：访问 AgentLoop 包内的 planFor。 */
public final class AgentLoopTestAccess {
    public static String planFor(String observation, String query) {
        java.util.List<AgentLoop.Step> steps = new java.util.ArrayList<AgentLoop.Step>();
        steps.add(new AgentLoop.Step("app.launch", new org.json.JSONObject(), observation, false, false));
        return AgentLoop.planFor(steps, query);
    }
}
