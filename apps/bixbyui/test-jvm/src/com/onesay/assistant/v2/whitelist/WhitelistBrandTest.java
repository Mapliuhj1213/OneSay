package com.onesay.assistant.v2.whitelist;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * [app-whitelist-1116] 开源版：应用白名单新增的用户可见文字（控制台页、通知、确认卡、风险说明、分层原因、文档）里没有厂商 / 旧品牌字样。
 * 两种查法：① 真跑一遍会给人看的函数，查返回的文字；② 扫新增源码里的字符串字面量（只是包名标识符的不算，例如「不能加白名单」名单里的旧包名）。
 */
public class WhitelistBrandTest {

    static final Pattern BRAND = Pattern.compile("bixby|三星|samsung|小布", Pattern.CASE_INSENSITIVE);
    static final Pattern LIT = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    static final Pattern PKG_ID = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+");

    static void clean(String where, String text) {
        Matcher m = BRAND.matcher(text == null ? "" : text);
        assertTrue(where + " 里有品牌字样：" + text, !m.find());
    }

    @Test public void runtimeTextsHaveNoBrand() {
        String[][] apps = {{"android", "com.example.chat", "聊天"}, {"windows", "C:\\x\\Code.exe", "VS Code"}, {"android", "com.onesay.assistant", ""},
                {"windows", "powershell.exe", ""}, {"android", "bad pkg", ""}, {"ios", "x", ""}};
        for (String[] a : apps) {
            clean("riskText", AppWhitelist.riskText(a[0], a[1], a[2]));
            clean("refuseReason", AppWhitelist.refuseReason(a[0], a[1]));
        }
        clean("refuseReason 旧包名", AppWhitelist.refuseReason("android", "com.bixbyui"));
        for (ActionPolicy.Verdict v : ActionPolicy.Verdict.values())
            for (ActionPolicy.Kind k : ActionPolicy.Kind.values())
                for (int bits = 0; bits < 32; bits++) {
                    ActionPolicy.Facts f = new ActionPolicy.Facts();
                    f.base = v;
                    f.kind = k;
                    f.whitelisted = true;
                    f.appId = "com.example.chat";
                    if ((bits & 1) != 0) f.outPrivacy.add(PrivacyScan.PHONE);
                    if ((bits & 2) != 0) f.readPrivacy.add(PrivacyScan.ID_CARD);
                    f.readExternal = (bits & 4) != 0;
                    f.neverAsk = (bits & 8) != 0;
                    if ((bits & 16) != 0) { f.passesUsed = 5; f.passLimit = 5; }
                    clean("ActionPolicy " + v + "/" + k + "/" + bits, ActionPolicy.decide(f).text);
                }
        WhitelistState st = new WhitelistState(null, "ph-t");
        clean("add 拒绝", st.add("windows", "cmd.exe", ""));
        clean("confirm 没有", st.confirm("android", "com.example.none"));
    }

    static String read(File f) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
    }

    static File root() {
        String r = System.getProperty("bixby.root", ".");
        return new File(r.length() == 0 ? "." : r);
    }

    static Set<String> literals(String src) {
        Set<String> out = new LinkedHashSet<String>();
        Matcher m = LIT.matcher(src);
        while (m.find()) {
            String s = m.group(1);
            if (PKG_ID.matcher(s).matches()) continue; // 包名标识符，不是给人看的
            out.add(s);
        }
        return out;
    }

    @Test public void newSourceLiteralsAndDocsHaveNoBrand() throws Exception {
        File r = root();
        File wl = new File(r, "app/src/com/onesay/assistant/v2/whitelist");
        List<File> files = new ArrayList<File>(Arrays.asList(wl.listFiles()));
        assertTrue("找不到白名单源码目录：" + wl, files.size() >= 6);
        for (File f : files) if (f.getName().endsWith(".java")) for (String s : literals(read(f))) clean(f.getName(), s);
        // 控制台「应用白名单」那一段
        String gp = read(new File(r, "app/src/com/onesay/assistant/v2/ui/console/GroupPages.java"));
        int a = gp.indexOf("private void buildWhitelist("), b = gp.indexOf("static String platformOf(");
        assertTrue(a > 0 && b > a);
        for (String s : literals(gp.substring(a, b))) clean("GroupPages 应用白名单", s);
        // PcRx 的白名单同步回执
        String rx = read(new File(r, "app/src/com/onesay/assistant/v2/pclink/PcRx.java"));
        int c = rx.indexOf("public static PcLanClient.Result whitelistSync(");
        assertTrue(c > 0);
        for (String s : literals(rx.substring(c, rx.indexOf("// ====", c)))) clean("PcRx.whitelistSync", s);
        // 文档
        clean("docs/APP-WHITELIST.md", read(new File(r, "docs/APP-WHITELIST.md")).replace("apps/bixbyui", ""));
    }
}
