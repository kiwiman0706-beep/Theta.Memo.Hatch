package jp.yaegaki.theta.memo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

// Taps inside the single-note widget: a list item is checked off in place (no app opens);
// anything else opens the note.
public class WidgetAction extends Activity {
  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    Intent i = getIntent();
    String id = i.getStringExtra(Widgets.EXTRA_OPEN);
    if (id != null && i.hasExtra(Widgets.EXTRA_LINE)) {
      try {
        Memo.toggle(this, id, i.getIntExtra(Widgets.EXTRA_LINE, -1));
        Widgets.update(this);
        SyncJob.soon(this);
      } catch (Exception ignored) {
      }
    } else if (id != null)
      startActivity(new Intent(this, MainActivity.class).putExtra(Widgets.EXTRA_OPEN, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    finish();
    overridePendingTransition(0, 0);
  }
}
