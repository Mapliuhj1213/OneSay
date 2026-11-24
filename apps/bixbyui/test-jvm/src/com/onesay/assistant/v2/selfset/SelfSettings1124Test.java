package com.onesay.assistant.v2.selfset;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ConfirmFacts;
import com.onesay.assistant.v2.executor.ConsentPolicy;
import com.onesay.assistant.v2.executor.ExecutionGate;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.platform.Tri;
import com.onesay.assistant.v2.router.Invocation;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * [settings-diag-1124] 自身设置：普通项直接改 + 回读核对；敏感项走确认、普通 set 被拒、不给「始终允许」；
 * 系统权限只给跳转；诊断回答说清缺什么、去哪开；别的 App / 系统功能的话不被抢；确认卡不露内部 id。
 */
public class SelfSettings1124Test {

    /** 假存储：值 / 前提 / 关停 / 「写了但没生效」。 */
    static final class FakeStore implements SelfStore {
        final Map<String, Object> values = new HashMap<String, Object>();
        final Map<String, Tri> needs = new HashMap<String, Tri>();
        final Set<String> killed = new HashSet<String>();
        final Set<String> stuck = new HashSet<String>();
        int writes;

        @Override public Object read(String itemId) {
            return values.get(itemId);
        }

        @Override public boolean write(String itemId, Object value) {
            writes++;
            if (!stuck.contains(itemId)) values.put(itemId, value);
            return true;
        }

        @Override public Tri need(String needId) {
            Tri t = needs.get(needId);
            return t == null ? Tri.YES : t;
        }

        @Override public boolean killed(String itemId) {
            return killed.contains(itemId);
        }
    }

    private FakeStore st;

    @Before public void setUp() {
        st = new FakeStore();
        for (SelfCatalog.Item it : SelfCatalog.all()) {
            if (it.kind == SelfCatalog.Kind.CHOICE) st.values.put(it.id, it.choices.get(0));
            else st.values.put(it.id, Boolean.FALSE);
        }
        st.values.put("assist.on", Boolean.TRUE);
        SelfSettingsHooks.sTestStore = st;
    }

    @After public void tearDown() {
        SelfSettingsHooks.sTestStore = null;
    }

    private static Invocation route(String q) {
        return SelfSettingsHooks.route(null, q);
    }

    private static String text(Invocation inv) {
        return inv.args.optString("text", "");
    }

    // ------------------------------------------------------------------ 普通项：直接改 + 回读

    @Test public void normalToggleChangesDirectlyAndReadsBack() {
        Invocation inv = route("把快捷开关打开");
        assertNotNull(inv);
        assertEquals(SelfSettingsCaps.SIGN_SET, inv.sign);
        assertEquals("entry.qs_tile", inv.args.optString(SelfSettingsCaps.ARG_SETTING));
        ExecutionResult r = SelfSettingsHooks.executeLocal(null, SelfSettingsCaps.set(), inv.args);
        assertTrue(r.userMessage, r.ok);
        assertTrue("回读核对过", r.verified);
        assertEquals(Boolean.TRUE, st.values.get("entry.qs_tile"));
        assertTrue(r.userMessage, r.userMessage.contains("读回来核对过了"));
        assertTrue("开着还要知道的一句", r.userMessage.contains("拖进快捷开关"));
    }

    @Test public void normalToggleOff() {
        st.values.put("plug.island", Boolean.TRUE);
        Invocation inv = route("关掉灵动岛");
        assertNotNull(inv);
        assertEquals(SelfSettingsCaps.SIGN_SET, inv.sign);
        ExecutionResult r = SelfSettingsHooks.executeLocal(null, SelfSettingsCaps.set(), inv.args);
        assertTrue(r.ok);
        assertEquals(Boolean.FALSE, st.values.get("plug.island"));
    }

    @Test public void choiceItemChanges() {
        Invocation inv = route("把思考模式改成全部");
        assertNotNull(inv);
        assertEquals(SelfSettingsCaps.SIGN_SET, inv.sign);
        ExecutionResult r = SelfSettingsHooks.executeLocal(null, SelfSettingsCaps.set(), inv.args);
        assertTrue(r.userMessage, r.ok);
        assertEquals("all", st.values.get("think.mode"));
        assertTrue(r.userMessage.contains("全部"));
    }

    @Test public void readbackMismatchIsReportedHonestly() {
        st.stuck.add("entry.widget");
        SelfSettingsEngine.Outcome o = SelfSettingsEngine.apply(st, "entry.widget", Boolean.TRUE);
        assertFalse(o.ok);
        assertFalse(o.verified);
        assertTrue(o.text, o.text.contains("没改成"));
    }

