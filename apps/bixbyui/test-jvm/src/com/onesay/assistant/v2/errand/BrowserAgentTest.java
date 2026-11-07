package com.onesay.assistant.v2.errand;

import com.onesay.assistant.v2.errand.web.AgentAction;
import com.onesay.assistant.v2.errand.web.BrowsePolicy;
import com.onesay.assistant.v2.errand.web.BrowserAgent;
import com.onesay.assistant.v2.errand.web.PageScript;
import com.onesay.assistant.v2.errand.web.PageSnapshot;
import com.onesay.assistant.v2.errand.web.WebViewDriver;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** ★ lane C3-2：内置 WebView 浏览代理（假页面 / 假模型 / 假时钟）。 */
public class BrowserAgentTest {

    // ------------------------------------------------------------------ 假页面世界

    static PageSnapshot page(String url, String text, PageSnapshot.El... els) {
        return new PageSnapshot(url, "T:" + url, text, Arrays.asList(els), false, false, 0, 3000, null);
    }

    static PageSnapshot.El link(int id, String text, String href) {
        return new PageSnapshot.El(id, "a", "a", text, href, "");
    }

    static PageSnapshot.El input(int id, String text) {
        return new PageSnapshot.El(id, "input", "input:text", text, "", "");
    }

    static PageSnapshot.El pwd(int id) {
        return new PageSnapshot.El(id, "input", "input:password", "密码", "", "");
    }

    /** 点击 / 输入按「页面 url + 动作」跳到下一个页面；没登记的动作 = 页面不变。 */
    static final class World implements BrowserAgent.Driver {
        final Map<String, PageSnapshot> pages = new HashMap<String, PageSnapshot>();
        final Map<String, String> clicks = new HashMap<String, String>();   // url#id → url
        PageSnapshot cur;
        String blocked;
        final List<String> log = new ArrayList<String>();
        String typed;

        void add(PageSnapshot p) {
            pages.put(p.url, p);
        }

        @Override public PageSnapshot load(String url) {
            log.add("load " + url);
            PageSnapshot p = pages.get(url);
            if (p == null) return null;
            cur = p;
            return p;
        }

        @Override public PageSnapshot snapshot() {
            return cur;
        }

        @Override public BrowserAgent.Result act(AgentAction a) {
            log.add(a.toString());
            if (a.type == AgentAction.Type.TYPE) {
                typed = a.text;
                String to = clicks.get(cur.url + "#type");
                if (a.submit && to != null) cur = pages.get(to);
                return new BrowserAgent.Result(true, a.text, null);
            }
            if (a.type == AgentAction.Type.SCROLL) {
                cur = new PageSnapshot(cur.url, cur.title, cur.text, cur.els, cur.hasPassword, cur.hasCaptcha, cur.scrollY + 800, cur.scrollMax, cur.sig);
                return new BrowserAgent.Result(true, null, null);
            }
            String to = clicks.get(cur.url + "#" + a.id);
            if (to != null) cur = pages.get(to);
            return new BrowserAgent.Result(cur.find(a.id) != null || to != null, null, null);
        }

        @Override public boolean back() {
            return false;
        }

        @Override public String blockedReason() {
            return blocked;
        }
    }

    static class Script implements BrowserAgent.Planner {
        final LinkedList<AgentAction> plan;
        final List<List<String>> seenHistory = new ArrayList<List<String>>();
        String wrap = "阶段性结论";

        Script(AgentAction... a) {
            plan = new LinkedList<AgentAction>(Arrays.asList(a));
        }

        @Override public AgentAction next(ErrandIntent i, PageSnapshot p, List<String> h, int step, int max) {
            seenHistory.add(new ArrayList<String>(h));
            return plan.isEmpty() ? AgentAction.scroll(1) : plan.removeFirst();
        }

        @Override public String wrapUp(ErrandIntent i, PageSnapshot p, List<String> h) {
            return wrap;
        }
    }

    static final class FakeClock implements BrowserAgent.Clock {
        long t;

        @Override public long now() {
            return t;
        }
    }

    static final ErrandIntent READ = ErrandIntent.parse("帮我看看这个帖子 https://bbs.example.com/t/1 讲了什么");

    // ------------------------------------------------------------------ 测试

    @Test
    public void search_click_and_done() {
        World w = new World();
        w.add(page("https://bbs.example.com/t/1", "帖子列表", link(1, "第一楼", "https://bbs.example.com/t/1/p1"), input(2, "搜索")));
        w.add(page("https://bbs.example.com/t/1/p1", "第一楼的正文：Note 8 换电池要先加热背板。"));
        w.clicks.put("https://bbs.example.com/t/1#1", "https://bbs.example.com/t/1/p1");
        Script s = new Script(AgentAction.click(1), AgentAction.done("要先加热背板。"));
        BrowserAgent ag = new BrowserAgent(w, s, null, null, new FakeClock());
        BrowseResult r = ag.run(READ, "https://bbs.example.com/t/1", null);
        assertEquals(BrowseResult.Status.DONE, r.status);
        assertEquals("要先加热背板。", r.answer);
        assertEquals(2, r.steps);
        assertEquals(2, r.sources.size());
        assertEquals("https://bbs.example.com/t/1/p1", r.sources.get(1)[1]);
        // 第二轮的历史里有读回校验的结果
        assertTrue(s.seenHistory.get(1).get(0), s.seenHistory.get(1).get(0).contains("已跳转"));
    }

