package com.onesay.assistant.v2.conn.watch;

import android.content.Context;

import com.onesay.assistant.v2.conn.ConnHooks;
import com.onesay.assistant.v2.conn.ConnResult;
import com.onesay.assistant.v2.conn.ConnSettings;
import com.onesay.assistant.v2.conn.ConnTool;
import com.onesay.assistant.v2.conn.Connector;
import com.onesay.assistant.v2.conn.ConnectorRegistry;
import com.onesay.assistant.v2.conn.TavilyConnector;

import org.json.JSONObject;

/**
 * [conn/watch] Android 装配：进程内单例 {@link WatchService}，状态存 bixby_conn prefs（键 watches），搜索走 Tavily 适配器
 * （{@code conn.tavily.search}，与现有搜索同一个 Key、同一个实现）。A 线接入点见 {@link WatchPorts}。
 */
public final class WatchHooks {

    private WatchHooks() {}

    static final String KEY = "watches";
    private static volatile WatchService sSvc;

    public static WatchService service(Context ctx) {
        WatchService s = sSvc;
        if (s != null) return s;
        synchronized (WatchHooks.class) {
            if (sSvc == null) {
                final Context app = ctx.getApplicationContext();
                sSvc = new WatchService(new WatchService.Store() {
                    @Override public String load() { return ConnSettings.prefs(app).getString(KEY, "[]"); }
                    @Override public void save(String json) { ConnSettings.prefs(app).edit().putString(KEY, json).apply(); }
                }, new WatchService.Searcher() {
                    @Override public ConnResult search(String query, int max) {
                        ConnectorRegistry reg = ConnHooks.registry(app);
                        String sign = ConnTool.signOf(TavilyConnector.ID, TavilyConnector.TOOL_SEARCH);
                        ConnTool t = reg.findAny(sign);
                        Connector c = t == null ? null : reg.get(t.connectorId);
                        if (c == null) return ConnResult.fail(ConnResult.Code.NOT_CONFIGURED, "Tavily 搜索 Key 没配置");
                        try {
                            return c.call(t.name, new JSONObject().put("query", query).put("max_results", max));
                        } catch (Exception e) {
                            return ConnResult.fail(ConnResult.Code.BAD_ARGS, "参数生成失败");
                        }
                    }
                }, null);
            }
            return sSvc;
        }
    }
}
