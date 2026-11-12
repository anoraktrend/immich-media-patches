package dev.immich.media;

import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;

/**
 * Static entry points that patched bytecode calls into. Keep the signatures stable: they are
 * referenced from injected smali.
 */
public final class ImmichHooks {

    private ImmichHooks() {
    }

    /**
     * Replacement for Tumblr's {@code ImageData.d(long)} image URI builder, which concatenates
     * the MediaStore image collection with the id. Our ids only resolve through our provider.
     * Returns null for ids we did not issue so the stock implementation runs.
     */
    public static String imageUri(long id) {
        ImmichConfig config = ImmichConfig.peek();
        if (config == null || !isImmichId(config.context(), id)) {
            ImmichLog.d("imageUri(" + id + ") -> foreign id, deferring to stock");
            return null;
        }
        String uri = "content://" + config.authority + "/media/" + id;
        ImmichLog.d("imageUri(" + id + ") -> " + uri);
        return uri;
    }

    /**
     * GalleryMedia's constructor turns its _data argument into {@code "file://" + uri.getPath()},
     * which destroys our provider URIs ({@code content://host/media/9} becomes
     * {@code file:///media/9}). Returns the original string for our URIs so the field keeps
     * pointing at the provider; null otherwise, letting the stock logic run.
     */
    public static String keepContentUri(String data) {
        ImmichConfig config = ImmichConfig.peek();
        if (config == null || data == null) {
            return null;
        }
        if (data.startsWith("content://") && data.contains(config.authority)) {
            ImmichLog.d("keepContentUri -> " + data);
            return data;
        }
        return null;
    }

    /**
     * Replacement for {@code BitmapFactory.decodeFile(String, Options)}. The host app feeds it
     * our {@code _data} values (a content URI) when a media item reports no dimensions, which
     * simply fails. Decode from the staged file instead, or straight off the provider stream.
     */
    public static android.graphics.Bitmap decodeFile(String path, android.graphics.BitmapFactory.Options options) {
        Context context = ImmichConfig.peek() == null ? null : ImmichConfig.peek().context();
        if (path != null && path.startsWith("content:")) {
            String local = ImmichStage.localPath(context, path);
            if (local != null) {
                ImmichLog.d("decodeFile " + path + " -> " + local);
                return android.graphics.BitmapFactory.decodeFile(local, options);
            }
            if (context != null) {
                try {
                    java.io.InputStream in = context.getContentResolver().openInputStream(Uri.parse(path));
                    try {
                        ImmichLog.d("decodeFile " + path + " -> stream");
                        return android.graphics.BitmapFactory.decodeStream(in, null, options);
                    } finally {
                        if (in != null) {
                            in.close();
                        }
                    }
                } catch (Throwable t) {
                    ImmichLog.w("decodeFile failed for " + path + ": " + t);
                }
            }
            return null;
        }
        return android.graphics.BitmapFactory.decodeFile(path, options);
    }

    /** One-argument form of the decodeFile replacement. */
    public static android.graphics.Bitmap decodeFile(String path) {
        return decodeFile(path, null);
    }

    /** Replacement for the host app's error logger so failures reach the trace file. */
    public static void logThrown(String tag, String message, Throwable throwable) {
        StringBuilder builder = new StringBuilder("applog ").append(tag).append(": ").append(message);
        Throwable cause = throwable;
        int depth = 0;
        while (cause != null && depth++ < 4) {
            builder.append(" | ").append(cause);
            StackTraceElement[] frames = cause.getStackTrace();
            for (int i = 0; i < frames.length && i < 8; i++) {
                builder.append(" <- ").append(frames[i]);
            }
            cause = cause.getCause();
        }
        ImmichLog.w(builder.toString());
    }

    /** Replacement for the host app's error logger, message only. */
    public static void logMessage(String tag, String message) {
        ImmichLog.w("applog " + tag + ": " + message);
    }

    /**
     * Replacement query URI for a media collection query. The host app queries this instead of
     * MediaStore; the trailing id is appended by the app itself via ContentUris.withAppendedId.
     */
    public static Uri thumbBase(Context context, Uri original) {
        ImmichConfig config = ImmichConfig.get(context);
        String kind = "thumb";
        if (original != null) {
            String path = original.getPath();
            if (path != null) {
                if (path.contains("/video/")) {
                    kind = "video";
                } else if (path.contains("/images/")) {
                    kind = "image";
                }
            }
        }
        Uri result = Uri.parse("content://" + config.authority + "/" + kind);
        ImmichLog.d("thumbBase(" + original + ") -> " + result);
        return result;
    }

