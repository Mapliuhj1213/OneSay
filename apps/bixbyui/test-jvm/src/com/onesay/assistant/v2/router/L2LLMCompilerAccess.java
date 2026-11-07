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
}
