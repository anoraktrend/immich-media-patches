package dev.immich.media;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Logs to logcat and, when a target directory is configured, to a file inside the host app's
 * private storage. Some devices keep very small log buffers that evict app output, so the
 * file trace is the reliable channel while debugging.
 */
final class ImmichLog {

    private static final String TAG = "ImmichMedia";

    private static final Object LOCK = new Object();
    private static final boolean FILE_TRACE = true;

    private static File file;

    private ImmichLog() {
    }

    static void init(File directory) {
        if (!FILE_TRACE || directory == null) {
            return;
        }
        synchronized (LOCK) {
            file = new File(directory, "immich-debug.log");
        }
    }

    static void d(String message) {
        Log.i(TAG, message);
        write("I " + message);
    }

    static void w(String message) {
        Log.w(TAG, message);
        write("W " + message);
    }

    static void e(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
        write("E " + message + " :: " + throwable);
    }

    private static void write(String line) {
        synchronized (LOCK) {
            if (file == null) {
                return;
            }
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(file, true);
                out.write((System.currentTimeMillis() + " " + line + "\n").getBytes("UTF-8"));
            } catch (IOException ignored) {
                // Tracing must never break the provider.
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }
}
