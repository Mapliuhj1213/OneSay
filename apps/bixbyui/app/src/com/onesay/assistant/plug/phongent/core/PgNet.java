package com.onesay.assistant.plug.phongent.core;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * [phongent] 来源地址闸（OneSay 自写，上游没有这一层）：执行端只接受局域网 / Tailscale 来的连接，
 * 公网地址（例如手机走移动数据、被端口映射）一律直接断开。纯 Java，JVM 可测。
 *
 * <ul>
 *   <li>Tailscale：IPv4 100.64.0.0/10（CGNAT 段），IPv6 fd7a:115c:a1e0::/48；</li>
 *   <li>局域网：10/8、172.16/12、192.168/16、169.254/16、IPv6 fe80::/10、fc00::/7；</li>
 *   <li>回环：只有显式允许（JVM 单测 / 沙箱联调）才放行 —— 生产不放，免得本机其它 App 冒充主控弹配对申请。</li>
 * </ul>
 */
public final class PgNet {

    private PgNet() {}

    public static boolean isTailscale(InetAddress a) {
        if (a == null) return false;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            return b0 == 100 && b1 >= 64 && b1 <= 127;
        }
        if (a instanceof Inet6Address) {
            return (b[0] & 0xff) == 0xfd && (b[1] & 0xff) == 0x7a && (b[2] & 0xff) == 0x11 && (b[3] & 0xff) == 0x5c
                    && (b[4] & 0xff) == 0xa1 && (b[5] & 0xff) == 0xe0;
        }
        return false;
    }

    public static boolean isLan(InetAddress a) {
        if (a == null) return false;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if (b0 == 10) return true;
            if (b0 == 172 && b1 >= 16 && b1 <= 31) return true;
            if (b0 == 192 && b1 == 168) return true;
            return b0 == 169 && b1 == 254;
        }
        if (a instanceof Inet6Address) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if (b0 == 0xfe && (b1 & 0xc0) == 0x80) return true;   // fe80::/10
            return (b0 & 0xfe) == 0xfc && !isTailscale(a);          // fc00::/7（ULA）
        }
        return false;
    }

    /** IPv4-mapped IPv6（::ffff:a.b.c.d）还原成 IPv4，再判。 */
    static InetAddress unmap(InetAddress a) {
        if (a instanceof Inet6Address) {
            byte[] b = a.getAddress();
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) mapped = false;
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]});
                } catch (Exception ignored) {
                }
            }
        }
        return a;
    }

    /** 这个来源能不能连。tailscaleOnly = 设置里「只允许 Tailscale」。 */
    public static boolean allowed(InetAddress remote, boolean tailscaleOnly, boolean allowLoopback) {
        InetAddress a = unmap(remote);
        if (a == null) return false;
        if (a.isLoopbackAddress()) return allowLoopback;
        if (isTailscale(a)) return true;
        return !tailscaleOnly && isLan(a);
    }

    /** 本机可被主控连的地址（控制台显示 / 手动配对链接用）：Tailscale 排前面。 */
    public static List<String> localAddresses() {
        List<String> ts = new ArrayList<String>(), lan = new ArrayList<String>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    if (isTailscale(a)) ts.add(a.getHostAddress());
                    else if (isLan(a)) lan.add(a.getHostAddress());
                }
            }
        } catch (Throwable ignored) {
        }
        ts.addAll(lan);
        return ts;
    }
}
