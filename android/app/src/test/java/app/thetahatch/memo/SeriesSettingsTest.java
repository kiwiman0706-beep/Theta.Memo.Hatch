package app.thetahatch.memo;

import static org.junit.Assert.*;

import java.util.*;
import org.json.*;
import org.junit.Test;

// The same cases as desktop/test/series.test.js.
public class SeriesSettingsTest {
  static final String T1 = "2026-10-22T01:00:00.000Z", T2 = "2026-10-22T02:00:00.000Z", T3 = "2026-10-22T03:00:00.000Z";

  static JSONObject cur(Object... kv) throws JSONException {
    JSONObject o = new JSONObject();
    for (int i = 0; i < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
    return o;
  }

  @Test
  public void firstAppPublishesItsSettings() throws Exception {
    SeriesSettings.Result r = SeriesSettings.sync(SeriesSettings.initial(), null, cur("gemini.model", "gemini-2.5-flash", "google.account", "a@x.jp"), "meishi", T1);
    JSONObject e = new JSONObject(r.write).getJSONObject("series").getJSONObject("gemini.model");
    assertEquals("gemini-2.5-flash", e.getString("value"));
    assertEquals("meishi", e.getString("by"));
    assertTrue(r.local.getBoolean("decided"));
    assertEquals(0, r.offers.length());
    assertNull(SeriesSettings.sync(r.local, r.write, cur("gemini.model", "gemini-2.5-flash", "google.account", "a@x.jp"), "meishi", T2).write);
  }

  @Test
  public void secondAppIsOfferedOnceAndChooses() throws Exception {
    JSONObject cal = new JSONObject().put("account", "a@x.jp").put("name", "仕事");
    SeriesSettings.Result first = SeriesSettings.sync(SeriesSettings.initial(), null, cur("gemini.model", "gemini-2.5-pro", "google.account", "a@x.jp", "calendar", cal), "meishi", T1);
    SeriesSettings.Result r = SeriesSettings.sync(SeriesSettings.initial(), first.write, cur("gemini.model", "gemini-2.5-flash"), "recipe", T2);
    Set<String> offered = new TreeSet<>();
    for (int i = 0; i < r.offers.length(); i++) offered.add(r.offers.getJSONObject(i).getString("key"));
    assertEquals(new TreeSet<>(Arrays.asList("calendar", "gemini.model", "google.account")), offered);
    assertEquals(0, r.apply.length());
    JSONObject local = SeriesSettings.adopt(r.local, r.offers, new HashSet<>(Arrays.asList("gemini.model", "google.account")));
    SeriesSettings.Result r2 = SeriesSettings.sync(local, first.write, cur("gemini.model", "gemini-2.5-flash"), "recipe", T3);
    assertEquals("gemini-2.5-pro", r2.apply.getString("gemini.model"));
    assertEquals("a@x.jp", r2.apply.getString("google.account"));
    assertFalse(r2.apply.has("calendar"));
    assertEquals(0, r2.offers.length());
  }

  @Test
  public void followingChangesTravelThisAppOnlyStays() throws Exception {
    SeriesSettings.Result a = SeriesSettings.sync(SeriesSettings.initial(), null, cur("gemini.model", "m1"), "meishi", T1);
    SeriesSettings.Result b = SeriesSettings.sync(SeriesSettings.initial(), a.write, cur("gemini.model", "m1"), "recipe", T1);
    assertTrue(b.local.getBoolean("decided"));
    JSONObject local = SeriesSettings.changed(b.local, "gemini.model", "m2", "recipe", T2);
    SeriesSettings.Result out = SeriesSettings.sync(local, a.write, cur("gemini.model", "m2"), "recipe", T2);
    assertEquals("m2", new JSONObject(out.write).getJSONObject("series").getJSONObject("gemini.model").getString("value"));
    SeriesSettings.Result back = SeriesSettings.sync(a.local, out.write, cur("gemini.model", "m1"), "meishi", T3);
    assertEquals("m2", back.apply.getString("gemini.model"));
    JSONObject own = SeriesSettings.changed(SeriesSettings.setFollow(back.local, "gemini.model", false), "gemini.model", "m9", "meishi", T3);
    SeriesSettings.Result r = SeriesSettings.sync(own, out.write, cur("gemini.model", "m9"), "meishi", T3);
    assertNull(r.write);
    assertEquals(0, r.apply.length());
  }

  @Test
  public void newerWinsSecretsDroppedUnknownKeptBrokenRespected() throws Exception {
    String text =
        new JSONObject()
            .put("spec", 1)
            .put("series", new JSONObject()
                .put("gemini.model", new JSONObject().put("value", "old").put("by", "x").put("at", T1))
                .put("gemini.key", new JSONObject().put("value", "AIza-secret").put("by", "x").put("at", T1)))
            .put("apps", new JSONObject().put("recipe", new JSONObject().put("autoRead", true)))
            .put("future", new JSONObject().put("keep", 1))
            .toString();
    JSONObject local = SeriesSettings.changed(SeriesSettings.adopt(SeriesSettings.initial(), new JSONArray(), new HashSet<>()), "gemini.model", "new", "meishi", T2);
    SeriesSettings.Result r = SeriesSettings.sync(local, text, cur("gemini.model", "new"), "meishi", T2);
    JSONObject doc = new JSONObject(r.write);
    assertEquals("new", doc.getJSONObject("series").getJSONObject("gemini.model").getString("value"));
    assertFalse(doc.getJSONObject("series").has("gemini.key"));
    assertTrue(doc.getJSONObject("apps").getJSONObject("recipe").getBoolean("autoRead"));
    assertEquals(1, doc.getJSONObject("future").getInt("keep"));
    SeriesSettings.Result broken = SeriesSettings.sync(local, "{ not json", cur("gemini.model", "new"), "meishi", T2);
    assertNull(broken.write);
    assertTrue(broken.broken);
  }

  @Test
  public void changeBeforeCarryOverIsNotPublished() throws Exception {
    SeriesSettings.Result first = SeriesSettings.sync(SeriesSettings.initial(), null, cur("gemini.model", "m1"), "meishi", T1);
    JSONObject local = SeriesSettings.changed(SeriesSettings.initial(), "gemini.model", "m2", "recipe", T2);
    SeriesSettings.Result r = SeriesSettings.sync(local, first.write, cur("gemini.model", "m2"), "recipe", T2);
    assertEquals(1, r.offers.length());
    assertEquals("gemini.model", r.offers.getJSONObject(0).getString("key"));
    assertNull(r.write);
  }
}
