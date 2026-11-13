package dev.immich.bridge.probe;

import android.app.Activity;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.util.Log;

import java.io.InputStream;
import java.util.ArrayList;

/**
 * Headless probe for the Immich bridge provider. Launched via `am start`, it runs
 * every bridge endpoint once and writes the results to logcat under the tag
 * "ImmichBridgeProbe", then finishes.
 *
 * The same APK is built twice: signed with the project keystore (positive tests)
 * and with a throwaway key (proves the signature permission denies strangers).
 */
public class ProbeActivity extends Activity {
  private static final String TAG = "ImmichBridgeProbe";
  private static final String AUTH = "app.alextran.immich.bridge";
  private static final Uri BASE = Uri.parse("content://" + AUTH);

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    new Thread(this::runProbe, "bridge-probe").start();
  }

  private void runProbe() {
    ContentResolver cr = getContentResolver();
    try {
      // Sanity: system provider, no custom permission required.
      Log.i(TAG, "system setting read: " + Settings.Global.getString(cr, Settings.Global.AIRPLANE_MODE_ON));

      // Does the caller even see the provider package?
      ProviderInfo info = getPackageManager().resolveContentProvider(AUTH, 0);
      Log.i(TAG, "resolveContentProvider: " + (info == null ? "NULL" : info.packageName + "/" + info.name));

      // Reachability: our provider throws UnsupportedOperationException for query().
      // Seeing that exception here proves the provider process was reached.
      try {
        cr.query(BASE, null, null, null, null);
        Log.i(TAG, "bridge query: unexpectedly succeeded");
      } catch (Throwable t) {
        Log.i(TAG, "bridge query threw: " + t);
      }

      Bundle cfg = null;
      try {
        cfg = cr.call(BASE, "config", null, null);
        Log.i(TAG, "config: protocol=" + cfg.getInt("protocol")
          + " configured=" + cfg.getBoolean("configured")
          + " server=" + cfg.getString("serverUrl"));
      } catch (Throwable t) {
        Log.e(TAG, "call() failed", t);
        try (ContentProviderClient client = cr.acquireContentProviderClient(BASE)) {
          if (client == null) {
            Log.e(TAG, "acquireContentProviderClient: NULL");
          } else {
            Bundle b = client.call("config", null, null);
            Log.i(TAG, "client.call: protocol=" + b.getInt("protocol")
              + " configured=" + b.getBoolean("configured"));
          }
        } catch (Throwable t2) {
          Log.e(TAG, "acquireContentProviderClient failed", t2);
        }
      }

      Bundle args = new Bundle();
      args.putInt("count", 20);
      args.putInt("offset", 0);
      Bundle tl = cr.call(BASE, "timeline", null, args);
      boolean available = tl.getBoolean("available");
      ArrayList<Bundle> assets = tl.getParcelableArrayList("assets");
      int rows = assets == null ? -1 : assets.size();
      Log.i(TAG, "timeline: available=" + available + " rows=" + rows);

      String firstKey = null;
      String firstLocalKey = null;
      if (assets != null) {
        for (Bundle a : assets) {
          Log.i(TAG, "  row: key=" + a.getString("key")
            + " name=" + a.getString("name")
            + " type=" + a.getInt("type")
            + " created=" + a.getLong("createdAt")
            + " " + a.getInt("width") + "x" + a.getInt("height"));
          if (firstKey == null) firstKey = a.getString("key");
          if (firstLocalKey == null && a.getString("key").startsWith("l/")) firstLocalKey = a.getString("key");
        }
      }

      if (firstKey != null) {
        Bundle size = cr.call(BASE, "size", firstKey, null);
        Log.i(TAG, "size(" + firstKey + ") = " + size.getLong("size"));

        String[] parts = firstKey.split("/");
        String idKind = parts[0];
        String id = parts[1];

        long thumbBytes = drain(cr, BASE + "/thumb/" + idKind + "/" + id);
        Log.i(TAG, "thumb stream: " + thumbBytes + " bytes");

        long origBytes = drain(cr, BASE + "/original/" + idKind + "/" + id);
        Log.i(TAG, "original stream: " + origBytes + " bytes");

        Uri origUri = Uri.parse(BASE + "/original/" + idKind + "/" + id);
        try (ParcelFileDescriptor fd = cr.openFileDescriptor(origUri, "r")) {
          Log.i(TAG, "original fd statSize=" + (fd == null ? -1 : fd.getStatSize()));
        }
      }

      if (firstLocalKey != null) {
        Log.i(TAG, "local row: " + firstLocalKey);
        String[] lp = firstLocalKey.split("/");
        Bundle lsize = cr.call(BASE, "size", firstLocalKey, null);
        Log.i(TAG, "local size = " + lsize.getLong("size"));
        Log.i(TAG, "local thumb stream: " + drain(cr, BASE + "/thumb/" + lp[0] + "/" + lp[1]) + " bytes");
        Log.i(TAG, "local original stream: " + drain(cr, BASE + "/original/" + lp[0] + "/" + lp[1]) + " bytes");
      } else {
        Log.i(TAG, "no local-only rows in this page");
      }

      String localIdExtra = getIntent() == null ? null : getIntent().getStringExtra("localid");
      if (localIdExtra != null) {
        Log.i(TAG, "direct local test: l/" + localIdExtra);
        Bundle lsize = cr.call(BASE, "size", "l/" + localIdExtra, null);
        Log.i(TAG, "direct local size = " + lsize.getLong("size"));
        Log.i(TAG, "direct local thumb stream: " + drain(cr, BASE + "/thumb/l/" + localIdExtra) + " bytes");
        Log.i(TAG, "direct local original stream: " + drain(cr, BASE + "/original/l/" + localIdExtra) + " bytes");
      }

      Log.i(TAG, "PROBE OK");
    } catch (Throwable t) {
      Log.e(TAG, "PROBE FAILED", t);
    } finally {
      runOnUiThread(this::finish);
    }
  }

  private long drain(ContentResolver cr, String uri) throws Exception {
    long total = 0;
    try (InputStream in = cr.openInputStream(Uri.parse(uri))) {
      if (in == null) throw new IllegalStateException("null stream for " + uri);
      byte[] buf = new byte[64 * 1024];
      int n;
      final long cap = 2L * 1024 * 1024;
      while ((n = in.read(buf)) > 0 && total < cap) total += n;
    }
    return total;
  }
}
