package jp.yaegaki.theta.memo;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.*;

// New versions are published as GitHub Releases (the APK is attached). Checked at most twice a
// day; the app shows a banner and the APK opens in the browser (Android installs it from there).
public final class UpdateCheck {
  static final String LATEST = "https://api.github.com/repos/kiwiman0706-beep/Theta.Memo.Hatch/releases/latest";
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

  // The release's version and APK link, or null when it has no APK.
  static JSONObject parse(JSONObject release) throws JSONException {
    if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null;
    JSONArray assets = release.optJSONArray("assets");
    for (int i = 0; assets != null && i < assets.length(); i++) {
      JSONObject a = assets.getJSONObject(i);
      if (a.optString("name").endsWith(".apk"))
        return new JSONObject()
            .put("version", release.optString("tag_name").replaceFirst("^v", ""))
            .put("url", a.optString("browser_download_url"))
            // GitHub publishes each file's checksum ("sha256:…"); the download is checked against it.
            .put("sha256", a.optString("digest").startsWith("sha256:") ? a.optString("digest").substring(7) : "");
    }
    return null;
  }

  // Runs on a background thread; true when what the app should show changed.
  public static boolean run(Context c, String current) {
    SharedPreferences prefs = c.getSharedPreferences("prefs", 0);
    if (System.currentTimeMillis() - prefs.getLong("updateChecked", 0) < EVERY) return false;
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
      JSONObject r = parse(new JSONObject(body));
      String was = prefs.getString("updateVersion", "");
      if (r == null || compare(r.getString("version"), current) <= 0) {
        prefs.edit().remove("updateVersion").remove("updateUrl").remove("updateFileVersion").apply();
        File[] old = UpdateInstaller.dir(c).listFiles(); // the APK of the version now installed
        for (int i = 0; old != null && i < old.length; i++) old[i].delete();
        return !was.isEmpty();
      }
      prefs.edit()
          .putString("updateVersion", r.getString("version"))
          .putString("updateUrl", r.getString("url"))
          .putString("updateSha256", r.optString("sha256"))
          .remove("updateError")
          .apply();
      // On Wi-Fi the new version is fetched now, so it is ready to install.
      if (UpdateInstaller.unmetered(c))
        try {
          UpdateInstaller.download(c, pending(c, current));
        } catch (Exception e) {
          // offered again from the banner
        }
      return true;
    } catch (Exception e) {
      return false; // offline: try again later
    }
  }

  public static JSONObject pending(Context c, String current) throws JSONException {
    SharedPreferences prefs = c.getSharedPreferences("prefs", 0);
    String v = prefs.getString("updateVersion", "");
    if (v.isEmpty() || compare(v, current) <= 0) return null;
    return new JSONObject()
        .put("version", v)
        .put("url", prefs.getString("updateUrl", ""))
        .put("sha256", prefs.getString("updateSha256", ""))
        .put("ready", UpdateInstaller.ready(c, v) != null)
        .put("error", prefs.getString("updateError", ""));
  }
}
