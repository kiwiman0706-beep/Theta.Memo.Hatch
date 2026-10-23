package jp.yaegaki.theta.memo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.json.*;

// Google Keep from Google Takeout (takeout-*.zip, "Keep" folder): one JSON per note, attachments
// (photos, drawings, recordings) next to it. Takeout splits large exports into several ZIPs, and
// a note's attachment can land in another part, so all parts are indexed before importing.
// Re-importing the same export skips notes already brought in (import_key keep:<file>).
public final class KeepImport {
  public static class Result {
    public int imported, skipped, trashedSkipped, missingFiles, attachments;
    public final List<String> errors = new ArrayList<>();

    public String text() {
      StringBuilder b = new StringBuilder("Google Keepから " + imported + "件を取り込みました");
      List<String> p = new ArrayList<>();
      if (attachments > 0) p.add("添付 " + attachments + "件");
      if (skipped > 0) p.add("取り込み済みのため省略 " + skipped + "件");
      if (trashedSkipped > 0) p.add("Keepのゴミ箱にあったもの " + trashedSkipped + "件は省略");
      if (missingFiles > 0) p.add("見つからない添付 " + missingFiles + "件");
      if (!p.isEmpty()) b.append("（").append(String.join(" / ", p)).append("）");
      for (int i = 0; i < Math.min(errors.size(), 10); i++) b.append("\n").append(errors.get(i));
      if (errors.size() > 10) b.append("\n…ほか ").append(errors.size() - 10).append("件");
      return b.toString();
    }
  }

  static final Map<String, String> COLORS = new HashMap<>();

  static {
    String[][] m = {
      {"DEFAULT", ""}, {"RED", "red"}, {"ORANGE", "orange"}, {"YELLOW", "yellow"}, {"GREEN", "green"}, {"TEAL", "teal"},
      {"BLUE", "blue"}, {"CERULEAN", "darkblue"}, {"PURPLE", "purple"}, {"PINK", "pink"}, {"BROWN", "brown"}, {"GRAY", "gray"}
    };
    for (String[] c : m) COLORS.put(c[0], c[1]);
  }

  static final class Entry {
    ZipFile zip;
    ZipEntry e;
  }

  // Anything inside a folder named Keep (Takeout keeps this name in every language).
  static boolean inKeep(String path) {
    String p = "/" + path.replace('\\', '/');
    return p.contains("/Keep/");
  }

  public static Result run(Store s, List<File> zips, boolean includeTrashed, VaultSync.Progress progress) throws Exception {
    Result res = new Result();
    List<ZipFile> open = new ArrayList<>();
    try {
      Map<String, Entry> files = new HashMap<>(); // base name -> entry
      List<Entry> notes = new ArrayList<>();
      for (File z : zips) {
        ZipFile zip;
        try {
          zip = new ZipFile(z, StandardCharsets.UTF_8);
        } catch (ZipException e) {
          zip = new ZipFile(z, java.nio.charset.Charset.forName("windows-31j"));
        }
        open.add(zip);
        for (Enumeration<? extends ZipEntry> it = zip.entries(); it.hasMoreElements(); ) {
          ZipEntry e = it.nextElement();
          if (e.isDirectory() || !inKeep(e.getName())) continue;
          Entry x = new Entry();
          x.zip = zip;
          x.e = e;
          String base = e.getName().substring(e.getName().lastIndexOf('/') + 1);
          if (base.toLowerCase(Locale.ROOT).endsWith(".json")) notes.add(x);
          else files.put(base, x);
        }
      }
      if (notes.isEmpty()) throw new IOException("Keepのデータが見つかりません。Google TakeoutでKeepを選んで書き出したZIPを選んでください");
      Set<String> have = new HashSet<>();
      for (JSONObject n : s.all()) if (!n.optString("importKey").isEmpty()) have.add(n.optString("importKey"));
      int i = 0;
      for (Entry x : notes) {
        if (++i % 20 == 0) progress.update("Keepから取り込み中 " + i + " / " + notes.size());
        String base = x.e.getName().substring(x.e.getName().lastIndexOf('/') + 1);
        String key = "keep:" + base.replaceFirst("(?i)\\.json$", "");
        if (have.contains(key)) {
          res.skipped++;
          continue;
        }
        try {
          JSONObject k;
          try (InputStream in = x.zip.getInputStream(x.e)) {
            k = new JSONObject(new String(Store.read(in, 16 * 1024 * 1024), StandardCharsets.UTF_8));
          }
          if (!k.has("userEditedTimestampUsec") && !k.has("textContent") && !k.has("listContent")) continue; // not a note
          if (k.optBoolean("isTrashed") && !includeTrashed) {
            res.trashedSkipped++;
            continue;
          }
          JSONObject n = convert(k, key, name -> copy(s, files.get(name), name, res));
          s.putSynced(n);
          have.add(key);
          res.imported++;
        } catch (Exception e) {
          res.errors.add(base + "：" + e.getMessage());
        }
      }
    } finally {
      for (ZipFile z : open)
        try {
          z.close();
        } catch (IOException ignored) {
        }
    }
    return res;
  }

