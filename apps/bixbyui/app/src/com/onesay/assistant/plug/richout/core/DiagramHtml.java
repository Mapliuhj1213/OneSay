/*
 * [富内容输出插件 · core] OneSay 自写。只依赖 java.* 与 org.json。
 */
package com.onesay.assistant.plug.richout.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 图卡片的沙箱页面（宿主用 {@code loadDataWithBaseURL(BASE, html, …)} 加载；{@link #BASE} 是 .invalid 域名，
 * 宿主在 shouldInterceptRequest 里只把 {@link #LIB_MERMAID} / {@link #LIB_CHART} 两个地址映射到 assets，其它请求一律 403）。
 *
 * <p>页面自带 CSP（default-src 'none'），即使宿主拦截漏了，浏览器也不会发网络请求；svg 页面连脚本都不允许。
 * 结果回报不用 JS 接口：页面把 {@code document.title} 设成 {@code RO:ok:宽x高} 或 {@code RO:err:原因}，宿主在
 * onReceivedTitle 里读。
 */
public final class DiagramHtml {

    private DiagramHtml() {}

    public static final String BASE = "https://richout.invalid/";
    public static final String LIB_MERMAID = BASE + "lib/mermaid.min.js";
    public static final String LIB_CHART = BASE + "lib/chart.umd.js";
    /** assets 里的文件名（不建子目录）。 */
    public static final String ASSET_MERMAID = "richout_mermaid.min.js";
    public static final String ASSET_CHART = "richout_chart.umd.js";

    public static final String TITLE_OK = "RO:ok:";
    public static final String TITLE_ERR = "RO:err:";

    /** 宿主拦截请求时：这个地址映射到哪个 asset（不是两个库之一 → null → 403）。 */
    public static String assetFor(String url) {
        if (LIB_MERMAID.equals(url)) return ASSET_MERMAID;
        if (LIB_CHART.equals(url)) return ASSET_CHART;
        return null;
    }

    public static final class Page {
        public final String html;
        /** 需要 JS（mermaid / chart）；svg 页面不开 JS。 */
        public final boolean js;
        public final String error;

        Page(String html, boolean js, String error) {
            this.html = html;
            this.js = js;
            this.error = error;
        }

        public boolean ok() {
            return html != null;
        }
    }

    private static final String CSP_NOJS = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:";
    private static final String CSP_JS = "default-src 'none'; script-src 'unsafe-inline' " + BASE + "lib/; style-src 'unsafe-inline'; img-src data:; font-src data:";

    private static String head(String csp, boolean dark, boolean zoom) {
        String fg = dark ? "#E5E5EA" : "#1C1C1E";
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"" + csp + "\">"
                + "<meta name=\"referrer\" content=\"no-referrer\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1" + (zoom ? ",maximum-scale=8,user-scalable=yes" : ",user-scalable=no") + "\">"
                + "<style>html,body{margin:0;padding:0;background:transparent;color:" + fg + ";font-family:sans-serif;}"
                + "#d{padding:8px;box-sizing:border-box;}#d svg{max-width:100%;height:auto;display:block;margin:0 auto;}"
                + ".e{font-size:13px;opacity:.7;padding:8px;}</style></head><body><div id=\"d\">";
    }

    public static Page svg(String raw, boolean dark, boolean zoom) {
        SvgSanitizer.Result r = SvgSanitizer.sanitize(raw == null ? null : raw.trim());
        if (!r.ok()) return new Page(null, false, "SVG 不合规：" + r.reason);
        String html = head(CSP_NOJS, dark, zoom) + r.svg + "</div></body></html>";
        return new Page(html, false, null);
    }

    /** 去掉 mermaid 的 %%{init:…}%% 指令（能改 securityLevel）和 click 交互行。 */
    public static String cleanMermaid(String src) {
        if (src == null) return "";
        String s = src.replaceAll("(?s)%%\\{.*?\\}%%", "");
        StringBuilder b = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            String t = line.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("click ") || t.startsWith("callback ") || t.startsWith("link ") || t.startsWith("links ")) continue;
            if (t.contains("javascript:")) continue;
            b.append(line).append('\n');
        }
        return b.toString().trim();
    }

    public static Page mermaid(String src, boolean dark, boolean zoom) {
        String s = cleanMermaid(src);
        if (s.length() == 0) return new Page(null, true, "mermaid 源码为空");
        if (s.length() > RichOutPlugs.MAX_MERMAID_CHARS) return new Page(null, true, "mermaid 源码过长");
        String js = "<script src=\"" + LIB_MERMAID + "\"></script><script>(function(){"
                + "function ok(){var e=document.getElementById('d');var r=e.getBoundingClientRect();document.title='" + TITLE_OK + "'+Math.ceil(r.width)+'x'+Math.ceil(e.scrollHeight);}"
                + "function fail(x){document.getElementById('d').innerHTML='<div class=e>图渲染失败</div>';document.title='" + TITLE_ERR + "'+String(x&&x.message||x).slice(0,80);}"
                + "function put(svg){try{document.getElementById('d').innerHTML=svg;ok();}catch(e){fail(e);}}"
                + "try{if(!window.mermaid){fail('lib');return;}"
                + "mermaid.initialize({startOnLoad:false,securityLevel:'strict',theme:'" + (dark ? "dark" : "default") + "',"
                + "flowchart:{htmlLabels:false},maxTextSize:" + RichOutPlugs.MAX_MERMAID_CHARS + "});"
                + "var src=" + jsString(s) + ";"
                + "var r=mermaid.render('m0',src,function(svg){put(svg);});"
                + "if(r&&typeof r.then==='function'){r.then(function(o){put(o&&o.svg?o.svg:o);},fail);}"
                + "else if(typeof r==='string'){put(r);}"
                + "}catch(e){fail(e);}})();</script>";
        return new Page(head(CSP_JS, dark, zoom) + "</div>" + js + "</body></html>", true, null);
    }

    static final Set<String> CHART_TYPES = new HashSet<String>(Arrays.asList(
            "bar", "line", "pie", "doughnut", "radar", "polararea", "scatter", "bubble"));

    /** chart 规格校验：JSON 对象、type 在白名单、data 是对象、数据点总数 ≤ 2000。返回规范化 JSON 或 null。 */
    public static String validateChart(String src) {
        if (src == null) return null;
        String s = src.trim();
        if (s.length() == 0 || s.length() > RichOutPlugs.MAX_CHART_CHARS) return null;
        try {
            JSONObject o = new JSONObject(s);
            String type = o.optString("type", "");
            if (!CHART_TYPES.contains(type.toLowerCase(Locale.ROOT))) return null;
            JSONObject data = o.optJSONObject("data");
            if (data == null) return null;
            JSONArray ds = data.optJSONArray("datasets");
            if (ds == null || ds.length() == 0 || ds.length() > 20) return null;
            int pts = 0;
            for (int i = 0; i < ds.length(); i++) {
                JSONObject d = ds.optJSONObject(i);
                if (d == null) return null;
                JSONArray arr = d.optJSONArray("data");
                if (arr != null) pts += arr.length();
            }
            if (pts > 2000) return null;
            JSONObject opt = o.optJSONObject("options");
            if (opt == null) {
                opt = new JSONObject();
                o.put("options", opt);
            }
            // 宿主口径：不动画（截图 / 保存时就是终态）、跟随容器
            opt.put("animation", false);
            opt.put("responsive", true);
            opt.put("maintainAspectRatio", false);
            // 不允许插件 / 事件之类的宿主外配置（Chart.js 配置是纯数据，这里只是兜底）
            o.remove("plugins");
            return o.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public static Page chart(String src, boolean dark, boolean zoom) {
        String cfg = validateChart(src);
        if (cfg == null) return new Page(null, true, "chart 配置不合规（要 Chart.js 的 JSON：type / data.datasets）");
        String fg = dark ? "#E5E5EA" : "#1C1C1E";
        String grid = dark ? "rgba(255,255,255,0.12)" : "rgba(0,0,0,0.1)";
        String js = "<script src=\"" + LIB_CHART + "\"></script><script>(function(){"
                + "function fail(x){document.getElementById('d').innerHTML='<div class=e>图渲染失败</div>';document.title='" + TITLE_ERR + "'+String(x&&x.message||x).slice(0,80);}"
                + "try{if(!window.Chart){fail('lib');return;}"
                + "Chart.defaults.color='" + fg + "';Chart.defaults.borderColor='" + grid + "';"
                + "var cfg=JSON.parse(" + jsString(cfg) + ");"
                + "var box=document.getElementById('d');box.innerHTML='<div style=\"position:relative;height:260px\"><canvas id=c></canvas></div>';"
                + "new Chart(document.getElementById('c'),cfg);"
                + "document.title='" + TITLE_OK + "'+Math.ceil(box.getBoundingClientRect().width)+'x'+Math.ceil(box.scrollHeight);"
                + "}catch(e){fail(e);}})();</script>";
        return new Page(head(CSP_JS, dark, zoom) + "</div>" + js + "</body></html>", true, null);
    }

    public static Page forKind(String kind, String src, boolean dark, boolean zoom) {
        if ("svg".equals(kind)) return svg(src, dark, zoom);
        if ("mermaid".equals(kind)) return mermaid(src, dark, zoom);
        if ("chart".equals(kind)) return chart(src, dark, zoom);
        return new Page(null, false, "不支持的图：" + kind);
    }

    /** 嵌进 &lt;script&gt; 的 JS 字符串字面量：双引号、反斜杠、控制字符、&lt; &gt; &amp;、U+2028/2029 全部转义（不可能提前闭合 script）。 */
    public static String jsString(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '<': b.append("\\u003c"); break;
                case '>': b.append("\\u003e"); break;
                case '&': b.append("\\u0026"); break;
                case '\'': b.append("\\u0027"); break;
                case '\u2028': b.append("\\u2028"); break;
                case '\u2029': b.append("\\u2029"); break;
                default:
                    if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** 从 {@code RO:ok:宽x高} 解析高度（CSS 像素）；不是这个格式返回 -1。 */
    public static int parseOkHeight(String title) {
        if (title == null || !title.startsWith(TITLE_OK)) return -1;
        String t = title.substring(TITLE_OK.length());
        int x = t.indexOf('x');
        if (x < 0) return -1;
        try {
            return Integer.parseInt(t.substring(x + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
