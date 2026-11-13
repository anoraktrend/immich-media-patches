package dev.immich.media;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for the Immich app's bridge provider (v2 orchestrator).
 *
 * When the Immich app is installed, signed with the same key, and signed in, the
 * configuration, the timeline and every media byte come from here instead of direct
 * HTTP — credentials live in exactly one place and the data path matches the app's
 * own gallery. When the bridge is unreachable the caller falls back to the baked
 * server URL + API key.
 *
 * The consumer manifest must declare both the bridge permission and a
 * {@code <queries><provider/>} entry, or the provider is invisible to this app.
 */
public final class ImmichBridge {

    public static final String AUTHORITY = "app.alextran.immich.bridge";
    public static final String PERMISSION = "app.alextran.immich.permission.BRIDGE";
    public static final String BASE_URI = "content://" + AUTHORITY;

    private ImmichBridge() {
    }

    /** Returns the bridge config bundle, or null when the bridge is unavailable. */
    public static Bundle config(Context context) {
        if (context == null) {
            return null;
        }
        try {
            Bundle result = context.getContentResolver()
                    .call(Uri.parse(BASE_URI), "config", null, null);
            if (result != null && result.getBoolean("configured", false)) {
                return result;
            }
            return null;
        } catch (Throwable t) {
            // Missing provider, untrusted signer, or Immich signed out.
            ImmichLog.d("bridge unavailable: " + t);
            return null;
        }
    }

    /** content://app.alextran.immich.bridge/&lt;thumb|original&gt;/&lt;key&gt; */
    public static Uri uri(String kind, String key) {
        String normalized = key != null && key.indexOf('/') >= 0 ? key : "r/" + key;
        return Uri.parse(BASE_URI + "/" + kind + "/" + normalized);
    }

    /** Byte size of the original, or -1 when the bridge cannot say. */
    public static long size(Context context, String key) {
        if (context == null || key == null) {
            return -1L;
        }
        try {
            Bundle result = context.getContentResolver()
                    .call(Uri.parse(BASE_URI), "size", key, null);
            return result == null ? -1L : result.getLong("size", -1L);
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** One page of the app's own timeline, or null when the bridge is unavailable. */
    public static List<Bundle> timeline(Context context, int count, int offset) {
        if (context == null) {
            return null;
        }
        try {
            Bundle args = new Bundle();
            args.putInt("count", count);
            args.putInt("offset", offset);
            Bundle result = context.getContentResolver()
                    .call(Uri.parse(BASE_URI), "timeline", null, args);
            if (result == null || !result.getBoolean("available", false)) {
                return null;
            }
            ArrayList<Bundle> assets = result.getParcelableArrayList("assets");
            return assets == null ? new ArrayList<Bundle>() : assets;
        } catch (Throwable t) {
            ImmichLog.w("bridge timeline failed: " + t);
            return null;
        }
    }

    /** Copies the bytes behind a bridge URI into a cache file (.part + rename). */
    public static void copyToFile(Context context, Uri uri, File destination) throws IOException {
        if (destination.isFile() && destination.length() > 0L) {
            return;
        }
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create " + parent);
        }
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("No stream for " + uri);
        }
        File temporary = new File(parent, destination.getName()
                + "." + Thread.currentThread().getId() + ".part");
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
            throw new IOException("Cannot move bridge download into place: " + destination);
        }
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
