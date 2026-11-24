package com.onesay.assistant.v2.selfset;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.applog.AppLogPoints;
import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.ui.ActionChipsView;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.ui.FloatingSheetView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * [settings-diag-1124] 「自身设置」的全部挂钩（写法同 DiagHooks / S2Hooks）。
 *
 * <table>
 *   <tr><td>路由（状态插件档，排在快路径前）</td><td>{@link #route}：「为什么唤醒词用不了」→ 诊断回答 + 按钮；「把快捷开关打开」→ self.settings.set；敏感项 → set_guarded（确认卡）；系统权限 → 跳转按钮</td></tr>
 *   <tr><td>不进 AgentLoop / 不交给识屏</td><td>{@link #claims}</td></tr>
 *   <tr><td>面板派发</td><td>{@link #dispatch}：改设置一律经 {@code screenRefExecute}（门禁 → 确认卡 → 执行器）；敏感项换成带「改前 → 改后」标题的能力</td></tr>
 *   <tr><td>回答上屏 / 执行后</td><td>{@link #onReplyShown} / {@link #onExecuted}：显示按钮（帮我打开 / 去打开系统权限）</td></tr>
 *   <tr><td>AgentLoop</td><td>{@link #appendTools}（问到 OneSay 设置时把四个工具补进来）、{@link #agentPrecheck}（敏感项用普通 set → 退回让模型改用 set_guarded）</td></tr>
 *   <tr><td>执行后端</td><td>{@link #executeLocal}（SelfSettingsBackend，executor=selfset）</td></tr>
 * </table>
 */
public final class SelfSettingsHooks {

    private SelfSettingsHooks() {}

    private static volatile List<SelfSettingsEngine.Chip> sChips = new ArrayList<SelfSettingsEngine.Chip>();
    private static volatile List<SelfSettingsEngine.Chip> sAfterChips = new ArrayList<SelfSettingsEngine.Chip>();

    /** 单测注入的假存储（null = 真机）。 */
    static volatile SelfStore sTestStore;

    static SelfStore store(Context ctx) {
        SelfStore t = sTestStore;
        return t != null ? t : SelfSettingsAndroid.store(ctx);
    }

    // ------------------------------------------------------------------ 路由

    /** null = 不接管。后台线程。 */
    public static Invocation route(Context ctx, String query) {
        try {
            SelfSettingsEngine.Match m = SelfSettingsEngine.match(query);
            if (m == null) return null;
            if (ctx != null && sTestStore == null
                    && com.onesay.assistant.v2.kernel.FeatureSwitch.killFilePresent(ctx, com.onesay.assistant.v2.kernel.plugins.SelfSettingsPlugin.KILL)) return null;
            return decide(store(ctx), m, query);
        } catch (Throwable t) {
            AppLogPoints.selfset("路由出错，回落原流程: " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** 纯逻辑部分（单测直接调）。 */
    static Invocation decide(SelfStore store, SelfSettingsEngine.Match m, String query) {
        if (m.intent == SelfSettingsEngine.Intent.OVERVIEW) {
            SelfSettingsEngine.Reply rp = SelfSettingsEngine.overview(SelfSettingsEngine.snapshot(store));
            AppLogPoints.selfset("整体检查 → 按钮 " + rp.chips.size());
            return reply(rp, query, null);
        }
        SelfSettingsEngine.Row r = SelfSettingsEngine.row(store, m.item);
        if (m.intent == SelfSettingsEngine.Intent.WHY || m.intent == SelfSettingsEngine.Intent.STATUS) {
            SelfCatalog.Item on = SelfCatalog.get("assist.on");
            SelfSettingsEngine.Reply rp = SelfSettingsEngine.diagnose(r, on == null ? null : SelfSettingsEngine.row(store, on));
            AppLogPoints.selfset("诊断 " + m.item.id + " 值=" + m.item.valueText(r.value) + " 缺=" + r.missing.size() + " 按钮=" + rp.chips.size());
            return reply(rp, query, m.item.id);
        }
        Object want = m.intent == SelfSettingsEngine.Intent.SET_CHOICE ? m.choice : Boolean.valueOf(m.intent == SelfSettingsEngine.Intent.ON);
        SelfSettingsEngine.Plan p = SelfSettingsEngine.plan(r, want);
        AppLogPoints.selfset("改 " + m.item.id + " → " + m.item.valueText(SelfSettingsEngine.normalizeValue(m.item, want)) + " 计划=" + p);
        if (p == SelfSettingsEngine.Plan.DIRECT) {
            return new Invocation(SelfSettingsCaps.SIGN_SET, SelfSettingsCaps.args(m.item.id, want), 1.0, Invocation.RouteSource.L0_RULE, query);
        }
        if (p == SelfSettingsEngine.Plan.CONFIRM) {
            return new Invocation(SelfSettingsCaps.SIGN_SET_GUARDED, SelfSettingsCaps.args(m.item.id, want), 1.0, Invocation.RouteSource.L0_RULE, query);
        }
        return reply(SelfSettingsEngine.explainNoChange(p, r, want), query, m.item.id);
    }

    static Invocation reply(SelfSettingsEngine.Reply rp, String query, String itemId) {
        JSONObject a = new JSONObject();
        try {
            a.put("text", rp.text);
            a.put("query", query == null ? "" : query.trim());
            a.put("selfset", itemId == null ? "overview" : itemId);
            JSONArray ch = new JSONArray();
            for (SelfSettingsEngine.Chip c : rp.chips) ch.put(c.label);
            a.put("selfset_chips", ch);
        } catch (Exception ignored) {
        }
        sChips = new ArrayList<SelfSettingsEngine.Chip>(rp.chips);
        // 本地回答：不进 ASK 缓存（设置会变）、不进 AgentLoop
        return new Invocation("ask.reply", a, 1.0, Invocation.RouteSource.L0_RULE, query);
    }

    static List<SelfSettingsEngine.Chip> lastChips() {
        return sChips;
    }

    public static boolean claims(Context ctx, String query) {
        try {
            return SelfSettingsEngine.match(query) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static Capability describe(String sign) {
        if (SelfSettingsCaps.SIGN_READ.equals(sign)) return SelfSettingsCaps.read();
        if (SelfSettingsCaps.SIGN_SET.equals(sign)) return SelfSettingsCaps.set();
        if (SelfSettingsCaps.SIGN_SET_GUARDED.equals(sign)) return SelfSettingsCaps.guarded();
        if (SelfSettingsCaps.SIGN_OPEN.equals(sign)) return SelfSettingsCaps.open();
        return null;
    }

    // ------------------------------------------------------------------ 面板（主线程）

    /**
     * 改设置的派发：普通项照常经门禁；敏感项（不管路由给的是 set 还是 set_guarded）换成「改前 → 改后」标题的 set_guarded，出确认卡。
     * @return true = 已处理
     */
    public static boolean dispatch(FloatingSheetService svc, Invocation inv, String command) {
        if (svc == null || inv == null || inv.sign == null) return false;
        if (!SelfSettingsCaps.SIGN_SET.equals(inv.sign) && !SelfSettingsCaps.SIGN_SET_GUARDED.equals(inv.sign)) return false;
        try {
            JSONObject args = inv.args != null ? inv.args : new JSONObject();
            SelfCatalog.Item it = SelfCatalog.find(args.optString(SelfSettingsCaps.ARG_SETTING, ""));
            Capability cap = capFor(svc, it, args, inv.sign);
            svc.screenRefExecute(cap, normalizedArgs(it, args), command != null ? command : inv.originalQuery);
            return true;
        } catch (Throwable t) {
            AppLogPoints.selfset("派发出错: " + t.getClass().getSimpleName());
            return false;
        }
    }

    static Capability capFor(Context ctx, SelfCatalog.Item it, JSONObject args, String sign) {
        boolean guarded = SelfSettingsCaps.SIGN_SET_GUARDED.equals(sign) || (it != null && it.sensitive);
        if (!guarded) return SelfSettingsCaps.set();
        SelfSettingsEngine.Row r = it == null ? null : SelfSettingsEngine.row(store(ctx), it);
        return SelfSettingsCaps.guarded(SelfSettingsEngine.changeSummary(r, args.opt(SelfSettingsCaps.ARG_VALUE)));
    }

    /** 参数规范成「setting=清单 id、value=开/关/选项」（模型可能写中文名），令牌按规范后的参数做摘要。 */
    static JSONObject normalizedArgs(SelfCatalog.Item it, JSONObject args) {
        if (it == null) return args;
        Object w = SelfSettingsEngine.normalizeValue(it, args.opt(SelfSettingsCaps.ARG_VALUE));
        return SelfSettingsCaps.args(it.id, w != null ? w : args.opt(SelfSettingsCaps.ARG_VALUE));
    }

    public static void onReplyShown(final FloatingSheetService svc, Invocation inv) {
        try {
            if (svc == null || inv == null || inv.args == null || !inv.args.has("selfset")) return;
            showChips(svc, sChips, inv.args.optString("query", ""));
        } catch (Throwable ignored) {
        }
    }

    public static void onExecuted(FloatingSheetService svc, Capability cap, JSONObject args, ExecutionResult r) {
        try {
            if (svc == null || cap == null || !SelfSettingsCaps.isSelfSign(cap.sign)) return;
            List<SelfSettingsEngine.Chip> chips = sAfterChips;
            sAfterChips = new ArrayList<SelfSettingsEngine.Chip>();
            if (chips.isEmpty()) return;
            showChips(svc, chips, "");
        } catch (Throwable ignored) {
        }
    }

    static void showChips(final FloatingSheetService svc, final List<SelfSettingsEngine.Chip> chips, final String query) {
        ActionChipsView v = ensureChipsView(svc);
        if (v == null) return;
        if (chips == null || chips.isEmpty()) {
            v.hideChips();
            return;
        }
        List<String> labels = new ArrayList<String>();
        for (SelfSettingsEngine.Chip c : chips) labels.add(c.label);
        v.setListener(new ActionChipsView.Listener() {
            @Override public void onChipClicked(int index) {
                if (index < 0 || index >= chips.size()) return;
                try {
                    com.onesay.assistant.v2.wake.WakeService.cancelCaptureFromUi();
                } catch (Throwable ignored) {
                }
                hideChips(svc);
                runChip(svc, chips.get(index), query);
            }
        });
        v.showChips(labels);
    }

    static void runChip(FloatingSheetService svc, SelfSettingsEngine.Chip c, String query) {
        AppLogPoints.selfset("按钮 " + c.label);
        if (c.kind == SelfSettingsEngine.Chip.Kind.OPEN) {
            boolean ok = SelfSettingsAndroid.open(svc, c.target);
            if (!ok) svc.showLocalReply(c.label, "没能打开" + (c.target == null ? "这个页面" : c.target.zh) + "，请到系统设置里找一下。", "OneSay 设置");
            return;
        }
        SelfCatalog.Item it = SelfCatalog.get(c.itemId);
        JSONObject args = SelfSettingsCaps.args(c.itemId, c.value);
        svc.screenRefExecute(capFor(svc, it, args, SelfSettingsCaps.SIGN_SET), args, query.length() > 0 ? query : c.label);
    }

    private static ActionChipsView ensureChipsView(FloatingSheetService svc) {
        FloatingSheetView sheet = svc.getSheetView();
        if (sheet == null) return null;
        View existing = sheet.findViewWithTag(ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof ActionChipsView) return (ActionChipsView) existing;
        ActionChipsView v = new ActionChipsView(svc);
        int idx = Math.min(2, sheet.getChildCount());
        sheet.addView(v, idx, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private static void hideChips(FloatingSheetService svc) {
        FloatingSheetView sheet = svc != null ? svc.getSheetView() : null;
        if (sheet == null) return;
        View existing = sheet.findViewWithTag(ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof ActionChipsView) ((ActionChipsView) existing).hideChips();
    }

    // ------------------------------------------------------------------ AgentLoop

    static final String[] TOOL_HINTS = {"设置", "onesay", "唤起", "唤醒", "开关", "权限", "为什么", "用不了", "打开", "关掉", "关闭", "开启"};

    /** 问到 OneSay 设置（或提到清单里的项）时，把四个工具补进本轮（检索没捞到时）。 */
    public static void appendTools(Context ctx, JSONArray tools, String query) {
        try {
            if (tools == null || query == null) return;
            String q = query.toLowerCase(java.util.Locale.ROOT);
            boolean relevant = SelfSettingsEngine.match(query) != null;
            if (!relevant) {
                boolean hint = false;
                for (String h : TOOL_HINTS) if (q.contains(h)) hint = true;
                if (hint) {
                    for (SelfCatalog.Item it : SelfCatalog.all()) {
                        for (String a : it.aliases) if (q.contains(a.toLowerCase(java.util.Locale.ROOT))) relevant = true;
                    }
                    if (q.contains("onesay") || q.contains("你的设置") || q.contains("自己的设置")) relevant = true;
                }
            }
            if (!relevant) return;
            java.util.Set<String> have = new java.util.HashSet<String>();
            for (int i = 0; i < tools.length(); i++) {
                JSONObject t = tools.optJSONObject(i);
                JSONObject fn = t == null ? null : t.optJSONObject("function");
                if (fn != null) have.add(fn.optString("name", ""));
            }
            List<Capability> add = new ArrayList<Capability>();
            for (Capability c : new Capability[]{SelfSettingsCaps.read(), SelfSettingsCaps.set(), SelfSettingsCaps.guarded(), SelfSettingsCaps.open()}) {
                if (!have.contains(CapabilityRegistry.signToToolName(c.sign))) add.add(c);
            }
            if (add.isEmpty()) return;
            JSONArray extra = CapabilityRegistry.getInstance().exportToolsSchema(add);
            for (int i = 0; i < extra.length(); i++) tools.put(extra.get(i));
        } catch (Throwable ignored) {
        }
    }

    public static Capability describeForAgent(Context ctx, String name) {
        return describe(name == null ? null : CapabilityRegistry.toolNameToSign(name));
    }

    /** 敏感项不能走普通 set（模型要改用 set_guarded，那条会弹确认卡）。null = 放行。 */
    public static String agentPrecheck(Context ctx, String name, JSONObject args) {
        if (name == null) return null;
        String sign = name.indexOf("__") >= 0 ? CapabilityRegistry.toolNameToSign(name) : name;
        if (!SelfSettingsCaps.SIGN_SET.equals(sign)) return null;
        SelfCatalog.Item it = SelfCatalog.find(args == null ? "" : args.optString(SelfSettingsCaps.ARG_SETTING, ""));
        if (it == null || !it.sensitive) return null;
        AppLogPoints.selfset("模型用普通 set 改敏感项 " + it.id + " → 退回改用 set_guarded");
        return "{\"ok\":false,\"code\":\"NEEDS_CONFIRM\",\"note\":\"「" + it.title + "」是要用户确认的设置，这次没改。改用工具 "
                + CapabilityRegistry.signToToolName(SelfSettingsCaps.SIGN_SET_GUARDED) + "（同样的参数），它会弹确认卡给用户。\"}";
    }

    // ------------------------------------------------------------------ 执行（门禁已放行 / 令牌已批准）

    public static ExecutionResult executeLocal(Context ctx, Capability cap, JSONObject args) {
        if (cap == null) return null;
        JSONObject a = args != null ? args : new JSONObject();
        String key = a.optString(SelfSettingsCaps.ARG_SETTING, "").trim();
        SelfStore st = store(ctx);
        try {
            if (SelfSettingsCaps.SIGN_READ.equals(cap.sign)) {
                if (key.length() == 0) {
                    List<SelfSettingsEngine.Row> rows = SelfSettingsEngine.snapshot(st);
                    SelfSettingsEngine.Reply rp = SelfSettingsEngine.overview(rows);
                    AppLogPoints.selfset("读清单（全部）");
                    return ExecutionResult.success(cap, a, rp.text, ExecutionResult.Verification.VERIFIED, SelfSettingsEngine.toJson(rows).toString());
                }
                SelfCatalog.Item it = SelfCatalog.find(key);
                if (it == null) return ExecutionResult.failed(ErrorCode.SCHEMA_INVALID, "OneSay 的设置里没有「" + key + "」这一项", "unknown setting", cap, a);
                SelfSettingsEngine.Row r = SelfSettingsEngine.row(st, it);
                SelfCatalog.Item on = SelfCatalog.get("assist.on");
                SelfSettingsEngine.Reply rp = SelfSettingsEngine.diagnose(r, on == null ? null : SelfSettingsEngine.row(st, on));
                List<SelfSettingsEngine.Row> one = new ArrayList<SelfSettingsEngine.Row>();
                one.add(r);
                AppLogPoints.selfset("读 " + it.id);
                return ExecutionResult.success(cap, a, rp.text, ExecutionResult.Verification.VERIFIED, SelfSettingsEngine.toJson(one).toString());
            }
            if (SelfSettingsCaps.SIGN_OPEN.equals(cap.sign)) {
                SelfCatalog.Item it = SelfCatalog.find(key);
                SelfCatalog.Target t = it != null ? it.page : null;
                if (t == null) {
                    SelfCatalog.Need n = SelfCatalog.need(key);
                    if (n != null) t = n.target;
                }
                if (t == null || t == SelfCatalog.Target.NONE) {
                    return ExecutionResult.failed(ErrorCode.SCHEMA_INVALID, "不知道「" + key + "」在哪一页", "no target", cap, a);
                }
                boolean ok = SelfSettingsAndroid.open(ctx, t);
                AppLogPoints.selfset("打开 " + t.name() + (ok ? " 成功" : " 失败"));
                return ok ? ExecutionResult.success(cap, a, "已打开" + t.zh + "。", ExecutionResult.Verification.UNVERIFIED, t.name())
                        : ExecutionResult.failed(ErrorCode.EXEC_FAILED, "没能打开" + t.zh + "，请到系统设置里找一下", "open failed", cap, a);
            }
            if (SelfSettingsCaps.SIGN_SET.equals(cap.sign) || SelfSettingsCaps.SIGN_SET_GUARDED.equals(cap.sign)) {
                SelfCatalog.Item it = SelfCatalog.find(key);
                if (it == null) return ExecutionResult.failed(ErrorCode.SCHEMA_INVALID, "OneSay 的设置里没有「" + key + "」这一项", "unknown setting", cap, a);
                if (it.sensitive && !SelfSettingsCaps.SIGN_SET_GUARDED.equals(cap.sign)) {   // 防御：没经确认卡的敏感项不改
                    return ExecutionResult.failed(ErrorCode.RISK_GATED, "「" + it.title + "」要你确认后才能改，这次没动", "needs set_guarded",
                            "改用 " + CapabilityRegistry.signToToolName(SelfSettingsCaps.SIGN_SET_GUARDED) + "（会弹确认卡）", cap, a);
                }
                SelfSettingsEngine.Outcome o = SelfSettingsEngine.apply(st, it.id, a.opt(SelfSettingsCaps.ARG_VALUE));
                sAfterChips = new ArrayList<SelfSettingsEngine.Chip>(o.chips);
                AppLogPoints.selfset("改 " + it.id + " " + it.valueText(o.before) + " → " + it.valueText(o.after) + (o.ok ? " 回读一致" : " 没改成"));
                if (!o.ok) return ExecutionResult.failed(ErrorCode.VERIFY_FAILED, o.text, "readback " + it.valueText(o.after), cap, a);
                return ExecutionResult.success(cap, a, o.text, o.verified ? ExecutionResult.Verification.VERIFIED : ExecutionResult.Verification.UNVERIFIED,
                        it.title + "=" + it.valueText(o.after));
            }
        } catch (Throwable t) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "读写 OneSay 设置时出错了", t.getClass().getSimpleName(), cap, a);
        }
        return null;
    }
}
