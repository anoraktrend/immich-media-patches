package dev.immich.media;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Reads the Immich server configuration that the patch bakes into the host
 * application's AndroidManifest.xml as &lt;meta-data&gt; entries.
 */
public final class ImmichConfig {

    private static final String TAG = "ImmichMedia";

    private static volatile ImmichConfig instance;
    private static volatile Context appContext;

    public final String baseUrl;
    public final String apiKey;
    public final String authority;

    private ImmichConfig(String baseUrl, String apiKey, String authority) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
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

    private static ImmichConfig load(Context context) {
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
        return new ImmichConfig(baseUrl, apiKey, context.getPackageName() + ".immich.media");
    }

    public boolean isConfigured() {
        return baseUrl != null && baseUrl.length() > 0 && apiKey != null && apiKey.length() > 0;
    }
}
