package jp.yaegaki.theta.memo;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

// Chooses the note a single-note widget shows (also from the widget's "reconfigure").
public class WidgetPick extends Activity {
  int widgetId;
  List<JSONObject> all = new ArrayList<>(), shown = new ArrayList<>();
  BaseAdapter adapter;

  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    setResult(RESULT_CANCELED);
    widgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
    if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
      finish();
      return;
    }
    setContentView(R.layout.pick);
    try {
      all = Notes.active(Store.get(this).all());
    } catch (Exception ignored) {
    }
    shown = new ArrayList<>(all);
    ListView list = findViewById(R.id.p_list);
    float dp = getResources().getDisplayMetrics().density;
    adapter =
        new BaseAdapter() {
          public int getCount() {
            return shown.size();
          }

          public Object getItem(int i) {
            return shown.get(i);
          }

          public long getItemId(int i) {
            return i;
          }

          public View getView(int i, View v, ViewGroup parent) {
            TextView t = v instanceof TextView ? (TextView) v : new TextView(WidgetPick.this);
            JSONObject n = shown.get(i);
            String title = Notes.title(n);
            t.setText((n.optBoolean("pinned") ? "📌 " : "") + (title.isEmpty() ? "（タイトルなし）" : title));
            t.setTextSize(15);
            t.setTextColor(0xFF202124);
            t.setPadding((int) (14 * dp), (int) (14 * dp), (int) (14 * dp), (int) (14 * dp));
            Integer c = Notes.COLOR.get(n.optString("color"));
            t.setBackgroundColor(c == null ? 0xFFFFFFFF : c);
            return t;
          }
        };
    list.setAdapter(adapter);
    list.setOnItemClickListener((p, v, i, id) -> choose(shown.get(i).optString("id")));
    ((EditText) findViewById(R.id.p_search))
        .addTextChangedListener(
            new TextWatcher() {
              public void beforeTextChanged(CharSequence s, int a, int c, int d) {}

              public void onTextChanged(CharSequence s, int a, int c, int d) {}

              public void afterTextChanged(Editable s) {
                String q = s.toString().trim().toLowerCase(Locale.ROOT);
                shown = new ArrayList<>();
                for (JSONObject n : all)
                  if (q.isEmpty() || (n.optString("title") + "\n" + n.optString("body")).toLowerCase(Locale.ROOT).contains(q)) shown.add(n);
                adapter.notifyDataSetChanged();
              }
            });
  }

  void choose(String noteId) {
    getSharedPreferences("prefs", 0).edit().putString(Widgets.PREF + widgetId, noteId).apply();
    AppWidgetManager m = AppWidgetManager.getInstance(this);
    m.updateAppWidget(widgetId, Widgets.NoteWidget.views(this, widgetId));
    m.notifyAppWidgetViewDataChanged(new int[] {widgetId}, R.id.n_list);
    setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
    finish();
  }
}
