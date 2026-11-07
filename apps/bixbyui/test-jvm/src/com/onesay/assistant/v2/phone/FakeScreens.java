package com.onesay.assistant.v2.phone;

import com.onesay.assistant.v2.fastscreen.FsTree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 测试用：用 FsTree 节点拼界面，经 UiTree.from 编号（走真实构建路径）。 */
final class FakeScreens {

    static final int C = FsTree.F_CLICK | FsTree.F_ENABLED | FsTree.F_VISIBLE;

    static final class B {
        final FsTree t = new FsTree();
        int y = 100;

        B(String pkg, String act) {
            t.pkg = pkg;
            t.activity = act;
            FsTree.Node root = new FsTree.Node();
            root.cls = "android.widget.FrameLayout";
            root.l = 0;
            root.t = 0;
            root.r = 1080;
            root.b = 2220;
            root.flags = FsTree.F_ENABLED | FsTree.F_VISIBLE;
            t.nodes.add(root);
        }

        B node(String cls, String text, String rid, int flags) {
            FsTree.Node n = new FsTree.Node();
            n.parent = 0;
            n.cls = cls;
            n.text = text;
            n.rid = rid;
            n.l = 0;
            n.t = y;
            n.r = 1080;
            n.b = y + 120;
            n.flags = flags;
            y += 130;
            t.nodes.add(n);
            return this;
        }

        B button(String text) {
            return node("android.widget.Button", text, "", C);
        }

        B button(String text, String rid) {
            return node("android.widget.Button", text, rid, C);
        }

        B text(String text) {
            return node("android.widget.TextView", text, "", FsTree.F_ENABLED | FsTree.F_VISIBLE);
        }

        B edit(String hint, String rid) {
            return node("android.widget.EditText", hint, rid, C | FsTree.F_EDIT | FsTree.F_FOCUSABLE);
        }

        B password(String rid) {
            return node("android.widget.EditText", "", rid, C | FsTree.F_EDIT | FsTree.F_PASSWORD);
        }

        B toggle(String text, boolean on) {
            return node("android.widget.Switch", text, "", C | FsTree.F_CHECKABLE | (on ? FsTree.F_CHECKED : 0));
        }

        B list() {
            return node("android.widget.ListView", "", "list", FsTree.F_SCROLL | FsTree.F_ENABLED | FsTree.F_VISIBLE);
        }

        /** 「标题 + 摘要」两行的可点条目（没字的可点容器，名字取子孙文字）。 */
        B row(String title, String summary) {
            int p = t.nodes.size();
            FsTree.Node row = new FsTree.Node();
            row.parent = 0;
            row.cls = "android.widget.LinearLayout";
            row.l = 0;
            row.t = y;
            row.r = 1080;
            row.b = y + 160;
            row.flags = C;
            t.nodes.add(row);
            FsTree.Node a = new FsTree.Node();
            a.parent = p;
            a.cls = "android.widget.TextView";
            a.text = title;
            a.rid = "android:id/title";
            a.l = 40;
            a.t = y + 10;
            a.r = 1000;
            a.b = y + 80;
            a.flags = FsTree.F_ENABLED;
            t.nodes.add(a);
            if (summary != null) {
                FsTree.Node s = new FsTree.Node();
                s.parent = p;
                s.cls = "android.widget.TextView";
                s.text = summary;
                s.rid = "android:id/summary";
                s.l = 40;
                s.t = y + 90;
                s.r = 1000;
                s.b = y + 150;
                s.flags = FsTree.F_ENABLED;
                t.nodes.add(s);
            }
            y += 170;
            return this;
        }

        UiTree ui(UiTree.Ids ids) {
            return UiTree.from(t, ids, 1080, 2220);
        }
    }

    static B screen(String pkg, String act) {
        return new B(pkg, act);
    }

    /** 状态机式假手机：点某个名字 → 跳到某屏。 */
    static final class Phone implements PhoneAgent.Driver {
        final Map<String, B> screens = new HashMap<String, B>();
        final Map<String, String> taps = new HashMap<String, String>();
        String cur;
        long clock = 0;
        final List<String> log = new ArrayList<String>();
        String typed;
        int observes;

        Phone add(String name, B b) {
            screens.put(name, b);
            if (cur == null) cur = name;
            return this;
        }

        Phone on(String from, String label, String to) {
            taps.put(from + "|" + label, to);
            return this;
        }

        @Override public UiTree observe(UiTree.Ids ids) {
            tick();
            observes++;
            clock += 15;
            return screens.get(cur).ui(ids);
        }

