package com.onesay.assistant.plug.richout;

import com.onesay.assistant.plug.api.PlugSwitches;
import com.onesay.assistant.plug.richout.core.CodeHighlighter;
import com.onesay.assistant.plug.richout.core.DiagramHtml;
import com.onesay.assistant.plug.richout.core.InlineMd;
import com.onesay.assistant.plug.richout.core.LinkPolicy;
import com.onesay.assistant.plug.richout.core.MarkdownBlocks;
import com.onesay.assistant.plug.richout.core.ReaderText;
import com.onesay.assistant.plug.richout.core.RichBlock;
import com.onesay.assistant.plug.richout.core.RichOutPlugs;
import com.onesay.assistant.plug.richout.core.RichText;
import com.onesay.assistant.plug.richout.core.SvgSanitizer;

import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 富内容输出插件 core：Markdown 分块、行内、代码高亮、SVG 净化（恶意样例）、链接白名单、朗读跳过、裁剪、沙箱页面、开关。 */
public class RichOutCoreTest {

    @After
    public void resetSwitches() {
        RichOutPlugs.setSwitches(PlugSwitches.DEFAULTS);
    }

    // ------------------------------------------------------------------ Markdown 分块

    @Test
    public void blocksCoverCommonShapes() {
        String md = "# 标题一\n\n第一段，**加粗**和`代码`。\n第二行仍属第一段。\n\n"
                + "- 苹果\n- 香蕉\n  - 小香蕉\n- 橘子\n\n"
                + "1. 先做\n2. 再做\n\n"
                + "> 引用一句\n> 第二句\n\n"
                + "| 名称 | 价格 |\n|:--|--:|\n| A | 1 |\n| B | 2 |\n\n"
                + "---\n\n"
                + "```python\nprint('hi')\n```\n\n"
                + "```svg\n<svg viewBox=\"0 0 10 10\"><rect width=\"5\" height=\"5\"/></svg>\n```\n"
                + "收尾段。";
        List<RichBlock> b = MarkdownBlocks.parse(md);
        String kinds = "";
        for (RichBlock x : b) kinds += x.kind.name().charAt(0) + (x.kind == RichBlock.Kind.RULE ? "r" : "");
        assertEquals("HPLLQTRrCDP", kinds);
        assertEquals(1, b.get(0).level);
        assertEquals("第一段，**加粗**和`代码`。\n第二行仍属第一段。", b.get(1).text);
        RichBlock ul = b.get(2);
        assertFalse(ul.ordered);
        assertEquals(4, ul.items.size());
        assertEquals("\t小香蕉", ul.items.get(2));
        assertTrue(b.get(3).ordered);
        assertEquals("引用一句\n第二句", b.get(4).text);
        RichBlock t = b.get(5);
        assertEquals(2, t.header.size());
        assertEquals(2, t.rows.size());
        assertEquals(Character.valueOf('L'), t.aligns.get(0));
        assertEquals(Character.valueOf('R'), t.aligns.get(1));
        assertEquals("python", b.get(7).lang);
        assertEquals("print('hi')", b.get(7).text);
        assertFalse(b.get(7).open);
        assertEquals("svg", b.get(8).lang);
        assertEquals("收尾段。", b.get(9).text);
    }

    @Test
    public void openFenceWhileStreamingAndAliases() {
        List<RichBlock> b = MarkdownBlocks.parse("看代码：\n```js\nconst a = 1;\nconst b");
        assertEquals(2, b.size());
        assertEquals(RichBlock.Kind.CODE, b.get(1).kind);
        assertTrue(b.get(1).open);
        assertEquals("javascript", b.get(1).lang);
        assertEquals("const a = 1;\nconst b", b.get(1).text);
        assertEquals("mermaid", MarkdownBlocks.parse("```mermaid\ngraph TD;A-->B\n```").get(0).lang);
        assertEquals(RichBlock.Kind.DIAGRAM, MarkdownBlocks.parse("```chartjs\n{}\n```").get(0).kind);
        // html 只当代码，不渲染
        RichBlock h = MarkdownBlocks.parse("```html\n<b>x</b>\n```").get(0);
        assertEquals(RichBlock.Kind.CODE, h.kind);
        assertEquals("html", h.lang);
        // ~~~ 围栏、围栏内的 ``` 不提前结束
        RichBlock t = MarkdownBlocks.parse("~~~\na\n```\nb\n~~~").get(0);
        assertEquals("a\n```\nb", t.text);
        // 中文顿号编号
        assertTrue(MarkdownBlocks.parse("1、第一\n2、第二").get(0).ordered);
    }

