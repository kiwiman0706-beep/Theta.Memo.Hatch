package app.thetahatch.memo;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.*;

// New versions are GitHub Releases. This app only finds out about them (for the banner); installing
// is the update hub's job (Theta Home Hatch / KidsHome Hatch, series spec §6.1). Without a hub the
// Releases page opens in the browser. The app has no permission to install packages itself.
public final class UpdateCheck {
  static final String REPO = "Theta.Memo.Hatch";
  static final String LATEST = "https://api.github.com/repos/kiwiman0706-beep/" + REPO + "/releases/latest";
  public static final String PAGE = "https://github.com/kiwiman0706-beep/" + REPO + "/releases/latest";
  static final String[] HUBS = {"app.thetahatch.home", "app.thetahatch.kidshome"};
  static final String ACTION_UPDATE = "app.thetahatch.action.UPDATE";
  static final long EVERY = 12L * 3600 * 1000;

  // "0.14.0" > "0.13.2"; non-numeric parts count as 0.
  static int compare(String a, String b) {
    String[] x = a.replaceFirst("^v", "").split("\\."), y = b.replaceFirst("^v", "").split("\\.");
    for (int i = 0; i < Math.max(x.length, y.length); i++) {
      int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
      if (p != q) return Integer.compare(p, q);
    }
    return 0;
  }

  static int num(String s) {
    String d = s.replaceAll("[^0-9].*$", "");
    return d.isEmpty() ? 0 : Integer.parseInt(d);
  }

  // The release's version, or null for drafts, pre-releases and releases without an APK.
  static String version(JSONObject release) {
    if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null;
    JSONArray assets = release.optJSONArray("assets");
    for (int i = 0; assets != null && i < assets.length(); i++)
      if (assets.optJSONObject(i).optString("name").endsWith(".apk")) return release.optString("tag_name").replaceFirst("^v", "");
    return null;
  }

  // Background thread. True when the banner should change. At most twice a day unless forced.
  public static boolean run(Context c, String current, boolean force) {
    SharedPreferences prefs = c.getSharedPreferences("prefs", 0);
    if (!force && System.currentTimeMillis() - prefs.getLong("updateChecked", 0) < EVERY) return false;
    try {
      HttpURLConnection con = (HttpURLConnection) new URL(LATEST).openConnection();
      con.setConnectTimeout(15000);
      con.setReadTimeout(15000);
      con.setRequestProperty("Accept", "application/vnd.github+json");
      prefs.edit().putLong("updateChecked", System.currentTimeMillis()).apply();
      if (con.getResponseCode() != 200) return false;
      String body;
      try (InputStream in = con.getInputStream()) {
        body = new String(Store.read(in, 2 * 1024 * 1024), "UTF-8");
      }
      String v = version(new JSONObject(body));
      String was = prefs.getString("updateVersion", "");
      if (v == null || compare(v, current) <= 0) {
        prefs.edit().remove("updateVersion").apply();
        return !was.isEmpty();
      }
      prefs.edit().putString("updateVersion", v).apply();
      return !v.equals(was);
    } catch (Exception e) {
      return false; // offline: try again later
    }
  }

  public static JSONObject pending(Context c, String current) throws JSONException {
    String v = c.getSharedPreferences("prefs", 0).getString("updateVersion", "");
    if (v.isEmpty() || compare(v, current) <= 0) return null;
    return new JSONObject().put("version", v).put("hub", hub(c) != null);
  }

  // An installed update hub, Home first.
  static String hub(Context c) {
    for (String p : HUBS)
      try {
        c.getPackageManager().getPackageInfo(p, 0);
        return p;
      } catch (Exception ignored) {
      }
    return null;
  }

  // "Update": the hub opens this app's update screen; without a hub, the Releases page.
  public static void ask(Context c) {
    String hub = hub(c);
    if (hub != null)
      try {
        c.startActivity(new Intent(ACTION_UPDATE).setPackage(hub).putExtra("packageName", c.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return;
      } catch (Exception ignored) {
        // an older hub without the update screen
      }
    c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  }
}
