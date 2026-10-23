package jp.yaegaki.theta.memo;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.json.*;
import org.junit.*;

public class VaultTest {
  static final VaultSync.Progress QUIET = t -> {};

  static File tmp() throws Exception {
    return Files.createTempDirectory("memo").toFile();
  }

  static JSONObject note(String title, String body) throws Exception {
    JSONObject n = Store.empty();
    n.put("title", title).put("body", body);
    return n;
  }

  @Test
  public void roundTripKeepsEverything() throws Exception {
    JSONObject n = note("買い物", "- [ ] 牛乳\n- [x] パン\n\n| 品 | 数 |\n|---|---|\n| 卵 | 10 |\n\n![[Memo/_files/abc123.jpg]]");
    n.put("color", "yellow").put("pinned", true).put("labels", new JSONArray().put("家").put("週末"));
    n.put("source", "https://example.com/a");
    n.put("extra", new JSONObject().put("author", "someone").put("cssclasses", new JSONArray().put("wide")));
    String md = NoteCodec.render(n);
    assertTrue(md.startsWith("---\ntheta: memo/note@1\nrev: "));
    assertTrue(md.contains("tags:\n- 家\n- 週末"));
    assertFalse(NoteCodec.editedOutside(md));
    JSONObject back = NoteCodec.parse(md, "ignored", 0);
    assertEquals(Sync.hash(n), Sync.hash(back));
    assertEquals("someone", back.getJSONObject("extra").getString("author"));
    assertEquals(NoteCodec.render(n), NoteCodec.render(back));
  }

  @Test
  public void obsidianEditIsDetected() throws Exception {
    String md = NoteCodec.render(note("a", "hello"));
    assertTrue(NoteCodec.editedOutside(md.replace("hello", "hello world")));
  }

  @Test
  public void handWrittenNoteGetsIdAndFileTitle() throws Exception {
    JSONObject n = NoteCodec.parse("# 旅行の持ち物\n- [ ] 充電器\n", "旅行", 1_700_000_000_000L);
    assertTrue(n.optBoolean("vaultNewId"));
    assertEquals("旅行", n.getString("title"));
    assertEquals(1_700_000_000_000L, n.getLong("created"));
  }

  @Test
  public void webClipperNoteIsUnderstood() throws Exception {
    String md =
        "---\ntitle: \"記事のタイトル\"\nsource: \"https://example.com/post\"\nauthor:\n  - \"[[Someone]]\"\npublished: 2026-10-01\n"
            + "created: 2026-10-20\ndescription: \"概要\"\ntags:\n  - \"clippings\"\n---\n本文です\n";
    JSONObject n = NoteCodec.parse(md, "x", 0);
    assertEquals("記事のタイトル", n.getString("title"));
    assertEquals("https://example.com/post", n.getString("source"));
    assertEquals("clippings", n.getJSONArray("labels").getString(0));
    assertEquals("概要", n.getJSONObject("extra").getString("description"));
    assertEquals("本文です", n.getString("body"));
    assertTrue(NoteCodec.render(n).contains("author:\n- '[[Someone]]'"));
  }

  @Test
  public void mediaReferences() {
    List<String> m =
        NoteCodec.media("a ![[Memo/_files/x1.png|300]] b ![](Memo/_files/y2.pdf) [[Memo/_files/z3.m4a]] ![[Other/evil/../q.png]] ![[k9.ink.png]]");
    assertEquals(Arrays.asList("x1.png", "y2.pdf", "z3.m4a", "k9.ink.png", "k9.ink.json"), m);
  }

  @Test
  public void fileNames() throws Exception {
    assertEquals("a b c.md", NoteCodec.fileName(note("a/b:c", "")));
    assertEquals("牛乳.md", NoteCodec.fileName(note("", "- [ ] 牛乳\n- [ ] パン")));
    assertTrue(NoteCodec.fileName(note("", "![[Memo/_files/a.jpg]]")).startsWith("メモ "));
  }

  @Test
  public void decisionTable() {
    assertEquals(Sync.Action.PUSH, Sync.decide("a", null, null));
    assertEquals(Sync.Action.PULL, Sync.decide(null, "b", null));
    assertEquals(Sync.Action.PUSH, Sync.decide("a2", "a", "a"));
    assertEquals(Sync.Action.PULL, Sync.decide("a", "b", "a"));
    assertEquals(Sync.Action.MERGE, Sync.decide("x", "y", "a"));
    assertEquals(Sync.Action.PULL, Sync.decide(Sync.DELETED, "y", "a"));
    assertEquals(Sync.Action.PUSH, Sync.decide("x", Sync.DELETED, "a"));
  }

