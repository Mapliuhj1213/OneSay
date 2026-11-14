package com.onesay.desktop;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import org.junit.Test;

/**
 * 2026-11-13 集成分支 integ/2026-11-13 上落实的安装程序默认做法：
 * 中文用户名时 ProgramData 回退按账户分子目录、下载目录不回退、安装程序的发布者 / 版权 / 桌面快捷方式 / 不附 source.zip、
 * 用户可见文字不出现 Bixby / 三星、链接下载工具按需下载的说明。
 */
public class IntegTest {

    private static String read(String p) throws Exception {
        return Files.readString(Path.of(p));
    }

    @Test public void accountTagIsAsciiAndPerAccount() {
        assertEquals("_674E_96F7", UserDirs.accountTag("C:\\Users\\李雷"));
        assertEquals("尾部分隔符不影响", "_674E_96F7", UserDirs.accountTag("C:\\Users\\李雷\\"));
        assertEquals("_5F20_4E09", UserDirs.accountTag("C:/Users/张三"));
        assertEquals("me", UserDirs.accountTag("C:\\Users\\me"));
        assertEquals("点、空格、下划线都转义（_ 只用作转义前缀，不会撞）", "a_002Eb_0020c_005Fd-1", UserDirs.accountTag("C:\\Users\\a.b c_d-1"));
        assertEquals("_674E_96F7_002EPC-1", UserDirs.accountTag("C:\\Users\\李雷.PC-1"));
        assertEquals("default", UserDirs.accountTag(""));
        assertEquals("default", UserDirs.accountTag(null));
        String t = UserDirs.accountTag("C:\\Users\\😀");
        assertEquals("代理对按两个 UTF-16 字符写", "_D83D_DE00", t);
        for (char c : UserDirs.accountTag("C:\\Users\\李雷 ü").toCharArray()) assertTrue(c < 0x7f);
        assertNotEquals("两个中文账户不共用", UserDirs.accountTag("C:\\Users\\李雷"), UserDirs.accountTag("C:\\Users\\张三"));
    }

    @Test public void workspaceFallbackIsPerAccount() {
        assertEquals("C:\\Users\\me\\OneSay\\workspace", UserDirs.workspaceDir("C:\\Users\\me", "C:\\ProgramData"));
        assertEquals("C:\\Users\\me\\OneSay\\workspace", UserDirs.workspaceDir("C:\\Users\\me\\", "C:\\ProgramData"));
        assertEquals("C:\\ProgramData\\OneSay\\accounts\\_674E_96F7\\workspace", UserDirs.workspaceDir("C:\\Users\\李雷", "C:\\ProgramData"));
        assertEquals("C:\\ProgramData\\OneSay\\accounts\\_738B_5C0F_660E\\workspace", UserDirs.workspaceDir("C:\\Users\\王小明", null));
        assertEquals("D:\\PD\\OneSay\\accounts\\_5F20_4E09\\workspace", UserDirs.workspaceDir("C:\\Users\\张三", "D:\\PD\\"));
        assertNotEquals(UserDirs.workspaceDir("C:\\Users\\李雷", "C:\\ProgramData"), UserDirs.workspaceDir("C:\\Users\\张三", "C:\\ProgramData"));
        assertEquals("没有用户目录时退到 default", "C:\\ProgramData\\OneSay\\accounts\\default\\workspace", UserDirs.workspaceDir("", "C:\\ProgramData"));
    }

    @Test public void configUsesPerAccountWorkspaceAndLinkPolicyCopyIsUntouched() throws Exception {
        String cfg = read("src/main/java/com/onesay/desktop/Config.java");
        assertTrue(cfg.contains("UserDirs.workspaceDir(up, System.getenv(\"ProgramData\"))"));
        assertFalse("电脑端不再用 LinkPolicy 的共用回退算路径", cfg.contains("LinkPolicy.pcWorkspaceDir("));
        // LinkPolicy 三端同步：旧函数原样保留（手机 / 开源版测试钉着）
        assertEquals("C:\\ProgramData\\OneSay\\workspace", LinkPolicy.pcWorkspaceDir("C:\\Users\\王小明", "C:\\ProgramData"));
    }

    @Test public void toolsDirFallsBackPerAccountSameTagAsWorkspace() {
        assertEquals("C:\\Users\\me\\AppData\\Local\\OneSay\\tools", MediaTools.toolsDir("C:\\Users\\me\\AppData\\Local", "C:\\ProgramData", "/h", "C:\\Users\\me"));
        String tools = MediaTools.toolsDir("C:\\Users\\李雷\\AppData\\Local", "C:\\ProgramData", "/h", "C:\\Users\\李雷");
        assertEquals("C:\\ProgramData\\OneSay\\accounts\\_674E_96F7\\tools", tools);
        assertEquals("不给 USERPROFILE 时从 LOCALAPPDATA 推出同一个账户", tools, MediaTools.toolsDir("C:\\Users\\李雷\\AppData\\Local", "C:\\ProgramData", "/h"));
        assertEquals("大小写 / 斜杠不同也认", tools, MediaTools.toolsDir("C:/Users/李雷/appdata/local/", "C:\\ProgramData", "/h"));
        String ws = UserDirs.workspaceDir("C:\\Users\\李雷", "C:\\ProgramData");
        assertEquals("工具和工作区在同一个账户目录下", UserDirs.fallbackRoot("C:\\Users\\李雷", "C:\\ProgramData"),
                ws.substring(0, ws.lastIndexOf('\\')));
        assertTrue(MediaTools.toolsDir(null, null, "/home/u", null).endsWith("onesay/tools"));
    }

