package com.onesay.assistant.v2.platform;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 功能注册表里的一项（纯 Java，refactor/oss 2026-11-06）：功能是什么、需要哪些设备能力、没有时的替代、开关在哪。
 *
 * <p>开关绑定（{@link #prefs} / {@link #key} / {@link #def} / {@link #killFiles}）<b>必须与该功能现有的 Settings 类一致</b>，
 * 由 JVM 测试逐项比对，保证「设备能力与功能」页上的开关和原设置页读写的是同一个值、默认值不变。
 * 没有开关的项（状态项、未实现的项）{@link #prefs} 为 null。
 */
public final class FeatureSpec {

    /** 功能的落地状态。 */
    public enum Status {
        /** 已实现并随包发布。 */
        SHIPPED,
        /** 代码在，但只进 debug 包。 */
        DEBUG_ONLY,
        /** 受系统限制（无 root / 非三星 / 系统版本）从没做或被砍掉，只在这里登记。 */
        NOT_BUILT
    }

    public final String id;
    public final String title;
    /** 分组：唤醒入口 / 执行 / 读屏 / 显示 / 厂商专属 / 未实现。 */
    public final String group;
    public final Status status;
    /** 全部都要有（AND）。 */
    public final List<DeviceCap> requires;
    /** 没有所需能力时的替代做法（给人看）。 */
    public final String alternative;
    /** 一句说明（可空）。 */
    public final String note;
    /** 开关绑定；null = 只显示状态、没有开关。 */
    public final String prefs, key;
    public final boolean def;
    public final List<String> killFiles;

    private FeatureSpec(String id, String title, String group, Status status, DeviceCap[] requires, String alternative,
                        String note, String prefs, String key, boolean def, String[] killFiles) {
        this.id = id;
        this.title = title;
        this.group = group;
        this.status = status;
        this.requires = Collections.unmodifiableList(Arrays.asList(requires == null ? new DeviceCap[0] : requires));
        this.alternative = alternative;
        this.note = note;
        this.prefs = prefs;
        this.key = key;
        this.def = def;
        this.killFiles = Collections.unmodifiableList(Arrays.asList(killFiles == null ? new String[0] : killFiles));
    }

    /** 有开关（与原设置共用同一个 prefs 值）的已实现功能。 */
    public static FeatureSpec toggle(String id, String title, String group, DeviceCap[] requires, String alternative, String note,
                                     String prefs, String key, boolean def, String... killFiles) {
        return new FeatureSpec(id, title, group, Status.SHIPPED, requires, alternative, note, prefs, key, def, killFiles);
    }

    /** 已实现、但没有单独开关（由守护 / 权限 / 系统设置决定）的功能：只显示可用与否。 */
    public static FeatureSpec status(String id, String title, String group, Status status, DeviceCap[] requires,
                                     String alternative, String note) {
        return new FeatureSpec(id, title, group, status, requires, alternative, note, null, null, false, null);
    }

    /** 受系统限制没做 / 砍掉的功能：登记所需能力和替代方案，开关永远置灰。 */
    public static FeatureSpec notBuilt(String id, String title, DeviceCap[] requires, String alternative, String note) {
        return new FeatureSpec(id, title, FeatureRegistry.G_NOT_BUILT, Status.NOT_BUILT, requires, alternative, note, null, null, false, null);
    }

    public boolean hasSwitch() {
        return prefs != null && key != null;
    }

    @Override
    public String toString() {
        return id + (hasSwitch() ? " [" + prefs + "." + key + " def=" + def + "]" : "") + " requires=" + requires;
    }
}
