package dev.immich.media;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal Immich REST client: paged timeline search and asset downloads. */
public final class ImmichClient {

    private static final String TAG = "ImmichMedia";

    /** Fixed page size keeps offset to page math stable across picker queries. */
    public static final int PAGE_SIZE = 500;

    /** Single synthetic MediaStore bucket holding the whole Immich timeline. */
    public static final int BUCKET_ID = "immich".hashCode();

    private static final long TTL_MS = 120000L;

    private final ImmichConfig config;

    private final Map<Integer, Page> cache = new LinkedHashMap<Integer, Page>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Page> eldest) {
            return size() > 8;
        }
    };

    public ImmichClient(ImmichConfig config) {
        this.config = config;
    }

    public static final class Page {
        public final List<ImmichAsset> items;
        public final int total;
        final long fetchedAt;

        Page(List<ImmichAsset> items, int total) {
            this.items = items;
            this.total = total;
            this.fetchedAt = System.currentTimeMillis();
        }
    }

    /** 1 based page fetch, cached briefly so scrolling does not refetch. */
    public Page page(int pageNumber) throws IOException {
        synchronized (cache) {
            Page cached = cache.get(pageNumber);
            if (cached != null && System.currentTimeMillis() - cached.fetchedAt < TTL_MS) {
                return cached;
            }
        }
        Page fresh = fetch(pageNumber);
        synchronized (cache) {
            cache.put(pageNumber, fresh);
        }
        return fresh;
    }

    private Page fetch(int pageNumber) throws IOException {
        if (!config.isConfigured()) {
            return new Page(Collections.<ImmichAsset>emptyList(), 0);
        }
        if (config.bridge()) {
            return fetchBridge(pageNumber);
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(config.baseUrl + "/api/search/metadata").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(45000);
            connection.setDoOutput(true);
            connection.setRequestProperty("x-api-key", config.apiKey);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            byte[] body = ("{\"page\":" + pageNumber + ",\"size\":" + PAGE_SIZE + "}").getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(body);
            } finally {
                out.close();
            }
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new IOException("Immich search returned HTTP " + code);
            }
            String json = readAll(connection.getInputStream());
            JSONObject root = new JSONObject(json);
            JSONObject assets = root.optJSONObject("assets");
            if (assets == null) {
                return new Page(Collections.<ImmichAsset>emptyList(), 0);
            }
            JSONArray items = assets.optJSONArray("items");
            List<ImmichAsset> parsed = new ArrayList<ImmichAsset>();
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item != null) {
                        parsed.add(ImmichAsset.fromJson(item));
                    }
                }
            }
            return new Page(parsed, assets.optInt("total", parsed.size()));
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Immich search failed", t);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** v2: the Immich app's own timeline, through the bridge provider. */
    private Page fetchBridge(int pageNumber) throws IOException {
        List<Bundle> rows = ImmichBridge.timeline(config.context(), PAGE_SIZE,
                (pageNumber - 1) * PAGE_SIZE);
        if (rows == null) {
            throw new IOException("bridge timeline unavailable");
        }
        List<ImmichAsset> parsed = new ArrayList<ImmichAsset>(rows.size());
        for (Bundle row : rows) {
            parsed.add(ImmichAsset.fromBridge(row));
        }
        return new Page(parsed, (pageNumber - 1) * PAGE_SIZE + parsed.size());
    }

    /** Bridge aware download: content URI in bridge mode, HTTP otherwise. */
    public void download(ImmichAsset asset, boolean preview, File destination) throws IOException {
        if (config.bridge()) {
            ImmichBridge.copyToFile(config.context(), asset.uri(preview ? "thumb" : "original"),
                    destination);
        } else {
            String url = preview ? thumbnailUrl(asset.id()) : originalUrl(asset.id());
            ImmichNet.fetch(url, config.apiKey, destination);
        }
    }

    /** Byte size of the original: bridge call in bridge mode, HEAD otherwise. */
    public long size(ImmichAsset asset) {
        if (config.bridge()) {
            long bridged = ImmichBridge.size(config.context(), asset.key);
            return bridged > 0L ? bridged : 0L;
        }
        return remoteSize(asset.id());
    }

    public String thumbnailUrl(String uuid) {
        return config.baseUrl + "/api/assets/" + uuid + "/thumbnail?size=thumbnail";
    }

    /** Content-Length of the original asset, 0 when the server will not say. */
    public long remoteSize(String uuid) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(originalUrl(uuid)).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("x-api-key", config.apiKey);
            int code = connection.getResponseCode();
            if (code != 200) {
                return 0L;
            }
            long length = connection.getContentLengthLong();
            return length > 0L ? length : 0L;
        } catch (Throwable t) {
            return 0L;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public String originalUrl(String uuid) {
        return config.baseUrl + "/api/assets/" + uuid + "/original";
    }

    private static String readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, in.available()));
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static void log(String message) {
        Log.d(TAG, message);
    }
}
