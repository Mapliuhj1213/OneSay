package com.onesay.assistant.v2.roles;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * [roles] 运行时推导出的候选 App（某角色下）。{@link #id} 是习惯库里存的值：
 * 有种子的用种子 id（与旧 S2 习惯数据同值，旧数据零迁移），推导出来的非种子 App 用包名。
 */
public final class RoleCandidate {

    public static final String SRC_SEED = "seed";
    public static final String SRC_INSTALLED = "installed";
    public static final String SRC_INTENT = "intent";
    public static final String SRC_MINED = "mined";

    public final String id;
    public final String pkg;
    public final String label;
    public final String launchName;
    /** 口头叫法（小写，长的在前）；非种子候选只有它的标签。 */
    public final String[] aliases;
    /** 句子带目的地时的能力（种子级优先，其次角色级）；null = 直接打开。 */
    public final String destSign;
    /** 设备上存在的挖掘深链能力（代替 app.launch）；null = app.launch。 */
    public final String handoffSign;
    /** 是否已安装；设备现状未知（无快照）时为 true。 */
    public final boolean installed;
    /** 证据来源（seed / installed / intent / mined）。 */
    public final Set<String> sources;
    /** 该包在挖掘接口里出现的次数（证据，仅展示）。 */
    public final int minedCount;

    RoleCandidate(String id, String pkg, String label, String launchName, String[] aliases, String destSign,
                  String handoffSign, boolean installed, Set<String> sources, int minedCount) {
        this.id = id;
        this.pkg = pkg;
        this.label = label;
        this.launchName = launchName;
        this.aliases = aliases;
        this.destSign = destSign;
        this.handoffSign = handoffSign;
        this.installed = installed;
        this.sources = Collections.unmodifiableSet(new LinkedHashSet<String>(sources));
        this.minedCount = minedCount;
    }

    /** 只按种子元数据构造（不知道属于哪个角色 / 安装状态时，如「别默认用高德了」）。 */
    public static RoleCandidate ofSeed(RoleCatalog.Seed s) {
        java.util.Set<String> src = new LinkedHashSet<String>();
        src.add(SRC_SEED);
        return new RoleCandidate(s.id, s.pkg, s.label, s.launchName, s.aliases, s.destSign, null, true, src, 0);
    }

    public boolean isSeed() {
        return sources.contains(SRC_SEED);
    }

    @Override public String toString() {
        return id + "[" + pkg + (installed ? "" : " 未安装") + " " + sources + (minedCount > 0 ? " mined=" + minedCount : "") + "]";
    }
}