    /** Rewrites a display (thumbnail) URI back to the original media URI before upload. */
    public static Uri toOriginal(Uri uri) {
        if (uri == null) {
            return null;
        }
        try {
            String value = uri.toString();
            int index = value.indexOf("/thumb/");
            if (index >= 0) {
                Uri rewritten = Uri.parse(value.substring(0, index) + "/media/" + value.substring(index + 7));
                ImmichLog.d("toOriginal(" + uri + ") -> " + rewritten);
                return rewritten;
            }
        } catch (Throwable ignored) {
        }
        return uri;
    }

    /** {@code File.exists()} replacement that accepts our content URIs. */
    public static boolean exists(File file) {
        if (file == null) {
            ImmichLog.d("exists(null) -> false");
            return false;
        }
        String path = file.getPath();
        // File collapses the double slash of a URI ("content:/host/..."), so match the scheme only.
        if (path != null && path.startsWith("content:")) {
            return true;
        }
        boolean real;
        try {
            real = file.exists();
        } catch (Throwable t) {
            real = false;
        }
        ImmichLog.d("exists(" + shorten(path) + ") -> " + real + " (real file)");
        return real;
    }

    private static String shorten(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= 72 ? value : value.substring(0, 72) + "...";
    }

    /** Builds the original media URI for an asset id. */
    public static Uri mediaUri(Context context, long id) {
        return Uri.parse("content://" + ImmichConfig.get(context).authority + "/media/" + id);
    }

    /** Builds the thumbnail URI for an asset id. */
    public static Uri thumbUri(Context context, long id) {
        return Uri.parse("content://" + ImmichConfig.get(context).authority + "/thumb/" + id);
    }

    /**
     * Gallery collection URI, used where the host app would otherwise query
     * MediaStore directly. Falls back to the stock collection when the injected
     * provider has not been initialised yet.
     */
    public static Uri galleryBase() {
        ImmichConfig config = ImmichConfig.peek();
        if (config == null) {
            ImmichLog.w("galleryBase() before provider init, falling back to MediaStore");
            return MediaStore.Files.getContentUri("external");
        }
        Uri result = Uri.parse("content://" + config.authority + "/thumb");
        ImmichLog.d("galleryBase() -> " + result);
        return result;
    }

    /**
     * Thumbnail URI for a MediaStore style id. Mirrors the signature of Tumblr's
     * {@code plc.j(int, long, Context)} so the call site can pass its arguments straight
     * through; the media type is redundant because the id already identifies the asset.
     */
    public static Uri thumbUri(int mediaType, long id, Context context) {
        ImmichConfig config = ImmichConfig.peek();
        if (config == null || !isImmichId(context, id)) {
            ImmichLog.d("thumbUri(type=" + mediaType + ", id=" + id + ") -> foreign id, leaving null");
            return null;
        }
        Uri result = Uri.parse("content://" + config.authority + "/thumb/" + id);
        ImmichLog.d("thumbUri(type=" + mediaType + ", id=" + id + ") -> " + result);
        return result;
    }

    /** Original media URI for an asset id, without needing a Context. */
    public static Uri mediaUriFor(long id) {
        ImmichConfig config = ImmichConfig.peek();
        if (config == null || !isImmichId(config.context(), id)) {
            ImmichLog.d("mediaUriFor(" + id + ") -> foreign id, leaving null");
            return null;
        }
        Uri result = Uri.parse("content://" + config.authority + "/media/" + id);
        ImmichLog.d("mediaUriFor(" + id + ") -> " + result);
        return result;
    }

    /**
     * Replacement for GalleryMedia.d(). Returns the Immich original for ids we handed out and
     * otherwise reproduces the stock MediaStore behaviour, so unrelated media keeps working.
     */
    public static Uri mediaUri(int mediaType, long id) {
        ImmichConfig config = ImmichConfig.peek();
        if (config != null && isImmichId(config.context(), id)) {
            Uri result = Uri.parse("content://" + config.authority + "/media/" + id);
            ImmichLog.d("mediaUri(" + id + ") -> " + result);
            return result;
        }
        // Not ours: let GalleryMedia.d() run its stock implementation.
        ImmichLog.d("mediaUri(" + id + ") -> foreign id, deferring to stock");
        return null;
    }

    /** True when the id was handed out by our provider rather than by MediaStore. */
    private static boolean isImmichId(Context context, long id) {
        if (context == null || id <= 0L) {
            return false;
        }
        try {
            return IdStore.get(context).assetFor(id) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
