package com.onesay.assistant.v2.companion;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * [companion] 伴侣发现的判定（纯 Java）：装没装 → 签名对不对 → {@code link.hello} 握手（接口名 / 版本 / 能力）。
 * Android 层（{@link CompanionLink}）只负责把 PackageManager / ContentResolver 的结果喂进来。
 */
public final class CompanionHandshake {
    private CompanionHandshake() {}

    /** 伴侣状态。PRESENT 系列（UNTRUSTED 除外）= 已安装且与助手同证书。 */
    public enum State {
        /** 没装。 */
        ABSENT,
        /** 装了但签名不同（或 provider 不归它）——当第三方对待。 */
        UNTRUSTED,
        /** 同证书，但 hello 不通 / 太旧（没有 link.hello）。 */
        NO_LINK,
        /** 同证书，hello 通了但版本不兼容。 */
        VERSION_MISMATCH,
        /** 同证书，版本兼容但缺必需能力。 */
        MISSING_CAPS,
        /** 可以走连接。 */
        READY;

        /** 同证书的我们自家 App（不论连接通不通）：AgentLoop 不得替这类意图去探测第三方 App。 */
        public boolean present() {
            return this != ABSENT && this != UNTRUSTED;
        }

        public boolean ready() {
            return this == READY;
        }
    }

    /** 一次判定结果。 */
    public static final class Result {
        public final State state;
        public final int peerVersion;
        public final Set<String> caps;
        public final String why;

        Result(State state, int peerVersion, Set<String> caps, String why) {
            this.state = state;
            this.peerVersion = peerVersion;
            this.caps = caps;
            this.why = why;
        }

        public boolean has(String cap) {
            return caps.contains(cap);
        }

        @Override public String toString() {
            return state + " v" + peerVersion + " · " + why;
        }
    }

    /**
     * @param installed  PackageManager 查得到包
     * @param sameSigner checkSignatures == MATCH 且 provider 归这个包
     * @param hello      {@code link.hello} 的结果（键见 {@link CompanionContract}）；null = 调用失败 / 没有这个方法
     * @param required   本端要求的能力
     */
    public static Result evaluate(boolean installed, boolean sameSigner, Map<String, Object> hello, String[] required) {
        Set<String> none = new LinkedHashSet<String>();
        if (!installed) return new Result(State.ABSENT, 0, none, "没装");
        if (!sameSigner) return new Result(State.UNTRUSTED, 0, none, "签名不同，当第三方 App 对待");
        if (hello == null) return new Result(State.NO_LINK, 0, none, "没有 link.hello（版本太旧或连不上）");
        String iface = str(hello.get(CompanionContract.K_IFACE));
        if (!CompanionContract.IFACE.equals(iface)) return new Result(State.NO_LINK, 0, none, "接口名不对：" + iface);
        int v = num(hello.get(CompanionContract.K_VERSION));
        int minClient = num(hello.get(CompanionContract.K_MIN_CLIENT));
        Set<String> caps = parseCaps(hello.get(CompanionContract.K_CAPS));
        if (v < CompanionContract.MIN_PEER_VERSION) return new Result(State.VERSION_MISMATCH, v, caps, "对端版本 " + v + " < 最低 " + CompanionContract.MIN_PEER_VERSION);
        if (minClient > CompanionContract.VERSION) return new Result(State.VERSION_MISMATCH, v, caps, "对端要求助手版本 ≥ " + minClient + "，本端 " + CompanionContract.VERSION);
        if (required != null) {
            for (String r : required) {
                if (!caps.contains(r)) return new Result(State.MISSING_CAPS, v, caps, "缺能力 " + r);
            }
        }
        return new Result(State.READY, v, caps, "已连上（接口 v" + v + "，" + caps.size() + " 项能力）");
    }

    static Set<String> parseCaps(Object o) {
        Set<String> s = new LinkedHashSet<String>();
        if (o == null) return s;
        if (o instanceof String[]) {
            s.addAll(Arrays.asList((String[]) o));
            return s;
        }
        for (String p : String.valueOf(o).split(",")) {
            p = p.trim();
            if (p.length() > 0) s.add(p);
        }
        return s;
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static int num(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return o == null ? 0 : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
