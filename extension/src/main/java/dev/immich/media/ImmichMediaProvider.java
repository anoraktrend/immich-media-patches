package dev.immich.media;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * MediaStore shaped provider that exposes an Immich timeline to the host application.
 *
 * <p>Layout: {@code /thumb/<id>} serves a scaled preview, {@code /media/<id>} serves the
 * original asset, and the bare collections ({@code /thumb}, {@code /image}, {@code /video},
 * {@code /media}) behave like a MediaStore directory cursor.
 */
public final class ImmichMediaProvider extends ContentProvider {

        private static final String[] DEFAULT_COLUMNS = {
            "_id", "_display_name", "mime_type", "width", "height", "date_added",
            "media_type", "duration", "orientation", "_size", "_data",
            "bucket_id", "bucket_display_name"
    };

    private static final int DEFAULT_LIMIT = 1000;
    private static final int MAX_LIMIT = 5000;

    private ImmichConfig config;
    private ImmichClient client;
    private IdStore store;

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        config = ImmichConfig.get(context);
        client = new ImmichClient(config);
        store = IdStore.get(context);
        ImmichLog.init(context.getFilesDir());
        ImmichStage.sweep(context);
        ImmichLog.d("provider ready, configured=" + config.isConfigured()
                + " authority=" + config.authority);
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        int[] window = parseWindow(sortOrder);
        return buildCursor(uri, projection, window[0], window[1]);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, Bundle queryArgs,
                        CancellationSignal cancellationSignal) {
        int limit = DEFAULT_LIMIT;
        int offset = 0;
        if (queryArgs != null) {
            if (queryArgs.containsKey("android:query-arg-limit")) {
                limit = queryArgs.getInt("android:query-arg-limit", DEFAULT_LIMIT);
            }
            if (queryArgs.containsKey("android:query-arg-offset")) {
                offset = queryArgs.getInt("android:query-arg-offset", 0);
            }
        }
        return buildCursor(uri, projection, limit, offset);
    }

    private Cursor buildCursor(Uri uri, String[] projection, int limit, int offset) {
        String[] columns = projection != null && projection.length > 0 ? projection : DEFAULT_COLUMNS;
        List<Object[]> rows = new ArrayList<Object[]>();
        List<String> segments = uri.getPathSegments();
        String kind = segments.isEmpty() ? "thumb" : segments.get(0);
        if (segments.size() >= 2) {
            long id = parseLong(segments.get(1), -1L);
            ImmichAsset asset = id > 0L ? store.assetFor(id) : null;
            if (asset != null) {
                String staged = null;
                if (contains(columns, "_data")) {
                    // Upload paths (UriUtil.getRealPathFromUri, sld.g, Discord's lossless
                    // upload) wrap _data in a File, so it has to be a real path. Single item
                    // queries are the ones that matter, and the picker grid reads the URI
                    // itself, so staging here does not make browsing download originals.
                    File file = ImmichStage.stage(getContext(), asset);
                    if (file != null) {
                        staged = file.getAbsolutePath();
                    }
                }
                if (asset.size <= 0L) {
                    // Callers such as the uploader validate the media by size, and the search
                    // API does not report one. Ask the server here, where it is a single item.
                    long size = client.remoteSize(asset.uuid);
                    if (size > 0L) {
                        store.updateSize(id, size);
                        asset = new ImmichAsset(asset.uuid, asset.name, asset.mime, asset.mediaType,
                                asset.width, asset.height, asset.orientation, asset.dateAdded,
                                asset.durationMs, size);
                        ImmichLog.d("resolved size for " + id + " = " + size);
                    }
                }
                rows.add(row(columns, asset, id, staged));
                ImmichLog.d("item " + uri + " columns=" + join(columns)
                        + " staged=" + staged + " size=" + asset.size);
            }
            ImmichLog.d("query " + uri + " rows=" + rows.size());
            return new CoercingCursor(columns, rows);
        }
        List<Entry> entries;
        try {
            entries = list(kind, limit, offset);
        } catch (Throwable t) {
            ImmichLog.e("list failed for " + uri, t);
            entries = new ArrayList<Entry>();
        }
        for (Entry entry : entries) {
            rows.add(row(columns, entry.asset, entry.id, null));
        }
        ImmichLog.d("query " + uri + " kind=" + kind + " columns=" + columns.length
                + " limit=" + limit + " offset=" + offset + " rows=" + rows.size());
        return new CoercingCursor(columns, rows);
    }

    private List<Entry> list(String kind, int limit, int offset) {
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }
        if (offset < 0) {
            offset = 0;
        }
        List<Entry> result = new ArrayList<Entry>(Math.min(limit, 256));
        int index = offset;
        int guard = 0;
        while (result.size() < limit && guard++ < 64) {
            int pageNumber = index / ImmichClient.PAGE_SIZE + 1;
            int within = index % ImmichClient.PAGE_SIZE;
            ImmichClient.Page page;
            try {
                page = client.page(pageNumber);
            } catch (IOException e) {
                ImmichLog.w("Unable to list Immich assets: " + e);
                break;
            }
            ImmichLog.d("page " + pageNumber + " items=" + page.items.size() + " total=" + page.total);
            if (within >= page.items.size()) {
                break;
            }
            int consumed = 0;
            for (int i = within; i < page.items.size() && result.size() < limit; i++) {
                ImmichAsset asset = page.items.get(i);
                consumed++;
                if (matches(kind, asset)) {
                    long id = store.idFor(asset);
                    if (id > 0L) {
                        result.add(new Entry(asset, id));
                    } else {
                        ImmichLog.w("no id for asset " + asset.uuid);
                    }
                }
            }
            index += consumed;
            if (page.items.size() < ImmichClient.PAGE_SIZE) {
                break;
            }
        }
        return result;
    }

    private static boolean matches(String kind, ImmichAsset asset) {
        if ("image".equals(kind)) {
            return asset.mediaType == ImmichAsset.TYPE_IMAGE;
        }
        if ("video".equals(kind)) {
            return asset.mediaType == ImmichAsset.TYPE_VIDEO;
        }
        return true;
    }

    private Object[] row(String[] columns, ImmichAsset asset, long id, String stagedData) {
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            values[i] = "_data".equals(columns[i]) && stagedData != null
                    ? stagedData
                    : value(columns[i], asset, id);
        }
        return values;
    }

    private static boolean contains(String[] values, String wanted) {
        for (String value : values) {
            if (wanted.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private Object value(String column, ImmichAsset asset, long id) {
        if ("_id".equals(column)) {
            return Long.valueOf(id);
        }
        if ("_display_name".equals(column) || "title".equals(column) || "display_name".equals(column)) {
            return asset.name;
        }
        if ("mime_type".equals(column)) {
            return asset.mime;
        }
        if ("width".equals(column)) {
            return Integer.valueOf(asset.width);
        }
        if ("height".equals(column)) {
            return Integer.valueOf(asset.height);
        }
        if ("date_added".equals(column) || "date_modified".equals(column)) {
            return Long.valueOf(asset.dateAdded);
        }
        if ("datetaken".equals(column)) {
            return Long.valueOf(asset.dateAdded * 1000L);
        }
        if ("media_type".equals(column)) {
            return Integer.valueOf(asset.mediaType);
        }
        if ("duration".equals(column)) {
            return Long.valueOf(asset.durationMs);
        }
        if ("orientation".equals(column)) {
            return Integer.valueOf(asset.orientation);
        }
        if ("_size".equals(column) || "size".equals(column)) {
            return Long.valueOf(asset.size);
        }
        if ("_data".equals(column)) {
            return "content://" + config.authority + "/media/" + id;
        }
        if ("bucket_id".equals(column)) {
            // MediaStore exposes bucket_id as an int, so apps read it with getInt/getLong.
            return Integer.valueOf(ImmichClient.BUCKET_ID);
        }
        if ("bucket_display_name".equals(column)) {
            return "Immich";
        }
        if ("is_favorite".equals(column) || "is_pending".equals(column) || "is_trashed".equals(column)) {
            return Integer.valueOf(0);
        }
        return null;
    }

    private static boolean isPreview(Uri uri) {
        List<String> segments = uri.getPathSegments();
        String kind = segments.isEmpty() ? "media" : segments.get(0);
        return !"media".equals(kind) && !"orig".equals(kind) && !"original".equals(kind);
    }

    @Override
    public String getType(Uri uri) {
        String type = resolveType(uri);
        ImmichLog.d("getType " + uri + " -> " + type);
        return type;
    }

    private String resolveType(Uri uri) {
        long id = idFrom(uri);
        if (id > 0L) {
            ImmichAsset asset = store.assetFor(id);
            if (asset != null) {
                if (isPreview(uri)) {
                    // Immich serves /thumbnail as WebP whatever the source format is, and image
                    // loaders pick their decoder from this value before reading any bytes.
                    return "image/webp";
                }
                String detected = ContentTypes.known(asset.uuid);
                if (detected != null) {
                    return detected;
                }
                if (asset.mime != null && asset.mime.length() > 0) {
                    return asset.mime;
                }
            }
        }
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        ImmichLog.d("openFile " + uri + " mode=" + mode);
        final ParcelFileDescriptor[] result = new ParcelFileDescriptor[1];
        final Throwable[] failure = new Throwable[1];
        ImmichNet.runBlocking(new Runnable() {
            @Override
            public void run() {
                try {
                    result[0] = open(uri);
                } catch (Throwable t) {
                    failure[0] = t;
                }
            }
        });
        if (failure[0] != null) {
            FileNotFoundException error = new FileNotFoundException("Immich: " + uri + ": " + failure[0]);
            error.initCause(failure[0]);
            throw error;
        }
        return result[0];
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        ParcelFileDescriptor descriptor = openFile(uri, mode);
        // The declared length must stay UNKNOWN_LENGTH: ContentResolver.openFileDescriptor()
        // rejects any descriptor that declares a length, because it cannot tell a whole file
        // from a slice inside one. Callers that want the size read the fd's stat size.
        return new AssetFileDescriptor(descriptor, 0L, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    private ParcelFileDescriptor open(Uri uri) throws IOException {
        List<String> openSegments = uri.getPathSegments();
        ImmichLog.d("open " + uri + " segments=" + openSegments.size());
        long id = idFrom(uri);
        if (id <= 0L) {
            throw new FileNotFoundException("No id in " + uri);
        }
        ImmichAsset asset = store.assetFor(id);
        if (asset == null) {
            throw new FileNotFoundException("Unknown asset id " + id);
        }
        boolean preview = isPreview(uri);
        File file = preview ? thumbnailFile(asset) : originalFile(asset);
        String url = preview ? client.thumbnailUrl(asset.uuid) : client.originalUrl(asset.uuid);
        ImmichNet.fetch(url, config.apiKey, file);
        if (!preview) {
            String detected = ContentTypes.sniff(file);
            if (detected != null) {
                ContentTypes.remember(asset.uuid, detected);
                if (!detected.equals(asset.mime)) {
                    ImmichLog.w("mime mismatch " + asset.uuid + " metadata=" + asset.mime
                            + " actual=" + detected);
                }
            }
        }
        ImmichLog.d("open " + uri + " preview=" + preview + " bytes=" + file.length());
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private File thumbnailFile(ImmichAsset asset) {
        File directory = new File(getContext().getCacheDir(), "immich/thumbs");
        return new File(directory, asset.uuid);
    }

    private File originalFile(ImmichAsset asset) {
        File directory = new File(getContext().getCacheDir(), "immich/originals");
        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(asset.mime);
        if (extension == null || extension.length() == 0) {
            extension = "bin";
        }
        return new File(directory, asset.uuid + "." + extension);
    }

    private long idFrom(Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (segments.size() < 2) {
            return -1L;
        }
        return parseLong(segments.get(1), -1L);
    }

    /** Parses "LIMIT n OFFSET m" / "LIMIT m,n" out of a MediaStore style sort order. */
    private static int[] parseWindow(String sortOrder) {
        int limit = DEFAULT_LIMIT;
        int offset = 0;
        if (sortOrder != null) {
            String upper = sortOrder.toUpperCase();
            int index = upper.indexOf("LIMIT");
            if (index >= 0) {
                String rest = sortOrder.substring(index + 5).trim();
                String[] parts = rest.split("\\s+");
                if (parts.length > 0) {
                    try {
                        String head = parts[0];
                        int comma = head.indexOf(',');
                        if (comma >= 0) {
                            offset = Integer.parseInt(head.substring(0, comma).trim());
                            limit = Integer.parseInt(head.substring(comma + 1).trim());
                            return new int[]{limit, offset};
                        }
                        limit = Integer.parseInt(head.trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                for (int i = 0; i < parts.length - 1; i++) {
                    if ("OFFSET".equalsIgnoreCase(parts[i])) {
                        try {
                            offset = Integer.parseInt(parts[i + 1].trim());
                        } catch (NumberFormatException ignored) {
                        }
                        break;
                    }
                }
            }
        }
        return new int[]{limit, offset};
    }

    private static String join(String[] values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(",");
            }
            builder.append(values[i]);
        }
        return builder.toString();
    }

    private static long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text);
        } catch (Throwable t) {
            return fallback;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Immich media provider is read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Immich media provider is read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Immich media provider is read only");
    }

    private static final class Entry {
        final ImmichAsset asset;
        final long id;

        Entry(ImmichAsset asset, long id) {
            this.asset = asset;
            this.id = id;
        }
    }
}
