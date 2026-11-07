package com.onesay.assistant.plug.suggest.core;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

/** fix/shot-1104 单测：读仓库源码（-Dbixby.root）。 */
public final class ShotSrcTestUtil {
    private ShotSrcTestUtil() {
    }

    public static String read(String rel) throws Exception {
        String root = System.getProperty("bixby.root", ".");
        return new String(Files.readAllBytes(new File(root, rel).toPath()), Charset.forName("UTF-8"));
    }
}
