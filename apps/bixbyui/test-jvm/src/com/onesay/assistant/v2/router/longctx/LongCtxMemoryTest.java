package com.onesay.assistant.v2.router.longctx;

import com.onesay.assistant.plug.skills.core.SkillIndex;
import com.onesay.assistant.plug.skills.core.SkillMatcher;
import com.onesay.assistant.plug.skills.core.SkillSession;
import com.onesay.assistant.v2.mikaplug.SkillPack;
import com.onesay.assistant.v2.router.AgentLoop;
import com.onesay.assistant.v2.router.ConversationMemory;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.router.L2LLMCompilerAccess;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** ★ fix/1123-longctx-phone：ConversationMemory 接上长对话组件后的行为（逐项单独测）+ askTurn 出口自检。 */
public class LongCtxMemoryTest {

    final long[] clock = {1000L};
    ConversationMemory m;

    @Before public void setUp() {
        LongCtx.setEnabled(true);
        LongCtx.setSelfCheck(true);
        m = ConversationMemory.newForTest(new ConversationMemory.Clock() {
            @Override public long now() {
                return clock[0];
            }
        });
    }

    @After public void tearDown() {
        LongCtx.setEnabled(true);
        LongCtx.setSelfCheck(true);
        ConversationMemory.setListener(null);
    }

    void turn(String q, String a) {
        clock[0] += 1000;
        m.beginTurn(q);
        m.record(q, a);
    }

    static String lastUser(String body) throws Exception {
        JSONArray ms = new JSONObject(body).getJSONArray("messages");
        return ms.getJSONObject(ms.length() - 1).getString("content");
    }

    @Test public void firstTurnUnchangedThenLatestLast() throws Exception {
        assertNull("第一轮不加任何东西", m.tailFor("天空为什么是蓝的", null));
        assertEquals("天空为什么是蓝的", L2LLMCompilerAccess.composeAskUser(m, "天空为什么是蓝的", "天空为什么是蓝的", null));
        turn("天空为什么是蓝的", "因为散射。");
        m.beginTurn("那晚霞呢");
        String u = L2LLMCompilerAccess.composeAskUser(m, "那晚霞呢", "那晚霞呢", null);
        String[] lines = u.split("\n");
        assertEquals(LongCtx.LATEST + "#2 用户：那晚霞呢", lines[lines.length - 1]);
        assertTrue(u.contains("已结：#1"));
        assertTrue(u.contains("不用「啥」「咋」「呗」"));
        // 带检索资料：资料在前，最新一句仍在最后
        String withSearch = L2LLMCompilerAccess.composeAskUser(m, "那晚霞呢", "【检索资料】……\n————\n【用户问题】那晚霞呢", null);
        assertTrue(withSearch.startsWith("【检索资料】"));
        assertTrue(withSearch.endsWith("#2 用户：那晚霞呢"));
        String body = L2LLMCompilerAccess.buildBody("系统", m.snapshot(), u, 100, false);
        assertTrue(lastUser(body).endsWith("#2 用户：那晚霞呢"));
    }

    @Test public void switchOffIsLegacy() {
        LongCtx.setEnabled(false);
        turn("天空为什么是蓝的", "因为散射。");
        assertNull(m.tailFor("那晚霞呢", null));
        assertEquals(0, m.beginTurn("那晚霞呢"));
        assertTrue(m.ledger().isEmpty());
        assertEquals(0, m.archive().size());
        String receipt = "[执行回执] Agent 循环已完成\n· 第1步 x\n结论：好了";
        m.record("打开设置", receipt, true);
        assertEquals("总开关关：回执原样进历史", receipt, m.snapshot().get(m.snapshot().size() - 1).answer);
    }

    @Test public void ledgerTracksEveryUserMessage() {
        m.beginTurn("帮我查快递");          // 没出结果（失败 / 被打断）
        turn("天空为什么是蓝的", "因为散射。");
        List<TurnLedger.Entry> l = m.ledger();
        assertEquals(2, l.size());
        assertEquals(TurnLedger.Status.PENDING, l.get(0).status);
        assertEquals(TurnLedger.Status.DONE, l.get(1).status);
        m.beginTurn("那晚霞呢");
        String t = m.tailFor("那晚霞呢", null);
        assertTrue(t, t.contains("没答完：#1「帮我查快递」（待答）——用户再提起时才接着做"));
        assertTrue(t.contains("已结：#2"));
        assertEquals("同一句重复进来不重复记", 3, m.beginTurn("那晚霞呢"));
    }

