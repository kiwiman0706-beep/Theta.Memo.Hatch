package app.thetahatch.memo;

import java.nio.charset.StandardCharsets;
import org.json.JSONArray;

// Memo/メモ.base: the notes as cards and as a table in Obsidian (Bases, Obsidian 1.9+, no plugin),
// as the series asks of every app (Theta.Hatch.Series spec/THETA_VAULT_SPEC.md). Written once when
// missing; after that it is the user's file (views added in Obsidian are kept).
final class Bases {
  static final String NAME = "メモ.base";
  static final String TEXT =
      "filters:\n"
          + "  and:\n"
          + "    - file.inFolder(\"Memo\")\n"
          + "    - 'note.theta == \"memo/note@1\"'\n"
          + "    - \"!note.archived\"\n"
          + "    - \"!note.trashed\"\n"
          + "properties:\n"
          + "  note.title:\n"
          + "    displayName: タイトル\n"
          + "  note.tags:\n"
          + "    displayName: ラベル\n"
          + "  note.gtd:\n"
          + "    displayName: GTD\n"
          + "  note.due:\n"
          + "    displayName: 期限\n"
          + "  note.important:\n"
          + "    displayName: 重要\n"
          + "  note.urgent:\n"
          + "    displayName: 緊急\n"
          + "  note.updated:\n"
          + "    displayName: 更新\n"
          + "views:\n"
          + "  - type: cards\n"
          + "    name: メモ\n"
          + "    order:\n"
          + "      - file.name\n"
          + "      - note.tags\n"
          + "      - note.updated\n"
          + "    sort:\n"
          + "      - property: note.pinned\n"
          + "        direction: DESC\n"
          + "      - property: note.updated\n"
          + "        direction: DESC\n"
          + "  - type: table\n"
          + "    name: 一覧\n"
          + "    order:\n"
          + "      - file.name\n"
          + "      - note.tags\n"
          + "      - note.pinned\n"
          + "      - note.updated\n"
          + "  - type: table\n"
          + "    name: タスク（GTD）\n"
          + "    filters:\n"
          + "      and:\n"
          + "        - note.gtd\n"
          + "        - 'note.gtd != \"done\"'\n"
          + "    order:\n"
          + "      - file.name\n"
          + "      - note.gtd\n"
          + "      - note.due\n"
          + "      - note.important\n"
          + "      - note.urgent\n"
          + "    sort:\n"
          + "      - property: note.due\n"
          + "        direction: ASC\n";

  static void ensure(VaultRemote d, String appFolder) throws Exception {
    JSONArray files = d.files(appFolder);
    for (int i = 0; i < files.length(); i++) if (NAME.equals(files.getJSONObject(i).optString("name"))) return;
    d.put("", appFolder, NAME, TEXT.getBytes(StandardCharsets.UTF_8), "", "");
  }
}
