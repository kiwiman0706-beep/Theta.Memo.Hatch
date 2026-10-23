package jp.yaegaki.theta.memo;

import java.io.File;
import org.json.*;

// What VaultSync needs (same interface as Theta Meishi Hatch) from wherever the vault lives. Google Drive implements it today; a local
// folder (desktop, Syncthing) or WebDAV can implement the same calls later.
public interface VaultRemote {
  // Child folder by name, created when missing.
  String folder(String parent, String name) throws Exception;

  // Files (not folders) directly in a folder: [{id, name, md5Checksum, modifiedTime}].
  JSONArray files(String folder) throws Exception;

  String read(String fileId) throws Exception;

  // Create (fileId "") or replace content and name; optionally move between folders.
  // Returns {id, name, md5Checksum}.
  JSONObject put(String fileId, String parent, String name, byte[] body, String moveTo, String moveFrom) throws Exception;

  String upload(String folder, String name, String mime, File f) throws Exception;

  void download(String fileId, File to) throws Exception;

  void trash(String fileId, String from, String trashFolder) throws Exception;

  // {id, name, md5Checksum, modifiedTime, parents, trashed} or null when gone.
  JSONObject meta(String fileId) throws Exception;

  final class OnDrive implements VaultRemote {
    final Drive d;

    public OnDrive(Drive d) {
      this.d = d;
    }

    public String folder(String parent, String name) throws Exception {
      return d.folderNamed(parent, name, true);
    }

    public JSONArray files(String folder) throws Exception {
      return d.listAll(
          Drive.q(folder) + " in parents and trashed=false and mimeType!='application/vnd.google-apps.folder'",
          "id,name,md5Checksum,modifiedTime");
    }

    public String read(String fileId) throws Exception {
      return d.text(fileId);
    }

    public JSONObject put(String fileId, String parent, String name, byte[] body, String moveTo, String moveFrom) throws Exception {
      String mime = name.endsWith(".json") ? "application/json" : "text/markdown";
      return d.putFile(fileId, parent, name, mime, body, moveTo, moveFrom);
    }

    public String upload(String folder, String name, String mime, File f) throws Exception {
      return d.upload(folder, name, mime, f);
    }

    public void download(String fileId, File to) throws Exception {
      d.download(fileId, to);
    }

    public void trash(String fileId, String from, String trashFolder) throws Exception {
      d.trashTo(fileId, from, trashFolder);
    }

    public JSONObject meta(String fileId) throws Exception {
      return d.meta(fileId);
    }
  }
}
