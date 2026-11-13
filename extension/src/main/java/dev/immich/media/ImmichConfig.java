package dev.immich.media;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Resolves how this patched app reaches Immich, in priority order:
 *
 * <ol>
 *   <li><b>Bridge</b> (v2): the Immich app on this device, signed with the same key and
 *       signed in, serves configuration, timeline and media through a content provider.</li>
 *   <li><b>Direct</b> (v1): the server URL and API key that the patch baked into the
 *       manifest as &lt;meta-data&gt;.</li>
 * </ol>
 *
 * When the Immich app is signed out or absent, the baked values keep the picker
 * working exactly as in v1.
 */
public final class ImmichConfig {

    private static final String TAG = "ImmichMedia";

    public static final int MODE_NONE = 0;
    public static final int MODE_DIRECT = 1;
    public static final int MODE_BRIDGE = 2;

    private static volatile ImmichConfig instance;
    private static volatile Context appContext;

    public final int mode;
    /** Direct mode: server URL (no trailing slash). */
    public final String baseUrl;
    /** Direct mode: API key. */
    public final String apiKey;
    /** Bridge mode: server URL the Immich app is using (informational). */
    public final String serverUrl;
    /** This app's own media provider authority. */
    public final String authority;

    private ImmichConfig(int mode, String baseUrl, String apiKey, String serverUrl, String authority) {
        this.mode = mode;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.serverUrl = serverUrl;
        this.authority = authority;
    }

    public static ImmichConfig get(Context context) {
        ImmichConfig local = instance;
        if (local != null) {
            return local;
        }
        synchronized (ImmichConfig.class) {
            if (instance == null) {
                instance = load(context.getApplicationContext());
            }
            return instance;
        }
    }

    /**
     * Returns the configuration when it has already been loaded, without a Context.
     * The injected provider initialises it at process start, so hook methods that
     * cannot take a Context can still build authority URIs.
     */
    public static ImmichConfig peek() {
        return instance;
    }

    /** Application context captured when the configuration was first loaded. */
    public static Context context() {
        return appContext;
    }

    public boolean bridge() {
        return mode == MODE_BRIDGE;
    }

    public boolean isConfigured() {
        return mode != MODE_NONE;
    }

    private static ImmichConfig load(Context context) {
        String authority = context.getPackageName() + ".immich.media";

        // 1. The Immich app's bridge, when installed and signed in.
        Bundle bridge = ImmichBridge.config(context);
        if (bridge != null) {
            appContext = context;
            ImmichLog.d("config: bridge mode, server=" + bridge.getString("serverUrl"));
            return new ImmichConfig(MODE_BRIDGE, null, null,
                    bridge.getString("serverUrl"), authority);
        }

        // 2. Fall back to the values baked in at patch time.
        String baseUrl = null;
        String apiKey = null;
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            Bundle metaData = info.metaData;
            if (metaData != null) {
                baseUrl = metaData.getString("immich.baseUrl");
                apiKey = metaData.getString("immich.apiKey");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Unable to read Immich meta-data", t);
        }
        if (baseUrl != null) {
            while (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
        }
        appContext = context;
        boolean direct = baseUrl != null && baseUrl.length() > 0 && apiKey != null && apiKey.length() > 0;
        ImmichLog.d("config: " + (direct ? "direct mode" : "not configured"));
        return new ImmichConfig(direct ? MODE_DIRECT : MODE_NONE,
                baseUrl, apiKey, baseUrl, authority);
    }
}
