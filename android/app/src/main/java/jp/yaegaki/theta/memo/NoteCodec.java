package jp.yaegaki.theta.memo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;
import org.json.*;
import org.snakeyaml.engine.v2.api.*;
import org.snakeyaml.engine.v2.common.FlowStyle;

// Note record ⇄ Obsidian Markdown (docs/THETA_MEMO_VAULT.md). Pure Java so it is unit-tested on
// the JVM. Unlike Meishi there are no app-generated sections: the whole body is the user's
// Markdown (text, "- [ ]" lists, tables, ![[…]] embeds of attachments, handwriting and recordings).
// Frontmatter carries the Keep-like card state. Unknown frontmatter keys are kept in "extra".
public final class NoteCodec {
  public static final String APP_DIR = "Memo";
  public static final String FILES = APP_DIR + "/_files/";
  public static final String KIND = "memo/note@1";

  // Frontmatter keys this app owns, in output order.
  static final String[] ORDER = {
    "theta", "rev", "id", "title", "color", "pinned", "archived", "trashed", "gtd", "important", "urgent", "due", "contexts",
    "waiting_for", "done", "parent", "up", "view", "tags", "source", "import_key", "created", "updated"
  };
  // GTD lists (docs/GTD.md). A note without "gtd" is an ordinary memo.
  public static final String[] GTD = {"inbox", "next", "waiting", "scheduled", "someday", "project", "reference", "done"};
  static final Set<String> OWN = new HashSet<>(Arrays.asList(ORDER));
  // Obsidian Web Clipper and other tools write these; they are read as ours when present.
  static final String[] CREATED_ALIASES = {"created", "date", "published"};

  public static final String[] COLORS = {
    "", "red", "orange", "yellow", "green", "teal", "blue", "darkblue", "purple", "pink", "brown", "gray"
  };

  // ---- time -----------------------------------------------------------------------

