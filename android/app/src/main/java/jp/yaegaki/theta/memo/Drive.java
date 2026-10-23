package jp.yaegaki.theta.memo;

import android.net.Uri;
import java.io.*;
import java.net.*;
import org.json.*;

// Minimal Drive REST v3 client. The access token comes from Google Play services
// (AuthorizationClient) and is never stored by the app.
public final class Drive {
  static final String API = "https://www.googleapis.com/drive/v3/";
  static final String FOLDER = "application/vnd.google-apps.folder";
  final String token;

  public Drive(String token) {
    this.token = token;
  }

  private HttpURLConnection open(String path, String method) throws Exception {
    HttpURLConnection con = (HttpURLConnection) new URL(API + path).openConnection();
    con.setConnectTimeout(20000);
    con.setReadTimeout(120000);
    con.setRequestProperty("Authorization", "Bearer " + token);
    if (method.equals("PATCH")) {
      // HttpURLConnection has no PATCH; Google APIs accept the override header.
      con.setRequestMethod("POST");
      con.setRequestProperty("X-HTTP-Method-Override", "PATCH");
    } else con.setRequestMethod(method);
    return con;
  }

  private JSONObject json(String path, String method, JSONObject body) throws Exception {
    HttpURLConnection con = open(path, method);
    try {
      if (body != null) {
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try (OutputStream out = con.getOutputStream()) {
          out.write(body.toString().getBytes("UTF-8"));
        }
      }
      int status = con.getResponseCode();
      InputStream stream = status < 400 ? con.getInputStream() : con.getErrorStream();
      String text = "";
      if (stream != null)
        try (InputStream in = stream) {
          text = new String(Store.read(in, 8 * 1024 * 1024), "UTF-8");
        }
      if (status >= 400) throw failure(status, text);
      return text.isEmpty() ? new JSONObject() : new JSONObject(text);
    } finally {
      con.disconnect();
    }
  }

  static IOException failure(int status, String text) {
    String message = "";
    try {
      message = new JSONObject(text).getJSONObject("error").optString("message");
    } catch (Exception ignored) {
    }
    return new IOException(
        "Google Drive HTTP "
            + status
            + "\n"
            + message
            + (status == 401 || status == 403
                ? "\nDriveへのアクセスが許可されていません。もう一度お試しいただき、許可画面で「許可」を選んでください。"
                : status == 404 ? "\nフォルダが見つかりません。メニューからフォルダを設定し直してください。" : ""));
  }

  static String q(String s) {
    return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
  }

  private JSONArray list(String query, String order) throws Exception {
    JSONArray out = new JSONArray();
    String page = "";
    do {
      JSONObject r =
          json(
              "files?pageSize=200&supportsAllDrives=true&includeItemsFromAllDrives=true"
                  + "&fields="
                  + Uri.encode("nextPageToken,files(id,name,mimeType,size)")
                  + "&orderBy="
                  + Uri.encode(order)
                  + "&q="
                  + Uri.encode(query)
                  + (page.isEmpty() ? "" : "&pageToken=" + Uri.encode(page)),
              "GET",
              null);
      JSONArray files = r.optJSONArray("files");
      if (files != null) for (int i = 0; i < files.length(); i++) out.put(files.get(i));
      page = r.optString("nextPageToken");
    } while (!page.isEmpty() && out.length() < 1000);
    return out;
  }

  // Unlimited listing for sync (folders can hold thousands of cards).
  public JSONArray listAll(String query, String fields) throws Exception {
    JSONArray out = new JSONArray();
    String page = "";
    do {
      JSONObject r =
          json(
              "files?pageSize=1000&supportsAllDrives=true&includeItemsFromAllDrives=true"
                  + "&fields="
                  + Uri.encode("nextPageToken,files(" + fields + ")")
                  + "&q="
                  + Uri.encode(query)
                  + (page.isEmpty() ? "" : "&pageToken=" + Uri.encode(page)),
              "GET",
              null);
      JSONArray files = r.optJSONArray("files");
      if (files != null) for (int i = 0; i < files.length(); i++) out.put(files.get(i));
      page = r.optString("nextPageToken");
    } while (!page.isEmpty());
    return out;
  }

  // Finds a folder by a private marker property (not by name, which the user may change).
  public String markedFolder(String parent, String key, String name) throws Exception {
    String q =
        "appProperties has { key=" + q(key) + " and value='1' } and mimeType=" + q(FOLDER) + " and trashed=false"
            + (parent.equals("root") ? "" : " and " + q(parent) + " in parents");
    JSONArray found = listAll(q, "id");
    if (found.length() > 0) return found.getJSONObject(0).getString("id");
    return json(
            "files?supportsAllDrives=true&fields=id",
            "POST",
            new JSONObject()
                .put("name", name)
                .put("mimeType", FOLDER)
                .put("parents", new JSONArray().put(parent))
                .put("appProperties", new JSONObject().put(key, "1")))
        .getString("id");
  }

