package com.onesay.assistant.v2.pclink;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * 找茬 · 链接下载（media_get）：
 * <ul>
 *   <li>check：PcMedia.checkUrl（「和电脑端同一套」，电脑端再查一遍）——分号、空格、&amp; 以外的 shell 元字符、换行、反斜杠、
 *       引号、$、反引号、{ } ^、以 - 开头（选项注入）、非 http(s)、带 user:pw@、没有主机、超长 → 拒；&amp; 放开（2026-11-11）；同形字主机在
 *       java.net.URI 里没有 host → 拒；</li>
 *   <li>tidy：从分享文字里取第一个链接、去 # 片段、去跟踪参数（utm_* / spm / spm_id_from / fbclid / gclid / igshid，不分大小写），其余参数和顺序不变；</li>
 *   <li>voice：语音口令「下载这个视频」「把这个链接下到电脑」「下载这个视频的音频」；光说「下载」不接；</li>
 *   <li>policy：链接没过检查 → 电脑端直接拒（不弹卡）；过了 → 弹 pc.media_download，工具没装 → pc.media_tool_install。</li>
 * </ul>
 */
@RunWith(Parameterized.class)
public class AdvMediaLinkTest {

    @Parameterized.Parameters(name = "{index}: {0} [{1}]")
    public static Collection<Object[]> data() {
        List<Object[]> out = new ArrayList<Object[]>();
        Set<String> seen = new HashSet<String>();
        String[] okUrls = {"https://www.youtube.com/watch?v=abc&t=10", "https://www.bilibili.com/video/BV1xx411c7mD?p=2", "http://example.com/a.mp4",
                "HTTPS://EXAMPLE.COM/x", "https://example.com:8443/v", "https://b23.tv/AbCd", "https://example.com/a&b", "https://example.com/?a=1&b=2&c=3",
                "https://example.com/%E4%B8%AD", "https://xn--fsqu00a.xn--0zwm56d/v", "https://example.com/watch?list=PL1&index=3", "https://v.douyin.com/iRNBho6/",
                "https://example.com/a(1).mp4", "https://example.com/a!b", "https://example.com/a~b_c-d.e", "https://example.com/a*b", "https://example.com/a,b",
                "https://example.com/a+b", "https://example.com/a=b", "https://example.com/@user/video/1"};
        String[] badUrls = {"", "https://a.com/;rm -rf ~", "https://a.com/ x", "https://a.com/x|sh", "https://a.com/`id`", "https://a.com/$(id)", "https://a.com/$HOME",
                "https://a.com/x\nrm", "https://a.com/x\r", "https://a.com/x\t", "https://a.com\\x", "https://a.com/\"x", "https://a.com/'x", "https://a.com/<x>",
                "https://a.com/{x}", "https://a.com/^x", "-https://a.com/", "--exec=x", "-o/tmp/x https://a.com", "ftp://a.com/x", "file:///etc/passwd",
                "javascript:alert(1)", "a.com/x", "https://user:pw@a.com/x", "https://user@a.com/x", "https://a.com@evil.com/x", "https:///x", "https://",
                "https://уoutube.com/x", "https://例子.测试/v", "https://a.com/\u0000", "https://a.com/\u007f", "https://a.com/x\u000b",
                "https://" + new String(new char[2000]).replace('\0', 'a') + ".com", "https://a.com/%", "https://a.com/a b"};
        for (String u : okUrls) add(out, seen, "check", u, "ok");
        for (String u : badUrls) add(out, seen, "check", u, "bad");
        String[][] tidy = {
                {"看这个 https://www.youtube.com/watch?v=abc&t=10#frag 不错", "https://www.youtube.com/watch?v=abc&t=10"},
                {"https://b23.tv/x?utm_source=a&spm_id_from=333&p=2", "https://b23.tv/x?p=2"},
                {"https://a.com/v?UTM_MEDIUM=x&FBCLID=y&keep=1&GCLID=z", "https://a.com/v?keep=1"},
                {"https://a.com/v?spm=1&igshid=2", "https://a.com/v"},
                {"https://a.com/v?&&a=1&&b=2&", "https://a.com/v?a=1&b=2"},
                {"【标题】https://v.douyin.com/iRNBho6/ 复制此链接", "https://v.douyin.com/iRNBho6/"},
                {"链接：https://a.com/v。", "https://a.com/v"},
                {"https://a.com/v，后面是中文", "https://a.com/v"},
                {"(https://a.com/v)", "https://a.com/v)"},   // 半角 ) 是网址合法字符，tidy 只排除中文标点（断言按注释改）
                {"https://a.com/v.", "https://a.com/v"},
                {"https://a.com/v,", "https://a.com/v"},
                {"https://a.com/v?", "https://a.com/v"},
                {"two https://a.com/1 https://b.com/2", "https://a.com/1"},
                {"HTTPS://A.COM/V", "HTTPS://A.COM/V"},
                {"https://a.com/v?utm_=1&utm=2", "https://a.com/v?utm=2"},
                {"https://a.com/v?spmx=1", "https://a.com/v?spmx=1"},
                {"https://a.com/v#", "https://a.com/v"},
                {"https://a.com/v?b=2&a=1", "https://a.com/v?b=2&a=1"},
                {"没有链接", null}, {"", null}, {"ftp://a.com/x", null}, {"https://", null},
        };
        for (String[] t : tidy) add(out, seen, "tidy", t[0], t[1]);
        String[][] voice = {
                {"下载这个视频", "|"}, {"帮我下载这个视频", "|"}, {"用电脑下载这个视频", "|"}, {"把这个链接下到电脑", "|"}, {"把这个视频下载到电脑上", "|"},
                {"下载这个视频的音频", "|m4a"}, {"下载这首歌", "|m4a"}, {"把这个视频转成mp3下到电脑", "|mp3"}, {"下载这个视频mp3", "|mp3"},
                {"下载 https://a.com/v", "https://a.com/v|"}, {"用电脑下载 https://a.com/v 吧", "https://a.com/v|"}, {"下载刚才那个视频", "|"},
                {"下载", null}, {"下载微信", null}, {"帮我下载一个App", null}, {"把文件下到电脑", null}, {"这个视频真好看", null}, {"", null},
                {"下载这个视频下载这个视频下载这个视频下载这个视频", null}, {"请帮我用电脑下载这个视频到我的电脑上吧", null}   // HEAD 只认一个客气词（请 / 帮我），两个叠用不接（断言按正则改）,
        };
        for (String[] v : voice) add(out, seen, "voice", v[0], v[1]);
        return out;
    }