  static String iso(long ms) {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);
    f.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
    return f.format(new Date(ms));
  }

  public static long ms(Object v, long fallback) {
    if (v instanceof Number) return ((Number) v).longValue();
    if (v instanceof Date) return ((Date) v).getTime();
    if (v == null) return fallback;
    String s = v.toString().trim();
    if (s.endsWith("Z")) s = s.substring(0, s.length() - 1) + "+00:00";
    for (String pat :
        new String[] {
          "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss",
          "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"
        }) {
      try {
        SimpleDateFormat f = new SimpleDateFormat(pat, Locale.ROOT);
        f.setLenient(false);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
        ParsePosition pos = new ParsePosition(0);
        Date d = f.parse(s, pos);
        if (d != null && pos.getIndex() == s.length()) return d.getTime();
      } catch (Exception ignored) {
      }
    }
    return fallback;
  }

  static String stamp(long ms) {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HHmm", Locale.ROOT);
    f.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
    return f.format(new Date(ms));
  }

  // ---- media paths ----------------------------------------------------------------

  public static String path(String localName) {
    return FILES + localName;
  }

  static boolean safeName(String name) {
    return name != null && name.matches("[A-Za-z0-9_.-]+") && !name.contains("..") && !name.startsWith(".");
  }

  // Wiki embeds ![[Memo/_files/x.png|300]], links [[…]] and Markdown ![](Memo/_files/x.png).
  static final Pattern EMBED =
      Pattern.compile("!?\\[\\[([^\\]|#]+)(?:[#|][^\\]]*)?\\]\\]|!?\\[[^\\]]*\\]\\(<?([^)>\\s]+)>?(?:\\s+\"[^\"]*\")?\\)");

  // Attachment names (in Memo/_files/) referenced from a body, in order of appearance.
  public static List<String> media(String body) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    if (body == null) return new ArrayList<>(out);
    Matcher m = EMBED.matcher(body);
    while (m.find()) {
      String p = m.group(1) != null ? m.group(1) : m.group(2);
      if (p == null) continue;
      p = p.trim().replace('\\', '/');
      try {
        p = java.net.URLDecoder.decode(p.replace("+", "%2B"), "UTF-8");
      } catch (Exception ignored) {
      }
      int slash = p.lastIndexOf('/');
      String name = p.substring(slash + 1);
      String dir = slash < 0 ? "" : p.substring(0, slash + 1);
      if (!(dir.isEmpty() || dir.endsWith("_files/"))) continue;
      if (!safeName(name)) continue;
      out.add(name);
      // Handwriting keeps its strokes next to the picture so it can be edited again.
      if (name.endsWith(".ink.png")) out.add(name.substring(0, name.length() - 4) + ".json");
    }
    return new ArrayList<>(out);
  }

  // ---- render ---------------------------------------------------------------------

  static Dump dumper() {
    return new Dump(DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).setWidth(4000).setSplitLines(false).build());
  }

  static Object plain(Object v) throws JSONException {
    if (v instanceof JSONObject) {
      Map<String, Object> m = new LinkedHashMap<>();
      JSONObject o = (JSONObject) v;
      for (Iterator<String> it = o.keys(); it.hasNext(); ) {
        String k = it.next();
        m.put(k, plain(o.get(k)));
      }
      return m;
    }
    if (v instanceof JSONArray) {
      List<Object> l = new ArrayList<>();
      JSONArray a = (JSONArray) v;
      for (int i = 0; i < a.length(); i++) l.add(plain(a.get(i)));
      return l;
    }
    if (v == JSONObject.NULL) return null;
    return v;
  }

  static Object json(Object v) throws JSONException {
    if (v instanceof Map) {
      JSONObject o = new JSONObject();
      for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) o.put(String.valueOf(e.getKey()), json(e.getValue()));
      return o;
    }
    if (v instanceof Collection) {
      JSONArray a = new JSONArray();
      for (Object x : (Collection<?>) v) a.put(json(x));
      return a;
    }
    if (v == null) return JSONObject.NULL;
    if (v instanceof Date) return iso(((Date) v).getTime());
    if (v instanceof java.time.temporal.TemporalAccessor) return v.toString();
    if (v instanceof byte[]) return "";
    return v;
  }

  static Map<String, Object> front(JSONObject n, boolean withRev) throws JSONException {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("theta", KIND);
    if (withRev) m.put("rev", "");
    m.put("id", n.getString("id"));
    m.put("title", n.optString("title"));
    if (!n.optString("color").isEmpty()) m.put("color", n.optString("color"));
    if (n.optBoolean("pinned")) m.put("pinned", true);
    if (n.optBoolean("archived")) m.put("archived", true);
    if (n.optLong("trashed") > 0) m.put("trashed", iso(n.optLong("trashed")));
    if (!n.optString("gtd").isEmpty()) m.put("gtd", n.optString("gtd"));
    if (n.optBoolean("important")) m.put("important", true);
    if (n.optBoolean("urgent")) m.put("urgent", true);
    if (!n.optString("due").isEmpty()) m.put("due", n.optString("due"));
    JSONArray ctx = n.optJSONArray("contexts");
    if (ctx != null && ctx.length() > 0) {
      List<Object> t = new ArrayList<>();
      for (int i = 0; i < ctx.length(); i++) t.add(ctx.getString(i));
      m.put("contexts", t);
    }
    if (!n.optString("waitingFor").isEmpty()) m.put("waiting_for", n.optString("waitingFor"));
    if (n.optLong("done") > 0) m.put("done", iso(n.optLong("done")));
    if (!n.optString("parent").isEmpty()) {
      m.put("parent", n.optString("parent"));
      // For Obsidian (Breadcrumbs and plain links): the parent's file, filled in by sync.
      if (!n.optString("upName").isEmpty()) m.put("up", "[[" + n.optString("upName") + "]]");
    }
    if (!n.optString("view").isEmpty()) m.put("view", n.optString("view"));
    JSONArray labels = n.optJSONArray("labels");
    if (labels != null && labels.length() > 0) {
      List<Object> t = new ArrayList<>();
      for (int i = 0; i < labels.length(); i++) t.add(labels.getString(i));
      m.put("tags", t);
    }
    if (!n.optString("source").isEmpty()) m.put("source", n.optString("source"));
    if (!n.optString("importKey").isEmpty()) m.put("import_key", n.optString("importKey"));
    m.put("created", iso(n.optLong("created")));
    m.put("updated", iso(n.optLong("updated")));
    JSONObject extra = n.optJSONObject("extra");
    if (extra != null)
      for (Iterator<String> it = extra.keys(); it.hasNext(); ) {
        String k = it.next();
        if (!OWN.contains(k)) m.put(k, plain(extra.get(k)));
      }
    return m;
  }

  static String compose(Map<String, Object> fm, String body) {
    String yaml = dumper().dumpToString(fm);
    StringBuilder b = new StringBuilder("---\n").append(yaml);
    if (!yaml.endsWith("\n")) b.append('\n');
    b.append("---\n");
    if (body != null && !body.isEmpty()) b.append(body.endsWith("\n") ? body : body + "\n");
    return b.toString();
  }

  // Bodies are kept without trailing newlines so a round trip through a file is exact.
  public static String trimEnd(String s) {
    int e = s.length();
    while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) e--;
    return s.substring(0, e);
  }

  public static String render(JSONObject n) throws JSONException {
    String body = trimEnd(n.optString("body"));
    Map<String, Object> fm = front(n, true);
    fm.remove("rev");
    String rev = fingerprint(compose(fm, body));
    Map<String, Object> withRev = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : fm.entrySet()) {
      withRev.put(e.getKey(), e.getValue());
      if (e.getKey().equals("theta")) withRev.put("rev", rev);
    }
    return compose(withRev, body);
  }

  static String fingerprint(String s) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < 6; i++) b.append(String.format("%02x", d[i]));
      return b.toString();
    } catch (Exception e) {
      return "";
    }
  }

  // ---- parse ----------------------------------------------------------------------

  static final Pattern FRONT = Pattern.compile("\\A\\uFEFF?---[ \\t]*\\r?\\n(.*?)\\r?\\n---[ \\t]*(?:\\r?\\n|\\z)", Pattern.DOTALL);
  static final Pattern EMPTY_FRONT = Pattern.compile("\\A\\uFEFF?---[ \\t]*\\r?\\n---[ \\t]*(?:\\r?\\n|\\z)");

  // fileTitle: the file name without .md, used when the note has no title of its own.
  // fileTime: the file's modification time, for notes written by hand without dates.
  public static JSONObject parse(String md, String fileTitle, long fileTime) throws Exception {
    md = md.replace("\r\n", "\n");
    Map<String, Object> fm = new LinkedHashMap<>();
    String body = md;
    Matcher m = FRONT.matcher(md);
    if (m.find()) {
      Object y = new Load(LoadSettings.builder().build()).loadFromString(m.group(1));
      if (y != null && !(y instanceof Map)) throw new IllegalArgumentException("frontmatterを読めません");
      if (y != null) for (Map.Entry<?, ?> e : ((Map<?, ?>) y).entrySet()) fm.put(String.valueOf(e.getKey()), e.getValue());
      body = md.substring(m.end());
    } else {
      Matcher e = EMPTY_FRONT.matcher(md);
      if (e.find()) body = md.substring(e.end());
    }
    JSONObject n = new JSONObject();
    String id = fm.get("id") == null ? "" : String.valueOf(fm.get("id")).trim();
    if (id.isEmpty()) {
      id = UUID.randomUUID().toString();
      n.put("vaultNewId", true);
    }
    n.put("id", id);
    n.put("title", fm.containsKey("title") ? str(fm.get("title")) : fileTitle == null ? "" : fileTitle);
    String color = str(fm.get("color")).toLowerCase(Locale.ROOT);
    n.put("color", Arrays.asList(COLORS).contains(color) ? color : "");
    if (!color.isEmpty() && !Arrays.asList(COLORS).contains(color)) n.put("colorRaw", color);
    n.put("pinned", truthy(fm.get("pinned")));
    n.put("archived", truthy(fm.get("archived")));
    n.put("trashed", fm.get("trashed") == null ? 0 : ms(fm.get("trashed"), fileTime));
    JSONArray labels = new JSONArray();
    Object tags = fm.get("tags");
    if (tags instanceof Collection) {
      for (Object t : (Collection<?>) tags) if (t != null && !str(t).isEmpty()) labels.put(str(t).replaceFirst("^#", ""));
    } else if (tags != null) for (String t : str(tags).split("[,\\s]+")) if (!t.isEmpty()) labels.put(t.replaceFirst("^#", ""));
    n.put("labels", labels);
    Object source = fm.get("source");
    if (source == null) source = fm.get("url");
    n.put("source", source instanceof Collection ? (((Collection<?>) source).isEmpty() ? "" : str(((Collection<?>) source).iterator().next())) : str(source));
    n.put("importKey", str(fm.get("import_key")));
    String gtd = str(fm.get("gtd")).toLowerCase(Locale.ROOT);
    n.put("gtd", Arrays.asList(GTD).contains(gtd) ? gtd : "");
    if (!gtd.isEmpty() && !Arrays.asList(GTD).contains(gtd)) n.put("gtdRaw", str(fm.get("gtd")));
    n.put("important", truthy(fm.get("important")));
    n.put("urgent", truthy(fm.get("urgent")));
    String due = str(fm.get("due"));
    n.put("due", due.matches("\\d{4}-\\d{2}-\\d{2}.*") ? due.substring(0, 10) : "");
    JSONArray contexts = new JSONArray();
    Object cx = fm.get("contexts");
    if (cx instanceof Collection) {
      for (Object t : (Collection<?>) cx) if (t != null && !str(t).isEmpty()) contexts.put(str(t));
    } else if (cx != null) for (String t : str(cx).split("[,\\s]+")) if (!t.isEmpty()) contexts.put(t);
    n.put("contexts", contexts);
    n.put("waitingFor", str(fm.get("waiting_for")));
    n.put("done", fm.get("done") == null ? 0 : ms(fm.get("done"), fileTime));
    n.put("parent", str(fm.get("parent")));
    // A link written in Obsidian ("up: [[Parent]]") without our id: sync resolves it by file name.
    Object upv = fm.get("up");
    while (upv instanceof Collection && !((Collection<?>) upv).isEmpty()) upv = ((Collection<?>) upv).iterator().next();
    String up = upv instanceof Collection ? "" : str(upv);
    if (up.startsWith("[[")) up = up.replaceAll("^\\[\\[|\\]\\]$", "").replaceAll("[|#].*$", "");
    if (!up.isEmpty()) n.put("upName", up.substring(up.lastIndexOf('/') + 1).replaceFirst("(?i)\\.md$", ""));
    n.put("view", str(fm.get("view")).toLowerCase(Locale.ROOT).equals("mindmap") ? "mindmap" : "");
    long created = 0;
    for (String k : CREATED_ALIASES) if (created == 0 && fm.get(k) != null) created = ms(fm.get(k), 0);
    long updated = ms(fm.get("updated"), 0);
    if (created == 0) created = updated > 0 ? updated : fileTime;
    if (updated == 0) updated = Math.max(created, fileTime);
    n.put("created", created);
    n.put("updated", updated);
    n.put("body", trimEnd(body));
    JSONObject extra = new JSONObject();
    for (Map.Entry<String, Object> e : fm.entrySet())
      if (!OWN.contains(e.getKey()) && !(e.getKey().equals("url") && fm.get("source") == null)) extra.put(e.getKey(), json(e.getValue()));
    if (n.has("colorRaw")) {
      extra.put("color", n.remove("colorRaw"));
    }
    if (n.has("gtdRaw")) extra.put("gtd", n.remove("gtdRaw"));
    if (extra.length() > 0) n.put("extra", extra);
    return n;
  }

  // The note was changed by something other than this app since it was written (rev mismatch).
  public static boolean editedOutside(String md) {
    try {
      md = md.replace("\r\n", "\n");
      Matcher m = FRONT.matcher(md);
      if (!m.find()) return true;
      Object y = new Load(LoadSettings.builder().build()).loadFromString(m.group(1));
      if (!(y instanceof Map)) return true;
      Object rev = ((Map<?, ?>) y).get("rev");
      if (rev == null) return true;
      String rest = md.substring(m.end());
      String withoutRev = m.group(1).replaceFirst("(?m)^rev:.*\\n?", "");
      Map<?, ?> fm = (Map<?, ?>) new Load(LoadSettings.builder().build()).loadFromString(withoutRev);
      Map<String, Object> ordered = new LinkedHashMap<>();
      if (fm != null) for (Map.Entry<?, ?> e : fm.entrySet()) ordered.put(String.valueOf(e.getKey()), e.getValue());
      return !String.valueOf(rev).equals(fingerprint(compose(ordered, rest)));
    } catch (Exception e) {
      return true;
    }
  }

  static String str(Object o) {
    if (o == null) return "";
    if (o instanceof Date) return iso(((Date) o).getTime());
    return String.valueOf(o).trim();
  }

  static boolean truthy(Object o) {
    if (o instanceof Boolean) return (Boolean) o;
    String s = str(o).toLowerCase(Locale.ROOT);
    return s.equals("true") || s.equals("yes") || s.equals("1");
  }

  // ---- file names -----------------------------------------------------------------

  // Plain text of the first meaningful line, for titles of untitled notes.
  static String firstLine(String body) {
    if (body == null) return "";
    for (String line : body.split("\n")) {
      String t =
          line.replaceAll("!?\\[\\[[^\\]]*\\]\\]", "")
              .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")
              .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
              .replaceAll("^\\s*(#+|[-*+]\\s+\\[[ xX]\\]|[-*+>]|\\d+\\.|\\|)\\s*", "")
              .replaceAll("[*_`~|]", "")
              .trim();
      if (!t.isEmpty() && !t.matches("-{3,}")) return t;
    }
    return "";
  }

  public static String fileName(JSONObject n) {
    String base = n.optString("title").trim();
    if (base.isEmpty()) base = firstLine(n.optString("body"));
    base = base.replaceAll("[\\\\/:*?\"<>|#^\\[\\]\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
    base = base.replaceAll("^\\.+", "").trim();
    if (base.codePointCount(0, base.length()) > 40) base = base.substring(0, base.offsetByCodePoints(0, 40)).trim();
    if (base.isEmpty()) base = "メモ " + stamp(n.optLong("created", System.currentTimeMillis()));
    return base + ".md";
  }

  // Which managed folder a note belongs in.
  public static String folder(JSONObject n) {
    if (n.optLong("trashed") > 0) return "Trash";
    if (n.optBoolean("archived")) return "Archive";
    return "Notes";
  }
}