    @Test
    public void readback_failure_is_reported_to_model_not_repeated_blindly() {
        World w = new World();
        w.add(page("https://bbs.example.com/t/1", "正文", link(1, "没反应的链接", "https://bbs.example.com/x")));
        Script s = new Script(AgentAction.click(1), AgentAction.done("好"));
        BrowserAgent ag = new BrowserAgent(w, s, null, null, new FakeClock());
        ag.run(READ, "https://bbs.example.com/t/1", null);
        String h = s.seenHistory.get(1).get(0);
        assertTrue(h, h.contains("没有生效"));
        assertTrue(h, h.contains("页面没有变化"));
    }

    @Test
    public void invalid_ids_and_passwords_never_reach_the_page() {
        World w = new World();
        w.add(page("https://bbs.example.com/t/1", "正文", input(1, "用户名"), pwd(2)));
        Script s = new Script(AgentAction.click(99), AgentAction.type(2, "123456", true), AgentAction.done("x"));
        // 密码框在页面上 → 先触发接手；这里接手被拒，确认根本没执行到输入
        BrowserAgent ag = new BrowserAgent(w, s, new BrowserAgent.Takeover() {
            @Override public boolean await(String reason, String url) {
                return false;
            }
        }, null, new FakeClock());
        BrowseResult r = ag.run(READ, "https://bbs.example.com/t/1", null);
        assertEquals(BrowseResult.Status.TAKEOVER, r.status);
        assertEquals("需要你登录", r.reason);
        assertNull(w.typed);

        // 没有密码框时：越界编号被拒、不执行
        World w2 = new World();
        w2.add(page("https://bbs.example.com/t/1", "正文", input(1, "搜索")));
        Script s2 = new Script(AgentAction.click(99), AgentAction.done("x"));
        new BrowserAgent(w2, s2, null, null, new FakeClock()).run(READ, "https://bbs.example.com/t/1", null);
        assertFalse(w2.log.toString(), w2.log.toString().contains("CLICK#99"));
        assertTrue(s2.seenHistory.get(1).get(0).contains("不在当前页面"));
    }

    @Test
    public void takeover_waits_for_user_then_resumes_without_counting_time() {
        final World w = new World();
        PageSnapshot login = new PageSnapshot("https://passport.example.com/login?r=1", "登录", "扫码登录 密码登录", Arrays.asList(pwd(1)), true, false, 0, 0, null);
        w.add(login);
        final PageSnapshot home = page("https://www.example.com/me", "我的订单 …… 共 3 单");
        w.add(home);
        final FakeClock clock = new FakeClock();
        final String[] asked = {null};
        BrowserAgent ag = new BrowserAgent(w, new Script(AgentAction.done("共 3 单")), new BrowserAgent.Takeover() {
            @Override public boolean await(String reason, String url) {
                asked[0] = reason + "@" + url;
                clock.t += 9 * 60 * 1000L;   // 用户花了 9 分钟登录
                w.cur = home;                // 登录后跳回
                return true;
            }
        }, null, clock);
        BrowseResult r = ag.run(READ, "https://passport.example.com/login?r=1", null);
        assertEquals("需要你登录@https://passport.example.com/login?r=1", asked[0]);
        assertEquals(BrowseResult.Status.DONE, r.status);   // 没有因为「超过 180 秒」被判 LIMIT
    }

    @Test
    public void captcha_triggers_takeover() {
        PageSnapshot p = new PageSnapshot("https://www.example.com/s", "", "请完成安全验证 向右滑动", new ArrayList<PageSnapshot.El>(), false, true, 0, 0, null);
        assertEquals("需要你完成验证", p.takeoverReason());
        assertNull(page("https://www.example.com/a", "一篇很长的文章，里面提到了登录这个词但不是登录页").takeoverReason());
        assertEquals("需要你登录", page("https://passport.jd.com/new/login.aspx", "x").takeoverReason());
    }

    @Test
    public void step_and_time_limits_return_partial_result() {
        World w = new World();
        w.add(page("https://bbs.example.com/t/1", "很长的正文"));
        Script s = new Script();   // 一直滚动，永远不 done
        BrowserAgent ag = new BrowserAgent(w, s, null, null, new FakeClock());
        BrowseResult r = ag.run(READ, "https://bbs.example.com/t/1", null);
        assertEquals(BrowseResult.Status.LIMIT, r.status);
        assertEquals("阶段性结论", r.answer);
        assertTrue(r.steps <= BrowsePolicy.MAX_STEPS);

        final FakeClock c = new FakeClock();
        World w2 = new World();
        w2.add(page("https://bbs.example.com/t/1", "很长的正文"));
        BrowserAgent ag2 = new BrowserAgent(w2, new Script() {
            @Override public AgentAction next(ErrandIntent i, PageSnapshot p, List<String> h, int step, int max) {
                c.t += 100000L;
                return AgentAction.scroll(1);
            }
        }, null, null, c);
        BrowseResult r2 = ag2.run(READ, "https://bbs.example.com/t/1", null);
        assertEquals(BrowseResult.Status.LIMIT, r2.status);
        assertTrue(r2.reason, r2.reason.contains("秒"));
    }

