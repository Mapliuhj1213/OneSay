package com.onesay.desktop;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * [app-whitelist-1116] 应用白名单新增的用户可见文字（设置页、通知、确认框、风险说明、分层原因、文档）里没有厂商 / 旧品牌字样。
 * ① 真跑一遍会给人看的函数查返回的文字；② 扫新增源码里的字符串字面量和界面脚本（只是包名标识符的不算，例如「不能加白名单」名单里的旧包名）。
 */
public class WhitelistBrandTest {

    static final Pattern BRAND = Pattern.compile("bixby|三星|samsung|小布", Pattern.CASE_INSENSITIVE);
    static final Pattern LIT = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"|'((?:[^'\\\\\\n]|\\\\.)*)'|`([^`]*)`");
    static final Pattern PKG_ID = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+");

    static void clean(String where, String text) {
        assertTrue(where + " 里有品牌字样：" + text, text == null || !BRAND.matcher(text).find());
    }

    static Set<String> literals(String src) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = LIT.matcher(src);
        while (m.find()) {
            String s = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            if (s == null || PKG_ID.matcher(s).matches()) continue;
            out.add(s);
        }
        return out;
    }

    static Path root() {
        for (String c : new String[]{System.getProperty("wl.root", ""), "."}) {
            if (c.isEmpty()) continue;
            if (Files.isRegularFile(Paths.get(c, "src/main/java/com/onesay/desktop/WhitelistStore.java"))) return Paths.get(c);
        }
        throw new AssertionError("找不到电脑端源码（-Dwl.root 指向 apps/desktop）");
    }

    static String read(Path p) throws Exception { return Files.readString(p, StandardCharsets.UTF_8); }

    static String between(String s, String a, String b) {
        int i = s.indexOf(a);
        assertTrue("找不到：" + a, i >= 0);
        int j = s.indexOf(b, i + a.length());
        assertTrue("找不到：" + b, j > i);
        return s.substring(i, j);
    }

    @Test public void runtimeTextsHaveNoBrand() throws Exception {
        String[][] apps = {{"windows", "C:\\x\\Code.exe", "VS Code"}, {"android", "com.example.chat", "聊天"}, {"windows", "onesay.exe", ""},
                {"android", "com.onesay.assistant", ""}, {"windows", "a.bat", ""}, {"ios", "x", ""}};
        for (String[] a : apps) {
            clean("riskText", AppWhitelist.riskText(a[0], a[1], a[2]));
            clean("refuseReason", AppWhitelist.refuseReason(a[0], a[1]));
        }
        clean("refuseReason 旧包名（风险说明会原样带上用户填的 id，不算新增文字）", AppWhitelist.refuseReason("android", "com.bixbyui"));
        for (ActionPolicy.Verdict v : ActionPolicy.Verdict.values())
            for (ActionPolicy.Kind k : ActionPolicy.Kind.values())
                for (int bits = 0; bits < 32; bits++) {
                    ActionPolicy.Facts f = new ActionPolicy.Facts();
                    f.base = v;
                    f.kind = k;
                    f.whitelisted = true;
                    f.appId = "notepad.exe";
                    if ((bits & 1) != 0) f.outPrivacy.add(PrivacyScan.PHONE);
                    if ((bits & 2) != 0) f.readPrivacy.add(PrivacyScan.ID_CARD);
                    f.readExternal = (bits & 4) != 0;
                    f.neverAsk = (bits & 8) != 0;
                    if ((bits & 16) != 0) { f.passesUsed = 5; f.passLimit = 5; }
                    clean("ActionPolicy " + v + "/" + k + "/" + bits, ActionPolicy.decide(f).text);
                }
        WhitelistStore w = new WhitelistStore(Files.createTempDirectory("wlbrand"));
        clean("add 拒绝", w.add("windows", "cmd.exe", ""));
        clean("confirm 没有", w.confirm("android", "com.example.none"));
        java.util.Map<String, Object> b = new java.util.TreeMap<>();
        for (String op : new String[]{"list", "risk", "add", "confirm", "remove", "nope"}) {
            b.put("op", op);
            b.put("platform", "windows");
            b.put("id", "code.exe");
            clean("whitelistApi " + op, String.valueOf(App.whitelistApi(w, b, s -> { }).get("text")));
        }
    }

    @Test public void newSourceLiteralsUiAndDocsHaveNoBrand() throws Exception {
        Path r = root(), src = r.resolve("src/main/java/com/onesay/desktop");
        for (String n : new String[]{"AppWhitelist", "PrivacyScan", "ActionPolicy", "WhitelistStore"})
            for (String s : literals(read(src.resolve(n + ".java")))) clean(n, s);
        String app = read(src.resolve("App.java"));
        for (String s : literals(between(app, "// ---------- [app-whitelist-1116] 应用白名单", "// ---------- 链接下载"))) clean("App 白名单", s);
        String guard = read(src.resolve("PcGuard.java"));
        for (String s : literals(between(guard, "[app-whitelist-1116] 统一分层策略", "[fix-1114] 写法可疑"))) clean("PcGuard 分层", s);
        String ui = read(r.resolve("src/main/resources/onesay/ui/index.html"));
        for (String s : literals(between(ui, "function wlPolicy(){", "async function remRevoke("))) clean("界面 应用白名单", s);
        for (String d : new String[]{"docs/APP-WHITELIST.md", "docs/PERMISSIONS.md"}) clean(d, read(r.resolve(d)).replace("apps/bixbyui", ""));
    }
}
