package com.onesay.assistant.v2.pclink;

import static org.junit.Assert.*;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import org.junit.Test;

/**
 * ★ fix-1118 扫码配对：电脑端二维码里的配对串 → 扫码页同款解码（ZXing core，灰度帧 → PlanarYUVLuminanceSource → 只认 QR）→
 * 和「粘贴配对码」同一套校验（PcPairing.looksLike / parse / requireSecure）。不碰相机、不碰 Context。
 */
public class PcQrPairingTest {
    static final String FP = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    static String key() {
        byte[] k = new byte[32];
        for (int i = 0; i < k.length; i++) k[i] = (byte) (i * 11 + 5);
        return PcB64.encodeUrl(k);
    }

    static String v2() {
        return PcPairing.PREFIX + "key=" + key() + "&peer=desktop-demo01&lan=192.168.1.14:47820&fp=" + FP + "&v=2";
    }

    /** 和 PcScanActivity 一样：灰度（Y 平面）帧交给 MultiFormatReader，只开 QR_CODE。 */
    static String scan(String payload) throws Exception {
        Map<EncodeHintType, Object> eh = new EnumMap<>(EncodeHintType.class);
        eh.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        eh.put(EncodeHintType.MARGIN, 4);
        BitMatrix m = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 480, 480, eh);
        int w = m.getWidth(), h = m.getHeight();
        byte[] y = new byte[w * h];
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) y[r * w + c] = (byte) (m.get(c, r) ? 0 : 255);
        MultiFormatReader reader = new MultiFormatReader();
        Map<DecodeHintType, Object> dh = new EnumMap<>(DecodeHintType.class);
        dh.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        dh.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        reader.setHints(dh);
        return reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(new PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false)))).getText();
    }

    @Test public void v2QrRoundTripsAndPassesSameValidationAsPaste() throws Exception {
        String text = scan(v2());
        assertEquals(v2(), text);
        assertTrue(PcPairing.looksLike(text));
        PcPairing p = PcPairing.parse(text);
        PcPairing.requireSecure(p, false);
        assertEquals("desktop-demo01", p.peer);
        assertEquals(FP, p.fp);
        assertEquals(47820, p.lanPort);
        assertEquals(32, p.key.length);
    }

    @Test public void otherQrCodesAreNotPairing() throws Exception {
        assertFalse(PcPairing.looksLike(scan("https://example.com/pair?key=x")));
        assertFalse(PcPairing.looksLike(scan("WIFI:S:home;T:WPA;P:secret;;")));
    }

    @Test public void plainV1QrRefusedByDefault() throws Exception {
        String text = scan(PcPairing.PREFIX + "key=" + key() + "&peer=pc-old&lan=192.168.1.14:47820");
        PcPairing p = PcPairing.parse(text);
        try {
            PcPairing.requireSecure(p, false);
            fail("v1 明文码扫进来也要拒");
        } catch (IllegalArgumentException e) {
            assertEquals(PcPairing.PLAIN_REFUSED, e.getMessage());
        }
    }

    @Test public void tamperedQrPayloadsRejected() throws Exception {
        String[] bad = {
            PcPairing.PREFIX + "key=" + key() + "&peer=desktop&lan=192.168.1.14:47820&v=2",              // v2 缺指纹
            PcPairing.PREFIX + "key=" + key() + "&peer=desktop&lan=192.168.1.14:47820&fp=short&v=2",     // 指纹坏
            PcPairing.PREFIX + "key=AAAA&peer=desktop&lan=192.168.1.14:47820&fp=" + FP + "&v=2",         // 钥匙太短
            PcPairing.PREFIX + "key=" + key() + "&peer=desktop&lan=192.168.1.14:47820&fp=" + FP + "&v=9", // 版本太新
            PcPairing.PREFIX + "key=" + key() + "&lan=192.168.1.14:47820&fp=" + FP + "&v=2",              // 没 peer
        };
        for (String b : bad) {
            String text = scan(b);
            try {
                PcPairing.parse(text);
                fail("应拒：" + b.replace(key(), "<key>"));
            } catch (IllegalArgumentException e) {
                String msg = e.getMessage();
                assertNotNull(msg);
                assertFalse(msg.contains(key()));
                for (String w : new String[]{"Bixby", "三星", "小布"}) assertFalse(msg.contains(w));
            }
        }
    }
}
