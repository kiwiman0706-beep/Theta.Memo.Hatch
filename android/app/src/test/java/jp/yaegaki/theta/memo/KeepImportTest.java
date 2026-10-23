package jp.yaegaki.theta.memo;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import org.json.*;
import org.junit.*;

public class KeepImportTest {
  static void add(ZipOutputStream z, String name, byte[] b) throws IOException {
    z.putNextEntry(new ZipEntry(name));
    z.write(b);
    z.closeEntry();
  }

  @Test
  public void takeoutAcrossTwoZips() throws Exception {
    File dir = Files.createTempDirectory("keep").toFile();
    File z1 = new File(dir, "takeout-001.zip"), z2 = new File(dir, "takeout-002.zip");
    JSONObject list =
        new JSONObject()
            .put("color", "YELLOW").put("isPinned", true).put("isArchived", false).put("isTrashed", false)
            .put("title", "買い物").put("userEditedTimestampUsec", 1700000000000000L).put("createdTimestampUsec", 1690000000000000L)
            .put("labels", new JSONArray().put(new JSONObject().put("name", "家 族")))
            .put("listContent", new JSONArray()
                .put(new JSONObject().put("text", "パン").put("isChecked", true))
                .put(new JSONObject().put("text", "牛乳").put("isChecked", false)))
            .put("attachments", new JSONArray().put(new JSONObject().put("filePath", "1a2b.jpg").put("mimetype", "image/jpeg")));
    JSONObject text =
        new JSONObject()
            .put("color", "DEFAULT").put("isArchived", true).put("title", "").put("textContent", "メモ本文\n2行目")
            .put("userEditedTimestampUsec", 1700000001000000L)
            .put("annotations", new JSONArray().put(new JSONObject().put("url", "https://example.com/").put("title", "例").put("source", "WEBLINK")));
    JSONObject trashed = new JSONObject().put("isTrashed", true).put("textContent", "x").put("userEditedTimestampUsec", 1L);
    try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(z1))) {
      add(z, "Takeout/Keep/買い物.json", list.toString().getBytes(StandardCharsets.UTF_8));
      add(z, "Takeout/Keep/買い物.html", "<html/>".getBytes(StandardCharsets.UTF_8));
      add(z, "Takeout/Keep/2023-01-01T00_00_00.000+09_00.json", text.toString().getBytes(StandardCharsets.UTF_8));
      add(z, "Takeout/Keep/ゴミ.json", trashed.toString().getBytes(StandardCharsets.UTF_8));
      add(z, "Takeout/Labels.txt", "家 族".getBytes(StandardCharsets.UTF_8));
    }
    try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(z2))) {
      add(z, "Takeout/Keep/1a2b.jpg", new byte[] {9, 9, 9});
    }
    Store s = Store.openForTest(Files.createTempDirectory("store").toFile());
    KeepImport.Result r = KeepImport.run(s, Arrays.asList(z1, z2), false, t -> {});
    assertEquals(2, r.imported);
    assertEquals(1, r.trashedSkipped);
    assertEquals(1, r.attachments);
    JSONObject shop = null, memo = null;
    for (JSONObject n : s.all()) if (n.getString("title").equals("買い物")) shop = n; else memo = n;
    assertNotNull(shop);
    assertEquals("yellow", shop.getString("color"));
    assertTrue(shop.getBoolean("pinned"));
    assertEquals("家_族", shop.getJSONArray("labels").getString(0));
    assertEquals(1690000000000L, shop.getLong("created"));
    String[] lines = shop.getString("body").split("\n");
    assertTrue(lines[0].matches("!\\[\\[Memo/_files/[a-z0-9]+\\.jpg\\]\\]"));
    assertEquals("- [ ] 牛乳", lines[2]);
    assertEquals("- [x] パン", lines[3]);
    String pic = NoteCodec.media(shop.getString("body")).get(0);
    assertTrue(s.mediaFile(pic).isFile());
    assertTrue(memo.getBoolean("archived"));
    assertEquals("https://example.com/", memo.getString("source"));
    assertEquals("メモ本文\n2行目\n\n- [例](https://example.com/)", memo.getString("body"));

    // The same export again: nothing new.
    r = KeepImport.run(s, Arrays.asList(z1, z2), false, t -> {});
    assertEquals(0, r.imported);
    assertEquals(2, r.skipped);
  }

  @Test
  public void richText() {
    assertEquals("# 見出し\n**太字** と *斜体*\n- a\n- b",
        KeepImport.htmlToMarkdown("<h1>見出し</h1><p><b>太字</b> と <i>斜体</i></p><ul><li>a</li><li>b</li></ul>"));
  }
}
