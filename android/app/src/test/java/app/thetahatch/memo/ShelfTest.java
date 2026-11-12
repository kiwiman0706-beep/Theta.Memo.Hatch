package app.thetahatch.memo;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

// What one row of Home's shelf (/shelf) shows for a note.
public class ShelfTest {
  static JSONObject note(String title, String body, String color) throws Exception {
    return Store.empty().put("title", title).put("body", body).put("color", color).put("pinned", true);
  }

  @Test
  public void rowContents() throws Exception {
    JSONObject n = note("買い物", "- [ ] 牛乳\n- [x] パン #重要\n![[Memo/_files/abc123.jpg]]\n![[Memo/_files/doc9.pdf|見積.pdf]]", "yellow");
    assertEquals("#FFF8B8", SeriesProvider.color(n));
    assertEquals("", SeriesProvider.color(note("", "x", "")));
    assertEquals("abc123.jpg", SeriesProvider.picture(n));
    assertNull(SeriesProvider.picture(note("", "![[Memo/_files/doc9.pdf]]", "")));
    assertEquals("☐ 牛乳\n☑ パン", SeriesProvider.text(n));
    // An untitled note does not repeat its first line (the title) in the text.
    assertEquals("2行目", SeriesProvider.text(note("", "1行目\n2行目", "")));
    StringBuilder longBody = new StringBuilder();
    for (int i = 0; i < 5; i++) longBody.append("あ".repeat(80)).append('\n');
    assertTrue(SeriesProvider.text(note("t", longBody.toString(), "")).length() <= 201);
  }
}
