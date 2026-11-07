package com.onesay.assistant.v2.soul;

import com.onesay.assistant.v2.roles.AppChoiceSpec;
import com.onesay.assistant.v2.router.L2LLMCompiler;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * ★ 综合包（soul × bike-chooser）：
 * ① 提示词顺序：soul 稳定前缀在系统提示词最前，app_choice 规则在末尾；
 * ② 记忆写入 / 会话整理 / 人设不收回答里的 &lt;app_choice&gt; / store_query 尾巴。
 */
public class SoulAppChoiceGuardTest extends SoulTestBase {

    static final String TAIL = "<app_choice>{\"category\":\"共享单车\",\"store_query\":\"共享单车\",\"apps\":[{\"label\":\"哈啰\",\"pkg\":\"com.jingyao.easybike\"}]}</app_choice>";

    @Test public void soulPrefixFirstAppChoiceRuleLast() {
        String prefix = en.inject(T0, false).prefix;
        String sys = prefix + L2LLMCompiler.buildAgentLoopPrompt(12);   // 与 SoulHooks.withPrefix 同口径拼接
        assertTrue("人设前缀在最前", sys.startsWith("【人设】\n"));
        assertTrue("app_choice 规则在末尾", sys.endsWith(AppChoiceSpec.PROMPT_RULE));
        assertEquals("规则只出现一次", sys.indexOf(AppChoiceSpec.PROMPT_RULE), sys.lastIndexOf(AppChoiceSpec.PROMPT_RULE));
    }

    @Test public void stripMachineTail() {
        assertEquals("骑车用哈啰", MemoryText.stripMachineTail("骑车用哈啰\n" + TAIL));
        assertEquals("骑车用哈啰", MemoryText.stripMachineTail("骑车用哈啰<app_choice>{\"category\":\"共享"));   // 截断的尾巴
        assertNull("漏了标签直接吐 JSON → 整条不收", MemoryText.stripMachineTail("{\"store_query\":\"共享单车\"}"));
        assertEquals("普通文字原样", "我喜欢喝美式", MemoryText.stripMachineTail("我喜欢喝美式"));
    }

    @Test public void rememberDropsTail() {
        SoulEngine.WriteResult w = en.remember("以后骑车用哈啰\n" + TAIL, Claim.SRC_USER, "以后骑车用哈啰", "s1", false, "", "", "", T0);
        assertTrue(w.ok);
        assertFalse(w.claim.text.contains("app_choice"));
        assertFalse(w.claim.text.contains("store_query"));
        assertFalse(en.remember("{\"store_query\":\"x\"}", Claim.SRC_USER, "q", "s1", false, "", "", "", T0).ok);
        SoulEngine.WriteResult n = en.noteImplicit("车停在B2" + TAIL, "", "", "", "s1", T0);
        assertTrue(n.ok);
        assertEquals("车停在B2", n.claim.text);
    }

    @Test public void consolidationOutputWithTailIsCleaned() {
        String llm = "[{\"text\":\"用户骑车用哈啰\",\"quote\":\"我骑车都用哈啰\",\"kind\":\"pref\",\"confidence\":0.8},"
                + "{\"text\":\"用户想要 store_query=共享单车\",\"quote\":\"我骑车都用哈啰\",\"kind\":\"pref\"}]\n" + TAIL;
        int n = en.ingestCandidates(llm, Arrays.asList("我骑车都用哈啰"), "s1", T0);
        assertEquals(1, n);
        for (Claim c : repo.list(Claim.T_LOG)) {
            assertFalse(c.text, c.text.contains("store_query"));
            assertFalse(c.text, c.text.contains("app_choice"));
        }
    }

    @Test public void soulProposalRejectsTail() {
        SoulEngine.SoulResult r = en.proposeSoul(SoulTemplate.DEFAULT + "\n" + TAIL, "x", false, T0);
        assertFalse(r.ok);
    }
}
