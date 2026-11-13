package dev.immich.media;

import android.content.Context;

import java.io.File;
import java.io.IOException;

/**
 * Materialises an Immich original into the app's cache so that code which insists on a real
 * file path (UriUtil.getRealPathFromUri, FileInputStream, BitmapFactory.decodeFile) works.
 */
final class ImmichStage {

    private ImmichStage() {
    }

    static File directory(Context context) {
        return new File(context.getCacheDir(), "immich/staged");
    }

    static File fileFor(Context context, ImmichAsset asset) {
        String name = asset.name == null || asset.name.length() == 0 ? asset.fileKey() : asset.name;
        return new File(directory(context),
                asset.fileKey() + "-" + name.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    /** Downloads the original when needed and returns the staged file, or null on failure. */
    static File stage(Context context, ImmichAsset asset) {
        if (context == null) {
            return null;
        }
        ImmichConfig config = ImmichConfig.peek();
        if (config == null) {
            return null;
        }
        File target = fileFor(context, asset);
        try {
            new ImmichClient(config).download(asset, false, target);
            return target.isFile() && target.length() > 0L ? target : null;
        } catch (IOException e) {
            ImmichLog.w("staging failed for " + asset.key + ": " + e);
            return null;
        }
    }

    /** Parses the asset id out of one of our media URIs. */
    static long idFrom(String data) {
        if (data == null) {
            return -1L;
        }
        int base = data.indexOf("/media/");
        if (base < 0) {
            return -1L;
        }
        String tail = data.substring(base + 7);
        int slash = tail.indexOf('/');
        if (slash >= 0) {
            tail = tail.substring(0, slash);
        }
        try {
            return Long.parseLong(tail.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /** Stages the asset referenced by one of our URIs and returns the real path. */
    static String localPath(Context context, String data) {
        if (context == null || data == null) {
            return null;
        }
        long id = idFrom(data);
        if (id <= 0L) {
            return null;
        }
        try {
            ImmichAsset asset = IdStore.get(context).assetFor(id);
            if (asset == null) {
                return null;
            }
            File staged = stage(context, asset);
            return staged == null ? null : staged.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Deletes staged originals that are old enough that no pending post can still need them. */
    static void sweep(Context context) {
        File[] files = directory(context).listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 6L * 60L * 60L * 1000L;
        int removed = 0;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff && file.delete()) {
                removed++;
            }
        }
        if (removed > 0) {
            ImmichLog.d("swept " + removed + " staged files");
        }
    }
}
