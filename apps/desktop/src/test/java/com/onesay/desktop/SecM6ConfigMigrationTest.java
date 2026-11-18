package com.onesay.desktop;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** ★ fix-1118 SEC M6：旧配置里自动写进去的 lan_plain=on 迁移成关；保存后带标记，之后用户明确打开的 on 才算数。 */
public class SecM6ConfigMigrationTest {
    static Config load(String body) throws Exception {
        Path d = Files.createTempDirectory("m6cfg");
        Path f = d.resolve("desktop.properties");
        Files.write(f, body.getBytes(StandardCharsets.UTF_8));
        return Config.load(f);
    }

    @Test public void legacyOnWithoutMarkerBecomesOff() throws Exception {
        assertFalse(load("lan_plain=on\n").lanPlain);
        assertFalse(load("").lanPlain);
        assertFalse(load("lan_plain=off\nlan_plain_v=2\n").lanPlain);
    }

    @Test public void explicitOnAfterMigrationKept() throws Exception {
        assertTrue(load("lan_plain=on\nlan_plain_v=2\n").lanPlain);
    }

    @Test public void saveWritesMarkerAndRoundTrips() throws Exception {
        Config c = load("lan_plain=on\n");
        assertFalse(c.lanPlain);
        c.save();
        String s = new String(Files.readAllBytes(c.file), StandardCharsets.UTF_8);
        assertTrue(s.contains("lan_plain_v=2"));
        assertTrue(s.contains("lan_plain=off"));
        c.lanPlain = true; c.save();
        assertTrue(Config.load(c.file).lanPlain);
    }
}