    @Test public void alreadyOnDoesNotWrite() {
        st.values.put("entry.widget", Boolean.TRUE);
        Invocation inv = route("把桌面小部件打开");
        assertNotNull(inv);
        assertEquals("ask.reply", inv.sign);
        assertTrue(text(inv), text(inv).contains("本来就是开"));
        assertEquals(0, st.writes);
    }

    @Test public void killedItemIsNotChanged() {
        st.killed.add("plug.memory");
        SelfSettingsEngine.Outcome o = SelfSettingsEngine.apply(st, "plug.memory", Boolean.TRUE);
        assertFalse(o.ok);
        assertEquals(0, st.writes);
    }

    @Test public void assistantMasterSwitchNotChangeableInChat() {
        Invocation inv = route("把总开关关掉");
        assertNotNull(inv);
        assertEquals("ask.reply", inv.sign);
        assertTrue(text(inv), text(inv).contains("不在聊天里改"));
        assertEquals(0, st.writes);
    }

    // ------------------------------------------------------------------ 敏感项：确认、普通 set 被拒、不给始终允许

    @Test public void sensitiveItemsGoThroughConfirm() {
        String[][] cases = {
                {"把外部唤起打开", "entry.intent"},
                {"把自动执行打开", "plug.phone"},
                {"关掉隐私保护", "privacy.redact"},
                {"把Shizuku特权打开", "exec.shizuku"},
        };
        st.values.put("privacy.redact", Boolean.TRUE);
        for (String[] c : cases) {
            Invocation inv = route(c[0]);
            assertNotNull(c[0], inv);
            assertEquals(c[0], SelfSettingsCaps.SIGN_SET_GUARDED, inv.sign);
            assertEquals(c[0], c[1], inv.args.optString(SelfSettingsCaps.ARG_SETTING));
            assertTrue(c[1] + " 要标成敏感", SelfCatalog.get(c[1]).sensitive);
        }
        assertEquals("路由阶段一个都没写", 0, st.writes);
    }

    @Test public void plainSetOnSensitiveItemIsRefused() {
        for (String id : new String[]{"entry.intent", "plug.phone", "privacy.redact", "exec.shizuku"}) {
            ExecutionResult r = SelfSettingsHooks.executeLocal(null, SelfSettingsCaps.set(), SelfSettingsCaps.args(id, Boolean.TRUE));
            assertFalse(id, r.ok);
            assertTrue(id + " " + r.userMessage, r.userMessage.contains("确认"));
            assertNotNull("模型那条路也退回", SelfSettingsHooks.agentPrecheck(null, SelfSettingsCaps.SIGN_SET, SelfSettingsCaps.args(id, Boolean.TRUE)));
        }
        assertEquals(0, st.writes);
        assertNull("普通项不拦", SelfSettingsHooks.agentPrecheck(null, SelfSettingsCaps.SIGN_SET, SelfSettingsCaps.args("entry.qs_tile", Boolean.TRUE)));
    }

    @Test public void guardedSetAsksEveryTimeWithoutAlwaysAllow() {
        Capability g = SelfSettingsCaps.guarded();
        assertTrue("要确认", ExecutionGate.evaluate(g, SelfSettingsCaps.args("entry.intent", Boolean.TRUE), false).needsConfirm());
        assertFalse("不给「始终允许」", ConsentPolicy.offersAlwaysAllow(g, false));
        assertFalse(ConsentPolicy.acceptsAlwaysAllow(g));
        Capability titled = SelfSettingsCaps.guarded("把「外部 App 唤起」从 关 改成 开");
        assertFalse(ConsentPolicy.offersAlwaysAllow(titled, false));
        // 确认后（令牌已批）才真的改
        ExecutionResult r = SelfSettingsHooks.executeLocal(null, g, SelfSettingsCaps.args("entry.intent", Boolean.TRUE));
        assertTrue(r.userMessage, r.ok);
        assertEquals(Boolean.TRUE, st.values.get("entry.intent"));
    }

    @Test public void paymentRulesAreNotInCatalog() {
        for (String q : new String[]{"付款确认", "支付确认", "付款前确认", "免密支付"}) assertNull(q, SelfCatalog.find(q));
        for (String q : new String[]{"关掉付款确认", "把支付确认关了", "付款不用再问我了"}) {
            assertNull(q, SelfSettingsEngine.match(q));
            assertFalse(q, SelfSettingsHooks.claims(null, q));
        }
    }

    // ------------------------------------------------------------------ 系统权限：只给跳转

