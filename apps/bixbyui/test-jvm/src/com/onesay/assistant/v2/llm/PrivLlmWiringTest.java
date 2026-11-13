package com.onesay.assistant.v2.llm;

import com.onesay.assistant.v2.security.SecretPrefs;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/** priv-llm：Manifest / 构建脚本 / 许可声明 / 密钥登记 / 安全约束的接线（读源文件）。 */
public class PrivLlmWiringTest {

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), rel).toPath()), "UTF-8");
    }

    @Test public void manifestShizukuProviderAndProvidersPage() throws Exception {
        String m = src("app/AndroidManifest.xml");
        int i = m.indexOf("android:name=\"rikka.shizuku.ShizukuProvider\"");
        assertTrue(i > 0);
        String block = m.substring(i, m.indexOf("/>", i));
        assertTrue(block.contains("android:authorities=\"com.onesay.assistant.shizuku\""));
        assertTrue("只有系统 / Shizuku 服务能调", block.contains("android:permission=\"android.permission.INTERACT_ACROSS_USERS_FULL\""));
        assertTrue(m.contains("moe.shizuku.manager.permission.API_V23"));
        assertTrue(m.contains("moe.shizuku.client.V3_SUPPORT"));
        int a = m.indexOf("com.onesay.assistant.v2.ui.LlmProvidersActivity");
        assertTrue(a > 0);
        assertTrue("服务商页不导出", m.substring(a, m.indexOf("/>", a)).contains("android:exported=\"false\""));
    }

    @Test public void buildScriptsCarryShizukuJar() throws Exception {
        String b = src("build_linux.sh");
        assertTrue(b.contains("SHIZUKU=\"$ROOT/libs/shizuku-api-13.1.5.jar\""));
        assertTrue(b.contains("CP=\"$PLATFORM:$MAIL1:$MAIL2:$ZXING:$SHIZUKU\""));
        assertTrue("dex 也带上", b.contains("\"$ZXING\" \"$SHIZUKU\"\n"));
        assertTrue("debug 包改 authority", b.contains("com.onesay.assistant.debug.shizuku"));
        assertTrue(src("run_unit_tests.sh").contains("libs/shizuku-api-13.1.5.jar"));
        File jar = new File(System.getProperty("bixby.root", "."), "libs/shizuku-api-13.1.5.jar");
        assertTrue(jar.isFile());
        java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar);
        try {
            assertNotNull(z.getEntry("rikka/shizuku/Shizuku.class"));
            assertNotNull(z.getEntry("rikka/shizuku/ShizukuProvider.class"));
            assertNotNull(z.getEntry("moe/shizuku/server/IShizukuService.class"));
            assertNotNull(z.getEntry("moe/shizuku/server/IRemoteProcess.class"));
        } finally {
            z.close();
        }
        assertTrue(src("libs/README-shizuku.md").contains("1f760c56493bdbc01462930e75bb325033b36fe2"));
    }

    @Test public void attributionKept() throws Exception {
        String n = src("NOTICE");
        assertEquals(n, src("app/assets/NOTICE.txt"));
        assertTrue("原有 Mika 署名保留", n.contains("upstream revision ebb0e44"));
        assertTrue("服务商层钉住的上游版本", n.contains("8d61573eaed7d36fa37093bab233c94a5e79dd4d"));
        assertTrue("Shizuku API 是 MIT", n.contains("Copyright (c) 2021 RikkaW") && n.contains("MIT License"));
        for (String f : new String[]{"ProviderKind", "ProviderPreset", "ProviderErrors", "OpenAiCompatAdapter", "AnthropicAdapter", "GeminiAdapter"}) {
            String s = src("app/src/com/onesay/assistant/v2/llm/" + f + ".java");
            assertTrue(f, s.startsWith("/*\n * Portions derived from Mika (https://github.com/wangzan101/mika)"));
            assertTrue(f, s.contains("Upstream: 8d61573"));
        }
        String t = src("../../THIRD_PARTY_NOTICES.md");
        assertTrue(t.contains("shizuku-api-13.1.5.jar") && t.contains("MIT-Shizuku-API.txt"));
        assertTrue(new File(System.getProperty("bixby.root", "."), "../../licenses/MIT-Shizuku-API.txt").isFile());
    }

    @Test public void providerKeysRegisteredAsSecrets() {
        Set<String> keys = new HashSet<String>();
        for (String[] row : SecretPrefs.REGISTRY) keys.add(row[0] + "/" + row[1]);
        for (ProviderPreset p : ProviderPreset.all()) {
            if (p.id.equals("deepseek") || p.id.equals("dashscope")) continue;   // 沿用原有的 deepseek_key / dashscope_key
            assertTrue(p.id, keys.contains(ProviderStore.PREF + "/" + ProviderStore.keyPrefName(p.id)));
        }
        assertTrue(keys.contains("bixby_llm_cfg/deepseek_key"));
        assertTrue(keys.contains("bixby_asr_cfg/dashscope_key"));
    }

    @Test public void keySafetyRules() throws Exception {
        String store = src("app/src/com/onesay/assistant/v2/llm/ProviderStore.java");
        assertFalse("Key 不明文存", store.contains("putString(keyPrefName"));
        assertTrue(store.contains("SecretPrefs.put(e, keyPrefName(p.id), k)"));
        String http = src("app/src/com/onesay/assistant/v2/llm/ProviderHttp.java");
        assertTrue("不跟随重定向", http.contains("setInstanceFollowRedirects(false)"));
        String client = src("app/src/com/onesay/assistant/v2/net/LlmClient.java");
        assertTrue(client.contains("conn.setInstanceFollowRedirects(false);"));
        assertTrue(client.contains("ProviderRouting.allowsHost(w.url)"));
        String act = src("app/src/com/onesay/assistant/v2/ui/LlmProvidersActivity.java");
        assertTrue(act.contains("FLAG_SECURE"));
        assertFalse("日志不记 Key", act.matches("(?s).*Log\\.[iwde]\\([^;]*\\+ *key[^;]*;.*"));
        assertTrue(src("app/src/com/onesay/assistant/v2/router/L2LLMCompiler.java").contains("ProviderRouting.mainKeyOverride()"));
        assertTrue(src("app/src/com/onesay/assistant/v2/net/LlmEndpoints.java").contains("ProviderStore.apply(c)"));
    }

    @Test public void shizukuPluginDefaultOffAndConsoleWired() throws Exception {
        String host = src("app/src/com/onesay/assistant/v2/platform/ExecBackendHost.java");
        assertTrue("默认关", host.contains("getBoolean(KEY_SHIZUKU, false)"));
        assertTrue(src("app/src/com/onesay/assistant/v2/executor/ShellExecutor.java").contains("ExecBackendHost.route(command)"));
        assertTrue(src("app/src/com/onesay/assistant/v2/ui/console/StatusProbe.java").contains("ExecBackendHost.shizukuReady(app)"));
        assertTrue(src("app/src/com/onesay/assistant/v2/ui/console/DeviceCapsPage.java").contains("ExecBackendHost.setShizukuEnabled(c.act, v)"));
        assertEquals(com.onesay.assistant.v2.platform.FeatureSpec.Status.SHIPPED,
                com.onesay.assistant.v2.platform.FeatureRegistry.get("exec.shizuku").status);
        assertFalse(com.onesay.assistant.v2.platform.FeatureRegistry.get("exec.shizuku").hasSwitch());
    }

    @SuppressWarnings("unused")
    private static String sha1(byte[] b) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-1").digest(b)) s.append(String.format("%02x", x));
        return s.toString();
    }
}
