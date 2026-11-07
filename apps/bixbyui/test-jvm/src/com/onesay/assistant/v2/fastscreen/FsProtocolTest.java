package com.onesay.assistant.v2.fastscreen;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** ★ fast-screen-v2：助手协议（编码、参数白名单、帧、对端鉴权）+ FsTree 序列化 / XML。 */
public class FsProtocolTest {

    @Test public void encodeDecodeStripsSeparators() {
        String l = FsProtocol.encode("settext", "540", "300", "第一行\n第二\t行");
        String[] p = FsProtocol.decode(l);
        assertEquals(4, p.length);
        assertEquals("第一行 第二 行", p[3]);
        assertEquals(0, FsProtocol.decode(null).length);
        assertEquals(1, FsProtocol.decode("ping").length);
    }

    @Test public void validateWhitelistsOpsAndRanges() {
        assertNull(FsProtocol.validate(new String[]{"ping"}));
        assertEquals("EMPTY", FsProtocol.validate(new String[0]));
        assertTrue(FsProtocol.validate(new String[]{"exec", "rm"}).startsWith("OP_NOT_ALLOWED"));
        assertNull(FsProtocol.validate(new String[]{"tap", "540", "300"}));
        assertEquals("ARGC", FsProtocol.validate(new String[]{"tap", "540"}));
        assertTrue(FsProtocol.validate(new String[]{"tap", "-1", "300"}).startsWith("RANGE"));
        assertEquals("NUM", FsProtocol.validate(new String[]{"tap", "x", "300"}));
        assertNull(FsProtocol.validate(new String[]{"key", "4"}));
        assertTrue(FsProtocol.validate(new String[]{"key", "26"}).startsWith("KEY_NOT_ALLOWED"));   // 电源键不许
        assertNull(FsProtocol.validate(new String[]{"launch", "com.android.settings"}));
        assertEquals("BAD_PKG", FsProtocol.validate(new String[]{"launch", "a;reboot"}));
        assertNull(FsProtocol.validate(new String[]{"long", "1", "1", "800"}));
        assertNotNull(FsProtocol.validate(new String[]{"long", "1", "1", "9000"}));
        assertNull(FsProtocol.validate(new String[]{"swipe", "1", "2", "3", "4", "300"}));
        assertNotNull(FsProtocol.validate(new String[]{"scroll", "1", "2", "5"}));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2001; i++) big.append('x');
        assertEquals("TEXT_TOO_LONG", FsProtocol.validate(new String[]{"settext", "1", "1", big.toString()}));
        assertNull(FsProtocol.validate(new String[]{"backwatch", "0", "0", "1", "1", "2", "2", "3", "3"}));
        assertEquals("ARGC", FsProtocol.validate(new String[]{"backwatch", "0"}));
        assertNull(FsProtocol.validate(new String[]{"idle", "300", "3000"}));
        assertNotNull(FsProtocol.validate(new String[]{"shot", "99"}));
    }

    @Test public void framesRoundTripAndRejectOversize() throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        FsProtocol.writeRequest(new DataOutputStream(bo), "tree\t1200");
        assertEquals("tree\t1200", FsProtocol.readRequest(new DataInputStream(new ByteArrayInputStream(bo.toByteArray()))));
        bo.reset();
        FsProtocol.writeResponse(new DataOutputStream(bo), FsProtocol.ST_OK, "好");
        FsProtocol.Resp r = FsProtocol.readResponse(new DataInputStream(new ByteArrayInputStream(bo.toByteArray())));
        assertTrue(r.ok());
        assertEquals("好", r.text());
        byte[] bad = {0x7f, 0, 0, 0};
        try {
            FsProtocol.readRequest(new DataInputStream(new ByteArrayInputStream(bad)));
            fail();
        } catch (IOException expected) {
        }
    }

    @Test public void openSourceBuildPinsNothing() {
        assertEquals("开源版不内置证书", 0, FsProtocol.PINNED.length);
        assertNull(FsProtocol.pinnedSig("com.onesay.assistant"));
        assertNull(FsProtocol.pinnedSig("com.onesay.assistant.debug"));
    }

    @Test public void authorizePeerFailsClosedWithoutPins() {
        String any = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
        assertTrue(FsProtocol.authorizePeer(10239, 10239, "com.onesay.assistant", new String[]{"com.onesay.assistant"}, new String[]{any}).startsWith("NOT_PINNED"));
        assertTrue(FsProtocol.authorizePeer(10240, 10239, "com.onesay.assistant", new String[]{"com.onesay.assistant"}, new String[]{any}).startsWith("UID"));
        assertEquals("PKG", FsProtocol.authorizePeer(1, 1, "com.onesay.assistant", new String[]{"com.evil", "com.onesay.assistant"}, new String[]{any}));
    }

    @Test public void socketAndProcessNamesPerPackage() {
        assertEquals("bixbyui_fs.com.onesay.assistant.debug", FsProtocol.socketName("com.onesay.assistant.debug"));
        assertEquals("bixbyui_fs:com.onesay.assistant.debug", FsProtocol.niceName("com.onesay.assistant.debug"));
        assertNotEquals(FsProtocol.socketName("com.onesay.assistant"), FsProtocol.socketName("com.onesay.assistant.debug"));
    }

    static FsTree sample() {
        FsTree t = new FsTree();
        t.pkg = "com.android.settings";
        t.activity = "x.Settings";
        t.ms = 12;
        FsTree.Node r = new FsTree.Node();
        r.cls = "android.widget.FrameLayout";
        r.r = 1080;
        r.b = 2220;
        r.flags = FsTree.F_ENABLED;
        t.nodes.add(r);
        FsTree.Node layout = new FsTree.Node();
        layout.parent = 0;
        layout.cls = "android.widget.LinearLayout";
        layout.r = 1080;
        layout.b = 500;
        t.nodes.add(layout);
        FsTree.Node b = new FsTree.Node();
        b.parent = 1;
        b.cls = "android.widget.Button";
        b.text = "WLAN \"<&>\"\n二行";
        b.rid = "android:id/title";
        b.l = 10;
        b.t = 20;
        b.r = 300;
        b.b = 120;
        b.flags = FsTree.F_CLICK | FsTree.F_ENABLED | FsTree.F_CHECKED;
        t.nodes.add(b);
        return t;
    }

    @Test public void jsonRoundTrip() throws Exception {
        FsTree t = sample();
        FsTree u = FsTree.fromJson(t.toJson());
        assertEquals(t.pkg, u.pkg);
        assertEquals(t.activity, u.activity);
        assertEquals(3, u.nodes.size());
        assertEquals("WLAN \"<&>\"\n二行", u.nodes.get(2).text);
        assertEquals(1, u.nodes.get(2).parent);
        assertTrue(u.nodes.get(2).has(FsTree.F_CHECKED));
        assertEquals(155, u.nodes.get(2).cx());
    }

    @Test public void uiautomatorXmlCompatibleAndCompressed() {
        String full = sample().toUiautomatorXml(false);
        assertTrue(full.startsWith("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"));
        assertTrue(full.contains("text=\"WLAN &quot;&lt;&amp;&gt;&quot;&#10;二行\""));
        assertTrue(full.contains("bounds=\"[10,20][300,120]\""));
        assertTrue(full.contains("clickable=\"true\""));
        assertEquals(3, full.split("<node ", -1).length - 1);
        String c = sample().toUiautomatorXml(true);
        assertEquals(2, c.split("<node ", -1).length - 1);   // 纯布局 LinearLayout 去掉，按钮上提到根下
        assertTrue(c.endsWith("</node></hierarchy>"));
    }
}
