package com.onesay.assistant.v2.soul;

import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

/**
 * [soul] s2_memory.db 里新增的表（库版本 1 → 2，由 {@code SqliteMemoryStore} 的 onCreate / onUpgrade 调用；全部 IF NOT EXISTS，可重复执行）。
 *
 * <pre>
 * memory_core / user_profile / daily_log（同一套列，见 {@link Claim}）
 *   id PK（三表共用序列，≥1e9，由 SqliteSoulRepo 分配）
 * soul(id PK AUTOINCREMENT, text, source, status, user_locked, reason, created_at, base_id)   人设版本
 * soul_identity(key PK, value)                                                              名字 / 称呼 / 语气 / 表情
 * ops_notes(id PK, scope, text, created_at)                                                 设备 / 应用怪癖（不注入）
 * soul_forgotten(key_hash PK, at)                                                           被忘内容的键哈希（不存原文）
 * soul_snapshot(id PK, json, at)                                                            整理 / 迁移前快照
 * soul_meta(key PK, value)
 * </pre>
 */
public final class SoulSchema {

    private SoulSchema() {}

    static final String CLAIM_COLS_DDL = "id INTEGER PRIMARY KEY, kind TEXT, text TEXT, quote TEXT, evidence_ref TEXT, confidence REAL DEFAULT 1, "
            + "first_seen INTEGER DEFAULT 0, reinforced_at INTEGER DEFAULT 0, valid_until INTEGER DEFAULT 0, supersedes INTEGER DEFAULT 0, "
            + "status TEXT, pinned INTEGER DEFAULT 0, privacy_class TEXT, source TEXT, recall_count INTEGER DEFAULT 0, subject TEXT, "
            + "relation TEXT, value TEXT, topic TEXT, day TEXT, session_id TEXT, tainted INTEGER DEFAULT 0, legacy_id INTEGER DEFAULT 0, "
            + "user_edited INTEGER DEFAULT 0, cand_kind TEXT";

    public static void create(SQLiteDatabase db) {
        for (String t : new String[]{Claim.T_CORE, Claim.T_PROFILE, Claim.T_LOG}) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + t + " (" + CLAIM_COLS_DDL + ")");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_" + t + "_status ON " + t + "(status)");
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_daily_log_day ON daily_log(day)");
        db.execSQL("CREATE TABLE IF NOT EXISTS soul (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT, source TEXT, status TEXT, "
                + "user_locked INTEGER DEFAULT 0, reason TEXT, created_at INTEGER DEFAULT 0, base_id INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS soul_identity (key TEXT PRIMARY KEY, value TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS ops_notes (id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT, text TEXT, created_at INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS soul_forgotten (key_hash TEXT PRIMARY KEY, at INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS soul_snapshot (id INTEGER PRIMARY KEY AUTOINCREMENT, json TEXT, at INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS soul_meta (key TEXT PRIMARY KEY, value TEXT)");
    }

    /**
     * 探测本机 SQLite 的全文检索能力（研究 §6.4：Android 9 自带 SQLite 是否有 FTS5 / trigram 要真机验证）。
     * 返回 "fts5-trigram" / "fts5" / "fts4" / "none"。只建临时表，用完即删。首版检索不依赖它（Java 里算 bigram），结果写进 soul_meta 供诊断。
     */
    public static String probeFts(SQLiteDatabase db) {
        String[][] tries = {
                {"fts5-trigram", "CREATE VIRTUAL TABLE temp.soul_fts_probe USING fts5(x, tokenize='trigram')"},
                {"fts5", "CREATE VIRTUAL TABLE temp.soul_fts_probe USING fts5(x)"},
                {"fts4", "CREATE VIRTUAL TABLE temp.soul_fts_probe USING fts4(x)"},
        };
        for (String[] t : tries) {
            try {
                db.execSQL(t[1]);
                try {
                    db.execSQL("DROP TABLE IF EXISTS temp.soul_fts_probe");
                } catch (Throwable ignored) {
                }
                return t[0];
            } catch (Throwable e) {
                Log.i("Soul", "[SOUL] FTS 探测 " + t[0] + " 不支持: " + e.getMessage());
            }
        }
        return "none";
    }
}
