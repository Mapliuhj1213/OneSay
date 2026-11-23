package com.onesay.assistant.v2.router.longctx;

import com.onesay.assistant.plug.skills.core.SkillIndex;
import com.onesay.assistant.plug.skills.core.SkillMatcher;
import com.onesay.assistant.plug.skills.core.SkillSession;
import com.onesay.assistant.v2.mikaplug.SkillPack;
import com.onesay.assistant.v2.router.ConversationMemory;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.router.L2LLMCompilerAccess;

/**
 * ★ fix/1123-longctx-phone：新做法 = 生产代码原样：路由入口记账（beginTurn）→ 技能按「已加载清单」选（SkillPack.selectWithReload）
 * → L2LLMCompiler.askTurn（组请求 / 出口自检重答 / 记终稿）；重活回执照生产调用 record。on=false 时总开关关。
 */
public final class LongCtxEvalNewDriver implements LongCtxEval.Driver {
    final boolean on;
    ConversationMemory mem;
    SkillMatcher matcher;
    final SkillSession session = new SkillSession();
    long[] clock;

    final int relayTurns;

    public LongCtxEvalNewDriver(boolean on) {
        this(on, 0);
    }

    /** relayTurns：自动接力门槛（0 = 默认）；评测里压低它，专门看「接力之后还记不记得、还跑不跑偏」。 */
    public LongCtxEvalNewDriver(boolean on, int relayTurns) {
        this.on = on;
        this.relayTurns = relayTurns;
    }

    @Override public String name() {
        return on ? "改后（默认开）" : "改后（总开关关）";
    }

    @Override public void begin(int budget, final long[] clock, SkillIndex skills) {
        LongCtx.setEnabled(on);
        this.clock = clock;
        mem = ConversationMemory.newForTest(new ConversationMemory.Clock() {
            @Override public long now() {
                return clock[0];
            }
        });
        mem.setBudgetTokens(budget);
        mem.setRelayTurns(relayTurns);
        matcher = new SkillMatcher(skills, new SkillMatcher.Config());
        session.reset();
    }

    @Override public String ask(String query, String system, final FakeLongCtxModel model) {
        LongCtx.setEnabled(on);
        mem.beginTurn(query);
        if (mem.snapshot().isEmpty()) session.reset();
        SkillMatcher.Input in = new SkillMatcher.Input(query);
        in.path = "ask";
        SkillMatcher.Selection sel = SkillPack.selectWithReload(matcher, in, session, clock[0], mem);
        String sys = sel.isEmpty() ? system : system + "\n\n" + sel.block;
        return L2LLMCompilerAccess.askTurn(sys, query, query, mem, 800, new L2LLMCompiler.BodyCaller() {
            @Override public String call(String body) {
                return model.answer(body);
            }
        }, false);
    }

    @Override public void heavy(String query, String receipt) {
        mem.beginTurn(query);
        mem.record(query, receipt, true);
    }

    @Override public int relays() {
        return mem == null ? 0 : mem.relays();
    }

    @Override public int retries() {
        return mem == null ? 0 : mem.retries();
    }
}
