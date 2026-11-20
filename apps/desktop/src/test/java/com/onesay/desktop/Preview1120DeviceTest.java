package com.onesay.desktop;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.*;

/**
 * [preview-1120 真机综合测试] 电脑上用真模型跑「考研准备 / 出差准备 / 下载」时发现的问题：
 * 只读 netsh show 被当成「要管理员」、被拦的下载命令看不到原文且常见包装认不出、失败原因括号重复、
 * 通知标题「任务你拒绝了」、通知标签完成 / 拒绝没有底色、通知页按钮被挤成两行。
 */
public class Preview1120DeviceTest {

    static String admin(String cmd) { return LinkPolicy.adminReason(LinkPolicy.normalizeCommand(cmd)); }

    @Test public void readOnlyNetshShowIsNotAdmin() {
        for (String c : new String[] {
                "netsh wlan show interfaces",
                "Get-TimeZone | Select-Object Id,DisplayName; Get-Date; Write-Output \"---WIFI---\"; netsh wlan show interfaces",
                "netsh interface ip show config",
                "netsh.exe wlan show networks mode=bssid",
                "netsh advfirewall show allprofiles state"})
            assertEquals(c, "", admin(c));
        for (String c : new String[] {
                "netsh advfirewall set allprofiles state off",
                "netsh interface ip set dns name=Ethernet static 1.1.1.1",
                "netsh wlan delete profile name=*",
                "netsh wlan connect name=Cafe",
                "netsh winhttp set proxy 1.2.3.4:8080",
                "netsh advfirewall reset",
                "netsh wlan show profile name=Home key=clear",
                "netsh -f evil.txt",
                "netsh wlan show interfaces; netsh int ip reset",
                "netsh exec show.txt"})
            assertFalse(c, admin(c).isEmpty());
        assertTrue(LinkPolicy.netshReadOnly(" wlan show interfaces"));
        assertFalse(LinkPolicy.netshReadOnly(" wlan show profile name=x key = clear"));
    }

    @Test public void downloadRewriteTakesCallOperatorRedirectAndCreateDirs() {
        String u = "https://mirrors.aliyun.com/ubuntu/ls-lR.gz";
        for (String c : new String[] {
                "& curl.exe -L -o ls-lR-test.gz " + u,
                "curl.exe -L -o ls-lR-test.gz " + u + " 2>&1",
                "curl.exe -L --create-dirs -o sub\\ls-lR-test.gz " + u,
                "& Invoke-WebRequest -Uri " + u + " -OutFile ls-lR-test.gz *>&1"}) {
            DownloadRewrite.Dl d = DownloadRewrite.parse(c);
            assertNotNull(c, d);
            assertEquals(u, d.url);
        }
        // 仍然不猜：多条语句、& 后面不是下载命令、关证书校验
        assertNull(DownloadRewrite.parse("Set-Location C:\\x; curl.exe -L -o a.gz " + u));
        assertNull(DownloadRewrite.parse("& .\\tool.exe -o a.gz " + u));
        assertNull(DownloadRewrite.parse("& curl.exe -k -o a.gz " + u));
        assertTrue(DownloadRewrite.hint().contains("不要回头说命令被禁止"));
    }

    @Test public void heldDownloadNoteShowsTheCommand() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/onesay/desktop/ChatAgent.java"), StandardCharsets.UTF_8);
        assertTrue(src.contains("\"下载文件改用下载工具（任务卡上有进度条），这条命令先不执行：\" + clip(LinkPolicy.oneLine(String.valueOf(args.get(\"command\"))), 160)"));
    }

    @Test public void failedStepSummaryDoesNotRepeatReason() {
        Map<String, Object> d = new TreeMap<>();
        d.put("reason", LinkPolicy.R_NEEDS_ADMIN_SCRIPT);
        d.put("reason_zh", LinkPolicy.reasonZh(LinkPolicy.R_NEEDS_ADMIN_SCRIPT));
        String t = ChatAgent.summary(new Actions.Result(false, "这件事要管理员，已生成脚本，需你自己用管理员身份执行：scripts/x.ps1", d));
        assertEquals("这件事要管理员，已生成脚本，需你自己用管理员身份执行：scripts/x.ps1", t);
        Map<String, Object> d2 = new TreeMap<>();
        d2.put("reason", LinkPolicy.R_CONFIRM_DENIED);
        d2.put("reason_zh", LinkPolicy.reasonZh(LinkPolicy.R_CONFIRM_DENIED));
        String t2 = ChatAgent.summary(new Actions.Result(false, "电脑上拒绝了", d2));
        assertFalse(t2, t2.contains("对方"));
        // 别的原因照旧括上
        Map<String, Object> d3 = new TreeMap<>();
        d3.put("reason", LinkPolicy.R_UNTRUSTED_URL);
        d3.put("reason_zh", "网址不在信任列表");
        assertEquals("没打开（网址不在信任列表）", ChatAgent.summary(new Actions.Result(false, "没打开", d3)));
    }

    @Test public void deniedNotificationTitleAndLabelColors() throws Exception {
        assertEquals("被你拒绝", Outcomes.titleZh("denied"));
        assertEquals("完成", Outcomes.titleZh(Outcomes.DONE));
        assertEquals("卡住了", Outcomes.titleZh("loop"));
        String app = Files.readString(Path.of("src/main/java/com/onesay/desktop/App.java"), StandardCharsets.UTF_8);
        assertTrue(app.contains("\"任务\" + Outcomes.titleZh(st) + \"：\""));
        String h = Preview1120UiTest.html();
        assertTrue("旧标题也认", h.contains("|你拒绝了|被你拒绝|"));
        assertTrue(h.contains(".nk.task{background:rgba(31,164,99,.12);color:var(--green)}"));
        assertTrue(h.contains(".nk.task.st-denied,.nk.task.st-stopped,.nk.task.st-time_limit{"));
        assertTrue("通知页按钮不换行", h.contains(".head>.btn{flex:none;white-space:nowrap}"));
    }

    @Test public void clixmlErrorOutputIsReadable() {
        String raw = "#< CLIXML\r\n<Objs Version=\"1.1.0.1\" xmlns=\"http://schemas.microsoft.com/powershell/2004/04\"><S S=\"Error\">Register-ScheduledTask : 参数错误_x000D__x000A_</S><S S=\"Error\">所在位置 行:1_x000D__x000A_</S></Objs>";
        assertEquals("Register-ScheduledTask : 参数错误\r\n所在位置 行:1", ChatAgent.clixml(raw));
        Map<String, Object> d = new TreeMap<>();
        d.put("output", raw);
        String t = ChatAgent.summary(new Actions.Result(false, "命令失败（退出码 1）", d));
        assertFalse(t, t.contains("CLIXML"));
        assertTrue(t, t.contains("Register-ScheduledTask : 参数错误"));
        assertEquals("普通输出", ChatAgent.clixml("普通输出"));
        assertEquals("先有一行", ChatAgent.clixml("先有一行\n#< CLIXML\n<Objs></Objs>"));
        String src;
        try { src = Files.readString(Path.of("src/main/java/com/onesay/desktop/ChatAgent.java"), StandardCharsets.UTF_8); } catch (java.io.IOException e) { throw new AssertionError(e); }
        assertTrue("详情和给模型的结果也解开", src.contains("r.data.put(\"output\", clixml((String) r.data.get(\"output\")))"));
    }
}
