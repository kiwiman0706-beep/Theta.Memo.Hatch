package app.thetahatch.memo;

import java.util.*;
import java.util.regex.Pattern;
import org.json.*;

// Settings shared across the Theta Hatch series (docs/THETA_SHARED_SETTINGS.md), kept in the vault
// at .theta/settings.json; desktop/lib/series.js is the same logic (SeriesSettingsTest mirrors its
// tests). Locally: the last known document, which items this app follows, and whether the
// first-launch carry-over was decided. Secrets are never written or read here.
public final class SeriesSettings {
  public static final String ME = "memo";
  static final Map<String, String> LABELS = new LinkedHashMap<>();
  static final Pattern SECRET = Pattern.compile("(?i)key|token|secret|password");

  static {
    LABELS.put("ui.skin", "スキン");
    LABELS.put("gemini.model", "Geminiのモデル");
    LABELS.put("gemini.format", "Geminiの呼び出し形式");
    LABELS.put("google.account", "Googleアカウント");
    LABELS.put("calendar", "Googleカレンダーの登録先");
    LABELS.put("google.cloudReady", "Google Cloudの初期設定");
    LABELS.put("google.webClientId", "Web版のOAuthクライアントID");
    LABELS.put("nas.url", "NASの接続先");
    LABELS.put("nas.user", "NASのアカウント");
    LABELS.put("nas.shareRoot", "NASのTheta Vaultの場所");
  }

  public static final class Result {
    public JSONObject local;
    public String write; // new file content, or null
    public JSONObject apply = new JSONObject();
    public JSONArray offers = new JSONArray();
    public boolean broken;
  }

  static JSONObject empty() throws JSONException {
    return new JSONObject().put("spec", 1).put("updated", "").put("series", new JSONObject()).put("apps", new JSONObject());
  }

  public static JSONObject initial() throws JSONException {
    return new JSONObject().put("doc", empty()).put("follow", new JSONObject()).put("decided", false);
  }

  // Order-independent text of a JSON value, for comparing.
  static String canon(Object v) throws JSONException {
    if (v == null || v == JSONObject.NULL) return "null";
    if (v instanceof JSONObject) {
      JSONObject o = (JSONObject) v;
      List<String> keys = new ArrayList<>();
      for (Iterator<String> i = o.keys(); i.hasNext(); ) keys.add(i.next());
      Collections.sort(keys);
      StringBuilder b = new StringBuilder("{");
      for (String k : keys) b.append(JSONObject.quote(k)).append(':').append(canon(o.get(k))).append(',');
      return b.append('}').toString();
    }
    if (v instanceof JSONArray) {
      JSONArray a = (JSONArray) v;
      StringBuilder b = new StringBuilder("[");
      for (int i = 0; i < a.length(); i++) b.append(canon(a.get(i))).append(',');
      return b.append(']').toString();
    }
    if (v instanceof String) return JSONObject.quote((String) v);
    return String.valueOf(v);
  }

  static boolean same(Object a, Object b) throws JSONException {
    return canon(a).equals(canon(b));
  }

  static boolean blank(Object v) {
    return v == null || v == JSONObject.NULL || "".equals(v) || (v instanceof JSONObject && ((JSONObject) v).length() == 0);
  }

  static JSONObject copy(JSONObject o) throws JSONException {
    return new JSONObject(o.toString());
  }

  // The file's text → document; null when absent; throws when unreadable.
  static JSONObject parse(String text) throws JSONException {
    if (text == null || text.isEmpty()) return null;
    JSONObject j = new JSONObject(text);
    JSONObject doc = copy(j);
    doc.put("spec", j.optInt("spec", 1));
    JSONObject series = new JSONObject(), in = j.optJSONObject("series");
    if (in != null)
      for (Iterator<String> i = in.keys(); i.hasNext(); ) {
        String k = i.next();
        JSONObject e = in.optJSONObject(k);
        if (SECRET.matcher(k).find() || e == null || !e.has("value")) continue; // never secrets
        series.put(k, new JSONObject().put("value", e.get("value")).put("by", e.optString("by")).put("at", e.optString("at")));
      }
    doc.put("series", series);
    if (doc.optJSONObject("apps") == null) doc.put("apps", new JSONObject());
    return doc;
  }

  // Per item, the newer `at` wins; unknown top-level keys and other apps' sections are kept.
  static JSONObject merge(JSONObject a, JSONObject b) throws JSONException {
    JSONObject out = copy(a);
    for (Iterator<String> i = b.keys(); i.hasNext(); ) {
      String k = i.next();
      if (!k.equals("series") && !k.equals("apps")) out.put(k, b.get(k));
    }
    JSONObject series = copy(a.getJSONObject("series")), bs = b.getJSONObject("series");
    for (Iterator<String> i = bs.keys(); i.hasNext(); ) {
      String k = i.next();
      JSONObject e = bs.getJSONObject(k), cur = series.optJSONObject(k);
      if (cur == null || e.optString("at").compareTo(cur.optString("at")) > 0) series.put(k, e);
    }
    out.put("series", series);
    JSONObject apps = copy(a.optJSONObject("apps") == null ? new JSONObject() : a.getJSONObject("apps")), ba = b.optJSONObject("apps");
    for (Iterator<String> i = ba == null ? Collections.<String>emptyIterator() : ba.keys(); i.hasNext(); ) {
      String app = i.next();
      JSONObject merged = apps.optJSONObject(app) == null ? new JSONObject() : apps.getJSONObject(app);
      JSONObject v = ba.getJSONObject(app);
      for (Iterator<String> j = v.keys(); j.hasNext(); ) {
        String k = j.next();
        merged.put(k, v.get(k));
      }
      apps.put(app, merged);
    }
    out.put("apps", apps);
    String ua = a.optString("updated"), ub = b.optString("updated");
    out.put("updated", ua.compareTo(ub) >= 0 ? ua : ub);
    return out;
  }

