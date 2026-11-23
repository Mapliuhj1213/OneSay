package com.onesay.assistant.v2.dui;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.a11ylabel.A11yLabeler;
import com.onesay.assistant.v2.apifirst.RouteTool;
import com.onesay.assistant.v2.fastscreen.FsTree;
import com.onesay.assistant.v2.phone.SettingsAlias;
import com.onesay.assistant.v2.phone.UiTree;
import com.onesay.assistant.v2.router.AgentPreemption;
import com.onesay.assistant.v2.ui.chat.ChatTranscript;
import com.onesay.assistant.v2.util.UserErrText;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** ★ 1123-dui：界面这组 bug（B3/B22、B11、B12、B17'、B18、B24、B36、B37）。 */
public class Dui1123Test {

    static String src(String rel) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        File f = new File(root, "app/src/com/onesay/assistant/v2/" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static FsTree.Node node(int parent, String cls, String text, int l, int t, int r, int b, int flags) {
        FsTree.Node n = new FsTree.Node();
        n.parent = parent; n.cls = cls; n.text = text; n.l = l; n.t = t; n.r = r; n.b = b; n.flags = flags;
        return n;
    }

    /** Flyme 设置：一行只标 focusable，标题是子 TextView。 */
    static FsTree flymeSettings(boolean rowClickable) {
        FsTree t = new FsTree();
        t.pkg = "com.android.settings";
        t.nodes.add(node(-1, "android.widget.FrameLayout", "", 0, 0, 1080, 2400, FsTree.F_ENABLED));
        int rowFlags = FsTree.F_ENABLED | (rowClickable ? FsTree.F_CLICK : FsTree.F_FOCUSABLE);
        t.nodes.add(node(0, "android.widget.LinearLayout", "", 0, 300, 1080, 450, rowFlags));
        t.nodes.add(node(1, "android.widget.TextView", "我的手机", 40, 330, 600, 420, FsTree.F_ENABLED));
        t.nodes.add(node(0, "android.widget.LinearLayout", "", 0, 450, 1080, 600, FsTree.F_ENABLED | FsTree.F_FOCUSABLE));
        t.nodes.add(node(3, "android.widget.TextView", "蓝牙", 40, 480, 600, 570, FsTree.F_ENABLED));
        t.nodes.add(node(3, "android.widget.Switch", "", 900, 480, 1040, 570, FsTree.F_ENABLED | FsTree.F_CLICK | FsTree.F_CHECKABLE));
        return t;
    }

    static UiTree.El find(UiTree u, String label) {
        for (UiTree.El e : u.els) if (e.label.contains(label)) return e;
        return null;
    }

    // ---------------- B11
    @Test public void b11_focusableRowGetsNumber() {
        UiTree u = UiTree.from(flymeSettings(false), new UiTree.Ids(), 1080, 2400);
        UiTree.El row = find(u, "我的手机");
        assertNotNull("focusable 行要进控件表", row);
        assertTrue(row.focusRow);
        assertTrue("「显示按钮」要给它标号", A11yLabeler.tappable(row));
    }

    @Test public void b11_rowWithClickableKid_notDoubled() {
        UiTree u = UiTree.from(flymeSettings(false), new UiTree.Ids(), 1080, 2400);
        for (UiTree.El e : u.els) assertFalse("里面有开关的行不当整行可点", e.focusRow && e.label.contains("蓝牙") && e.r - e.l > 1000);
    }

    @Test public void b11_clickableRowUnchanged() {
        UiTree u = UiTree.from(flymeSettings(true), new UiTree.Ids(), 1080, 2400);
        UiTree.El row = find(u, "我的手机");
        assertNotNull(row);
        assertTrue(row.click);
        assertFalse(row.focusRow);
    }

    // ---------------- B12
    @Test public void b12_noScreenSaysWhy() throws Exception {
        String s = src("a11ylabel/ButtonNumbersHooks.java");
        assertTrue(s.contains("if (drv == null) return noScreenSay(ch);"));
        assertTrue(s.contains("读屏助手停了"));
        int i = s.indexOf("static String noScreenSay(");
        String body = s.substring(i, s.indexOf("static FastScreenDriver driver(", i));
        assertFalse(body.contains("Shizuku"));
        assertFalse(body.contains("SHIZUKU_"));
    }

    // ---------------- B17'
    @Test public void b17_homePcGroupRefilledAfterRebuild() throws Exception {
        String s = src("ui/console/HomePage.java");
        assertTrue(s.contains("mPcGroup = k.group(col, \"连接电脑\");\n        mPcState = \"\";"));
        assertTrue(s.contains("if (!needFill(st, mPcState, mPcGroup.getChildCount())) return;"));
        assertTrue(s.contains("return childCount == 0 || !st.equals(prev);"));
    }

    // ---------------- B18
    @Test public void b18_backKeyKeepsIslandTask() throws Exception {
        assertTrue(AgentPreemption.backKeyKeepsTask(true, true));
        assertFalse(AgentPreemption.backKeyKeepsTask(true, false));
        assertFalse(AgentPreemption.backKeyKeepsTask(false, true));
        String v = src("ui/FloatingSheetView.java");
        assertTrue(v.contains("((FloatingSheetService) mListener).onBackKey();"));
        String svc = src("ui/FloatingSheetService.java");
        int i = svc.indexOf("public void onBackKey() {");
        assertTrue(i > 0);
        String body = svc.substring(i, svc.indexOf("public void onDismiss() {", i));
        assertTrue("岛上任务：走让位收起，不取消", body.contains("s1YieldSheetForExec();"));
        assertFalse(body.contains("stopCurrentTask"));
    }

    // ---------------- B24
    @Test public void b24_inputGrowsToFiveLines() throws Exception {
        String s = src("ui/chat/ChatBottomBar.java");
        assertFalse("不再单行", s.contains("mEdit.setSingleLine(true);"));
        assertFalse("不再省略号", s.contains("TruncateAt.END"));
        assertTrue(s.contains("static final int MAX_LINES = 5;"));
        assertTrue(s.contains("mEdit.setMaxLines(MAX_LINES);"));
        assertTrue(s.contains("mEdit.setHorizontallyScrolling(false);"));
        assertTrue("保住键盘发送：不用多行输入类型", s.contains("mEdit.setRawInputType(InputType.TYPE_CLASS_TEXT);"));
        assertFalse(s.contains("TYPE_TEXT_FLAG_MULTI_LINE"));
        assertTrue("胶囊高度随内容", s.contains("new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)"));
        assertTrue(s.contains("pill.setMinimumHeight(dp(52));"));
        // setMaxLines 必须在 setRawInputType 之后（setSingleLine(false) 会把行数放开）
        assertTrue(s.lastIndexOf("mEdit.setMaxLines(MAX_LINES);") > s.indexOf("mEdit.setRawInputType(InputType.TYPE_CLASS_TEXT);"));
    }

    @Test public void b24_pillRadius() throws Exception {
        java.lang.reflect.Method m = null;
        // ChatBottomBar 依赖 Android 视图，不进 JVM 编译：按源码核对圆角口径
        String s = src("ui/chat/ChatBottomBar.java");
        assertTrue(s.contains("return lines > 1 ? 20f : 26f;"));
        assertNull(m);
    }

    // ---------------- B36
    @Test public void b36_suggestionsDedupAndNoEnterMark() {
        List<String> out = ChatTranscript.suggestionsFrom(Arrays.asList("今天有什么安排↵", "今天有什么安排", "[[撤销]]", "撤销。", " "));
        assertEquals(Arrays.asList("今天有什么安排", "撤销"), out);
        for (String s : out) assertFalse(s.contains("↵"));
    }

    @Test public void b36_questionMarkKept() {
        assertEquals(Arrays.asList("明天呢？"), ChatTranscript.suggestionsFrom(Arrays.asList("明天呢？", "明天呢?")));
    }

    // ---------------- B37（前半：界面不露内部路径）
    @Test public void b37_noInternalPath() {
        String fb = "声纹引擎没加载起来，重启 OneSay 再试";
        assertEquals(fb, UserErrText.scrub("模型未就绪（需推送到 /data/user/0/com.onesay.assistant/files/voiceprint/）", fb));
        assertEquals(fb, UserErrText.scrub("IllegalStateException: boom", fb));
        assertEquals(fb, UserErrText.scrub(null, fb));
        assertEquals("声纹模型还没装到手机上", UserErrText.scrub("声纹模型还没装到手机上", fb));
    }

    @Test public void b37_uiUsesScrub() throws Exception {
        assertTrue(src("ui/console/VoicePage.java").contains("UserErrText.scrub(sv.getLoadError()"));
        assertTrue(src("ui/VoiceprintEnrollController.java").contains("UserErrText.scrub(sv.getLoadError()"));
    }

    // ---------------- B3 / B22
    @Test public void b3_aliasTable() {
        List<String> n = SettingsAlias.namesFor("关于手机");
        assertEquals("关于手机", n.get(0));
        assertTrue(n.contains("我的手机"));
        assertTrue(n.contains("关于本机"));
        assertEquals(Arrays.asList("随便"), SettingsAlias.namesFor("随便"));
    }

    @Test public void b3_hintTellsSearch() {
        String h = SettingsAlias.hintFor("打开设置找到关于手机看看安卓版本");
        assertTrue(h.contains("「我的手机」"));
        assertTrue(h.contains("搜索框"));
        assertEquals("", SettingsAlias.hintFor("给妈妈发微信"));
    }

    @Test public void b3_hasElUsesAlias() {
        UiTree u = UiTree.from(flymeSettings(false), new UiTree.Ids(), 1080, 2400);
        assertTrue(com.onesay.assistant.v2.phone.Dui1123Access.hasEl(u, "关于手机"));
        assertFalse(com.onesay.assistant.v2.phone.Dui1123Access.hasEl(u, "开发者选项"));
    }

    @Test public void b3_promptCarriesHint() throws Exception {
        assertTrue(src("phone/PhonePrompt.java").contains("b.append(SettingsAlias.hintFor(goal));"));
    }

    @Test public void b22_aboutPageNotApps() {
        assertEquals("android.settings.DEVICE_INFO_SETTINGS", RouteTool.settingsAction("about"));
        assertEquals("android.settings.DEVICE_INFO_SETTINGS", RouteTool.settingsAction("关于手机"));
        assertEquals("android.settings.DEVICE_INFO_SETTINGS", RouteTool.settingsAction("我的手机"));
        assertEquals("android.settings.APPLICATION_SETTINGS", RouteTool.settingsAction("apps"));
        assertEquals("android.settings.WIFI_SETTINGS", RouteTool.settingsAction("wifi"));
    }
}
