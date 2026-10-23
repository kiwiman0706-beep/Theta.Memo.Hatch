package jp.yaegaki.theta.memo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import java.util.*;

// Lends this app's Gemini API key to another Theta Hatch app on the same phone, when that app's
// user chose to use it (docs/THETA_SHARED_SETTINGS.md §4). Readable only by apps signed with the
// same key (permission jp.yaegaki.theta.permission.SHARE, protectionLevel signature); every read is
// told to the user. /notes lists the notes for Theta Home Hatch (docs/INTEGRATION.md). /info says whether there is a key to lend, without revealing it.
public class SeriesProvider extends ContentProvider {
  public static final String ACTION = "jp.yaegaki.theta.HATCH";

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
    Context c = getContext();
    try {
      // Theta Home Hatch / other series apps: the notes as a list (newest, pinned first).
      if ("/notes".equals(uri.getPath())) {
        MatrixCursor m = new MatrixCursor(new String[] {"id", "title", "snippet", "color", "pinned", "updated", "cover"});
        int limit = 50;
        try {
          limit = Math.max(1, Math.min(500, Integer.parseInt(uri.getQueryParameter("limit"))));
        } catch (Exception ignored) {
        }
        java.util.List<org.json.JSONObject> all = Notes.active(Store.get(c).all());
        for (int i = 0; i < Math.min(limit, all.size()); i++) {
          org.json.JSONObject n = all.get(i);
          StringBuilder b = new StringBuilder();
          for (org.json.JSONObject l : Notes.lines(n, 6)) b.append(b.length() > 0 ? "\n" : "").append(l.optString("type").equals("check") ? (l.optBoolean("checked") ? "☑ " : "☐ ") : "").append(l.optString("text"));
          String cover = Notes.cover(n);
          m.addRow(new Object[] {n.optString("id"), Notes.title(n), b.toString(), n.optString("color"), n.optBoolean("pinned") ? 1 : 0, n.optLong("updated"), cover == null ? "" : cover});
        }
        return m;
      }
      String key = VaultKey.get(c);
      if ("/info".equals(uri.getPath())) {
        MatrixCursor m = new MatrixCursor(new String[] {"app", "hasGeminiKey"});
        m.addRow(new Object[] {String.valueOf(c.getPackageManager().getApplicationLabel(c.getApplicationInfo())), key.isEmpty() ? 0 : 1});
        return m;
      }
      if ("/secret/gemini.key".equals(uri.getPath()) && !key.isEmpty()) {
        tell(c, getCallingPackage());
        MatrixCursor m = new MatrixCursor(new String[] {"value"});
        m.addRow(new Object[] {key});
        return m;
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  static void tell(Context c, String pkg) {
    NotificationManager nm = c.getSystemService(NotificationManager.class);
    nm.createNotificationChannel(new NotificationChannel("series", "Theta Hatch シリーズの設定", NotificationManager.IMPORTANCE_DEFAULT));
    String who = pkg == null ? "別のアプリ" : label(c, pkg);
    nm.notify(
        41,
        new Notification.Builder(c, "series")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Gemini APIキーを渡しました")
            .setContentText(who + " がこのアプリのAPIキーを使い始めました")
            .setAutoCancel(true)
            .build());
  }

  static String label(Context c, String pkg) {
    try {
      PackageManager pm = c.getPackageManager();
      return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
    } catch (Exception e) {
      return pkg;
    }
  }

  // Other Theta Hatch apps on this phone that have a key to lend: [{package, label}].
  public static List<String[]> lenders(Context c) {
    List<String[]> out = new ArrayList<>();
    PackageManager pm = c.getPackageManager();
    for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(new Intent(ACTION), 0)) {
      String pkg = r.activityInfo.packageName;
      if (pkg.equals(c.getPackageName())) continue;
      try (Cursor q = c.getContentResolver().query(Uri.parse("content://" + pkg + ".theta/info"), null, null, null, null)) {
        if (q != null && q.moveToFirst() && q.getInt(1) == 1) out.add(new String[] {pkg, q.getString(0)});
      } catch (Exception ignored) {
        // not signed with the same key, or an older version without the provider
      }
    }
    return out;
  }

  public static String borrow(Context c, String pkg) throws Exception {
    try (Cursor q = c.getContentResolver().query(Uri.parse("content://" + pkg + ".theta/secret/gemini.key"), null, null, null, null)) {
      if (q == null || !q.moveToFirst()) throw new java.io.IOException("APIキーを受け取れませんでした");
      return q.getString(0);
    }
  }

  @Override
  public String getType(Uri uri) {
    return null;
  }

  @Override
  public Uri insert(Uri uri, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri uri, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri uri, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
