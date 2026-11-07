package com.onesay.assistant.v2.router;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.router.cache.SkillCache;
import com.onesay.assistant.v2.router.cache.SkillEntry;
import com.onesay.assistant.v2.router.cache.SqliteSkillStore;

import org.json.JSONObject;

import java.io.File;

/**
 * L1 技能缓存层（重写为模板化技能缓存的 Android 门面）。
 *
 * <p>策略全部在纯 Java 的 {@link SkillCache}（JVM 单测覆盖），本类只负责：
 * <ul>
 *   <li>接上 SQLite 存储（生产库 {@link SqliteSkillStore#DB_PROD}，自测库 {@link SqliteSkillStore#DB_SELFTEST}）；</li>
 *   <li>接上能力表（{@link RegistryOracle}：能力指纹、风险、Schema 校验）；</li>
 *   <li>一次性迁移旧库 {@code skills_v2.db}（清洗可疑条目后以 candidate 导入，随后删除旧库）；</li>
 *   <li>保留旧 API（{@link #route}、{@link #recordSkill}、{@link #hasSkill}、{@link #clearForTesting}…），
 *       供设备端自测与 adb 探针继续编译运行。</li>
 * </ul>
 *
 * <h3>与旧版的行为差异（都是有意的）</h3>
 * <ol>
 *   <li>写入点从"LLM 返回后、执行前"移到"执行成功后"（{@link UnifiedRouter#reportExecution}）。</li>
 *   <li>查询按分诊标签分组：ASK 条目不再满足 DO 查询，反之亦然。</li>
 *   <li>旧 API {@link #recordSkill} / {@link #clearForTesting} 只作用于**自测库**，
 *       自测再也不会清空用户真实缓存；自测库只在最近 10 分钟内调用过这些 API 时参与查询。</li>
 * </ol>
 */
public class L1SkillRouter implements IRouter {

    private static final String TAG = "L1SkillRouter";

    /** 缓存语义纪元：规范化管线 / 提示词 / 能力语义有全局变化时手动递增 —— 递增即整库失效。 */
    public static final String CACHE_EPOCH = "2026-10-07.1";

    /** 兼容旧常量（自测代码引用）。 */
    public static final long TTL_PERMANENT = 0L;
    public static final long TTL_ASK_KNOWLEDGE = SkillCache.TTL_ASK;

    private static final String LEGACY_DB = "skills_v2.db";
    private static final String PREF = "skill_cache_meta";
    private static final String PREF_MIGRATED = "migrated_v2_to_v3";

    /** 自测窗口：最近一次调用自测 API 后 10 分钟内，查询会额外看一眼自测库。 */
    private static final long SELFTEST_WINDOW_MS = 10L * 60 * 1000;
    private static volatile long sSelfTestUntil = 0L;

    private final Context mContext;
    private final SkillCache mCache;
    private final SkillCache mSelfTest;
    private final SqliteSkillStore mProdStore;
    private volatile boolean mMigrated;

    public L1SkillRouter(Context context) {
        this.mContext = context.getApplicationContext();
        RegistryOracle oracle = new RegistryOracle();
        this.mProdStore = SqliteSkillStore.get(mContext, SqliteSkillStore.DB_PROD);
        this.mCache = new SkillCache(mProdStore, CACHE_EPOCH, oracle, null);
        this.mSelfTest = new SkillCache(SqliteSkillStore.get(mContext, SqliteSkillStore.DB_SELFTEST),
                CACHE_EPOCH, oracle, null);
    }

    /** 策略核心（UnifiedRouter 用）。 */
    public SkillCache cache() {
        ensureMigrated();
        return mCache;
    }

    // =====================================================================
    // 新 API（UnifiedRouter 使用）
    // =====================================================================

