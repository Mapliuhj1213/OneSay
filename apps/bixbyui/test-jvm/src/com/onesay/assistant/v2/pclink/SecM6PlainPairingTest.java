package com.onesay.assistant.v2.pclink;

import static org.junit.Assert.*;
import org.junit.Test;

/** ★ fix-1118 SEC M6：旧版（v1 明文，没有证书指纹）配对码默认拒；用户明确允许才收；v2 带指纹的照常。 */
public class SecM6PlainPairingTest {
    static String key() {
        byte[] k = new byte[32];
        for (int i = 0; i < k.length; i++) k[i] = (byte) (i * 7 + 3);
        return PcB64.encodeUrl(k);
    }

    static final String FP = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Test public void plainV1RefusedByDefault() {
        PcPairing p = PcPairing.parse(PcPairing.PREFIX + "key=" + key() + "&peer=pc-home&lan=192.168.1.5%3A47820");
        assertEquals("", p.fp);
        try {
            PcPairing.requireSecure(p, false);
            fail("v1 明文应被拒");
        } catch (IllegalArgumentException e) {
            assertEquals(PcPairing.PLAIN_REFUSED, e.getMessage());
            assertFalse(e.getMessage().contains("Bixby"));
        }
        PcPairing.requireSecure(p, true);   // 明确允许 → 收
    }

    @Test public void v2WithFingerprintAccepted() {
        assertTrue(PcTls.validFp(FP));
        PcPairing p = PcPairing.parse(PcPairing.PREFIX + "key=" + key() + "&peer=pc-home&lan=192.168.1.5%3A47820&fp=" + FP + "&v=2");
        PcPairing.requireSecure(p, false);
        assertEquals(FP, p.fp);
    }
}
