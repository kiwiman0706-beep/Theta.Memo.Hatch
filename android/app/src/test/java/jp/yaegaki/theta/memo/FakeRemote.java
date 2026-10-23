package jp.yaegaki.theta.memo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

// An in-memory Google Drive for sync tests: folders and files by id, md5 like Drive's.
final class FakeRemote implements VaultRemote {
  static final class F {
    String id, name, parent;
    byte[] body;
    boolean folder, trashed;
    long modified;
  }

  final Map<String, F> byId = new LinkedHashMap<>();
  int next = 1;
  long clock = 1_700_000_000_000L;

  F make(String parent, String name, boolean folder, byte[] body) {
    F f = new F();
    f.id = "f" + (next++);
    f.name = name;
    f.parent = parent;
    f.folder = folder;
    f.body = body;
    f.modified = clock += 1000;
    byId.put(f.id, f);
    return f;
  }

  static String md5(byte[] b) {
    try {
      byte[] d = MessageDigest.getInstance("MD5").digest(b);
      StringBuilder s = new StringBuilder();
      for (byte x : d) s.append(String.format("%02x", x));
      return s.toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public String folder(String parent, String name) {
    for (F f : byId.values()) if (f.folder && !f.trashed && f.parent.equals(parent) && f.name.equals(name)) return f.id;
    return make(parent, name, true, null).id;
  }

  // Path helper for tests: "Theta Vault/Memo/Notes".
  String path(String p) {
    String cur = "root";
    for (String part : p.split("/")) cur = folder(cur, part);
    return cur;
  }

  F file(String folderPath, String name) {
    String dir = path(folderPath);
    for (F f : byId.values()) if (!f.folder && !f.trashed && f.parent.equals(dir) && f.name.equals(name)) return f;
    return null;
  }

  List<F> filesIn(String folderPath) {
    String dir = path(folderPath);
    List<F> out = new ArrayList<>();
    for (F f : byId.values()) if (!f.folder && !f.trashed && f.parent.equals(dir)) out.add(f);
    return out;
  }

  // Simulates someone writing the file in Obsidian.
  F write(String folderPath, String name, String text) {
    F f = file(folderPath, name);
    if (f == null) return make(path(folderPath), name, false, text.getBytes(StandardCharsets.UTF_8));
    f.body = text.getBytes(StandardCharsets.UTF_8);
    f.modified = clock += 1000;
    return f;
  }

  public JSONArray files(String folder) throws Exception {
    JSONArray a = new JSONArray();
    for (F f : byId.values())
      if (!f.folder && !f.trashed && f.parent.equals(folder))
        a.put(new JSONObject().put("id", f.id).put("name", f.name).put("md5Checksum", md5(f.body)).put("modifiedTime", NoteCodec.iso(f.modified)));
    return a;
  }

  public String read(String fileId) {
    return new String(byId.get(fileId).body, StandardCharsets.UTF_8);
  }

  public JSONObject put(String fileId, String parent, String name, byte[] body, String moveTo, String moveFrom) throws Exception {
    F f;
    if (fileId.isEmpty()) f = make(parent, name, false, body);
    else {
      f = byId.get(fileId);
      f.name = name;
      f.body = body;
      f.modified = clock += 1000;
      if (!moveTo.isEmpty()) f.parent = moveTo;
    }
    return new JSONObject().put("id", f.id).put("name", f.name).put("md5Checksum", md5(f.body));
  }

  public String upload(String folder, String name, String mime, File file) throws Exception {
    try (InputStream in = new FileInputStream(file)) {
      return make(folder, name, false, Store.read(in, 1 << 26)).id;
    }
  }

  public void download(String fileId, File to) throws Exception {
    try (OutputStream out = new FileOutputStream(to)) {
      out.write(byId.get(fileId).body);
    }
  }

  public void trash(String fileId, String from, String trashFolder) {
    byId.get(fileId).parent = trashFolder;
  }

  public JSONObject meta(String fileId) throws Exception {
    F f = byId.get(fileId);
    if (f == null) return null;
    return new JSONObject()
        .put("id", f.id).put("name", f.name).put("md5Checksum", md5(f.body)).put("modifiedTime", NoteCodec.iso(f.modified))
        .put("parents", new JSONArray().put(f.parent)).put("trashed", f.trashed);
  }
}
