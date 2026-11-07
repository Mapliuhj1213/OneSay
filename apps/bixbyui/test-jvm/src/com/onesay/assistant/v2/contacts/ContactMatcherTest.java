package com.onesay.assistant.v2.contacts;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.CommsRisk;
import com.onesay.assistant.v2.executor.ConsentPolicy;
import com.onesay.assistant.v2.executor.ExecutionGate;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.executor.ToolGate;
import com.onesay.assistant.v2.router.AgentLoop;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 「打电话给妈妈」：本机通讯录解析（纯 Java 部分 + 面板 / AgentLoop 接线的源码断言）。 */
public class ContactMatcherTest {

    static ContactMatcher.Contact c(String name, String num) {
        return new ContactMatcher.Contact(name, num);
    }

    static final List<ContactMatcher.Contact> BOOK = Arrays.asList(
            c("老妈", "138 0013 8000"),
            c("张伟", "139-0000-1111"),
            c("张伟", "+86 139 0000 1111"),   // 同一人同号（+86）→ 去重
            c("姑妈", "137 0000 2222"),
            c("小明妈妈", "136 0000 3333"),
            c("爸爸（单位）", "135 0000 4444"),
            c("爸爸 手机", "135 0000 5555"),
            c("李雷", "134 0000 6666"),
            c("李雷", "010-6000 7777"),       // 一人两号 → 芯片
            c("没号码的人", ""));