    @Test
    public void plainTextStaysOneParagraphAndPathologicalInputIsBounded() {
        List<RichBlock> b = MarkdownBlocks.parse("今天晴，最高 23 度。\n适合出门。");
        assertEquals(1, b.size());
        assertEquals(RichBlock.Kind.PARAGRAPH, b.get(0).kind);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) sb.append("# h").append(i).append('\n');
        long t0 = System.nanoTime();
        List<RichBlock> many = MarkdownBlocks.parse(sb.toString());
        assertTrue(many.size() <= MarkdownBlocks.MAX_BLOCKS + 1);
        StringBuilder ticks = new StringBuilder();
        for (int i = 0; i < 20000; i++) ticks.append("`*_[");
        MarkdownBlocks.parse(ticks.toString());
        InlineMd.parse(ticks.toString());
        assertTrue("线性时间", (System.nanoTime() - t0) / 1000000L < 3000);
    }

    // ------------------------------------------------------------------ 行内

    @Test
    public void inlineSpans() {
        InlineMd.Result r = InlineMd.parse("**粗** *斜* ~~删~~ `a*b` [官网](https://example.com/a?b=1) 和 https://x.org/p。");
        assertEquals("粗 斜 删 a*b 官网 和 https://x.org/p。", r.text);
        assertEquals(1, r.of(InlineMd.Type.BOLD).size());
        assertEquals(1, r.of(InlineMd.Type.ITALIC).size());
        assertEquals(1, r.of(InlineMd.Type.STRIKE).size());
        assertEquals(1, r.of(InlineMd.Type.CODE).size());
        assertEquals(2, r.links().size());
        assertEquals("https://example.com/a?b=1", r.links().get(0));
        assertEquals("https://x.org/p", r.links().get(1));   // 中文句号不进网址
        // snake_case 不被拆；转义
        assertEquals("my_var_name \\*x*", InlineMd.parse("my_var_name \\\\\\*x\\*").text.replace("\\*", "\\*"));
        assertEquals("a_b_c", InlineMd.parse("a_b_c").text);
        // 链接文字里的格式、维基括号、尾随标点
        InlineMd.Result w = InlineMd.parse("见 https://en.wikipedia.org/wiki/Foo_(bar)，以及 (https://a.com/x).");
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", w.links().get(0));
        assertEquals("https://a.com/x", w.links().get(1));
        // 图片只当链接
        InlineMd.Result img = InlineMd.parse("![猫](https://img.example/cat.png)");
        assertEquals("图：猫", img.text);
        assertEquals("https://img.example/cat.png", img.links().get(0));
        // 未闭合的标记原样保留
        assertEquals("**没闭合", InlineMd.parse("**没闭合").text);
        assertEquals("[只有括号]", InlineMd.parse("[只有括号]").text);
    }

    // ------------------------------------------------------------------ 代码高亮

    private static String kindsOf(String lang, String code) {
        StringBuilder b = new StringBuilder();
        for (CodeHighlighter.Token t : CodeHighlighter.tokenize(lang, code)) {
            b.append(t.kind).append(':').append(code, t.start, t.end).append(' ');
        }
        return b.toString().trim();
    }

    @Test
    public void highlightCommonLanguages() {
        String j = kindsOf("java", "@Override public int f(String s) { return 42; } // 注释");
        assertTrue(j, j.contains("ANNOTATION:@Override"));
        assertTrue(j, j.contains("KEYWORD:public"));
        assertTrue(j, j.contains("TYPE:int"));
        assertTrue(j, j.contains("FUNCTION:f"));
        assertTrue(j, j.contains("NUMBER:42"));
        assertTrue(j, j.contains("COMMENT:// 注释"));
        String p = kindsOf("py", "def f(x):\n    return \"\"\"多\n行\"\"\"  # c\nprint(1)");
        assertTrue(p, p.contains("KEYWORD:def"));
        assertTrue(p, p.contains("STRING:\"\"\"多\n行\"\"\""));
        assertTrue(p, p.contains("COMMENT:# c"));
        String sh = kindsOf("bash", "echo $HOME ${X} # hi\nfor i in 1 2; do echo \"$i\"; done");
        assertTrue(sh, sh.contains("VARIABLE:$HOME"));
        assertTrue(sh, sh.contains("VARIABLE:${X}"));
        assertTrue(sh, sh.contains("COMMENT:# hi"));
        assertTrue(sh, sh.contains("KEYWORD:done"));
        String js = kindsOf("javascript", "const s = `t${1}`; /* b */ fetch('u')");
        assertTrue(js, js.contains("KEYWORD:const"));
        assertTrue(js, js.contains("STRING:`t${1}`"));
        assertTrue(js, js.contains("COMMENT:/* b */"));
        assertTrue(js, js.contains("FUNCTION:fetch"));
        String json = kindsOf("json", "{\"a\": 1, \"b\": \"x\", \"c\": true}");
        assertTrue(json, json.contains("ATTR:\"a\""));
        assertTrue(json, json.contains("STRING:\"x\""));
        assertTrue(json, json.contains("KEYWORD:true"));
        String xml = kindsOf("svg", "<svg width=\"1\"><!-- c --><rect/></svg>");
        assertTrue(xml, xml.contains("TAG:svg"));
        assertTrue(xml, xml.contains("ATTR:width"));
        assertTrue(xml, xml.contains("COMMENT:<!-- c -->"));
        String sql = kindsOf("sql", "SELECT name FROM t WHERE id = 3 -- x");
        assertTrue(sql, sql.contains("KEYWORD:SELECT"));
        assertTrue(sql, sql.contains("COMMENT:-- x"));
        String c = kindsOf("c", "#include <stdio.h>\nint main(){return 0;}");
        assertTrue(c, c.contains("ANNOTATION:#include"));
        String y = kindsOf("yaml", "name: demo # c\nport: 8080");
        assertTrue(y, y.contains("ATTR:name"));
        assertTrue(y, y.contains("NUMBER:8080"));
        for (String l : new String[] {"kotlin", "go", "rust", "swift", "ts", "cpp", "cs", "css", "php", "ruby", "lua", "dart", "", "brainfuck"}) {
            CodeHighlighter.tokenize(l, "fun x() { val s = \"a\" // c\n }");
        }
        assertTrue(CodeHighlighter.supported().containsAll(java.util.Arrays.asList("java", "python", "bash", "javascript", "json", "html", "sql")));
    }

    @Test
    public void highlightTokensAreOrderedNonOverlappingAndBounded() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 3000; i++) big.append("int a").append(i).append(" = \"s\"; // c\n");
        List<CodeHighlighter.Token> toks = CodeHighlighter.tokenize("java", big.toString());
        int last = 0;
        for (CodeHighlighter.Token t : toks) {
            assertTrue(t.start >= last);
            assertTrue(t.end > t.start);
            assertTrue(t.end <= CodeHighlighter.MAX_CHARS);
            last = t.end;
        }
        // 未闭合字符串 / 注释不越界
        CodeHighlighter.tokenize("java", "String s = \"abc");
        CodeHighlighter.tokenize("java", "/* never closed");
        CodeHighlighter.tokenize("python", "'''x");
    }

    // ------------------------------------------------------------------ SVG 净化

    private static String clean(String svg) {
        SvgSanitizer.Result r = SvgSanitizer.sanitize(svg);
        assertTrue(r.toString(), r.ok());
        return r.svg;
    }

    private static void assertNoActive(String out) {
        String l = out.toLowerCase(Locale.ROOT);
        for (String bad : new String[] {"<script", "onload", "onerror", "onclick", "onbegin", "javascript:", "foreignobject",
                "<iframe", "<image", "<a ", "data:", "http://evil", "https://evil", "@import", "<!entity", "<!doctype", "expression(", "<animate", "<set"}) {
            assertFalse(bad + " 漏网：" + out, l.contains(bad));
        }
    }

    @Test
    public void svgKeepsBenignDrawing() {
        String out = clean("<?xml version=\"1.0\"?><!-- c --><svg viewBox=\"0 0 100 50\" xmlns=\"http://www.w3.org/2000/svg\">"
                + "<defs><linearGradient id=\"g\"><stop offset=\"0\" stop-color=\"#f00\"/></linearGradient></defs>"
                + "<rect x=\"1\" y=\"2\" width=\"30\" height=\"20\" fill=\"url(#g)\" rx=\"3\"/>"
                + "<text x=\"5\" y=\"40\" font-size=\"12\">温度 &lt; 30 &amp; 湿度</text>"
                + "<use href=\"#g\"/><style>.a{fill:red} rect > text{opacity:.5}</style></svg>");
        assertTrue(out, out.startsWith("<svg viewBox=\"0 0 100 50\" xmlns=\"http://www.w3.org/2000/svg\"><defs><linearGradient id=\"g\">"));
        assertTrue(out, out.contains("fill=\"url(#g)\""));
        assertTrue(out, out.contains("温度 &lt; 30 &amp; 湿度"));
        assertTrue(out, out.contains("<use href=\"#g\"/>"));
        assertTrue(out, out.contains(".a{fill:red}"));
        assertTrue(out, out.endsWith("</svg>"));
        // 缺 xmlns 会补上
        assertTrue(clean("<svg><circle r=\"1\"/></svg>").contains("xmlns=\"http://www.w3.org/2000/svg\""));
    }

    @Test
    public void svgMaliciousSamples() {
        String[] evil = {
            "<svg onload=\"alert(1)\"><circle r=\"1\"/></svg>",
            "<svg><script>alert(1)</script><rect/></svg>",
            "<svg><SCRIPT type=\"text/javascript\">fetch('https://evil.example')</SCRIPT></svg>",
            "<svg><foreignObject><iframe src=\"https://evil.example\"></iframe><body onload=alert(1)></body></foreignObject></svg>",
            "<svg><a href=\"javascript:alert(1)\"><text>点我</text></a></svg>",
            "<svg><a xlink:href=\"java&#x09;script:alert(1)\"><rect/></a></svg>",
            "<svg><use href=\"https://evil.example/x.svg#a\"/></svg>",
            "<svg><use xlink:href=\"data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=#x\"/></svg>",
            "<svg><image href=\"https://evil.example/track.png\"/></svg>",
            "<svg><animate attributeName=\"href\" to=\"javascript:alert(1)\"/><set attributeName=\"onclick\" to=\"alert(1)\"/></svg>",
            "<svg><rect style=\"fill:url(https://evil.example/a)\"/><rect fill=\"url('http://evil.example/b')\"/></svg>",
            "<svg><style>@import url(https://evil.example/x.css); .a{background:url(http://evil.example/y)}</style></svg>",
            "<svg><style>.a{behavior:url(x.htc)} .b{width:expression(alert(1))}</style></svg>",
            "<!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\"><!ENTITY a \"aaaa\">]><svg><text>&x;&a;</text></svg>",
            "<svg><rect onmouseover = 'alert(1)' ONCLICK=\"x\" width=\"1\"/></svg>",
            "<svg><g><g><foreignObject><div xmlns=\"http://www.w3.org/1999/xhtml\"><img src=x onerror=alert(1)></div></foreignObject></g></g></svg>",
            "<svg><text>&lt;script&gt;alert(1)&lt;/script&gt;</text><![CDATA[<script>alert(2)</script>]]></svg>",
            "<svg><handler type=\"application/ecmascript\">alert(1)</handler><listener event=\"load\"/></svg>",
            "<svg><style><![CDATA[@import 'https://evil.example/z.css';]]></style></svg>",
            "<svg><rect style=\"fill:u\\72l(https://evil.example)\"/></svg>",
            "<svg><feImage href=\"https://evil.example/f.png\"/><filter id=\"f\"><feImage xlink:href=\"https://evil.example\"/></filter></svg>",
            "<svg xmlns:xlink=\"http://evil.example\"><rect/></svg>",
            "<svg><rect fill=\"&#106;avascript:alert(1)\"/></svg>",
        };
        for (String e : evil) {
            SvgSanitizer.Result r = SvgSanitizer.sanitize(e);
            assertTrue(e + " → " + r, r.ok());
            assertNoActive(r.svg);
            assertTrue(e, r.removed > 0 || !r.svg.contains("&x;"));
        }
        // CDATA 里的 <script> 只剩转义文字
        String cd = clean(evil[16]);
        assertTrue(cd, cd.contains("&lt;script&gt;alert(2)"));
        // 实体：外部 / 自定义实体不展开
        String ent = clean(evil[13]);
        assertFalse(ent, ent.contains("passwd") && !ent.contains("&amp;x;"));
    }

    @Test
    public void svgRejectsNonSvgAndOversize() {
        assertFalse(SvgSanitizer.sanitize("<html><body onload=x></body></html>").ok());
        assertFalse(SvgSanitizer.sanitize("<script>alert(1)</script><svg></svg>").ok());
        assertFalse(SvgSanitizer.sanitize("纯文字").ok());
        assertFalse(SvgSanitizer.sanitize(null).ok());
        StringBuilder big = new StringBuilder("<svg>");
        while (big.length() < SvgSanitizer.MAX_BYTES + 10) big.append("<rect width=\"1\"/>");
        assertFalse(SvgSanitizer.sanitize(big.append("</svg>").toString()).ok());
        // 深嵌套 / 元素过多：截断但不炸
        StringBuilder deep = new StringBuilder("<svg>");
        for (int i = 0; i < 500; i++) deep.append("<g>");
        SvgSanitizer.Result d = SvgSanitizer.sanitize(deep.append("</svg>").toString());
        assertTrue(d.ok());
        assertTrue(d.removed > 0);
    }

    // ------------------------------------------------------------------ 链接白名单

    @Test
    public void linkPolicy() {
        assertEquals(LinkPolicy.Action.READER, LinkPolicy.decide("https://example.com/a?b=1#c").action);
        assertEquals(LinkPolicy.Action.READER, LinkPolicy.decide("HTTP://Example.COM").action);
        assertEquals("example.com", LinkPolicy.decide("HTTP://Example.COM:8080/x").host);
        assertEquals("https://www.zhihu.com/q", LinkPolicy.decide("www.zhihu.com/q").url);
        for (String bad : new String[] {"javascript:alert(1)", "JaVaScRiPt:alert(1)", " javascript:alert(1)", "vbscript:x",
                "data:text/html,<script>alert(1)</script>", "file:///sdcard/a.txt", "content://sms/inbox",
                "intent://scan/#Intent;scheme=zxing;package=com.x;end", "about:blank", "blob:https://a/b",
                "chrome://settings", "ftp://a.com/x", "java\nscript:alert(1)", "https://a.com/\u0000x", "https://a\u200b.com",
                "http:example.com", "//evil.com/x", "https://", "https://a b.com/", "http://localhost:8080/", "http://127.0.0.1:5555/",
                "http://[::1]/x", "https://a.com\\@evil.com/", "relative/path", "", null}) {
            assertEquals(String.valueOf(bad), LinkPolicy.Action.BLOCK, LinkPolicy.decide(bad).action);
        }
        for (String deep : new String[] {"weixin://dl/scan", "alipays://platformapi/startapp", "taobao://item.taobao.com/x",
                "mailto:a@b.com", "tel:10086", "sms:10086", "market://details?id=x", "snssdk1128://aweme"}) {
            LinkPolicy.Decision d = LinkPolicy.decide(deep);
            assertEquals(deep, LinkPolicy.Action.CONFIRM, d.action);
        }
        LinkPolicy.Decision ui = LinkPolicy.decide("https://www.bank.com@evil.example/login");
        assertEquals(LinkPolicy.Action.READER, ui.action);
        assertTrue(ui.suspicious);
        assertEquals("evil.example", ui.host);
        assertTrue(LinkPolicy.decide("https://xn--80ak6aa92e.com/").suspicious);
        assertTrue(LinkPolicy.decide("http://192.168.1.1/").suspicious);
        assertTrue(LinkPolicy.decide("http://8.8.8.8/").suspicious);
        assertFalse(LinkPolicy.decide("https://example.com/").suspicious);
        assertTrue(LinkPolicy.allowInReader("https://a.com"));
        assertFalse(LinkPolicy.allowInReader("weixin://x"));
    }

    // ------------------------------------------------------------------ 朗读跳过 / 岛 / 回落

    @Test
    public void speechSkipsCodeAndDiagrams() {
        RichText.Speech s = RichText.speech("冒泡排序这样写：\n```python\nfor i in range(n):\n    pass\n```\n复杂度 O(n²)。");
        assertTrue(s.hasCode);
        assertFalse(s.hasDiagram);
        assertEquals(RichText.CUE_CODE, s.cue);
        assertFalse(s.spoken, s.spoken.contains("range"));
        assertFalse(s.spoken, s.spoken.contains("```"));
        assertTrue(s.spoken, s.spoken.startsWith("冒泡排序这样写："));
        assertTrue(s.spoken, s.spoken.contains("复杂度"));
        assertTrue(s.spoken, s.spoken.endsWith(RichText.CUE_CODE));

        RichText.Speech d = RichText.speech("流程如下\n```mermaid\ngraph TD;A-->B\n```");
        assertEquals(RichText.CUE_DIAGRAM, d.cue);
        assertFalse(d.spoken.contains("graph"));
        RichText.Speech m = RichText.speech("```js\nx\n```\n```svg\n<svg/>\n```");
        assertEquals(RichText.CUE_MIXED, m.cue);
        RichText.Speech t = RichText.speech("| a | b |\n|---|---|\n| 1 | 2 |");
        assertEquals(RichText.CUE_TABLE, t.cue);
        // 链接：念文字不念网址；裸网址不念
        RichText.Speech l = RichText.speech("详情见[官网](https://example.com)，或者 https://x.org/abc 。**注意**安全。");
        assertFalse(l.spoken, l.spoken.contains("http"));
        assertTrue(l.spoken, l.spoken.contains("官网"));
        assertTrue(l.spoken, l.spoken.contains("注意安全"));
        assertNull(l.cue);
        assertEquals(2, l.links);
        // 纯文本：原样
        assertEquals("今天晴。", RichText.speech("今天晴。").spoken);
        assertNull(RichText.speech("今天晴。").cue);
    }

    @Test
    public void islandLineHasNoRichContent() {
        String line = RichText.plainLine("结果：\n```java\nint a = 1;\n```\n```chart\n{}\n```\n- **一**\n- 二", 60);
        assertFalse(line, line.contains("```"));
        assertFalse(line, line.contains("int a"));
        assertFalse(line, line.contains("**"));
        assertTrue(line, line.contains("〔代码〕"));
        assertTrue(line, line.contains("〔图〕"));
        assertTrue(line, line.contains("一；二"));
        assertFalse(line.contains("\n"));
        assertEquals("短答案", RichText.plainLine("短答案", 60));
        assertTrue(RichText.plainLine("很长很长很长很长很长", 5).length() <= 5);
    }

    @Test
    public void plainFallbackKeepsCodeText() {
        String p = RichText.plainFallback("## 标题\n- **a**\n```bash\nls -la | grep '*.txt'\n```");
        assertTrue(p, p.contains("标题"));
        assertTrue(p, p.contains("· a"));
        assertTrue(p, p.contains("ls -la | grep '*.txt'"));
        assertFalse(p, p.contains("##"));
    }

    // ------------------------------------------------------------------ 终稿裁剪

    @Test
    public void polishKeepsFencesWholeAndClosesOpenOnes() {
        StringBuilder prose = new StringBuilder();
        for (int i = 0; i < 80; i++) prose.append("这是第").append(i).append("句话。");
        String code = "```java\nclass A {}\n```\n";
        String out = RichText.polish("开头。\n" + code + prose, 500, 8000);
        assertTrue(out, out.contains("class A {}\n```"));
        assertTrue(out.length() < 520 + code.length());
        assertTrue(out, out.endsWith("。"));
        // 正文先到上限 → 后面的代码也不要了（不打乱先后）
        String out2 = RichText.polish(prose + "\n" + code, 100, 8000);
        assertFalse(out2.contains("class A"));
        // 流式半截：补上围栏
        assertEquals("看：\n```py\nprint(1)\n```", RichText.polish("看：\n```py\nprint(1)", 500, 8000));
        // 总量上限：长代码按行截断并注明
        StringBuilder longCode = new StringBuilder("```c\n");
        for (int i = 0; i < 2000; i++) longCode.append("int x").append(i).append(";\n");
        longCode.append("```");
        String out3 = RichText.polish(longCode.toString(), 500, 3000);
        assertTrue(out3.length() <= 3010);
        assertTrue(out3, out3.contains("已截断"));
        assertTrue(out3, out3.trim().endsWith("```"));
        assertEquals("", RichText.polish("   ", 500, 8000));
    }

    @Test
    public void needsRichOnlyForMarkdownish() {
        assertFalse(RichText.needsRich("佛山今天多云，23 到 28 度。"));
        assertFalse(RichText.needsRich("比分是 3:2，主队赢了。"));
        assertFalse(RichText.needsRich(""));
        assertFalse(RichText.needsRich(null));
        assertTrue(RichText.needsRich("看 https://a.com"));
        assertTrue(RichText.needsRich("**重点**"));
        assertTrue(RichText.needsRich("步骤：\n1. 打开\n2. 关闭"));
        assertTrue(RichText.needsRich("```\nx\n```"));
        assertTrue(RichText.needsRich("- a\n- b"));
        assertTrue(RichText.needsRich("| a | b |\n|---|---|"));
    }

    // ------------------------------------------------------------------ 沙箱页面

    @Test
    public void diagramPagesAreSandboxed() {
        DiagramHtml.Page svg = DiagramHtml.svg("<svg onload=\"x()\"><script>y()</script><rect width=\"1\"/></svg>", true, false);
        assertTrue(svg.ok());
        assertFalse(svg.js);
        assertTrue(svg.html.contains("default-src 'none'"));
        assertFalse(svg.html, svg.html.contains("script-src"));
        assertFalse(svg.html.toLowerCase(Locale.ROOT).contains("<script"));
        assertFalse(svg.html.contains("onload"));
        assertFalse(DiagramHtml.svg("<html/>", true, false).ok());

        DiagramHtml.Page mm = DiagramHtml.mermaid("%%{init: {'securityLevel':'loose'}}%%\ngraph TD\nA-->B\nclick A \"javascript:alert(1)\"\n</script><script>alert(9)</script>", true, false);
        assertTrue(mm.ok());
        assertTrue(mm.js);
        assertTrue(mm.html.contains("securityLevel:'strict'"));
        assertFalse(mm.html, mm.html.contains("loose"));
        assertFalse(mm.html, mm.html.contains("javascript:alert"));
        assertFalse("源码里的 </script> 不能提前闭合", mm.html.contains("</script><script>alert(9)"));
        assertTrue(mm.html.contains(DiagramHtml.LIB_MERMAID));
        assertTrue(mm.html.contains("script-src 'unsafe-inline' " + DiagramHtml.BASE + "lib/"));
        assertFalse(DiagramHtml.mermaid("   ", true, false).ok());

        DiagramHtml.Page ch = DiagramHtml.chart("{\"type\":\"bar\",\"data\":{\"labels\":[\"a\"],\"datasets\":[{\"label\":\"x\",\"data\":[1]}]},\"plugins\":[1]}", true, false);
        assertTrue(ch.ok());
        assertTrue(ch.html.contains(DiagramHtml.LIB_CHART));
        assertFalse(ch.html.contains("\"plugins\""));
        assertTrue(ch.html.contains("animation"));
        assertFalse(DiagramHtml.chart("{\"type\":\"evil\",\"data\":{\"datasets\":[{}]}}", true, false).ok());
        assertFalse(DiagramHtml.chart("function(){}", true, false).ok());
        assertFalse(DiagramHtml.chart("{\"type\":\"bar\"}", true, false).ok());

        assertEquals(DiagramHtml.ASSET_MERMAID, DiagramHtml.assetFor(DiagramHtml.LIB_MERMAID));
        assertEquals(DiagramHtml.ASSET_CHART, DiagramHtml.assetFor(DiagramHtml.LIB_CHART));
        assertNull(DiagramHtml.assetFor("https://richout.invalid/lib/../x"));
        assertNull(DiagramHtml.assetFor("https://evil.example/lib/mermaid.min.js"));
        assertEquals(240, DiagramHtml.parseOkHeight("RO:ok:300x240"));
        assertEquals(-1, DiagramHtml.parseOkHeight("RO:err:x"));
        assertEquals(-1, DiagramHtml.parseOkHeight("某网页标题"));
        String js = DiagramHtml.jsString("</script>\u2028'\"\\&");
        assertFalse(js.contains("<"));
        assertFalse(js.contains("\u2028"));
        assertFalse(js.contains("'"));
    }

    // ------------------------------------------------------------------ 阅读模式兜底抽取

    @Test
    public void readerTextExtractsArticle() {
        String html = "<html><head><title>标题 &amp; 副题</title><script>var x='<p>不要</p>';</script><style>p{}</style></head>"
                + "<body><nav>首页 | 登录</nav><article><h1>正文标题</h1><p>第一段内容足够长，讲了一件事情的来龙去脉。</p>"
                + "<p>第二段&nbsp;继续说明，包含 &lt;尖括号&gt; 和 &#20013;文。</p><button>点赞</button></article>"
                + "<footer>版权所有</footer></body></html>";
        ReaderText.Doc d = ReaderText.extract(html);
        assertEquals("标题 & 副题", d.title);
        String t = d.text();
        assertTrue(t, t.contains("第一段内容足够长"));
        assertTrue(t, t.contains("<尖括号>"));
        assertTrue(t, t.contains("中文"));
        assertFalse(t, t.contains("不要"));
        assertFalse(t, t.contains("首页"));
        assertFalse(t, t.contains("版权所有"));
        assertFalse(t, t.contains("点赞"));
        assertEquals("gbk", ReaderText.charsetOf("text/html; charset=GBK"));
        assertEquals("utf-8", ReaderText.metaCharset("<meta charset=\"UTF-8\">".getBytes()));
        assertFalse(ReaderText.extract(null).usable());
    }

    // ------------------------------------------------------------------ 插件开关 / 提示词

    @Test
    public void switchesAndFallback() {
        assertTrue(RichOutPlugs.on());
        assertTrue(RichOutPlugs.diagramsOn());
        assertTrue(RichOutPlugs.readerOn());
        assertEquals(RichOutPlugs.ASK_RULE_RICH, RichOutPlugs.askRule());
        assertEquals(RichOutPlugs.AGENT_RULE_RICH, RichOutPlugs.agentRule());
        RichOutPlugs.setSwitches(new PlugSwitches() {
            @Override public boolean on(String id, boolean def) {
                return !RichOutPlugs.ID.equals(id) && def;
            }
        });
        // 总开关关 → 依赖它的两个也关；提示词回到原文
        assertFalse(RichOutPlugs.on());
        assertFalse(RichOutPlugs.diagramsOn());
        assertFalse(RichOutPlugs.readerOn());
        assertEquals("不使用 Markdown 标题、代码块、项目符号。", RichOutPlugs.askRule());
        assertEquals("最终答复适合屏幕阅读：先给结论、短段落，不要 Markdown、不要代码块、不要输出 JSON。", RichOutPlugs.agentRule());
        assertEquals("用纯文本，禁止 Markdown 符号（* # ` 等）、禁止编号符号堆砌、禁止表情符号。", RichOutPlugs.legacyRule());
        assertNotNull(RichOutPlugs.registry().whyOff(RichOutPlugs.ID_DIAGRAM));
        assertTrue(RichOutPlugs.registry().problems().isEmpty());
    }

    @Test
    public void promptRulesMentionLimits() {
        String r = RichOutPlugs.ASK_RULE_RICH;
        for (String k : new String[] {"```svg", "```mermaid", "```chart", "[文字](https://", "20KB", "60 行", "200 个数据点", "不写 script"}) {
            assertTrue(k, r.contains(k));
        }
        assertTrue(RichOutPlugs.AGENT_RULE_RICH.contains("不要输出 JSON"));
        assertTrue(RichOutPlugs.AGENT_RULE_RICH.length() < 120);
    }
}
