package com.onesay.assistant.v2.jobs;

import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.Set;

/**
 * 一条任务的授权（随任务一起持久化）。纯 Java，JVM 可测。
 *
 * <ul>
 *   <li>{@link #required}：创建时静态分析出的权限清单（{@link PermissionAnalyzer#analyze}）；</li>
 *   <li>{@link #granted}：用户在权限卡上勾选的项 —— <b>只可能是可预授权的项</b>（{@link #grant} 会把发邮件 / 付款 / 红卡剔掉）；</li>
 *   <li>{@link #fingerprint}：任务规格（指令 / 模板 / 时间 / 触发）+ 权限清单的摘要。任务一改、或者重新分析出的清单变了，
 *       指纹就对不上 → {@link #needsReauth} 为真 → 任务停在「等确认」，重新弹权限卡。</li>
 * </ul>
 */
public final class TaskGrant {

    public final String spec;
    public final Set<Perm> required;
    public final Set<Perm> granted;
    public final String fingerprint;
    public final long grantedAt;

    private TaskGrant(String spec, Set<Perm> required, Set<Perm> granted, String fingerprint, long grantedAt) {
        this.spec = spec == null ? "" : spec;
        this.required = required;
        this.granted = granted;
        this.fingerprint = fingerprint;
        this.grantedAt = grantedAt;
    }

    /** 用户在权限卡上确认：checked 里不可预授权的项一律剔除。 */
    public static TaskGrant grant(String spec, Set<Perm> required, Set<Perm> checked, long now) {
        Set<Perm> req = copy(required);
        Set<Perm> g = EnumSet.noneOf(Perm.class);
        if (checked != null) for (Perm p : checked) if (p != null && p.preGrantable && req.contains(p)) g.add(p);
        return new TaskGrant(spec, req, g, fingerprint(spec, req), now);
    }

    /** 规格 + 清单的指纹（SHA-256 前 16 位十六进制）。 */
    public static String fingerprint(String spec, Set<Perm> required) {
        StringBuilder sb = new StringBuilder(spec == null ? "" : spec.trim()).append('\u0001');
        for (Perm p : Perm.values()) if (required != null && required.contains(p)) sb.append(p.name()).append(',');
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes("UTF-8"));
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < 8; i++) h.append(String.format("%02x", d[i] & 0xff));
            return h.toString();
        } catch (Exception e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    /** 当前规格 / 当前分析出的清单与授权时不一致 → 必须重新授权。 */
    public boolean needsReauth(String currentSpec, Set<Perm> currentRequired) {
        return !fingerprint.equals(fingerprint(currentSpec, currentRequired));
    }

    /** 清单里还没授权、且可以预授权的项（权限卡上要勾的）。 */
    public Set<Perm> missing() {
        Set<Perm> m = EnumSet.noneOf(Perm.class);
        for (Perm p : required) if (p.preGrantable && !granted.contains(p)) m.add(p);
        return m;
    }

    /** 清单里每次都要单独确认的项。 */
    public static Set<Perm> perRun(Set<Perm> required) {
        Set<Perm> m = EnumSet.noneOf(Perm.class);
        if (required != null) for (Perm p : required) if (!p.preGrantable) m.add(p);
        return m;
    }

    // ------------------------------------------------------------------ 序列化（存库）

    public static String encode(Set<Perm> s) {
        StringBuilder sb = new StringBuilder();
        for (Perm p : Perm.values()) if (s != null && s.contains(p)) sb.append(sb.length() == 0 ? "" : ",").append(p.name());
        return sb.toString();
    }

    public static Set<Perm> decode(String s) {
        Set<Perm> r = EnumSet.noneOf(Perm.class);
        if (s == null) return r;
        for (String x : s.split(",")) {
            Perm p = Perm.parse(x);
            if (p != null) r.add(p);
        }
        return r;
    }

    /** 从库里读回（granted 再过一遍「可预授权」过滤：库被篡改也授不出发邮件 / 付款 / 红卡）。 */
    public static TaskGrant restore(String spec, String required, String granted, String fingerprint, long grantedAt) {
        Set<Perm> req = decode(required);
        Set<Perm> g = EnumSet.noneOf(Perm.class);
        for (Perm p : decode(granted)) if (p.preGrantable) g.add(p);
        return new TaskGrant(spec, req, g, fingerprint == null ? "" : fingerprint, grantedAt);
    }

    private static Set<Perm> copy(Set<Perm> s) {
        Set<Perm> r = EnumSet.noneOf(Perm.class);
        if (s != null) r.addAll(s);
        return r;
    }

    @Override public String toString() {
        return "grant{req=" + encode(required) + " ok=" + encode(granted) + " fp=" + fingerprint + "}";
    }
}
