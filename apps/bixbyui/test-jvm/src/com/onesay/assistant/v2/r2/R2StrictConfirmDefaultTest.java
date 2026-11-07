package com.onesay.assistant.v2.r2;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * r2/fixes 第 11 项：ExecutionGate 严格确认模式<b>默认开启</b>，不再依赖 FloatingSheetService.onCreate 才打开。
 * 其他单测会改 sStrictConfirm（静态），所以这里用隔离的类加载器重新加载一份 ExecutionGate，读它刚初始化的默认值；
 * 再加源码扫描兜底，防止有人把默认改回 false。
 */
public class R2StrictConfirmDefaultTest {

    static final String GATE = "com.onesay.assistant.v2.executor.ExecutionGate";

    /** 只对 ExecutionGate 及其内部类 child-first，其余类交给父加载器。 */
    static final class Isolated extends ClassLoader {
        Isolated(ClassLoader parent) { super(parent); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(GATE) && !name.startsWith(GATE + "$")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                        byte[] b = bo.toByteArray();
                        c = defineClass(name, b, 0, b.length);
                    } catch (Exception e) {
                        throw new ClassNotFoundException(name, e);
                    }
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }

    @Test public void freshClassDefaultsToStrict() throws Exception {
        Class<?> fresh = Class.forName(GATE, true, new Isolated(getClass().getClassLoader()));
        assertNotSame("必须是新加载的一份，静态状态没被其他单测动过", com.onesay.assistant.v2.executor.ExecutionGate.class, fresh);
        Method m = fresh.getMethod("isStrictConfirm");
        assertEquals("刚加载（相当于进程冷启动、服务还没起来）就是严格模式", Boolean.TRUE, m.invoke(null));
    }

    @Test public void sourceKeepsStrictDefault() throws Exception {
        File f = new File(System.getProperty("bixby.root", "."), "app/src/com/onesay/assistant/v2/executor/ExecutionGate.java");
        String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue(s.contains("private static volatile boolean sStrictConfirm = true;"));
        assertFalse(s.contains("sStrictConfirm = false;"));
        // 生产代码里没有任何地方把严格模式关掉
        File src = new File(System.getProperty("bixby.root", "."), "app/src");
        assertEquals(0, countDisable(src));
    }

    static int countDisable(File dir) throws Exception {
        int n = 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        for (File f : fs) {
            if (f.isDirectory()) n += countDisable(f);
            else if (f.getName().endsWith(".java")
                    && new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).contains("setStrictConfirm(false)")) n++;
        }
        return n;
    }
}
