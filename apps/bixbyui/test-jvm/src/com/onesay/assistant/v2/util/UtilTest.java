package com.onesay.assistant.v2.util;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class UtilTest {

    @Test public void readAllUtf8AndNull() throws Exception {
        assertEquals("你好 bixby", IoUtil.readAll(new ByteArrayInputStream("你好 bixby".getBytes("UTF-8"))));
        assertEquals("", IoUtil.readAll(null));
        assertEquals("", IoUtil.readAllQuietly(null));
    }

    @Test public void fileRoundTripAtomic() throws Exception {
        File dir = Files.createTempDirectory("ioutil").toFile();
        File f = new File(dir, "consent_policy.json");
        assertNull(IoUtil.readFileOrNull(f));
        assertTrue(IoUtil.writeFileAtomic(f, "{\"always_allow\":[\"sys.reboot\"]}"));
        assertEquals("{\"always_allow\":[\"sys.reboot\"]}", IoUtil.readFileOrNull(f));
        assertTrue(IoUtil.writeFileAtomic(f, "覆盖"));
        assertEquals("覆盖", IoUtil.readFileOrNull(f));
        assertFalse("tmp 文件应被清理", new File(dir, "consent_policy.json.tmp").exists());
    }

    @Test public void textHelpers() {
        assertEquals("abc...", TextUtil.truncate("abcdef", 3));
        assertEquals("abc", TextUtil.truncate("abc", 3));
        assertEquals("", TextUtil.truncate(null, 3));
        assertEquals("a b…", TextUtil.oneLine("a\nb\r\nc", 3, "…", "-"));
        assertEquals("-", TextUtil.oneLine(null, 3, "…", "-"));
        assertEquals("标题 粗体 link", TextUtil.cleanMarkdown("# 标题 **粗体** [link]"));
        assertTrue(TextUtil.isBlank("  "));
        assertEquals("", TextUtil.nz(null));
    }

    @Test public void sharedPoolRunsNamedAndBounded() throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> name = new AtomicReference<String>();
        Threads.bg("unit", new Runnable() {
            @Override public void run() {
                name.set(Thread.currentThread().getName());
                done.countDown();
            }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(name.get(), name.get().startsWith("bixby-bg-") && name.get().endsWith(":unit"));
        assertSame(Threads.shared(), Threads.shared());
    }

    @Test public void pcmClip() {
        byte[] three = new byte[16000 * 2 * 3];
        assertEquals(32000, PcmUtil.bytesPerSecond(16000));
        assertTrue(PcmUtil.exceeds(three, 16000, 2));
        assertFalse(PcmUtil.exceeds(three, 16000, 3));
        assertEquals(64000, PcmUtil.clip(three, 16000, 2).length);
        assertSame("未超出不拷贝", three, PcmUtil.clip(three, 16000, 60));
        assertEquals(3, PcmUtil.seconds(three, 16000));
        assertEquals(0, PcmUtil.clip(null, 16000, 1).length);
    }
}