        /** 页面切换延迟：点完要再读几次屏，新页面才出现（模拟 Activity 转场）。 */
        int lagObserves;
        private String mPending;
        private int mLag;

        @Override public boolean tap(UiTree.El e) {
            clock += 40;
            log.add("tap " + (e == null ? "null" : e.label));
            if (e == null) return false;
            String to = taps.get(cur + "|" + e.label);
            if (to != null) {
                if (lagObserves > 0) {
                    mPending = to;
                    mLag = lagObserves;
                } else cur = to;
            }
            return true;
        }

        private void tick() {
            if (mPending != null && --mLag <= 0) {
                cur = mPending;
                mPending = null;
            }
        }

        @Override public boolean longPress(UiTree.El e, int ms) {
            log.add("long " + e.label);
            return true;
        }

        @Override public String type(UiTree.El e, String text) {
            log.add("type " + text);
            typed = text;
            return text;
        }

        @Override public boolean scroll(UiTree.El e, int dir) {
            log.add("scroll " + dir);
            String to = taps.get(cur + "|<scroll>");
            if (to == null) return false;
            cur = to;
            return true;
        }

        @Override public boolean swipe(int x1, int y1, int x2, int y2, int ms) {
            return true;
        }

        @Override public boolean key(String key) {
            log.add("key " + key + (mPending != null ? "(转场中)" : ""));
            String to = taps.get(cur + "|<" + key + ">");
            if (to != null) cur = to;
            return true;
        }

        @Override public boolean launch(String pkg) {
            log.add("launch " + pkg);
            String to = taps.get("*|launch:" + pkg);
            if (to != null) cur = to;
            return true;
        }

        @Override public void waitIdle(int idleMs, int timeoutMs) {
            clock += idleMs;
        }

        @Override public String describeScreen() {
            return null;
        }

        @Override public long now() {
            return clock;
        }

        @Override public void sleep(long ms) {
            clock += ms;
        }
    }

    /** 脚本化 planner：按顺序吐预先写好的 JSON；记录收到的提示词。 */
    static final class Script implements PhoneAgent.Planner {
        final List<String> outs = new ArrayList<String>();
        final List<String> users = new ArrayList<String>();
        final List<String> systems = new ArrayList<String>();
        int i;

        Script(String... o) {
            for (String s : o) outs.add(s);
        }

        @Override public String next(String system, String user) {
            systems.add(system);
            users.add(user);
            String o = i < outs.size() ? outs.get(i++) : "{\"done\":\"脚本用完\"}";
            return resolve(o, user);
        }
    }

    /** 把 {id:名字} 换成当前提示词里该名字的编号（模型就是这样读编号的）。 */
    static String resolve(String o, String user) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{id:([^}]+)\\}").matcher(o);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            java.util.regex.Matcher k = java.util.regex.Pattern.compile("\\[(\\d+)\\] [^\\n「]*「" + java.util.regex.Pattern.quote(m.group(1))).matcher(user);
            m.appendReplacement(b, k.find() ? k.group(1) : "-9");
        }
        m.appendTail(b);
        return b.toString();
    }

    /** 可编程宿主：确认答案、接手请求、继续 / 停止。 */
    static final class Host implements PhoneAgent.Host {
        final List<String> steps = new ArrayList<String>();
        final List<String> confirms = new ArrayList<String>();
        final List<String> resumes = new ArrayList<String>();
        boolean confirmAnswer = true;
        String pendingTakeover;
        PhoneAgent.Resume resumeWith = PhoneAgent.Resume.RESUME;
        String supplement;
        boolean stop;
        /** 第 n 次 onStep 时触发接手（-1 = 不触发）。 */
        int takeoverAtStep = -1;

        @Override public void onStep(String w, int n) {
            steps.add(w);
            if (takeoverAtStep >= 0 && steps.size() == takeoverAtStep) pendingTakeover = "按了返回键";
        }

        @Override public boolean confirm(String title, String detail, String[] out) {
            confirms.add(detail);
            return confirmAnswer;
        }

        @Override public String takeoverRequested() {
            String t = pendingTakeover;
            pendingTakeover = null;
            return t;
        }

        @Override public PhoneAgent.Resume awaitResume(String why, String[] sup) {
            resumes.add(why);
            sup[0] = supplement;
            supplement = null;
            return resumeWith;
        }

        @Override public boolean stopRequested() {
            return stop;
        }
    }
}
