package dev.immich.media;

import android.net.Uri;
import android.os.Bundle;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** A single Immich asset, normalised into MediaStore shaped values. */
public final class ImmichAsset {

    public static final int TYPE_IMAGE = 1;
    public static final int TYPE_VIDEO = 3;

    /** Stable identity: "r/&lt;uuid&gt;" for remote rows, "l/&lt;mediaStoreId&gt;" for local-only rows. */
    public final String key;
    public final String name;
    public final String mime;
    public final int mediaType;
    public final int width;
    public final int height;
    public final int orientation;
    /** Seconds since epoch, MediaStore style. */
    public final long dateAdded;
    public final long durationMs;
    public final long size;

    public ImmichAsset(String key, String name, String mime, int mediaType, int width, int height,
                       int orientation, long dateAdded, long durationMs, long size) {
        this.key = key;
        this.name = name;
        this.mime = mime;
        this.mediaType = mediaType;
        this.width = width;
        this.height = height;
        this.orientation = orientation;
        this.dateAdded = dateAdded;
        this.durationMs = durationMs;
        this.size = size;
    }

    /** Bare id: the server uuid for remote rows, the MediaStore id for local rows. */
    public String id() {
        if (key != null && key.length() > 2 && key.charAt(1) == '/') {
            return key.substring(2);
        }
        return key;
    }

    public boolean local() {
        return key != null && key.startsWith("l/");
    }

    /** Key with no path separators, safe as a cache file name. */
    public String fileKey() {
        return key == null ? "unknown" : key.replace('/', '_');
    }

    public ImmichAsset withSize(long newSize) {
        return new ImmichAsset(key, name, mime, mediaType, width, height, orientation,
                dateAdded, durationMs, newSize);
    }

    /** Bridge URI for this asset ({@code thumb} or {@code original}). */
    public Uri uri(String kind) {
        return ImmichBridge.uri(kind, key);
    }

    public static ImmichAsset fromJson(JSONObject object) {
        String uuid = object.optString("id", "");
        String name = object.optString("originalFileName", "");
        if (name == null || name.length() == 0) {
            name = "immich-" + uuid;
        }
        String mime = object.optString("originalMimeType", "");
        int mediaType = "VIDEO".equals(object.optString("type", "IMAGE")) ? TYPE_VIDEO : TYPE_IMAGE;
        if (mime == null || mime.length() == 0) {
            mime = mediaType == TYPE_VIDEO ? "video/mp4" : "image/jpeg";
        }
        long created = parseIso(object.optString("fileCreatedAt", null));
        if (created <= 0) {
            created = parseIso(object.optString("createdAt", null));
        }
        return new ImmichAsset(
                uuid,
                name,
                mime,
                mediaType,
                object.optInt("width", 0),
                object.optInt("height", 0),
                0,
                created / 1000L,
                parseDuration(object.opt("duration")),
                0L);
    }

    /** One row of the Immich app's own timeline (bridge protocol 1). */
    public static ImmichAsset fromBridge(Bundle row) {
        String key = row.getString("key");
        String name = row.getString("name");
        if (name == null || name.length() == 0) {
            name = "immich-" + (key == null ? "asset" : key.replace('/', '-'));
        }
        // The app's AssetType enum: other=0, image=1, video=2, audio=3.
        int mediaType = row.getInt("type", 1) == 2 ? TYPE_VIDEO : TYPE_IMAGE;
        long created = row.getLong("createdAt", 0L);
        return new ImmichAsset(
                key,
                name,
                mimeFromName(name, mediaType),
                mediaType,
                row.getInt("width", 0),
                row.getInt("height", 0),
                row.getInt("orientation", 0),
                created / 1000L,
                row.getInt("durationMs", 0),
                0L);
    }

    private static String mimeFromName(String name, int mediaType) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String extension = name.substring(dot + 1).toLowerCase(Locale.US);
            if ("jpg".equals(extension) || "jpeg".equals(extension)) {
                return "image/jpeg";
            }
            if ("png".equals(extension)) {
                return "image/png";
            }
            if ("webp".equals(extension)) {
                return "image/webp";
            }
            if ("heic".equals(extension) || "heif".equals(extension)) {
                return "image/heic";
            }
            if ("gif".equals(extension)) {
                return "image/gif";
            }
            if ("mp4".equals(extension)) {
                return "video/mp4";
            }
            if ("mov".equals(extension)) {
                return "video/quicktime";
            }
            if ("webm".equals(extension)) {
                return "video/webm";
            }
        }
        return mediaType == TYPE_VIDEO ? "video/mp4" : "image/jpeg";
    }

    /** Accepts both millisecond numbers and "H:MM:SS.mmm" strings. */
    static long parseDuration(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return 0L;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String text = String.valueOf(value);
        try {
            String[] parts = text.split(":");
            if (parts.length == 3) {
                double seconds = Double.parseDouble(parts[2].replace(',', '.'));
                long hours = Long.parseLong(parts[0].trim());
                long minutes = Long.parseLong(parts[1].trim());
                return Math.round((hours * 3600 + minutes * 60 + seconds) * 1000.0);
            }
            return Math.round(Double.parseDouble(text) * 1000.0);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** Parses "2026-10-29T20:59:12.000Z" style timestamps without extra deps. */
    static long parseIso(String text) {
        if (text == null || text.length() < 19) {
            return 0L;
        }
        try {
            int year = Integer.parseInt(text.substring(0, 4));
            int month = Integer.parseInt(text.substring(5, 7));
            int day = Integer.parseInt(text.substring(8, 10));
            int hour = Integer.parseInt(text.substring(11, 13));
            int minute = Integer.parseInt(text.substring(14, 16));
            int second = Integer.parseInt(text.substring(17, 19));
            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
            calendar.clear();
            calendar.set(year, month - 1, day, hour, minute, second);
            return calendar.getTimeInMillis();
        } catch (Throwable t) {
            return 0L;
        }
    }
}
