package com.onesay.assistant.v2.variant;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.BuildVariant;
import com.onesay.assistant.v2.imgsave.ImageSavePlan;
import com.onesay.assistant.v2.kernel.FeatureSwitch;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * 2026-10-22 独立 debug 变体：
 * <ul>
 *   <li>两份 BuildVariant 字段一一对应，只含编译期常量；release 的值与引入变体机制之前写死的值完全一样；</li>
 *   <li>debug 的包名 / kill-switch 目录 / 公共存储目录 / 文件标记都和 release 不同（不会读写同一份）；</li>
 *   <li>kill-switch 路径工具：release 的 .off 文件关不掉 debug，反之亦然；</li>
 *   <li>Manifest 里需要随变体改名的东西（签名级权限、自定义 action）都能被 build_linux.sh 的 debug sed 覆盖；provider 只放行 ShizukuProvider，且 authority 随变体改名。</li>
 * </ul>
 */
public class BuildVariantTest {

    static String src(String rel) {
        try {
            return new String(Files.readAllBytes(new File(rel).toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("读不到 " + rel + "（单测要在仓库根目录跑）: " + e);
        }
    }

    static final Pattern FIELD = Pattern.compile(
            "public static final (String|boolean|int|long) ([A-Z_][A-Z0-9_]*) = ([^;]+);");

    /** 解析 BuildVariant 源文件：字段名 → 「类型 值」。顺带确认没有非常量成员。 */
    static Map<String, String> fields(String rel) {
        String s = src(rel);
        Map<String, String> m = new LinkedHashMap<String, String>();
        Matcher mt = FIELD.matcher(s);
        while (mt.find()) {
            String v = mt.group(3).trim();
            assertTrue(rel + " 的 " + mt.group(2) + " 必须是字面量（javac 才会内联）: " + v,
                    v.matches("\"[^\"]*\"|true|false|-?\\d+L?"));
            m.put(mt.group(2), mt.group(1) + " " + v);
        }
        String noComments = s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        int statics = noComments.split("public static final", -1).length - 1;
        assertEquals(rel + " 只准有 public static final 字面量字段", m.size(), statics);
        assertFalse(rel + " 不准有静态块", noComments.contains("static {"));
        assertEquals(rel + " 除了私有构造器不准有方法（只有一处左括号）", 1, noComments.split("\\(", -1).length - 1);
        return m;
    }

    static final String REL = "app/variant/release/com/onesay/assistant/v2/BuildVariant.java";
    static final String DBG = "app/variant/debug/com/onesay/assistant/v2/BuildVariant.java";

    @Test public void bothVariantsDeclareTheSameConstants() {
        Map<String, String> r = fields(REL), d = fields(DBG);
        assertEquals("两个变体的字段名必须一一对应", r.keySet(), d.keySet());
        for (String k : r.keySet()) {
            assertEquals(k + " 类型要一致", r.get(k).split(" ")[0], d.get(k).split(" ")[0]);
        }
        assertTrue(r.size() >= 8);
    }

    @Test public void releaseValuesAreTheHistoricalConstants() {
        // 单测按 release 口径编译：这里拿到的就是 release 的值
        assertEquals("release", BuildVariant.VARIANT);
        assertFalse(BuildVariant.IS_DEBUG);
        assertEquals("com.onesay.assistant", BuildVariant.APPLICATION_ID);
        assertEquals("/data/local/tmp", BuildVariant.KILL_TMP_ROOT);
        assertEquals("/data/local/tmp", FeatureSwitch.TMP_ROOT);
        assertEquals("/data/local/tmp/bixby_s1.off", com.onesay.assistant.v2.s1.S1Settings.KILL_ALL_TMP);
        assertEquals("/data/local/tmp/bixby_s2.off", com.onesay.assistant.v2.s2.S2Settings.KILL_ALL_TMP);
        assertEquals("/data/local/tmp/bixby_diag.off", com.onesay.assistant.v2.diag.DiagSettings.KILL_TMP);
        assertEquals("/data/local/tmp/bixby_island.off", com.onesay.assistant.v2.island.IslandSettings.KILL_TMP);
        assertEquals("/data/local/tmp/bixby_summary.off", com.onesay.assistant.v2.summary.SummarySettings.KILL_TMP);
        assertEquals("/data/local/tmp/bixby_imgsave.off", com.onesay.assistant.v2.imgsave.ImageSaveSettings.KILL_TMP);
        assertEquals("/data/local/tmp/bixby_screenref.off", com.onesay.assistant.v2.vision.screenref.ScreenRefSettings.KILL_FILE_TMP);
        assertEquals("OneSay/com.tencent.mm", ImageSavePlan.relativeDir("com.tencent.mm"));
        assertEquals("OneSay", BuildVariant.APP_TITLE);
        assertEquals("OneSay", BuildVariant.APP_TITLE_V2);
        assertEquals("", BuildVariant.PUBLIC_FILE_TAG);
    }

    @Test public void debugValuesAreSuffixedAndDisjoint() {
        Map<String, String> r = fields(REL), d = fields(DBG);
        assertEquals("String \"com.onesay.assistant.debug\"", d.get("APPLICATION_ID"));
        assertEquals("String \"debug\"", d.get("VARIANT"));
        assertEquals("boolean true", d.get("IS_DEBUG"));
        for (String k : new String[]{"APPLICATION_ID", "KILL_TMP_ROOT", "PUBLIC_DIR", "PUBLIC_FILE_TAG", "APP_TITLE", "APP_TITLE_V2"}) {
            assertNotEquals("debug 的 " + k + " 不能和 release 相同（会读写同一份 / 认不出来）", r.get(k), d.get(k));
        }
        String dbgRoot = unq(d.get("KILL_TMP_ROOT"));
        assertTrue("debug kill 目录要在 /data/local/tmp 下（adb shell 不用 root 也能写）", dbgRoot.startsWith("/data/local/tmp/"));
        assertTrue(dbgRoot.contains("debug"));
        assertTrue(unq(d.get("PUBLIC_DIR")).toLowerCase().contains("debug"));
        assertTrue(unq(d.get("PUBLIC_FILE_TAG")).contains("debug"));
    }

    static String unq(String typed) {
        String v = typed.substring(typed.indexOf(' ') + 1);
        return v.startsWith("\"") ? v.substring(1, v.length() - 1) : v;
    }

    /** 路径工具：两个变体的 kill 根目录互不影响（同一台手机上 release 关掉某功能不会顺带关掉 debug）。 */
    @Test public void killSwitchRootsDoNotCrossTalk() throws Exception {
        File tmp = Files.createTempDirectory("dlt").toFile();          // 模拟 /data/local/tmp
        File relRoot = tmp;
        File dbgRoot = new File(tmp, "com.onesay.assistant.debug");             // debug：/data/local/tmp/com.onesay.assistant.debug
        assertTrue(dbgRoot.mkdirs());
        File relExt = Files.createTempDirectory("ext-rel").toFile();  // /sdcard/Android/data/com.onesay.assistant/files
        File dbgExt = Files.createTempDirectory("ext-dbg").toFile();  // /sdcard/Android/data/com.onesay.assistant.debug/files

        assertTrue(new File(relRoot, "bixby_island.off").createNewFile());
        assertTrue("release 的 kill 文件关 release", FeatureSwitch.killFilePresent(relExt, relRoot.getPath(), "island.off"));
        assertFalse("release 的 kill 文件不能关掉 debug", FeatureSwitch.killFilePresent(dbgExt, dbgRoot.getPath(), "island.off"));

        assertTrue(new File(dbgRoot, "bixby_s3.off").createNewFile());
        assertTrue("debug 的 kill 文件关 debug", FeatureSwitch.killFilePresent(dbgExt, dbgRoot.getPath(), "s3.off"));
        assertFalse("debug 的 kill 文件不能关掉 release", FeatureSwitch.killFilePresent(relExt, relRoot.getPath(), "s3.off"));

        assertTrue(new File(dbgExt, "summary.off").createNewFile());
        assertFalse("外部 files 目录按包名天然隔离", FeatureSwitch.killFilePresent(relExt, relRoot.getPath(), "summary.off"));
    }

    @Test public void codeUsesVariantConstantsNotHardcodedPackage() {
        // 运行期按包名判断「是不是自己」的地方必须走变体常量 / getPackageName()
        String qn = src("app/src/com/onesay/assistant/v2/quicknote/QuickNoteActions.java");
        assertFalse(qn.contains("equals(\"com.onesay.assistant\")"));
        assertTrue(qn.contains("com.onesay.assistant.v2.BuildVariant.APPLICATION_ID"));
        String sys = src("app/src/com/onesay/assistant/v2/capability/caps/SystemCaps.java");
        assertFalse("截图公共文件名要带变体标记", sys.contains("/sdcard/Pictures/bixbyui_shot.png"));
        assertTrue(src("app/src/com/onesay/assistant/v2/kernel/FeatureSwitch.java").contains("TMP_ROOT = com.onesay.assistant.v2.BuildVariant.KILL_TMP_ROOT"));
        // 引用 BuildVariant 只用全限定名（不加 import，不改行数 → release dex 逐字节不变）
        for (String f : new String[]{"kernel/FeatureSwitch", "quicknote/QuickNoteActions", "capability/caps/SystemCaps",
                "imgsave/ImageSavePlan", "ui/console/HomePage", "ui/FloatingSheetService"}) {
            assertFalse(f, src("app/src/com/onesay/assistant/v2/" + f + ".java").contains("import com.onesay.assistant.v2.BuildVariant"));
        }
    }

    /** Manifest 里随变体改名的只有签名级权限 com.onesay.assistant.permission.CONTROL 和测试 action；build_linux.sh 的 debug sed 覆盖它们。 */
    @Test public void manifestCustomNamesAreCoveredByDebugRewrite() {
        String m = src("app/AndroidManifest.xml");
        // priv-llm：唯一允许的 provider 是 Shizuku 官方库的 Binder 接收端；它的 authority 必须是「正式包名.shizuku」，
        // 且 build_linux.sh 的 debug 改写把它改成「debug 包名.shizuku」（并在改写后自检），两包不会抢同一 authority。
        // 任何别的 <provider> 仍然直接失败。
        Matcher prov = Pattern.compile("<provider\\b([^>]*)/?>").matcher(m);
        int providers = 0;
        while (prov.find()) {
            providers++;
            String attrs = prov.group(1);
            assertTrue("出现 <provider> 了：authorities 要按变体区分（例如 ${applicationId}.xxx），并补 build_linux.sh 的 debug 改写（只放行 rikka.shizuku.ShizukuProvider）: " + attrs,
                    attrs.contains("android:name=\"rikka.shizuku.ShizukuProvider\""));
            assertTrue("ShizukuProvider 的 authority 必须是 com.onesay.assistant.shizuku", attrs.contains("android:authorities=\"com.onesay.assistant.shizuku\""));
            assertTrue("ShizukuProvider 必须只让持 INTERACT_ACROSS_USERS_FULL 的调用方进来",
                    attrs.contains("android:permission=\"android.permission.INTERACT_ACROSS_USERS_FULL\""));
        }
        assertTrue("最多一个 provider（ShizukuProvider）", providers <= 1);
        if (providers == 1) {
            String shp = src("build_linux.sh");
            assertTrue("build_linux.sh 缺 ShizukuProvider authority 的 debug 改写",
                    shp.contains("s#android:authorities=\"com\\.onesay\\.assistant\\.shizuku\"#android:authorities=\"com.onesay.assistant.debug.shizuku\"#"));
            assertTrue("build_linux.sh 缺改写后的 authority 自检", shp.contains("if grep -q 'android:authorities=\"com.onesay.assistant.shizuku\"' \"$MANIFEST\""));
            String dm = m.replace("android:authorities=\"com.onesay.assistant.shizuku\"", "android:authorities=\"com.onesay.assistant.debug.shizuku\"");
            assertFalse(dm.contains("android:authorities=\"com.onesay.assistant.shizuku\""));
            assertTrue(dm.contains("android:authorities=\"com.onesay.assistant.debug.shizuku\""));
        }
        assertFalse("出现 sharedUserId：两个签名不同的包不能共用", m.contains("sharedUserId"));
        Matcher perm = Pattern.compile("android:(?:name|permission)=\"(com\\.onesay\\.assistant\\.[^\"]+)\"").matcher(m);
        while (perm.find()) {
            String n = perm.group(1);
            boolean component = n.startsWith("com.onesay.assistant.v2.") || n.equals("com.onesay.assistant.WebViewUiActivity");
            boolean renamed = n.equals("com.onesay.assistant.permission.CONTROL") || n.equals("com.onesay.assistant.action.RUN_TEST")
                    || n.equals("com.onesay.assistant.permission.SUMMON") || n.equals("com.onesay.assistant.action.SUMMON");
            assertTrue("Manifest 里新的 com.onesay.assistant.* 名字没有被 debug 改写覆盖（组件类名除外）: " + n, component || renamed);
        }
        // 模拟 build_linux.sh 的 debug 改写
        String d = m.replace("com.onesay.assistant.permission.CONTROL", "com.onesay.assistant.debug.permission.CONTROL")
                .replace("com.onesay.assistant.action.RUN_TEST", "com.onesay.assistant.debug.action.RUN_TEST")
                .replace("com.onesay.assistant.permission.SUMMON", "com.onesay.assistant.debug.permission.SUMMON")
                .replace("com.onesay.assistant.action.SUMMON", "com.onesay.assistant.debug.action.SUMMON");
        assertFalse(Pattern.compile("com\\.onesay\\.assistant\\.(permission|action)\\.").matcher(d).find());
        String sh = src("build_linux.sh");
        assertTrue(sh.contains("--rename-manifest-package \"$APP_ID\""));
        assertTrue(sh.contains("s#com\\.onesay\\.assistant\\.permission\\.CONTROL#com.onesay.assistant.debug.permission.CONTROL#g"));
        assertTrue(sh.contains("s#com\\.onesay\\.assistant\\.action\\.RUN_TEST#com.onesay.assistant.debug.action.RUN_TEST#g"));
        assertTrue(sh.contains("APP_ID=\"com.onesay.assistant.debug\""));
        assertTrue(sh.contains("s#com\\.onesay\\.assistant\\.permission\\.SUMMON#com.onesay.assistant.debug.permission.SUMMON#g"));
        assertTrue(sh.contains("s#com\\.onesay\\.assistant\\.action\\.SUMMON#com.onesay.assistant.debug.action.SUMMON#g"));
    }

    /** 守护：默认 release 常量与 v8.3 一字不差；debug 冷启动目标写全类名。 */
    @Test public void keyDaemonDefaultsToRelease() {
        // [ci-1114] hijack_daemon.v9 / exec_daemon.v2 已从开源版删除；同一意图（守护默认指向 release 包，debug 需显式开关）改查现存的
        //   key_daemon.sh 与 exec_daemon.v4.sh
        String kd = src("tools/kdaemon/key_daemon.sh");
        assertTrue(kd.contains("PKG=${KD_PKG:-com.onesay.assistant}\n"));
        String ex = src("tools/kdaemon/exec_daemon.v4.sh");
        assertTrue(ex.contains("DIR=/data/user/0/com.onesay.assistant/files\n"));
        assertTrue(ex.contains("DBG_FLAG=/data/adb/bixbyui_debug_exec"));
        assertTrue(ex.contains("PKG_REL=${BIXBY_EXEC_PKG_REL:-com.onesay.assistant}\n"));
    }
}