  @Test
  public void twoDevicesShareNotesAndAttachments() throws Exception {
    FakeRemote drive = new FakeRemote();
    Store a = Store.openForTest(tmp()), b = Store.openForTest(tmp());
    String pic = Store.newName(".jpg");
    try (OutputStream o = new FileOutputStream(a.mediaFile(pic))) {
      o.write(new byte[] {1, 2, 3});
    }
    JSONObject n = note("写真", "![[Memo/_files/" + pic + "]]\nきれい");
    a.put(n);
    VaultSync.Result r = VaultSync.run(a, drive, QUIET);
    assertEquals(1, r.up);
    assertEquals(1, r.mediaUp);
    assertNotNull(drive.file("Theta Vault/Memo/Notes", "写真.md"));
    assertTrue(drive.read(drive.file("Theta Vault/.theta", "vault.json").id).contains("memo"));

    r = VaultSync.run(b, drive, QUIET);
    assertEquals(1, r.down);
    assertTrue(b.mediaFile(pic).isFile());
    assertEquals("きれい", b.note(n.getString("id")).getString("body").split("\n")[1]);

    // Nothing changed: nothing moves.
    r = VaultSync.run(a, drive, QUIET);
    assertEquals(0, r.up + r.down);
    r = VaultSync.run(b, drive, QUIET);
    assertEquals(0, r.up + r.down);
  }

  @Test
  public void obsidianEditsComeBackAndNewNotesGetIds() throws Exception {
    FakeRemote drive = new FakeRemote();
    Store a = Store.openForTest(tmp());
    JSONObject n = note("メモ1", "最初");
    a.put(n);
    VaultSync.run(a, drive, QUIET);
    FakeRemote.F f = drive.file("Theta Vault/Memo/Notes", "メモ1.md");
    drive.write("Theta Vault/Memo/Notes", "メモ1.md", drive.read(f.id).replace("最初", "Obsidianで編集"));
    drive.write("Theta Vault/Memo/Clippings", "ある記事.md", "---\nsource: https://example.com\n---\n切り抜き\n");
    VaultSync.Result r = VaultSync.run(a, drive, QUIET);
    assertEquals(2, r.down);
    assertEquals("Obsidianで編集", a.note(n.getString("id")).getString("body"));
    FakeRemote.F clip = drive.file("Theta Vault/Memo/Clippings", "ある記事.md");
    assertNotNull("clipping stays where the clipper put it", clip);
    assertTrue(drive.read(clip.id).contains("id: "));
    boolean found = false;
    for (JSONObject x : a.all()) if (x.optString("title").equals("ある記事")) found = true;
    assertTrue(found);
  }

  @Test
  public void archiveAndTrashMoveFilesAndDeleteGoesToVaultTrash() throws Exception {
    FakeRemote drive = new FakeRemote();
    Store a = Store.openForTest(tmp()), b = Store.openForTest(tmp());
    JSONObject n = note("片付け", "x");
    a.put(n);
    VaultSync.run(a, drive, QUIET);
    VaultSync.run(b, drive, QUIET);
    JSONObject m = a.note(n.getString("id"));
    m.put("archived", true);
    a.put(m);
    VaultSync.run(a, drive, QUIET);
    assertNotNull(drive.file("Theta Vault/Memo/Archive", "片付け.md"));
    assertNull(drive.file("Theta Vault/Memo/Notes", "片付け.md"));
    VaultSync.run(b, drive, QUIET);
    assertTrue(b.note(n.getString("id")).getBoolean("archived"));
    a.remove(n.getString("id"));
    VaultSync.run(a, drive, QUIET);
    assertEquals(1, drive.filesIn("Theta Vault/.theta/trash").size());
    VaultSync.Result r = VaultSync.run(b, drive, QUIET);
    assertEquals(1, r.deleted);
    assertNull(b.find(n.getString("id")));
  }

  @Test
  public void concurrentEditsKeepBothTexts() throws Exception {
    FakeRemote drive = new FakeRemote();
    Store a = Store.openForTest(tmp()), b = Store.openForTest(tmp());
    JSONObject n = note("予定", "元");
    a.put(n);
    VaultSync.run(a, drive, QUIET);
    VaultSync.run(b, drive, QUIET);
    JSONObject x = a.note(n.getString("id"));
    x.put("body", "Aで書いた");
    a.put(x);
    Thread.sleep(5);
    JSONObject y = b.note(n.getString("id"));
    y.put("body", "Bで書いた").put("color", "green");
    b.put(y);
    VaultSync.run(a, drive, QUIET);
    VaultSync.Result r = VaultSync.run(b, drive, QUIET);
    assertEquals(1, r.merged);
    assertEquals(1, r.conflicts);
    Set<String> bodies = new HashSet<>();
    for (JSONObject k : b.all()) bodies.add(k.getString("body"));
    assertEquals(new HashSet<>(Arrays.asList("Aで書いた", "Bで書いた")), bodies);
    assertEquals("green", b.note(n.getString("id")).getString("color"));
    VaultSync.run(a, drive, QUIET);
    assertEquals(2, a.size());
  }

  @Test
  public void manyMissingNotesAreNotDeleted() throws Exception {
    FakeRemote drive = new FakeRemote();
    Store a = Store.openForTest(tmp());
    for (int i = 0; i < 12; i++) a.put(note("n" + i, "b" + i));
    VaultSync.run(a, drive, QUIET);
    for (FakeRemote.F f : drive.filesIn("Theta Vault/Memo/Notes")) f.trashed = true;
    VaultSync.Result r = VaultSync.run(a, drive, QUIET);
    assertEquals(12, a.size());
    assertFalse(r.errors.isEmpty());
  }
}