  interface Files {
    // Copies a Keep attachment into the store; returns its new local name, or null if missing.
    String get(String keepName) throws Exception;
  }

  static String copy(Store s, Entry x, String keepName, Result res) throws Exception {
    if (x == null) {
      res.missingFiles++;
      return null;
    }
    String ext = keepName.contains(".") ? keepName.substring(keepName.lastIndexOf('.')) : "";
    String name = Store.newName(ext);
    File f = s.mediaFile(name);
    try (InputStream in = x.zip.getInputStream(x.e);
        OutputStream out = new FileOutputStream(f)) {
      Store.copy(in, out, 2L * 1024 * 1024 * 1024);
    }
    res.attachments++;
    return name;
  }

  static long usec(JSONObject k, String key) {
    long v = k.optLong(key, 0);
    return v > 0 ? v / 1000 : 0;
  }

  // One Keep note (Takeout JSON) as a note record. Pure, so it is unit-tested.
  static JSONObject convert(JSONObject k, String importKey, Files files) throws Exception {
    JSONObject n = Store.empty();
    n.put("importKey", importKey);
    n.put("title", k.optString("title").trim());
    String color = COLORS.get(k.optString("color", "DEFAULT"));
    n.put("color", color == null ? "" : color);
    n.put("pinned", k.optBoolean("isPinned"));
    n.put("archived", k.optBoolean("isArchived"));
    long edited = usec(k, "userEditedTimestampUsec"), created = usec(k, "createdTimestampUsec");
    if (created == 0) created = edited;
    if (edited == 0) edited = created;
    if (created > 0) n.put("created", created);
    if (edited > 0) n.put("updated", edited);
    if (k.optBoolean("isTrashed")) n.put("trashed", Math.max(edited, 1));
    JSONArray labels = new JSONArray();
    JSONArray kl = k.optJSONArray("labels");
    for (int i = 0; kl != null && i < kl.length(); i++) {
      String l = kl.getJSONObject(i).optString("name").trim().replaceAll("\\s+", "_");
      if (!l.isEmpty()) labels.put(l);
    }
    n.put("labels", labels);

    List<String> parts = new ArrayList<>();
    // Keep shows pictures and drawings on top of the card, recordings with the text.
    List<String> pictures = new ArrayList<>(), others = new ArrayList<>();
    JSONArray att = k.optJSONArray("attachments");
    for (int i = 0; att != null && i < att.length(); i++) {
      JSONObject a = att.getJSONObject(i);
      String path = a.optString("filePath");
      if (path.isEmpty()) continue;
      String keepName = path.substring(path.replace('\\', '/').lastIndexOf('/') + 1);
      String local = files.get(keepName);
      if (local == null) {
        // Takeout sometimes names the file with a different extension than the JSON says.
        String stem = keepName.contains(".") ? keepName.substring(0, keepName.lastIndexOf('.')) : keepName;
        for (String ext : new String[] {".jpg", ".jpeg", ".png", ".gif", ".webp", ".3gp", ".m4a", ".mp3", ".amr"}) {
          if (local != null) break;
          if (!(stem + ext).equals(keepName)) local = files.get(stem + ext);
        }
      }
      if (local == null) continue;
      String embed = "![[" + NoteCodec.path(local) + "]]";
      if (a.optString("mimetype").startsWith("image/") || local.matches("(?i).*\\.(jpe?g|png|gif|webp)$")) pictures.add(embed);
      else others.add(embed);
    }
    if (!pictures.isEmpty()) parts.add(String.join("\n", pictures));
    if (!others.isEmpty()) parts.add(String.join("\n", others));

    JSONArray list = k.optJSONArray("listContent");
    if (list != null && list.length() > 0) {
      StringBuilder b = new StringBuilder();
      // Keep lists unchecked items first, checked ones below; keep that order.
      for (int pass = 0; pass < 2; pass++)
        for (int i = 0; i < list.length(); i++) {
          JSONObject it = list.getJSONObject(i);
          boolean checked = it.optBoolean("isChecked");
          if (checked != (pass == 1)) continue;
          String t = it.optString("text").replace("\r", "").replace("\n", " ").trim();
          if (b.length() > 0) b.append('\n');
          b.append(checked ? "- [x] " : "- [ ] ").append(t);
        }
      if (b.length() > 0) parts.add(b.toString());
    }
    String text = k.optString("textContent").replace("\r\n", "\n");
    String html = k.optString("textContentHtml");
    if (!html.isEmpty() && FORMATTED.matcher(html).find()) {
      String md = htmlToMarkdown(html);
      if (!md.trim().isEmpty()) text = md;
    }
    if (!text.trim().isEmpty()) parts.add(NoteCodec.trimEnd(text));

    JSONArray ann = k.optJSONArray("annotations");
    StringBuilder links = new StringBuilder();
    for (int i = 0; ann != null && i < ann.length(); i++) {
      JSONObject a = ann.getJSONObject(i);
      String url = a.optString("url");
      if (url.isEmpty()) continue;
      if (n.optString("source").isEmpty()) n.put("source", url);
      if (text.contains(url)) continue;
      String t = a.optString("title").trim().replace("[", "(").replace("]", ")");
      if (links.length() > 0) links.append('\n');
      links.append("- [").append(t.isEmpty() ? url : t).append("](").append(url.replace(")", "%29")).append(")");
    }
    if (links.length() > 0) parts.add(links.toString());
    n.put("body", String.join("\n\n", parts));
    return n;
  }

