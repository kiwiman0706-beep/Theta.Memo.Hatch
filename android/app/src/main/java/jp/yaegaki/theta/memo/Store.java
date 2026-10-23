package jp.yaegaki.theta.memo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

// Notes on this device: one JSON file per note (small atomic writes, a crash never takes the rest
// with it), the attachments in media/ under their vault names, and tombstones for deletions
// that sync has yet to carry to the vault. Pure Java (no android.*) so sync is tested on the JVM.
public final class Store {
  private static Store instance;
  public final File root, notesDir, media, thumbs;
  private final Map<String, JSONObject> notes = new HashMap<>();
  private JSONObject tomb;
  // Last local write, so the app knows whether there is anything to sync or redraw.
  public volatile long changedAt = 0;
  public interface Listener {
    void changed();
  }
  public static volatile Listener listener;

  public static synchronized Store get(File filesDir) throws Exception {
    if (instance == null) instance = new Store(new File(filesDir, "memo"));
    return instance;
  }

  public static Store get(android.content.Context c) throws Exception {
    return get(c.getFilesDir());
  }

  static Store openForTest(File dir) throws Exception {
    return new Store(dir);
  }

  private Store(File dir) throws Exception {
    root = dir;
    notesDir = new File(root, "notes");
    media = new File(root, "media");
    thumbs = new File(root, "thumbs");
    notesDir.mkdirs();
    media.mkdirs();
    thumbs.mkdirs();
    File[] fs = notesDir.listFiles();
    for (int i = 0; fs != null && i < fs.length; i++) {
      File f = fs[i];
      if (f.getName().endsWith(".tmp")) {
        f.delete();
        continue;
      }
      if (!f.getName().endsWith(".json")) continue;
      try {
        JSONObject n = new JSONObject(readText(f));
        notes.put(n.getString("id"), n);
      } catch (Exception e) {
        // Keep the unreadable file aside instead of losing it silently.
        f.renameTo(new File(root, "broken-" + f.getName()));
      }
    }
    File t = new File(root, "tombstones.json");
    tomb = t.isFile() ? new JSONObject(readText(t)) : new JSONObject();
  }

  // ---- io -------------------------------------------------------------------------