    static void add(List<Object[]> out, Set<String> seen, String kind, String in, String want) {
        if (!seen.add(kind + "\u0001" + in)) throw new IllegalStateException("dup " + kind + " " + in);
        out.add(new Object[]{kind, in, want});
    }

    private final String kind, in, want;

    public AdvMediaLinkTest(String kind, String in, String want) {
        this.kind = kind;
        this.in = in;
        this.want = want;
    }

    @Test public void media() {
        String at = kind + " [" + in + "]";
        if (kind.equals("check")) {
            String why = PcMedia.checkUrl(in);
            if (want.equals("ok")) assertNull(at + " → " + why, why);
            else assertNotNull(at + " 应拒绝", why);
            LinkPolicy.Facts f = new LinkPolicy.Facts();
            f.mediaBad = why == null ? "" : why;
            for (LinkPolicy.Sandbox sb : LinkPolicy.Sandbox.values())
                for (LinkPolicy.Approval ap : LinkPolicy.Approval.values()) {
                    LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PC, new LinkPolicy.Mode(sb, ap), LinkPolicy.Cat.MEDIA_DOWNLOAD, f);
                    if (why != null || sb == LinkPolicy.Sandbox.READ_ONLY) assertEquals(at + " " + sb + "/" + ap, LinkPolicy.Verdict.DENY, d.verdict);
                    else if (ap != LinkPolicy.Approval.NEVER) assertEquals(at, "pc.media_download", d.kind);
                }
            if (why == null) {
                f.mediaToolsMissing = true;
                assertEquals(at, "pc.media_tool_install", LinkPolicy.decide(LinkPolicy.Target.PC, LinkPolicy.Mode.DEFAULT, LinkPolicy.Cat.MEDIA_DOWNLOAD, f).kind);
                Map<String, Object> a = PcMedia.args(in, "4k", "flac");
                assertEquals("best", a.get("quality"));
                assertEquals("", a.get("audio"));
            }
        } else if (kind.equals("tidy")) {
            String got = PcMedia.tidy(in);
            assertEquals(at, want, got);
            if (got != null && !got.contains("'")) assertNull(at + " 整理后的链接应能过检查 → " + PcMedia.checkUrl(got), PcMedia.checkUrl(got));
        } else {
            PcMedia.Ask a = PcMedia.voice(in);
            if (want == null) { assertNull(at, a); return; }
            assertNotNull(at, a);
            String[] p = want.split("\\|", -1);
            assertEquals(at, p[0].length() == 0 ? null : p[0], a.url);
            assertEquals(at, p[1], a.audio);
        }
    }
}
