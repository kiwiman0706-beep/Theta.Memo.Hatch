package jp.yaegaki.theta.memo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

// Two-way sync between this device and the Theta Vault (docs/THETA_MEMO_VAULT.md), following the
// rules of Theta Meishi Hatch: remote change detection by the file's md5 (so edits made in
// Obsidian through Google Drive for desktop are seen), per-device records outside the vault,
// identity by frontmatter id, deletions into .theta/trash, no deletions when many notes vanish.
public final class VaultSync {
  public static final String ROOT = "Theta Vault";
  static final String DELETED = Sync.DELETED;
  static final String[] MANAGED = {"Notes", "Archive", "Trash"};
  // Read as well: where Obsidian Web Clipper saves by default. Notes found there stay there.
  static final String[] INBOXES = {"Clippings"};

  public interface Progress {
    void update(String text);
  }

  public static class Result {
    public int up, down, merged, deleted, mediaUp, mediaDown, conflicts;
    public final List<String> errors = new ArrayList<>();

    public String text() {
      List<String> parts = new ArrayList<>();
      if (down > 0) parts.add("受信 " + down + "件");
      if (up > 0) parts.add("送信 " + up + "件");
      if (merged > 0) parts.add("両方の変更を統合 " + merged + "件");
      if (conflicts > 0) parts.add("競合コピー " + conflicts + "件");
      if (deleted > 0) parts.add("削除の反映 " + deleted + "件");
      if (mediaUp + mediaDown > 0) parts.add("添付 " + (mediaUp + mediaDown) + "件");
      StringBuilder b = new StringBuilder("Theta Vaultと同期しました");
      if (!parts.isEmpty()) b.append("（").append(String.join(" / ", parts)).append("）");
      for (String e : errors) b.append("\n").append(e);
      return b.toString();
    }
  }

  // ---- local state (outside the vault) ----------------------------------------------

  static File indexFile(Store s) {
    return new File(s.root, "vault-sync.json");
  }

  static JSONObject loadIndex(Store s) {
    try {
      return new JSONObject(Store.readText(indexFile(s)));
    } catch (Exception e) {
      return new JSONObject();
    }
  }

  static void saveIndex(Store s, JSONObject index) throws Exception {
    Store.writeAtomic(indexFile(s), index.toString().getBytes(StandardCharsets.UTF_8));
  }

  public static void forget(Store s) {
    indexFile(s).delete();
  }

  // ---- one run ------------------------------------------------------------------------

  static final class Remote {
    String fileId, name, folder, md5;
    long modified;
    String text;
    JSONObject parsed;
  }

  static final class Ctx {
    VaultRemote d;
    Store s;
    JSONObject known; // id -> {f, md5, lh, name}
    Map<String, String> folders = new HashMap<>(); // "Notes" -> drive id
    Set<String> managedIds = new HashSet<>();
    String files, trash;
    Map<String, String> media = new HashMap<>(); // local name -> drive id
    Map<String, Set<String>> names = new HashMap<>(); // folder id -> file names in use
    Result res = new Result();
    boolean allowRemoteDeletes = true;
  }

