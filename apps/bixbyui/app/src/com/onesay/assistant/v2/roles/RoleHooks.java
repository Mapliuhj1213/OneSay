package com.onesay.assistant.v2.roles;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.s2.HabitPolicy;
import com.onesay.assistant.v2.s2.S2Capabilities;
import com.onesay.assistant.v2.s2.S2Clock;
import com.onesay.assistant.v2.s2.S2Engine;
import com.onesay.assistant.v2.s2.S2Hooks;
import com.onesay.assistant.v2.s2.S2Settings;
import com.onesay.assistant.v2.ui.ActionChipsView;
import com.onesay.assistant.v2.ui.ChoiceGridView;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.ui.FloatingSheetView;
import com.onesay.assistant.v2.ui.InstallPromptView;
import com.onesay.assistant.v2.wake.WakeService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * [roles] 默认应用角色层的<b>全部 Android 挂钩</b>（写法同 S2Hooks / DiagHooks：既有文件里只有几行 {@code // [roles]} 调用指向这里）。
 *
 * <table>
 *   <tr><td>R-H1</td><td>S2Hooks.engine / selfTestEngine</td><td>{@link #attach}：角色目录（assets/app_roles.json）+ 设备快照</td></tr>
 *   <tr><td>R-H2</td><td>FloatingSheetService：ask.reply 上屏之后</td><td>{@link #onReplyShown}：选择芯片 / 安装提示</td></tr>
 *   <tr><td>R-H3</td><td>FloatingSheetService：执行失败</td><td>{@link #onExecFailed}：通用「未安装 X，点此安装」（任何带 pkg 的能力）</td></tr>
 *   <tr><td>R-H5</td><td>FloatingSheetService：新一次唤醒</td><td>{@link #onWake}：收起残留安装提示</td></tr>
 *   <tr><td>R-H4</td><td>FloatingSheetService：执行成功（非跳转类）</td><td>{@link #onExecuted}：设了未安装的默认 → 安装提示</td></tr>
 * </table>
 * 开关沿用 S2 的 {@code habit}（SharedPreferences bixby_s2）与 kill-switch 文件 {@code habit.off}；关闭时 S2 不接管角色句子，
 * 这里的挂钩也什么都不做（安装提示 R-H3 除外：它只在执行已经失败、且能力明确给了包名时出现，与开关无关）。
 */
public final class RoleHooks {

    private static final String TAG = "Roles";

    private RoleHooks() {}

    private static volatile AndroidDeviceSnapshot sSnap;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** [R-H1] 进程级角色目录（assets 读一次）+ 设备快照，装到 S2 引擎上。 */
    public static void attach(Context ctx, S2Engine e) {
        if (ctx == null || e == null) return;
        try {
            e.setRoles(catalog(ctx));
            e.setDevice(snapshot(ctx));
        } catch (Throwable t) {
            Log.w(TAG, "[ROLES] attach 异常（角色层退化为旧 S2 行为）: " + t.getMessage());
        }
    }

    public static RoleCatalog catalog(Context ctx) {
        synchronized (RoleHooks.class) {
            RoleCatalog c = RoleCatalog.active();
            if (c != null && !"none".equals(c.source())) return c;
            java.io.InputStream in = null;
            try {
                in = ctx.getAssets().open(RoleCatalog.ASSET);
                RoleCatalog parsed = RoleCatalog.parseOrEmpty(RoleCatalog.readAll(in), RoleCatalog.ASSET);
                RoleCatalog.setActive(parsed);
                Log.i(TAG, "[ROLES] 角色目录已加载: " + parsed.roles().size() + " 个角色 " + parsed.roles());
                return parsed;
            } catch (Throwable t) {
                Log.w(TAG, "[ROLES] 角色目录读取失败: " + t.getMessage());
                return c;
            } finally {
                try {
                    if (in != null) in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static AndroidDeviceSnapshot snapshot(Context ctx) {
        AndroidDeviceSnapshot s = sSnap;
        if (s != null) return s;
        synchronized (RoleHooks.class) {
            if (sSnap == null) sSnap = new AndroidDeviceSnapshot(ctx);
            return sSnap;
        }
    }

    // ------------------------------------------------------------------ 面板

    /** 新一次唤醒（主线程）：收起上一轮残留的安装提示。 */
    public static void onWake(FloatingSheetService svc) {
        try {
            if (svc != null) InstallPromptView.hideIn(svc.getSheetView());
            hideGrid(svc);   // [r2/bike-chooser]
        } catch (Throwable ignored) {
        }
    }

    /** [R-H2] 主线程：ask.reply 上屏之后。带 role_ui 就渲染选择芯片 / 安装提示；否则收起残留的安装提示。 */
    public static void onReplyShown(final FloatingSheetService svc, Invocation inv) {
        try {
            if (svc == null) return;
            FloatingSheetView sheet = svc.getSheetView();
            InstallPromptView.hideIn(sheet);
            hideGrid(svc);   // [r2/bike-chooser] 上一轮的应用网格
            if (inv == null || inv.args == null) return;
            String query = inv.args.optString("query", "");
            if (!inv.args.has("role_ui")) {
                // [r2/bike-chooser] 大模型在同一次输出里给的 app_choice（编译通道 args.app_choice / 文字尾巴已被 L2 剥成字段）
                showLlmChoice(svc, inv.args.optJSONObject("app_choice"), query);
                return;
            }
            JSONObject ui = inv.args.optJSONObject("role_ui");
            if (ui == null) return;
            if ("install".equals(ui.optString("kind"))) {
                showInstall(svc, ui.optString("label"), ui.optString("pkg"));
            } else if ("chooser".equals(ui.optString("kind"))) {
                showChooser(svc, ui, query);
            }
        } catch (Throwable t) {
            Log.w(TAG, "[ROLES] 面板渲染异常: " + t.getMessage());
        }
    }

    /**
     * [r2/bike-chooser] 选择芯片改成 2×2 应用网格（用户 2026-10-30 定的排版）：每格 图标 + 名称 + 「↗ 打开 / ↓ 下载」，
     * 下面一行蓝色「打开应用商店查看更多」。本地角色（source 缺省）沿用 R5 语义：点已安装 = 记成显式默认再执行，点未安装 = 只走下载链；
     * 大模型给的（source=llm）只打开 / 下载，不写默认。
     */
    static void showChooser(final FloatingSheetService svc, final JSONObject ui, final String query) {
        final JSONArray opts = ui.optJSONArray("options");
        if (opts == null || opts.length() == 0) return;
        final AppGridModel model = AppGridModel.from(ui);
        if (model.isEmpty()) return;
        ChoiceGridView v = ensureGrid(svc);
        if (v == null) return;
        final boolean llm = "llm".equals(ui.optString("source"));
        abstract class GridL implements ChoiceGridView.Listener, ChoiceGridView.DismissListener {}
        v.setListener(new GridL() {
            @Override
            public void onDismiss() {
                stopListening();
                hideGrid(svc);
                Log.i(TAG, "[ROLES] 应用网格「不用了」");
                java.util.List<String> ps = new java.util.ArrayList<String>();
                for (AppGridModel.Cell c : model.cells) if (!c.installed && c.pkg != null) ps.add(c.pkg);
                com.onesay.assistant.v2.jobs.InstallWaitHooks.cancelFor(svc, ps);   // ★ backlog #3：关卡同时取消这张卡的等装登记
            }

            @Override
            public void onCell(AppGridModel.Cell cell) {
                stopListening();
                hideGrid(svc);
                Log.i(TAG, "[ROLES] 应用格子点击 " + cell + " src=" + (llm ? "llm" : "role:" + ui.optString("role")));
                // ★ install-wait：点「下载」= 登记等待任务（装好后接着做原任务）
                if (!(cell.installed && snapshot(svc).isInstalled(cell.pkg)) && query != null && query.trim().length() > 0) {
                    java.util.List<String>[] c = com.onesay.assistant.v2.jobs.InstallWaitHooks.candidates(model, cell);
                    com.onesay.assistant.v2.jobs.InstallWaitHooks.register(svc, query, query, "已选 " + cell.label + "，已打开商店详情页", c[0], c[1]);
                }
                if (llm) {
                    openPlain(svc, cell, query);
                } else {
                    choose(svc, ui, opts.optJSONObject(cell.optionIndex), query);
                }
            }

            @Override
            public void onMore(String storeQuery) {
                stopListening();
                Log.i(TAG, "[ROLES] 打开应用商店查看更多 q=" + storeQuery);
                if (openStoreSearch(svc, storeQuery)) yieldPanel(svc, "store-search");
            }
        });
        boolean animate = true;
        try {
            animate = com.onesay.assistant.v2.ui.wake.WakeUiSettings.animatorsEnabled(svc);
        } catch (Throwable ignored) {
        }
        v.show(model, gridLight(svc), iconSource(svc), animate);
        Log.i(TAG, "[ROLES] 应用网格 role=" + ui.optString("role") + " mode=" + ui.optString("mode") + " " + model
                + " light=" + gridLight(svc));
    }

    /** [r2/bike-chooser] 大模型 app_choice → 按本机安装情况补 installed → 同一个网格。没有合法候选就什么都不画。 */
    static void showLlmChoice(FloatingSheetService svc, JSONObject choice, String query) {
        if (svc == null || choice == null) return;
        AppChoiceSpec spec = AppChoiceSpec.parse(choice, query);
        if (spec == null) {
            Log.i(TAG, "[ROLES] 大模型 app_choice 无合法候选，不画网格");
            return;
        }
        Log.i(TAG, "[ROLES] 大模型 app_choice " + spec);
        showChooser(svc, spec.toRoleUi(snapshot(svc)), query);
    }

    /**
     * [r2/bike-chooser ⑤] AgentLoop 收工时会不会画应用网格（与 {@link #onAgentAnswer} 同一判定）。新界面下任务结论默认只进岛（C1），
     * 网格画在卡片里 → 为 true 时 S1Hooks.onTaskOutcome(…, needsCard=true) 让收工挂回卡片，否则格子永远看不见。
     */
    public static boolean agentGridWanted(Context ctx, String query, JSONObject appChoice) {
        try {
            return appChoice != null && ctx != null && S2Settings.flags(ctx).habit && AppChoiceSpec.parse(appChoice, query) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** [r2/bike-chooser] AgentLoop 收工（FloatingSheetService 一行调用）：终稿尾巴里剥出来的 app_choice。 */
    public static void onAgentAnswer(FloatingSheetService svc, String query, JSONObject appChoice) {
        try {
            hideGrid(svc);
            if (appChoice != null && S2Settings.flags(svc).habit) showLlmChoice(svc, appChoice, query);
        } catch (Throwable t) {
            Log.w(TAG, "[ROLES] Agent 应用网格异常: " + t.getMessage());
        }
    }

    /** 大模型候选：已安装 → 直接打开（不写默认）；未安装 → 商店详情页（没有商店 → 浏览器下载页）。 */
    private static void openPlain(final FloatingSheetService svc, final AppGridModel.Cell cell, String query) {
        if (cell.installed && snapshot(svc).isInstalled(cell.pkg)) {
            if (openInstalled(svc, cell.pkg, cell.openUri)) yieldPanel(svc, cell.pkg);
            return;
        }
        FloatingSheetView sheet = svc.getSheetView();
        if (sheet != null) sheet.showAskReply(query.length() > 0 ? query : "下载应用", "去下载" + cell.label + "，装好后我接着做。");
        openInstallOrWeb(svc, cell.pkg, cell.label);
    }

    /** ★ install-wait：商店无网络时岛上「重试」= 再走一遍商店链；「换商店」= 浏览器下载页（官网）。 */
    public static void reopenInstall(FloatingSheetService svc, String pkg, String label, boolean web) {
        try {
            if (web) {
                String url = StoreLinks.webDownloadUrl(label, pkg);
                Log.i(TAG, "[ROLES] 换商店 → 浏览器下载页 " + url);
                startView(svc, url, null);
            } else {
                openInstallOrWeb(svc, pkg, label);
            }
        } catch (Throwable t) {
            Log.w(TAG, "[ROLES] reopenInstall: " + t.getMessage());
        }
    }

    /** 商店详情页链（{@link #openInstall}）；连 market: 都没有 → 浏览器下载页。 */
    static void openInstallOrWeb(final FloatingSheetService svc, final String pkg, final String label) {
        openInstall(svc, svc, pkg, label, new InstallDone() {
            @Override
            public void done(InstallLinkPlanner.Decision d) {
                if (d == null || d.link == null || d.link.kind == InstallLinkPlanner.Kind.NONE) {
                    String url = StoreLinks.webDownloadUrl(label, pkg);
                    Log.i(TAG, "[ROLES] 没有可用商店 → 浏览器下载页 " + url);
                    if (startView(svc, url, null)) yieldPanel(svc, pkg);
                }
            }
        });
    }

    /** 已安装：open_uri（限定该包）→ 主界面。 */
    static boolean openInstalled(Context c, String p, String openUri) {
        for (StoreLinks.Spec sp : StoreLinks.openApp(p, openUri)) {
            if (sp.uri == null) {
                if (launchInstalled(c, p)) {
                    Log.i(TAG, "[ROLES] 打开 " + p + " 主界面");
                    return true;
                }
            } else if (startView(c, sp.uri, sp.pkg)) {
                Log.i(TAG, "[ROLES] 打开 " + p + " 深链 " + sp.uri);
                return true;
            }
        }
        return false;
    }

    /** 「打开应用商店查看更多」：三星商店搜索 → market://search → 浏览器搜索页（第一个能解析的）。 */
    static boolean openStoreSearch(Context ctx, String query) {
        for (StoreLinks.Spec sp : StoreLinks.storeSearch(query)) {
            if (startView(ctx, sp.uri, sp.pkg)) {
                Log.i(TAG, "[ROLES] 查看更多 → " + sp);
                return true;
            }
        }
        Log.w(TAG, "[ROLES] 查看更多：商店和浏览器都打不开 q=" + query);
        return false;
    }

    /** VIEW 一个 uri（可限定包）；先 resolveActivity，解析不到返回 false。 */
    static boolean startView(Context ctx, String uri, String pkg) {
        if (ctx == null || uri == null) return false;
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            if (pkg != null) it.setPackage(pkg);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (it.resolveActivity(ctx.getPackageManager()) == null) return false;
            ctx.startActivity(it);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "[ROLES] 打开失败 " + uri + ": " + t.getMessage());
            return false;
        }
    }

    /** 网格主题：debug 强制 → 控制台「外观」（prefs bixby_console / theme）。 */
    static boolean gridLight(Context ctx) {
        String f = ChoiceGridView.sForceTheme;
        if (f != null) return "light".equals(f);
        try {
            return com.onesay.assistant.v2.ui.console.ConsoleTheme.MODE_LIGHT.equals(
                    com.onesay.assistant.v2.ui.console.Console.prefs(ctx).getString(com.onesay.assistant.v2.ui.console.Console.KEY_THEME,
                            com.onesay.assistant.v2.ui.console.ConsoleTheme.MODE_DARK));
        } catch (Throwable t) {
            return false;
        }
    }

    private static ChoiceGridView.IconSource iconSource(final Context ctx) {
        return new ChoiceGridView.IconSource() {
            @Override
            public android.graphics.drawable.Drawable icon(String pkg) {
                try {
                    return ctx.getPackageManager().getApplicationIcon(pkg);
                } catch (Throwable t) {
                    return null;
                }
            }
        };
    }

    /**
     * 点<b>已安装</b>的芯片 = 记成显式默认（「总是用这个」），然后按 mode 执行 / 只设默认；
     * 点<b>未安装</b>的芯片（R5）= 直接走「未安装 X，点此安装」同一条安装链（2026-10-10 起：国内 → 三星商店；国外 → Play / 代理 App / 退三星商店），<b>不</b>写默认、不执行。
     * 是否已安装按点击时重新推导（期间装好了就按已安装处理）。
     */
    static void choose(final FloatingSheetService svc, final JSONObject ui, final JSONObject opt, final String query) {
        if (opt == null) return;
        final String role = ui.optString("role");
        final String id = opt.optString("id");
        final String mode = ui.optString("mode", "apply");
        final String dest = ui.has("dest") ? ui.optString("dest") : null;
        com.onesay.assistant.v2.util.Threads.start("RoleChoose", new Runnable() {
            @Override
            public void run() {
                try {
                    final S2Engine e = S2Hooks.engine(svc);
                    if (e.choiceAction(role, id) == RoleResolver.ChoiceAction.INSTALL_ONLY) {
                        final String pkg = opt.optString("pkg");
                        final String label = opt.optString("label");
                        Log.i(TAG, "[ROLES] 选择 role=" + role + " app=" + id + " 未安装 → 只引导安装，不设默认");
                        MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                FloatingSheetView sheet = svc.getSheetView();
                                if (sheet != null) {
                                    sheet.showAskReply(query.length() > 0 ? query : "默认应用",
                                            "去安装" + label + "（没有设成默认），装好后再说一次就能选它。");
                                }
                                Log.i(TAG, "[ROLES] 安装引导点击(格子) pkg=" + pkg);
                                openInstallOrWeb(svc, pkg, label);
                            }
                        });
                        return;
                    }
                    JSONObject a = new JSONObject();
                    a.put("group", role);
                    a.put("app_id", id);
                    final S2Engine.Exec x = e.execute(S2Capabilities.SIGN_HABIT_SET, a, S2Clock.now());
                    Log.i(TAG, "[ROLES] 选择 role=" + role + " app=" + id + " → 显式默认 ok=" + x.ok + " verified=" + x.verified);
                    final S2Engine.Result r = "set".equals(mode) ? null : e.applyChoice(role, id, dest);
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            FloatingSheetView sheet = svc.getSheetView();
                            if (sheet == null) return;
                            if (r == null) {
                                sheet.showAskReply(query.length() > 0 ? query : "默认应用", x.message);
                                if (!snapshot(svc).isInstalled(opt.optString("pkg"))) showInstall(svc, opt.optString("label"), opt.optString("pkg"));
                                return;
                            }
                            if (r.isLocalReply()) {
                                sheet.showAskReply(query.length() > 0 ? query : "默认应用", r.reply);
                                if (r.ui != null && "install".equals(r.ui.optString("kind"))) showInstall(svc, r.ui.optString("label"), r.ui.optString("pkg"));
                                return;
                            }
                            // [r2/bike-chooser] 种子声明了直达深链（如支付宝扫一扫）且是「打开」类交接 → 先试深链，失败照常交接
                            String ou = opt.optString("open_uri", "");
                            if (ou.length() > 0 && "app.launch".equals(r.sign)
                                    && startView(svc, ou, opt.optString("pkg"))) {
                                Log.i(TAG, "[ROLES] 交接改走深链 " + ou);
                                yieldPanel(svc, opt.optString("pkg"));
                                return;
                            }
                            Capability cap = CapabilityRegistry.getInstance().describe(r.sign);
                            if (cap == null) cap = S2Hooks.describe(r.sign);
                            if (cap == null) {
                                Log.w(TAG, "[ROLES] 交接能力不存在: " + r.sign);
                                return;
                            }
                            Log.i(TAG, "[ROLES] 交接 " + r);
                            svc.screenRefExecute(cap, r.args, query.length() > 0 ? query : opt.optString("label"));
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "[ROLES] 选择处理异常: " + t.getMessage());
                }
            }
        });
    }

    /** [R-H3] 主线程：执行失败。没找到应用且能力带了合法包名、该包确实未安装 → 通用安装提示。 */
    public static void onExecFailed(FloatingSheetService svc, Capability cap, JSONObject args, ExecutionResult r) {
        try {
            if (svc == null || r == null || r.code != ErrorCode.NOT_FOUND_APP || args == null) return;
            String pkg = args.optString("pkg", "");
            String label = args.optString("app", "");
            // ★ 11-04 I（白盒审计）：普通 app.launch 只带 app 名、没 pkg → 先按种子 / 常见 App 表反查包名，别空手退出
            if (!RoleCatalog.PKG.matcher(pkg).matches() && label.length() > 0) {
                String p2 = KnownApps.pkgOf(label);
                if (p2 != null) pkg = p2;
            }
            if (!RoleCatalog.PKG.matcher(pkg).matches() || snapshot(svc).isInstalled(pkg)) return;
            RoleCatalog.Seed s = catalog(svc).seedByPkg(pkg);
            if (s != null) label = s.label;
            // ★ audit-J 2：路由层拦截带了续办句（原话）→ 点安装登记 InstallWait，装好后接着办
            showInstall(svc, label.length() > 0 ? label : pkg, pkg, args.optString(com.onesay.assistant.v2.router.MissingAppIntercept.ARG_RESUME, ""));
        } catch (Throwable t) {
            Log.w(TAG, "[ROLES] 安装提示异常: " + t.getMessage());
        }
    }

    /** [R-H4] 主线程：执行成功（非跳转类）。设了一个本机没装的默认 → 安装提示。 */
    public static void onExecuted(FloatingSheetService svc, Capability cap, JSONObject args, ExecutionResult r) {
        try {
            if (svc == null || cap == null || r == null || !r.ok || args == null) return;
            if (!S2Capabilities.SIGN_HABIT_SET.equals(cap.sign)) return;
            RoleCatalog.Role g = catalog(svc).role(args.optString("group"));
            if (g == null) return;
            RoleCandidate c = CandidateDeriver.find(CandidateDeriver.derive(g, snapshot(svc)), args.optString("app_id"));
            if (c != null && !c.installed) showInstall(svc, c.label, c.pkg);
        } catch (Throwable t) {
            Log.w(TAG, "[ROLES] 设默认后安装提示异常: " + t.getMessage());
        }
    }

    /** 通用：面板底部「未安装 X，点此安装」。 */
    public static void showInstall(final FloatingSheetService svc, String label, String pkg) {
        showInstall(svc, label, pkg, null);
    }

    /** @param resume 非空 = 点安装时登记 InstallWait（续办句 = 它，候选包 = pkg） */
    public static void showInstall(final FloatingSheetService svc, String label, String pkg, final String resume) {
        if (svc == null || !RoleCatalog.PKG.matcher(pkg == null ? "" : pkg).matches()) return;
        InstallPromptView v = InstallPromptView.ensureIn(svc.getSheetView());
        if (v == null) return;
        abstract class PromptL implements InstallPromptView.Listener, InstallPromptView.DismissListener {}
        v.show(label, pkg, new PromptL() {
            @Override
            public void onDismiss(String p, String l) {   // ★ backlog #3：「不用了」= 收起 + 取消等装登记
                Log.i(TAG, "[ROLES] 安装提示「不用了」pkg=" + p);
                com.onesay.assistant.v2.jobs.InstallWaitHooks.cancelFor(svc, java.util.Collections.singletonList(p));
            }

            @Override
            public void onInstallClicked(String p, String l) {
                Log.i(TAG, "[ROLES] 安装引导点击 pkg=" + p);
                InstallPromptView.hideIn(svc.getSheetView());
                if (resume != null && resume.trim().length() > 0) {
                    com.onesay.assistant.v2.jobs.InstallWaitHooks.register(svc, resume, resume, "已打开 " + l + " 的商店页",
                            java.util.Collections.singletonList(p), java.util.Collections.singletonList(l));
                }
                openInstall(svc, svc, p, l, null);
            }
        });
        Log.i(TAG, "[ROLES] 显示安装提示 pkg=" + pkg + " region=" + InstallLinkPlanner.regionOf(catalog(svc), pkg).id()
                + " 预选链接=" + InstallLinkPlanner.preview(pkg, InstallLinkPlanner.regionOf(catalog(svc), pkg), snapshot(svc)));
    }

    /**
     * ★ backlog #3：安装卡在屏时用户打字 / 说「不用了」「算了」「不装了」（路由线程调，RolesPlugin TAKEOVER 最先）。
     * 回主线程真的去关在屏的提示条 / 网格（最多等 400 ms）：真关了 → 取消等装登记、本地回一句；卡其实不在屏 → null（照旧路由）。
     */
    public static Invocation routeDismiss(Context ctx, String query) {
        try {
            if (!InstallCardState.wantsDismiss(query)) return null;
            final FloatingSheetService svc = FloatingSheetService.getInstance();
            if (svc == null) {
                InstallCardState.gone(InstallCardState.KIND_PROMPT);
                InstallCardState.gone(InstallCardState.KIND_GRID);
                return null;
            }
            final java.util.List<String> pkgs = InstallCardState.pkgs();
            final boolean[] closed = {false};
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            MAIN.post(new Runnable() {
                @Override public void run() {
                    try {
                        FloatingSheetView sheet = svc.getSheetView();
                        if (InstallPromptView.dismissIn(sheet)) closed[0] = true;
                        View g = sheet == null ? null : sheet.findViewWithTag(ChoiceGridView.TAG_IN_SHEET);
                        if (g instanceof ChoiceGridView && g.getVisibility() == View.VISIBLE) {
                            stopListening();
                            ((ChoiceGridView) g).hide();
                            closed[0] = true;
                        }
                        if (!closed[0]) {   // 状态过期（面板已收起等）：清掉
                            InstallCardState.gone(InstallCardState.KIND_PROMPT);
                            InstallCardState.gone(InstallCardState.KIND_GRID);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        latch.countDown();
                    }
                }
            });
            if (!latch.await(400, java.util.concurrent.TimeUnit.MILLISECONDS) || !closed[0]) return null;
            com.onesay.assistant.v2.jobs.InstallWaitHooks.cancelFor(svc, pkgs);
            Log.i(TAG, "[ROLES] 口令关安装卡 pkgs=" + pkgs);
            JSONObject a = new JSONObject();
            a.put("text", InstallCardState.REPLY);
            a.put("query", query.trim());
            return new Invocation("ask.reply", a, 1.0, Invocation.RouteSource.L0_RULE, query);
        } catch (Throwable t) {
            Log.w(TAG, "[ROLES] routeDismiss: " + t.getMessage());
            return null;
        }
    }

    /** 安装链走完（主线程回调；调试驱动用来打日志）。d == null = 已安装直接打开 / 包名不合法。 */
    public interface InstallDone {
        void done(InstallLinkPlanner.Decision d);
    }

    /**
     * 点击安装（主线程调用，2026-10-10 去应用宝、按国内 / 国外分流）：<b>每次重新检查</b>安装状态（已装好 → 直接打开它），
     * 否则后台线程按 {@link InstallLinkPlanner#decide} 决策（国外 App 要测连通性，可能自动启动代理 App并复测），
     * 再回主线程打开商店 / 说明一句 / 给「打开代理 App（会断开当前 VPN）」芯片。打开商店是 UI 动作，不弹确认。
     * svc 可为 null（调试驱动）：那时不上屏，只打日志；已有 VPN 的芯片分支也只记日志不打开任何东西。
     */
    public static void openInstall(final Context ctx, final FloatingSheetService svc, final String pkg, final String label, final InstallDone cb) {
        if (ctx == null || !InstallLinkPlanner.validPkg(pkg)) {
            if (cb != null) cb.done(null);
            return;
        }
        final AndroidDeviceSnapshot snap = snapshot(ctx);
        if (snap.isInstalled(pkg)) {
            if (InstallLinkPlanner.yieldsPanel(InstallLinkPlanner.Step.OPEN, launchInstalled(ctx, pkg))) yieldPanel(svc, pkg);
            if (cb != null) cb.done(null);
            return;
        }
        final InstallLinkPlanner.Region region = InstallLinkPlanner.regionOf(catalog(ctx), pkg);
        if (region == InstallLinkPlanner.Region.INTL) note(svc, label, "正在检查 Play 商店能不能连上…");
        com.onesay.assistant.v2.util.Threads.start("RoleInstall", new Runnable() {
            @Override
            public void run() {
                InstallLinkPlanner.Decision d = null;
                try {
                    d = InstallLinkPlanner.decide(pkg, region, new AndroidInstallEnv(ctx, snap));
                } catch (Throwable t) {
                    Log.w(TAG, "[ROLES] 安装链决策异常: " + t.getMessage());
                }
                final InstallLinkPlanner.Decision fd = d;
                Log.i(TAG, "[ROLES] 安装链 pkg=" + pkg + " region=" + region.id() + " → " + fd);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        applyInstall(ctx, svc, pkg, label, fd);
                        if (cb != null) cb.done(fd);
                    }
                });
            }
        });
    }

    /** 主线程：执行一次决策。 */
    static void applyInstall(final Context ctx, final FloatingSheetService svc, final String pkg, final String label,
                             final InstallLinkPlanner.Decision d) {
        if (d == null) return;
        if (d.step == InstallLinkPlanner.Step.OPEN) {
            note(svc, label, d.note);
            if (InstallLinkPlanner.yieldsPanel(d.step, startStore(ctx, pkg, d.link))) yieldPanel(svc, pkg);
            return;
        }
        // OFFER_VPN_CHIP：已有 VPN——绝不自动切，只在点芯片后才启动用户填写的代理 App
        note(svc, label, d.note);
        if (svc == null) return;
        ActionChipsView v = ensureChips(svc);
        if (v == null) return;
        List<String> chips = new ArrayList<String>();
        chips.add(InstallLinkPlanner.VPN_CHIP);
        chips.add(d.link.kind == InstallLinkPlanner.Kind.GALAXY ? InstallLinkPlanner.GALAXY_CHIP : "改用系统应用商店");
        v.setListener(new ActionChipsView.Listener() {
            @Override
            public void onChipClicked(int index) {
                hideChips(svc);
                if (index == 0) {
                    Log.i(TAG, "[ROLES] 用户点了「" + InstallLinkPlanner.VPN_CHIP + "」 pkg=" + pkg);
                    note(svc, label, "正在打开代理 App，连上后自动跳 Play…");
                    com.onesay.assistant.v2.util.Threads.start("RoleInstallVpn", new Runnable() {
                        @Override
                        public void run() {
                            InstallLinkPlanner.Decision d2 = null;
                            try {
                                d2 = InstallLinkPlanner.afterVpnChip(pkg, new AndroidInstallEnv(ctx, snapshot(ctx)));
                            } catch (Throwable t) {
                                Log.w(TAG, "[ROLES] 代理 App 复测异常: " + t.getMessage());
                            }
                            final InstallLinkPlanner.Decision fd2 = d2;
                            Log.i(TAG, "[ROLES] 安装链(芯片) pkg=" + pkg + " → " + fd2);
                            MAIN.post(new Runnable() {
                                @Override
                                public void run() {
                                    applyInstall(ctx, svc, pkg, label, fd2);
                                }
                            });
                        }
                    });
                } else if (index == 1) {
                    Log.i(TAG, "[ROLES] 用户选择不开代理 App → " + d.link);
                    if (InstallLinkPlanner.yieldsPanel(InstallLinkPlanner.Step.OPEN, startStore(ctx, pkg, d.link))) yieldPanel(svc, pkg);
                }
            }
        });
        v.showChips(chips);
    }

    private static void note(FloatingSheetService svc, String label, String text) {
        if (svc == null || text == null) return;
        try {
            FloatingSheetView sheet = svc.getSheetView();
            if (sheet != null) sheet.showAskReply("安装" + (label == null ? "" : label), text);
        } catch (Throwable ignored) {
        }
    }

    /**
     * [round7] 外部商店 / App 已拉起 → 面板让位（与 FloatingSheetService「打开应用 / 跳转界面」同口径：220 ms 后 hideSheet）。
     * svc == null（调试驱动 --es roles open）不上屏，什么都不做。
     */
    private static void yieldPanel(final FloatingSheetService svc, String pkg) {
        if (svc == null) return;
        Log.i(TAG, "[ROLES] 已拉起外部界面（安装 " + pkg + "）→ 面板让位");
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    svc.hideSheet();
                } catch (Throwable ignored) {
                }
            }
        }, InstallLinkPlanner.YIELD_DELAY_MS);
    }

    /** @return 是否真的拉起了（[round7] 用来决定面板让位）。 */
    private static boolean launchInstalled(Context ctx, String pkg) {
        try {
            Intent li = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (li != null) {
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(li);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 打开商店链接；指定商店打不开（被停用等）→ 退普通 market://（系统选）。
     * @return 是否真的拉起了某个商店界面（[round7] 用来决定面板让位）。
     */
    private static boolean startStore(Context ctx, String pkg, InstallLinkPlanner.Link link) {
        if (link == null || link.kind == InstallLinkPlanner.Kind.NONE) {
            Log.w(TAG, "[ROLES] 没有可用的应用商店 pkg=" + pkg);
            return false;
        }
        if (start(ctx, link)) return true;
        if (link.viaPkg != null || link.kind != InstallLinkPlanner.Kind.MARKET) {
            try {
                Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(InstallLinkPlanner.marketUri(pkg)));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(it);
                Log.i(TAG, "[ROLES] 退到普通 market:// pkg=" + pkg);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "[ROLES] 普通 market:// 也打不开 pkg=" + pkg + ": " + t.getMessage());
            }
        }
        return false;
    }

    private static boolean start(Context ctx, InstallLinkPlanner.Link link) {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(link.uri));
            if (link.viaPkg != null) it.setPackage(link.viaPkg);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "[ROLES] 打开安装链接失败 " + link + ": " + t.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ 控制台 / 调试

    public static boolean enabled(Context ctx) {
        return S2Settings.habitEnabled(ctx);
    }

    /** 控制台卡片 / 调试：每个角色一行「打车：滴滴出行（显式）· 本机 2/6」。 */
    public static List<String[]> summary(Context ctx) {
        List<String[]> out = new ArrayList<String[]>();
        S2Engine e = S2Hooks.engine(ctx);
        for (RoleCatalog.Role g : e.roles().roles()) {
            List<RoleCandidate> cands = e.candidates(g);
            HabitPolicy.Default d = HabitPolicy.resolve(e.habits(), g.id);
            String cur;
            if (d == null) {
                cur = "未设置（无）";
            } else {
                RoleCandidate c = CandidateDeriver.find(cands, d.appId);
                cur = (c != null ? c.label : d.appId) + (d.explicit() ? "（显式）" : "（推断 " + d.hits + "/" + d.window + "）")
                        + (c != null && !c.installed ? " · 未安装" : "");
            }
            out.add(new String[]{g.id, g.label, cur, "本机已装 " + CandidateDeriver.installed(cands).size() + "/" + cands.size()});
        }
        return out;
    }

    /** 调试：列出每个角色推导出的候选（含来源）。 */
    public static String debugCandidates(Context ctx) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return "（仅 debug 包可用）";   // r2/fixes 9：debug 驱动专用，release 里是空操作
        S2Engine e = S2Hooks.engine(ctx);
        StringBuilder sb = new StringBuilder("catalog=" + e.roles().source() + " device=" + (e.device() != null));
        for (RoleCatalog.Role g : e.roles().roles()) {
            List<RoleCandidate> cands = e.candidates(g);
            RoleResolver.Resolution res = RoleResolver.resolve(cands, null, e.habits().explicitDefault(g.id),
                    e.habits().recentUses(g.id, e.habits().resetAt(g.id), HabitPolicy.WINDOW), e.device() != null);
            sb.append("\n  ").append(g.id).append(" probe=").append(g.probe).append(" → ").append(res).append("\n    ").append(cands);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 工具

    private static void stopListening() {
        try {
            WakeService.cancelCaptureFromUi();
        } catch (Throwable ignored) {
        }
    }

    private static ActionChipsView ensureChips(FloatingSheetService svc) {
        FloatingSheetView sheet = svc.getSheetView();
        if (sheet == null) return null;
        View existing = sheet.findViewWithTag(ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof ActionChipsView) return (ActionChipsView) existing;
        ActionChipsView v = new ActionChipsView(svc);
        int idx = Math.min(2, sheet.getChildCount());
        sheet.addView(v, idx, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    /** [r2/bike-chooser] 网格插在动作芯片后面（没有芯片就在内容区后面 / 旧界面 index 2）；不改 FloatingSheetView。 */
    private static ChoiceGridView ensureGrid(FloatingSheetService svc) {
        FloatingSheetView sheet = svc == null ? null : svc.getSheetView();
        if (sheet == null) return null;
        View existing = sheet.findViewWithTag(ChoiceGridView.TAG_IN_SHEET);
        if (existing instanceof ChoiceGridView) return (ChoiceGridView) existing;
        ChoiceGridView v = new ChoiceGridView(svc);
        ActionChipsView chips = ensureChips(svc);
        int idx = chips != null && chips.getParent() == sheet ? sheet.indexOfChild(chips) + 1 : Math.min(2, sheet.getChildCount());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (4 * svc.getResources().getDisplayMetrics().density);
        lp.bottomMargin = (int) (6 * svc.getResources().getDisplayMetrics().density);
        sheet.addView(v, Math.max(0, Math.min(idx, sheet.getChildCount())), lp);
        return v;
    }

    static void hideGrid(FloatingSheetService svc) {
        FloatingSheetView sheet = svc != null ? svc.getSheetView() : null;
        if (sheet == null) return;
        View existing = sheet.findViewWithTag(ChoiceGridView.TAG_IN_SHEET);
        if (existing instanceof ChoiceGridView) ((ChoiceGridView) existing).hide();
    }

    private static void hideChips(FloatingSheetService svc) {
        FloatingSheetView sheet = svc != null ? svc.getSheetView() : null;
        if (sheet == null) return;
        View existing = sheet.findViewWithTag(ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof ActionChipsView) ((ActionChipsView) existing).hideChips();
    }
}