  public static byte[] read(InputStream in, int limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[65536];
    int total = 0;
    for (int n; (n = in.read(buf)) > 0; ) {
      total += n;
      if (total > limit) throw new IOException("データが大きすぎます");
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  public static long copy(InputStream in, OutputStream out, long limit) throws IOException {
    byte[] buf = new byte[65536];
    long total = 0;
    for (int n; (n = in.read(buf)) > 0; ) {
      total += n;
      if (total > limit) throw new IOException("ファイルが大きすぎます");
      out.write(buf, 0, n);
    }
    return total;
  }

  static String readText(File f) throws IOException {
    try (InputStream in = new FileInputStream(f)) {
      return new String(read(in, 64 * 1024 * 1024), StandardCharsets.UTF_8);
    }
  }

  static void writeAtomic(File f, byte[] data) throws IOException {
    File tmp = new File(f.getPath() + ".tmp");
    try (FileOutputStream out = new FileOutputStream(tmp)) {
      out.write(data);
      out.getFD().sync();
    }
    if (!tmp.renameTo(f)) {
      f.delete();
      if (!tmp.renameTo(f)) throw new IOException("保存できませんでした");
    }
  }

  private void writeNote(JSONObject n) throws Exception {
    writeAtomic(new File(notesDir, n.getString("id") + ".json"), n.toString().getBytes(StandardCharsets.UTF_8));
  }

  private void saveTomb() throws IOException {
    writeAtomic(new File(root, "tombstones.json"), tomb.toString().getBytes(StandardCharsets.UTF_8));
  }

  private void touched() {
    changedAt = System.currentTimeMillis();
    Listener l = listener;
    if (l != null) l.changed();
  }

  // ---- notes ----------------------------------------------------------------------

  public static JSONObject empty() throws JSONException {
    long now = System.currentTimeMillis();
    return new JSONObject()
        .put("id", UUID.randomUUID().toString())
        .put("title", "")
        .put("body", "")
        .put("color", "")
        .put("pinned", false)
        .put("archived", false)
        .put("trashed", 0)
        .put("labels", new JSONArray())
        .put("created", now)
        .put("updated", now);
  }

  public synchronized List<JSONObject> all() throws JSONException {
    List<JSONObject> out = new ArrayList<>();
    for (JSONObject n : notes.values()) out.add(new JSONObject(n.toString()));
    return out;
  }

  public synchronized JSONArray allJson() throws JSONException {
    JSONArray a = new JSONArray();
    for (JSONObject n : notes.values()) a.put(n);
    return new JSONArray(a.toString());
  }

  public synchronized int size() {
    return notes.size();
  }

  public synchronized JSONObject note(String id) throws Exception {
    JSONObject n = notes.get(id);
    if (n == null) throw new IOException("メモが見つかりません");
    return new JSONObject(n.toString());
  }

  public synchronized JSONObject find(String id) throws JSONException {
    JSONObject n = notes.get(id);
    return n == null ? null : new JSONObject(n.toString());
  }

  // A local edit: stamps "updated".
  public synchronized void put(JSONObject n) throws Exception {
    n = new JSONObject(n.toString());
    n.put("updated", System.currentTimeMillis());
    if (!n.has("created")) n.put("created", n.getLong("updated"));
    n.put("body", NoteCodec.trimEnd(n.optString("body")));
    Set<String> before = referenced();
    notes.put(n.getString("id"), n);
    writeNote(n);
    if (tomb.has(n.getString("id"))) {
      tomb.remove(n.getString("id"));
      saveTomb();
    }
    removeUnused(before);
    touched();
  }

  // For records arriving from sync or import: keeps their "updated" so devices agree.
  public synchronized void putSynced(JSONObject n) throws Exception {
    n = new JSONObject(n.toString());
    n.put("body", NoteCodec.trimEnd(n.optString("body")));
    Set<String> before = referenced();
    notes.put(n.getString("id"), n);
    writeNote(n);
    if (tomb.has(n.getString("id"))) {
      tomb.remove(n.getString("id"));
      saveTomb();
    }
    removeUnused(before);
    touched();
  }

  // Deleted for good (from the app's trash): leaves a tombstone for sync.
  public synchronized void remove(String id) throws Exception {
    Set<String> before = referenced();
    if (notes.remove(id) == null) return;
    new File(notesDir, id + ".json").delete();
    tomb.put(id, System.currentTimeMillis());
    saveTomb();
    removeUnused(before);
    touched();
  }

  // Deleted elsewhere: no tombstone (there is nothing left to tell the vault).
  public synchronized void removeQuietly(String id) throws Exception {
    Set<String> before = referenced();
    if (notes.remove(id) == null) return;
    new File(notesDir, id + ".json").delete();
    removeUnused(before);
    touched();
  }

  public synchronized JSONObject tombstones() throws JSONException {
    return new JSONObject(tomb.toString());
  }

  public synchronized void clearTombstone(String id) throws IOException {
    if (tomb.remove(id) != null) saveTomb();
  }

  // ---- media ----------------------------------------------------------------------

  public File mediaFile(String name) throws IOException {
    if (!NoteCodec.safeName(name)) throw new IOException("ファイル名が不正です");
    return new File(media, name);
  }

  // A fresh, never-reused attachment name with the given extension (".jpg", ".ink.png"…).
  public static String newName(String ext) {
    ext = ext == null ? "" : ext.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]", "");
    if (!ext.isEmpty() && !ext.startsWith(".")) ext = "." + ext;
    return UUID.randomUUID().toString().replace("-", "").substring(0, 20) + ext;
  }

  synchronized Set<String> referenced() {
    Set<String> s = new HashSet<>();
    for (JSONObject n : notes.values()) s.addAll(NoteCodec.media(n.optString("body")));
    return s;
  }

  // Attachments a note stopped using are dropped once no other note uses them. Files that were
  // never referenced (an attachment being added right now) are left alone.
  private void removeUnused(Set<String> before) {
    Set<String> now = referenced();
    for (String name : before)
      if (!now.contains(name)) {
        new File(media, name).delete();
        new File(thumbs, name + ".jpg").delete();
      }
  }

  // Leftovers from attachments that were added to an editor and then discarded.
  public synchronized void sweep(long olderThan) {
    Set<String> now = referenced();
    File[] fs = media.listFiles();
    for (int i = 0; fs != null && i < fs.length; i++)
      if (!now.contains(fs[i].getName()) && fs[i].lastModified() < olderThan) fs[i].delete();
  }
}