  public static Result run(Store store, VaultRemote d, Progress progress) throws Exception {
    Ctx x = new Ctx();
    x.d = d;
    x.s = store;
    progress.update("Theta Vaultと同期中");
    String root = d.folder("root", ROOT);
    String theta = d.folder(root, ".theta");
    registerApp(d, theta);
    x.trash = d.folder(theta, "trash");
    String app = d.folder(root, NoteCodec.APP_DIR);
    List<String> scan = new ArrayList<>();
    for (String f : MANAGED) {
      String id = d.folder(app, f);
      x.folders.put(f, id);
      x.managedIds.add(id);
      scan.add(id);
    }
    for (String f : INBOXES) scan.add(d.folder(app, f));
    x.files = d.folder(app, "_files");

    JSONObject index = loadIndex(x.s);
    x.known = index.optJSONObject("notes");
    if (x.known == null) index.put("notes", x.known = new JSONObject());
    Map<String, String> byFile = new HashMap<>();
    for (Iterator<String> it = x.known.keys(); it.hasNext(); ) {
      String id = it.next();
      byFile.put(x.known.getJSONObject(id).optString("f"), id);
    }

    Map<String, Remote> remote = new HashMap<>();
    for (String folder : scan) {
      Set<String> used = new HashSet<>();
      x.names.put(folder, used);
      JSONArray list = d.files(folder);
      for (int i = 0; i < list.length(); i++) {
        JSONObject f = list.getJSONObject(i);
        used.add(f.getString("name"));
        if (!f.getString("name").toLowerCase(Locale.ROOT).endsWith(".md")) continue;
        Remote r = new Remote();
        r.fileId = f.getString("id");
        r.name = f.getString("name");
        r.folder = folder;
        r.md5 = f.optString("md5Checksum");
        r.modified = NoteCodec.ms(f.optString("modifiedTime"), 0);
        String id = byFile.get(r.fileId);
        if (id == null) {
          try {
            r.text = d.read(r.fileId);
            r.parsed = NoteCodec.parse(r.text, title(r.name), r.modified);
            id = r.parsed.getString("id");
          } catch (Exception e) {
            x.res.errors.add(r.name + "：読み込めないため対象外（" + e.getMessage() + "）");
            continue;
          }
        }
        if (remote.containsKey(id)) {
          x.res.errors.add(r.name + "：同じidのノートが複数あります。片方は無視しました");
          continue;
        }
        remote.put(id, r);
      }
    }
    // A known note missing from the folders may have been moved into a subfolder in Obsidian.
    int missing = 0;
    for (Iterator<String> it = x.known.keys(); it.hasNext(); ) {
      String id = it.next();
      if (remote.containsKey(id)) continue;
      JSONObject m = d.meta(x.known.getJSONObject(id).optString("f"));
      if (m == null
          || m.optBoolean("trashed")
          || m.optJSONArray("parents") == null
          || m.getJSONArray("parents").optString(0).equals(x.trash)) {
        missing++;
        continue;
      }
      Remote r = new Remote();
      r.fileId = m.getString("id");
      r.name = m.optString("name");
      r.folder = m.getJSONArray("parents").optString(0);
      r.md5 = m.optString("md5Checksum");
      r.modified = NoteCodec.ms(m.optString("modifiedTime"), 0);
      remote.put(id, r);
    }
    x.allowRemoteDeletes = missing <= Math.max(5, x.known.length() * 3 / 10);
    if (!x.allowRemoteDeletes)
      x.res.errors.add("Vaultから" + missing + "件のノートが見つかりません。誤操作の可能性があるため、端末側の削除は行いませんでした");
    JSONArray mf = d.files(x.files);
    for (int i = 0; i < mf.length(); i++) x.media.put(mf.getJSONObject(i).getString("name"), mf.getJSONObject(i).getString("id"));

    Map<String, JSONObject> local = new HashMap<>();
    for (JSONObject n : x.s.all()) local.put(n.getString("id"), n);
    JSONObject tomb = x.s.tombstones();
    Set<String> ids = new TreeSet<>(local.keySet());
    ids.addAll(remote.keySet());
    for (Iterator<String> it = tomb.keys(); it.hasNext(); ) ids.add(it.next());
    for (Iterator<String> it = x.known.keys(); it.hasNext(); ) ids.add(it.next());

    int n = 0;
    for (String id : ids) {
      if (++n % 10 == 0) progress.update("Theta Vaultと同期中 " + n + " / " + ids.size());
      try {
        step(x, id, local.get(id), tomb.has(id), remote.get(id));
      } catch (Exception e) {
        x.res.errors.add(label(local.get(id), id) + "：" + e.getMessage());
        String m = String.valueOf(e.getMessage());
        if (m.contains("HTTP 401") || m.contains("HTTP 403")) break;
      }
      if (n % 20 == 0) saveIndex(x.s, index);
    }
    saveIndex(x.s, index);
    return x.res;
  }

  // .theta/vault.json lists the Theta apps sharing this vault; add ourselves, keep the rest.
  static void registerApp(VaultRemote d, String theta) throws Exception {
    JSONArray tf = d.files(theta);
    for (int i = 0; i < tf.length(); i++) {
      JSONObject f = tf.getJSONObject(i);
      if (!f.optString("name").equals("vault.json")) continue;
      try {
        JSONObject v = new JSONObject(d.read(f.getString("id")));
        JSONArray apps = v.optJSONArray("apps");
        if (apps == null) v.put("apps", apps = new JSONArray());
        for (int k = 0; k < apps.length(); k++) if ("memo".equals(apps.optString(k))) return;
        apps.put("memo");
        d.put(f.getString("id"), theta, "vault.json", v.toString(2).getBytes(StandardCharsets.UTF_8), "", "");
      } catch (JSONException e) {
        // someone's hand-edited file: leave it alone
      }
      return;
    }
    d.put("", theta, "vault.json",
        new JSONObject().put("spec", 1).put("apps", new JSONArray().put("memo")).toString(2).getBytes(StandardCharsets.UTF_8), "", "");
  }