    @Test public void downloadsDirNeverFallsBack() throws Exception {
        assertEquals(Paths.get("C:\\Users\\李雷", "OneSay", "Downloads"), UserDirs.downloadsDir("", "C:\\Users\\李雷", "/h"));
        assertFalse(UserDirs.downloadsDir(null, "C:\\Users\\李雷", "/h").toString().contains("ProgramData"));
        assertEquals(Paths.get("E:\\视频"), UserDirs.downloadsDir(" E:\\视频 ", "C:\\Users\\李雷", "/h"));
        assertEquals(Paths.get("/h", "OneSay", "Downloads"), UserDirs.downloadsDir("", null, "/h"));
        assertTrue(read("src/main/java/com/onesay/desktop/Media.java").contains("UserDirs.downloadsDir(configured"));
    }

    @Test public void installerDefaultsAccepted() throws Exception {
        String nsi = read("installer/OneSay.nsi");
        assertTrue(nsi.contains("WriteRegStr   HKCU \"${UNINSTKEY}\" \"Publisher\"            \"OneSay\""));
        assertTrue(nsi.contains("VIAddVersionKey /LANG=0 \"LegalCopyright\" \"© 2026 OneSay contributors\""));
        assertTrue(nsi.contains("VIAddVersionKey /LANG=0 \"CompanyName\" \"OneSay\""));
        assertTrue("桌面快捷方式默认勾选（Section 不带 /o）", nsi.contains("Section \"$(SecDesktopName)\" SecDesktop"));
        assertFalse(nsi.contains("Section /o \"$(SecDesktopName)\""));
        assertTrue("开机自启仍默认不勾", nsi.contains("Section /o \"$(SecAutoName)\" SecAuto"));
        assertTrue("删除数据默认不勾", nsi.contains("Section /o \"un.$(SecDataName)\" UnSecData"));
        // 卸载只删本账户的回退目录，标记规则与 UserDirs 相同
        assertTrue(nsi.contains("Function un.AccountTag"));
        assertTrue(nsi.contains("IntFmt $4 \"_%04X\" $4"));
        assertTrue(nsi.contains("${ElseIf} $4 == 0x2D"));
        assertTrue(nsi.contains("RMDir /r \"$1\\OneSay\\accounts\\$2\""));
        assertFalse("不再整个删共用的 %ProgramData%\\OneSay\\workspace", nsi.contains("RMDir /r \"$1\\OneSay\\workspace\""));
        assertTrue(nsi.contains("RMDir /r \"$LOCALAPPDATA\\OneSay\\tools\""));
        assertFalse("下载的文件不删", nsi.contains("Downloads\"") && nsi.contains("RMDir /r \"$PROFILE\\OneSay\\Downloads"));
        assertFalse("首版不签名：脚本里没有签名步骤", nsi.contains("!uninstfinalize") || nsi.contains("!finalize"));

        String sh = read("installer/build-installer.sh");
        assertFalse("不附 source.zip", sh.contains("git archive"));
        for (String line : sh.split("\n")) if (!line.trim().startsWith("#")) assertFalse(line, line.contains("source.zip"));
        assertTrue(sh.contains("cp \"$ROOT/LICENSE\" \"$STAGE/LICENSE.txt\""));
        assertTrue(sh.contains("cp \"$ROOT/NOTICE\" \"$STAGE/NOTICE.txt\""));
        assertTrue(sh.contains("cp \"$ROOT/THIRD_PARTY_NOTICES.md\" \"$STAGE/THIRD_PARTY_NOTICES.md\""));
        assertTrue("随包的许可文件卸载时删掉", nsi.contains("Delete \"$INSTDIR\\LICENSE.txt\"") && nsi.contains("Delete \"$INSTDIR\\NOTICE.txt\"")
                && nsi.contains("Delete \"$INSTDIR\\THIRD_PARTY_NOTICES.md\""));
    }

    @Test public void userVisibleTextHasNoBixbyOrSamsung() throws Exception {
        String nsi = read("installer/OneSay.nsi");
        for (String line : nsi.split("\n")) {
            String l = line.toLowerCase(Locale.ROOT);
            if (line.startsWith("LangString") || line.startsWith("VIAddVersionKey") || line.contains("BrandingText") || line.contains("WriteRegStr")) {
                assertFalse(line, l.contains("bixby") || l.contains("samsung") || line.contains("三星"));
            }
        }
        String ui = read("src/main/resources/onesay/ui/index.html").toLowerCase(Locale.ROOT);
        assertFalse(ui.contains("bixby") || ui.contains("samsung") || ui.contains("三星"));
        for (String doc : new String[] {"docs/PCLINK.md", "docs/CONSOLE.md", "installer/README.md", "NOTICE", "THIRD_PARTY_NOTICES.md"}) {
            String d = read(doc).toLowerCase(Locale.ROOT);
            assertFalse(doc, d.contains("bixby") || d.contains("samsung") || d.contains("三星"));
        }
    }

    @Test public void installNotesExplainOnDemandTools() throws Exception {
        String r = read("installer/README.md");
        assertTrue(r.contains("安装包里**没有** yt-dlp 和 FFmpeg"));
        assertTrue(r.contains("**LGPL 静态构建**"));
        assertTrue(r.contains("不会退到 GPL 构建"));
        assertTrue(r.contains("SHA2-256SUMS") && r.contains("checksums.sha256"));
        assertTrue(r.contains("**首版不签名**"));
        assertTrue(r.contains("**不附 source.zip**"));
        assertTrue(r.contains("© 2026 OneSay contributors"));
        assertTrue(read("docs/PCLINK.md").contains("## 按账户的目录（2026-11-13 集成）"));
    }
}
