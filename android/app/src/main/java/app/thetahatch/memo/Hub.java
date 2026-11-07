package app.thetahatch.memo;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import java.util.*;

// The series' settings hub on this phone: Theta Home Hatch, else Theta KidsHome Hatch
// (Theta.Hatch.Series spec/THETA_SHARED_SETTINGS.md §4.0–4.1). Its ContentProvider
// content://<hub>.theta, readable by apps signed with the series key:
//  - /info: rows key, value, by, at (ui.skin, gemini.model, google.account, …)
//  - /secret/<key>: row key, value (gemini.key, …), read only when the user chose to use it
//  - update /info: this app's changes to the items it follows ("シリーズに合わせる")
public final class Hub {
  static final String[] HUBS = {"app.thetahatch.home", "app.thetahatch.kidshome"};

  // The installed hub's package, or "".
  public static String installed(Context c) {
    PackageManager pm = c.getPackageManager();
    for (String p : HUBS)
      try {
        pm.getPackageInfo(p, 0);
        return p;
      } catch (Exception ignored) {
      }
    return "";
  }

  static Uri uri(String hub, String path) {
    return Uri.parse("content://" + hub + ".theta" + path);
  }

  // key → {value, by, at}; empty without a hub (or one that does not let this app read).
  public static Map<String, String[]> info(Context c) {
    Map<String, String[]> out = new HashMap<>();
    String hub = installed(c);
    if (hub.isEmpty()) return out;
    try (Cursor q = c.getContentResolver().query(uri(hub, "/info"), null, null, null, null)) {
      while (q != null && q.moveToNext()) {
        int k = q.getColumnIndex("key"), v = q.getColumnIndex("value"), by = q.getColumnIndex("by"), at = q.getColumnIndex("at");
        if (k < 0 || v < 0) break;
        out.put(q.getString(k), new String[] {q.getString(v), by < 0 ? "" : q.getString(by), at < 0 ? "" : q.getString(at)});
      }
    } catch (Exception ignored) {
      // not signed with the series key, or an older hub
    }
    return out;
  }

  // A secret the user chose to take from the hub; "" when the hub has none.
  public static String secret(Context c, String hub, String key) throws Exception {
    try (Cursor q = c.getContentResolver().query(uri(hub, "/secret/" + key), null, null, null, null)) {
      if (q == null) throw new java.io.IOException("ハブから受け取れませんでした（同じ鍵で署名された新しい版が要ります）");
      if (!q.moveToFirst()) return "";
      int v = q.getColumnIndex("value");
      return q.getString(v < 0 ? 0 : v);
    }
  }

  // This app changed an item it follows: the hub gets it too.
  public static void tell(Context c, String key, String value) {
    String hub = installed(c);
    if (hub.isEmpty() || value == null || value.isEmpty()) return;
    try {
      ContentValues v = new ContentValues();
      v.put(key, value);
      c.getContentResolver().update(uri(hub, "/info"), v, null, null);
    } catch (Exception ignored) {
      // a hub that does not take changes yet
    }
  }
}