    @Test public void systemPermissionOnlyGivesJump() {
        Invocation inv = route("把悬浮窗权限打开");
        assertNotNull(inv);
        assertEquals("ask.reply", inv.sign);
        assertTrue(text(inv), text(inv).contains("系统权限"));
        java.util.List<SelfSettingsEngine.Chip> chips = SelfSettingsHooks.lastChips();
        assertEquals(1, chips.size());
        assertEquals(SelfSettingsEngine.Chip.Kind.OPEN, chips.get(0).kind);
        assertEquals(SelfCatalog.Target.SYS_OVERLAY, chips.get(0).target);
        assertEquals(0, st.writes);
        ExecutionResult r = SelfSettingsHooks.executeLocal(null, SelfSettingsCaps.set(), SelfSettingsCaps.args(SelfCatalog.N_OVERLAY, Boolean.TRUE));
        assertFalse("set 也改不了系统权限", r.ok);
        assertEquals(0, st.writes);
        for (SelfCatalog.Item it : SelfCatalog.all()) {
            if (it.kind != SelfCatalog.Kind.PERMISSION) continue;
            assertFalse(it.id, it.chatChangeable);
            assertFalse(it.id, it.page.inApp());
        }
    }

    // ------------------------------------------------------------------ 诊断回答

    @Test public void wakeWordWhyExplainsWhatIsMissingAndWhere() {
        st.values.put("entry.wake_word", Boolean.TRUE);
        st.needs.put(SelfCatalog.N_MIC, Tri.NO);
        st.needs.put(SelfCatalog.N_WAKE_WORDS, Tri.NO);
        Invocation inv = route("唤醒词为什么用不了");
        assertNotNull(inv);
        assertEquals("ask.reply", inv.sign);
        String t = text(inv);
        assertTrue(t, t.contains("麦克风权限"));
        assertTrue(t, t.contains("应用信息"));
        assertTrue(t, t.contains("我的唤醒词"));
        java.util.List<SelfSettingsEngine.Chip> chips = SelfSettingsHooks.lastChips();
        assertTrue(chips.toString(), chips.size() >= 2);
        for (SelfSettingsEngine.Chip c : chips) assertEquals(SelfSettingsEngine.Chip.Kind.OPEN, c.kind);
    }

    @Test public void offItemWhyOffersTurnOn() {
        Invocation inv = route("为什么按键唤醒没反应");
        assertNotNull(inv);
        assertTrue(text(inv), text(inv).contains("关着"));
        java.util.List<SelfSettingsEngine.Chip> chips = SelfSettingsHooks.lastChips();
        assertEquals(SelfSettingsEngine.Chip.Kind.SET, chips.get(0).kind);
        assertEquals("帮我打开", chips.get(0).label);
    }

    @Test public void deviceCannotDoItSaysSo() {
        st.values.put("entry.wake_word", Boolean.TRUE);
        st.needs.put(SelfCatalog.N_ARM64, Tri.NO);
        Invocation inv = route("唤醒词为什么用不了");
        assertTrue(text(inv), text(inv).contains("补不上"));
        st.values.put("entry.wake_word", Boolean.FALSE);
        assertEquals(SelfSettingsEngine.Plan.BLOCKED, SelfSettingsEngine.plan(SelfSettingsEngine.row(st, SelfCatalog.get("entry.wake_word")), Boolean.TRUE));
    }

    @Test public void pausedAssistantIsMentioned() {
        st.values.put("assist.on", Boolean.FALSE);
        st.values.put("entry.key_wake", Boolean.TRUE);
        Invocation inv = route("按键唤醒为什么用不了");
        assertTrue(text(inv), text(inv).contains("暂停"));
    }

    @Test public void overviewListsProblems() {
        st.values.put("plug.island", Boolean.TRUE);
        st.needs.put(SelfCatalog.N_OVERLAY, Tri.NO);
        Invocation inv = route("检查一下你的设置");
        assertNotNull(inv);
        assertTrue(text(inv), text(inv).contains("灵动岛"));
        assertTrue(text(inv), text(inv).contains("悬浮窗权限"));
        st.needs.clear();
        Invocation ok = route("检查一下你的设置");
        assertTrue(text(ok), text(ok).contains("没发现问题"));
    }

    @Test public void statusQuestion() {
        st.values.put("entry.qs_tile", Boolean.TRUE);
        Invocation inv = route("快捷开关开着吗");
        assertNotNull(inv);
        assertEquals("ask.reply", inv.sign);
        assertTrue(text(inv), text(inv).contains("开着"));
        assertEquals(0, st.writes);
    }

    // ------------------------------------------------------------------ 不误抢

