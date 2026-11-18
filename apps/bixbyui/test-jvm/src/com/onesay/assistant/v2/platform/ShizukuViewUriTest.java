package com.onesay.assistant.v2.platform;

import static org.junit.Assert.*;
import org.junit.Test;

/** ★ fix-1118 B19 真机：只打开链接的 am start 模板走 App 内 startActivity；其它照旧。 */
public class ShizukuViewUriTest {
    @Test public void marketAndHttpsOnly() {
        assertEquals("market://search?q=微信&c=apps",
                ShizukuCompat.viewUri("am start -a android.intent.action.VIEW -d 'market://search?q=微信&c=apps'"));
        assertEquals("https://www.baidu.com/s?wd=x", ShizukuCompat.viewUri("am start -a android.intent.action.VIEW -d 'https://www.baidu.com/s?wd=x'"));
        assertNull(ShizukuCompat.viewUri("am start -a android.intent.action.VIEW -d 'intent://x#Intent;end'"));
        assertNull(ShizukuCompat.viewUri("am start -a android.intent.action.VIEW -d 'file:///sdcard/a'"));
        assertNull(ShizukuCompat.viewUri("am start -a android.intent.action.VIEW -d 'market://x'; reboot"));
        assertNull(ShizukuCompat.viewUri("am start -a android.intent.action.VIEW -d 'market://x' --es a b"));
        assertNull(ShizukuCompat.viewUri("am start -a android.intent.action.CALL -d 'tel:10086'"));
        assertNull(ShizukuCompat.viewUri(null));
    }
}
