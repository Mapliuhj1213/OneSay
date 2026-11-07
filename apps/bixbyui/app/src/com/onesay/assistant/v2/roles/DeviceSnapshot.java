package com.onesay.assistant.v2.roles;

import java.util.List;

/**
 * [roles] 角色层看到的「设备现状」：只读、可替换（生产 = {@link AndroidDeviceSnapshot}，JVM 测试 = 假快照）。
 *
 * <p>全部来自<b>已有能力</b>：PackageManager（是否安装 / 标签 / 标准 intent 解析）与能力注册表里的挖掘接口
 * （AppInterfaceMiner / MinedCapabilities 产物）。角色层不新增任何执行器。
 */
public interface DeviceSnapshot {

    /** 包是否已安装（每次调用都应反映当前状态：装/卸之后不需要重启）。 */
    boolean isInstalled(String pkg);

    /** 包的显示名；未知返回 null。 */
    String label(String pkg);

    /** 能处理该标准 intent 的包（去重、不含本 App 与系统选择器），没有返回空表。 */
    List<String> resolve(RoleCatalog.Probe probe);

    /** 挖掘出的接口（包名 + sign + 可检索文本）。 */
    List<MinedIface> mined();

    /** 注册表里是否有这个能力（用于种子声明的挖掘深链 handoff_sign）。 */
    boolean hasCapability(String sign);

    /** 挖掘接口摘要。 */
    final class MinedIface {
        public final String pkg;
        public final String sign;
        /** sign + 摘要 + 关键词（小写）。 */
        public final String text;

        public MinedIface(String pkg, String sign, String text) {
            this.pkg = pkg;
            this.sign = sign;
            this.text = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        }
    }
}
