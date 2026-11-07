package com.onesay.assistant.v2.security;

/**
 * SharedPreferences 里凭据值的编解码规则（纯 Java，无 Android 依赖，JVM 全测）。
 *
 * 存储格式：
 * <ul>
 *   <li>{@code ks1:<base64(iv+密文)>} —— Android Keystore（AES-256-GCM，密钥不出 TEE / 系统 keystore）加密；</li>
 *   <li>不带前缀 —— 明文（老版本写的，或 Keystore 不可用时的降级写入）。读取照常返回，并标记「待迁移」。</li>
 * </ul>
 * 不丢数据的规则：
 * <ol>
 *   <li>写入 / 迁移：先加密，再<b>立即解密回读比对</b>，一致才用密文；任何一步失败就保留明文（与旧版行为相同）。</li>
 *   <li>读取：明文原样返回；密文解密失败（例如数据被还原到另一次安装、Keystore 被清）时返回空串并标记 UNDECRYPTABLE，
 *       <b>不删除</b>原值，由调用方记日志、提示重新填写。</li>
 * </ol>
 */
public final class SecretValues {

    public static final String PREFIX = "ks1:";

    public enum State { EMPTY, PLAIN, ENCRYPTED, UNDECRYPTABLE }

    /** 读取结果：value 是给业务用的明文（失败为 ""）。 */
    public static final class Read {
        public final String value;
        public final State state;
        Read(String value, State state) { this.value = value; this.state = state; }
        /** 明文且非空 → 应该迁移成密文。 */
        public boolean needsMigration() { return state == State.PLAIN; }
    }

    /** 写入结果：stored 是该写进 prefs 的字符串。 */
    public static final class Write {
        public final String stored;
        public final boolean encrypted;
        public final String error;   // 降级原因（只含异常类名，绝不含值）
        Write(String stored, boolean encrypted, String error) {
            this.stored = stored; this.encrypted = encrypted; this.error = error;
        }
    }

    private SecretValues() {}

    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    public static Read decode(String stored, SecretCodec codec) {
        if (stored == null || stored.length() == 0) return new Read("", State.EMPTY);
        if (!isEncrypted(stored)) return new Read(stored, State.PLAIN);
        if (codec == null) return new Read("", State.UNDECRYPTABLE);
        try {
            String v = codec.decrypt(stored.substring(PREFIX.length()));
            if (v == null) return new Read("", State.UNDECRYPTABLE);
            return new Read(v, State.ENCRYPTED);
        } catch (Throwable t) {
            return new Read("", State.UNDECRYPTABLE);
        }
    }

    /** 把明文编码成要存的字符串。空值存空串（等同删除语义由调用方决定）。 */
    public static Write encode(String plain, SecretCodec codec) {
        if (plain == null || plain.length() == 0) return new Write("", false, null);
        if (codec == null) return new Write(plain, false, "no-codec");
        try {
            String blob = codec.encrypt(plain);
            if (blob == null || blob.length() == 0) return new Write(plain, false, "empty-blob");
            String back = codec.decrypt(blob);
            if (!plain.equals(back)) return new Write(plain, false, "roundtrip-mismatch");
            return new Write(PREFIX + blob, true, null);
        } catch (Throwable t) {
            return new Write(plain, false, t.getClass().getSimpleName());
        }
    }

    /** 状态描述（调试 / 控制台用，不含值）。 */
    public static String describe(State s) {
        switch (s) {
            case EMPTY: return "未配置";
            case PLAIN: return "已配置（明文，待迁移）";
            case ENCRYPTED: return "已配置（Keystore 加密）";
            default: return "已配置但无法解密（请重新填写）";
        }
    }
}
