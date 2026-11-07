package com.onesay.assistant.v2.sec;

import static org.junit.Assert.*;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * 2026-10-25 安全修复 fix-ipc：IPC-1（debug.keystore 出库 + 口令不再明文 + CI 断言）与 IPC §8（问答答复正文不进日志）。
 * 静态核对脚本 / .gitignore，并实际跑 tools/ci/check_no_keystore.sh（临时 git 仓库里造正反例）。
 */
public class SecIpcBuildTest {

    static final String ROOT = System.getProperty("bixby.root", ".");

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(ROOT, rel).toPath()), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- IPC-1 静态

    @Test public void gitignoreNoLongerWhitelistsDebugKeystore() throws Exception {
        String g = src("../../.gitignore"); // monorepo 根的 .gitignore
        for (String line : g.split("\n")) {
            assertFalse("不能再有 !debug.keystore 豁免", line.trim().equals("!debug.keystore"));
            assertFalse("任何 keystore 都不能被反向豁免: " + line, line.trim().startsWith("!") && line.contains(".keystore"));
        }
        assertTrue(g.contains("\n*.keystore\n"));
        assertTrue("口令文件也忽略", g.contains("\n*.keystore.pass\n"));
    }

    @Test public void buildScriptsHaveNoPlaintextPasswordDefaults() throws Exception {
        String lin = src("build_linux.sh");
        assertFalse("debug 明文口令", lin.contains("bixbydebug"));
        assertFalse("release 明文口令默认值", lin.contains("KS_PASS:-android"));
        assertFalse("不再用 pass: 传口令", lin.contains("\"pass:$KS_PASS\"") || lin.contains("pass:$KEY_PASS"));
        assertTrue(lin.contains("resolve_pass KS_PASS KS_PASS_FILE \"$KS.pass\""));
        assertTrue("缺失即报错退出", lin.contains("缺少签名口令"));
        assertTrue("支持不签名的审查构建", lin.contains("UNSIGNED=\"${UNSIGNED:-0}\""));
        int sign = lin.indexOf("APKSIGNER sign --ks");
        int ci = lin.indexOf("bash \"$ROOT/tools/ci/check_no_keystore.sh\"");
        assertTrue("构建后跑 CI 断言", sign > 0 && ci > sign);
        String win = src("build.sh");
        assertFalse(win.contains("pass:android"));
        assertTrue(win.contains("tools/ci/check_no_keystore.sh"));
    }

    @Test public void debugKeystoreNotInIndex() throws Exception {
        Assume.assumeTrue(new File(ROOT, ".git").exists() && which("git"));
        String ls = run(new File(ROOT), "git", "ls-files");
        for (String f : ls.split("\n")) {
            assertFalse("索引里有 keystore: " + f, f.toLowerCase().endsWith(".keystore") || f.toLowerCase().endsWith(".jks"));
        }
    }

    // ---------------------------------------------------------------- IPC-1 CI 脚本实跑

    @Test public void ciScriptFailsOnTrackedKeystoreAndPassesWhenClean() throws Exception {
        Assume.assumeTrue(which("git") && which("bash"));
        File tmp = Files.createTempDirectory("ksci").toFile();
        try {
            run(tmp, "git", "init", "-q");
            run(tmp, "git", "config", "user.email", "t@t");
            run(tmp, "git", "config", "user.name", "t");
            Files.write(new File(tmp, "a.txt").toPath(), "x".getBytes(StandardCharsets.UTF_8));
            run(tmp, "git", "add", "a.txt");
            String script = new File(ROOT, "tools/ci/check_no_keystore.sh").getAbsolutePath();
            assertEquals("干净仓库通过", 0, exit(tmp, "bash", script));
            assertEquals("默认扫脚本所在仓库（本仓库已干净）", 0, exitEnv(tmp, null, "bash", script));
            Files.write(new File(tmp, "debug.keystore").toPath(), new byte[]{1, 2, 3});
            run(tmp, "git", "add", "-f", "debug.keystore");
            assertEquals("keystore 进索引 → 失败", 1, exit(tmp, "bash", script));
            run(tmp, "git", "rm", "-q", "--cached", "debug.keystore");
            assertEquals("移出索引 → 通过", 0, exit(tmp, "bash", script));
            Files.write(new File(tmp, "x.keystore.pass").toPath(), "pw".getBytes(StandardCharsets.UTF_8));
            run(tmp, "git", "add", "-f", "x.keystore.pass");
            assertEquals("口令文件进索引 → 失败", 1, exit(tmp, "bash", script));
            run(tmp, "git", "rm", "-q", "--cached", "x.keystore.pass");
            run(tmp, "git", "add", "-f", "debug.keystore");
            run(tmp, "git", "commit", "-q", "-m", "leak");
            run(tmp, "git", "rm", "-q", "--cached", "debug.keystore");
            run(tmp, "git", "commit", "-q", "-m", "unleak");
            assertEquals("历史里有只告警", 0, exit(tmp, "bash", script, "--history"));
            assertEquals("--strict-history 历史里有 → 失败", 1, exit(tmp, "bash", script, "--strict-history"));
        } finally {
            run(tmp.getParentFile(), "rm", "-rf", tmp.getAbsolutePath());
        }
    }

    @Test public void buildFailsWithoutPassword() throws Exception {
        Assume.assumeTrue(which("bash"));
        Assume.assumeTrue("需要构建工具链", new File(System.getenv("TOOLS") == null ? "/nonexistent" : System.getenv("TOOLS") + "/android-sdk/build-tools/30.0.3/lib/apksigner.jar").exists());
        File ks = File.createTempFile("dummy", ".keystore");
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", new File(ROOT, "build_linux.sh").getAbsolutePath());
            pb.environment().put("DEBUG", "1");
            pb.environment().put("KS", ks.getAbsolutePath());
            pb.environment().put("OUT", Files.createTempDirectory("ipcout").toString());
            pb.environment().remove("KS_PASS");
            pb.environment().remove("KS_PASS_FILE");
            pb.environment().remove("KEY_PASS");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
            assertTrue(p.waitFor(60, TimeUnit.SECONDS));
            Assume.assumeFalse("本机缺构建依赖，跳过", out.contains("MISSING DEP"));
            assertEquals(out, 2, p.exitValue());
            assertTrue(out, out.contains("缺少签名口令"));
        } finally {
            ks.delete();
        }
    }

    // ---------------------------------------------------------------- IPC §8

    @Test public void askLlmAnswerBodyNotLogged() throws Exception {
        String l2 = src("app/src/com/onesay/assistant/v2/router/L2LLMCompiler.java");
        assertFalse(l2.contains("\" 字): \" + answer"));
        assertFalse(l2.contains("\" 字): \" + reply"));
        for (String line : l2.split("\n")) {
            if (!line.contains("[ASK-LLM]") || !line.contains("Log.")) continue;
            assertFalse("ASK-LLM 日志直接拼正文: " + line.trim(), line.matches(".*\\+\\s*(answer|reply)\\s*\\).*"));
        }
        assertTrue(l2.contains("LogText.u(answer, 60)") && l2.contains("LogText.u(reply, 60)"));
        // release 口径：只有长度
        String ans = "张三住在北京市海淀区某某路 1 号，电话 13800138000";
        String u = com.onesay.assistant.v2.util.LogText.u(ans, 60);
        assertFalse(u.contains("张三") || u.contains("138"));
    }

    // ---------------------------------------------------------------- 工具

    static boolean which(String cmd) {
        try {
            return exit(new File("."), "sh", "-c", "command -v " + cmd + " >/dev/null 2>&1") == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }

    static String run(File dir, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertEquals(String.join(" ", cmd) + "\n" + out, 0, p.exitValue());
        return out;
    }

    /** 跑 CI 脚本时 REPO 指向 dir（临时仓库）。 */
    static int exit(File dir, String... cmd) throws Exception {
        return exitEnv(dir, dir.getAbsolutePath(), cmd);
    }

    static int exitEnv(File dir, String repo, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
        if (repo != null) pb.environment().put("REPO", repo);
        else pb.environment().remove("REPO");
        Process p = pb.start();
        readAll(p.getInputStream());
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        return p.exitValue();
    }
}
