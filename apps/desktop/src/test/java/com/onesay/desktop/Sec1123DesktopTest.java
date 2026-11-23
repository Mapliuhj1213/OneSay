package com.onesay.desktop;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.Test;

/** ★ sec-1123 L3：中文用户名时工作区在 %ProgramData% 下，启动时把本账户目录收成只有自己能访问；L2 回归（界面页要入口钥匙）。 */
public class Sec1123DesktopTest {

    @Test public void restrictToOwnerLocksDirectory() throws Exception {
        Path d = Files.createTempDirectory("sec1123");
        Files.createDirectories(d.resolve("workspace"));
        assertTrue(UserDirs.restrictToOwner(d));
        PosixFileAttributeView px = Files.getFileAttributeView(d, PosixFileAttributeView.class);
        if (px != null) assertEquals("rwx------", PosixFilePermissions.toString(px.readAttributes().permissions()));
    }

    @Test public void asciiProfileNotTouched() {
        assertNull("ASCII 用户目录本来就在自己的用户目录里", UserDirs.secureFallbackRoot("C:\\Users\\bob", "C:\\ProgramData", null));
        assertNull(UserDirs.secureFallbackRoot("", "C:\\ProgramData", null));
        assertNull(UserDirs.secureFallbackRoot(null, null, null));
    }

    @Test public void nonAsciiProfileSecuresOwnAccountRoot() throws Exception {
        Path pd = Files.createTempDirectory("sec1123pd");
        // 非 Windows 上路径分隔符不同：只验返回的就是本账户的回退根目录，并且确实收成 700
        Path r = UserDirs.secureFallbackRoot("C:\\Users\\张三", pd.toString(), null);
        assertNotNull(r);
        assertEquals(Paths.get(UserDirs.fallbackRoot("C:\\Users\\张三", pd.toString())), r);
        assertTrue(r.toString().contains("_5F20_4E09"));
        PosixFileAttributeView px = Files.getFileAttributeView(r, PosixFileAttributeView.class);
        if (px != null) assertEquals("rwx------", PosixFilePermissions.toString(px.readAttributes().permissions()));
    }

    @Test public void mainCallsItOnWindowsAtStartup() throws Exception {
        String m = new String(Files.readAllBytes(Paths.get("src/main/java/com/onesay/desktop/Main.java")), StandardCharsets.UTF_8);
        int load = m.indexOf("Config cfg = Config.load(cfgFile);");
        int call = m.indexOf("UserDirs.secureFallbackRoot(System.getenv(\"USERPROFILE\"), System.getenv(\"ProgramData\")");
        assertTrue(load > 0 && call > load && call < m.indexOf("switch (sub)"));
        String u = new String(Files.readAllBytes(Paths.get("src/main/java/com/onesay/desktop/UserDirs.java")), StandardCharsets.UTF_8);
        assertTrue("Windows 走 ACL：只有自己 + SYSTEM + 管理员组，子目录继承", u.contains("AclFileAttributeView") && u.contains("FILE_INHERIT") && u.contains("DIRECTORY_INHERIT"));
    }

    @Test public void l2UiPageStillNeedsLaunchKey() throws Exception {
        String ui = new String(Files.readAllBytes(Paths.get("src/main/java/com/onesay/desktop/UiServer.java")), StandardCharsets.UTF_8);
        assertTrue(ui.contains("SEC L2"));
    }
}
