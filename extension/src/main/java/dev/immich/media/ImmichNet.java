package dev.immich.media;

import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;

/** Tiny blocking HTTP helper; runs work off the main thread when needed. */
public final class ImmichNet {

    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<String, Object>();

    private ImmichNet() {
    }

    /**
     * Runs the task inline when already on a background thread. When called from the main
     * thread it runs on a helper thread and waits, so that StrictMode's network-on-main-thread
     * check never trips while the provider is being opened by a UI component.
     */
    public static void runBlocking(Runnable task) {
        Looper looper = Looper.myLooper();
        if (looper == null || looper != Looper.getMainLooper()) {
            task.run();
            return;
        }
        Thread thread = new Thread(task, "immich-io");
        thread.setDaemon(true);
        thread.start();
        boolean interrupted = false;
        while (true) {
            try {
                thread.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Downloads {@code url} into {@code destination} unless it is already cached. */
    public static File fetch(String url, String apiKey, File destination) throws IOException {
        if (isUsable(destination)) {
            return destination;
        }
        Object lock = LOCKS.get(url);
        if (lock == null) {
            Object candidate = new Object();
            Object previous = LOCKS.putIfAbsent(url, candidate);
            lock = previous != null ? previous : candidate;
        }
        synchronized (lock) {
            if (isUsable(destination)) {
                return destination;
            }
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("Cannot create " + parent);
            }
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(120000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("x-api-key", apiKey);
                int code = connection.getResponseCode();
                if (code != 200) {
                    throw new IOException("HTTP " + code + " for " + url);
                }
                File temporary = new File(parent, destination.getName() + "."
                        + Thread.currentThread().getId() + ".part");
                InputStream in = connection.getInputStream();
                FileOutputStream out = new FileOutputStream(temporary);
                try {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                } finally {
                    closeQuietly(in);
                    closeQuietly(out);
                }
                if (!temporary.renameTo(destination)) {
                    temporary.delete();
                    throw new IOException("Cannot move download into place: " + destination);
                }
                return destination;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }

    private static boolean isUsable(File file) {
        return file != null && file.isFile() && file.length() > 0L;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
