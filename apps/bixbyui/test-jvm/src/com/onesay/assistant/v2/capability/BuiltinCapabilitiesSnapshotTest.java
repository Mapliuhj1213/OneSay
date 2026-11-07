package com.onesay.assistant.v2.capability;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.Assert.*;

/**
 * P1-15：BuiltinCapabilities 按领域拆文件必须是<b>纯机械搬移</b> ——
 * 注册顺序、数量、每条能力的全部字段（签名/摘要/关键词/Schema/命令/回读/说法/回复/风险…）逐字不变。
 *
 * <p>快照 {@code test-jvm/fixtures/builtin_caps_snapshot.txt} 在拆分<b>之前</b>由原始单文件生成并提交；
 * 之后任何改动能力表内容的提交都会让本测试失败（有意改能力时，用
 * {@code -Dbixby.updateSnapshot=true} 重新生成并在提交说明里写明原因）。
 */
public class BuiltinCapabilitiesSnapshotTest {

    static List<String> fingerprint() throws Exception {
        CapabilityRegistry reg = new CapabilityRegistry();
        BuiltinCapabilities.registerAll(reg);
        List<String> out = new ArrayList<String>();
        for (Capability c : reg.listCapabilities()) {
            out.add(c.sign + " | " + sha(full(c)));
        }
        return out;
    }

    static String full(Capability c) {
        Map<String, String> rm = c.replyMap == null ? null : new TreeMap<String, String>(c.replyMap);
        return c.sign + "\u0001" + c.summary + "\u0001" + c.category + "\u0001" + c.domain
                + "\u0001" + c.verbs + "\u0001" + c.entities + "\u0001" + c.examples + "\u0001" + c.keywords
                + "\u0001" + (c.paramsSchema == null ? "null" : c.paramsSchema.toJsonSchema().toString())
                + "\u0001" + c.risk + "\u0001" + c.confirm + "\u0001" + c.sideEffect + "\u0001" + c.executorType
                + "\u0001" + c.cmdTemplate + "\u0001" + c.checkCommand + "\u0001" + c.expectTemplate
                + "\u0001" + c.sayTemplates + "\u0001" + c.replyTemplate + "\u0001" + rm;
    }

    static String sha(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
        return sb.toString();
    }

    static File snapshotFile() {
        return new File(System.getProperty("bixby.root", "."), "test-jvm/fixtures/builtin_caps_snapshot.txt");
    }

    @Test public void registryIsIdenticalToSnapshot() throws Exception {
        List<String> now = fingerprint();
        File f = snapshotFile();
        if ("true".equals(System.getProperty("bixby.updateSnapshot"))) {
            StringBuilder sb = new StringBuilder();
            sb.append("# BuiltinCapabilities 注册快照（顺序 | 全字段 SHA-256 前 8 字节）。共 ").append(now.size()).append(" 条\n");
            for (String l : now) sb.append(l).append('\n');
            Files.write(f.toPath(), sb.toString().getBytes(Charset.forName("UTF-8")));
            return;
        }
        assertTrue("缺少快照文件 " + f, f.exists());
        List<String> snap = new ArrayList<String>();
        for (String l : new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8")).split("\n")) {
            if (l.length() > 0 && !l.startsWith("#")) snap.add(l);
        }
        assertEquals("能力条数", snap.size(), now.size());
        for (int i = 0; i < snap.size(); i++) {
            assertEquals("第 " + (i + 1) + " 条（顺序与全部字段）", snap.get(i), now.get(i));
        }
    }

    @Test public void signsAreUnique() throws Exception {
        Set<String> seen = new HashSet<String>();
        for (String l : fingerprint()) {
            String sign = l.substring(0, l.indexOf(' '));
            assertTrue("重复签名 " + sign, seen.add(sign));
        }
    }
}
