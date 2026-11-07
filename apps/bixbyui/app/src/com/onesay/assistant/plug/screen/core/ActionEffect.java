/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (core/uistate/ActionEffect.kt, EffectTuning.kt) to Java 7 + org.json;
 * frame (screenshot) evidence and web pages removed; feedback strings translated to Chinese
 * (prompts/feedback/tools.md effect.*). Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.screen.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/** 动作的实际效果：done / small_change / no_effect / place_changed，按动作前后的元素表判出。 */
public final class ActionEffect {

    public enum Kind {
        DONE("done"), SMALL_CHANGE("small_change"), NO_EFFECT("no_effect"), PLACE_CHANGED("place_changed");
        public final String wire;

        Kind(String w) {
            wire = w;
        }
    }

    /** 动作种类（Mika Verb 的手机子集）。 */
    public enum Verb { TAP, TYPE, CLEAR, SCROLL, SWIPE, BACK, KEY, LAUNCH, GESTURE, LONG_PRESS }

    // ---- EffectTuning ----
    public static final int MAJOR_MIN = 3;
    public static final double MAJOR_SHARE = 0.10;
    public static final int NEAR = 50, SAME_FIELD = 120;

    /** 端口事实：changed（端口核实的变没变，null 不知道）、switchedTo（端口看到前台换成的包）、verified（输入当场读回一致）、loading。 */
    public static final class PortFacts {
        public Boolean changed;
        public String switchedTo;
        public boolean verified, loading;
    }

    /** 两屏元素表的结构差异。 */
    public static final class Diff {
        public final int added, removed, moved, edited, toggled, total;
        public final boolean focusMoved;

        Diff(int added, int removed, int moved, int edited, int toggled, boolean focusMoved, int total) {
            this.added = added;
            this.removed = removed;
            this.moved = moved;
            this.edited = edited;
            this.toggled = toggled;
            this.focusMoved = focusMoved;
            this.total = total;
        }

        public int changes() {
            return added + removed + moved + edited + toggled;
        }

        public boolean none() {
            return changes() == 0 && !focusMoved;
        }

        public boolean major() {
            return changes() >= Math.max(MAJOR_MIN, (int) Math.ceil(total * MAJOR_SHARE));
        }

        public static Diff between(ScreenSnapshot before, ScreenSnapshot after) {
            List<UiElement> a = meaningful(before), b = meaningful(after);
            List<UiElement> la = new ArrayList<UiElement>(a), lb = new ArrayList<UiElement>(b);
            pairOff(la, lb, 0);
            int toggled = pairOff(la, lb, 1);
            int moved = pairOff(la, lb, 2);
            int edited = pairOff(la, lb, 3);
            String fa = null, fb = null;
            for (UiElement e : a) if (e.focused) { fa = place(e); break; }
            for (UiElement e : b) if (e.focused) { fb = place(e); break; }
            boolean fm = fa == null ? fb != null : !fa.equals(fb);
            return new Diff(lb.size(), la.size(), moved, edited, toggled, fm, Math.max(a.size(), b.size()));
        }

        private static int pairOff(List<UiElement> a, List<UiElement> b, int mode) {
            int n = 0;
            Iterator<UiElement> it = a.iterator();
            while (it.hasNext()) {
                UiElement x = it.next();
                int j = -1;
                for (int k = 0; k < b.size(); k++) if (match(x, b.get(k), mode)) { j = k; break; }
                if (j >= 0) {
                    b.remove(j);
                    it.remove();
                    n++;
                }
            }
            return n;
        }

        private static boolean match(UiElement x, UiElement y, int mode) {
            boolean sameKey = key(x).equals(key(y));
            switch (mode) {
                case 0: return sameKey && Arrays.equals(x.bounds, y.bounds) && x.states.equals(y.states);
                case 1: return sameKey && Arrays.equals(x.bounds, y.bounds);
                case 2: return sameKey;
                default: return x.editable && y.editable && x.role.equals(y.role) && eq(x.resourceId, y.resourceId) && near(x, y);
            }
        }
    }

    static List<UiElement> meaningful(ScreenSnapshot s) {
        List<UiElement> out = new ArrayList<UiElement>();
        if (s != null) for (UiElement e : s.elements) if (e.meaningful()) out.add(e);
        return out;
    }

    static String key(UiElement e) {
        return e.role + "\u0000" + e.name + "\u0000" + (e.resourceId == null ? "" : e.resourceId) + "\u0000" + e.hint;
    }

    static String place(UiElement e) {
        return e.role + "|" + (e.resourceId == null ? "" : e.resourceId) + "|" + Arrays.toString(e.bounds);
    }

    static boolean near(UiElement x, UiElement y) {
        if (x.bounds.length != 4 || y.bounds.length != 4) return x.resourceId != null;
        return distance(x, y) <= NEAR;
    }

