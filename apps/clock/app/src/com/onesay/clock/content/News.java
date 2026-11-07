package com.onesay.clock.content;

import android.content.Context;

import com.onesay.clock.L;
import com.onesay.clock.core.NewsMerge;
import com.onesay.clock.core.Template;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 新闻：今日头条热榜 / B 站热搜 / 腾讯要闻都是可选源（非官方公开接口），默认全关，在设置里逐个打开才参与；
 * 全关 = 播报里不念新闻。
 * 原说明：腾讯要闻 / 今日头条热榜 / B 站热搜 三源并发（各 8 秒），交给 NewsMerge 选源、清洗、去重。接口地址沿用 v1 Sources（2026-09-29 实测，11-05 沙箱复测腾讯/头条可用）。
 */
public final class News {
    private News() { }

    static final String QIMEI = qimei();

    static String qimei() {
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 36; i++) sb.append("0123456789abcdef".charAt(r.nextInt(16)));
        return sb.toString();
    }

    public static NewsMerge.Result fetch(Context c, long budgetMs) {
        ExecutorService ex = Executors.newFixedThreadPool(3);
        long t0 = System.currentTimeMillis();
        Map<String, Future<List<String>>> fs = new LinkedHashMap<String, Future<List<String>>>();
        if (new com.onesay.clock.Prefs(c).flag(com.onesay.clock.core.Flags.X_NEWS_TENCENT))
            fs.put("tencent", ex.submit(new Callable<List<String>>() { public List<String> call() { return tencent(); } }));
        if (new com.onesay.clock.Prefs(c).flag(com.onesay.clock.core.Flags.X_NEWS_TOUTIAO))
            fs.put("toutiao", ex.submit(new Callable<List<String>>() { public List<String> call() { return toutiao(); } }));
        if (new com.onesay.clock.Prefs(c).flag(com.onesay.clock.core.Flags.X_NEWS_BILIBILI))
            fs.put("bilibili", ex.submit(new Callable<List<String>>() { public List<String> call() { return bilibili(); } }));
        Map<String, List<String>> got = new LinkedHashMap<String, List<String>>();
        StringBuilder sum = new StringBuilder();
        for (Map.Entry<String, Future<List<String>>> e : fs.entrySet()) {
            List<String> l = null;
            try { l = e.getValue().get(Math.max(100, budgetMs - (System.currentTimeMillis() - t0)), TimeUnit.MILLISECONDS); }
            catch (Exception ignored) { e.getValue().cancel(true); }
            if (l == null) l = new ArrayList<String>();
            got.put(e.getKey(), l);
            sum.append(e.getKey()).append('=').append(l.size()).append(' ');
        }
        ex.shutdownNow();
        NewsMerge.Result r = NewsMerge.pick(got, Template.NEWS_N);
        L.i(c, "NEWS " + sum + "→ " + r.sourceKey + " pool=" + r.titles.size() + " ms=" + (System.currentTimeMillis() - t0));
        return r;
    }

    static List<String> tencent() {
        List<String> out = new ArrayList<String>();
        try {
            JSONObject b = new JSONObject().put("base_req", new JSONObject().put("from", "pc")).put("forward", "2")
                    .put("qimei36", QIMEI).put("device_id", QIMEI).put("flush_num", 0).put("channel_id", "news_news_top").put("item_count", 20);
            JSONArray d = new JSONObject(Http.postJson("https://i.news.qq.com/web_feed/getHotModuleList", b.toString(), 8000)).optJSONArray("data");
            for (int i = 0; d != null && i < d.length(); i++) {
                JSONObject it = d.optJSONObject(i);
                if (it != null && it.optString("title").length() > 0) out.add(it.optString("title"));
            }
        } catch (Exception ignored) { }
        return out;
    }

    static List<String> toutiao() {
        List<String> out = new ArrayList<String>();
        try {
            JSONArray d = new JSONObject(Http.get("https://www.toutiao.com/hot-event/hot-board/?origin=toutiao_pc", 8000)).optJSONArray("data");
            for (int i = 0; d != null && i < d.length() && out.size() < 30; i++) {
                JSONObject it = d.optJSONObject(i);
                if (it == null) continue;
                String t = it.optString("Title", it.optString("title", ""));
                if (t.length() > 0) out.add(t);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static List<String> bilibili() {
        List<String> out = new ArrayList<String>();
        try {
            JSONObject o = new JSONObject(Http.get("https://api.bilibili.com/x/web-interface/search/square?limit=20", 8000));
            JSONObject d = o.optJSONObject("data");
            JSONObject tr = d == null ? null : d.optJSONObject("trending");
            JSONArray l = tr == null ? null : tr.optJSONArray("list");
            for (int i = 0; l != null && i < l.length(); i++) {
                JSONObject it = l.optJSONObject(i);
                if (it == null) continue;
                String t = it.optString("show_name", it.optString("keyword", ""));
                if (t.length() > 0) out.add(t);
            }
        } catch (Exception ignored) { }
        return out;
    }
}