    @Test public void doesNotGrabOtherAppsOrSystemFeatures() {
        String[] qs = {
                "打开微信", "把蓝牙打开", "打开设置看版本", "打开设置", "打开蓝牙", "关掉蓝牙", "打开手电筒", "把WiFi关了",
                "打开相机", "打开Shizuku", "打开抖音", "给妈妈打电话", "发短信给张三", "明天早上七点叫我",
                "打开微信的快捷方式", "微信的通知权限打开", "关掉微信的电池优化", "为什么微信的快捷方式用不了",
                "系统快捷开关里的手电筒打开", "把抖音的悬浮窗权限打开", "帮我记住明天开会", "我记忆力不好",
                "打开设置里的蓝牙", "看看今天天气", "帮我付款", "打开支付宝付款码",
        };
        for (String q : qs) {
            assertNull(q + " → " + SelfSettingsEngine.match(q), SelfSettingsEngine.match(q));
            assertFalse(q, SelfSettingsHooks.claims(null, q));
            assertNull(q, route(q));
        }
        assertEquals(0, st.writes);
    }

    @Test public void tooLongSentenceIsNotGrabbed() {
        assertNull(SelfSettingsEngine.match("我想问一下就是那个我昨天在用的时候发现快捷开关好像有点问题你能不能帮我看看到底怎么回事啊谢谢你"));
    }

    // ------------------------------------------------------------------ 确认卡不露内部 id

    @Test public void confirmCardShowsNoInternalIds() throws Exception {
        for (SelfCatalog.Item it : SelfCatalog.all()) {
            if (it.kind == SelfCatalog.Kind.PERMISSION) continue;
            Object want = it.kind == SelfCatalog.Kind.CHOICE ? it.choices.get(it.choices.size() - 1) : Boolean.TRUE;
            JSONObject a = SelfSettingsCaps.args(it.id, want);
            String card = ConfirmFacts.lines(SelfSettingsCaps.guarded(), a);
            assertNotNull(it.id, card);
            String summary = SelfSettingsEngine.changeSummary(SelfSettingsEngine.row(st, it), want);
            for (String s : new String[]{card, summary}) {
                assertFalse(it.id + " 露了内部 id: " + s, s.contains(it.id));
                assertFalse(s, s.contains("self.settings"));
                assertFalse(s, s.contains("selfset"));
                assertFalse(s, s.contains("true") || s.contains("false"));
                assertTrue(s, s.contains(it.title));
            }
        }
        String card = ConfirmFacts.lines(SelfSettingsCaps.guarded(), SelfSettingsCaps.args("entry.intent", Boolean.TRUE));
        assertTrue(card, card.contains("改成：开"));
        assertTrue(card, card.contains("先问你"));
    }

    // ------------------------------------------------------------------ 用户可见文字

    @Test public void userVisibleTextIsPlain() {
        String[] banned = {"Bixby", "三星", "DeX", "小布", "Intent", "intent", "com.onesay", "啥", "咋", "呗"};
        StringBuilder all = new StringBuilder();
        for (SelfCatalog.Item it : SelfCatalog.all()) {
            all.append(it.title).append('\n').append(it.where).append('\n');
            if (it.onHint != null) all.append(it.onHint).append('\n');
            for (String l : it.choiceLabels) all.append(l).append('\n');
            SelfSettingsEngine.Row r = SelfSettingsEngine.row(st, it);
            all.append(SelfSettingsEngine.diagnose(r, null).text).append('\n');
            for (SelfSettingsEngine.Plan p : SelfSettingsEngine.Plan.values()) all.append(SelfSettingsEngine.explainNoChange(p, r, Boolean.TRUE).text).append('\n');
        }
        for (SelfCatalog.Need n : SelfCatalog.NEEDS.values()) all.append(n.title).append(n.how).append('\n');
        for (SelfCatalog.Target t : SelfCatalog.Target.values()) if (t.zh != null) all.append(t.zh).append('\n');
        String s = all.toString();
        for (String b : banned) assertFalse("用户可见文字出现「" + b + "」", s.contains(b));
    }

    @Test public void catalogIsConsistent() {
        Set<String> ids = new HashSet<String>();
        Set<String> aliases = new HashSet<String>();
        assertTrue("清单约 30 项", SelfCatalog.all().size() >= 25);
        for (SelfCatalog.Item it : SelfCatalog.all()) {
            assertTrue("重复 id " + it.id, ids.add(it.id));
            for (String n : it.needs) assertNotNull(it.id + " 前提 " + n, SelfCatalog.need(n));
            for (String a : it.aliases) assertTrue("别名重复 " + a, aliases.add(SelfCatalog.squash(a)));
            assertSame(it, SelfCatalog.find(it.title));
            if (it.kind == SelfCatalog.Kind.CHOICE) assertEquals(it.choices.size(), it.choiceLabels.size());
        }
    }
}
