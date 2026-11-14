package com.onesay.assistant.v2.executor.backend;

import com.onesay.assistant.v2.executor.ShellCommand;
import com.onesay.assistant.v2.executor.ShellExecutor;
import com.onesay.assistant.v2.executor.ShellRunner;
import com.onesay.assistant.v2.executor.ShellScript;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 能力模板里带条件分支的复合脚本，改成 Java 里的「多次单条命令」（2026-10-25 渗透测试修复·集成）。
 *
 * <p>exec 守护 v4 不再执行含 {@code ; if $() |grep -q} 的脚本（fix-root.md §3「默认浏览器拉起」「地图兜底」）。
 * 这些能力的 {@code cmdTemplate} 仍保留原文（别处拿它做展示 / 路由特征 / 「是否拉起界面」判断），但 {@link ShellBackend}
 * 执行时改走这里：每一步都是一条单命令，槽位仍按 {@link ShellCommand#fill} 的上下文转义规则填。纯 Java，JVM 可测。
 */
public final class ShellPrograms {

    private ShellPrograms() {}

    public interface Program {
        ShellExecutor.ShellResponse run(ShellRunner sh, Map<String, String> slots);
    }

    public static final String SIGN_BROWSER_DEFAULT = "browser.open.default";

    public static final String RESOLVE_BROWSER = "cmd package resolve-activity --brief -a android.intent.action.VIEW -d http://example.com";
    public static final String BROWSER_FALLBACK = "am start -a android.intent.action.VIEW -d about:blank";
    static final Pattern COMPONENT = Pattern.compile("^[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+$");

    /** 打车：先高德打车深链，认不出（输出含 Error / 被拒）再用 geo: 搜索。原脚本以 {@code ; true} 结尾，rc 恒 0。 */
    public static final String TAXI_AMAP = "am start -a android.intent.action.VIEW -d 'amapuri://drive/takeTaxi?sourceApplication=bixbyui&dname={address}&dev=0'";
    public static final String TAXI_GEO = "am start -a android.intent.action.VIEW -d 'geo:0,0?q={address}'";
    /** 导航：先 geo:，认不出再开高德网页版。 */
    public static final String NAV_GEO = "am start -a android.intent.action.VIEW -d 'geo:0,0?q={address}'";
    public static final String NAV_WEB = "am start -a android.intent.action.VIEW -d 'https://uri.amap.com/search?keyword={address}'";

    // ---- 其它带 $() / 算术 / if / || 的能力模板（CI 跑能力注册表时发现，fix-root 的静态语料没覆盖到）----
    public static final String BRIGHTNESS_GET = "settings get system screen_brightness";
    public static final String VOLUME_GET = "media volume --stream 3 --get";
    // [ci-1114] 原 torch-sec1 sysfs 节点（厂商专有）的 shell 手电筒程序已删：开源版 torch.* 能力走 executor=custom
    //   （SysActionBackend → CameraManager），这条 shell 路径没人调，且 exec 守护 v4 本来就拒（PATH_NOT_ALLOWED / METACHAR）。
    public static final String LOC_GET = "settings get secure location_providers_allowed";
    public static final String NFC_ON = "svc nfc enable";
    public static final String NFC_OFF = "svc nfc disable";
    public static final String NFC_STATE = "dumpsys nfc | grep -m1 mState";
    public static final String STORE_GOOGLE = "dumpsys account | grep -c com.google";
    public static String screenshotCmd() {
        return "screencap -p /sdcard/Pictures/bixbyui" + com.onesay.assistant.v2.BuildVariant.PUBLIC_FILE_TAG + "_shot.png";
    }

    public static Program forSign(String sign) {
        if (sign == null) return null;
        if (SIGN_BROWSER_DEFAULT.equals(sign)) return BROWSER_DEFAULT;
        if (com.onesay.assistant.v2.s1.S1Capabilities.SIGN_TAXI.equals(sign)) return fallback(TAXI_AMAP, TAXI_GEO);
        // device-1102 问题 1/5：导航前置「定位已开」；s2.nav.app 原模板是 if…| grep -q…; fi; true 复合脚本 + -p，
        // 守护 v4 直接 DENY METACHAR(;) → 「被系统特权守护按安全策略拦下了」。改成单条命令序列。
        if (com.onesay.assistant.v2.vision.screenref.ScreenRefCapabilities.SIGN_NAV.equals(sign)) return navigate(false);
        if (com.onesay.assistant.v2.s2.S2Capabilities.SIGN_NAV_APP.equals(sign)) return navigate(true);
        if ("settings.brightness.up".equals(sign)) return brightnessStep(40);
        if ("settings.brightness.down".equals(sign)) return brightnessStep(-40);
        if ("volume.media.up".equals(sign)) return volumeStep(2);
        if ("volume.media.down".equals(sign)) return volumeStep(-2);
        if ("connectivity.gps.state".equals(sign)) return LOC_STATE;
        if ("connectivity.nfc.on".equals(sign)) return alwaysOk(NFC_ON);
        if ("connectivity.nfc.off".equals(sign)) return alwaysOk(NFC_OFF);
        if ("connectivity.nfc.state".equals(sign)) return nfcState("mState=off");
        if ("store.status.check".equals(sign)) return STORE_STATUS;
        if (com.onesay.assistant.v2.capability.caps.FastCaps.SIGN_MUTE_ALL.equals(sign)) return MUTE_ALL;
        if ("ui.screenshot".equals(sign)) return SCREENSHOT;
        return null;
    }

    /** 回读命令（check）里的复合写法。UnifiedExecutor 跑 check 时先问这里。 */
    public static Program checkFor(String sign) {
        if (sign == null) return null;
        if ("connectivity.gps.on".equals(sign) || "connectivity.gps.off".equals(sign)) return LOC_STATE;
        if ("connectivity.nfc.on".equals(sign)) return nfcState("mState=on");
        if ("connectivity.nfc.off".equals(sign)) return nfcState("mState=off");
        return null;
    }

    static ShellExecutor.ShellResponse ok(String out) {
        return new ShellExecutor.ShellResponse(0, out, false, false);
    }

    static boolean bad(ShellExecutor.ShellResponse r) {
        return r.isDown || r.isDenied;
    }

    static int firstInt(String s, Pattern p, int dflt) {
        if (s == null) return dflt;
        java.util.regex.Matcher m = p.matcher(s);
        if (!m.find()) return dflt;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    static final Pattern INT = Pattern.compile("(-?\\d+)");
    static final Pattern VOL_IS = Pattern.compile("volume is (\\d+)");
    static final Pattern VOL_MAX = Pattern.compile("\\.\\.(\\d+)");

    /** 原：b=$(settings get …); n=$((b±40)); 夹到 1..255; settings put …; echo $n */
    static Program brightnessStep(final int delta) {
        return new Program() {
            @Override public ShellExecutor.ShellResponse run(ShellRunner sh, Map<String, String> slots) {
                ShellExecutor.ShellResponse r = sh.execute(BRIGHTNESS_GET);
                if (bad(r)) return r;
                // 同原脚本 $((b ± 40))：读不出数时 b 按 0 算（shell 算术里空变量 = 0）
                int b = r.isSuccess() ? firstInt(r.output, INT, 0) : 0;
                int n = Math.max(1, Math.min(255, b + delta));
                ShellExecutor.ShellResponse w = sh.execute("settings put system screen_brightness " + n);
                if (bad(w) || !w.isSuccess()) return w;
                return ok(String.valueOf(n));
            }
        };
    }

    /** 原 VOL_STEP_CMD：读当前与上限（「..15」，缺省 15），±2 后夹到 0..上限再 set，回显新值；读不到当前值 → rc 1。 */
    static Program volumeStep(final int delta) {
        return new Program() {
            @Override public ShellExecutor.ShellResponse run(ShellRunner sh, Map<String, String> slots) {
                ShellExecutor.ShellResponse r = sh.execute(VOLUME_GET);
                if (bad(r)) return r;
                int v = firstInt(r.output, VOL_IS, -1);
                if (v < 0) return new ShellExecutor.ShellResponse(1, r.output, r.isTimeout, false);
                int m = firstInt(r.output, VOL_MAX, 15);
                int n = Math.max(0, Math.min(m, v + delta));
                ShellExecutor.ShellResponse w = sh.execute("media volume --stream 3 --set " + n);
                if (bad(w)) return w;
                return ok(String.valueOf(n));
            }
        };
    }

    /** 原 LOC_PROBE：providers 里有 gps 或 network → 3，否则 0。 */
    static final Program LOC_STATE = new Program() {
        @Override public ShellExecutor.ShellResponse run(ShellRunner sh, Map<String, String> slots) {
            ShellExecutor.ShellResponse r = sh.execute(LOC_GET);
            if (bad(r)) return r;
            String p = "," + r.output.trim() + ",";
            return ok(p.contains(",gps,") || p.contains(",network,") ? "3" : "0");
        }
    };

    /** 原：timeout 2 svc nfc enable; true（rc 恒 0）。timeout 交给守护的单条上限。 */
    static Program alwaysOk(final String cmd) {
        return new Program() {
            @Override public ShellExecutor.ShellResponse run(ShellRunner sh, Map<String, String> slots) {
                ShellExecutor.ShellResponse r = sh.execute(cmd);
                if (bad(r)) return r;
                return new ShellExecutor.ShellResponse(0, r.output, r.isTimeout, false);
            }
        };
    }

    /** 原：dumpsys nfc | grep -m1 mState || echo 缺省。注意 v4 的 dumpsys 服务白名单里没有 nfc（INTEGRATION.md）。 */
    static Program nfcState(final String dflt) {
        return new Program() {
            @Override public ShellExecutor.ShellResponse run(ShellRunner sh, Map<String, String> slots) {
                ShellExecutor.ShellResponse r = sh.execute(NFC_STATE);
                if (r.isDown) return r;
                String o = r.isSuccess() ? r.output.trim() : "";
                return ok(o.length() > 0 ? o : dflt);
            }
        };
    }

    /** ★ int/1102 审计「全部静音」：铃声 2 / 通知 5 / 系统 1 / 媒体 3 逐条置 0（每条都是守护白名单里的单命令）；闹钟 4 不动。 */
    public static final String[] MUTE_ALL_CMDS = {
            "media volume --stream 2 --set 0", "media volume --stream 5 --set 0",
            "media volume --stream 1 --set 0", "media volume --stream 3 --set 0"};

    static final Program MUTE_ALL = new Program() {
        @Override public ShellExecutor.ShellResponse run(ShellRunner sh, Map<String, String> slots) {
            int ok = 0;
            ShellExecutor.ShellResponse last = null;
            for (String c : MUTE_ALL_CMDS) {
                ShellExecutor.ShellResponse r = sh.execute(c);
                if (r == null) continue;
                if (r.isDown) return r;
                if (r.isSuccess()) ok++;
                last = r;
            }
            if (ok == 0 && last != null) return last;            // 一条都没成：按最后一条的失败上抛（被拒会有分类说明）
            return ok("muted=" + ok + "/" + MUTE_ALL_CMDS.length);
        }
    };

    /** 原 store.status.check：Google 账号数，并提示安装统一走 market://（默认应用商店）。 */
    static final Program STORE_STATUS = new Program() {
        @Override public ShellExecutor.ShellResponse run(ShellRunner sh, Map<String, String> slots) {
            ShellExecutor.ShellResponse g = sh.execute(STORE_GOOGLE);
            if (g.isDown) return g;
            int a = g.isDenied ? 0 : firstInt(g.output, INT, 0);
            return ok("google_accounts=" + a + "\n" + (a > 0 ? "play=可能可用" : "play=未登录(不可用)") + "\n"
                    + "store=market://（由默认应用商店处理）");
        }
    };

    /** 原：timeout 6 screencap -p /sdcard/Pictures/bixbyui…_shot.png …; true。注意 v4 不允许写 Pictures/（INTEGRATION.md）。 */
    static final Program SCREENSHOT = new Program() {
        @Override public ShellExecutor.ShellResponse run(ShellRunner sh, Map<String, String> slots) {
            return alwaysOk(screenshotCmd()).run(sh, slots);
        }
    };

    /** 默认浏览器：resolve 出组件就 {@code am start -n 组件}，否则回落 VIEW。 */
    static final Program BROWSER_DEFAULT = new Program() {
        @Override public ShellExecutor.ShellResponse run(ShellRunner sh, Map<String, String> slots) {
            ShellExecutor.ShellResponse r = sh.execute(RESOLVE_BROWSER);
            if (r.isDown) return r;
            String c = lastLine(r.isSuccess() ? r.output : "");
            return sh.execute(COMPONENT.matcher(c).matches() ? "am start -n " + c : BROWSER_FALLBACK);
        }
    };

    // ---- device-1102：导航（定位前置 + 指定包名 + 逐级回退）----
    public static final String LOC_MODE_GET = "settings get secure location_mode";
    /** 守护 SETTINGS_ALLOW 里已有 secure:location_mode；3 = 高精度（GPS+网络）。 */
    public static final String LOC_MODE_ON = "settings put secure location_mode 3";
    /** 老机型 / location_mode 写不进时的固定模板（守护 fixed_template 逐字放行）。 */
    public static final String LOC_PROVIDERS_ON = "settings put secure location_providers_allowed +gps; settings put secure location_providers_allowed +network";
    /** 指定包名的 geo:（守护 v4.4 起放行 {@code -p <包名>}；旧守护拒 → 回退到不带包名）。 */
    public static final String NAV_GEO_PKG = "am start -a android.intent.action.VIEW -d 'geo:0,0?q={address}' -p {pkg}";
    /** 输出里的标记行：定位是否由本次导航顺手打开（UnifiedExecutor/回复文案可据此说「已帮你打开定位」）。 */
    public static final String LOC_AUTO_ON_MARK = "loc_auto_on=1";

    /**
     * 导航前置：定位关着就打开（低风险、可逆，按「直接执行」处理，不弹卡、不硬拒）。
     * 返回 null = 定位本来就开着或已打开；否则返回让调用方原样上抛的响应（守护离线）。
     * 打开失败（被拒 / 写失败）不阻断导航——地图 App 自己会再提示开定位。
     */
    static ShellExecutor.ShellResponse ensureLocation(ShellRunner sh, StringBuilder note) {
        ShellExecutor.ShellResponse r = sh.execute(LOC_MODE_GET);
        if (r == null) return null;
        if (r.isDown) return r;
        String v = r.isSuccess() && r.output != null ? r.output.trim() : "";
        if (!"0".equals(v)) return null;          // 已开（1/2/3）或读不到：不动
        ShellExecutor.ShellResponse w = sh.execute(LOC_MODE_ON);
        if (w != null && w.isDown) return w;
        if (w == null || !w.isSuccess()) {
            ShellExecutor.ShellResponse w2 = sh.execute(LOC_PROVIDERS_ON);
            if (w2 != null && w2.isDown) return w2;
            if (w2 == null || !w2.isSuccess()) {
                android.util.Log.w("ShellPrograms", "[NAV] 定位是关的，自动打开失败（继续导航，地图会自己提示）");
                return null;
            }
        }
        android.util.Log.i("ShellPrograms", "[NAV] 定位是关的，已自动打开（location_mode=3）");
        note.append(LOC_AUTO_ON_MARK);
        return null;
    }

    /** 导航：先确保定位 → 有包名先 geo:+-p → geo: 系统默认 → 高德网页版。任何一步被守护拒都继续往下，不让整件事被一条拒绝卡死。 */
    static Program navigate(final boolean withPkg) {
        return new Program() {
            @Override public ShellExecutor.ShellResponse run(ShellRunner sh, Map<String, String> slots) {
                StringBuilder note = new StringBuilder();
                ShellExecutor.ShellResponse pre = ensureLocation(sh, note);
                if (pre != null) return pre;
                java.util.List<String> chain = new java.util.ArrayList<String>();
                String pkg = slots != null ? slots.get("pkg") : null;
                if (withPkg && pkg != null && pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) {
                    chain.add(ShellCommand.fill(NAV_GEO_PKG, slots));
                }
                chain.add(ShellCommand.fill(NAV_GEO, slots));
                chain.add(ShellCommand.fill(NAV_WEB, slots));
                ShellExecutor.ShellResponse last = null, firstDeny = null;
                for (String c : chain) {
                    ShellExecutor.ShellResponse r = sh.execute(c);
                    if (r == null) continue;
                    if (r.isDown) return r;
                    if (r.isDenied && firstDeny == null) firstDeny = r;
                    if (!ShellScript.failedForNav(r)) {
                        String o = (r.output == null ? "" : r.output.trim());
                        if (note.length() > 0) o = o.length() == 0 ? note.toString() : o + "\n" + note;
                        return new ShellExecutor.ShellResponse(0, o, r.isTimeout, false);
                    }
                    last = r;
                }
                // 全部失败：若全是被拒，按被拒上抛（ShellBackend 会给分类说明）；否则按原脚本 「; true」 当成功（地图自己报错）
                if (firstDeny != null && last != null && last.isDenied) return firstDeny;
                return new ShellExecutor.ShellResponse(0, last == null ? "" : last.output, last != null && last.isTimeout, false);
            }
        };
    }

    static Program fallback(final String first, final String second) {
        return new Program() {
            @Override public ShellExecutor.ShellResponse run(ShellRunner sh, Map<String, String> slots) {
                ShellExecutor.ShellResponse r = ShellScript.exec(sh,
                        ShellScript.run(ShellCommand.fill(first, slots)).orElse(ShellCommand.fill(second, slots)));
                if (r.isDown || r.isDenied) return r;
                return new ShellExecutor.ShellResponse(0, r.output, r.isTimeout, false);   // 原脚本 「; true」
            }
        };
    }

    static String lastLine(String out) {
        if (out == null) return "";
        String[] ls = out.trim().split("\n");
        return ls.length == 0 ? "" : ls[ls.length - 1].trim();
    }
}
