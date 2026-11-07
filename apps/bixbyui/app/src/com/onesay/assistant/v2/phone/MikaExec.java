package com.onesay.assistant.v2.phone;

import com.onesay.assistant.plug.action.core.OutwardPacing;
import com.onesay.assistant.plug.action.core.RiskGate;
import com.onesay.assistant.plug.guard.core.OperationGuards;
import com.onesay.assistant.plug.guard.core.StuckAdvice;
import com.onesay.assistant.plug.screen.core.ActionEffect;
import com.onesay.assistant.plug.screen.core.RefStabilizer;
import com.onesay.assistant.plug.screen.core.ScreenJson;
import com.onesay.assistant.plug.screen.core.ScreenReader;
import com.onesay.assistant.plug.screen.core.ScreenSnapshot;
import com.onesay.assistant.plug.screen.core.TreeQuality;
import com.onesay.assistant.plug.screen.core.UiElement;
import com.onesay.assistant.plug.screen.core.UiNode;
import com.onesay.assistant.plug.screen.core.UiWindow;
import com.onesay.assistant.v2.fastscreen.FsTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * ★ mika-plug：PhoneAgent / FastScreenDriver 与 Mika 执行层插件核心之间的适配（纯 Java，JVM 可测）。
 *
 * <ul>
 *   <li>{@link Flags}：各插件此刻开没开（宿主从 MikaPlugs 注册表取；全关 = 原行为，零副作用）；</li>
 *   <li>{@link #windows}：助手的 FsTree → 后端无关的 UiWindow / UiNode（src = 助手树下标）；</li>
 *   <li>{@link #build}：读屏 → ScreenReader（@eN、0–999、隐私、剪枝）→ RefStabilizer（稳定编号）→ UiTree（编号 = @eN 的数字）；</li>
 *   <li>{@link #afterStep}：一步之后的 ActionEffect + OperationGuards 阶梯 + StuckAdvice，守卫叫停时给出 StopRules 的说法；</li>
 *   <li>{@link #consent}：SensitiveGuard 之外的 RiskGate（屏上有金额的提交按付款问）；{@link #paceSite} OutwardPacing。</li>
 * </ul>
 */
public final class MikaExec {

    /** 插件开关快照（一个任务开始时取一次）。 */
    public static final class Flags {
        public boolean screen, refs, changewait, actchain, effect, guard, actgate, pacing, dosteps, allWindows, a11y;
        public int maxChars = ScreenJson.DEFAULT_MAX_CHARS;
        /** 读屏跳过的包前缀（自家面板 / 岛 / 护罩）。 */
        public String selfPrefix = "com.onesay.assistant";

        public boolean any() {
            return screen || changewait || actchain || effect || guard || actgate || pacing || dosteps;
        }

        @Override public String toString() {
            return (screen ? "screen " : "") + (refs ? "refs " : "") + (changewait ? "changewait " : "") + (actchain ? "actchain " : "")
                    + (effect ? "effect " : "") + (guard ? "guard " : "") + (actgate ? "actgate " : "") + (pacing ? "pacing " : "")
                    + (dosteps ? "dosteps " : "") + (a11y ? "a11y(B2)" : "");
        }
    }

    /** 进程内共用一份（同一 App 里连着发几条要跨任务计间隔）。 */
    static final OutwardPacing PACING = new OutwardPacing(new Random());

    public final Flags f;
    final OperationGuards guards = new OperationGuards(null);
    final StuckAdvice advice = new StuckAdvice();
    /** 守卫叫停时给用户看的原因（StopRules）；null = 接着做。 */
    public String stopReason;
    /** 统计（报告 / dump 用）。 */
    public int effectsNoEffect, effectsDone, guardWarns, consentAsks;

    public MikaExec(Flags f) {
        this.f = f == null ? new Flags() : f;
    }

    // ------------------------------------------------------------------ 读屏

    /** FsTree → UiWindow 列表（多窗口读法按窗口表分根；旧助手 / 单根读法 = 一个 application 窗口）。 */
    public static List<UiWindow> windows(FsTree t) {
        List<UiWindow> out = new ArrayList<UiWindow>();
        if (t == null) return out;
        int n = t.nodes.size();
        UiNode[] nodes = new UiNode[n];
        for (int i = 0; i < n; i++) {
            FsTree.Node x = t.nodes.get(i);
            UiNode u = UiNode.of(x.cls, x.text, x.l, x.t, x.r, x.b);
            u.desc = x.desc;
            u.rid = x.rid;
            u.hint = x.hint == null ? "" : x.hint;
            u.visible = x.flags == 0 || x.has(FsTree.F_VISIBLE) || x.parent < 0;
            u.clickable = x.has(FsTree.F_CLICK);
            u.longClickable = x.has(FsTree.F_LONG);
            u.editable = x.has(FsTree.F_EDIT);
            u.scrollable = x.has(FsTree.F_SCROLL);
            u.checkable = x.has(FsTree.F_CHECKABLE);
            u.checked = x.has(FsTree.F_CHECKED);
            u.selected = x.has(FsTree.F_SELECTED);
            u.focused = x.has(FsTree.F_FOCUSED);
            u.enabled = x.has(FsTree.F_ENABLED) || x.flags == 0;
            u.password = x.has(FsTree.F_PASSWORD);
            u.showingHint = x.has(FsTree.F_HINT_SHOWING);
            u.collection = x.has(FsTree.F_LIST);
            u.src = i;
            nodes[i] = u;
        }
        for (int i = 0; i < n; i++) {
            int p = t.nodes.get(i).parent;
            if (p >= 0 && p < n && p != i) nodes[p].add(nodes[i]);
        }
        if (t.windows.isEmpty()) {
            for (int i = 0; i < n; i++) if (t.nodes.get(i).parent < 0) out.add(new UiWindow(i, UiWindow.APPLICATION, 0, t.pkg, nodes[i]));
            return out;
        }
        for (int i = 0; i < n; i++) {
            if (t.nodes.get(i).parent >= 0) continue;
            int wi = t.nodes.get(i).win;
            FsTree.Win w = wi >= 0 && wi < t.windows.size() ? t.windows.get(wi) : null;
            out.add(new UiWindow(w == null ? i : w.id, w == null ? UiWindow.APPLICATION : w.type, w == null ? 0 : w.layer,
                    w == null ? t.pkg : w.pkg, nodes[i]));
        }
        return out;
    }

    /** 读屏 → 模型编号下的 UiTree。ids 一个任务一份（里面放 RefStabilizer）。 */
    public UiTree build(FsTree t, UiTree.Ids ids, int screenW, int screenH) {
        int w = screenW > 0 ? screenW : 1080, h = screenH > 0 ? screenH : 2220;
        List<UiWindow> ws = windows(t);
        ScreenSnapshot port = ScreenReader.read("port", ws, w, h, new ScreenReader.Options().skip(f.selfPrefix));
        port.truncated = t.truncated;
        port.ime = t.ime;
        port.activity = t.activity == null ? "" : t.activity;
        ScreenSnapshot model;
        if (f.refs) {
            if (ids.mikaRefs == null) ids.mikaRefs = new RefStabilizer("s");
            model = ids.mikaRefs.next(port).screen;
        } else {
            model = port;
        }
        UiTree u = new UiTree();
        u.pkg = port.packageName != null ? port.packageName : t.pkg;
        u.activity = t.activity;
        u.truncated = t.truncated;
        u.ime = t.ime;
        u.readMs = t.ms;
        u.screenW = w;
        u.screenH = h;
        u.blindArea = port.blind ? 0.35f : 0f;
        for (UiElement e : model.elements) {
            UiTree.El el = new UiTree.El();
            el.id = e.refNo();
            el.label = e.name;
            el.src = e.src;
            FsTree.Node x = e.src >= 0 && e.src < t.nodes.size() ? t.nodes.get(e.src) : null;
            el.rid = x != null ? UiTree.shortRid(x.rid) : (e.resourceId == null ? "" : e.resourceId);
            el.cls = x != null ? x.shortCls() : e.role;
            if (x != null) {
                el.l = Math.max(0, x.l);
                el.t = Math.max(0, x.t);
                el.r = Math.min(w, x.r);
                el.b = Math.min(h, x.b);
            } else {
                el.l = UiElement.denormalize(e.bounds[0], w);
                el.t = UiElement.denormalize(e.bounds[1], h);
                el.r = UiElement.denormalize(e.bounds[2], w);
                el.b = UiElement.denormalize(e.bounds[3], h);
            }
            el.click = e.clickable;
            el.edit = e.editable;
            el.scroll = e.scrollable;
            el.check = x != null && x.has(FsTree.F_CHECKABLE);
            el.checked = e.states.contains(UiElement.CHECKED);
            el.selected = e.states.contains(UiElement.SELECTED);
            el.focused = e.focused;
            el.password = e.secret;
            el.enabled = !e.states.contains(UiElement.DISABLED);
            el.key = el.rid + "|" + el.label + "|" + el.cls + "|" + (el.edit ? "E" : "") + (el.scroll ? "S" : "") + "#" + e.ref;
            u.els.add(el);
        }
        u.mika = model;
        u.mikaStable = f.refs;
        u.mikaQuality = TreeQuality.of(model)[0];
        u.mikaJson = ScreenJson.observe(model, f.maxChars).toString();
        return u;
    }

    // ------------------------------------------------------------------ 一步之后

    static ActionEffect.Verb verb(PhonePlan.Step s) {
        if (s.op.equals("tap")) return ActionEffect.Verb.TAP;
        if (s.op.equals("long")) return ActionEffect.Verb.LONG_PRESS;
        if (s.op.equals("type")) return ActionEffect.Verb.TYPE;
        if (s.op.equals("scroll")) return ActionEffect.Verb.SCROLL;
        if (s.op.equals("swipe")) return ActionEffect.Verb.SWIPE;
        if (s.op.equals("back")) return ActionEffect.Verb.BACK;
        if (s.op.equals("launch")) return ActionEffect.Verb.LAUNCH;
        return ActionEffect.Verb.KEY;
    }

    /**
     * 一步执行完、界面读好之后：判效果（effect）、过守卫阶梯（guard）、给卡住建议。返回附在这一步结果后面的说明（可空串）；
     * 守卫叫停时设 {@link #stopReason}。插件关 / 没有 Mika 快照时返回空串、不计数。
     */
    public String afterStep(PhonePlan.Step s, UiTree before, UiTree after, String effectWord) {
        if (!(f.effect || f.guard) || s.op.equals("wait")) return "";
        StringBuilder b = new StringBuilder();
        ActionEffect e = null;
        if (f.effect && before != null && after != null && before.mika != null && after.mika != null) {
            UiElement target = s.id >= 0 ? before.mika.find("@e" + s.id) : null;
            ActionEffect.PortFacts pf = new ActionEffect.PortFacts();
            if (s.op.equals("type") && effectWord != null && effectWord.startsWith("已输入")) pf.verified = true;
            e = ActionEffect.classify(verb(s), before.mika, after.mika, pf, target, s.op.equals("type") ? s.text : null, s.enter, target != null && target.secret);
            int[] pt = s.x >= 0 && s.y >= 0 ? new int[]{s.x, s.y} : null;
            e = ActionEffect.withTreeBlind(e, verb(s), before.mikaQuality, before.mika, target, pt);
            if (e != null) {
                b.append(" effect=").append(e.kind.wire);
                if (e.kind == ActionEffect.Kind.NO_EFFECT) effectsNoEffect++;
                if (e.kind == ActionEffect.Kind.DONE) effectsDone++;
                String hint = ActionEffect.hint(e, verb(s));
                if (hint != null) b.append("（").append(hint).append("）");
            }
        }
        if (f.guard) {
            List<OperationGuards.Verdict> vs = new ArrayList<OperationGuards.Verdict>();
            boolean failed = effectWord != null && (effectWord.contains("失败") || effectWord.startsWith("打不开") || effectWord.equals("不支持"));
            vs.addAll(guards.result(s.op, !failed, true, failed ? effectWord : null));
            long before0 = guards.progressVersion();
            if (!s.op.equals("wait")) guards.noteAction();
            if (e != null) vs.addAll(guards.effect(e.kind.wire));
            if (after != null && after.mika != null) vs.addAll(guards.frame(after.mika.snapshotId, after.mika.hash));
            else if (after != null) vs.addAll(guards.frame(after.signature(), after.signature().hashCode()));
            boolean progressed = guards.progressVersion() != before0;
            for (OperationGuards.Verdict v : vs) {
                if (v.nudge != null) {
                    guardWarns++;
                    b.append(" 提醒：").append(v.nudge);
                }
            }
            String typed = s.op.equals("type") && effectWord != null && effectWord.startsWith("已输入") ? s.text : null;
            String adv = advice.next(vs, typed, after == null ? null : after.pkg, progressed);
            if (adv != null) b.append(" 建议：").append(adv);
            if (guards.stop() != null) stopReason = StuckAdvice.stopReason(guards.stop());
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ 风险 / 节奏

    /**
     * SensitiveGuard 没拦的这一步，RiskGate 还要不要问用户：识别出发送 / 提交 / 付款 / 下单 / 删除，或屏上带金额的提交（按付款问）。
     * sensitive = SensitiveGuard 的结论（非 null 时只补「花钱」说明）。返回给用户看的原因，null = 不用另问。
     */
    public String consent(PhonePlan.Step s, UiTree cur, String sensitive) {
        if (!f.actgate || cur == null) return null;
        UiTree.El e = s.id >= 0 ? cur.find(s.id) : null;
        if (e == null) return null;
        RiskGate.Irrev hit = RiskGate.detect(s.op, e.label, e.rid, e.edit);
        if (hit == null) return null;
        List<String> texts = new ArrayList<String>();
        for (UiTree.El x : cur.els) texts.add(x.label);
        RiskGate.Risk r = RiskGate.assess(s.op, hit, texts);
        if (sensitive != null) return r.payment && !sensitive.contains("付款") ? sensitive + "（页面上有金额，可能要花钱）" : null;
        if (!r.needsConsent()) return null;
        consentAsks++;
        return "这一步像是「" + r.label() + "」" + (r.payment ? "，页面上有金额，可能要花钱" : "") + "：「" + PhoneAgent.clip(e.label, 16) + "」";
    }

    /** 这一步是对外动作时的节奏键（包名）；不是对外动作为 null。 */
    public String paceSite(PhonePlan.Step s, UiTree cur) {
        if (!f.pacing || cur == null) return null;
        UiTree.El e = s.id >= 0 ? cur.find(s.id) : null;
        if (e == null) return null;
        return RiskGate.detect(s.op, e.label, e.rid, e.edit) == null ? null : OutwardPacing.siteOf(cur.pkg, false);
    }

    public long paceWait(String site, long now) {
        return site == null ? 0 : PACING.waitBefore(site, now);
    }

    public void paceDone(String site, long now) {
        if (site != null) PACING.noteDone(site, now);
    }

    /** 提示词附加段（mika.screen / mika.dosteps 开时）。 */
    public String promptAddendum() {
        if (!f.screen && !f.dosteps) return "";
        StringBuilder b = new StringBuilder();
        if (f.screen) {
            b.append("\n【界面格式（对齐 Mika phone_observe）】当前界面是 JSON：currentApp、snapshotId、elements[{ref:\"@e3\", role, name, center{x,y}, bounds[l,t,r,b], resourceId?, hint?}]，"
                    + "坐标都归一到 0–999。hint=true 表示 name 是输入框的提示语、不是已输入的内容；secret=true 是密码框。"
                    + "同一个元素在相邻两屏里编号不变（列表滚动后同名行会换新号）。truncated=true 表示元素太多只给了前面一部分，需要时滚动。"
                    + "treeQuality 不是 adequate 时控件树不全，可以要 {\"shot\":true}。动作结果里 effect=no_effect 表示界面一点没变，别原样重复。");
        }
        if (f.dosteps) {
            b.append("\n【步骤写法】元素可以写 \"ref\":\"@e3\"（等同 id:3）；也可以写 0–999 坐标 \"x\":500,\"y\":120（必须同时写 \"target\" 说明点的是什么，坐标点没效果不许原样再点）。"
                    + "也接受 Mika do_steps 写法：{\"do_steps\":{\"steps\":[{\"tool\":\"tap\",\"args\":{\"ref\":\"@e3\"},\"expect\":\"…\"}]}}。"
                    + "确实做不成时输出 {\"end_not_done\":\"原因\"}。");
        }
        return b.toString();
    }
}
