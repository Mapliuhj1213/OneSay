package com.onesay.desktop;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/**
 * [dual-1122] 双端互通真机前的沙箱回环里发现的两处（电脑端）：
 * (1) 电脑对话「让手机打开设置看看安卓版本」被规则劫持成「打开一个叫『设置看看安卓版本』的 App」（open_app，手机默认还关着），
 *     没交给按目标操作；(2) 手机连着交第二个任务时，接在了上一个任务卡里（标题、推回手机的结果都是上一个）。
 */
public class Dual1122Test {

    @After public void reset() {
        PcGuard.LOCAL.set(Boolean.FALSE);
        PcGuard.TASK.set("");
        SafeExec.REMOTE.set(Boolean.FALSE);
        SafeExec.reset(() -> false, null);
        Progress.CANCEL.set(false);
    }

    @Test public void openAppWithFollowUpGoesToModel() {
        for (String s : new String[]{"让手机打开设置看看安卓版本", "手机打开微信然后看看有没有新消息", "让手机打开淘宝帮我搜索充电器", "让手机打开相册里的最新照片"}) {
            Intent it = Intent.parse(s, Map.of(), List.of(), true);
            assertNull(s + " → " + it.phoneCmd + " " + it.args, it.phoneCmd);
            assertTrue(s, it.say.startsWith("没听懂"));
        }
        // 只打开一个 App / 网站：照旧走快速指令
        Intent w = Intent.parse("让手机打开微信", Map.of(), List.of(), true);
        assertEquals("open_app", w.phoneCmd);
        assertEquals("微信", w.args.get("name"));
        assertEquals("open_url", Intent.parse("手机打开B站", Map.of(), List.of(), true).phoneCmd);
        // 没配大模型：照旧（规则能做多少做多少）
        assertEquals("open_app", Intent.parse("让手机打开设置看看安卓版本", Map.of(), List.of(), false).phoneCmd);
        assertTrue(Intent.openWithTask("设置看看安卓版本"));
        assertFalse(Intent.openWithTask("设置"));
        assertFalse(Intent.openWithTask("看看"));
    }

    @Test public void eachPhoneTaskGetsItsOwnCard() throws Exception {
        try (Link1119E2ETest.Pc pc = new Link1119E2ETest.Pc()) {
            Config ph = LinkV2Test.phoneCfg(pc.cfg, "phone-d");
            List<Map<String, Object>> notes = new CopyOnWriteArrayList<>();
            LinkV2Test.Conn held = new LinkV2Test.Conn(ph, true, Link1119E2ETest.phone(ph, new CopyOnWriteArrayList<>(), (cmd, a) -> {
                if (cmd.equals("notify")) notes.add(a);
                return Actions.Result.ok("好");
            }));
            assertTrue(held.up);
            AtomicInteger n = new AtomicInteger();
            pc.chat.brain = (m, t) -> {
                int k = n.incrementAndGet();
                if (k == 1) return Link1119E2ETest.call("1", "write_file", Json.stringify(Map.of("description", "写第一份", "path", "a.txt", "text", "一")));
                if (k == 2) return Link1119E2ETest.say("【完成】\n写好 a.txt。");
                if (k == 3) return Link1119E2ETest.call("2", "write_file", Json.stringify(Map.of("description", "写第二份", "path", "b.txt", "text", "二")));
                return Link1119E2ETest.say("【完成】\n写好 b.txt。");
            };
            held.request(ph, "pc_task", new TreeMap<>(Map.of("goal", "第一件：写 a")));
            assertNotNull(Link1119E2ETest.waitFor(() -> notes.isEmpty() ? null : notes.get(0), 8000));
            long end = System.currentTimeMillis() + 5000;
            while (pc.chat.running() && System.currentTimeMillis() < end) Thread.sleep(50);
            held.request(ph, "pc_task", new TreeMap<>(Map.of("goal", "第二件：写 b")));
            assertNotNull(Link1119E2ETest.waitFor(() -> notes.size() < 2 ? null : notes.get(1), 8000));
            List<Map<String, Object>> cards = new java.util.ArrayList<>();
            for (Map<String, Object> m : pc.chat.store().list("chat")) if ("task".equals(m.get("via"))) cards.add(m);
            assertEquals("两个任务两张卡：" + cards, 2, cards.size());
            assertTrue(String.valueOf(cards.get(0).get("title")).contains("第一件"));
            assertTrue(String.valueOf(cards.get(1).get("title")).contains("第二件"));
            assertTrue("推回手机的是第二件的结果：" + notes.get(1), String.valueOf(notes.get(1).get("text")).contains("b.txt"));
            Map<String, Object> st = held.request(ph, "pc_task_status", new TreeMap<>());
            @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) st.get("data");
            assertTrue(String.valueOf(d), String.valueOf(d.get("title")).contains("第二件"));
            assertEquals(1L, ((Number) d.get("steps")).longValue());
            held.lc.stop();
        }
    }
}
