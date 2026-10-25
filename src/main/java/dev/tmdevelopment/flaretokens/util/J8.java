package dev.tmdevelopment.flaretokens.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small helpers so the plugin runs on Java 8 (servers 1.8) as well as on Java 21. */
public final class J8 {

    private J8() {
    }

    @SafeVarargs
    public static <T> List<T> list(T... items) {
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(items)));
    }

    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> map(Object... kv) {
        Map<K, V> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((K) kv[i], (V) kv[i + 1]);
        return Collections.unmodifiableMap(m);
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    public static void write(Path path, String text, Charset cs) throws IOException {
        Files.write(path, text.getBytes(cs));
    }

    public static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
