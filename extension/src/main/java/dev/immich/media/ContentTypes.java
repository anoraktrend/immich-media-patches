package dev.immich.media;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Identifies media by its magic bytes.
 *
 * <p>Image loaders choose a decoder from {@code ContentResolver.getType()} before reading any
 * data, and Immich's stored metadata does not always match the bytes it serves (a PNG uploaded
 * as {@code .jpg} is reported as {@code image/jpeg}). Remembering the sniffed type keeps later
 * lookups honest.
 */
final class ContentTypes {

    private static final ConcurrentHashMap<String, String> KNOWN = new ConcurrentHashMap<String, String>();

    private ContentTypes() {
    }

    static void remember(String key, String mime) {
        if (key != null && mime != null) {
            KNOWN.put(key, mime);
        }
    }

    static String known(String key) {
        return key == null ? null : KNOWN.get(key);
    }

    static String sniff(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] header = new byte[16];
            int read = in.read(header);
            if (read < 12) {
                return null;
            }
            if (starts(header, 0x89, 'P', 'N', 'G')) {
                return "image/png";
            }
            if (starts(header, 0xFF, 0xD8, 0xFF)) {
                return "image/jpeg";
            }
            if (starts(header, 'G', 'I', 'F', '8')) {
                return "image/gif";
            }
            if (starts(header, 'B', 'M')) {
                return "image/bmp";
            }
            if (starts(header, 'R', 'I', 'F', 'F') && matches(header, 8, 'W', 'E', 'B', 'P')) {
                return "image/webp";
            }
            if (starts(header, 0x1A, 0x45, 0xDF, 0xA3)) {
                return "video/webm";
            }
            if (matches(header, 4, 'f', 't', 'y', 'p')) {
                String brand = new String(header, 8, 4, "US-ASCII");
                if (brand.startsWith("avif")) {
                    return "image/avif";
                }
                if (brand.startsWith("heic") || brand.startsWith("heix")
                        || brand.startsWith("mif1") || brand.startsWith("msf1")) {
                    return "image/heic";
                }
                return "video/mp4";
            }
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static boolean starts(byte[] header, int... expected) {
        return matches(header, 0, expected);
    }

    private static boolean matches(byte[] header, int offset, int... expected) {
        if (header.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((header[offset + i] & 0xFF) != (expected[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}
