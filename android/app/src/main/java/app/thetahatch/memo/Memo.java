package app.thetahatch.memo;

import android.content.Context;
import org.json.*;

// Changes that arrive from outside the editor (recorder, transcriber, widgets, share sheet).
// When the note is open in the editor, the editor applies them (so typing in progress is not
// overwritten); otherwise they go straight into the store.
public final class Memo {
  static Store store(Context c) throws Exception {
    return Store.get(c);
  }

  static boolean editing(String id) {
    return id != null && !id.isEmpty() && id.equals(MainActivity.editing) && MainActivity.instance != null;
  }

  // Returns the note that now holds the recording (a new one when none was given).
  public static synchronized String recorded(Context c, String id, String name, long durationMs) throws Exception {
    JSONObject data = new JSONObject().put("note", id).put("name", name).put("duration", durationMs);
    if (editing(id)) {
      MainActivity.post("recorded", data);
      return id;
    }
    Store s = store(c);
    JSONObject n = id == null || id.isEmpty() ? null : s.find(id);
    if (n == null) {
      n = Store.empty();
      n.put("title", "");
    }
    n.put("body", Notes.append(n.optString("body"), Notes.embed(name)));
    s.put(n);
    MainActivity.post("changed", n.getString("id"));
    Widgets.update(c);
    return n.getString("id");
  }

  // Attachments for a note that is not open: appended as embeds (the note is made if missing).
  public static synchronized void attach(Context c, String id, org.json.JSONArray files) throws Exception {
    Store s = store(c);
    JSONObject n = s.find(id);
    if (n == null) {
      n = Store.empty();
      n.put("id", id);
    }
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < files.length(); i++) {
      JSONObject f = files.getJSONObject(i);
      if (b.length() > 0) b.append("\n");
      b.append(ShareActivity.embed(f.getString("name"), f.optString("label")));
    }
    n.put("body", Notes.append(n.optString("body"), b.toString()));
    s.put(n);
    MainActivity.post("changed", n.getString("id"));
    Widgets.update(c);
  }

  public static synchronized void transcript(Context c, String id, String name, String text) throws Exception {
    if (editing(id)) {
      MainActivity.post("transcript", new JSONObject().put("note", id).put("name", name).put("text", text));
      return;
    }
    Store s = store(c);
    JSONObject n = s.find(id);
    if (n == null) return;
    n.put("body", Notes.setTranscript(n.optString("body"), name, text));
    s.put(n);
    MainActivity.post("changed", id);
    MainActivity.post("toast", "録音を文字にしました");
    Widgets.update(c);
  }

  public static synchronized void toggle(Context c, String id, int line) throws Exception {
    if (editing(id)) {
      MainActivity.post("toggle", new JSONObject().put("note", id).put("line", line));
      return;
    }
    Store s = store(c);
    JSONObject n = s.find(id);
    if (n == null) return;
    n.put("body", Notes.toggle(n.optString("body"), line));
    s.put(n);
    MainActivity.post("changed", id);
  }
}
