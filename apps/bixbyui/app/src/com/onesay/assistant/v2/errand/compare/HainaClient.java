package com.onesay.assistant.v2.errand.compare;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ fix/shop-research（用户 11-03 16:15）：什么值得买「海纳开放平台」商品检索 Pro / 好价检索 Pro 的接口封装（纯 Java，JVM 可测）。
 * <ul>
 *   <li>主机 https://gw-openapi.zhidemai.com，请求头 {@code x-api-key}，JSON POST；商品检索 Pro = {@code /product/v1}，
 *       好价检索 Pro = {@code /hj-search/discount/v2}（路径取自 ai.zhidemai.com/haina-platform 文档页的前端配置，11-03 沙箱实测 200）。</li>
 *   <li>key 由调用方从现有密钥存储（与 DeepSeek / Tavily 同一套 SecretPrefs）取出传入；本类不存 key、不打日志，日志只允许记长度。</li>
 *   <li>同一查询 30 分钟内存缓存；当日（Asia/Shanghai）调用计数，达到 {@link #DAILY_SOFT_LIMIT}（个人日上限约 1000 的 90%）就不再调用，
 *       调用方降级到比价网（粉象 / 慢慢买）。</li>
 * </ul>
 * 状态：只做了封装 + 解析 + JVM 测试（fixture 为 11-03 实测脱敏响应）；<b>尚未接进 ResearchLadder.runCompare</b>（用户 16:18 收窄），
 * 接法见 RESEARCH-TEST-basilisk.md §7.9。
 */
public final class HainaClient {

    public static final String HOST = "https://gw-openapi.zhidemai.com";
    public static final String PRODUCT_PATH = "/product/v1";
    public static final String DEAL_PATH = "/hj-search/discount/v2";
    public static final long CACHE_TTL_MS = 30L * 60 * 1000;
    public static final int DAILY_SOFT_LIMIT = 900;

    /** 发 POST（带 x-api-key 头）；失败返回 null。安卓实现用 HttpURLConnection，JVM 测试回 fixture。 */
    public interface Post {
        String post(String url, String apiKey, String jsonBody, int timeoutMs);
    }

    private final Post mPost;
    private final Map<String, Object[]> mCache = new LinkedHashMap<String, Object[]>();
    private String mDay = "";
    private int mCalls;

    public HainaClient(Post post) {
        mPost = post;
    }

    public synchronized int callsToday(long now) {
        roll(now);
        return mCalls;
    }

    private void roll(long now) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"), Locale.ROOT);
        c.setTimeInMillis(now);
        String d = String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        if (!d.equals(mDay)) {
            mDay = d;
            mCalls = 0;
        }
    }

    /** 当日额度是否已到软上限（到了就降级，不再调用）。 */
    public synchronized boolean exhausted(long now) {
        roll(now);
        return mCalls >= DAILY_SOFT_LIMIT;
    }

    static String body(String query, int size) {
        try {
            JSONObject o = new JSONObject();
            o.put("request_from", "bixbyui");
            o.put("question_id", "q" + Integer.toHexString(query.hashCode()));
            o.put("product_query", query);
            o.put("query_process", 1);
            o.put("size", size);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 商品检索 Pro：各商城各店铺在售价 + 常卖价 / 历史最低 / 30 天最低标签。key 为空 / 额度到了 / 失败 → 空表。 */
    public List<CompareDeal> products(String apiKey, String query, long now) {
        String json = call(PRODUCT_PATH, apiKey, query, 20, now);
        return json == null ? new ArrayList<CompareDeal>() : parseProducts(json, now);
    }

    /** 好价检索 Pro：真人审核的好价（到手价、页面价、商城、发布时间、商品直达链接）。 */
    public List<CompareDeal> deals(String apiKey, String query, long now) {
        String json = call(DEAL_PATH, apiKey, query, 20, now);
        return json == null ? new ArrayList<CompareDeal>() : parseDeals(json, now);
    }

    private String call(String path, String apiKey, String query, int size, long now) {
        if (apiKey == null || apiKey.length() == 0 || query == null || query.trim().length() == 0) return null;
        String ck = path + "|" + query.trim().toLowerCase(Locale.ROOT);
        synchronized (this) {
            Object[] c = mCache.get(ck);
            if (c != null && now - (Long) c[0] < CACHE_TTL_MS && now >= (Long) c[0]) return (String) c[1];
            roll(now);
            if (mCalls >= DAILY_SOFT_LIMIT) return null;
            mCalls++;
        }
        String r;
        try {
            r = mPost.post(HOST + path, apiKey, body(query.trim(), size), 30000);
        } catch (Throwable t) {
            r = null;
        }
        if (r == null || !ok(r)) return null;
        synchronized (this) {
            mCache.put(ck, new Object[]{now, r});
            if (mCache.size() > 50) mCache.remove(mCache.keySet().iterator().next());
        }
        return r;
    }

    static boolean ok(String json) {
        try {
            return new JSONObject(json).optInt("error_code", -1) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static final Pattern TAG = Pattern.compile("['\"]title['\"]:\\s*['\"]([^'\"]+)['\"],\\s*['\"]price['\"]:\\s*['\"]([^'\"]+)['\"]");   // 实测是 JSON 数组；文档示例是 Python 字面量字符串，两种都认

    /** 商品检索 Pro 的 rows → CompareDeal（site=「值得买商品库」；plan = 页面价 / 常卖价 / 历史最低 / 30 天最低；tags = 店铺类型）。 */
    public static List<CompareDeal> parseProducts(String json, long now) {
        List<CompareDeal> out = new ArrayList<CompareDeal>();
        JSONArray rows = rows(json);
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            String price = r.optString("price", "");
            double p = FenxiangParser.money(price);
            if (p <= 0) continue;
            StringBuilder plan = new StringBuilder();
            String pp = r.optString("page_price", "");
            if (pp.length() > 0) plan.append("页面价 ¥").append(pp.replaceFirst("\\.0$", ""));
            List<String[]> tags = new ArrayList<String[]>();
            JSONArray ta = r.optJSONArray("price_tags");
            if (ta != null) {
                for (int k = 0; k < ta.length(); k++) {
                    JSONObject t = ta.optJSONObject(k);
                    if (t != null) tags.add(new String[]{t.optString("title", ""), t.optString("price", "")});
                }
            } else {
                Matcher m = TAG.matcher(r.optString("price_tags", ""));
                while (m.find()) tags.add(new String[]{m.group(1), m.group(2)});
            }
            for (String[] t : tags) {
                if (t[0].contains("618") || t[0].contains("双11") || t[0].contains("历史") || t[0].contains("30天") || t[0].contains("常卖")) {
                    plan.append(plan.length() > 0 ? " · " : "").append(t[0]).append(' ').append(t[1].replaceFirst("\\.00$", ""));
                }
            }
            String mall = r.optString("mall_name", "");
            String shop = r.optString("shop_name", "");
            out.add(new CompareDeal("值得买商品库", r.optString("title", ""), p, price, mall + (shop.length() > 0 ? "「" + shop + "」" : ""),
                    "", today(now), 0, plan.toString(), r.optString("shop_type", ""), r.optString("url", ""), now));
        }
        return out;
    }

    /** 好价检索 Pro 的 rows → CompareDeal（site=「值得买好价」；date = pubdate；url 优先商品直达链接）。 */
    public static List<CompareDeal> parseDeals(String json, long now) {
        List<CompareDeal> out = new ArrayList<CompareDeal>();
        JSONArray rows = rows(json);
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            String dp = r.optString("digital_price", r.optString("price", ""));
            double p = FenxiangParser.money(dp);
            if (p <= 0) continue;
            String pub = r.optString("pubdate", "");
            String[] d = ManmanbuyParser.inferDate(pub, now);
            String pp = r.optString("page_price", "");
            String plan = pp.length() > 0 ? "页面价 ¥" + pp + " → 到手 ¥" + dp : "";
            String link = r.optString("direct_link", "");
            if (link.length() == 0) link = r.optString("url", "");
            out.add(new CompareDeal("值得买好价", r.optString("title", ""), p, "¥" + dp, r.optString("mall_name", ""), pub, d[0],
                    Integer.parseInt(d[1]), plan, "", link, now));
        }
        return out;
    }

    private static JSONArray rows(String json) {
        try {
            JSONObject d = new JSONObject(json).optJSONObject("data");
            return d == null ? null : d.optJSONArray("rows");
        } catch (Exception e) {
            return null;
        }
    }

    private static String today(long now) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"), Locale.ROOT);
        c.setTimeInMillis(now);
        return String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }
}
