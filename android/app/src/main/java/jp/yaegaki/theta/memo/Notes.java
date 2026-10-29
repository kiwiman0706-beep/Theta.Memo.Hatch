package jp.yaegaki.theta.memo;

import java.util.*;
import java.util.regex.*;
import org.json.*;

// Small rules about note bodies shared by the app, the widgets and the recorder. Pure Java.
public final class Notes {
  public static String embed(String name) {
    return "![[" + NoteCodec.path(name) + "]]";
  }

  public static String append(String body, String block) {
    body = NoteCodec.trimEnd(body == null ? "" : body);
    return body.isEmpty() ? block : body + "\n\n" + block;
  }

  // A recording's transcript is the quote right under its embed ("> …" lines).
  public static String setTranscript(String body, String name, String text) {
    String[] lines = body.split("\n", -1);
    List<String> out = new ArrayList<>();
    boolean done = false;
    for (int i = 0; i < lines.length; i++) {
      out.add(lines[i]);
      if (!done && lines[i].contains("_files/" + name + "]]")) {
        done = true;
        while (i + 1 < lines.length && lines[i + 1].startsWith(">")) i++;
        for (String t : text.trim().split("\n")) out.add(t.trim().isEmpty() ? ">" : "> " + t.trim());
      }
    }
    if (!done) return append(body, embed(name) + "\n> " + text.trim().replace("\n", "\n> "));
    return String.join("\n", out);
  }

  static final Pattern TASK = Pattern.compile("^(\\s*[-*+]\\s+\\[)([ xX])(\\]\\s?)(.*)$");

  // Plain lines for the widget: [{type: title|text|check|image, text, checked, line}].
  public static List<JSONObject> lines(JSONObject n, int max) throws JSONException {
    List<JSONObject> out = new ArrayList<>();
    String[] ls = n.optString("body").split("\n");
    boolean fence = false;
    for (int i = 0; i < ls.length && out.size() < max; i++) {
      String l = ls[i];
      if (l.trim().startsWith("```")) {
        fence = !fence;
        continue;
      }
      Matcher m = TASK.matcher(l);
      if (!fence && m.matches()) {
        out.add(new JSONObject().put("type", "check").put("text", inline(m.group(4))).put("checked", !m.group(2).equals(" ")).put("line", i));
        continue;
      }
      if (l.trim().matches("^\\|?\\s*:?-{2,}.*")) continue; // table separator
      String t = fence ? l : inline(l.replaceAll("^\\s*(#+|>|[-*+]|\\d+\\.)\\s+", "").replaceAll("^\\|\\s*|\\s*\\|\\s*$", "").replaceAll("\\s*\\|\\s*", "  "));
      if (t.trim().isEmpty()) continue;
      out.add(new JSONObject().put("type", l.startsWith("#") ? "heading" : "text").put("text", t).put("line", i));
    }
    return out;
  }

  static String inline(String s) {
    // Task details (Obsidian Tasks style) become a short date; the matrix tags are dropped.
    s = s.replaceAll("\\s*✅\\s*\\d{4}-\\d{2}-\\d{2}", "")
        .replaceAll("\\s*#(重要|緊急)(?=\\s|$)", "")
        .replaceAll("\\s*📅\\s*\\d{4}-0?(\\d{1,2})-0?(\\d{1,2})", " ($1/$2)");
    return s.replaceAll("!?\\[\\[[^\\]]*\\]\\]", "")
        .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")
        .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
        .replaceAll("(\\*\\*|__|~~|==|`)", "")
        .replaceAll("(?<![\\w*])\\*(?!\\s)([^*]+)\\*", "$1")
        .replaceAll("</?u>", "")
        .trim();
  }

  // Toggles the checkbox on one body line (widgets).
  public static String toggle(String body, int line) {
    String[] ls = body.split("\n", -1);
    if (line < 0 || line >= ls.length) return body;
    Matcher m = TASK.matcher(ls[line]);
    if (!m.matches()) return body;
    ls[line] = m.group(1) + (m.group(2).equals(" ") ? "x" : " ") + m.group(3) + m.group(4);
    return String.join("\n", ls);
  }

  public static String title(JSONObject n) {
    String t = n.optString("title").trim();
    return t.isEmpty() ? NoteCodec.firstLine(n.optString("body")) : t;
  }

  // Shown first in lists: pinned, then most recently edited. Trash and archive left out.
  public static List<JSONObject> active(List<JSONObject> all) {
    List<JSONObject> out = new ArrayList<>();
    for (JSONObject n : all) if (n.optLong("trashed") == 0 && !n.optBoolean("archived")) out.add(n);
    out.sort((a, b) -> a.optBoolean("pinned") != b.optBoolean("pinned") ? (a.optBoolean("pinned") ? -1 : 1) : Long.compare(b.optLong("updated"), a.optLong("updated")));
    return out;
  }

  // First picture-like attachment, for card / widget thumbnails.
  public static String cover(JSONObject n) {
    for (String m : NoteCodec.media(n.optString("body")))
      if (m.matches("(?i).*\\.(jpe?g|png|gif|webp|heic|pdf|mp4|mov|webm)$")) return m;
    return null;
  }

  public static final Map<String, Integer> COLOR = new HashMap<>();

  static {
    String[][] c = {
      {"", "#FFFFFF"}, {"red", "#FAAFA8"}, {"orange", "#F39F76"}, {"yellow", "#FFF8B8"}, {"green", "#E2F6D3"}, {"teal", "#B4DDD3"},
      {"blue", "#D4E4ED"}, {"darkblue", "#AECCDC"}, {"purple", "#D3BFDB"}, {"pink", "#F6E2DD"}, {"brown", "#E9E3D4"}, {"gray", "#EFEFF1"}
    };
    for (String[] x : c) COLOR.put(x[0], (int) Long.parseLong("FF" + x[1].substring(1), 16));
  }
}
