package com.onesay.assistant.v2.quicknote;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.kernel.IslandHost;
import com.onesay.assistant.v2.s2.MemoryEntry;
import com.onesay.assistant.v2.s2.S2Hooks;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.vision.screenref.EntityExtractor;
import com.onesay.assistant.v2.vision.screenref.ScreenContext;
import com.onesay.assistant.v2.vision.screenref.ScreenContextCapture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/**
 * [quicknote] 记一记的 Android 侧（R10 对标小布）。日志 tag QuickNote，前缀 [QUICKNOTE]。
 *
 * <ol>
 *   <li>长按（守护 v8）：不出声、不弹面板 → 岛上「记录中」流光（震动由 WakeService 给）；</li>
 *   <li>锁屏 → 不读屏，直接说明；</li>
 *   <li>读屏文字树（uiautomator，私有目录）；文字太少且开了视觉兜底（D8，默认开）且不是敏感 App → 既有 qwen 视觉识屏；</li>
 *   <li>本机分类 v2 → 敏感（验证码 / 密码 / 卡号 / 证件）整条拒收、也不截图；</li>
 *   <li>存 quicknote.db（D3）→ 缩略图（D7，默认关）→ 码上岛（D4，复用 S1 取件码卡）→ 订单记账（D9）→ 岛上结果卡带动作（D5）。</li>
 * </ol>
 */
public final class QuickNoteHooks {

    public static final String TAG = "QuickNote";

    private QuickNoteHooks() {}

    private static volatile QuickNoteBook sBook;
    private static volatile boolean sListenerSet;
    /** 随口记（按住 ≥ 2 s）：下一句语音截给记一记的截止时刻。 */
    private static volatile long sSuikoujiUntil;
    /** ★ R11：最近一次「长按记屏幕」开始的时刻 / 是否因锁屏没读屏（按住 ≥ 2 s 松手时据此决定要不要开随口记）。 */
    private static volatile long sLongNoteAt;
    private static volatile boolean sLongNoteLocked;

    // ------------------------------------------------------------------ 存储

    public static QuickNoteBook book(Context ctx) {
        QuickNoteBook b = sBook;
        if (b != null) return b;
        synchronized (QuickNoteHooks.class) {
            if (sBook == null) {
                Context app = ctx.getApplicationContext();
                sBook = new QuickNoteBook(SqliteQuickNoteStore.get(app, SqliteQuickNoteStore.DB_PROD), TimeZone.getDefault());
                ensureMigrated(app, sBook);
            }
            return sBook;
        }
    }

    /** D3 一次性迁移：S2 库里的旧记一记行 → quicknote.db（原行保留）。 */
    static void ensureMigrated(Context app, QuickNoteBook b) {
        try {
            if (QuickNoteSettings.migrated(app)) return;
            List<MemoryEntry> old = S2Hooks.engine(app).store().list(MemoryEntry.KIND_NOTE);
            int n = QuickNoteMigration.migrate(old, b.store(), TimeZone.getDefault());
            QuickNoteSettings.setMigrated(app);
            Log.i(TAG, "[QUICKNOTE] 迁移完成：从 s2_memory.db 搬来 " + n + " 条（原行保留）");
        } catch (Throwable t) {
            Log.w(TAG, "[QUICKNOTE] 迁移失败（下次启动再试）: " + t.getMessage());
        }
    }

    /** 列表 / 回忆前：码过了兜底时长 → 标「已过期」；最近删除超过 30 天 → 彻底删（连缩略图）。 */
    public static void housekeep(Context ctx) {
        QuickNoteBook b = book(ctx);
        long now = System.currentTimeMillis();
        for (QuickNoteEntry e : b.list()) {
            if (e.codeState == QuickNoteEntry.CODE_ON_ISLAND && now > e.createdAt + QuickNoteSettings.codeTtlMs(e.codeKind)) {
                e.codeState = QuickNoteEntry.CODE_EXPIRED;
                b.store().update(e);
            }
        }
        for (QuickNoteEntry e : b.purge(now)) QuickNoteThumbs.delete(e.thumbPath);
    }

