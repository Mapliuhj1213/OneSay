package com.onesay.assistant.v2.ui;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 2026-10-23 BUG-I2b：从源码证明面板的每条关闭路径都汇到<b>唯一</b>的摘窗点 {@code removeSheetWindow}，
 * 而 panel(false) 只从那里经 {@link PanelPresence} 发出（同一次关闭只报一次见 PanelPresenceTest）。
 *
 * <p>做法：把 FloatingSheetService 拆成成员（方法 / 字段初始化里的匿名 Runnable），按「成员体里引用了别的成员」建调用图，
 * 从每条关闭路径的入口做可达性检查。注释 / 字符串先抹掉，不会被文字误导。
 */
public class PanelPresenceWiringTest {

    static String raw, code;
    static Map<String, String> members;

    static String src(String rel) throws Exception {
        File f = new File(System.getProperty("bixby.root", "."), rel);
        return new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
    }

    @BeforeClass public static void parse() throws Exception {
        raw = src("app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        code = strip(raw);
        members = members(code, "class FloatingSheetService");
    }

    /** 抹掉注释、字符串、字符字面量（换成空格，保留长度和括号结构）。 */
    static String strip(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') { b.append(' '); i++; }
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                e = e < 0 ? n : e + 2;
                for (; i < e; i++) b.append(s.charAt(i) == '\n' ? '\n' : ' ');
            } else if (c == '"' || c == '\'') {
                char q = c;
                b.append(' ');
                i++;
                while (i < n && s.charAt(i) != q) {
                    if (s.charAt(i) == '\\') { b.append(' '); i++; }
                    b.append(' ');
                    i++;
                }
                b.append(' ');
                i++;
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString();
    }

    /** 顶层类体里的成员：名字 → 成员全文（重载合并）。 */
    static Map<String, String> members(String s, String classDecl) {
        int start = s.indexOf(classDecl);
        int open = s.indexOf('{', start);
        Map<String, String> out = new LinkedHashMap<String, String>();
        int depth = 1, i = open + 1, memberStart = i;
        boolean fieldInit = false;
        while (i < s.length() && depth > 0) {
            char c = s.charAt(i);
            if (depth == 1 && c == '=') fieldInit = true;
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) break;
                if (depth == 1 && !fieldInit) {
                    put(out, s.substring(memberStart, i + 1));
                    memberStart = i + 1;
                }
            } else if (c == ';' && depth == 1) {
                put(out, s.substring(memberStart, i + 1));
                memberStart = i + 1;
                fieldInit = false;
            }
            i++;
        }
        return out;
    }

    static final Pattern ANNOT = Pattern.compile("@\\w+(\\s*\\([^)]*\\))?");
    static final Pattern IDENT = Pattern.compile("[A-Za-z_$][\\w$]*");

    static void put(Map<String, String> out, String m) {
        String t = m.trim();
        if (t.isEmpty()) return;
        int brace = t.indexOf('{');
        String header = ANNOT.matcher(brace < 0 ? t : t.substring(0, brace)).replaceAll(" ");
        String name;
        int eq = header.indexOf('=');
        int par = header.indexOf('(');
        Matcher cls = Pattern.compile("\\b(class|interface|enum)\\s+(\\w+)").matcher(header);
        if (cls.find() && (par < 0 || cls.start() < par) && (eq < 0 || cls.start() < eq)) name = cls.group(2);
        else if (eq >= 0 && (par < 0 || eq < par)) name = lastIdent(header.substring(0, eq));
        else if (par >= 0) name = lastIdent(header.substring(0, par));
        else name = lastIdent(header.replace(";", " "));
        if (name == null) return;
        String prev = out.get(name);
        out.put(name, prev == null ? t : prev + "\n" + t);
    }

    static String lastIdent(String s) {
        Matcher m = IDENT.matcher(s);
        String last = null;
        while (m.find()) last = m.group();
        return last;
    }

    /** from 经成员引用能不能到 to。 */
    static boolean reaches(String from, String to) {
        Deque<String> q = new ArrayDeque<String>();
        Set<String> seen = new HashSet<String>();
        q.add(from);
        while (!q.isEmpty()) {
            String cur = q.poll();
            if (!seen.add(cur)) continue;
            if (cur.equals(to)) return true;
            String body = members.get(cur);
            if (body == null) continue;
            for (String m : members.keySet()) {
                if (seen.contains(m)) continue;
                if (Pattern.compile("(?<![\\w$.])(this\\.)?" + Pattern.quote(m) + "\\b").matcher(body).find()) q.add(m);
            }
        }
        return false;
    }

    static int count(String hay, String needle) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    // ------------------------------------------------------------------ 唯一汇合点

    @Test public void onlyOneRemovalAndOneAttachPointForTheCardWindow() {
        assertTrue(members.containsKey("removeSheetWindow"));
        assertTrue(members.containsKey("addSheetWindow"));
        String rm = members.get("removeSheetWindow");
        assertEquals("整个服务只有一处 removeViewImmediate(mSheetView)", 1, count(code, "removeViewImmediate(mSheetView)"));
        assertEquals("整个服务只有一处 removeView(mSheetView)", 1, count(code, ".removeView(mSheetView)"));
        assertTrue(rm.contains("removeViewImmediate(mSheetView)") && rm.contains(".removeView(mSheetView)"));
        assertEquals("整个服务只有一处 addView(mSheetView", 1, count(code, "addView(mSheetView"));
        assertTrue(members.get("addSheetWindow").contains("addView(mSheetView"));
    }

    @Test public void panelFalseOnlyFromTheRemovalPointAndAfterTryCatch() {
        assertEquals("mPresence.removed 只在 removeSheetWindow", 1, count(code, "mPresence.removed("));
        String rm = members.get("removeSheetWindow");
        String before = rm.substring(0, rm.indexOf("mPresence.removed("));
        assertEquals("removeView 抛异常也照样登记离屏：removed 在方法体最外层（try/catch 之后）",
                1, count(before, "{") - count(before, "}"));
        assertTrue(before.contains("catch"));
        assertEquals("mPresence.attached 只在 addSheetWindow", 1, count(code, "mPresence.attached("));
        assertTrue("attached 在 addView 成功之后", members.get("addSheetWindow").indexOf("mPresence.attached(")
                > members.get("addSheetWindow").indexOf("addView(mSheetView"));
        assertEquals("岛只从 PanelPresence 的 Sink 得到通知", 1, count(code, "S1Hooks.onPanelVisibility("));
        assertTrue(members.get("mPresence").contains("S1Hooks.onPanelVisibility(on)"));
        assertFalse("旧的分散 reportPanel 已删", code.contains("reportPanel"));
        assertTrue("岛取样用同一份真相", members.get("panelOnScreen").contains("mPresence.onScreen()"));
    }

    // ------------------------------------------------------------------ 每条关闭路径都汇到 removeSheetWindow

    @Test public void hideSheetActionReachesRemoval() {
        int at = code.indexOf("ACTION_HIDE.equals(action)");
        assertTrue(at > 0);
        String branch = code.substring(at, code.indexOf("else if", at));
        assertTrue("HIDE_SHEET → hideSheet(true)", branch.contains("hideSheet(true)"));
        assertTrue(members.get("hideSheet").contains("if (immediate)"));
        assertTrue("立即销毁 → detach", reaches("hideSheet", "detachFromWindowImmediate"));
        assertTrue("detach → removeSheetWindow", reaches("detachFromWindowImmediate", "removeSheetWindow"));
        String d = members.get("detachFromWindowImmediate");
        assertTrue("detach 在面板挂着时摘窗", d.contains("if (mSheetView.getParent() != null || mIsAttached) {")
                && d.indexOf("removeSheetWindow(") > d.indexOf("if (mSheetView.getParent() != null || mIsAttached) {"));
    }

    @Test public void closeButtonAndBackKeyReachRemoval() throws Exception {
        String view = strip(src("app/src/com/onesay/assistant/v2/ui/FloatingSheetView.java"));
        int back = view.indexOf("KeyEvent.KEYCODE_BACK");
        assertTrue("返回键 → onDismiss", back > 0 && view.indexOf("mListener.onDismiss()", back) - back < 400);
        assertTrue("✕（新界面关闭热区）→ onDismiss", view.contains("mCloseRect.contains(x, y) && mListener != null) mListener.onDismiss();"));
        assertTrue("✕（旧界面取消按钮）→ onDismiss", view.contains("mBtnCancelTop.setOnClickListener"));
        assertTrue(reaches("onDismiss", "dismissByUser"));
        assertTrue(reaches("dismissByUser", "hideSheet"));
        assertTrue("新界面退场动画 → detach", reaches("v2DismissAnimated", "detachFromWindowImmediate"));
        assertTrue("新界面退场兜底 → detach", reaches("mV2DismissFallback", "detachFromWindowImmediate"));
        assertTrue(reaches("onDismiss", "removeSheetWindow"));
        assertTrue("点外部也一样", reaches("handleOutsideTap", "removeSheetWindow"));
    }

    @Test public void autoHideReachesRemoval() {
        assertTrue(members.get("mAutoHideRunnable").contains("hideSheet()"));
        assertTrue("自动收起 → removeSheetWindow", reaches("mAutoHideRunnable", "removeSheetWindow"));
        assertTrue("错误态到点收起", reaches("mErrorHideRunnable", "removeSheetWindow"));
        assertTrue("旧界面收起动画 220 ms 兜底", reaches("mHideFallbackRunnable", "removeSheetWindow"));
        assertTrue("看门狗", reaches("mStageWatchdog", "removeSheetWindow"));
    }

    @Test public void serviceDestroyRemovesAndFlushesBeforeIslandTeardown() {
        String d = members.get("onDestroy");
        assertTrue(reaches("onDestroy", "removeSheetWindow"));
        int hide = d.indexOf("hideSheet(true)");
        int flush = d.indexOf("mPresence.flushNow()");
        int kernel = d.indexOf("onServiceDestroy(this)");
        assertTrue("先摘面板", hide >= 0 && hide < flush);
        assertTrue("同步报掉 panel(false) 之后才拆岛（[s1] onServiceDestroy 里 sIsland.destroy）", flush < kernel);
    }

    @Test public void lc5AndIslandPathsUseTheSamePoints() {
        assertTrue("LC-5 新请求接管卡片 → showSheet → addSheetWindow", reaches("beginForegroundRequest", "addSheetWindow"));
        assertTrue("LC-5 之后关闭走同一个 detach", reaches("s1CancelActiveTask", "removeSheetWindow"));
        assertTrue("任务收成胶囊", reaches("s1DetachForCapsule", "removeSheetWindow"));
        assertTrue("形变进岛：延迟摘窗", reaches("mDeferredCardRemoval", "removeSheetWindow"));
        assertTrue("形变进岛：新挂载前补摘", reaches("flushPendingCardRemoval", "removeSheetWindow"));
        assertTrue("结果挂回", reaches("s1AttachSheet", "addSheetWindow"));
        assertTrue("结果从岛流回", reaches("v2UnmorphFromIsland", "addSheetWindow"));
    }

    @Test public void parserSanity() {
        assertTrue(members.size() > 100);
        assertTrue(members.containsKey("onStartCommand") && members.containsKey("showSheet") && members.containsKey("mAutoHideRunnable"));
        assertFalse("反例：自动收起到不了挂窗点以外的无关成员", reaches("mPanelNoSuchMember", "removeSheetWindow"));
    }
}
