package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.BuiltinCapabilities;
import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * R15-2：把「LLM 给的参数 → shell」这条链从「抽查几个模板」扩成「整张能力表 × 一组恶意载荷」。
 *
 * <p>背景（调研 2026-10-17）：2026 年 mobilerun（CWE-78）、microsoft/UFO Mobile MCP（type_text / launch_app）、
 * XcodeBuildMCP 都出过「模型输出的字符串被设备 shell 二次解析」的洞——共同教训是：不要只测一两个工具，
 * 每个会拼命令的入口都要有同一套载荷回归。
 *
 * <p>做法：对 {@link BuiltinCapabilities} 里每条带命令模板（cmd / check）的能力，把<b>所有</b>占位符都填成同一个恶意载荷，
 * 交给真实 sh 执行，但 PATH 置空（所有外部命令都「找不到」，不会真的改任何东西），仅定义一个函数 {@code pwn}，
 * 载荷里写的是「调用 pwn」。只要 pwn 被执行（写出标记文件），就说明载荷逃出了参数位置、被当成命令。
 * 同时用 {@code sh -n} 检查填充结果语法合法（引号没被载荷破坏）。
 * {@code :i}（整数）占位符对非整数载荷抛 IllegalArgumentException 视为「被拒绝」，同样安全。
 */
public class CapabilityInjectionCorpusTest {

    static final String[] PAYLOADS = {
            "; pwn ;",
            "$(pwn)",
            "`pwn`",
            "' ; pwn ; '",
            "\" ; pwn ; \"",
            "a && pwn",
            "a | pwn",
            "a\npwn",
            "x' $(pwn) '",
            "\\'; pwn #",
            "${IFS}pwn",
            "&pwn&",
            "> /dev/null; pwn",
            "苏州 $(pwn) 园林",
    };

    private static final Pattern PH = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)(?:[=:][^}]*)?\\}");

    private static Set<String> names(String tpl) {
        Set<String> s = new LinkedHashSet<String>();
        if (tpl == null) return s;
        Matcher m = PH.matcher(tpl);
        while (m.find()) s.add(m.group(1));
        return s;
    }

    private static boolean runs(String script, File marker) throws Exception {
        String wrapped = "pwn() { echo x >> '" + marker.getPath() + "'; }\nPATH=\n" + script + "\n";
        Process p = new ProcessBuilder("/bin/sh", "-c", wrapped).redirectErrorStream(true).start();
        java.io.InputStream in = p.getInputStream();
        byte[] buf = new byte[4096];
        while (in.read(buf) > 0) { /* 丢弃 */ }
        p.waitFor();
        return marker.exists();
    }

    private static boolean syntaxOk(String script) throws Exception {
        Process p = new ProcessBuilder("/bin/sh", "-n", "-c", script).redirectErrorStream(true).start();
        java.io.InputStream in = p.getInputStream();
        byte[] buf = new byte[4096];
        while (in.read(buf) > 0) { /* 丢弃 */ }
        return p.waitFor() == 0;
    }

    @Test public void everyCapabilityTemplateSurvivesHostilePayloads() throws Exception {
        CapabilityRegistry reg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(reg);
        File marker = File.createTempFile("pwn_reg", ".flag");
        marker.delete();
        int templates = 0, withSlots = 0, rejectedByInt = 0;
        List<String> bad = new ArrayList<String>();
        for (Capability c : reg.listCapabilities()) {
            for (String tpl : new String[]{c.cmdTemplate, c.checkCommand}) {
                if (tpl == null || tpl.length() == 0) continue;
                templates++;
                Set<String> ns = names(tpl);
                if (ns.isEmpty()) continue;
                withSlots++;
                for (String payload : PAYLOADS) {
                    Map<String, String> vals = new HashMap<String, String>();
                    for (String n : ns) vals.put(n, payload);
                    String filled;
                    try {
                        filled = ShellCommand.fill(tpl, vals);
                    } catch (IllegalArgumentException e) {
                        rejectedByInt++;
                        continue;
                    }
                    if (!syntaxOk(filled)) { bad.add(c.sign + " 语法被破坏 <- " + payload); continue; }
                    marker.delete();
                    if (runs(filled, marker)) bad.add(c.sign + " 载荷被执行 <- " + payload + " :: " + filled);
                    marker.delete();
                }
            }
        }
        assertTrue("能力表里应有带命令模板的能力（实际 " + templates + "）", templates > 20);
        assertTrue("应有带占位符的模板（实际 " + withSlots + "）", withSlots > 5);
        assertTrue("存在可被注入的模板: " + bad, bad.isEmpty());
        System.out.println("[CapabilityInjectionCorpus] 模板 " + templates + " 条，带占位符 " + withSlots
                + " 条，载荷 " + PAYLOADS.length + " 种，整数槽拒绝 " + rejectedByInt + " 次，0 条逃逸");
    }
}
