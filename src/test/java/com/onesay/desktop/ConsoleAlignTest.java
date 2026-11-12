package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** console-align 2026-11-12：电脑端设置页与手机设置区同名同序（docs/CONSOLE.md），危险项 / 控屏颜色同一张表。 */
public class ConsoleAlignTest {

    static String html() throws Exception {
        return new String(UiServer.resource("onesay/ui/index.html"), StandardCharsets.UTF_8);
    }

    static String doc() throws Exception {
        return Files.readString(Path.of("docs/CONSOLE.md"));
    }

    /** 清单第一张表里的分组名（按顺序）。 */
    static List<String> docGroups(String d) {
        List<String> out = new ArrayList<>();
        String t = d.substring(d.indexOf("## 一、"), d.indexOf("## 二、"));
        Matcher m = Pattern.compile("(?m)^\\| ([^|]+?) \\| ").matcher(t);
        while (m.find()) {
            String g = m.group(1).trim();
            if (!g.equals("分组") && !g.startsWith("---")) out.add(g);
        }
        return out;
    }

    @Test public void settingsGroupsFollowDoc() throws Exception {
        String h = html(), d = doc();
        List<String> want = docGroups(d);
        assertEquals(List.of("连接", "权限与审批", "模型与密钥", "语音", "替你操作", "功能", "下载", "插件", "关于"), want);
        want.remove("功能");   // 只手机有
        String g = h.substring(h.indexOf("const GROUPS=["), h.indexOf("];", h.indexOf("const GROUPS=[")));
        List<String> got = new ArrayList<>();
        Matcher m = Pattern.compile("\\['set-[a-z]+','([^']+)'\\]").matcher(g);
        while (m.find()) got.add(m.group(1));
        assertEquals("跳转条顺序 = 清单顺序", want, got);
        // 页面里每节确实按这个顺序出现
        String p = h.substring(h.indexOf("P.settings=()=>"));
        int prev = -1;
        for (String id : new String[]{"set-connect", "set-perm", "set-model", "set-voice", "set-act", "set-download", "set-plugins", "set-about"}) {
            int i = id.equals("set-download") ? p.indexOf("${mdPanel()}") : p.indexOf(id);
            assertTrue(id + " 顺序对", i > prev);
            prev = i;
        }
    }

    @Test public void sameNamesAsPhone() throws Exception {
        String h = html();
        for (String must : new String[]{"grp('set-model','模型与密钥'", "grp('set-voice','语音'", "grp('set-act','替你操作'", "grp('set-perm','权限与审批'",
                "grp('set-connect','连接'", "sec('var(--accent)','插件','安全插件", "kv('接口地址'", "kv('模型名'", "kv('API Key'", "kv('语音识别 Key'", "remPolicy()"}) {
            assertTrue(must, h.contains(must));
        }
        for (String old : new String[]{"sec('var(--accent)','大模型'", "sec('var(--accent)','说话')", "sec('var(--accent)','电脑能力')", "kv('识别 Key'", "kv('模型',"}) {
            assertFalse("旧名不再出现：" + old, h.contains(old));
        }
        assertTrue("以后这类不用问 在权限与审批里", h.indexOf("remPolicy()", h.indexOf("grp('set-perm'")) < h.indexOf("grp('set-model'"));
        for (String w : new String[]{"Bixby", "三星"}) {
            assertFalse(w, h.contains(w));
            assertFalse(w, doc().contains(w));
        }
    }

    @Test public void dangerVerbsAndControlColorsMatchDoc() throws Exception {
        String h = html(), d = doc();
        String js = h.substring(h.indexOf("const DANGER=["), h.indexOf("];", h.indexOf("const DANGER=[")));
        Matcher m = Pattern.compile("'([^']+)'").matcher(js);
        List<String> verbs = new ArrayList<>();
        while (m.find()) verbs.add(m.group(1));
        String line = d.substring(d.indexOf("危险动词："));
        line = line.substring(0, line.indexOf('\n'));
        List<String> docVerbs = new ArrayList<>();
        for (String v : line.substring("危险动词：".length()).split("·")) docVerbs.add(v.trim());
        assertEquals("危险动词两端同一张表", docVerbs, verbs);
        assertTrue("渲染后自动标红", h.contains("markDanger($(page))") && h.contains("b.classList.add('danger')"));
        String[][] ctl = {{"--ctl-run", "#7C7CFF", "操作中"}, {"--ctl-wait", "#FFB020", "等确认"}, {"--ctl-user", "#34C759", "你接手"}, {"--ctl-stop", "#FF453A", "已停止"}};
        for (String[] c : ctl) {
            assertTrue(c[0], h.contains(c[0] + ":" + c[1]));
            assertTrue(c[2], d.contains(c[2]) && d.contains(c[1]));
            assertTrue(c[2], h.contains("'" + c[2] + "'"));
        }
        assertTrue("确认框 = 等确认琥珀", h.contains("box.style.borderLeft='4px solid var(--ctl-wait)'") && h.contains("tag wait"));
        assertTrue("撤销先确认", h.contains("confirm('撤销「'+((x&&x.zh)||k)+'」？"));
    }
}