    // ------------------------------------------------------------------ 记屏幕

    /** 后台线程调用：记下当前屏幕。source = long / voice / debug。 */
    public static QuickNoteClassifier.Note captureAndSave(Context ctx, String source) {
        final Context app = ctx.getApplicationContext();
        long t0 = System.currentTimeMillis();
        ensureListener(app);
        final String islandId = "quicknote-" + t0;
        IslandHost.Channel ch = channel();
        boolean silent = "long".equals(source) && com.onesay.assistant.v2.wake.WakeService.lastLongWasSilent();
        if ("long".equals(source)) {
            sLongNoteAt = t0;
            sLongNoteLocked = keyguardLocked(app);
        }
        if (ch != null) ch.noteStarted(islandId, "记录中");
        // 面板让位：守护 v8 长按时面板根本没出来 → 不等；语音 / v7 长按 → 收面板、等它退场再读屏
        if (!silent) {
            try {
                FloatingSheetService.hide(app);
                Thread.sleep(450);
            } catch (Throwable ignored) {
            }
        }
        // 锁屏时读到的是锁屏界面 —— 锁着就不读屏，直接说明
        if (keyguardLocked(app)) {
            Log.i(TAG, "[QUICKNOTE] source=" + source + " 锁屏中 → 不读屏、不记");
            fail(app, islandId, "手机锁着（解锁后再长按）");
            return QuickNoteClassifier.Note.refused("锁屏");
        }
        ScreenContext sc = ScreenContextCapture.captureSync(app);
        boolean vlUsed = false;
        // D8：文字树太少 → 已有视觉识屏兜底（敏感 App / 敏感文字 / 被打码过的屏幕不走）
        if (tooLittleText(sc) && QuickNoteSettings.get(app, QuickNoteSettings.KEY_VL) && !QuickNoteSettings.sensitivePkg(sc.pkg)
                && sc.redacted == 0 && ScreenSensitivity.pageReason(MailDigest.isMailApp(sc.pkg), sc.texts) == null) {
            ScreenContext v = ScreenContextCapture.ensureVl(app, sc);
            if (v != sc) {
                sc = v;
                vlUsed = true;
            }
        }
        QuickNoteClassifier.Note n = QuickNoteClassifier.classify(sc.pkg, sc.texts, sc.entities, System.currentTimeMillis());
        if (n.mail != null) Log.i(TAG, "[QUICKNOTE] 邮件抽取 " + n.mail + (vlUsed ? " (vl)" : ""));
        if (!n.saved()) {
            Log.i(TAG, "[QUICKNOTE] source=" + source + " app=" + sc.pkg + " texts=" + sc.texts.size() + " → 拒收 " + n.reason
                    + (vlUsed ? " (vl)" : "") + " cost=" + (System.currentTimeMillis() - t0) + "ms");
            fail(app, islandId, n.reason == null ? "" : n.reason);
            return n;
        }
        QuickNoteBook b = book(app);
        QuickNoteEntry e = b.toEntry(n, System.currentTimeMillis(), source);
        e.appLabel = appLabel(app, e.appPkg);
        e.activity = sc.activity == null ? "" : sc.activity;
        if (vlUsed && !e.tags.contains("识图")) e.tags.add("识图");
        long id = b.store().insert(e);
        if (id <= 0) {
            fail(app, islandId, "存不进去（数据库错误）");
            return n;
        }
        afterSave(app, e, true);
        Log.i(TAG, "[QUICKNOTE] source=" + source + " app=" + sc.pkg + " texts=" + sc.texts.size() + " entities=" + sc.entities.size()
                + " → " + n.category + " saved id=" + id + " title=" + n.title + (vlUsed ? " (vl)" : "") + (silent ? " silent" : "")
                + " code=" + (e.hasCode() ? e.codeKind + "/" + e.codeState : "-") + " ledger=" + e.ledgerId
                + " cost=" + (System.currentTimeMillis() - t0) + "ms");
        showResult(app, islandId, e);
        return n;
    }

