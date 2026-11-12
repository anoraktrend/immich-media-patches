package dev.immich.media;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** A single Immich asset, normalised into MediaStore shaped values. */
public final class ImmichAsset {

    public static final int TYPE_IMAGE = 1;
    public static final int TYPE_VIDEO = 3;

    public final String uuid;
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

    public ImmichAsset(String uuid, String name, String mime, int mediaType, int width, int height,
                       int orientation, long dateAdded, long durationMs, long size) {
        this.uuid = uuid;
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