    @Test public void stateSummaryNotLog() {
        m.setBudgetTokens(2048);
        turn("我跟你说一下情况，下周三要去成都出差，我的航班是 CA4502，早上八点二十起飞", "好的，记下了。");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 300; i++) big.append("内容");
        for (int i = 0; i < 8; i++) turn("问题" + i + "是什么来着呀朋友", "回答" + i + big);
        assertTrue("应已折叠：" + m.foldedTurns(), m.foldedTurns() > 0);
        ConversationMemory.Turn s = m.snapshot().get(0);
        assertEquals(ConversationMemory.SUMMARY_QUESTION, s.question);
        assertTrue(s.answer, s.answer.startsWith(LongCtx.STATE_HEAD));
        assertTrue("事实留下：" + s.answer, s.answer.contains("CA4502") && s.answer.contains("八点二十"));
        assertTrue(s.answer.contains("已结问题"));
        assertFalse("不是流水账", s.answer.contains("问：问题"));
        // 账本里折叠的标记了
        assertTrue(m.ledger().get(0).folded);
    }

    @Test public void archiveOnDemand() {
        m.setBudgetTokens(2048);
        turn("今天车停在公司地下停车场了，那边车位特别难找，转了好几圈才找到，最后我停在了负二层 B 区 117 号车位", "好的，记下了。");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 300; i++) big.append("内容");
        for (int i = 0; i < 8; i++) turn("问题" + i + "是什么来着呀朋友", "回答" + i + big);
        m.beginTurn("我车停在哪个车位了");
        String t = m.tailFor("我车停在哪个车位了", null);
        assertTrue("按需调出原文：" + t, t.contains(LongCtx.ARCHIVE) && t.contains("117 号车位"));
        // 不相关的话不调存档
        m.beginTurn("鸡蛋煮几分钟");
        assertFalse(m.tailFor("鸡蛋煮几分钟", null).contains(LongCtx.ARCHIVE));
        // 手动新对话 = 新链，不调回上一段
        m.clear();
        turn("你好", "你好呀");
        m.beginTurn("我车停在哪个车位了");
        assertFalse("新会话像第一次", m.tailFor("我车停在哪个车位了", null).contains("117"));
        assertTrue("但原文仍在存档里", m.archive().size() >= 10);
    }

    @Test public void heavyIsolatedButRecoverable() {
        StringBuilder r = new StringBuilder("[视觉识屏回执] 屏幕识别完成：群里在说周末聚餐。\n");
        for (int i = 1; i <= 60; i++) r.append(i).append(". 群消息：周末聚餐改到周六晚上七点，第").append(i).append("条，地点在万达三楼烤肉店\n");
        m.beginTurn("看看屏幕");
        m.record("看看屏幕", r.toString(), true);
        String inHistory = m.snapshot().get(0).answer;
        assertTrue("主上下文只留精简版：" + inHistory.length(), inHistory.length() < 400);
        assertTrue(inHistory.endsWith(HeavyIsolation.NOTE));
        assertTrue("仍是外部内容", m.snapshot().get(0).isTainted());
        m.beginTurn("第37条说了什么");
        String t = m.tailFor("第37条说了什么", null);
        assertTrue("回指 → 调出全文（含第 37 条）：" + t.length(), t.contains("第37条"));
        assertTrue("外部内容调出时仍包不可信标签", t.contains(AgentLoop.UNTRUSTED_OPEN));
    }

    @Test public void autoRelayKeepsStateAndNotifies() throws Exception {
        final CountDownLatch ended = new CountDownLatch(1);
        final int[] oldSize = {0};
        ConversationMemory.setListener(new ConversationMemory.Listener() {
            @Override public void onRecord(String question, boolean tainted) {
            }

            @Override public void onClear(List<ConversationMemory.Turn> turns) {
                oldSize[0] = turns.size();
                ended.countDown();
            }
        });
        m.setRelayTurns(8);
        turn("对了，以后叫我小林就行，我在杭州上班", "好的小林");
        for (int i = 0; i < 7; i++) turn("问题" + i, "回答" + i);
        assertEquals(1, m.relays());
        assertTrue("通知了上一段结束（记忆整理照旧）", ended.await(3, TimeUnit.SECONDS));
        assertEquals(8, oldSize[0]);
        List<ConversationMemory.Turn> snap = m.snapshot();
        assertEquals("接力摘要 + 最近 2 轮原文", 1 + ConversationMemory.RELAY_KEEP, snap.size());
        assertTrue(snap.get(0).answer.startsWith(LongCtx.RELAY_HEAD));
        assertTrue(snap.get(0).answer.contains("叫我小林"));
        // 编号接着来、同一条链还能调原文
        m.beginTurn("你还记得我在哪上班吗");
        String t = m.tailFor("你还记得我在哪上班吗", null);
        assertTrue(t, t.contains("#9 用户：你还记得我在哪上班吗"));
        assertTrue(t, t.contains("杭州"));
        // 手动新对话照旧
        m.clear();
        assertTrue(m.isEmpty());
        assertEquals(1, m.beginTurn("新话题"));
    }

    @Test public void skillsReloadAfterCompaction() {
        SkillIndex idx = new SkillIndex();
        idx.add("skill_trip_eval.md", LongCtxEval.TRIP_SKILL);
        SkillMatcher mt = new SkillMatcher(idx, new SkillMatcher.Config());
        SkillSession ss = new SkillSession();
        SkillMatcher.Input in = new SkillMatcher.Input("帮我规划去成都出差的行程");
        in.path = "ask";
        assertEquals("trip_eval", SkillPack.selectWithReload(mt, in, ss, 1000, m).ids().get(0));
        assertEquals("记进已加载清单", 1, m.loadedSkills().size());
        // 隔了好几轮不相关的 → 正常会卸载
        long now = 2000;
        for (int i = 0; i < 4; i++) {
            SkillMatcher.Input x = new SkillMatcher.Input("鸡蛋煮几分钟最好吃呢朋友" + i);
            x.path = "ask";
            SkillPack.selectWithReload(mt, x, ss, now += 1000, m);
        }
        SkillMatcher.Input q = new SkillMatcher.Input("这趟大概要带多少预算");
        q.path = "ask";
        assertTrue("没压缩：照旧卸载", SkillPack.selectWithReload(mt, q, ss, now += 1000, m).isEmpty());
        // 压缩发生 → 下一次重新加载清单里的
        m.setBudgetTokens(2048);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 300; i++) big.append("内容");
        for (int i = 0; i < 8; i++) turn("问题" + i, "回答" + big);
        assertTrue(m.foldedTurns() > 0);
        assertTrue("状态摘要写了已加载技能", m.snapshot().get(0).answer.contains("已加载技能：出行规划"));
        SkillMatcher.Selection sel = SkillPack.selectWithReload(mt, q, ss, now += 1000, m);
        assertEquals("压缩后重新加载", "trip_eval", sel.ids().isEmpty() ? null : sel.ids().get(0));
        assertTrue("只重新加载一次", m.takeSkillReload().isEmpty());
        // 总开关关：不记清单、不重载
        LongCtx.setEnabled(false);
        ConversationMemory m2 = ConversationMemory.newForTest(new ConversationMemory.Clock() {
            @Override public long now() {
                return 1;
            }
        });
        SkillPack.selectWithReload(mt, in, new SkillSession(), 1000, m2);
        assertTrue(m2.loadedSkills().isEmpty());
    }

    // ---------------------------------------------------------------- askTurn：出口自检重答一次
    static final class Scripted implements L2LLMCompiler.BodyCaller {
        final List<String> replies = new ArrayList<String>();
        final List<String> bodies = new ArrayList<String>();

        @Override public String call(String body) {
            bodies.add(body);
            return bodies.size() <= replies.size() ? replies.get(bodies.size() - 1) : null;
        }
    }

    @Test public void askTurnRechecksOnce() throws Exception {
        Scripted s = new Scripted();
        s.replies.add("你问的是「天空为什么是蓝色的」。");   // 第一轮：没有历史，不自检
        assertEquals("你问的是「天空为什么是蓝色的」。", L2LLMCompilerAccess.askTurn("系统", "天空为什么是蓝色的", "天空为什么是蓝色的", m, 300, s, false));
        assertEquals(1, s.bodies.size());
        // 第二轮：复述 → 重答一次，用重答的
        s.replies.add("你问的是「鸡蛋煮几分钟最好」。");
        s.replies.add("水开后煮六分钟是溏心。");
        m.beginTurn("鸡蛋煮几分钟最好");
        String a = L2LLMCompilerAccess.askTurn("系统", "鸡蛋煮几分钟最好", "鸡蛋煮几分钟最好", m, 300, s, false);
        assertEquals("水开后煮六分钟是溏心。", a);
        assertEquals(3, s.bodies.size());
        String redo = lastUser(s.bodies.get(2));
        assertTrue(redo.contains("【系统提示】上一次回答一开头就把用户的话复述了一遍"));
        assertTrue("重答请求最后一行仍是最新一句", redo.endsWith("用户：鸡蛋煮几分钟最好"));
        assertEquals(1, m.retries());
        assertEquals("记的是重答后的终稿", "水开后煮六分钟是溏心。", m.snapshot().get(1).answer);
        // 重答拿不到 → 用原答复
        m.beginTurn("Git 怎么撤销上一次提交");
        s.replies.add("你问的是「Git 怎么撤销上一次提交」。");
        String a3 = L2LLMCompilerAccess.askTurn("系统", "Git 怎么撤销上一次提交", "Git 怎么撤销上一次提交", m, 300, s, false);
        assertEquals("你问的是「Git 怎么撤销上一次提交」。", a3);
        // 只关自检：不重答
        LongCtx.setSelfCheck(false);
        Scripted s2 = new Scripted();
        s2.replies.add("你问的是「绿萝叶子发黄怎么回事」。");
        m.beginTurn("绿萝叶子发黄怎么回事");
        L2LLMCompilerAccess.askTurn("系统", "绿萝叶子发黄怎么回事", "绿萝叶子发黄怎么回事", m, 300, s2, false);
        assertEquals(1, s2.bodies.size());
    }
}
