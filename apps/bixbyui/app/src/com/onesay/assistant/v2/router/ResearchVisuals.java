package com.onesay.assistant.v2.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ 11-04 ④（用户：调研结果全是大段文字没有图）：调研 / 购物结论的配图模型（纯函数，JVM 可测；绘制在 ui.PriceBarView）。
 *
 * <ul>
 *   <li>代表款缩略图：只用读过的网页 / 比价商品页里出现的 og:image / 商品主图（{@link #collectImage}），公网 https、非 svg / data / 内网，
 *       最多 3 张；界面在内存里解码，加载失败就隐藏，不落盘；</li>
 *   <li>价格条形图：结论里「名称 … 数字元」的行 ≥ 3 条 → 每行一根横条（复用 MarketChartView 的绘制思路：名 / 条 / 数值三列，
 *       列宽按实测，不加省略号），数值取结论原文字符串，只用数字定条长；</li>
 *   <li>按结论文字指纹配对（与 {@link MarketChart} 同口径），30 分钟有效，进程内一份。</li>
 * </ul>
 */
public final class ResearchVisuals {

    private ResearchVisuals() {}

    public static final int MAX_IMAGES = 3;
    public static final int MAX_BARS = 8;
    public static final int MIN_BARS = 3;
    public static final long TTL_MS = 30L * 60L * 1000L;

    public static final class Bar {
        public final String label;
        public final double value;
        public final String text;

        Bar(String label, double value, String text) {
            this.label = label;
            this.value = value;
            this.text = text;
        }
    }

    public static final class Model {
        public final List<Bar> bars = new ArrayList<Bar>();
        public final List<String> images = new ArrayList<String>();
        String key;
        long createdAt;

        public boolean hasChart() {
            return bars.size() >= MIN_BARS;
        }

        public double max() {
            double m = 0;
            for (Bar b : bars) m = Math.max(m, b.value);
            return m;
        }
    }

    // ------------------------------------------------------------------ 图片来源（读过的页面）

    private static final List<String[]> sImages = new ArrayList<String[]>();   // {url, atMillis}

    /** 读页面时记下页面主图（og:image / 商品主图）。只收公网 https。 */
    public static synchronized void collectImage(String url) {
        String u = publicHttps(url);
        if (u == null) return;
        for (String[] e : sImages) if (e[0].equals(u)) return;
        sImages.add(new String[] {u, String.valueOf(System.currentTimeMillis())});
        while (sImages.size() > 12) sImages.remove(0);
    }

    static synchronized List<String> recentImages(long now) {
        List<String> out = new ArrayList<String>();
        for (int i = sImages.size() - 1; i >= 0 && out.size() < MAX_IMAGES; i--) {
            String[] e = sImages.get(i);
            long at = Long.parseLong(e[1]);
            if (now - at <= 10L * 60L * 1000L) out.add(e[0]);
        }
        return out;
    }

    /** 新任务开始：清掉上一轮的图（不串到别的结论上）。 */
    public static synchronized void resetImages() {
        sImages.clear();
    }

    static final Pattern OG = Pattern.compile("<meta[^>]+(?:property|name)\\s*=\\s*[\"'](?:og:image|og:image:secure_url|twitter:image)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    static final Pattern CONTENT = Pattern.compile("content\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /** HTML 里的 og:image（相对地址按页面补全）；没有 → null。 */
    public static String ogImage(String html, String pageUrl) {
        if (html == null) return null;
        Matcher m = OG.matcher(html.length() > 200000 ? html.substring(0, 200000) : html);
        while (m.find()) {
            Matcher c = CONTENT.matcher(m.group());
            if (!c.find()) continue;
            String u = c.group(1).trim().replace("&amp;", "&");
            if (u.startsWith("//")) u = "https:" + u;
            else if (u.startsWith("/") && pageUrl != null) {
                Matcher h = Pattern.compile("^(https?://[^/]+)").matcher(pageUrl);
                if (h.find()) u = h.group(1) + u;
            }
            String p = publicHttps(u);
            if (p != null) return p;
        }
        return null;
    }

    /** 只要公网 https 图片：非 svg / gif 动图 / data: / 内网地址。 */
    public static String publicHttps(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (!u.startsWith("https://") || u.length() > 600) return null;
        String low = u.toLowerCase(Locale.ROOT);
        if (low.matches(".*\\.(svg|ico)(\\?.*)?$")) return null;
        String host = low.substring(8);
        int sl = host.indexOf('/');
        if (sl >= 0) host = host.substring(0, sl);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        if (host.length() == 0 || host.equals("localhost") || host.endsWith(".local") || host.endsWith(".internal")) return null;
        if (host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            if (host.startsWith("10.") || host.startsWith("127.") || host.startsWith("192.168.") || host.startsWith("169.254.")
                    || host.matches("172\\.(1[6-9]|2\\d|3[01])\\..*") || host.startsWith("0.") || host.startsWith("100.")) return null;
        }
        if (host.indexOf('.') < 0) return null;
        return u;
    }

    // ------------------------------------------------------------------ 价格条形图（结论原文）

    static final Pattern PRICE = Pattern.compile("(?:¥|￥)\\s*([0-9]+(?:\\.[0-9]+)?)|([0-9]+(?:\\.[0-9]+)?)\\s*(万元|万|元|块)");

    /** 结论里的价格行 → 条（每行取第一个价格；名称 = 价格前的文字，去列表符 / Markdown / 编号，≤12 字）。 */
    static List<Bar> bars(String answer) {
        List<Bar> out = new ArrayList<Bar>();
        if (answer == null) return out;
        for (String line : answer.split("\n")) {
            String l = line.trim().replaceAll("\\*\\*|__|`", "");
            if (!l.matches("^(?:[-•·*]|\\d+[.、)）]).*")) continue;   // 只看列表行（要点）
            Matcher m = PRICE.matcher(l);
            if (!m.find()) continue;
            String num = m.group(1) != null ? m.group(1) : m.group(2);
            double v;
            try {
                v = Double.parseDouble(num);
            } catch (Exception e) {
                continue;
            }
            String unit = m.group(3);
            if (unit != null && unit.startsWith("万")) v *= 10000;
            if (v <= 0) continue;
            String label = l.substring(0, m.start()).replaceAll("^(?:[-•·*]|\\d+[.、)）])\\s*", "")
                    .replaceAll("\\[[0-9, ]+\\]", "").replaceAll("[:：，,（(\\s]+$", "").trim();
            int cut = label.indexOf('：');
            if (cut < 0) cut = label.indexOf(':');
            if (cut > 1) label = label.substring(0, cut);
            label = label.replaceAll("[「」《》\"]", "").trim();
            if (label.length() > 12) label = label.substring(0, 12);
            if (label.length() == 0) continue;
            String text = m.group().replaceAll("\\s+", "");
            out.add(new Bar(label, v, text));
            if (out.size() >= MAX_BARS) break;
        }
        return out;
    }

    private static volatile Model sLast;

    /** 结论收工时调用：有 ≥3 条价格或有图就登记一份（界面按指纹配对）。返回模型（没有可画的 → null）。 */
    public static Model attach(String answer, long now) {
        if (answer == null || answer.trim().length() == 0) return null;
        Model m = new Model();
        m.bars.addAll(bars(answer));
        if (m.bars.size() < MIN_BARS) m.bars.clear();
        m.images.addAll(recentImages(now));
        if (m.bars.isEmpty() && m.images.isEmpty()) return null;
        m.key = MarketChart.keyOf(answer.trim());
        m.createdAt = now;
        sLast = m;
        return m;
    }

    public static Model match(String text, long now) {
        Model m = sLast;
        if (m == null || text == null || m.key == null || m.key.length() == 0) return null;
        if (!MarketChart.keyOf(text).equals(m.key)) return null;
        if (now - m.createdAt > TTL_MS || now < m.createdAt - 60000) return null;
        return m;
    }

    static void clear() {
        sLast = null;
        resetImages();
    }

    /** 数值格式（条右侧）：原文字符串优先。 */
    public static String fmt(Bar b) {
        return b.text != null && b.text.length() > 0 ? b.text : String.format(Locale.ROOT, "%.0f", b.value);
    }
}