  public String text(String id) throws Exception {
    HttpURLConnection con = open("files/" + Uri.encode(id) + "?alt=media&supportsAllDrives=true", "GET");
    try {
      int status = con.getResponseCode();
      InputStream stream = status < 400 ? con.getInputStream() : con.getErrorStream();
      String text = "";
      if (stream != null)
        try (InputStream in = stream) {
          text = new String(Store.read(in, 16 * 1024 * 1024), "UTF-8");
        }
      if (status >= 400) throw failure(status, text);
      return text;
    } finally {
      con.disconnect();
    }
  }

  // Creates (fileId empty) or replaces a small JSON file with its metadata in one multipart call.
  public String putJson(String fileId, String parent, String name, JSONObject appProps, String body)
      throws Exception {
    JSONObject meta = new JSONObject().put("appProperties", appProps);
    if (fileId.isEmpty()) meta.put("name", name).put("parents", new JSONArray().put(parent)).put("mimeType", "application/json");
    String boundary = "meishi" + java.util.UUID.randomUUID().toString().replace("-", "");
    byte[] payload =
        ("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + meta
                + "\r\n--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + body
                + "\r\n--" + boundary + "--\r\n")
            .getBytes("UTF-8");
    HttpURLConnection con =
        (HttpURLConnection)
            new URL(
                    "https://www.googleapis.com/upload/drive/v3/files"
                        + (fileId.isEmpty() ? "" : "/" + Uri.encode(fileId))
                        + "?uploadType=multipart&supportsAllDrives=true&fields=id")
                .openConnection();
    con.setConnectTimeout(20000);
    con.setReadTimeout(120000);
    con.setRequestProperty("Authorization", "Bearer " + token);
    con.setRequestMethod("POST");
    if (!fileId.isEmpty()) con.setRequestProperty("X-HTTP-Method-Override", "PATCH");
    con.setRequestProperty("Content-Type", "multipart/related; boundary=" + boundary);
    con.setDoOutput(true);
    con.setFixedLengthStreamingMode(payload.length);
    try {
      try (OutputStream out = con.getOutputStream()) {
        out.write(payload);
      }
      return new JSONObject(readBody(con)).getString("id");
    } finally {
      con.disconnect();
    }
  }

  // Resumable upload so large recordings stream from disk instead of memory.
  public String upload(String parent, String name, String mime, File f) throws Exception {
    HttpURLConnection start =
        (HttpURLConnection)
            new URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&supportsAllDrives=true&fields=id")
                .openConnection();
    String location;
    try {
      start.setConnectTimeout(20000);
      start.setReadTimeout(60000);
      start.setRequestMethod("POST");
      start.setRequestProperty("Authorization", "Bearer " + token);
      start.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
      start.setRequestProperty("X-Upload-Content-Type", mime);
      start.setRequestProperty("X-Upload-Content-Length", "" + f.length());
      start.setDoOutput(true);
      try (OutputStream out = start.getOutputStream()) {
        out.write(
            new JSONObject().put("name", name).put("parents", new JSONArray().put(parent)).toString().getBytes("UTF-8"));
      }
      int status = start.getResponseCode();
      if (status >= 400) throw failure(status, readBody(start));
      location = start.getHeaderField("Location");
      if (location == null) throw new IOException("アップロードを開始できませんでした");
    } finally {
      start.disconnect();
    }
    HttpURLConnection put = (HttpURLConnection) new URL(location).openConnection();
    try {
      put.setConnectTimeout(20000);
      put.setReadTimeout(300000);
      put.setRequestMethod("PUT");
      put.setRequestProperty("Content-Type", mime);
      put.setFixedLengthStreamingMode(f.length());
      put.setDoOutput(true);
      try (InputStream in = new FileInputStream(f);
          OutputStream out = put.getOutputStream()) {
        Store.copy(in, out, Long.MAX_VALUE);
      }
      return new JSONObject(readBody(put)).getString("id");
    } finally {
      put.disconnect();
    }
  }

  static String readBody(HttpURLConnection con) throws Exception {
    int status = con.getResponseCode();
    InputStream stream = status < 400 ? con.getInputStream() : con.getErrorStream();
    String text = "";
    if (stream != null)
      try (InputStream in = stream) {
        text = new String(Store.read(in, 8 * 1024 * 1024), "UTF-8");
      }
    if (status >= 400) throw failure(status, text);
    return text;
  }

  // Folder by its visible name (Vault folders may be created by people or other tools).
  public String folderNamed(String parent, String name, boolean create) throws Exception {
    JSONArray found =
        listAll(
            q(parent) + " in parents and mimeType=" + q(FOLDER) + " and trashed=false and name=" + q(name), "id,createdTime");
    if (found.length() > 0) return found.getJSONObject(0).getString("id");
    if (!create) return "";
    return json(
            "files?supportsAllDrives=true&fields=id",
            "POST",
            new JSONObject().put("name", name).put("mimeType", FOLDER).put("parents", new JSONArray().put(parent)))
        .getString("id");
  }