  public static Result sync(JSONObject local, String text, JSONObject current, String me, String now) throws JSONException {
    Result r = new Result();
    local = copy(local == null ? initial() : local);
    JSONObject follow = local.getJSONObject("follow");
    JSONObject remote;
    try {
      remote = parse(text);
    } catch (JSONException e) {
      r.local = local;
      r.broken = true; // a file someone broke by hand is not overwritten
      return r;
    }
    JSONObject doc = merge(remote == null ? empty() : remote, local.optJSONObject("doc") == null ? empty() : local.getJSONObject("doc"));
    JSONObject series = doc.getJSONObject("series");
    Set<String> offered = new HashSet<>();
    if (!local.optBoolean("decided")) {
      for (Iterator<String> i = series.keys(); i.hasNext(); ) {
        String k = i.next();
        JSONObject e = series.getJSONObject(k);
        if (!e.optString("by").equals(me) && !follow.has(k) && LABELS.containsKey(k) && !same(e.get("value"), current.opt(k))) {
          r.offers.put(new JSONObject().put("key", k).put("label", LABELS.get(k)).put("value", e.get("value")).put("by", e.optString("by")).put("at", e.optString("at")));
          offered.add(k);
        }
      }
      if (offered.isEmpty()) local.put("decided", true);
    }
    for (Iterator<String> i = current.keys(); i.hasNext(); ) {
      String k = i.next();
      Object v = current.get(k);
      if (!blank(v) && !series.has(k) && !(follow.has(k) && !follow.getBoolean(k)) && !offered.contains(k)) {
        series.put(k, new JSONObject().put("value", v).put("by", me).put("at", now));
        follow.put(k, true);
      }
    }
    for (Iterator<String> i = series.keys(); i.hasNext(); ) {
      String k = i.next();
      Object v = series.getJSONObject(k).get("value");
      if (follow.optBoolean(k, false) && follow.has(k) && !same(v, current.opt(k))) r.apply.put(k, v);
    }
    boolean changed =
        remote == null
            || !same(remote.getJSONObject("series"), series)
            || !same(remote.optJSONObject("apps"), doc.optJSONObject("apps"));
    if (changed) doc.put("updated", now);
    local.put("doc", doc);
    r.local = local;
    r.write = changed ? doc.toString(2) + "\n" : null;
    return r;
  }

  public static JSONObject changed(JSONObject local, String key, Object value, String me, String now) throws JSONException {
    local = copy(local == null ? initial() : local);
    JSONObject follow = local.getJSONObject("follow");
    // Before the first sync's carry-over is decided nothing is published (the offer comes first).
    if (!local.optBoolean("decided") || (follow.has(key) && !follow.getBoolean(key)) || !LABELS.containsKey(key) || blank(value)) return local;
    local.getJSONObject("doc").getJSONObject("series").put(key, new JSONObject().put("value", value).put("by", me).put("at", now));
    follow.put(key, true);
    return local;
  }

  public static JSONObject adopt(JSONObject local, JSONArray offers, Set<String> keys) throws JSONException {
    local = copy(local == null ? initial() : local);
    for (int i = 0; i < offers.length(); i++) {
      String k = offers.getJSONObject(i).getString("key");
      local.getJSONObject("follow").put(k, keys.contains(k));
    }
    local.put("decided", true);
    return local;
  }

  public static JSONObject setFollow(JSONObject local, String key, boolean on) throws JSONException {
    local = copy(local == null ? initial() : local);
    local.getJSONObject("follow").put(key, on);
    return local;
  }

  // For the settings screen.
  public static JSONArray view(JSONObject local, JSONObject current) throws JSONException {
    JSONObject series = local.getJSONObject("doc").getJSONObject("series"), follow = local.getJSONObject("follow");
    Set<String> keys = new LinkedHashSet<>();
    for (Iterator<String> i = series.keys(); i.hasNext(); ) keys.add(i.next());
    for (Iterator<String> i = current.keys(); i.hasNext(); ) keys.add(i.next());
    JSONArray out = new JSONArray();
    for (String k : LABELS.keySet()) {
      if (!keys.contains(k)) continue;
      JSONObject e = series.optJSONObject(k);
      out.put(
          new JSONObject()
              .put("key", k)
              .put("label", LABELS.get(k))
              .put("value", e != null ? e.get("value") : current.opt(k))
              .put("mine", current.opt(k))
              .put("by", e != null ? e.optString("by") : "")
              .put("follow", !follow.has(k) || follow.getBoolean(k)));
    }
    return out;
  }
}
