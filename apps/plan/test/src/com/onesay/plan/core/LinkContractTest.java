package com.onesay.plan.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.Assert.*;

/** 伴侣连接（companion link）· 计划侧：契约、hello、OACP 形状的能力清单、Manifest 占位符与构建脚本、provider 接线。 */
public class LinkContractTest {

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("plan.root", "."), rel).toPath()), Charset.forName("UTF-8"));
    }

    @Test public void contractBasics() {
        assertEquals("onesay.companion", LinkContract.IFACE);
        assertEquals(1, LinkContract.VERSION);
        assertTrue(LinkContract.isLinkMethod("link.hello"));
        for (String f : new String[]{"createTask", "listTasks", "updateTask", "completeTask", "deleteTask", "createEvent", "listEvents"})
            assertTrue(f, LinkContract.isLinkMethod(f));
        assertFalse(LinkContract.isLinkMethod("ping"));
        assertFalse(LinkContract.isLinkMethod("import_bundle"));
        assertFalse(LinkContract.isLinkMethod(null));
        assertEquals("com.onesay.assistant.debug.permission.CONTROL", LinkContract.assistantControlPermission("com.onesay.assistant.debug"));
        assertEquals("com.onesay.assistant.permission.CONTROL", LinkContract.assistantControlPermission(""));
        assertTrue(LinkContract.clientOk(1));
        assertTrue(LinkContract.clientOk(0));
    }

    @Test public void helloCarriesVersionCapsAndDescriptor() throws Exception {
        Map<String, Object> h = LinkContract.hello("0.1.8", Contract.AUTHORITY);
        assertEquals("onesay.companion", h.get(LinkContract.K_IFACE));
        assertEquals(1, h.get(LinkContract.K_VERSION));
        assertEquals(1, h.get(LinkContract.K_MIN_CLIENT));
        assertEquals("createTask,listTasks,updateTask,completeTask,deleteTask,createEvent,listEvents", h.get(LinkContract.K_CAPS));
        assertEquals("0.1.8", h.get(LinkContract.K_APP_VERSION));
        JSONObject d = new JSONObject(String.valueOf(h.get(LinkContract.K_DESCRIPTOR)));
        assertEquals(7, d.getJSONArray("capabilities").length());
    }

    @Test public void descriptorIsOacpShaped() throws Exception {
        JSONObject d = LinkContract.descriptor(Contract.AUTHORITY);
        assertEquals("0.3", d.getString("oacpVersion"));
        assertEquals("com.onesay.plan", d.getString("appId"));
        JSONArray caps = d.getJSONArray("capabilities");
        for (int i = 0; i < caps.length(); i++) {
            JSONObject c = caps.getJSONObject(i);
            assertTrue(c.has("id") && c.has("description") && c.has("parameters") && c.has("completionMode"));
            JSONObject a = c.getJSONObject("invoke").getJSONObject("android");
            assertEquals("x-provider-call", a.getString("type"));
            assertEquals(Contract.AUTHORITY, a.getString("authority"));
            assertEquals(c.getString("id"), a.getString("method"));
        }
        assertEquals(1, d.getJSONObject("x-onesayLink").getInt("version"));
    }

    @Test public void manifestUsesAssistantPlaceholder() throws Exception {
        String m = src("app/AndroidManifest.xml");
        assertTrue(m.contains("<uses-permission android:name=\"${assistantAppId}.permission.CONTROL\" />"));
        assertTrue(m.contains("android:name=\"" + LinkContract.META_ASSISTANT + "\" android:value=\"${assistantAppId}\""));
        assertFalse("不写死助手包名", m.contains("android:name=\"com.onesay.assistant.permission.CONTROL\""));
        String sh = src("build_linux.sh");
        assertTrue(sh.contains("ASSISTANT_APP_ID=\"${ASSISTANT_APP_ID:-com.onesay.assistant.debug}\""));
        assertTrue(sh.contains("ASSISTANT_APP_ID=\"${ASSISTANT_APP_ID:-com.onesay.assistant}\""));
        assertTrue(sh.contains("--manifest \"$OUT/AndroidManifest.xml\""));
        assertTrue(sh.contains("Manifest 里还有没填的占位符"));
    }

    @Test public void providerRoutesLinkCallsAfterTheDoor() throws Exception {
        String p = src("app/src/com/onesay/plan/data/PlanProvider.java");
        int door = p.indexOf("Caller who = enforce();", p.indexOf("public Bundle call("));
        int link = p.indexOf("LinkContract.isLinkMethod(method)");
        assertTrue("门卫在前", door > 0 && link > door);
        String lc = src("app/src/com/onesay/plan/data/LinkCalls.java");
        assertTrue("写入都经 Store（校验 / 变更广播 / 提醒）", lc.contains("Store.insert(") && lc.contains("Store.update(") && lc.contains("Store.delete("));
        assertTrue("删待办连带它派生的提醒", lc.contains("todo_id=?"));
        assertFalse("不整表删", lc.contains("Store.delete(c, Contract.T_TODOS, null"));
    }
}