    static int distance(UiElement a, UiElement b) {
        if (a.bounds.length != 4 || b.bounds.length != 4) return 0;
        int dx = (a.bounds[0] + a.bounds[2]) / 2 - (b.bounds[0] + b.bounds[2]) / 2;
        int dy = (a.bounds[1] + a.bounds[3]) / 2 - (b.bounds[1] + b.bounds[3]) / 2;
        return (int) Math.sqrt((double) dx * dx + (double) dy * dy);
    }

    static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // ------------------------------------------------------------------ 结果

    public final Kind kind;
    public final String placeTo;
    public final Diff diff;
    /** 读回：text（密码框 null）、secret、matches；没读回为 null。 */
    public final String readbackText;
    public final Boolean readbackMatches;
    public final boolean readbackSecret, loading, treeBlind;

    ActionEffect(Kind kind, String placeTo, Diff diff, String rbText, Boolean rbMatches, boolean rbSecret, boolean loading, boolean treeBlind) {
        this.kind = kind;
        this.placeTo = placeTo;
        this.diff = diff;
        this.readbackText = rbText;
        this.readbackMatches = rbMatches;
        this.readbackSecret = rbSecret;
        this.loading = loading;
        this.treeBlind = treeBlind;
    }

    ActionEffect with(Kind k, boolean loading) {
        return new ActionEffect(k, placeTo, diff, readbackText, readbackMatches, readbackSecret, loading, treeBlind);
    }

    ActionEffect blind(boolean b) {
        return new ActionEffect(kind, placeTo, diff, readbackText, readbackMatches, readbackSecret, loading, b);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("kind", kind.wire);
            if (placeTo != null) o.put("to", placeTo);
            if (diff != null) {
                if (diff.added > 0) o.put("added", diff.added);
                if (diff.removed > 0) o.put("removed", diff.removed);
                if (diff.moved > 0) o.put("moved", diff.moved);
                if (diff.edited > 0) o.put("edited", diff.edited);
                if (diff.toggled > 0) o.put("toggled", diff.toggled);
                if (diff.focusMoved) o.put("focusMoved", true);
            }
            if (readbackMatches != null) {
                o.put("readback", readbackSecret ? "(hidden)" : clip(readbackText, 200));
                o.put("readbackMatches", readbackMatches.booleanValue());
            }
            if (loading) o.put("mayStillBeLoading", true);
            if (treeBlind) o.put("treeBlind", true);
        } catch (JSONException ignored) {
        }
        return o;
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** 动作前后判效果（拿不准时不说没效果：端口说变了至少是小变化；在加载也只算小变化）。 */
    public static ActionEffect classify(Verb verb, ScreenSnapshot before, ScreenSnapshot after, PortFacts port, UiElement target,
                                        String typed, boolean pressedEnter, boolean secret) {
        if (port == null) port = new PortFacts();
        if (port.switchedTo != null) return new ActionEffect(Kind.PLACE_CHANGED, port.switchedTo, null, null, null, false, false, false);
        ActionEffect raw = judge(verb, before, after, port, target, typed, pressedEnter, secret);
        if (raw == null) return null;
        boolean loading = port.loading || (after != null && after.changedDuringRead) || (after != null && showsProgress(after));
        if (raw.kind != Kind.NO_EFFECT) return raw.with(raw.kind, loading && raw.kind == Kind.SMALL_CHANGE);
        if (loading) return raw.with(Kind.SMALL_CHANGE, true);
        if (Boolean.TRUE.equals(port.changed)) return raw.with(Kind.SMALL_CHANGE, false);
        return raw;
    }

    /** 这一屏的树读不出点的那一处（树不能用，或缺了一块而坐标点不在任何元素上）：没变化也不能说没点上。 */
    public static ActionEffect withTreeBlind(ActionEffect e, Verb verb, String quality, ScreenSnapshot before, UiElement target, int[] point) {
        if (e == null || e.kind != Kind.NO_EFFECT || (verb != Verb.TAP && verb != Verb.GESTURE)) return e;
        boolean blind;
        if (TreeQuality.UNUSABLE.equals(quality)) blind = true;
        else if (TreeQuality.PARTIAL.equals(quality) && target == null && point != null && before != null) {
            blind = true;
            for (UiElement x : before.elements) {
                if (x.meaningful() && x.bounds.length == 4 && point[0] >= x.bounds[0] && point[0] <= x.bounds[2]
                        && point[1] >= x.bounds[1] && point[1] <= x.bounds[3]) {
                    blind = false;
                    break;
                }
            }
        } else blind = false;
        return blind ? e.blind(true) : e;
    }