    /** 存好之后：缩略图（D7）、码上岛（D4）、订单记账（D9）。会回写条目。 */
    static void afterSave(Context app, QuickNoteEntry e, boolean screen) {
        QuickNoteBook b = book(app);
        boolean dirty = false;
        if (screen && QuickNoteSettings.get(app, QuickNoteSettings.KEY_THUMBS) && !QuickNoteSettings.sensitivePkg(e.appPkg)) {
            String p = QuickNoteThumbs.capture(app, e.id);
            if (p != null) {
                e.thumbPath = p;
                dirty = true;
            }
        }
        if (e.hasCode()) {
            IslandHost.Channel ch = channel();
            boolean on = ch != null && ch.addCode(e.codeKind, e.codeValue, codeWhere(e), e.appPkg,
                    QuickNoteSettings.codeTtlMs(e.codeKind), e.id);
            if (on || ch != null) {
                e.codeState = QuickNoteEntry.CODE_ON_ISLAND;
                dirty = true;
            }
        }
        if (e.amountFen > 0 && "订单".equals(e.category) && QuickNoteSettings.get(app, QuickNoteSettings.KEY_LEDGER)) {
            long lid = ledgerAdd(app, e);
            if (lid > 0) {
                e.ledgerId = lid;
                if (!e.tags.contains("已记账")) e.tags.add("已记账");
                dirty = true;
            }
        }
        if (dirty) {
            e.updatedAt = System.currentTimeMillis();
            b.store().update(e);
        }
        com.onesay.assistant.v2.planner.PlannerHooks.onQuickNoteSaved(app, e.id, e.codeKind, e.codeValue, codeWhere(e), e.appPkg,
                e.tags.contains("待支付") ? "" : e.title + "\n" + e.summary + "\n" + e.body);   // ★ planner：码 / 票进计划 App（★ 选座 / 待支付页不进票夹）
    }

    static String codeWhere(QuickNoteEntry e) {
        String s = e.summary;
        int i = s.indexOf('（');
        if (i > 0 && s.endsWith("）")) return s.substring(i + 1, s.length() - 1);
        return e.appLabel;
    }

    /** D9：订单 → S2 账本记一笔（分类按标题 / 来源 App 关键词，描述带「记一记」）。 */
    static long ledgerAdd(Context app, QuickNoteEntry e) {
        try {
            MemoryEntry m = new MemoryEntry();
            m.kind = MemoryEntry.KIND_LEDGER;
            m.amountFen = e.amountFen;
            String cat = com.onesay.assistant.v2.s2.LedgerParser.categoryOf(e.title + " " + e.summary + " " + e.appLabel);
            if ("其他".equals(cat) && e.tags.contains("外卖")) cat = "餐饮";
            if ("其他".equals(cat) && e.tags.contains("购物")) cat = "购物";
            m.category = cat;
            m.content = "记一记 · " + (e.appLabel.length() > 0 ? e.appLabel + " " : "") + clip(e.summary, 30);
            m.day = e.day;
            m.createdAt = e.createdAt;
            long id = S2Hooks.engine(app).store().insert(m);
            Log.i(TAG, "[QUICKNOTE] 订单记账 " + e.amountFen + " 分 cat=" + cat + " → 账本 #" + id);
            return id;
        } catch (Throwable t) {
            Log.w(TAG, "[QUICKNOTE] 记账失败: " + t.getMessage());
            return -1;
        }
    }

    static boolean tooLittleText(ScreenContext sc) {
        if (sc == null) return false;
        if (sc.needsVl) return true;
        int chars = 0;
        for (String t : sc.texts) chars += t == null ? 0 : t.length();
        return sc.texts.size() < 3 || chars < 30;
    }

    // ------------------------------------------------------------------ 随口记（D6）

