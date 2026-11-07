package com.onesay.assistant.v2.security;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 凭据 Keystore 加密 + 明文迁移 + 降级（不丢数据）。 */
public class SecretPrefsTest {

    static final SecretCodec BROKEN = new SecretCodec() {
        @Override public String encrypt(String p) throws Exception { throw new java.security.KeyStoreException("no keystore"); }
        @Override public String decrypt(String b) throws Exception { throw new java.security.KeyStoreException("no keystore"); }
    };

    @After public void reset() { SecretPrefs.setCodecForTest(null); }

    @Test public void encodeDecodeRoundTripAndPrefix() {
        SoftCodec c = new SoftCodec();
        SecretValues.Write w = SecretValues.encode("sk-abc123", c);
        assertTrue(w.encrypted);
        assertTrue(w.stored.startsWith(SecretValues.PREFIX));
        assertFalse(w.stored.contains("sk-abc123"));
        SecretValues.Read r = SecretValues.decode(w.stored, c);
        assertEquals("sk-abc123", r.value);
        assertEquals(SecretValues.State.ENCRYPTED, r.state);
        assertEquals(SecretValues.State.EMPTY, SecretValues.decode("", c).state);
        assertEquals(SecretValues.State.PLAIN, SecretValues.decode("sk-old", c).state);
        assertTrue(SecretValues.decode("sk-old", c).needsMigration());
    }

    @Test public void keystoreFailureFallsBackToPlaintextNotLoss() {
        SecretValues.Write w = SecretValues.encode("pass-16chars-xxx", BROKEN);
        assertFalse(w.encrypted);
        assertEquals("pass-16chars-xxx", w.stored);
        assertEquals("KeyStoreException", w.error);
        // 回读不一致的实现（坏 TEE）同样降级
        SecretCodec liar = new SecretCodec() {
            @Override public String encrypt(String p) { return "AAAA"; }
            @Override public String decrypt(String b) { return "something-else"; }
        };
        SecretValues.Write w2 = SecretValues.encode("k", liar);
        assertFalse(w2.encrypted);
        assertEquals("k", w2.stored);
        assertEquals("roundtrip-mismatch", w2.error);
    }

    @Test public void getMigratesLegacyPlaintextInPlace() {
        SoftCodec c = new SoftCodec();
        SecretPrefs.setCodecForTest(c);
        FakePrefs sp = new FakePrefs();
        sp.map.put("deepseek_key", "sk-legacy");
        assertEquals("sk-legacy", SecretPrefs.get(sp, "deepseek_key"));
        String stored = (String) sp.map.get("deepseek_key");
        assertTrue("迁移后应为密文", stored.startsWith("ks1:"));
        assertEquals(1, sp.commits);
        // 再读：同值，不再写
        assertEquals("sk-legacy", SecretPrefs.get(sp, "deepseek_key"));
        assertEquals(1, sp.commits);
        assertEquals(SecretValues.State.ENCRYPTED, SecretPrefs.state(sp, "deepseek_key"));
    }

    @Test public void getWithBrokenKeystoreKeepsPlaintextAndReturnsIt() {
        SecretPrefs.setCodecForTest(BROKEN);
        FakePrefs sp = new FakePrefs();
        sp.map.put("pass", "imap-auth");
        assertEquals("imap-auth", SecretPrefs.get(sp, "pass"));
        assertEquals("imap-auth", sp.map.get("pass"));
        assertEquals(0, sp.commits);
        FakePrefs sp2 = new FakePrefs();
        android.content.SharedPreferences.Editor e = sp2.edit();
        SecretPrefs.put(e, "pass", "new-auth");
        e.apply();
        // OneSay：加密失败就不保存（不落明文）
        assertNull(sp2.map.get("pass"));
        assertEquals("", SecretPrefs.get(sp2, "pass"));
    }

    @Test public void undecryptableReturnsEmptyButKeepsBlob() {
        SecretPrefs.setCodecForTest(new SoftCodec((byte) 1));
        FakePrefs sp = new FakePrefs();
        android.content.SharedPreferences.Editor e = sp.edit();
        SecretPrefs.put(e, "tavily_key", "tvly-1");
        e.apply();
        String blob = (String) sp.map.get("tavily_key");
        // 换一把钥匙（= 数据被还原到另一次安装）
        SecretPrefs.setCodecForTest(new SoftCodec((byte) 2));
        assertEquals("", SecretPrefs.get(sp, "tavily_key"));
        assertEquals("原密文不能被删", blob, sp.map.get("tavily_key"));
        assertEquals(SecretValues.State.UNDECRYPTABLE, SecretPrefs.state(sp, "tavily_key"));
    }