  static final Pattern FORMATTED = Pattern.compile("(?i)<(b|strong|i|em|u|s|h[1-6]|ul|ol)[\\s>]");

  // Keep's rich text (bold, italic, underline, headings) as Markdown. Small and forgiving.
  static String htmlToMarkdown(String html) {
    String s = html.replace("\r", "").replace("\n", "");
    s = s.replaceAll("(?i)<br\\s*/?>", "\n");
    s = s.replaceAll("(?i)<h1[^>]*>", "\n# ").replaceAll("(?i)<h2[^>]*>", "\n## ").replaceAll("(?i)<h[3-6][^>]*>", "\n### ");
    s = s.replaceAll("(?i)</h[1-6]>", "\n");
    s = s.replaceAll("(?i)<li[^>]*>", "\n- ").replaceAll("(?i)</li>", "");
    s = s.replaceAll("(?i)</?(ul|ol)[^>]*>", "\n");
    s = s.replaceAll("(?i)<(b|strong)(\\s[^>]*)?>", "**").replaceAll("(?i)</(b|strong)>", "**");
    s = s.replaceAll("(?i)<(i|em)(\\s[^>]*)?>", "*").replaceAll("(?i)</(i|em)>", "*");
    s = s.replaceAll("(?i)<(s|strike|del)(\\s[^>]*)?>", "~~").replaceAll("(?i)</(s|strike|del)>", "~~");
    s = s.replaceAll("(?i)<u(\\s[^>]*)?>", "<u>").replaceAll("(?i)</u>", "</u>");
    s = s.replaceAll("(?i)</p>\\s*<p[^>]*>", "\n").replaceAll("(?i)</?p[^>]*>", "\n");
    s = s.replaceAll("(?i)<(?!/?u>)[^>]+>", "");
    s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
    s = s.replaceAll("\\*\\*\\s*\\*\\*", "").replaceAll("(?m)^[ \\t]+$", "");
    s = s.replaceAll("\n{2,}", "\n");
    return s.trim();
  }
}