    static String src(String rel) throws Exception {
        File f = new File(rel);
        if (!f.exists()) f = new File("../" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @After public void clean() {
        ContactMatcher.install(null);
        ConsentPolicy.revoke("phone.call");
        ConsentPolicy.revoke("sms.send");
    }

    // ---------------------------------------------------------------- 要不要查

    @Test public void needsLookupOnlyForWordsWithoutDigits() throws Exception {
        for (String s : new String[]{"妈妈", "老妈", "张伟", "Mom", "wang wei", "我妈"}) assertTrue(s, ContactMatcher.needsLookup(s));
        for (String s : new String[]{"", "10086", "138-0013-8000", "*#06#", "**21*138#", "10086,1", "妈妈138", "+86", "abc10086", null})
            assertFalse(String.valueOf(s), ContactMatcher.needsLookup(s));
        assertTrue(ContactMatcher.needsLookup("phone.call", new JSONObject().put("number", "妈妈")));
        assertTrue(ContactMatcher.needsLookup("sms.send", new JSONObject().put("number", "老爸").put("text", "x")));
        assertFalse("不是通信类", ContactMatcher.needsLookup("app.launch", new JSONObject().put("number", "妈妈")));
        assertFalse(ContactMatcher.needsLookup("phone.call", null));
    }

    @Test public void controlCharsStillDeniedByCommsRisk() throws Exception {
        // 查不查通讯录不改变号码分级：带拨号控制字符的照旧直接拒绝（不弹卡）
        Capability call = cap("phone.call");
        for (String bad : new String[]{"*#06#", "**21*13800138000#", "10086,1", "10086;2"}) {
            assertFalse(bad, ContactMatcher.needsLookup(bad));
            assertEquals(bad, ToolGate.Decision.DENY, ToolGate.check(call, new JSONObject().put("number", bad), false).decision);
        }
        // 通讯录里存的「号码」本身是 MMI 码：解析出来后过闸同样被拒
        ContactMatcher.Resolution r = ContactMatcher.resolve("运营商", Arrays.asList(c("运营商", "*#06#")));
        JSONObject a = new JSONObject().put("number", "运营商");
        ContactMatcher.apply(a, r.single());
        assertEquals(ToolGate.Decision.DENY, ToolGate.check(call, a, false).decision);
    }

    @Test public void cleanTargetStripsSpokenShell() {
        assertEquals("妈", ContactMatcher.cleanTarget("给我妈"));
        assertEquals("老婆", ContactMatcher.cleanTarget("我老婆的手机"));
        assertEquals("妈妈", ContactMatcher.cleanTarget("妈妈的电话"));
        assertEquals("张伟", ContactMatcher.cleanTarget("张伟"));
        assertEquals("mom", ContactMatcher.cleanTarget("Mom"));
        assertTrue(ContactMatcher.terms("我妈").containsAll(Arrays.asList("妈", "妈妈", "老妈", "母亲")));
        assertTrue(ContactMatcher.terms("爸爸").contains("老爸"));
        assertEquals(Arrays.asList("张伟"), ContactMatcher.terms("张伟"));
    }

    // ---------------------------------------------------------------- 解析

    @Test public void momResolvesToOneByAliasExact() {
        for (String t : new String[]{"妈妈", "老妈", "妈", "我妈", "母亲", "mom"}) {
            ContactMatcher.Resolution r = ContactMatcher.resolve(t, BOOK);
            assertEquals(t, ContactMatcher.Kind.ONE, r.kind);
            assertEquals(t, "老妈", r.single().name);
            assertEquals("138 0013 8000", r.single().number);
        }
    }

    @Test public void exactBeatsContainsAndSingleCharNeverContains() {
        // 有精确（老妈）时，「小明妈妈」「姑妈」都不算
        assertEquals(1, ContactMatcher.resolve("妈妈", BOOK).matches.size());
        // 没有精确时，≥2 字的说法才做包含匹配；单字「妈」不会带出「姑妈」
        List<ContactMatcher.Contact> noMom = new ArrayList<ContactMatcher.Contact>(BOOK);
        noMom.remove(0);
        ContactMatcher.Resolution r = ContactMatcher.resolve("妈妈", noMom);
        assertEquals(ContactMatcher.Kind.ONE, r.kind);
        assertEquals("小明妈妈", r.single().name);   // 可能误中 → 卡片上写名字，由用户确认
        assertEquals(ContactMatcher.Kind.NONE, ContactMatcher.resolve("哥", noMom).kind);
    }

    @Test public void manyAndNoneAndDedupe() {
        ContactMatcher.Resolution dad = ContactMatcher.resolve("爸爸", BOOK);
        assertEquals("「爸爸（单位）」「爸爸 手机」都是包含匹配", ContactMatcher.Kind.MANY, dad.kind);
        assertEquals(2, dad.matches.size());
        ContactMatcher.Resolution li = ContactMatcher.resolve("李雷", BOOK);
        assertEquals("一人两号 → 芯片", ContactMatcher.Kind.MANY, li.kind);
        assertEquals("李雷 134 0000 6666", li.matches.get(0).label());
        ContactMatcher.Resolution zw = ContactMatcher.resolve("张伟", BOOK);
        assertEquals("+86 同号去重", ContactMatcher.Kind.ONE, zw.kind);
        ContactMatcher.Resolution none = ContactMatcher.resolve("王老师", BOOK);
        assertEquals(ContactMatcher.Kind.NONE, none.kind);
        assertEquals("王老师", none.target);
        assertEquals("没号码的条目不算", ContactMatcher.Kind.NONE, ContactMatcher.resolve("没号码的人", BOOK).kind);
        assertEquals(ContactMatcher.Kind.NONE, ContactMatcher.resolve("妈妈", null).kind);
        assertEquals("表情 / 空格不影响精确", ContactMatcher.Kind.ONE,
                ContactMatcher.resolve("妈妈", Arrays.asList(c("妈 妈 ❤", "138 1111 2222"))).kind);
    }

    @Test public void contentQueryParsingAndSafeWhere() {
        String out = "Row: 0 display_name=老妈, data1=138 0013 8000\n"
                + "Row: 1 display_name=张, 伟, data1=139 0000 1111\n"
                + "Row: 2 display_name=NULL, data1=10086\n"
                + "Row: 3 display_name=空号, data1=NULL\n"
                + "No result found.\n";
        List<ContactMatcher.Contact> l = ContactMatcher.parseContentQuery(out);
        assertEquals(3, l.size());
        assertEquals("老妈", l.get(0).name);
        assertEquals("138 0013 8000", l.get(0).number);
        assertEquals("名字里的逗号按最后一个 data1 切", "张, 伟", l.get(1).name);
        assertEquals("", l.get(2).name);
        assertTrue(ContactMatcher.parseContentQuery(null).isEmpty());
        String w = ContactMatcher.likeWhere(Arrays.asList("妈妈", "mom", "x' OR '1'='1", "a;rm -rf", "张\"伟"));
        assertEquals("display_name LIKE '%妈妈%' OR display_name LIKE '%mom%'", w);
        assertNull(ContactMatcher.likeWhere(Arrays.asList("'", "$(id)")));
    }

    @Test public void logsAreMasked() {
        assertEquals("***8000", ContactMatcher.mask("138 0013 8000"));
        assertEquals("****", ContactMatcher.mask("110"));
        String s = c("老妈", "138 0013 8000").toString();
        assertFalse(s, s.contains("老妈") || s.contains("0013"));
    }

    // ---------------------------------------------------------------- 卡片

    @Test public void cardNoteShowsNameAndNumber() throws Exception {
        JSONObject a = new JSONObject().put("number", "妈妈");
        ContactMatcher.apply(a, c("老妈", "138 0013 8000"));
        assertEquals("呼叫：老妈 138 0013 8000", CommsRisk.cardNote("phone.call", a));
        assertEquals("短信发给：老妈 138 0013 8000", CommsRisk.cardNote("sms.send", a));
        assertEquals("呼叫：10086", CommsRisk.cardNote("phone.call", new JSONObject().put("number", "10086")));
        assertNull(CommsRisk.cardNote("wifi.on", a));
        assertNull(CommsRisk.cardNote("phone.call", new JSONObject()));
        // 解析后：phone.call 红卡（没有始终允许）、sms.send 黄卡
        assertEquals(ToolGate.Decision.REQUIRE_CONFIRM, ToolGate.check(cap("phone.call"), a, false).decision);
        assertEquals(ConsentPolicy.TIER_RED, ExecutionGate.evaluate(cap("phone.call"), a, false).tier);
        JSONObject s = new JSONObject(a.toString()).put("text", "我到了");
        assertEquals(ConsentPolicy.TIER_YELLOW, ExecutionGate.evaluate(cap("sms.send"), s, false).tier);
    }

    // ---------------------------------------------------------------- AgentLoop

    static final class FakeDir implements ContactMatcher.Directory {
        final List<List<String>> asked = new ArrayList<List<String>>();
        final List<ContactMatcher.Contact> book;
        FakeDir(List<ContactMatcher.Contact> book) { this.book = book; }
        @Override public List<ContactMatcher.Contact> lookup(List<String> terms) {
            asked.add(terms);
            return book;
        }
    }

    @Test public void agentPrecheckResolvesLocallyAndNeverLeaksBook() throws Exception {
        assertNull("号码不查", ContactMatcher.agentPrecheck("phone.call", new JSONObject().put("number", "10086")));
        JSONObject a0 = new JSONObject().put("number", "妈妈");
        String noDir = ContactMatcher.agentPrecheck("phone.call", a0);
        assertTrue(noDir, noDir.contains("查不了"));

        FakeDir d = new FakeDir(BOOK);
        ContactMatcher.install(d);
        JSONObject a = new JSONObject().put("number", "妈妈");
        assertNull(ContactMatcher.agentPrecheck("phone.call", a));
        assertEquals("138 0013 8000", a.optString("number"));
        assertEquals("老妈", a.optString("contact_name"));

        String many = ContactMatcher.agentPrecheck("phone.call", new JSONObject().put("number", "李雷"));
        assertTrue(many, many.contains("2 个号码"));
        assertFalse("观测里不带号码", many.contains("134") || many.contains("6666"));
        String none = ContactMatcher.agentPrecheck("sms.send", new JSONObject().put("number", "王老师").put("text", "x"));
        assertTrue(none, none.contains("没找到"));
        for (ContactMatcher.Contact c : BOOK) {
            if (c.name.length() > 0 && !"李雷".equals(c.name) && !"王老师".equals(c.name)) {
                assertFalse(c.name, many.contains(c.name) || none.contains(c.name));
            }
        }
    }

    static final class OneCallLlm implements AgentLoop.Llm {
        final List<String> seen = new ArrayList<String>();
        int n;
        @Override public boolean hasApiKey() { return true; }
        @Override public JSONObject call(JSONArray messages, JSONArray tools, int maxTokens) {
            seen.add(messages.toString());
            try {
                JSONObject m = new JSONObject().put("role", "assistant");
                if (n++ == 0) {
                    JSONObject fn = new JSONObject().put("name", "phone__call").put("arguments", "{\"number\":\"妈妈\"}");
                    m.put("content", "").put("tool_calls", new JSONArray().put(new JSONObject().put("id", "c1").put("type", "function").put("function", fn)));
                } else {
                    m.put("content", "好的。");
                }
                return m;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test public void agentLoopCallsMomGoesToRedCardWithResolvedNumber() throws Exception {
        CapabilityRegistry reg = CapabilityRegistry.getInstance();
        if (reg.describe("phone.call") == null) BuiltinCapabilities.registerAll(reg);
        ContactMatcher.install(new FakeDir(BOOK));
        final List<JSONObject> confirmed = new ArrayList<JSONObject>();
        final List<String> executed = new ArrayList<String>();
        OneCallLlm llm = new OneCallLlm();
        new AgentLoop(reg, llm).run("打给我妈说我晚点到", new AgentLoop.Callback() {
            @Override public ExecutionResult execute(Capability cap, JSONObject args) {
                executed.add(cap.sign);
                return ExecutionResult.success(cap, args, "ok", true, "");
            }
            @Override public AgentLoop.ConfirmVerdict onRequireConfirm(Capability cap, JSONObject args, String reason) {
                try { confirmed.add(new JSONObject(args.toString())); } catch (Exception ignored) {}
                return AgentLoop.ConfirmVerdict.DENY;
            }
        }, 0);
        assertEquals("过闸后弹确认（不自动拨号）", 1, confirmed.size());
        assertEquals("138 0013 8000", confirmed.get(0).optString("number"));
        assertEquals("老妈", confirmed.get(0).optString("contact_name"));
        assertTrue("拒绝后没执行", executed.isEmpty());
        assertFalse("整本通讯录没进提示词", llm.seen.toString().contains("小明妈妈") || llm.seen.toString().contains("姑妈"));
    }

    // ---------------------------------------------------------------- 接线（源码断言）

    @Test public void panelAndLoopWiring() throws Exception {
        String fss = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        int hook = fss.indexOf("ContactCallHooks.intercept(this, cap, inv, command)");
        int gate = fss.indexOf("ExecutionGate.evaluate(cap, inv.args, inv.tainted)");
        assertTrue("面板：过闸前先查通讯录", hook > 0 && gate > hook);
        assertTrue(fss.contains("ContactSource.installFor(this)"));
        String loop = src("app/src/com/onesay/assistant/v2/router/AgentLoop.java");
        int pre = loop.indexOf("ContactMatcher.agentPrecheck(name, args)");
        int lgate = loop.indexOf("ExecutionGate.evaluate(cap, args, stepTainted)");
        assertTrue("AgentLoop：过闸前解析", pre > 0 && lgate > pre);
        String hooks = src("app/src/com/onesay/assistant/v2/contacts/ContactCallHooks.java");
        assertTrue("选定后走同一套门禁 → 确认卡", hooks.contains("svc.screenRefExecute(cap, a, query)"));
        assertFalse("从不直接执行", hooks.contains("executeCapability") || hooks.contains("UnifiedExecutor"));
        String source = src("app/src/com/onesay/assistant/v2/contacts/ContactSource.java");
        assertTrue(source.contains("content://com.android.contacts/data/phones"));
        assertTrue(source.contains("READ_CONTACTS"));
        for (String line : source.split("\n")) {
            if (line.contains("Log.")) assertFalse("日志不写联系人名 / 号码: " + line, line.contains("getString") || line.contains("terms)") || line.contains(".name") || line.contains(".number"));
        }
        String view = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java");
        assertEquals("红卡与黄卡都写出呼叫对象", 2, count(view, "CommsRisk.cardNote(cap.sign, args)"));
        String manifest = src("app/AndroidManifest.xml");
        assertTrue("READ_CONTACTS 本来就有（不新增权限）", manifest.contains("android.permission.READ_CONTACTS"));
    }

    static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    static Capability cap(String sign) {
        CapabilityRegistry reg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(reg);
        for (Capability c : reg.listCapabilities()) if (sign.equals(c.sign)) return c;
        throw new AssertionError("no cap " + sign);
    }

    @Test public void commsNeverAlwaysAllowed() throws Exception {
        ConsentPolicy.allowAlways("sms.send");
        assertFalse("sms.send 不进始终允许名单", ConsentPolicy.isAlwaysAllowed("sms.send"));
        JSONObject s = new JSONObject().put("number", "10086").put("text", "x");
        assertEquals(ToolGate.Decision.REQUIRE_CONFIRM, ConsentPolicy.gate(cap("sms.send"), s, false).decision);
        String view = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java");
        // r2/fixes 2：按钮改由 ConsentPolicy.acceptsAlwaysAllow 决定（它包含通信类判断，另加对外发送 / 远程 MCP）
        assertTrue(view.contains("mBtnAlways.setVisibility(com.onesay.assistant.v2.executor.ConsentPolicy.acceptsAlwaysAllow(cap) ? VISIBLE : GONE)"));
        assertFalse(ConsentPolicy.acceptsAlwaysAllow(cap("sms.send")));
    }
}
