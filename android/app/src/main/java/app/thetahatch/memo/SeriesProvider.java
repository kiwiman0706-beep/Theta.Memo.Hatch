package app.thetahatch.memo;

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
// same key (permission app.thetahatch.permission.SHARE, protectionLevel signature); every read is
// told to the user. /notes lists the notes for Theta Home Hatch (docs/INTEGRATION.md). /info says whether there is a key to lend, without revealing it.
// /shelf is this app's shelf for Home (Theta.Hatch.Series spec/THETA_SHARED_SETTINGS.md §4.2): the
// pinned notes, with their pictures served by openFile at /image/<name>.
public class SeriesProvider extends ContentProvider {
  public static final String ACTION = "app.thetahatch.HATCH";

  static final String SHELF_LIMIT = "30";
  final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
  final Runnable shelfChanged = () -> {
    Context c = getContext();
    if (c != null) c.getContentResolver().notifyChange(shelfUri(c), null);
  };

  static Uri shelfUri(Context c) {
    return Uri.parse("content://" + c.getPackageName() + ".theta/shelf");
  }

  @Override
  public boolean onCreate() {
    // Notes changed (typing, sync, widgets): Home reads the shelf again, at most every couple of seconds.
    Store.listener = () -> {
      main.removeCallbacks(shelfChanged);
      main.postDelayed(shelfChanged, 2000);
    };
    return true;
  }

  // Pinned notes shown on Home's shelf; the user can turn this off in the settings.
  static List<org.json.JSONObject> shelf(Context c) throws Exception {
    List<org.json.JSONObject> out = new ArrayList<>();
    if (!c.getSharedPreferences("prefs", 0).getBoolean("shelfPinned", true)) return out;
    for (org.json.JSONObject n : Notes.active(Store.get(c).all())) {
      if (!n.optBoolean("pinned")) break; // active() lists pinned notes first
      out.add(n);
      if (out.size() >= Integer.parseInt(SHELF_LIMIT)) break;
    }
    return out;
  }

  // The note's first picture (photo or handwriting), for the shelf.
  static String picture(org.json.JSONObject n) {
    for (String m : NoteCodec.media(n.optString("body")))
      if (m.matches("(?i).*\\.(jpe?g|png|gif|webp|heic|heif)$")) return m;
    return null;
  }

  static String text(org.json.JSONObject n) throws Exception {
    StringBuilder b = new StringBuilder();
    String title = Notes.title(n);
    boolean skipTitle = n.optString("title").trim().isEmpty();
    for (org.json.JSONObject l : Notes.lines(n, 6)) {
      if (skipTitle && l.optString("text").equals(title)) {
        skipTitle = false;
        continue;
      }
      if (b.length() > 0) b.append('\n');
      if (l.optString("type").equals("check")) b.append(l.optBoolean("checked") ? "☑ " : "☐ ");
      b.append(l.optString("text"));
      if (b.length() > 200) break;
    }
    return b.length() > 200 ? b.substring(0, 200) + "…" : b.toString();
  }

  static String color(org.json.JSONObject n) {
    String key = n.optString("color");
    Integer c = key.isEmpty() ? null : Notes.COLOR.get(key);
    return c == null ? "" : String.format("#%06X", c & 0xFFFFFF);
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
      if ("/shelf".equals(uri.getPath())) {
        MatrixCursor m = new MatrixCursor(new String[] {"section", "sectionTitle", "id", "title", "subtitle", "text", "image", "action", "color"});
        for (org.json.JSONObject n : shelf(c)) {
          String id = n.getString("id");
          // Opens this note in this app (Home re-targets it to this package before opening).
          String action = new Intent(c, MainActivity.class).setAction(Intent.ACTION_VIEW)
              .setData(Uri.parse("memo-note://" + id)).putExtra(Widgets.EXTRA_OPEN, id)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).toUri(Intent.URI_INTENT_SCHEME);
          String pic = picture(n);
          String image = pic == null || Thumbs.get(Store.get(c), pic) == null ? "" : "content://" + c.getPackageName() + ".theta/image/" + pic;
          String title = Notes.title(n);
          m.addRow(new Object[] {"pinned", "ピン留め", id, title.isEmpty() ? "メモ" : title, "", text(n), image, action, color(n)});
        }
        m.setNotificationUri(c.getContentResolver(), shelfUri(c));
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
    return uri.getPath() != null && uri.getPath().startsWith("/image/") ? "image/jpeg" : null;
  }

  // Shelf pictures: a thumbnail of a picture that a note on the shelf shows, nothing else.
  @Override
  public android.os.ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
    String path = uri.getPath();
    if (path == null || !path.startsWith("/image/") || !"r".equals(mode)) throw new java.io.FileNotFoundException();
    String name = path.substring(7);
    try {
      for (org.json.JSONObject n : shelf(getContext()))
        if (name.equals(picture(n))) {
          java.io.File t = Thumbs.get(Store.get(getContext()), name);
          if (t != null) return android.os.ParcelFileDescriptor.open(t, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
        }
    } catch (Exception ignored) {
    }
    throw new java.io.FileNotFoundException(name);
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