    private static ActionEffect judge(Verb verb, ScreenSnapshot before, ScreenSnapshot after, PortFacts port, UiElement target,
                                      String typed, boolean pressedEnter, boolean secret) {
        if (before == null || after == null) {
            if (Boolean.TRUE.equals(port.changed)) return new ActionEffect(Kind.SMALL_CHANGE, null, null, null, null, false, false, false);
            if (Boolean.FALSE.equals(port.changed)) return new ActionEffect(Kind.NO_EFFECT, null, null, null, null, false, false, false);
            return null;
        }
        String pa = after.packageName;
        if (pa != null && before.packageName != null && !before.packageName.equals(pa))
            return new ActionEffect(Kind.PLACE_CHANGED, pa, null, null, null, false, false, false);
        Diff diff = Diff.between(before, after);
        String rbText = null;
        Boolean rbMatch = null;
        boolean rbSecret = false;
        if ((verb == Verb.TYPE || verb == Verb.CLEAR) && !pressedEnter) {
            String want = verb == Verb.CLEAR ? "" : typed;
            if (want != null) {
                UiElement field = readbackField(after, target);
                if (field != null) {
                    if (secret || field.secret) {
                        rbSecret = true;
                        rbMatch = Boolean.FALSE;
                    } else {
                        rbText = field.hint ? "" : field.name;
                        rbMatch = Accuracy.norm(rbText).equals(Accuracy.norm(want));
                    }
                }
            }
        }
        Kind k;
        if (rbMatch != null && !rbSecret && rbMatch) k = Kind.DONE;
        else if (port.verified && (rbMatch == null || rbSecret) && !pressedEnter) k = Kind.DONE;
        else if (rbMatch != null && !rbSecret) k = diff.none() ? Kind.NO_EFFECT : Kind.SMALL_CHANGE;
        else if (diff.major()) k = Kind.DONE;
        else if (diff.none()) k = Kind.NO_EFFECT;
        else k = Kind.SMALL_CHANGE;
        return new ActionEffect(k, null, diff, rbText, rbMatch, rbSecret, false, false);
    }

    static boolean showsProgress(ScreenSnapshot s) {
        for (UiElement e : s.elements) if (e.role.toLowerCase().contains("progress") && !e.states.contains(UiElement.DISABLED)) return true;
        return false;
    }

    /** 同一个框（同角色同 rid、相距 ≤ SAME_FIELD）优先，其次有焦点的输入框。 */
    static UiElement readbackField(ScreenSnapshot after, UiElement target) {
        List<UiElement> fields = new ArrayList<UiElement>();
        for (UiElement e : after.elements) if (e.editable) fields.add(e);
        if (target != null) {
            UiElement best = null;
            int bd = Integer.MAX_VALUE;
            for (UiElement f : fields) {
                if (!f.role.equals(target.role) || !eq(f.resourceId, target.resourceId)) continue;
                int d = distance(f, target);
                if (d < bd) {
                    bd = d;
                    best = f;
                }
            }
            if (best != null && bd <= SAME_FIELD) return best;
        }
        for (UiElement f : fields) if (f.focused) return f;
        return null;
    }

    /** 按效果给模型的一句话（prompts/feedback/tools.md effect.* 的中文版）；明显变了不另说。 */
    public static String hint(ActionEffect e, Verb verb) {
        if (e == null) return null;
        String differs = e.readbackMatches != null && !e.readbackSecret && !e.readbackMatches
                ? "输入框现在显示「" + clip(e.readbackText, 60) + "」，不是要输入的内容。" : null;
        switch (e.kind) {
            case DONE:
                return null;
            case PLACE_CHANGED:
                return "这个动作把你带到了别处：现在在 " + e.placeTo + "。如果这不是任务的一部分，返回。";
            case SMALL_CHANGE:
                if (differs != null) return differs;
                return e.loading ? "检查结束时界面还在变 / 在加载，结果可能还没出完。依赖它之前再看一眼，不要重复这个动作。"
                        : "只有小的局部变化（焦点、高亮或一两个元素）。依赖它之前确认确实达到了目的。";
            default: {
                String base;
                if (e.treeBlind) base = "看不到变化，但这一屏的控件树读不出这一处：只换了焦点或隐藏状态的点也看不出来，不等于没点上。重复之前先看一眼。";
                else if (verb == Verb.BACK) base = "返回之后界面没变：这个页面可能不认系统返回。别再按返回，点页面自己的返回 / 关闭，或重新打开 App。";
                else if (verb == Verb.SCROLL || verb == Verb.SWIPE) base = "什么都没动，这个方向多半已经到头（或这里不能滚）。认定没有更多内容之前，先确认方向。";
                else base = "动作之后界面一点没变，多半没生效。别原样重来：换个元素、先把目标滚进视野，或换条路去同一个地方。";
                return differs == null ? base : differs + " " + base;
            }
        }
    }
}
