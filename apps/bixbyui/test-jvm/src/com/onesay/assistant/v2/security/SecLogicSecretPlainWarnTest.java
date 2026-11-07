package com.onesay.assistant.v2.security;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/** 登记键读到明文时告警（只记键名，不打值）。 */
public class SecLogicSecretPlainWarnTest {

    @After public void reset() { SecretPrefs.setCodecForTest(null); }

    @Test public void registered_key_plaintext_is_flagged() {
        SecretPrefs.setCodecForTest(new SoftCodec());
        FakePrefs sp = new FakePrefs();
        sp.map.put("remote_token_url", "http://planted.example/x");
        sp.map.put("not_registered_key", "v");
        assertTrue(SecretPrefs.isRegistered("remote_token_url"));
        assertFalse(SecretPrefs.isRegistered("not_registered_key"));
        assertEquals("http://planted.example/x", SecretPrefs.get(sp, "remote_token_url"));
        assertTrue(SecretPrefs.warnedPlain("remote_token_url"));
        SecretPrefs.get(sp, "not_registered_key");
        assertFalse(SecretPrefs.warnedPlain("not_registered_key"));
    }
}
