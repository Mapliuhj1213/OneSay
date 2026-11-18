package com.onesay.assistant.plug.richout;

import com.onesay.assistant.plug.richout.core.SvgSanitizer;

import org.junit.Test;

import static org.junit.Assert.*;

/** ★ rel-1118 B15：SVG 净化要删掉 CSS image-set() 等不带 url( 的外链（t20k richout.svg 1 条复现）。 */
public class B15SvgImageSetTest {

    static String clean(String svg) {
        SvgSanitizer.Result r = SvgSanitizer.sanitize(svg);
        return r.svg == null ? "" : r.svg.toLowerCase();
    }

    @Test public void imageSetAndFriendsRemoved() {
        String[] css = {
                "*{background-image:image-set(\"https://evil.com\" 1x)}",
                "*{background-image:-webkit-image-set('https://evil.com/a.png' 1x)}",
                "*{background:IMAGE-SET( \"//evil.com/a.png\" 2x )}",
                "*{background-image:image(\"https://evil.com/a.png\")}",
                "*{background-image:cross-fade(url(#a), \"https://evil.com/b.png\", 50%)}",
                "*{background-image:src(\"https://evil.com/a.png\")}",
                "*{background-image:element(#x)}",
                "*{content:\"https://evil.com\"}",
        };
        for (String c : css) {
            String out = clean("<svg><style>" + c + "</style><rect width=\"1\" height=\"1\"/></svg>");
            assertFalse(c + " → " + out, out.contains("evil.com"));
            assertFalse(c + " → " + out, out.contains("image-set") || out.contains("cross-fade") || out.contains("element("));
            String out2 = clean("<svg><rect style='" + c.substring(2, c.length() - 1).replace('\'', '"') + "' width=\"1\" height=\"1\"/></svg>");
            assertFalse(c + " (style 属性) → " + out2, out2.contains("evil.com"));
        }
    }

    @Test public void localStylesKept() {
        String out = clean("<svg xmlns=\"http://www.w3.org/2000/svg\"><style>.a{fill:red;mask-image:url(#m)}</style><rect class=\"a\" width=\"1\" height=\"1\"/></svg>");
        assertTrue(out, out.contains("fill:red"));
        assertTrue(out, out.contains("url(#m)"));
        assertTrue(out, out.contains("xmlns=\"http://www.w3.org/2000/svg\""));
    }
}