    @Test public void putEncryptsAndEmptyStaysEmpty() {
        SoftCodec c = new SoftCodec();
        SecretPrefs.setCodecForTest(c);
        FakePrefs sp = new FakePrefs();
        android.content.SharedPreferences.Editor e = sp.edit();
        SecretPrefs.put(e, "ak_secret", "s3cr3t");
        SecretPrefs.put(e, "app_key", "");
        e.apply();
        assertTrue(((String) sp.map.get("ak_secret")).startsWith("ks1:"));
        assertEquals("", sp.map.get("app_key"));
        assertEquals("s3cr3t", SecretPrefs.get(sp, "ak_secret"));
        assertEquals(SecretValues.State.EMPTY, SecretPrefs.state(sp, "app_key"));
        // 缓存：第二次读不再解密
        int d = c.decrypts;
        SecretPrefs.get(sp, "ak_secret");
        assertEquals(d, c.decrypts);
    }

    @Test public void migrateSkipsWhenValueChangedConcurrently() {
        SecretPrefs.setCodecForTest(new SoftCodec());
        FakePrefs sp = new FakePrefs();
        sp.map.put("bocha_key", "new-value");
        assertFalse(SecretPrefs.migrateOne(sp, "bocha_key", "old-value"));
        assertEquals("new-value", sp.map.get("bocha_key"));
    }

    /** 守住：配置类里登记过的凭据键不能再有裸 getString / putString。 */
    @Test public void registryCoversConfigClassesAndNoRawAccess() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String[][] files = {
                {"app/src/com/onesay/assistant/v2/asr/AliyunAsrConfig.java", "K_AK_ID", "K_AK_SECRET", "K_APP_KEY", "K_MANUAL_TOKEN", "K_DASHSCOPE_KEY", "K_REMOTE_TOKEN_URL"},
                {"app/src/com/onesay/assistant/v2/router/L2LLMCompiler.java", "KEY_API_KEY"},
                {"app/src/com/onesay/assistant/v2/search/SearchConfig.java", "K_TAVILY_KEY", "K_BOCHA_KEY"},
                {"app/src/com/onesay/assistant/v2/executor/MailBridge.java", "KEY_PASS"},
        };
        for (String[] f : files) {
            String src = new String(Files.readAllBytes(new File(root, f[0]).toPath()), StandardCharsets.UTF_8);
            for (int i = 1; i < f.length; i++) {
                assertFalse(f[0] + " 裸读 " + f[i], src.contains("getString(" + f[i]));
                assertFalse(f[0] + " 裸写 " + f[i], src.contains("putString(" + f[i]));
            }
        }
        java.util.Set<String> keys = new java.util.HashSet<String>();
        for (String[] row : SecretPrefs.REGISTRY) keys.add(row[0] + "/" + row[1]);
        for (String k : new String[]{"bixby_llm_cfg/deepseek_key", "bixby_asr_cfg/ak_secret", "bixby_asr_cfg/dashscope_key",
                "bixby_mail_cfg/pass", "bixby_search_cfg/tavily_key", "bixby_search_cfg/bocha_key", "bixby_asr_cfg/remote_token_url"}) {
            assertTrue(k, keys.contains(k));
        }
    }

    /** r2/fixes 4：remote_token_url 旧明文一次性迁移成密文，明文不再留在 prefs 里。 */
    @Test public void remoteTokenUrlPlaintextMigratedAndRemoved() {
        SecretPrefs.setCodecForTest(new SoftCodec());
        FakePrefs sp = new FakePrefs();
        String url = "https://token.example.com/issue?token=abc123SECRET";
        sp.map.put("remote_token_url", url);
        assertEquals(url, SecretPrefs.get(sp, "remote_token_url"));
        String stored = (String) sp.map.get("remote_token_url");
        assertTrue("迁移后是密文", stored.startsWith("ks1:"));
        assertFalse("明文不再留在 prefs", stored.contains("abc123SECRET"));
        for (Object v : sp.map.values()) assertFalse(String.valueOf(v).contains("abc123SECRET"));
        assertEquals(SecretValues.State.ENCRYPTED, SecretPrefs.state(sp, "remote_token_url"));
        assertEquals("再读同值", url, SecretPrefs.get(sp, "remote_token_url"));
    }
}
