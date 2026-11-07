package com.onesay.assistant.v2.roles;

import com.onesay.assistant.v2.s2.HabitPolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * [roles] 「这次用哪个 App」的唯一裁决（纯函数，JVM 可测）。顺序固定，命中即停：
 *
 * <ol>
 *   <li><b>单句点名</b>（「用高德打车」）→ 这一次用它，不改默认；没装 → 安装提示；</li>
 *   <li><b>显式默认</b>（说过「以后打车用滴滴」或在选择芯片上选过一次）→ 用它；没装 → 安装提示（默认保留，装好即生效）；</li>
 *   <li><b>习惯推断</b>（S2 原规则：最近 10 次里某 App ≥3 次且领先）→ 用它（已卸载则跳过，不提示安装）；</li>
 *   <li>设备现状未知（无快照）→ {@link Kind#NONE}（原流程照旧，等同旧 S2）；</li>
 *   <li>只装了 <b>1</b> 个候选 → 静默用它；</li>
 *   <li>装了 <b>≥2</b> 个 → 选择芯片：<b>固定补足 {@link #MAX_CHOICES} 个</b>（R5 用户决定 2026-10-08）——已安装在前，
 *       不足时按 {@code app_roles.json} 种子顺序（= 热门度）用<b>未安装</b>的补位；数据里种子不够时有几个出几个；</li>
 *   <li>一个都没装 → 安装选择（同样按种子顺序最多 {@link #MAX_CHOICES} 个，全是未安装）。</li>
 * </ol>
 * <p>点芯片之后做什么见 {@link #onChoice}：点<b>已安装</b>的 = 记成显式默认并执行；点<b>未安装</b>的 = 只走安装链，
 * <b>不</b>设默认（所以没默认时下次照样再问，直到用户选了一个已安装的）。</p>
 */
public final class RoleResolver {

    private RoleResolver() {}

    public static final int MAX_CHOICES = 4;

    public enum Kind { OVERRIDE, EXPLICIT, INFERRED, SINGLE, CHOOSER, INSTALL, NONE_INSTALLED, NONE }

    public static final class Resolution {
        public final Kind kind;
        /** 要用的 App（OVERRIDE/EXPLICIT/INFERRED/SINGLE）或要装的 App（INSTALL）。 */
        public final RoleCandidate target;
        /** CHOOSER / NONE_INSTALLED 的选项。 */
        public final List<RoleCandidate> choices;
        /** 习惯裁决（EXPLICIT/INFERRED 时非空）。 */
        public final HabitPolicy.Default def;

        Resolution(Kind kind, RoleCandidate target, List<RoleCandidate> choices, HabitPolicy.Default def) {
            this.kind = kind;
            this.target = target;
            this.choices = choices == null ? new ArrayList<RoleCandidate>() : choices;
            this.def = def;
        }

        /** 直接可执行（选好了且已安装）。 */
        public boolean usable() {
            return target != null && (kind == Kind.OVERRIDE || kind == Kind.EXPLICIT || kind == Kind.INFERRED || kind == Kind.SINGLE);
        }

        @Override public String toString() {
            return kind + (target != null ? " " + target.id : "") + (choices.isEmpty() ? "" : " " + ids(choices)) + (def != null ? " def=" + def : "");
        }
    }

    /**
     * @param cands       {@link CandidateDeriver#derive} 的结果
     * @param named       句子里点名的候选（可 null）
     * @param explicitId  显式默认（种子 id 或包名，可 null）
     * @param recentUses  重置之后的使用记录（新的在前）
     * @param deviceKnown 是否有设备快照（false = 不知道装了什么 → 不出选择/安装/单装静默）
     */
    public static Resolution resolve(List<RoleCandidate> cands, RoleCandidate named, String explicitId,
                                     List<String> recentUses, boolean deviceKnown) {
        if (named != null) {
            return named.installed || !deviceKnown ? new Resolution(Kind.OVERRIDE, named, null, null)
                    : new Resolution(Kind.INSTALL, named, null, null);
        }
        if (explicitId != null && explicitId.length() > 0) {
            RoleCandidate c = CandidateDeriver.find(cands, explicitId);
            HabitPolicy.Default d = HabitPolicy.decide(explicitId, null);
            if (c != null) {
                return c.installed || !deviceKnown ? new Resolution(Kind.EXPLICIT, c, null, d)
                        : new Resolution(Kind.INSTALL, c, null, d);
            }
            // 显式默认指向的推导候选已不在（卸载了的非种子 App）→ 往下走
        }
        HabitPolicy.Default inf = HabitPolicy.decide(null, recentUses);
        if (inf != null) {
            RoleCandidate c = CandidateDeriver.find(cands, inf.appId);
            if (c != null && (c.installed || !deviceKnown)) return new Resolution(Kind.INFERRED, c, null, inf);
        }
        if (!deviceKnown) return new Resolution(Kind.NONE, null, null, null);
        List<RoleCandidate> inst = CandidateDeriver.installed(cands);
        if (inst.size() == 1) return new Resolution(Kind.SINGLE, inst.get(0), null, null);
        List<RoleCandidate> choices = new ArrayList<RoleCandidate>();
        for (RoleCandidate c : inst) if (choices.size() < MAX_CHOICES) choices.add(c);
        if (inst.size() >= 2) {
            for (RoleCandidate c : cands) if (!c.installed && c.isSeed() && choices.size() < MAX_CHOICES) choices.add(c);
            return new Resolution(Kind.CHOOSER, null, choices, null);
        }
        for (RoleCandidate c : cands) if (c.isSeed() && choices.size() < MAX_CHOICES) choices.add(c);
        return new Resolution(choices.isEmpty() ? Kind.NONE : Kind.NONE_INSTALLED, null, choices, null);
    }

    /**
     * [r2/bike-chooser] 角色声明了 always_choose：只装了一个（SINGLE）也不静默打开，改成选择网格
     * （已安装的在前，再按种子顺序补未安装的，共 {@link #MAX_CHOICES} 个）。点名 / 显式默认 / 推断默认不受影响。
     */
    public static Resolution chooseEvenIfSingle(List<RoleCandidate> cands, Resolution res) {
        if (res == null || res.kind != Kind.SINGLE) return res;
        List<RoleCandidate> choices = new ArrayList<RoleCandidate>();
        choices.add(res.target);
        for (RoleCandidate c : cands) if (!c.installed && c.isSeed() && choices.size() < MAX_CHOICES) choices.add(c);
        return new Resolution(Kind.CHOOSER, null, choices, null);
    }

    /** 点了选择芯片里的某一项之后做什么（R5）。 */
    public enum ChoiceAction {
        /** 已安装：写显式默认，然后执行（mode=apply）或只设默认（mode=set）。 */
        SET_DEFAULT,
        /** 未安装：只打开安装链（「未安装 X，点此安装」同一条链），不写默认、不执行。 */
        INSTALL_ONLY,
        /** 选项不存在（候选已变化）：什么也不做。 */
        IGNORE
    }

    /**
     * @param c           被点的候选（按点击时<b>重新推导</b>的设备现状；null = 已不在候选里）
     * @param deviceKnown 是否有设备快照（无快照 = 视为已安装，等同旧行为）
     */
    public static ChoiceAction onChoice(RoleCandidate c, boolean deviceKnown) {
        if (c == null) return ChoiceAction.IGNORE;
        if (deviceKnown && !c.installed) return ChoiceAction.INSTALL_ONLY;
        return ChoiceAction.SET_DEFAULT;
    }

    static String ids(List<RoleCandidate> l) {
        StringBuilder sb = new StringBuilder("[");
        for (RoleCandidate c : l) {
            if (sb.length() > 1) sb.append(',');
            sb.append(c.id).append(c.installed ? "" : "*");
        }
        return sb.append(']').toString();
    }
}
