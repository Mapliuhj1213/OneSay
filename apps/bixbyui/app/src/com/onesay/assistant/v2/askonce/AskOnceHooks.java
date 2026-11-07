package com.onesay.assistant.v2.askonce;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.ui.FloatingSheetService;

import org.json.JSONArray;
import org.json.JSONObject;


/**
 * ★ int/1102「问一次、记住」的 Android 侧（插件 {@code AskOncePlugin} 委托到这里）。
 *
 * <ul>
 *   <li>{@link #route}（TAKEOVER，排在所有本地接管之前）：没定过渠道的「发消息」、没定过方式的购物、没说哪类的「看看我的邮件」→ ask.reply + 芯片，记下待回答；</li>
 *   <li>{@link #claims}：这些句子不进 AgentLoop（先问）；</li>
 *   <li>{@link #interceptCommand}（面板输入入口）：待回答期间的回答（点芯片 / 直接说）→ 记偏好 → 把原话（按偏好改写）重新提交；
 *       已定过渠道的「发消息」→ 直接改写成带渠道的说法再提交；</li>
 *   <li>{@link #onReplyShown}：画芯片；{@link #onExecuted}：「播放音乐」按了播放键 1.5 s 还没声音 = 没有播放器 → 改说「听音乐」交给默认音乐 App 角色（没定过就出选择网格，选一次即默认）。</li>
 * </ul>
 */
public final class AskOnceHooks {

    private AskOnceHooks() {}

