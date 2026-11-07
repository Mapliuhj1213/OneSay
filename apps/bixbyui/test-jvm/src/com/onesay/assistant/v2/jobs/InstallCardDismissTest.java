package com.onesay.assistant.v2.jobs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.roles.InstallCardState;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

/** 安装卡「不用了」按钮 + 卡在屏时「不用了 / 算了 / 不装了」关卡并取消 InstallWait 登记。 */
public class InstallCardDismissTest {

    @After public void clear() {
        InstallCardState.gone(InstallCardState.KIND_PROMPT);
        InstallCardState.gone(InstallCardState.KIND_GRID);
    }

    @Test public void dismissWords() {
        for (String q : new String[]{"不用了", "算了", "不装了", "算了吧", "不用了谢谢", "那就算了", "不要了", "先不装了", "取消", "不安装", "不下载了。"}) {
            assertTrue(q, InstallCardState.isDismissWord(q));
        }
        for (String q : new String[]{"算了，打开微信", "不用了帮我打开淘宝", "打开闲鱼", "不用提醒我了", "不要打开蓝牙", ""}) {
            assertFalse(q, InstallCardState.isDismissWord(q));
        }
    }

    @Test public void onlyWhenCardShowing() {
        assertFalse(InstallCardState.wantsDismiss("不用了"));
        InstallCardState.shown(InstallCardState.KIND_PROMPT, Collections.singletonList("com.taobao.idlefish"));
        assertTrue(InstallCardState.wantsDismiss("不用了"));
        assertTrue(InstallCardState.wantsDismiss("不装了"));
        assertFalse(InstallCardState.wantsDismiss("打开微信"));
        InstallCardState.gone(InstallCardState.KIND_PROMPT);
        assertFalse(InstallCardState.wantsDismiss("算了"));
        InstallCardState.shown(InstallCardState.KIND_GRID, Arrays.asList("com.taobao.idlefish", "com.wuba.zhuanzhuan"));
        assertTrue(InstallCardState.wantsDismiss("算了"));
        assertEquals(Arrays.asList("com.taobao.idlefish", "com.wuba.zhuanzhuan"), InstallCardState.pkgs());
    }

    @Test public void cancelsMatchingWaits() {
        InstallWait w = new InstallWait();
        long now = 1000000L;
        w.register("打开闲鱼", "打开闲鱼", "", Arrays.asList("com.taobao.idlefish", "com.wuba.zhuanzhuan"), Arrays.asList("闲鱼", "转转"), now);
        w.register("打开抖音", "打开抖音", "", Collections.singletonList("com.ss.android.ugc.aweme"), Collections.singletonList("抖音"), now);
        assertEquals(1, w.cancelPkgs(Collections.singletonList("com.wuba.zhuanzhuan")));
        assertEquals(1, w.active().size());
        assertEquals("打开抖音", w.active().get(0).utterance);
        assertEquals(0, w.cancelPkgs(Collections.<String>emptyList()));
        assertEquals(0, w.cancelPkgs(null));
    }

    static String src(String rel) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        return new String(Files.readAllBytes(new File(root, "app/src/com/onesay/assistant/v2/" + rel).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void wiring() throws Exception {
        String v = src("ui/InstallPromptView.java");
        assertTrue(v.contains("DISMISS_TEXT = \"不用了\"") && v.contains("interface DismissListener") && v.contains("InstallCardState.shown"));
        String r = src("roles/RoleHooks.java");
        assertTrue(r.contains("InstallPromptView.DismissListener") && r.contains("InstallWaitHooks.cancelFor(svc"));
        assertTrue(r.contains("public static Invocation routeDismiss"));
        String p = src("kernel/plugins/RolesPlugin.java");
        assertTrue(p.contains("case TAKEOVER: return 50;") && p.contains("RoleHooks.routeDismiss"));
        String g = src("ui/ChoiceGridView.java");
        assertTrue(g.contains("InstallCardState.KIND_GRID"));
    }
}
