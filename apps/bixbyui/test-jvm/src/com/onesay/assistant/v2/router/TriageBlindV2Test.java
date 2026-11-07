package com.onesay.assistant.v2.router;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 分诊盲测集 v2（2026-10-09 第 4 阶段）。
 *
 * <p>30 条句子在修 D-3b 四条错判、动任何分诊规则<b>之前</b>写好并冻结（fixtures/triage/blind_v2.txt）。
 * 本测试每次都校验文件 SHA-256 与条数（防止有人改了句子再“盲测”），
 * 但<b>只有显式要求时才真正跑分</b>：{@code JAVA_OPTS=-Dbixby.blindv2=run bash run_unit_tests.sh TriageBlindV2Test}。
 * 结果只打印、不设门槛 —— 盲测的价值在于没有据此调过规则。第一次（也是冻结后唯一一次调参前后的）结果。
 * 以后补句子：另起 blind_v3.txt 并在这里加一个冻结常量，不要改 v2。
 */
public class TriageBlindV2Test {

    static final String FROZEN_SHA256 = "874b86787d6a4f780b6969698bb5de9b62dd50af354559101bfb8ec28bd5e821";

    static File file() {
        return new File(System.getProperty("bixby.root", "."), "test-jvm/fixtures/triage/blind_v2.txt");
    }

    static List<String[]> cases() throws Exception {
        List<String[]> out = new ArrayList<String[]>();
        for (String line : Files.readAllLines(file().toPath(), StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            String[] kv = line.split("\t");
            out.add(new String[]{kv[0].trim(), kv[1].trim()});
        }
        return out;
    }

    static String sha256(File f) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath()));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    @Test public void frozenFileUnchanged() throws Exception {
        assertEquals("盲测集 v2 已冻结，不能改（补句子请另起 v3）", FROZEN_SHA256, sha256(file()));
        List<String[]> c = cases();
        assertEquals(30, c.size());
        for (String[] kv : c) {
            assertNotNull(kv[0], TriageDecider.Label.valueOf(kv[1]));
        }
    }

    @Test public void reportOnlyWhenAsked() throws Exception {
        if (!"run".equals(System.getProperty("bixby.blindv2"))) return;
        StringBuilder fails = new StringBuilder();
        List<String[]> c = cases();
        int pass = TriageDeciderTest.run(c.toArray(new String[0][]), fails);
        System.out.println("[TRIAGE] 盲测 v2 " + pass + "/" + c.size()
                + (fails.length() > 0 ? "\n失败：\n" + fails : ""));
    }
}