  static String title(String fileName) {
    return fileName.replaceFirst("(?i)\\.md$", "");
  }

  static String label(JSONObject n, String id) {
    if (n != null) {
      String t = n.optString("title").isEmpty() ? NoteCodec.firstLine(n.optString("body")) : n.optString("title");
      if (!t.isEmpty()) return t.length() > 20 ? t.substring(0, 20) + "…" : t;
    }
    return id.substring(0, Math.min(8, id.length()));
  }

  static void step(Ctx x, String id, JSONObject L, boolean tomb, Remote R) throws Exception {
    if (L != null && tomb) {
      x.s.clearTombstone(id);
      tomb = false;
    }
    JSONObject I = x.known.optJSONObject(id);
    String lh = L != null ? Sync.hash(L) : tomb ? DELETED : null;
    String ih = I != null ? I.optString("lh") : null;
    String rh;
    if (R != null) rh = I != null && R.md5.equals(I.optString("md5")) ? ih : "md5:" + R.md5;
    else rh = I != null ? DELETED : null;
    switch (Sync.decide(lh, rh, ih)) {
      case SKIP:
        return;
      case SAME:
        if (lh.equals(DELETED)) {
          x.s.clearTombstone(id);
          x.known.remove(id);
        }
        return;
      case FORGET_TOMBSTONE:
        x.s.clearTombstone(id);
        x.known.remove(id);
        return;
      case RECORD_REMOTE_DELETE:
        x.known.remove(id);
        return;
      case PUSH:
        if (lh.equals(DELETED)) {
          if (R != null) x.d.trash(R.fileId, R.folder, x.trash);
          x.s.clearTombstone(id);
          x.known.remove(id);
          x.res.deleted++;
        } else push(x, L, R);
        return;
      case PULL:
        if (rh.equals(DELETED)) {
          if (!x.allowRemoteDeletes) return;
          if (L != null && Sync.hash(x.s.note(id)).equals(lh)) {
            x.s.removeQuietly(id);
            x.res.deleted++;
          }
          x.known.remove(id);
          return;
        }
        pull(x, id, lh == null || lh.equals(DELETED) ? null : L, R);
        return;
      case MERGE:
        {
          JSONObject theirs = read(x, R);
          if (Sync.hash(theirs).equals(lh)) {
            record(x, id, R.fileId, R.md5, lh, R.name);
            return;
          }
          JSONObject[] m = Sync.merge(L, theirs);
          JSONObject merged = m[0];
          if (!merged.has("extra") && theirs.has("extra")) merged.put("extra", theirs.get("extra"));
          fetchMedia(x, merged);
          JSONObject current = x.s.find(id);
          if (current == null || !Sync.hash(current).equals(lh)) return; // edited during sync; next run
          for (String k : Sync.LOCAL) if (current.has(k)) merged.put(k, current.get(k));
          x.s.putSynced(merged);
          push(x, merged, R);
          x.res.up--;
          x.res.merged++;
          if (m[1] != null) {
            fetchMedia(x, m[1]);
            x.s.putSynced(m[1]);
            push(x, m[1], null);
            x.res.up--;
            x.res.conflicts++;
          }
        }
    }
  }

  static JSONObject read(Ctx x, Remote R) throws Exception {
    String md = R.text != null ? R.text : x.d.read(R.fileId);
    JSONObject p = R.parsed != null ? new JSONObject(R.parsed.toString()) : NoteCodec.parse(md, title(R.name), R.modified);
    // An edit made in Obsidian does not touch "updated"; the file's own time tells when it changed.
    if (NoteCodec.editedOutside(md) && R.modified > p.optLong("updated")) p.put("updated", R.modified);
    return p;
  }

  static void record(Ctx x, String id, String fileId, String md5, String lh, String name) throws Exception {
    x.known.put(id, new JSONObject().put("f", fileId).put("md5", md5).put("lh", lh).put("name", name));
  }

