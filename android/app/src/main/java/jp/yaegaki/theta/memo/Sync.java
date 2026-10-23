package jp.yaegaki.theta.memo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

// Shared sync rules (same decision table as Theta Meishi Hatch): content hash, merge of concurrent
// edits and the decision table. VaultSync maps the vault onto these.
public final class Sync {
  // Device-only fields: never written to the vault and never counted as an edit.
  static final String[] LOCAL = {"queue", "queueError", "transcribing"};
  static final String DELETED = "deleted";

  static JSONObject shared(JSONObject n) throws Exception {
    JSONObject s = new JSONObject(n.toString());
    for (String k : LOCAL) s.remove(k);
    s.remove("vaultNewId");
    return s;
  }

  // Sorted-key JSON so the same content always hashes the same on every platform.
  static String canonical(Object o) throws Exception {
    if (o instanceof JSONObject) {
      JSONObject j = (JSONObject) o;
      List<String> keys = new ArrayList<>();
      for (Iterator<String> it = j.keys(); it.hasNext(); ) keys.add(it.next());
      Collections.sort(keys);
      StringBuilder b = new StringBuilder("{");
      for (String k : keys) {
        if (b.length() > 1) b.append(',');
        b.append(JSONObject.quote(k)).append(':').append(canonical(j.get(k)));
      }
      return b.append('}').toString();
    }
    if (o instanceof JSONArray) {
      JSONArray a = (JSONArray) o;
      StringBuilder b = new StringBuilder("[");
      for (int i = 0; i < a.length(); i++) {
        if (i > 0) b.append(',');
        b.append(canonical(a.get(i)));
      }
      return b.append(']').toString();
    }
    if (o instanceof String) return JSONObject.quote((String) o);
    if (o == null || o == JSONObject.NULL) return "null";
    if (o instanceof Number) {
      double d = ((Number) o).doubleValue();
      if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
    }
    return String.valueOf(o);
  }

  // The fields as the vault carries them, so a note read back from its own file hashes the same.
  static JSONObject comparable(JSONObject n) throws Exception {
    JSONObject s = shared(n);
    s.remove("updated");
    s.put("body", NoteCodec.trimEnd(s.optString("body")));
    s.put("title", s.optString("title").trim());
    s.put("color", s.optString("color"));
    s.put("pinned", s.optBoolean("pinned"));
    s.put("archived", s.optBoolean("archived"));
    // The vault stores times to the millisecond in +09:00; the zone is not part of the content.
    s.put("trashed", s.optLong("trashed") > 0 ? 1 : 0);
    s.put("created", s.optLong("created"));
    s.put("source", s.optString("source"));
    s.put("importKey", s.optString("importKey"));
    if (s.optJSONArray("labels") == null) s.put("labels", new JSONArray());
    JSONObject extra = s.optJSONObject("extra");
    if (extra != null && extra.length() == 0) s.remove("extra");
    return s;
  }

  static String hash(JSONObject n) throws Exception {
    byte[] d = MessageDigest.getInstance("SHA-256").digest(canonical(comparable(n)).getBytes(StandardCharsets.UTF_8));
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < 16; i++) b.append(String.format("%02x", d[i]));
    return b.toString();
  }

  // Both sides changed. Card state (colour, pin, labels…) follows the newer edit, labels are
  // unioned. If the text itself differs, the newer text stays and the other one becomes a
  // separate "conflict copy" note, so nothing typed on either device is lost.
  // Returns {merged, conflictCopyOrNull}.
  static JSONObject[] merge(JSONObject local, JSONObject remote) throws Exception {
    boolean remoteNewer = remote.optLong("updated") > local.optLong("updated");
    JSONObject base = shared(remoteNewer ? remote : local), other = shared(remoteNewer ? local : remote);
    JSONArray labels = base.optJSONArray("labels");
    if (labels == null) base.put("labels", labels = new JSONArray());
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < labels.length(); i++) seen.add(labels.getString(i));
    JSONArray ol = other.optJSONArray("labels");
    for (int i = 0; ol != null && i < ol.length(); i++) if (seen.add(ol.getString(i))) labels.put(ol.getString(i));
    if (base.optString("source").isEmpty()) base.put("source", other.optString("source"));
    JSONObject copy = null;
    String a = NoteCodec.trimEnd(base.optString("body")), b = NoteCodec.trimEnd(other.optString("body"));
    if (!a.equals(b) && !b.isEmpty()) {
      if (a.isEmpty()) base.put("body", b);
      else if (a.contains(b)) {
        // the newer text already has everything the older one had
      } else {
        copy = new JSONObject(other.toString());
        copy.put("id", UUID.randomUUID().toString());
        String t = other.optString("title").trim();
        copy.put("title", (t.isEmpty() ? NoteCodec.firstLine(b) : t) + "（競合 " + NoteCodec.stamp(other.optLong("updated")) + "）");
        copy.put("pinned", false);
        copy.remove("importKey");
      }
    }
    base.put("updated", Math.max(local.optLong("updated"), remote.optLong("updated")) + 1);
    return new JSONObject[] {base, copy};
  }

  static String mime(String name) {
    String n = name.toLowerCase(Locale.ROOT);
    String[][] map = {
      {".jpg", "image/jpeg"}, {".jpeg", "image/jpeg"}, {".png", "image/png"}, {".gif", "image/gif"}, {".webp", "image/webp"},
      {".heic", "image/heic"}, {".svg", "image/svg+xml"}, {".pdf", "application/pdf"}, {".m4a", "audio/mp4"}, {".mp3", "audio/mpeg"},
      {".3gp", "audio/3gpp"}, {".amr", "audio/amr"}, {".wav", "audio/wav"}, {".ogg", "audio/ogg"}, {".opus", "audio/ogg"},
      {".aac", "audio/aac"}, {".mp4", "video/mp4"}, {".mov", "video/quicktime"}, {".webm", "video/webm"}, {".json", "application/json"},
      {".txt", "text/plain"}, {".md", "text/markdown"}, {".csv", "text/csv"}, {".html", "text/html"}, {".zip", "application/zip"},
      {".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
      {".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"},
      {".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"}
    };
    for (String[] m : map) if (n.endsWith(m[0])) return m[1];
    return "application/octet-stream";
  }

  enum Action { SKIP, SAME, FORGET_TOMBSTONE, RECORD_REMOTE_DELETE, PUSH, PULL, MERGE }

  // lh/rh: hash of the local / remote record ("deleted" for a tombstone, null if absent);
  // ih: hash both sides had at the last successful sync (null if never synced).
  static Action decide(String lh, String rh, String ih) {
    if (lh == null && rh == null) return Action.SKIP;
    if (lh != null && lh.equals(rh)) return Action.SAME;
    if (rh == null) return lh.equals(DELETED) ? Action.FORGET_TOMBSTONE : Action.PUSH;
    if (lh == null) return rh.equals(DELETED) ? Action.RECORD_REMOTE_DELETE : Action.PULL;
    boolean localChanged = !lh.equals(ih), remoteChanged = !rh.equals(ih);
    if (!remoteChanged) return Action.PUSH;
    if (!localChanged) return Action.PULL;
    if (lh.equals(DELETED)) return Action.PULL; // an edit elsewhere beats a delete here
    if (rh.equals(DELETED)) return Action.PUSH; // and a local edit beats a delete elsewhere
    return Action.MERGE;
  }
}
