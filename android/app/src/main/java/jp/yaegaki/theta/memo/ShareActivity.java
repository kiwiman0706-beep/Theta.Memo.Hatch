package jp.yaegaki.theta.memo;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;
import java.io.*;
import java.util.*;
import org.json.*;

// "Share → Theta Memo": pages from the browser, text, photos, PDFs and any other files become a
// note straight away (a scrapbook), without opening the app. Pages are filled in with their
// title, picture and summary in the background.
public class ShareActivity extends Activity {
  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    try {
      JSONObject n = fromIntent(this, getIntent());
      if (n == null) {
        Toast.makeText(this, "保存できるものがありませんでした", Toast.LENGTH_SHORT).show();
      } else {
        boolean open = getSharedPreferences("prefs", 0).getBoolean("shareOpen", false);
        Toast.makeText(this, "Theta Memoに保存しました", Toast.LENGTH_SHORT).show();
        if (open) startActivity(new Intent(this, MainActivity.class).putExtra(Widgets.EXTRA_OPEN, n.getString("id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      }
    } catch (Exception e) {
      Toast.makeText(this, "保存できませんでした：" + e.getMessage(), Toast.LENGTH_LONG).show();
    }
    finish();
  }

  static String displayName(Context c, Uri u) {
    try (Cursor q = c.getContentResolver().query(u, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (q != null && q.moveToFirst() && q.getString(0) != null) return q.getString(0);
    } catch (Exception ignored) {
    }
    String p = u.getLastPathSegment();
    return p == null ? "" : p;
  }

  static String extension(Context c, Uri u, String display) {
    String ext = display.contains(".") ? display.substring(display.lastIndexOf('.')).toLowerCase(Locale.ROOT) : "";
    if (ext.matches("\\.[a-z0-9]{1,6}")) return ext;
    String mime = c.getContentResolver().getType(u);
    if (mime != null) {
      String e = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
      if (e != null) return "." + e;
    }
    return "";
  }

  // Copies a file the user chose or shared into the note's attachments: {name, label, mime}.
  static JSONObject importFile(Context c, Uri u) throws Exception {
    String display = displayName(c, u);
    String name = Store.newName(extension(c, u, display));
    File f = Store.get(c).mediaFile(name);
    try (InputStream in = c.getContentResolver().openInputStream(u);
        OutputStream out = new FileOutputStream(f)) {
      if (in == null) throw new IOException("ファイルを開けません");
      Store.copy(in, out, 4L * 1024 * 1024 * 1024);
    } catch (Exception e) {
      f.delete();
      throw e;
    }
    return new JSONObject().put("name", name).put("label", display).put("mime", Sync.mime(name));
  }

  static boolean picture(String name) {
    return name.matches("(?i).*\\.(jpe?g|png|gif|webp|heic|heif|bmp)$");
  }

  // The embed line for an attachment. Non-pictures carry the original file name after "|".
  public static String embed(String name, String label) {
    if (picture(name) || label == null || label.trim().isEmpty()) return Notes.embed(name);
    return "![[" + NoteCodec.path(name) + "|" + label.replaceAll("[\\[\\]|\\n]", " ").trim() + "]]";
  }

  static JSONObject fromIntent(Context c, Intent i) throws Exception {
    String action = i.getAction();
    List<Uri> streams = new ArrayList<>();
    if (Intent.ACTION_SEND.equals(action)) {
      Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
      if (u != null) streams.add(u);
    } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
      ArrayList<Uri> us = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
      if (us != null) streams.addAll(us);
    }
    CharSequence cs = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
    String text = cs == null ? "" : cs.toString().trim();
    String subject = i.getStringExtra(Intent.EXTRA_SUBJECT);
    Store s = Store.get(c);
    JSONObject n = Store.empty();
    List<String> parts = new ArrayList<>();
    for (Uri u : streams) {
      JSONObject f = importFile(c, u);
      parts.add(embed(f.getString("name"), f.getString("label")));
    }
    String url = streams.isEmpty() ? Clipper.firstUrl(text) : null;
    if (!text.isEmpty()) parts.add(text);
    if (parts.isEmpty()) return null;
    if (subject != null && !subject.trim().isEmpty() && !subject.trim().equals(text)) n.put("title", subject.trim());
    n.put("body", String.join("\n\n", parts));
    if (url != null) n.put("source", url).put("labels", new JSONArray());
    s.put(n);
    Widgets.update(c);
    MainActivity.post("changed", n.getString("id"));
    SyncJob.soon(c);
    if (url != null) {
      final String id = n.getString("id");
      final String shared = text;
      final Context app = c.getApplicationContext();
      new Thread(
              () -> {
                try {
                  JSONObject cur = s.find(id);
                  if (cur == null) return;
                  String before = cur.optString("body");
                  JSONObject done = Clipper.enrich(s, new JSONObject(cur.toString()), url, shared, true);
                  JSONObject now = s.find(id);
                  // Only if the note was not edited in the meantime.
                  if (now != null && now.optString("body").equals(before) && !Memo.editing(id)) {
                    s.put(done);
                    Widgets.update(app);
                    MainActivity.post("changed", id);
                  }
                } catch (Exception ignored) {
                }
              })
          .start();
    }
    return n;
  }
}
