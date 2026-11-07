package com.onesay.assistant.v2.notice;

/**
 * · 系统状态胶囊（纯 Java，JVM 可测）：充电接上 / 充满、低电量、耳机连接。
 *
 * <p>都是<b>短暂</b>提醒（OriginOS / ColorOS 的做法：插电时亮一下就收）：充电 / 耳机 15 秒后自己过期；低电量 5 分钟。
 * 「正在充电」不是常驻胶囊——常驻会一直占着岛，挡住更重要的东西。
 * 去重：低电量每次「跌破阈值」只提醒一次（充上电回到阈值以上才重置）；同一档（阈值 / 一半阈值）不重复。
 */
public final class SystemStatusNotices {

    private SystemStatusNotices() {}

    public static final long BRIEF_MS = 15000L;
    public static final long LOW_MS = 5L * 60000L;

    /** 电池跟踪（纯逻辑）：喂 BATTERY_CHANGED 的 (level%, 是否在充电)，吐要发的提醒（或 null）。 */
    public static final class Battery {
        int mLevel = -1;
        boolean mCharging;
        boolean mKnown;
        /** 已提醒过的低电档：0 = 没有，1 = 阈值档，2 = 一半阈值档。 */
        int mLowStage;
        boolean mFullShown;

        public IslandNotice on(int level, boolean charging, int lowPct, long now) {
            if (level < 0 || level > 100) return null;
            boolean first = !mKnown;
            boolean wasCharging = mCharging;
            mKnown = true;
            mLevel = level;
            mCharging = charging;
            if (charging) {
                if (level > lowPct) mLowStage = 0;
                if (!wasCharging && !first) {   // 刚插上（服务刚起来时的第一次快照不算「插上」）
                    mFullShown = level >= 100;
                    return charge(level, now);
                }
                if (level >= 100 && !mFullShown && !first) {
                    mFullShown = true;
                    return full(now);
                }
                return null;
            }
            mFullShown = false;
            if (level > lowPct) {
                mLowStage = 0;
                return null;
            }
            int stage = level <= Math.max(1, lowPct / 2) ? 2 : 1;
            if (stage <= mLowStage) return null;
            mLowStage = stage;
            return low(level, stage == 2, now);
        }

        public int level() { return mLevel; }
        public boolean charging() { return mCharging; }
    }

    public static IslandNotice charge(int level, long now) {
        IslandNotice n = new IslandNotice(IslandNotice.Source.SYSTEM, "sys:charge:" + now, IslandNotice.Priority.LOW,
                "正在充电 · " + level + "%", "");
        n.sourceTag = "充电";
        n.tone = "ok";
        n.persist = false;
        n.expireAt = now + BRIEF_MS;
        return n;
    }

    public static IslandNotice full(long now) {
        IslandNotice n = new IslandNotice(IslandNotice.Source.SYSTEM, "sys:full:" + now, IslandNotice.Priority.LOW, "电已充满 · 100%", "可以拔掉充电器了");
        n.sourceTag = "充电";
        n.tone = "ok";
        n.persist = false;
        n.expireAt = now + 60000L;
        return n;
    }

    public static IslandNotice low(int level, boolean critical, long now) {
        IslandNotice n = new IslandNotice(IslandNotice.Source.SYSTEM, "sys:low:" + (critical ? "2" : "1") + ":" + now,
                critical ? IslandNotice.Priority.HIGH : IslandNotice.Priority.NORMAL, "电量低 · " + level + "%", critical ? "请尽快充电" : "");
        n.sourceTag = "电量";
        n.tone = "warn";
        n.persist = false;
        n.expireAt = now + LOW_MS;
        n.action(new NoticeAction("知道了", NoticeAction.Kind.DISMISS, ""));
        return n;
    }

    /** 耳机连上（有线 / 蓝牙）。name 为空 → 「耳机已连接」。断开不提醒（OriginOS / ColorOS 也只在连上时亮一下）。 */
    public static IslandNotice headset(String name, boolean bluetooth, long now) {
        String nm = name == null ? "" : IslandNotice.clip(name, 16);
        IslandNotice n = new IslandNotice(IslandNotice.Source.SYSTEM, "sys:headset:" + now, IslandNotice.Priority.LOW,
                (bluetooth ? "蓝牙耳机已连接" : "耳机已连接") + (nm.length() > 0 ? " · " + nm : ""), "");
        n.sourceTag = "耳机";
        n.tone = "ok";
        n.persist = false;
        n.expireAt = now + BRIEF_MS;
        return n;
    }
}
