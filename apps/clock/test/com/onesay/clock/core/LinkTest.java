package com.onesay.clock.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.*;

/** 伴侣连接（companion link）· 时钟侧：契约常量、hello、能力清单（OACP 0.3 形状）、函数 ↔ 旧广播动作、Manifest 占位符与构建脚本。 */
public class LinkTest {
    static int pass, fail;
    static final List<String> FAILS = new ArrayList<String>();

    static void ok(String name, boolean c) {
        if (c) pass++; else { fail++; FAILS.add(name); System.out.println("  FAIL " + name); }
    }

    static void eq(String name, Object want, Object got) {
        boolean c = want == null ? got == null : want.equals(got);
        if (!c) System.out.println("  want: " + want + "\n  got : " + got);
        ok(name, c);
    }

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("clock.root", "."), rel).toPath()), Charset.forName("UTF-8"));
    }

    public static void main(String[] a) throws Exception {
        contract(); hello(); descriptor(); manifest();
        System.out.println("LinkTest " + pass + "/" + (pass + fail) + (fail == 0 ? " ALL PASS" : " FAILED: " + FAILS));
        System.exit(fail == 0 ? 0 : 1);
    }

    static void contract() {
        eq("iface", "onesay.companion", LinkContract.IFACE);
        eq("version 1", 1, LinkContract.VERSION);
        eq("createAlarm = ALARM_SET", AlarmApi.A_SET, LinkContract.alarmAction(LinkContract.F_CREATE_ALARM));
        eq("listAlarms = ALARM_QUERY", AlarmApi.A_QUERY, LinkContract.alarmAction(LinkContract.F_LIST_ALARMS));
        eq("deleteAlarm = ALARM_CANCEL", AlarmApi.A_CANCEL, LinkContract.alarmAction(LinkContract.F_DELETE_ALARM));
        eq("toggleAlarm = ALARM_TOGGLE", AlarmApi.A_TOGGLE, LinkContract.alarmAction(LinkContract.F_TOGGLE_ALARM));
        eq("getBriefStatus 不是闹钟动作", null, LinkContract.alarmAction(LinkContract.F_GET_BRIEF_STATUS));
        ok("hello 是连接方法", LinkContract.isLinkMethod("link.hello"));
        ok("五个函数都是连接方法", LinkContract.isLinkMethod("createAlarm") && LinkContract.isLinkMethod("listAlarms") && LinkContract.isLinkMethod("deleteAlarm")
                && LinkContract.isLinkMethod("toggleAlarm") && LinkContract.isLinkMethod("getBriefStatus"));
        ok("别的不是", !LinkContract.isLinkMethod("deleteAll") && !LinkContract.isLinkMethod(null));
        eq("debug 助手 CONTROL 权限名", "com.onesay.assistant.debug.permission.CONTROL", LinkContract.assistantControlPermission("com.onesay.assistant.debug"));
        eq("release 助手 CONTROL 权限名", "com.onesay.assistant.permission.CONTROL", LinkContract.assistantControlPermission(null));
        ok("client 版本 1 可以", LinkContract.clientOk(1) && LinkContract.clientOk(0));
        ok("state.json 自检：合法", LinkContract.looksLikeState("{\"state\":\"READY\",\"detail\":\"\",\"at\":1}"));
        ok("state.json 自检：坏状态", !LinkContract.looksLikeState("{\"state\":\"HACK\",\"at\":1}") && !LinkContract.looksLikeState("nope"));
        // 调试闹钟（id≥900）经连接同样碰不到：连接只是换了通道，执行仍是 AlarmApi.apply
        List<Alarm> as = new ArrayList<Alarm>();
        Alarm d = new Alarm(); d.id = 990; d.hour = 7; d.on = true; as.add(d);
        eq("调试闹钟不能经连接删", AlarmApi.RC_BAD_ARGS, AlarmApi.apply(as, LinkContract.alarmAction(LinkContract.F_DELETE_ALARM), 990, -1, -1, 0, false, null).code);
    }

    static void hello() throws Exception {
        Map<String, Object> h = LinkContract.hello("0.1.6");
        eq("hello iface", LinkContract.IFACE, h.get(LinkContract.K_IFACE));
        eq("hello version", 1, h.get(LinkContract.K_VERSION));
        eq("hello min_client", 1, h.get(LinkContract.K_MIN_CLIENT));
        eq("hello caps", "createAlarm,listAlarms,deleteAlarm,toggleAlarm,getBriefStatus", h.get(LinkContract.K_CAPS));
        ok("hello 带清单", new JSONObject(String.valueOf(h.get(LinkContract.K_DESCRIPTOR))).has("capabilities"));
    }

    static void descriptor() throws Exception {
        JSONObject d = LinkContract.descriptor();
        eq("oacpVersion", "0.3", d.getString("oacpVersion"));
        eq("appId", "com.onesay.clock", d.getString("appId"));
        JSONArray caps = d.getJSONArray("capabilities");
        eq("5 项能力", 5, caps.length());
        for (int i = 0; i < caps.length(); i++) {
            JSONObject c = caps.getJSONObject(i);
            ok("能力 " + i + " 有 id/description/parameters/invoke", c.has("id") && c.has("description") && c.has("parameters") && c.has("invoke"));
            JSONObject an = c.getJSONObject("invoke").getJSONObject("android");
            eq("能力 " + i + " invoke 指向 link provider", LinkContract.AUTHORITY, an.getString("authority"));
            eq("能力 " + i + " method = id", c.getString("id"), an.getString("method"));
        }
        eq("x-onesayLink.version", 1, d.getJSONObject("x-onesayLink").getInt("version"));
    }

    static void manifest() throws Exception {
        String m = src("app/AndroidManifest.xml");
        ok("uses-permission 用占位符", m.contains("<uses-permission android:name=\"${assistantAppId}.permission.CONTROL\" />"));
        ok("不再写死 release 名", !m.contains("android:name=\"com.onesay.assistant.permission.CONTROL\""));
        ok("meta-data 用占位符", m.contains("android:name=\"" + LinkContract.META_ASSISTANT + "\" android:value=\"${assistantAppId}\""));
        int p = m.indexOf(".api.LinkProvider");
        ok("link provider 声明", p > 0);
        String pv = m.substring(p, m.indexOf("/>", p));
        ok("authority = 契约", pv.contains("android:authorities=\"" + LinkContract.AUTHORITY + "\""));
        ok("受 ALARM_CONTROL 保护", pv.contains("android:permission=\"" + AlarmApi.PERMISSION + "\""));
        ok("不授 uri 权限", pv.contains("android:grantUriPermissions=\"false\""));
        String dbg = src("build_linux.sh"), rel = src("build_release.sh");
        ok("debug 构建默认配 debug 助手", dbg.contains("ASSISTANT_APP_ID=\"${ASSISTANT_APP_ID:-com.onesay.assistant.debug}\""));
        ok("release 构建默认配 release 助手", rel.contains("ASSISTANT_APP_ID=\"${ASSISTANT_APP_ID:-com.onesay.assistant}\""));
        ok("两个脚本都用生成的 Manifest", dbg.contains("--manifest \"$OUT/AndroidManifest.xml\"") && rel.contains("--manifest \"$OUT/AndroidManifest.xml\""));
        ok("两个脚本都查漏填占位符", dbg.contains("Manifest 里还有没填的占位符") && rel.contains("Manifest 里还有没填的占位符"));
        String sb = src("app/src/com/onesay/clock/StateBus.java");
        ok("BRIEF_STATE 发给配对的助手（不写死包名）", sb.contains("AssistantLink.assistantPkg(c)"));
        String lp = src("app/src/com/onesay/clock/api/LinkProvider.java");
        ok("provider 代码里验权限 + 同证书", lp.contains("checkCallingPermission(AlarmApi.PERMISSION)") && lp.contains("checkSignatures"));
        ok("闹钟函数与广播共用 handle", lp.contains("AlarmApiReceiver.handle(") && src("app/src/com/onesay/clock/api/AlarmApiReceiver.java").contains("Reply r = handle(c, a,"));
    }
}