  // Creates or replaces a small file (content + name, optionally moving folders) in one call.
  // Returns the file's id, name and md5Checksum.
  public JSONObject putFile(
      String fileId, String parent, String name, String mime, byte[] body, String addParent, String removeParent)
      throws Exception {
    JSONObject meta = new JSONObject().put("name", name);
    if (fileId.isEmpty()) meta.put("parents", new JSONArray().put(parent)).put("mimeType", mime);
    String boundary = "theta" + java.util.UUID.randomUUID().toString().replace("-", "");
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    payload.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + meta + "\r\n--" + boundary + "\r\nContent-Type: " + mime + "\r\n\r\n").getBytes("UTF-8"));
    payload.write(body);
    payload.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
    byte[] bytes = payload.toByteArray();
    String query = "uploadType=multipart&supportsAllDrives=true&fields=" + Uri.encode("id,name,md5Checksum,modifiedTime");
    if (!fileId.isEmpty() && !addParent.isEmpty())
      query += "&addParents=" + Uri.encode(addParent) + "&removeParents=" + Uri.encode(removeParent);
    HttpURLConnection con =
        (HttpURLConnection)
            new URL("https://www.googleapis.com/upload/drive/v3/files" + (fileId.isEmpty() ? "" : "/" + Uri.encode(fileId)) + "?" + query)
                .openConnection();
    con.setConnectTimeout(20000);
    con.setReadTimeout(120000);
    con.setRequestProperty("Authorization", "Bearer " + token);
    con.setRequestMethod("POST");
    if (!fileId.isEmpty()) con.setRequestProperty("X-HTTP-Method-Override", "PATCH");
    con.setRequestProperty("Content-Type", "multipart/related; boundary=" + boundary);
    con.setDoOutput(true);
    con.setFixedLengthStreamingMode(bytes.length);
    try {
      try (OutputStream out = con.getOutputStream()) {
        out.write(bytes);
      }
      return new JSONObject(readBody(con));
    } finally {
      con.disconnect();
    }
  }

  // A file's current state by id, or null if it no longer exists.
  public JSONObject meta(String fileId) throws Exception {
    try {
      return json(
          "files/" + Uri.encode(fileId) + "?supportsAllDrives=true&fields=" + Uri.encode("id,name,md5Checksum,modifiedTime,parents,trashed"),
          "GET",
          null);
    } catch (IOException e) {
      if (String.valueOf(e.getMessage()).contains("HTTP 404")) return null;
      throw e;
    }
  }

  public void trashTo(String fileId, String from, String trashFolder) throws Exception {
    move(fileId, from, trashFolder);
  }

  public JSONArray folders(String parent) throws Exception {
    return list(
        q(parent) + " in parents and mimeType=" + q(FOLDER) + " and trashed=false", "name");
  }

  public void download(String id, File to) throws Exception {
    HttpURLConnection con = open("files/" + Uri.encode(id) + "?alt=media&supportsAllDrives=true", "GET");
    try {
      int status = con.getResponseCode();
      if (status >= 400) {
        String text = "";
        InputStream err = con.getErrorStream();
        if (err != null)
          try (InputStream in = err) {
            text = new String(Store.read(in, 1024 * 1024), "UTF-8");
          }
        throw failure(status, text);
      }
      try (InputStream in = con.getInputStream();
          OutputStream out = new FileOutputStream(to)) {
        Store.copy(in, out, 1024L * 1024 * 1024);
      }
    } finally {
      con.disconnect();
    }
  }

  public String subfolder(String parent, String name) throws Exception {
    JSONArray found =
        list(
            q(parent)
                + " in parents and mimeType="
                + q(FOLDER)
                + " and trashed=false and name="
                + q(name),
            "name");
    if (found.length() > 0) return found.getJSONObject(0).getString("id");
    return json(
            "files?supportsAllDrives=true&fields=id",
            "POST",
            new JSONObject()
                .put("name", name)
                .put("mimeType", FOLDER)
                .put("parents", new JSONArray().put(parent)))
        .getString("id");
  }

  public void rename(String id, String name) throws Exception {
    json("files/" + Uri.encode(id) + "?supportsAllDrives=true&fields=id", "PATCH", new JSONObject().put("name", name));
  }

  public void move(String id, String from, String to) throws Exception {
    json(
        "files/"
            + Uri.encode(id)
            + "?supportsAllDrives=true&fields=id&addParents="
            + Uri.encode(to)
            + "&removeParents="
            + Uri.encode(from),
        "PATCH",
        new JSONObject());
  }
}