    @Test
    public void unloadable_start_is_blocked_for_samsung_fallback() {
        World w = new World();
        w.blocked = "站点返回 HTTP 403";
        BrowseResult r = new BrowserAgent(w, new Script(), null, null, new FakeClock()).run(READ, "https://nope.example.com/", null);
        assertEquals(BrowseResult.Status.BLOCKED, r.status);
        assertEquals("站点返回 HTTP 403", r.reason);
    }

    @Test
    public void stop_from_island_is_honoured() {
        World w = new World();
        w.add(page("https://bbs.example.com/t/1", "正文"));
        BrowseResult r = new BrowserAgent(w, new Script(), null, null, new FakeClock()).run(READ, "https://bbs.example.com/t/1",
                new ResearchLadder.Progress() {
                    @Override public void step(String text) {
                    }

                    @Override public boolean cancelled() {
                        return true;
                    }
                });
        assertEquals(BrowseResult.Status.FAILED, r.status);
        assertEquals("已停止", r.reason);
    }

    // ------------------------------------------------------------------ 动作解析 / 校验 / 脚本

    @Test
    public void action_parse_tolerates_fences() {
        AgentAction a = AgentAction.parse("```json\n{\"action\":\"type\",\"id\":3,\"text\":\"数据线\",\"submit\":true}\n```");
        assertEquals(AgentAction.Type.TYPE, a.type);
        assertEquals(3, a.id);
        assertTrue(a.submit);
        assertEquals(AgentAction.Type.SCROLL, AgentAction.parse("{\"action\":\"scroll\",\"dir\":\"up\"}").type);
        assertEquals(-1, AgentAction.parse("{\"action\":\"scroll\",\"dir\":\"up\"}").dir);
        assertEquals(AgentAction.Type.INVALID, AgentAction.parse("我觉得应该点第一个").type);
        assertEquals(AgentAction.Type.INVALID, AgentAction.parse("{\"action\":\"pay\"}").type);
        assertNotNull(AgentAction.open("taobao://item?id=1").validate(page("https://a.com", "")));
        assertNotNull(AgentAction.parse("{\"action\":\"done\"}").validate(page("https://a.com", "")));
    }

    @Test
    public void verify_rules() {
        PageSnapshot a = page("https://a.com", "x", input(1, "q"));
        PageSnapshot b = page("https://a.com/2", "y");
        assertTrue(BrowsePolicy.verify(a, AgentAction.click(1), true, null, b).ok);
        assertTrue("点输入框不要求页面变化", BrowsePolicy.verify(a, AgentAction.click(1), true, null, a).ok);
        assertTrue(BrowsePolicy.verify(a, AgentAction.type(1, "数据线", false), true, "数据线", a).ok);
        assertFalse(BrowsePolicy.verify(a, AgentAction.type(1, "数据线", false), true, "", a).ok);
        assertFalse("提交后页面要变", BrowsePolicy.verify(a, AgentAction.type(1, "数据线", true), true, "数据线", a).ok);
        assertFalse(BrowsePolicy.verify(a, AgentAction.scroll(1), true, null, a).ok);
        assertNull(BrowsePolicy.limitReason(3, 1000, 0, 0));
        assertNotNull(BrowsePolicy.limitReason(BrowsePolicy.MAX_STEPS, 0, 0, 0));
        assertNotNull(BrowsePolicy.limitReason(0, 0, BrowsePolicy.MAX_SAME_PAGE, 0));
    }

    @Test
    public void scripts_quote_untrusted_text_and_parse_results() {
        String js = PageScript.type(4, "a\"b'c\n</script>", true);
        assertTrue(js, js.endsWith("(4,\"a\\\"b'c\\n<\\/script>\",true)"));
        assertTrue(PageScript.click(7).endsWith("(7)"));
        BrowserAgent.Result r = WebViewDriver.parseResult("\"{\\\"ok\\\":true,\\\"value\\\":\\\"数据线\\\"}\"");
        assertTrue(r.ok);
        assertEquals("数据线", r.value);
        assertFalse(WebViewDriver.parseResult(null).ok);
        PageSnapshot p = PageSnapshot.parse("\"{\\\"url\\\":\\\"https://a.com\\\",\\\"text\\\":\\\"t\\\",\\\"els\\\":[{\\\"i\\\":1,\\\"kind\\\":\\\"a\\\",\\\"text\\\":\\\"x\\\",\\\"href\\\":\\\"https://b.com\\\"}],\\\"sy\\\":0,\\\"smax\\\":10}\"");
        assertNotNull(p);
        assertEquals(1, p.els.size());
        assertTrue(p.forLlm().contains("[1] a \"x\" → https://b.com"));
        assertNull(PageSnapshot.parse("null"));
    }
}
