package com.onesay.assistant.v2.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import com.onesay.assistant.v2.island.IslandModel;

/**
 * [island v3 / s3] 验收脚本 island / s3 模块的守卫：
 * <ul>
 *   <li>结构：模块存在、可分发、不再碰无障碍、全屏用 policy_control immersive.full=* 且跑完还原、kill-switch、s3 模块的用例；</li>
 *   <li><b>每条 il_step 的正则</b>都用「与 IslandController.apply / demo 同口径拼出来的日志」（真 IslandModel 驱动）逐条实测，
 *       前置（il_fresh）在模型上真能到 —— 防止 round5 那种脚本与日志口径不一致的假 FAIL。</li>
 * </ul>
 */
public class AcceptanceScriptIslandTest {

    static String src(String rel) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        return new String(Files.readAllBytes(new File(root, rel).toPath()), StandardCharsets.UTF_8);
    }

    static String section(String s, String start, String end) {
        int a = s.indexOf(start);
        int b = s.indexOf(end, a + 1);
        return s.substring(a, b);
    }

    @Test public void islandModuleIsV3AndHasNoAccessibility() throws Exception {
        String s = src("acceptance/device_acceptance.sh");
        assertTrue(s.contains("cmd_island() {"));
        assertTrue(s.contains("  island)    cmd_island ;;"));
        assertTrue(s.contains("  s3)        cmd_s3 ;;"));
        assertTrue("日志 tag", s.contains("Island:V IslandTestDriver:V S3:V S3TestDriver:V"));
        String il = section(s, "# ----------------------------------------------------------------------------- island（灵动岛 v3", "# ----------------------------------------------------------------------------- s3（");
        assertFalse("不再依赖无障碍", il.contains("settings put secure enabled_accessibility_services"));
        assertFalse(il.contains("il_enable_adb"));
        assertTrue("IL-0 悬浮窗", il.contains("appops get $PKG SYSTEM_ALERT_WINDOW"));
        assertTrue("IL-1 没有无障碍", il.contains("enabled_accessibility_services | grep -q \"$PKG/\""));
        assertTrue("几何", il.contains("il_geom IL-2"));
        assertTrue("全屏", il.contains("policy_control immersive.full=*"));
        assertTrue("全屏跑完还原", il.contains("settings delete global policy_control") && il.contains("settings put global policy_control '$pc0'"));
        for (String id : new String[]{"IL-F1", "IL-F2", "IL-F3", "IL-18b", "IL-19b", "IL-R1", "IL-R4", "IL-22b", "IL-24"}) {
            assertTrue(id, il.contains(id));
        }
        assertTrue("kill-switch", il.contains("touch $IL_KILL"));
        assertTrue("旋转", il.contains("settings put system user_rotation 1"));
        String s3 = section(s, "# ----------------------------------------------------------------------------- s3（", "\nusage() {");
        assertTrue(s3.contains("s3cmd remind_in \"验收提醒\" \"sec=120\""));
        assertTrue("重启 = MANUAL", s3.contains("ask S3-6"));
        assertTrue("Doze", s3.contains("dumpsys deviceidle force-idle") && s3.contains("dumpsys deviceidle unforce"));
        assertTrue("只清自己建的", s3.contains("s3cmd cleanup") && !s3.contains("s3cmd wipe"));
        String readme = src("acceptance/README.md");
        assertTrue(readme.contains("### island（灵动岛 v3"));
        assertTrue(readme.contains("### s3（定时任务"));
    }

    // ================================================================== 脚本解析

    private static final Pattern IL_STEP = Pattern.compile("il_step (IL-[A-Za-z0-9]+)\\s+(\\w+)\\s+(\\S+)\\s+([0-9.]+)\\s+\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern IL_FRESH = Pattern.compile("(?:IL_GAP=(\\S+) )?il_fresh (IL-[A-Za-z0-9]+) \"((?:[^\"\\\\]|\\\\.)*)\"([a-z ]*)");

    /** grep -E → java.util.regex：只有「[^]]」写法不同；bash 双引号里的 \\( 已是 \( 。 */
    static String ere(String re) {
        return re.replace("[^]]", "[^\\]]");
    }

    @Test public void everyIlStepRegexMatchesSimulatedLog() throws Exception {
        String s = src("acceptance/device_acceptance.sh");
        String il = section(s, "cmd_island() {", "\n# ----------------------------------------------------------------------------- s3（");
        Map<String, Object[]> fresh = new LinkedHashMap<String, Object[]>();
        Matcher f = IL_FRESH.matcher(il);
        while (f.find()) {
            String rest = f.group(4).trim();
            fresh.put(f.group(2), new Object[]{f.group(3), rest.isEmpty() ? new String[0] : rest.split("\\s+"),
                    f.group(1) == null ? 1.2 : Double.parseDouble(f.group(1))});
        }
        Matcher m = IL_STEP.matcher(il);
        int n = 0;
        StringBuilder bad = new StringBuilder();
        while (m.find()) {
            String id = m.group(1), sub = m.group(2), re = ere(m.group(5));
            double wait = Double.parseDouble(m.group(4));
            Sim sim = new Sim();
            String base = id.replaceAll("[a-z]$", "");
            Object[] pre = fresh.get(base);
            if (pre != null) {
                sim.reset();
                for (String c : (String[]) pre[1]) sim.cmd(c, null, (Double) pre[2]);
                String v = sim.view();
                if (!Pattern.compile((String) pre[0]).matcher(v).find()) {
                    bad.append(id).append(" 前置到不了：期望 /").append(pre[0]).append("/ 实际 ").append(v).append('\n');
                    continue;
                }
            }
            if (id.equals("IL-19c")) {   // 先 tuck 再 key（同一个前置）
                sim.cmd("tuck", null, 1);
            }
            sim.log.setLength(0);
            sim.cmd(sub, null, wait);
            if (!Pattern.compile(re).matcher(sim.log).find()) {
                bad.append(id).append(" /").append(re).append("/ 不匹配：\n").append(sim.log).append('\n');
            }
            n++;
        }
        assertTrue("至少 18 条 il_step（实际 " + n + "）", n >= 18);
        assertEquals(bad.toString(), 0, bad.length());
    }

    @Test public void timedFollowUpsMatchSimulatedLog() {
        // IL-6 轻提醒 2.4 s、IL-7 关键提醒 4 s、IL-10b 完成 3 s、IL-11b 失败 6 s、IL-16b 复制 1.5 s：脚本里的等待都比定时器长
        Sim a = new Sim();
        a.cmd("run", null, 1.2);
        a.log.setLength(0);
        a.cmd("step", null, 3.2);
        assertTrue(a.log.toString(), Pattern.compile("demo-step → \\[RENDER\\].*view=PULSE_STEP").matcher(a.log).find());
        assertTrue(a.log.toString(), Pattern.compile("pulse#[0-9]+ → \\[RENDER\\].*view=COMPACT_TASK").matcher(a.log).find());
        a.log.setLength(0);
        a.cmd("key", null, 4.8);
        assertTrue(a.log.toString(), Pattern.compile("key#[0-9]+ → \\[RENDER\\].*view=COMPACT_TASK").matcher(a.log).find());
        Sim d = new Sim();
        d.cmd("done", null, 3.8);
        assertTrue(d.log.toString(), Pattern.compile("doneHide#[0-9]+ → \\[RENDER\\].*view=HIDDEN").matcher(d.log).find());
        Sim x = new Sim();
        x.cmd("fail", null, 7);
        assertTrue(x.log.toString(), Pattern.compile("failCollapse#[0-9]+ → \\[RENDER\\].*view=COMPACT_TASK\\(TASK\\) status=FAIL").matcher(x.log).find());
        Sim w = new Sim();
        w.cmd("run", null, 1.2);
        w.cmd("wait", null, 0.5);
        w.cmd("outside", null, 1);
        w.cmd("tuck", null, 1);
        assertTrue("IL-8：确认卡点外部 / 横滑都收不起", w.view().startsWith("EXP_CONFIRM"));
        Sim r = new Sim();
        r.cmd("remind", null, 1);
        r.log.setLength(0);
        r.cmd("rdone", null, 2);
        assertTrue(r.log.toString(), r.log.toString().contains("demo-remindDone → [REMIND_DONE, RENDER]"));
    }

    @Test public void statusLineFormatIsWhatIlViewParses() throws Exception {
        String ctl = src("app/src/com/onesay/assistant/v2/s1/IslandController.java");
        assertTrue("il_view 用 view= … fullscreen= 截取", ctl.contains("\" view=\" + mModel.view() + \" fullscreen=\""));
        assertTrue(ctl.contains("Log.i(TAG, \"[ISLAND] \" + why + \" → \" + effects + \" mode=\" + mModel.mode() + \" sheet=\" + mModel.sheet()"));
        assertTrue(ctl.contains("+ \" view=\" + mModel.view() + (mDemo ? \" (demo)\" : \"\"));"));
        assertTrue("定时器日志 name#seq", ctl.contains("t.name + \"#\" + t.seq"));
        assertTrue("geom 格式", ctl.contains("\"screen=\" + p.x + \"x\" + p.y + \" density=\""));
        assertTrue(ctl.contains("\" anchor{\" + a + \"} win{\""));
        assertTrue(ctl.contains("\" shape=\" + mIsland.shapeOnScreen().toShortString()"));
        assertTrue("全屏日志", ctl.contains("\"[ISLAND-FS] \" + (fs ? \"进入\" : \"退出\") + \"全屏（\""));
        assertTrue(ctl.contains("\"swipeSide→tuck\""));
        assertTrue(ctl.contains("\"tap(\" + key + \")\""));
        assertEquals("cx=540 top=68 statusBar=63", com.onesay.assistant.v2.island.IslandGeometry.anchor(1080, 2.625f, 63, false).toString());
    }

    // ================================================================== IslandController.demo / apply 同口径模拟器

    static final class Sim {
        final IslandModel m = new IslandModel();
        final StringBuilder log = new StringBuilder();
        boolean demo;
        final List<IslandModel.Pickup> pickups = new ArrayList<IslandModel.Pickup>();
        final List<Object[]> timers = new ArrayList<Object[]>();
        double clock;

        Sim() {
            apply(m.setMode(IslandModel.Mode.ISLAND), "mode→ISLAND");
        }

        String view() {
            return m.view().toString();
        }

        void apply(List<IslandModel.Effect> effects, String why) {
            if (effects == null || effects.isEmpty()) {
                drain();
                return;
            }
            log.append("I/Island( 1): [ISLAND] ").append(why).append(" → ").append(effects).append(" mode=").append(m.mode())
                    .append(" sheet=").append(m.sheet()).append(" view=").append(m.view()).append(demo ? " (demo)" : "").append('\n');
            for (IslandModel.Effect e : effects) {
                switch (e) {
                    case CANCEL_TASK:
                    case END_SESSION:
                    case RETRY_TASK:
                        demo = false;
                        break;
                    case RESOLVE_CONFIRM_ALLOW:
                    case RESOLVE_CONFIRM_DENY:
                        apply(m.confirmResolved(), "demoConfirm");
                        break;
                    case BG_CONFIRM_ALLOW:
                    case BG_CONFIRM_DENY:
                        apply(m.bgConfirmResolved(m.lastTarget()), "demoBgConfirm");
                        break;
                    case COPY_PICKUP:
                        apply(m.markCopied(m.currentPickupId()), "copied");
                        break;
                    default:
                        break;
                }
            }
            drain();
        }

        void drain() {
            for (IslandModel.Timer t : m.drainTimers()) timers.add(new Object[]{t, Double.valueOf(clock + t.delayMs / 1000.0)});
        }

        /** 时钟前进 sec 秒：到点的定时器按 IslandController.scheduleTimers 的口径执行。 */
        void advance(double sec) {
            double until = clock + sec;
            boolean fired = true;
            while (fired) {
                fired = false;
                Object[] bestE = null;
                double bestAt = 0;
                for (Object[] e : timers) {
                    double at = (Double) e[1];
                    if (at <= until && (bestE == null || at < bestAt)) {
                        bestE = e;
                        bestAt = at;
                    }
                }
                if (bestE != null) {
                    timers.remove(bestE);
                    IslandModel.Timer best = (IslandModel.Timer) bestE[0];
                    clock = bestAt;
                    List<IslandModel.Effect> r = m.timerDue(best.name, best.seq, best.target);
                    if ("doneHide".equals(best.name) && !r.isEmpty()) demo = false;
                    apply(r, best.name + "#" + best.seq + (best.target != null ? "(" + best.target + ")" : ""));
                    fired = true;
                }
            }
            clock = until;
        }

        void reset() {
            boolean was = demo;
            demo = true;
            if (m.isCollapsed() || m.isRunning()) apply(m.stop(), "demo-reset");
            demo = false;
            apply(m.removeBg("demo-r1"), "demo-reset");
            apply(m.removeBg("demo-bg1"), "demo-reset");
            pickups.clear();
            apply(m.setPickups(new ArrayList<IslandModel.Pickup>(pickups)), "pickups(clear)");
            if (was) log.append("[ISLAND] demo 已复位\n");
            advance(1);
        }

        void startDemo(String title) {
            demo = true;
            m.configure(true, false);
            apply(m.taskStarted(0L, title != null ? title : "打车去公司，到了告诉妈妈一声"), "demo-taskStarted");
            apply(m.step("打开滴滴出行"), "demo-step");
            apply(m.step("设置目的地：公司"), "demo-step");
            apply(m.step("叫车并等待接单"), "demo-step");
            apply(m.requestCollapse(IslandModel.CollapseReason.USER_BUTTON), "demo-collapse");
        }

        void sms(String id, String code) {
            pickups.add(new IslandModel.Pickup(id, "取件码", code, "驿站", "短信", 1L, Long.MAX_VALUE / 4));
            apply(m.setPickups(new ArrayList<IslandModel.Pickup>(pickups)), "pickups(sms)");
        }

        void cmd(String cmd, String text, double wait) {
            if ("run".equals(cmd) || "dual".equals(cmd)) {
                startDemo(text);
                if ("dual".equals(cmd)) {
                    sms("p1", "5-2-1234");
                    sms("p2", "83920417");
                    sms("p3", "A123");
                }
            } else if ("wait".equals(cmd)) {
                if (!m.isCollapsed() || !m.isRunning()) startDemo(text);
                apply(m.step("发送短信给 妈妈"), "demo-step");
                apply(m.confirmRequired("发送短信给 妈妈", "我打车去公司了"), "demo-confirmRequired");
            } else if ("allow".equals(cmd) || "deny".equals(cmd)) {
                apply(m.islandConfirm("allow".equals(cmd)), "demo-" + cmd);
                if (m.isConfirmPending()) apply(m.confirmResolved(), "demo-resolved");
            } else if ("step".equals(cmd)) {
                if (!m.isCollapsed() || !m.isRunning()) startDemo(null);
                apply(m.step(text != null ? text : "确认上车点"), "demo-step");
            } else if ("key".equals(cmd)) {
                if (!m.isCollapsed() || !m.isRunning()) startDemo(null);
                apply(m.keyUpdate(text != null ? text : "司机已接单"), "demo-key");
            } else if ("done".equals(cmd)) {
                if (!m.isCollapsed()) startDemo(text);
                apply(m.taskEnded(false, false, true, false, null, 1000L), "demo-done");
            } else if ("fail".equals(cmd)) {
                if (!m.isCollapsed()) startDemo(text);
                apply(m.taskEnded(false, false, false, false, "叫车超时", 1000L), "demo-fail");
            } else if ("pickup".equals(cmd)) {
                sms("p1", "5-2-1234");
                sms("p2", "83920417");
                sms("p3", "A123");
            } else if ("longpress".equals(cmd) || "expand".equals(cmd)) {
                apply(m.longPress(), "demo-longPress");
            } else if ("tap".equals(cmd)) {
                apply(m.tapMain(), "demo-tap");
            } else if ("collapse".equals(cmd)) {
                apply(m.collapseExpanded(IslandModel.CollapseReason.SWIPE_UP), "demo-collapse");
            } else if ("outside".equals(cmd)) {
                apply(m.collapseExpanded(IslandModel.CollapseReason.OUTSIDE_TAP), "demo-outside");
            } else if ("tuck".equals(cmd)) {
                apply(m.tuck(), "demo-tuck");
            } else if ("copy".equals(cmd)) {
                if (m.view().kind != IslandModel.Kind.EXP_PICKUP) apply(m.longPress(IslandModel.K_PICKUP), "demo-longPress");
                apply(m.copyPickup(), "demo-copy");
            } else if ("prev".equals(cmd) || "next".equals(cmd)) {
                apply(m.pagePickup("next".equals(cmd) ? 1 : -1), "demo-" + cmd);
            } else if ("close".equals(cmd)) {
                apply(m.closeFailed(), "demo-close");
            } else if ("remind".equals(cmd)) {
                apply(m.remindFired("demo-r1", "交周报", 1000L, 0, 1000L), "demo-remind");
            } else if ("remindlate".equals(cmd)) {
                apply(m.remindFired("demo-r1", "取快递", 1000L, 25, 1000L), "demo-remind");
            } else if ("rdone".equals(cmd)) {
                apply(m.remindDone(null), "demo-remindDone");
            } else if ("bg".equals(cmd)) {
                apply(m.bgStarted("demo-bg1", "读一下QQ邮箱未读", 1000L), "demo-bgStarted");
                apply(m.bgStep("demo-bg1", "读取邮件"), "demo-bgStep");
            } else if ("bgwait".equals(cmd)) {
                if (!m.hasBg("demo-bg1")) apply(m.bgStarted("demo-bg1", "读一下QQ邮箱未读", 1000L), "demo-bgStarted");
                apply(m.bgConfirm("demo-bg1", "发送短信给 妈妈", "邮件摘要已读完"), "demo-bgConfirm");
            } else if ("bgdone".equals(cmd) || "bgfail".equals(cmd)) {
                apply(m.bgEnded("demo-bg1", "bgdone".equals(cmd), "确认超时，已安全中止", 2000L), "demo-" + cmd);
            } else if ("reset".equals(cmd)) {
                reset();
                return;
            } else {
                throw new AssertionError("模拟器不认识子命令 " + cmd);
            }
            log.append("I/IslandTestDriver( 1): [ISLAND-TEST] ").append(cmd).append(" → mode=").append(m.mode()).append(" view=")
                    .append(m.view()).append(" fullscreen=").append(m.isFullscreen()).append('\n');
            advance(wait);
        }
    }

    @Test public void simulatorCoversAllIlStepSubcommands() throws Exception {
        String s = src("acceptance/device_acceptance.sh");
        String il = section(s, "cmd_island() {", "\n# ----------------------------------------------------------------------------- s3（");
        Matcher m = IL_STEP.matcher(il);
        List<String> subs = new ArrayList<String>();
        while (m.find()) if (!subs.contains(m.group(2))) subs.add(m.group(2));
        for (String sub : subs) {
            Sim x = new Sim();
            x.cmd(sub, null, 0);   // 不抛 = 模拟器认识
        }
        assertTrue(subs.containsAll(Arrays.asList("run", "longpress", "collapse", "allow", "done", "fail", "close", "pickup", "prev", "copy",
                "dual", "tap", "tuck", "key", "remind", "remindlate", "bg", "bgwait", "bgfail")));
    }
}