    static final String TAG = "AskOnce";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile AskOnce.Ask sPending;
    private static volatile long sPendingAt;
    private static volatile String sLastCmd;
    private static volatile long sLastCmdAt;

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(AskOnce.PREFS, Context.MODE_PRIVATE);
    }

    public static String get(Context ctx, String key) {
        try {
            return prefs(ctx).getString(key, null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void set(Context ctx, String key, String val) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (val == null) e.remove(key); else e.putString(key, val);
        e.apply();
        Log.i(TAG, "[ASK-ONCE] 偏好 " + key + "=" + (val == null ? "（清除，下次再问）" : val));
    }

    /** 购物用浏览器跑腿还是 GUI：null = 还没问过。ErrandHooks.intentFor 读。 */
    public static String shopMode(Context ctx) {
        String v = get(ctx, AskOnce.K_SHOP);
        return AskOnce.shopLabel(v) != null ? v : null;
    }

    static AskOnce.Ask livePending() {
        AskOnce.Ask p = sPending;
        if (p == null) return null;
        if (System.currentTimeMillis() - sPendingAt > AskOnce.PENDING_MS) {
            sPending = null;
            return null;
        }
        return p;
    }

    static void setPending(AskOnce.Ask a) {
        sPending = a;
        sPendingAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ 判定（后台线程 / 主线程都可，纯规则 + prefs）

    /** 这句话需要先问一次吗？需要 → 那次追问；不需要 → null。 */
    static AskOnce.Ask needAsk(Context ctx, String query) {
        if (ctx == null || query == null) return null;
        String q = query.trim();
        if (AskOnce.mailGeneric(q)) return AskOnce.askMail(q);
        if (AskOnce.msgNoChannel(q) != null && AskOnce.msgLabel(get(ctx, AskOnce.K_MSG)) == null) return AskOnce.askMsg(q);
        String[] wx = AskOnce.wechatSend(q);
        if (wx != null && !installed(ctx, "com.tencent.mm")) return AskOnce.askWechatMissing(q, wx[0], installed(ctx, "com.tencent.mobileqq"));
        // ★ fix/shop-research：没装任何购物 App → 只有浏览器跑腿一条路，直接跑腿、不弹首问芯片；装了才问一次并记住
        if (shopMode(ctx) == null && isShop(ctx, q) && anyShopApp(ctx)) return AskOnce.askShop(q);
        return null;
    }

    /** 购物 App（京东 / 淘宝 / 天猫 / 拼多多）。 */
    public static final String[] SHOP_APPS = {"com.jingdong.app.mall", "com.taobao.taobao", "com.tmall.wireless", "com.xunmeng.pinduoduo"};

    static boolean anyShopApp(Context ctx) {
        for (String p : SHOP_APPS) if (installed(ctx, p)) return true;
        return false;
    }

    static boolean installed(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isShop(Context ctx, String q) {
        try {
            com.onesay.assistant.v2.errand.ErrandIntent in = com.onesay.assistant.v2.errand.ErrandIntent.parse(q);
            return in.kind == com.onesay.assistant.v2.errand.ErrandIntent.Kind.SHOP
                    && !com.onesay.assistant.v2.errand.ErrandIntent.explicitErrand(q)
                    && com.onesay.assistant.v2.errand.ErrandSettings.enabled(ctx) && com.onesay.assistant.v2.errand.ErrandSettings.SHOP.on(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean claims(Context ctx, String query) {
        try {
            return needAsk(ctx, query) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 后台线程（路由 TAKEOVER）。 */
    public static Invocation route(Context ctx, String query) {
        try {
            AskOnce.Ask a = needAsk(ctx, query);
            if (a == null) return null;
            setPending(a);
            Log.i(TAG, "[ASK-ONCE] 先问一次 " + a);
            return askInvocation(a, query);
        } catch (Throwable t) {
            Log.w(TAG, "[ASK-ONCE] route 异常，回落原流程: " + t.getMessage());
            return null;
        }
    }

    static Invocation askInvocation(AskOnce.Ask a, String query) throws Exception {
        JSONObject args = new JSONObject();
        args.put("text", a.text);
        args.put("query", query == null ? "" : query.trim());
        args.put("askonce", a.topic.name());
        if (a.chips != null) args.put("chips", new JSONArray(a.chips));
        return new Invocation("ask.reply", args, 1.0, Invocation.RouteSource.L0_RULE, query);
    }

    // ------------------------------------------------------------------ 面板输入（主线程）

    public static boolean interceptCommand(final FloatingSheetService svc, String command) {
        try {
            if (svc == null || command == null) return false;
            sLastCmd = command;
            sLastCmdAt = System.currentTimeMillis();
            AskOnce.Ask p = livePending();
            if (p != null) {
                AskOnce.Answer ans = AskOnce.answer(p, command);
                if (ans == null) {
                    Log.i(TAG, "[ASK-ONCE] 待回答期间说了别的，放弃追问 " + p.topic);
                    sPending = null;
                } else {
                    sPending = null;
                    if (ans.prefKey != null) set(svc, ans.prefKey, ans.prefVal);
                    if (ans.next != null) {
                        setPending(new AskOnce.Ask(ans.next.topic, ans.next.text, ans.next.chips, p.original));
                        svc.getSheetView().showAskReply(command, ans.next.text);
                        Log.i(TAG, "[ASK-ONCE] 接着问 " + ans.next.topic);
                        return true;
                    }
                    if (ans.resubmit != null) {
                        Log.i(TAG, "[ASK-ONCE] 回答 " + p.topic + " → 重新提交: " + com.onesay.assistant.v2.util.LogText.u(ans.resubmit));
                        resubmit(svc, ans.resubmit);
                        return true;
                    }
                }
            }
            // 已经定过渠道的「发消息」：改写成带渠道的说法（原路由：微信 / QQ → GUI 发送前确认，短信 → 确认卡）
            String ch = AskOnce.msgLabel(get(svc, AskOnce.K_MSG));
            if (ch != null && AskOnce.msgNoChannel(command) != null) {
                String rw = AskOnce.msgRewrite(command, ch);
                if (rw != null && !rw.equals(command)) {
                    Log.i(TAG, "[ASK-ONCE] 发消息按默认渠道 " + ch + " → " + com.onesay.assistant.v2.util.LogText.u(rw));
                    resubmit(svc, rw);
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "[ASK-ONCE] 拦截异常，回落原流程: " + t.getMessage());
            return false;
        }
    }

    private static void resubmit(final FloatingSheetService svc, final String q) {
        MAIN.post(new Runnable() {
            @Override public void run() {
                try {
                    svc.onSubmitCommand(q);
                } catch (Throwable t) {
                    Log.w(TAG, "[ASK-ONCE] 重新提交失败: " + t.getMessage());
                }
            }
        });
    }

    /** 主线程：带 askonce 的回复上屏后画芯片（点一下 = 说了这句）。 */
    public static void onReplyShown(final FloatingSheetService svc, Invocation inv) {
        try {
            if (svc == null || inv == null || inv.args == null || !inv.args.has("askonce")) return;
            JSONArray arr = inv.args.optJSONArray("chips");
            if (arr == null || arr.length() == 0) return;
            final java.util.List<String> labels = new java.util.ArrayList<String>();
            for (int i = 0; i < arr.length(); i++) labels.add(arr.optString(i));
            com.onesay.assistant.v2.ui.FloatingSheetView sheet = svc.getSheetView();
            if (sheet == null) return;
            android.view.View existing = sheet.findViewWithTag(com.onesay.assistant.v2.ui.ActionChipsView.TAG_IN_SHEET);
            com.onesay.assistant.v2.ui.ActionChipsView v;
            if (existing instanceof com.onesay.assistant.v2.ui.ActionChipsView) v = (com.onesay.assistant.v2.ui.ActionChipsView) existing;
            else {
                v = new com.onesay.assistant.v2.ui.ActionChipsView(svc);
                sheet.addView(v, Math.min(2, sheet.getChildCount()), new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final com.onesay.assistant.v2.ui.ActionChipsView fv = v;
            v.setListener(new com.onesay.assistant.v2.ui.ActionChipsView.Listener() {
                @Override public void onChipClicked(int index) {
                    if (index < 0 || index >= labels.size()) return;
                    try { com.onesay.assistant.v2.wake.WakeService.cancelCaptureFromUi(); } catch (Throwable ignored) {}
                    fv.hideChips();
                    svc.onSubmitCommand(labels.get(index));
                }
            });
            v.showChips(labels);
            Log.i(TAG, "[ASK-ONCE] 芯片 " + labels);
        } catch (Throwable t) {
            Log.w(TAG, "[ASK-ONCE] 芯片异常: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ 播放音乐：没有播放器 → 默认音乐 App

    public static final String MUSIC_ROLE_UTTERANCE = "听音乐";

    public static void onExecuted(final FloatingSheetService svc, Capability cap, JSONObject args, ExecutionResult r) {
        try {
            if (svc == null || cap == null || !"media.play".equals(cap.sign)) return;
            final String cmd = sLastCmd;
            if (cmd == null || System.currentTimeMillis() - sLastCmdAt > 15000L || !musicFallbackWanted(cmd)) return;
            MAIN.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        android.media.AudioManager am = (android.media.AudioManager) svc.getSystemService(Context.AUDIO_SERVICE);
                        boolean active = am != null && am.isMusicActive();
                        Log.i(TAG, "[ASK-ONCE] 播放键 1.5 s 后 isMusicActive=" + active);
                        if (!active) {
                            Log.i(TAG, "[ASK-ONCE] 没有播放器在放 → 交给默认音乐 App（没定过就选一次）");
                            svc.onSubmitCommand(MUSIC_ROLE_UTTERANCE);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "[ASK-ONCE] 播放检查异常: " + t.getMessage());
                    }
                }
            }, 1500L);
        } catch (Throwable t) {
            Log.w(TAG, "[ASK-ONCE] onExecuted 异常: " + t.getMessage());
        }
    }

    public static boolean musicFallbackWanted(String cmd) {
        return AskOnce.musicFallbackWanted(cmd);
    }

    // ------------------------------------------------------------------ 设置页（记忆 › 常用默认）

    public static void addConsole(final com.onesay.assistant.v2.kernel.ConsoleHost host, final android.widget.LinearLayout root) {
        android.widget.LinearLayout g = host.group(root, "常用默认（问过一次就记住）");
        fillConsole(host, g);
        host.footer(root, "打车、听歌用哪个 App 在「默认应用」里改；没定过时第一次会让你选。");
    }

    static void fillConsole(final com.onesay.assistant.v2.kernel.ConsoleHost host, final android.widget.LinearLayout g) {
        final android.app.Activity act = host.activity();
        android.content.SharedPreferences sp = com.onesay.assistant.v2.sched.SchedHooks.prefs(act);
        String rm = sp.getString(com.onesay.assistant.v2.sched.SchedEngine.PREF_REMIND_MINUTE, null);
        String late = sp.getString(com.onesay.assistant.v2.sched.SchedEngine.PREF_TODAY_LATE, null);
        final Runnable reload = new Runnable() {
            @Override public void run() {
                g.removeAllViews();
                fillConsole(host, g);
            }
        };
        host.actionRow(g, "提醒没说几点", rm == null ? "还没选，第一次用到时会问你" : "默认 " + minuteLabel(rm), "改", new Runnable() {
            @Override public void run() {
                pick(act, "只说日子的提醒默认几点", new String[]{"早上8点", "上午9点", "上午10点", "下次再问我"},
                        new String[]{"480", "540", "600", null}, new Pick() {
                            @Override public void on(String v) {
                                com.onesay.assistant.v2.sched.SchedHooks.setAskOnce(act, com.onesay.assistant.v2.sched.SchedEngine.PREF_REMIND_MINUTE, v);
                                host.ui(reload);
                            }
                        });
            }
        });
        host.actionRow(g, "今天的提醒时间已过", late == null ? "还没选，第一次用到时会问你" : com.onesay.assistant.v2.sched.SchedEngine.lateLabel(late), "改", new Runnable() {
            @Override public void run() {
                pick(act, "今天的默认提醒时间已经过了时", new String[]{"1 小时后提醒", "今晚 8 点提醒", "每次问我几点", "下次再问我"},
                        new String[]{com.onesay.assistant.v2.sched.SchedEngine.LATE_1H, com.onesay.assistant.v2.sched.SchedEngine.LATE_EVENING,
                                com.onesay.assistant.v2.sched.SchedEngine.LATE_ASK, null}, new Pick() {
                            @Override public void on(String v) {
                                com.onesay.assistant.v2.sched.SchedHooks.setAskOnce(act, com.onesay.assistant.v2.sched.SchedEngine.PREF_TODAY_LATE, v);
                                host.ui(reload);
                            }
                        });
            }
        });
        String msg = AskOnce.msgLabel(get(act, AskOnce.K_MSG));
        host.actionRow(g, "「发消息」没说用什么", msg == null ? "还没选，第一次用到时会问你" : "默认用" + msg, "改", new Runnable() {
            @Override public void run() {
                pick(act, "「给某人发消息」默认用", new String[]{"微信", "短信", "QQ", "下次再问我"},
                        new String[]{AskOnce.MSG_WECHAT, AskOnce.MSG_SMS, AskOnce.MSG_QQ, null}, new Pick() {
                            @Override public void on(String v) {
                                set(act, AskOnce.K_MSG, v);
                                host.ui(reload);
                            }
                        });
            }
        });
        String shop = shopMode(act);
        host.actionRow(g, "购物类多步任务", shop == null ? "还没选，第一次用到时会问你" : AskOnce.shopLabel(shop), "改", new Runnable() {
            @Override public void run() {
                pick(act, "购物类多步任务怎么做", new String[]{"浏览器跑腿（网页版，停在付款前）", "直接操作 App（下单前确认）", "下次再问我"},
                        new String[]{AskOnce.SHOP_BROWSER, AskOnce.SHOP_GUI, null}, new Pick() {
                            @Override public void on(String v) {
                                set(act, AskOnce.K_SHOP, v);
                                host.ui(reload);
                            }
                        });
            }
        });
    }

    static String minuteLabel(String v) {
        try {
            int m = Integer.parseInt(v);
            int h = m / 60, mm = m % 60;
            String p = h < 6 ? "凌晨" : h < 9 ? "早上" : h < 12 ? "上午" : h < 13 ? "中午" : h < 18 ? "下午" : "晚上";
            int h12 = h > 12 ? h - 12 : h;
            return p + h12 + "点" + (mm == 0 ? "" : mm == 30 ? "半" : mm + "分");
        } catch (Exception e) {
            return v;
        }
    }

    interface Pick {
        void on(String v);
    }

    static void pick(android.app.Activity act, String title, String[] labels, final String[] vals, final Pick cb) {
        new android.app.AlertDialog.Builder(act).setTitle(title).setItems(labels, new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int which) {
                if (which >= 0 && which < vals.length) cb.on(vals[which]);
            }
        }).setNegativeButton("取消", null).show();
    }

    public static String debugStatus(Context ctx) {
        AskOnce.Ask p = livePending();
        return "msg=" + get(ctx, AskOnce.K_MSG) + " shop=" + get(ctx, AskOnce.K_SHOP) + " pending=" + (p == null ? "-" : p.topic.name());
    }
}
