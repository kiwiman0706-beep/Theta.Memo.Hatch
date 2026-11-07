package app.thetahatch.memo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import org.json.*;

// The Theta Vault on a Synology NAS (File Station API through Tailscale), for VaultSync. Ids are
// the files' paths. A file's "md5" is its size and modification time (File Station has no cheap
// content hash), which is enough to notice that someone changed it. "root" is the folder chosen
// in the settings that holds "Theta Vault".
public final class NasRemote implements VaultRemote {
  final Nas nas;
  final String root;
  final File tmp;
  final Set<String> known = new HashSet<>();

  public NasRemote(Nas nas, String root, File tmpDir) {
    this.nas = nas;
    this.root = root.replaceAll("/+$", "");
    this.tmp = tmpDir;
    tmpDir.mkdirs();
  }

  static String dir(String path) {
    return path.substring(0, path.lastIndexOf('/'));
  }

  static String base(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  static String token(Nas.Item it) {
    return it.size + "-" + it.modified;
  }

  JSONObject entry(Nas.Item it) throws JSONException {
    return new JSONObject().put("id", it.path).put("name", it.name).put("md5Checksum", token(it)).put("modifiedTime", NoteCodec.iso(it.modified));
  }

  public String folder(String parent, String name) throws Exception {
    String p = parent.equals("root") ? root : parent;
    String path = p + "/" + name;
    if (known.contains(path)) return path;
    Nas.Item it = nas.info(path);
    if (it == null) nas.mkdir(p, name);
    else if (!it.dir) throw new IOException(path + " がフォルダではありません");
    known.add(path);
    return path;
  }

  public JSONArray files(String folder) throws Exception {
    JSONArray out = new JSONArray();
    for (Nas.Item it : nas.list(folder)) if (!it.dir) out.put(entry(it));
    return out;
  }

  File temp() {
    return new File(tmp, UUID.randomUUID().toString());
  }

  public String read(String fileId) throws Exception {
    File f = temp();
    try {
      nas.download(fileId, f);
      return Store.readText(f);
    } finally {
      f.delete();
    }
  }

  public JSONObject put(String fileId, String parent, String name, byte[] body, String moveTo, String moveFrom) throws Exception {
    String dir = parent;
    if (!fileId.isEmpty()) {
      String cur = fileId;
      if (!moveTo.isEmpty() && !dir(cur).equals(moveTo)) {
        nas.move(cur, moveTo);
        cur = moveTo + "/" + base(cur);
      }
      dir = dir(cur);
      if (!base(cur).equals(name)) nas.rename(cur, name);
    }
    File f = temp();
    try {
      try (FileOutputStream o = new FileOutputStream(f)) {
        o.write(body);
      }
      nas.upload(dir, name, f);
    } finally {
      f.delete();
    }
    Nas.Item it = nas.info(dir + "/" + name);
    if (it == null) throw new IOException(name + " を NAS に書き込めませんでした");
    return entry(it);
  }

  public String upload(String folder, String name, String mime, File f) throws Exception {
    nas.upload(folder, name, f);
    return folder + "/" + name;
  }

  public void download(String fileId, File to) throws Exception {
    nas.download(fileId, to);
  }

  public void trash(String fileId, String from, String trashFolder) throws Exception {
    nas.move(fileId, trashFolder);
  }

  // Where a note is now. A note moved in Obsidian keeps its file name, so it is looked for by
  // name under Memo/ when it is no longer where it was.
  public JSONObject meta(String fileId) throws Exception {
    Nas.Item it = nas.info(fileId);
    if (it == null) {
      String app = root + "/" + VaultSync.ROOT + "/" + NoteCodec.APP_DIR;
      for (Nas.Item f : nas.search(Collections.singletonList(app), base(fileId), 0, 20))
        if (f.name.equals(base(fileId)) && !f.path.contains("/.theta/")) {
          it = nas.info(f.path);
          break;
        }
    }
    if (it == null) return null;
    return entry(it).put("parents", new JSONArray().put(dir(it.path))).put("trashed", false);
  }
}
