package com.onesay.assistant.v2.shortcut;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.capability.Capability;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 5a5015d 回归：快捷指令动作查找用<b>真实</b>的宿主 lookup（{@link ShortcutHooks#ports} → CapabilityRegistry +
 * FeatureKernel.get() 内置插件 + 内部动作），不用假 lookup。2026-11-06 真机冒烟发现「加待办」被判「没有这个工具：s3.todo.add」——
 * 原因是 lookup 漏查 FeatureKernel.describe()（S3 插件只实现 DESCRIBE 钩子），而旧单测的假 lookup 什么都认，没拦住。
 */
public class ShortcutLookupTest {

    static Capability realLookup(String sign) {
        return ShortcutHooks.ports(null, null).lookup(sign);
    }

    @Test public void pluginToolsResolveThroughTheRealHostLookup() {
        Capability c = realLookup("s3.todo.add");
        assertNotNull("s3.todo.add 要能经真实 lookup 查到（S3 插件走 FeatureKernel.describe）", c);
        assertEquals("s3.todo.add", c.sign);
    }

    @Test public void everyCatalogActionExceptNotBuiltIsKnownToTheRealLookup() throws Exception {
        for (ShortcutActions.Action a : ShortcutActions.all()) {
            if (a.notBuilt != null) continue;
            assertNotNull("动作清单里的「" + a.label + "」（" + a.sign + "）在真实 lookup 里查不到", realLookup(a.sign));
        }
    }

    @Test public void catalogNeverSaysNoSuchToolWithTheRealLookup() throws Exception {
        String json = ShortcutActions.catalogJson(new ShortcutActions.Lookup() {
            @Override public Capability lookup(String sign) {
                return realLookup(sign);
            }
        }, null, true);
        assertFalse("上机版动作清单里不该出现「没有这个工具」：" + json, json.contains("没有这个工具"));
        JSONArray arr = json.trim().startsWith("[") ? new JSONArray(json) : new JSONObject(json).optJSONArray("actions");
        assertNotNull(arr);
        boolean seen = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if ("s3.todo.add".equals(o.optString("sign"))) {
                seen = true;
                assertTrue("加待办在上机版清单里应可用: " + o, o.getBoolean("available"));
            }
        }
        assertTrue("清单里要有加待办", seen);
    }

    @Test public void unknownSignStaysUnknown() {
        assertNull(realLookup("no.such.tool.zzz"));
    }
}
