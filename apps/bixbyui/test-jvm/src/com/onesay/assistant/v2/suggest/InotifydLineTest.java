package com.onesay.assistant.v2.suggest;

import com.onesay.assistant.v2.suggest.sources.InotifydLine;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ feat/suggest：toybox / busybox inotifyd 输出行解析（三种格式；认 n / w / y——fix/shot-1104 起 n 也认；认不出返回 null）。 */
public class InotifydLineTest {

    private static void ok(String line, String ev, String path) {
        String[] r = InotifydLine.parse(line);
        assertNotNull(line, r);
        assertEquals(ev, r[0]);
        assertEquals(path, r[1]);
    }

    @Test public void toyboxTabSeparated() {
        ok("w\t/storage/emulated/0/DCIM/Screenshots\tScreenshot_20261102.png", "w", "/storage/emulated/0/DCIM/Screenshots/Screenshot_20261102.png");
        ok("y\t/sdcard/Pictures/Screenshots/\ta b.jpg\n", "y", "/sdcard/Pictures/Screenshots/a b.jpg");
    }

    @Test public void busyboxSpaceSeparatedKeepsSpacesInName() {
        ok("w /sdcard/DCIM/Screenshots Screenshot 1.png", "w", "/sdcard/DCIM/Screenshots/Screenshot 1.png");
    }

    @Test public void twoFieldFullPath() {
        ok("w\t/sdcard/DCIM/Screenshots/x.png", "w", "/sdcard/DCIM/Screenshots/x.png");
        assertNull("两段但没有路径", InotifydLine.parse("w\tx.png"));
    }

    @Test public void multiLetterEventUsesFirst() {
        ok("wc\t/sdcard/DCIM/Screenshots\tx.png", "w", "/sdcard/DCIM/Screenshots/x.png");
    }

    @Test public void rejectsOtherEventsAndGarbage() {
        assertNull(InotifydLine.parse(null));
        assertNull(InotifydLine.parse(""));
        assertNull(InotifydLine.parse("w"));
        assertNotNull("★ fix/shot-1104：创建（n）也认（三星只报 n，宿主自己等写完）", InotifydLine.parse("n\t/sdcard/DCIM/Screenshots\tx.png"));
        assertNull("删除（d）不认", InotifydLine.parse("d\t/sdcard/DCIM/Screenshots\tx.png"));
        assertNull("相对路径", InotifydLine.parse("w\tDCIM/Screenshots\tx.png"));
        assertNull("空文件名", InotifydLine.parse("w\t/sdcard/DCIM\t "));
        assertNull("报错行", InotifydLine.parse("inotifyd: /sdcard/x: No such file"));
    }
}
