package app.thetahatch.memo;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

// Memo's side of the series settings (SeriesSettings has the rules, shared with Meishi): its own
// values of the series items, the phone's settings hub (Hub) and the trip to .theta/settings.json
// in each vault it syncs with (Theta.Hatch.Series spec/THETA_SHARED_SETTINGS.md).
public final class Series {
  // Items this app uses (offered / shown); others it only keeps.
  static final Set<String> USES = new HashSet<>(Arrays.asList("ui.skin", "gemini.model", "nas.url", "nas.user", "nas.shareRoot"));
  // Items the hub (Home / KidsHome) also keeps (§4.1).
  static final List<String> HUB_KEYS = Arrays.asList("ui.skin", "gemini.model", "nas.url", "nas.user", "nas.shareRoot");
  static final Set<String> SKINS = new HashSet<>(Arrays.asList("system", "dark", "light", "pop", "alert", "scifi", "term", "braun", "soft", "window", "lcd"));

  static JSONArray usable(JSONArray items) throws JSONException {
    JSONArray out = new JSONArray();
    for (int i = 0; i < items.length(); i++) if (USES.contains(items.getJSONObject(i).getString("key"))) out.put(items.getJSONObject(i));
    return out;
  }

  static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("prefs", 0);
  }

  static String now() {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
    f.setTimeZone(TimeZone.getTimeZone("UTC"));
    return f.format(new Date());
  }

  public static JSONObject local(Context c) {
    try {
      String s = prefs(c).getString("series", "");
      return s.isEmpty() ? SeriesSettings.initial() : new JSONObject(s);
    } catch (Exception e) {
      try {
        return SeriesSettings.initial();
      } catch (JSONException x) {
        throw new IllegalStateException(x);
      }
    }
  }

  static void save(Context c, JSONObject local) {
    prefs(c).edit().putString("series", local.toString()).apply();
  }

  // This app's values of the series items (unset ones left out).
  public static JSONObject current(Context c) throws JSONException {
    SharedPreferences p = prefs(c);
    JSONObject o = new JSONObject();
    if (p.contains("skin")) o.put("ui.skin", p.getString("skin", "system"));
    if (p.contains("model")) o.put("gemini.model", p.getString("model", ""));
    if (!p.getString("nasUrl", "").isEmpty()) o.put("nas.url", p.getString("nasUrl", ""));
    if (!p.getString("nasAccount", "").isEmpty()) o.put("nas.user", p.getString("nasAccount", ""));
    if (!p.getString("nasRoot", "").isEmpty()) o.put("nas.shareRoot", p.getString("nasRoot", ""));
    return o;
  }

  // Values from the series, for the items this app follows.
  static void apply(Context c, JSONObject values) throws JSONException {
    SharedPreferences p = prefs(c);
    SharedPreferences.Editor e = p.edit();
    for (Iterator<String> i = values.keys(); i.hasNext(); ) {
      String k = i.next();
      Object v = values.get(k);
      if (!(v instanceof String)) continue;
      String s = (String) v;
      switch (k) {
        case "ui.skin":
          if (SKINS.contains(s)) e.putString("skin", s);
          break;
        case "gemini.model":
          if (s.matches("[A-Za-z0-9._-]+")) e.putString("model", s);
          break;
        case "nas.url":
          // A different NAS: its device token and certificate are no longer valid.
          if (!s.equals(p.getString("nasUrl", ""))) e.putString("nasUrl", Nas.normalize(s)).remove("nasDid").remove("nasCertPin");
          break;
        case "nas.user":
          if (!s.equals(p.getString("nasAccount", ""))) e.putString("nasAccount", s).remove("nasDid");
          break;
        case "nas.shareRoot":
          if (s.startsWith("/")) e.putString("nasRoot", s);
          break;
        default:
          break;
      }
    }
    e.apply();
  }

  // A series item changed here: the series gets it at the next sync, the hub right away.
  public static void changed(Context c, String key) {
    try {
      Object v = current(c).opt(key);
      if (v == null) return;
      JSONObject local = local(c);
      save(c, SeriesSettings.changed(local, key, v, SeriesSettings.ME, now()));
      JSONObject follow = local.getJSONObject("follow");
      if (HUB_KEYS.contains(key) && v instanceof String && !(follow.has(key) && !follow.getBoolean(key))) Hub.tell(c, key, (String) v);
    } catch (Exception ignored) {
    }
  }

  // The hub's newer values for the items this app follows (on opening the app). True if any changed.
  public static boolean fromHub(Context c) {
    try {
      Map<String, String[]> h = Hub.info(c);
      if (h.isEmpty()) return false;
      JSONObject local = local(c), current = current(c);
      JSONObject follow = local.getJSONObject("follow"), series = local.getJSONObject("doc").getJSONObject("series");
      JSONObject apply = new JSONObject();
      for (String key : HUB_KEYS) {
        String[] e = h.get(key);
        if (e == null || e[0] == null || e[0].isEmpty()) continue;
        if (follow.has(key) && !follow.getBoolean(key)) continue; // "このアプリだけ"
        JSONObject mine = series.optJSONObject(key);
        if (mine != null && e[2].compareTo(mine.optString("at")) <= 0) continue; // ours is newer
        if (e[0].equals(current.optString(key))) continue;
        apply.put(key, e[0]);
        series.put(key, new JSONObject().put("value", e[0]).put("by", e[1].isEmpty() ? "home" : e[1]).put("at", e[2].isEmpty() ? now() : e[2]));
      }
      if (apply.length() == 0) return false;
      save(c, local);
      apply(c, apply);
      return true;
    } catch (Exception ignored) {
      return false; // no hub, or one that does not let this app read
    }
  }

  // During a vault sync (each vault): read, merge, write back, apply; offers wait for the user.
  public static void run(Context c, VaultRemote d) throws Exception {
    String root = d.folder("root", VaultSync.ROOT);
    String theta = d.folder(root, ".theta");
    String fileId = "", text = null;
    JSONArray files = d.files(theta);
    for (int i = 0; i < files.length(); i++)
      if (files.getJSONObject(i).optString("name").equals("settings.json")) {
        fileId = files.getJSONObject(i).getString("id");
        text = d.read(fileId);
      }
    SeriesSettings.Result r = SeriesSettings.sync(local(c), text, current(c), SeriesSettings.ME, now());
    if (r.broken) {
      prefs(c).edit().putString("seriesError", "Theta Vault の .theta/settings.json が読めません（手で直した場合はJSONの形を確認してください）").apply();
      return;
    }
    if (r.write != null) d.put(fileId, theta, "settings.json", r.write.getBytes(StandardCharsets.UTF_8), "", "");
    save(c, r.local);
    apply(c, r.apply);
    SharedPreferences.Editor e = prefs(c).edit().remove("seriesError");
    JSONArray offers = usable(r.offers);
    if (offers.length() > 0) e.putString("seriesOffer", offers.toString());
    else e.remove("seriesOffer");
    e.apply();
  }

  public static void adopt(Context c, Set<String> keys) throws Exception {
    JSONArray offers = new JSONArray(prefs(c).getString("seriesOffer", "[]"));
    JSONObject local = SeriesSettings.adopt(local(c), offers, keys);
    JSONObject apply = new JSONObject();
    for (int i = 0; i < offers.length(); i++) {
      JSONObject o = offers.getJSONObject(i);
      if (keys.contains(o.getString("key"))) apply.put(o.getString("key"), o.get("value"));
    }
    save(c, local);
    apply(c, apply);
    prefs(c).edit().remove("seriesOffer").apply();
  }

  public static void follow(Context c, String key, boolean on) throws Exception {
    JSONObject local = SeriesSettings.setFollow(local(c), key, on);
    save(c, local);
    if (on) {
      JSONObject e = local.getJSONObject("doc").getJSONObject("series").optJSONObject(key);
      if (e != null) apply(c, new JSONObject().put(key, e.get("value")));
    }
  }

  public static void state(Context c, JSONObject state) throws JSONException {
    state.put("series", usable(SeriesSettings.view(local(c), current(c))));
    String offer = prefs(c).getString("seriesOffer", "");
    if (!offer.isEmpty()) state.put("seriesOffer", new JSONArray(offer));
    state.put("seriesError", prefs(c).getString("seriesError", ""));
  }
}
