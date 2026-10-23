package jp.yaegaki.theta.memo;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;
import org.json.*;

// Scrapbook from the browser: a shared page becomes a note with its title, picture, summary
// and link (like the cards Obsidian Web Clipper makes on PC/Mac, which the vault reads too).
public final class Clipper {
  static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'）」』】]+");

  public static String firstUrl(String text) {
    if (text == null) return null;
    Matcher m = URL.matcher(text);
    return m.find() ? m.group().replaceAll("[.,、。)]+$", "") : null;
  }

  static String attr(String tag, String name) {
    Matcher m = Pattern.compile("(?i)\\b" + name + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))").matcher(tag);
    if (!m.find()) return null;
    return m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
  }

  static String unescape(String s) {
    if (s == null) return "";
    Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
    StringBuffer b = new StringBuffer();
    while (m.find()) {
      int cp;
      try {
        cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
      } catch (Exception e) {
        cp = '?';
      }
      m.appendReplacement(b, Matcher.quoteReplacement(new String(Character.toChars(cp))));
    }
    m.appendTail(b);
    return b.toString().replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&nbsp;", " ").replace("&amp;", "&").replaceAll("\\s+", " ").trim();
  }

  // {title, description, image, site} from a page's <head>. Pure, unit-tested.
  public static JSONObject meta(String html, String base) throws JSONException {
    JSONObject out = new JSONObject();
    Map<String, String> m = new HashMap<>();
    Matcher t = Pattern.compile("(?is)<meta\\b[^>]*>").matcher(html);
    while (t.find()) {
      String tag = t.group();
      String key = attr(tag, "property");
      if (key == null) key = attr(tag, "name");
      String content = attr(tag, "content");
      if (key != null && content != null && !m.containsKey(key.toLowerCase(Locale.ROOT))) m.put(key.toLowerCase(Locale.ROOT), unescape(content));
    }
    String title = m.get("og:title");
    if (title == null) title = m.get("twitter:title");
    if (title == null) {
      Matcher tt = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
      if (tt.find()) title = unescape(tt.group(1));
    }
    String desc = m.get("og:description");
    if (desc == null) desc = m.get("description");
    if (desc == null) desc = m.get("twitter:description");
    String image = m.get("og:image");
    if (image == null) image = m.get("og:image:url");
    if (image == null) image = m.get("twitter:image");
    if (image != null)
      try {
        image = new URL(new URL(base), image).toString();
      } catch (Exception e) {
        image = null;
      }
    out.put("title", title == null ? "" : title);
    out.put("description", desc == null ? "" : desc);
    out.put("image", image == null || !image.startsWith("https://") ? "" : image);
    out.put("site", m.containsKey("og:site_name") ? m.get("og:site_name") : "");
    return out;
  }

  static HttpURLConnection open(String url) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setConnectTimeout(15000);
    c.setReadTimeout(20000);
    c.setInstanceFollowRedirects(true);
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36");
    c.setRequestProperty("Accept-Language", "ja,en;q=0.8");
    return c;
  }

  public static JSONObject fetch(String url) throws Exception {
    HttpURLConnection c = open(url);
    try {
      if (c.getResponseCode() >= 400) throw new IOException("ページを開けません（HTTP " + c.getResponseCode() + "）");
      String type = String.valueOf(c.getContentType());
      if (!type.contains("html")) return new JSONObject().put("title", "").put("description", "").put("image", "").put("site", "");
      byte[] raw;
      try (InputStream in = c.getInputStream()) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; out.size() < 1_500_000 && (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        raw = out.toByteArray();
      }
      String charset = "UTF-8";
      Matcher cm = Pattern.compile("(?i)charset=([\\w-]+)").matcher(type);
      if (cm.find()) charset = cm.group(1);
      else {
        Matcher hm = Pattern.compile("(?i)<meta[^>]+charset=[\"']?([\\w-]+)").matcher(new String(raw, 0, Math.min(raw.length, 4096), StandardCharsets.ISO_8859_1));
        if (hm.find()) charset = hm.group(1);
      }
      String html;
      try {
        html = new String(raw, Charset.forName(charset));
      } catch (Exception e) {
        html = new String(raw, StandardCharsets.UTF_8);
      }
      return meta(html, c.getURL().toString());
    } finally {
      c.disconnect();
    }
  }

  static String download(Store s, String url) {
    try {
      HttpURLConnection c = open(url);
      try {
        if (c.getResponseCode() >= 400) return null;
        String type = String.valueOf(c.getContentType()).toLowerCase(Locale.ROOT);
        String ext = type.contains("png") ? ".png" : type.contains("webp") ? ".webp" : type.contains("gif") ? ".gif" : type.contains("image") ? ".jpg" : null;
        if (ext == null) return null;
        String name = Store.newName(ext);
        File f = s.mediaFile(name);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
          Store.copy(in, out, 20L * 1024 * 1024);
        } catch (IOException e) {
          f.delete();
          return null;
        }
        return name;
      } finally {
        c.disconnect();
      }
    } catch (Exception e) {
      return null;
    }
  }

  // The shared text (often "page title URL") and the page's own information as one note body.
  public static JSONObject enrich(Store s, JSONObject n, String url, String sharedText, boolean keepImage) throws Exception {
    JSONObject m;
    try {
      m = fetch(url);
    } catch (Exception e) {
      return n;
    }
    String title = m.optString("title");
    if (n.optString("title").isEmpty() && !title.isEmpty()) n.put("title", title);
    List<String> parts = new ArrayList<>();
    if (keepImage && !m.optString("image").isEmpty()) {
      String img = download(s, m.optString("image"));
      if (img != null) parts.add(Notes.embed(img));
    }
    if (!m.optString("description").isEmpty()) parts.add("> " + m.optString("description").replace("\n", "\n> "));
    String rest = sharedText == null ? "" : sharedText.replace(url, "").trim();
    if (!rest.isEmpty() && !rest.equals(title) && !rest.equals(n.optString("title"))) parts.add(rest);
    parts.add("[" + (m.optString("site").isEmpty() ? url : m.optString("site")).replace("[", "(").replace("]", ")") + "](" + url + ")");
    n.put("body", String.join("\n\n", parts));
    n.put("source", url);
    return n;
  }
}