    /** 语音「随口记 …」「帮我记一下 …」或按住 ≥ 2 s 后说的一句 → 存成随口记。后台线程。 */
    public static QuickNoteEntry saveVoice(Context ctx, String text, String source) {
        Context app = ctx.getApplicationContext();
        ensureListener(app);
        long now = System.currentTimeMillis();
        String islandId = "quicknote-" + now;
        List<String> one = Arrays.asList(text == null ? "" : text);
        QuickNoteClassifier.Note n = QuickNoteClassifier.classifyVoice(text, EntityExtractor.extractFromTexts(one, now, TimeZone.getDefault()));
        if (!n.saved()) {
            fail(app, islandId, n.reason);
            return null;
        }
        QuickNoteBook b = book(app);
        QuickNoteEntry e = b.toEntry(n, now, source);
        if (b.store().insert(e) <= 0) {
            fail(app, islandId, "存不进去（数据库错误）");
            return null;
        }
        afterSave(app, e, false);
        Log.i(TAG, "[QUICKNOTE] 随口记 source=" + source + " id=" + e.id + " len=" + e.summary.length() + " event=" + (e.eventAt > 0));
        showResult(app, islandId, e);
        return e;
    }

    /** ★ R11 按住 ≥ 2 s 松手怎么办（纯函数）。 */
    public enum HoldAction { SUIKOUJI, ABSORB }

    /** 窗口：这么久之内开始过「长按记屏幕」→ 这次按住属于同一次长按。 */
    static final long HOLD_SAME_PRESS_MS = 15000L;

    /**
     * ★ R11（真机 23:11–23:13，QQ 邮箱）：用户长按后没看到岛（被当成全屏藏了），就一直按着（2.1–3.5 s），
     * 松手 = 随口记 = 开收音窗 + 面板 + 芯片 → 看起来像「松手唤醒了助手」。
     * 现在：这次长按已经在记屏幕 → 松手只吸收掉（岛上照常「记录中」→ 结果），不开面板；
     * 只有锁屏（不读屏）时，按住 2 s 才走随口记（说一句就记）。随口记照旧可以用语音「随口记 …」。
     */
    public static HoldAction holdAction(long longNoteAt, boolean longNoteLocked, long now) {
        boolean samePress = longNoteAt > 0 && now - longNoteAt >= 0 && now - longNoteAt < HOLD_SAME_PRESS_MS;
        if (samePress && !longNoteLocked) return HoldAction.ABSORB;
        return HoldAction.SUIKOUJI;
    }

    /** 插件入口：返回 true = 已处理（吸收或开随口记）。 */
    public static boolean onHold(Context ctx) {
        HoldAction a = holdAction(sLongNoteAt, sLongNoteLocked, System.currentTimeMillis());
        if (a == HoldAction.ABSORB) {
            Log.i(TAG, "[QUICKNOTE] 按住 ≥ 2 s 松手：这次长按已经在记屏幕 → 不开收音窗、不弹面板（R11）");
            return true;
        }
        startHoldSuikouji(ctx);
        return true;
    }

    /** 按住 ≥ 2 s：开收音窗，下一句截给记一记（20 s 内有效）。 */
    public static void startHoldSuikouji(Context ctx) {
        sSuikoujiUntil = System.currentTimeMillis() + 20000L;
        Log.i(TAG, "[QUICKNOTE] 按住 ≥ 2 s → 随口记：开收音窗，下一句记下来");
        com.onesay.assistant.v2.wake.WakeService.requestSuikoujiListen();
    }

    /** 面板入口：随口记等待中 → 这句话是要记的内容（吃掉，不进路由）。 */
    public static boolean takeSuikouji(final Context ctx, final String command) {
        if (System.currentTimeMillis() > sSuikoujiUntil || command == null || command.trim().length() == 0) return false;
        sSuikoujiUntil = 0;
        com.onesay.assistant.v2.util.Threads.start("quicknote-hold", new Runnable() {
            @Override public void run() {
                try {
                    FloatingSheetService.hide(ctx.getApplicationContext());
                } catch (Throwable ignored) {
                }
                saveVoice(ctx, command.trim(), QuickNoteEntry.SRC_HOLD);
            }
        });
        return true;
    }

