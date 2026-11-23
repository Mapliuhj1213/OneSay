package com.onesay.assistant.v2.router;

/** ★ int/1102 测试桥：L2LLMCompiler.buildBody 是包内可见的。 */
public final class L2LLMCompilerAccess {
    private L2LLMCompilerAccess() {}

    public static String buildBody(String sys, java.util.List<ConversationMemory.Turn> h, String user, int max, boolean stream) throws Exception {
        return L2LLMCompiler.buildBody(sys, h, user, max, stream);
    }

    public static String buildBody(String sys, java.util.List<ConversationMemory.Turn> h, String user, int max, boolean stream,
                                   com.onesay.assistant.v2.net.ThinkingMode.Purpose p) throws Exception {
        return L2LLMCompiler.buildBody(sys, h, user, max, stream, p);
    }

    /** ★ fix/1123-longctx-phone：自由问答一轮（与 L2LLMCompiler.ask 同一份代码）。 */
    public static String askTurn(String system, String userContent, String query, ConversationMemory mem, int max,
                                 L2LLMCompiler.BodyCaller caller, boolean tainted) {
        return L2LLMCompiler.askTurn(system, userContent, query, mem, max, com.onesay.assistant.v2.net.ThinkingMode.Purpose.ASK, caller, tainted);
    }

    public static String composeAskUser(ConversationMemory mem, String query, String userContent, String redo) {
        return L2LLMCompiler.composeAskUser(mem, query, userContent, redo);
    }

    public static String recheck(String query, String answer, ConversationMemory mem, L2LLMCompiler.BodyCaller caller) {
        return L2LLMCompiler.recheck("系统", query, query, answer, mem, 800, com.onesay.assistant.v2.net.ThinkingMode.Purpose.ASK, caller);
    }
}