  static void push(Ctx x, JSONObject L, Remote R) throws Exception {
    // Attachments first, so anyone who sees the note can open its files.
    for (String name : NoteCodec.media(L.optString("body"))) {
      if (x.media.containsKey(name)) continue;
      File f = x.s.mediaFile(name);
      if (!f.isFile()) continue;
      x.media.put(name, x.d.upload(x.files, name, Sync.mime(name), f));
      x.res.mediaUp++;
    }
    String want = NoteCodec.folder(L);
    String wantId = x.folders.get(want);
    // Notes stay where people filed them (Clippings, their own subfolders) unless archiving or
    // trashing has to take them out of sight, or they come back from the archive / trash.
    String folder = wantId;
    if (R != null && !x.managedIds.contains(R.folder) && want.equals("Notes")) folder = R.folder;
    Set<String> used = x.names.get(folder);
    if (used == null) x.names.put(folder, used = new HashSet<>());
    boolean move = R != null && !R.folder.equals(folder);
    String wanted = NoteCodec.fileName(L);
    String name = R != null ? R.name : "";
    // A note written in Obsidian without a title keeps its file name; ours follow the title.
    boolean keepName = R != null && L.optString("title").trim().isEmpty() && !move;
    if (!keepName && (!wanted.equals(name) || move)) {
      String base = wanted.substring(0, wanted.length() - 3);
      name = wanted;
      for (int i = 2; used.contains(name) && !(R != null && !move && name.equals(R.name)); i++) name = base + " (" + i + ").md";
      used.add(name);
    }
    byte[] body = NoteCodec.render(L).getBytes(StandardCharsets.UTF_8);
    JSONObject r = x.d.put(R != null ? R.fileId : "", folder, name, body, move ? folder : "", move ? R.folder : "");
    record(x, L.getString("id"), r.getString("id"), r.optString("md5Checksum"), Sync.hash(L), r.optString("name", name));
    x.res.up++;
  }

  static void pull(Ctx x, String id, JSONObject L, Remote R) throws Exception {
    JSONObject theirs = read(x, R);
    fetchMedia(x, theirs);
    if (!theirs.getString("id").equals(id)) throw new IOException("ノートのidが一致しません");
    // A note in the Trash/Archive folder is trashed/archived even if its frontmatter says
    // otherwise (it was moved there in Obsidian or the file manager).
    if (R.folder.equals(x.folders.get("Trash")) && theirs.optLong("trashed") == 0) theirs.put("trashed", Math.max(R.modified, 1));
    if (R.folder.equals(x.folders.get("Archive"))) theirs.put("archived", true);
    if (R.folder.equals(x.folders.get("Notes"))) {
      theirs.put("archived", false);
      theirs.put("trashed", 0);
    }
    if (L != null) {
      JSONObject current = x.s.find(id);
      if (current == null || !Sync.hash(current).equals(Sync.hash(L))) return;
      for (String k : Sync.LOCAL) if (current.has(k)) theirs.put(k, current.get(k));
    }
    boolean needsId = theirs.optBoolean("vaultNewId");
    theirs.remove("vaultNewId");
    x.s.putSynced(theirs);
    if (needsId || !NoteCodec.folder(theirs).equals(folderName(x, R.folder)) && x.managedIds.contains(R.folder)) {
      // Written by hand in Obsidian: write it back once so it carries its id.
      push(x, theirs, R);
      x.res.up--;
    } else record(x, id, R.fileId, R.md5, Sync.hash(theirs), R.name);
    x.res.down++;
  }

  static String folderName(Ctx x, String folderId) {
    for (Map.Entry<String, String> e : x.folders.entrySet()) if (e.getValue().equals(folderId)) return e.getKey();
    return "";
  }

  static void fetchMedia(Ctx x, JSONObject p) throws Exception {
    for (String name : NoteCodec.media(p.optString("body"))) {
      File f = x.s.mediaFile(name);
      if (f.isFile() || !x.media.containsKey(name)) continue;
      File part = new File(f.getPath() + ".part");
      try {
        x.d.download(x.media.get(name), part);
        if (!part.renameTo(f)) throw new IOException("ファイルを保存できません");
        x.res.mediaDown++;
      } finally {
        part.delete();
      }
    }
  }
}