    // ------------------------------------------------------------------ 撤销 / 删除

    /** 撤销（结果卡「撤销」/ 详情页删除）：软删；码从岛上拿掉；记过的账删掉。 */
    public static void undo(Context ctx, long id) {
        Context app = ctx.getApplicationContext();
        QuickNoteBook b = book(app);
        QuickNoteEntry e = b.store().get(id);
        if (e == null) return;
        b.store().softDelete(id, System.currentTimeMillis());
        dropSideEffects(app, e);
        Log.i(TAG, "[QUICKNOTE] 撤销 / 删除 #" + id);
    }

    static void dropSideEffects(Context app, QuickNoteEntry e) {
        if (e.hasCode() && e.codeState == QuickNoteEntry.CODE_ON_ISLAND) {
            IslandHost.Channel ch = channel();
            if (ch != null) ch.removeCode(e.codeKind, e.codeValue);
        }
        if (e.ledgerId > 0) {
            try {
                S2Hooks.engine(app).store().delete(new long[]{e.ledgerId});
                e.ledgerId = 0;
                e.tags.remove("已记账");
                book(app).store().update(e);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 标记码「已取」（详情页按钮 / 岛上关掉）。 */
    public static void markTaken(Context ctx, long id) {
        QuickNoteBook b = book(ctx);
        QuickNoteEntry e = b.store().get(id);
        if (e == null || !e.hasCode()) return;
        if (e.codeState == QuickNoteEntry.CODE_ON_ISLAND) {
            IslandHost.Channel ch = channel();
            if (ch != null) ch.removeCode(e.codeKind, e.codeValue);
        }
        e.codeState = QuickNoteEntry.CODE_TAKEN;
        e.updatedAt = System.currentTimeMillis();
        b.store().update(e);
        com.onesay.assistant.v2.planner.PlannerHooks.onPickupTaken(ctx, "bixbyui:qn:" + id);   // ★ planner
    }

    /** S2「清空记忆」联动（D10 / 方案 4.9）：记一记库一起清，缩略图一起删。 */
    public static int clearAll(Context ctx) {
        QuickNoteBook b = book(ctx);
        for (QuickNoteEntry e : b.list()) QuickNoteThumbs.delete(e.thumbPath);
        for (QuickNoteEntry e : b.deletedList()) QuickNoteThumbs.delete(e.thumbPath);
        return b.store().clearAll();
    }

    // ------------------------------------------------------------------ 岛 + 结果

    static IslandHost.Channel channel() {
        try {
            IslandHost.Channel ch = FeatureKernel.get().island("quicknote");
            return ch.ready() ? ch : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 登记岛回调（结果卡动作 / 打开详情 / 码被关掉）。岛还没挂载时下次再登记。 */
    static void ensureListener(final Context app) {
        IslandHost.Channel ch = channel();   // 每次都登记（岛控制器可能随面板服务重建）
        if (ch == null) return;
        ch.setNoteListener(new IslandHost.NoteListener() {
            @Override public void onNoteAction(final String islandId, final int index) {
                final long id = pendingId(islandId);
                com.onesay.assistant.v2.util.Threads.start("quicknote-action", new Runnable() {
                    @Override public void run() {
                        QuickNoteEntry e = book(app).store().get(id);
                        if (e == null) return;
                        List<QuickNoteActions.Action> acts = QuickNoteActions.card(e, TimeZone.getDefault());
                        if (index < 0 || index >= acts.size()) return;
                        String msg = QuickNoteActionRunner.run(app, e, acts.get(index));
                        Log.i(TAG, "[QUICKNOTE] 结果卡动作 " + acts.get(index) + " → " + msg);
                        toast(app, msg);
                    }
                });
            }

            @Override public void onNoteOpen(String islandId) {
                openDetail(app, pendingId(islandId));
            }

            @Override public void onCodeGone(final long noteId, boolean expired) {
                com.onesay.assistant.v2.util.Threads.start("quicknote-code", new Runnable() {
                    @Override public void run() {
                        QuickNoteEntry e = book(app).store().get(noteId);
                        if (e == null) return;
                        e.codeState = QuickNoteEntry.CODE_TAKEN;
                        e.updatedAt = System.currentTimeMillis();
                        book(app).store().update(e);
                        Log.i(TAG, "[QUICKNOTE] 码胶囊被关掉 → #" + noteId + " 已取");
                    }
                });
            }
        });
        sListenerSet = true;
    }

    private static final java.util.Map<String, Long> sIslandIds = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Long>() {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> e) {
            return size() > 20;
        }
    });

    static long pendingId(String islandId) {
        Long v = islandId == null ? null : sIslandIds.get(islandId);
        return v == null ? -1 : v;
    }

    /** 岛上结果卡（D5：6 s，最多 3 个动作）；岛没挂载 → Toast 兜底。 */
    static void showResult(Context app, String islandId, QuickNoteEntry e) {
        sIslandIds.put(islandId, e.id);
        List<QuickNoteActions.Action> acts = QuickNoteActions.card(e, TimeZone.getDefault());
        String meta = "记一记 · " + e.category + (e.appLabel.length() > 0 ? " · 来自 " + e.appLabel : "");
        String detail = e.summary.equals(e.title) ? QuickNoteBook.clip(e.body.replace('\n', ' '), 80) : e.summary;
        if ("邮件".equals(e.category) && e.eventAt > 0) {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(e.tags.contains("全天") ? "M月d日" : "M月d日 HH:mm", java.util.Locale.CHINA);
            String k = QuickNoteActions.mailKind(e) == MailDigest.Kind.MEETING ? "会议 " : QuickNoteActions.mailKind(e) == MailDigest.Kind.DEADLINE ? "截止 " : "";
            detail = k + f.format(new java.util.Date(e.eventAt)) + " · " + detail;
        }
        if (e.hasCode()) detail = "已放到灵动岛上，取完点 ✕ 关掉" + (detail.length() > 0 ? " · " + detail : "");
        if (e.ledgerId > 0) detail = "已记账 ¥" + yuan(e.amountFen) + " · " + detail;
        IslandHost.Channel ch = channel();
        if (ch != null) ch.noteResult(islandId, true, e.title, detail, meta, Arrays.asList(QuickNoteActions.labels(acts)));
        else toast(app, "已记一记：" + e.title);
    }

    static void fail(Context app, String islandId, String reason) {
        String msg = "没有记：" + (reason == null ? "" : reason);
        IslandHost.Channel ch = channel();
        if (ch != null) ch.noteResult(islandId, false, msg, null, "记一记 · 没记", new ArrayList<String>());
        else toast(app, msg);
    }

    static String yuan(long fen) {
        return fen % 100 == 0 ? String.valueOf(fen / 100) : String.format(java.util.Locale.ROOT, "%.2f", fen / 100.0);
    }

    static void toast(final Context app, final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    Toast.makeText(app, msg, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 打开控制台记一记详情（id ≤ 0 = 列表）。 */
    public static void openDetail(Context app, long id) {
        try {
            Intent it = new Intent(app, com.onesay.assistant.v2.ui.MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            it.putExtra("page", "quicknote");
            if (id > 0) it.putExtra("id", id);
            app.startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "[QUICKNOTE] 打开详情失败: " + t.getMessage());
        }
    }

    static String appLabel(Context app, String pkg) {
        if (pkg == null || pkg.length() == 0) return "";
        try {
            PackageManager pm = app.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            return l == null ? "" : l.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    static boolean keyguardLocked(Context app) {
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            return false;
        }
    }

    static String clip(String s, int n) {
        return QuickNoteBook.clip(s, n);
    }
}