    /** 按分诊标签查询（计命中、续期）。 */
    public SkillCache.Hit lookup(String query, String label) {
        ensureMigrated();
        try {
            SkillCache.Hit h = mCache.lookup(query, label);
            if (h != null) {
                Log.i(TAG, "[L1] HIT key=" + h.key + " grp=" + h.grp + " -> " + h.sign + " " + com.onesay.assistant.v2.util.LogText.args(h.args)
                        + (h.generalized ? "（槽位模板代入）" : ""));
            }
            return h;
        } catch (Throwable t) {
            Log.w(TAG, "[L1] lookup 异常: " + t.getMessage());
            return null;
        }
    }

    /** 只读探测（不计命中）：分诊信号用。 */
    public boolean peek(String query, String label) {
        ensureMigrated();
        try {
            return mCache.peek(query, label);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 执行成功后写入。 */
    public SkillCache.Record recordSuccess(String query, String label, String sign, JSONObject args,
                                           SkillCache.Outcome outcome, boolean contextual) {
        ensureMigrated();
        try {
            SkillCache.Record r = mCache.recordSuccess(query, label, sign, args, outcome, contextual);
            Log.i(TAG, "[L1] PUT " + r + " label=" + label + " " + sign + " " + com.onesay.assistant.v2.util.LogText.args(args) + " ← " + com.onesay.assistant.v2.util.LogText.u(query)
                    + (contextual && r != SkillCache.Record.REJ_CONTEXT && SkillCache.contextIndependentAct(query, label)
                    ? "（确定性 DO_ACT，豁免上下文闸门 D-1）" : ""));
            return r;
        } catch (Throwable t) {
            Log.w(TAG, "[L1] record 异常: " + t.getMessage());
            return SkillCache.Record.REJ_FAILED;
        }
    }

    // =====================================================================
    // 旧 API（兼容设备端自测 / adb 探针）
    // =====================================================================

    @Override
    public Invocation route(String query) {
        return route(query, null);
    }

    /**
     * 兼容查询（**不计命中、不续期**）：唤醒热路径 {@code routeOffline} 与旧自测用。
     *
     * @param kind null 视同 AGENT（DO 组）
     */
    public Invocation route(String query, IntentClassifier.Kind kind) {
        if (query == null || query.trim().length() == 0) return null;
        try {
            if (System.currentTimeMillis() < sSelfTestUntil) {
                Invocation st = selfTestLookup(query, kind);
                if (st != null) return st;
            }
            String label = (kind == IntentClassifier.Kind.ASK) ? "ASK" : "DO_ACT";
            ensureMigrated();
            SkillCache.Hit h = mCache.peekHit(query, label);
            if (h == null) return null;
            return new Invocation(h.sign, h.args, 0.95, Invocation.RouteSource.L1_SKILL, query);
        } catch (Throwable t) {
            Log.w(TAG, "查询技能缓存失败: " + t.getMessage());
            return null;
        }
    }

    /** 自测窗口内查自测库（窗口外恒为 null）。UnifiedRouter 在分诊前调用，保证旧自测用例可复现。 */
    public Invocation selfTestRoute(String query) {
        if (query == null || System.currentTimeMillis() >= sSelfTestUntil) return null;
        try {
            return selfTestLookup(query, null);
        } catch (Throwable t) {
            return null;
        }
    }

    private Invocation selfTestLookup(String query, IntentClassifier.Kind kind) {
        String[] labels = (kind == IntentClassifier.Kind.ASK)
                ? new String[]{ "ASK", "DO_ACT" } : new String[]{ "DO_ACT", "ASK" };
        for (String l : labels) {
            SkillCache.Hit h = mSelfTest.peekHit(query, l);
            if (h != null) {
                Log.i(TAG, "[L1] 自测库命中: " + com.onesay.assistant.v2.util.LogText.u(query) + " -> " + h.sign);
                return new Invocation(h.sign, h.args, 0.95, Invocation.RouteSource.L1_SKILL, query);
            }
        }
        return null;
    }

    public boolean hasSkill(String query) {
        return route(query) != null || route(query, IntentClassifier.Kind.ASK) != null;
    }

    /** 生产库条目数 + 自测库条目数（自测断言"写进去了"用）。 */
    public int getSkillCount() {
        ensureMigrated();
        return mProdStore.count() + mSelfTest.store().count();
    }

    public int getTotalHits() {
        return mProdStore.totalHits();
    }

    /** 兼容旧调用：写入**自测库**（自测专用 API）。 */
    public void recordSkill(String query, String sign, JSONObject args, int risk) {
        recordSkill(query, IntentClassifier.Kind.AGENT, sign, args, risk, TTL_PERMANENT);
    }

    /**
     * 兼容旧调用：直接写入一条映射。★ 只写**自测库**（生产缓存只接受"执行成功后"的写入），
     * 并打开 10 分钟自测窗口。时效性、高危、计划类照旧拒写（自测 13.4 依赖这一点）。
     */
    public void recordSkill(String query, IntentClassifier.Kind kind, String sign,
                            JSONObject args, int risk, long ttl) {
        sSelfTestUntil = System.currentTimeMillis() + SELFTEST_WINDOW_MS;
        if (risk >= 2 || query == null || sign == null) return;
        if (IntentClassifier.isTimeSensitive(query)) {
            Log.i(TAG, "时效性问句不落缓存: " + com.onesay.assistant.v2.util.LogText.u(query));
            return;
        }
        if (Invocation.SIGN_PLAN.equals(sign)) return;
        try {
            com.onesay.assistant.v2.router.cache.CacheKey k = com.onesay.assistant.v2.router.cache.CacheKey.of(query);
            SkillEntry e = new SkillEntry();
            boolean ask = kind == IntentClassifier.Kind.ASK;
            e.grp = ask ? SkillCache.GRP_ASK : SkillCache.GRP_DO;
            e.key = ask ? k.literal : k.shape;
            e.label = ask ? "ASK" : "DO_ACT";
            e.sign = sign;
            JSONObject tpl = args != null ? new JSONObject(args.toString()) : new JSONObject();
            // 自测条目不做槽位泛化：数字全部作字面约束
            if (!ask && !k.numbers.isEmpty()) {
                JSONObject fixed = new JSONObject();
                for (int i = 0; i < k.numbers.size(); i++) fixed.put(String.valueOf(i), k.numbers.get(i));
                tpl.put("__fixed", fixed);
            }
            e.argsTpl = tpl.toString();
            Capability cap = CapabilityRegistry.getInstance().describe(sign);
            e.capVersion = ask ? "ask" : RegistryOracle.fingerprint(cap);
            e.epoch = CACHE_EPOCH;
            e.status = SkillEntry.STATUS_VERIFIED;
            e.okCount = 1;
            e.createdTs = System.currentTimeMillis();
            e.ttlMs = SkillCache.TTL_DO;
            if (e.capVersion == null) e.capVersion = "selftest";
            mSelfTest.store().put(e);
            Log.i(TAG, "[L1] 自测库写入: " + e);
        } catch (Throwable t) {
            Log.e(TAG, "自测库写入失败: " + t.getMessage());
        }
    }

    /**
     * ★ 只清**自测库**（审计 P0-5 ⑤：自测不得再清空用户真实缓存），并打开自测窗口。
     * 清生产库请用 {@link #clearProduction()}。
     */
    public int clearForTesting() {
        sSelfTestUntil = System.currentTimeMillis() + SELFTEST_WINDOW_MS;
        return mSelfTest.store().clear();
    }

    /** ★ round3 #4：清掉生产库里某句话的负缓存（验收脚本 cache 模块开跑前用；正缓存不动）。 */
    public int clearNegative(String query) {
        return mCache.clearNegative(query);
    }

    /** ★ round4：清掉生产库里某句话的正缓存（验收脚本 cache 模块 CA-0 用；负缓存不动）。 */
    public int clearPositive(String query) {
        return mCache.clearPositive(query);
    }

    /** 清空生产缓存（用户/排障显式操作才调用）。 */
    public int clearProduction() {
        return mProdStore.clear();
    }

    // =====================================================================
    // 一次性迁移：skills_v2.db → skill_cache_v3.db
    // =====================================================================

    private void ensureMigrated() {
        if (mMigrated) return;
        synchronized (L1SkillRouter.class) {
            if (mMigrated) return;
            mMigrated = true;
            try {
                SharedPreferences sp = mContext.getSharedPreferences(PREF, Context.MODE_PRIVATE);
                if (sp.getBoolean(PREF_MIGRATED, false)) return;
                File f = mContext.getDatabasePath(LEGACY_DB);
                if (f != null && f.exists()) {
                    migrateLegacy(f);
                    mContext.deleteDatabase(LEGACY_DB);
                }
                sp.edit().putBoolean(PREF_MIGRATED, true).apply();
            } catch (Throwable t) {
                Log.e(TAG, "[L1] 旧库迁移失败（不影响新缓存）: " + t.getMessage());
            }
        }
    }

    private void migrateLegacy(File f) {
        SQLiteDatabase db = null;
        Cursor c = null;
        int kept = 0, dropped = 0;
        try {
            db = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            c = db.rawQuery("SELECT intent, sign, args, kind FROM skills", null);
            while (c.moveToNext()) {
                String intent = c.getString(0);
                String sign = c.getString(1);
                String argsStr = c.getString(2);
                String kind = c.isNull(3) ? "AGENT" : c.getString(3);
                JSONObject args;
                try {
                    args = argsStr != null ? new JSONObject(argsStr) : new JSONObject();
                } catch (Throwable t) {
                    args = null;
                }
                SkillCache.Record r = args == null ? SkillCache.Record.REJ_INVENTED_ARG
                        : mCache.importLegacy(intent, kind, sign, args);
                if (r == SkillCache.Record.CANDIDATE) kept++; else dropped++;
                Log.i(TAG, "[L1-MIGRATE] " + r + " : " + com.onesay.assistant.v2.util.LogText.u(intent) + " -> " + sign + " " + com.onesay.assistant.v2.util.LogText.args(argsStr));
            }
        } catch (Throwable t) {
            Log.w(TAG, "[L1-MIGRATE] 读取旧库失败: " + t.getMessage());
        } finally {
            if (c != null) c.close();
            if (db != null) db.close();   // 旧库是本方法独立打开的只读连接，可以关
        }
        Log.i(TAG, "[L1-MIGRATE] 完成：导入(candidate) " + kept + " 条，丢弃可疑 " + dropped + " 条");
    }

    // =====================================================================
    // 能力表视图
    // =====================================================================

    /** 生产版能力表视图：指纹 = 签名+摘要+执行器+命令模板+参数 Schema。 */
    static final class RegistryOracle implements SkillCache.CapabilityOracle {
        @Override public String versionOf(String sign) {
            return fingerprint(CapabilityRegistry.getInstance().describe(sign));
        }

        @Override public int risk(String sign) {
            Capability cap = CapabilityRegistry.getInstance().describe(sign);
            return cap == null ? Integer.MAX_VALUE : cap.risk;
        }

        @Override public boolean validate(String sign, JSONObject args) {
            Capability cap = CapabilityRegistry.getInstance().describe(sign);
            if (cap == null) return false;
            if (cap.paramsSchema == null) return true;
            try {
                return cap.paramsSchema.validateAndApplyDefaults(args, new StringBuilder());
            } catch (Throwable t) {
                return false;
            }
        }

        static String fingerprint(Capability cap) {
            if (cap == null) return null;
            StringBuilder sb = new StringBuilder();
            sb.append(cap.sign).append('|').append(cap.summary).append('|').append(cap.executorType)
              .append('|').append(cap.cmdTemplate).append('|');
            try {
                if (cap.paramsSchema != null) sb.append(cap.paramsSchema.toJsonSchema().toString());
            } catch (Throwable ignored) {}
            String s = sb.toString();
            return Integer.toHexString(s.hashCode()) + ":" + s.length();
        }
    }
}
