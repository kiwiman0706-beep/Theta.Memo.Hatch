package jp.yaegaki.theta.memo;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import java.io.File;
import java.util.*;
import org.json.*;

// Home-screen widgets, like Keep's: the note list (pinned first) with quick buttons for a new
// text / list / handwriting / voice / photo note, and a single note whose list items can be
// checked right on the home screen. Data changes refresh them; there is no periodic update.
public final class Widgets {
  public static final String EXTRA_OPEN = "open", EXTRA_NEW = "new", EXTRA_LINE = "line", EXTRA_WIDGET = "widget";
  static final String PREF = "noteWidget.";

  public static void update(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    int[] lists = m.getAppWidgetIds(new ComponentName(c, ListWidget.class));
    if (lists.length > 0) m.notifyAppWidgetViewDataChanged(lists, R.id.w_list);
    int[] notes = m.getAppWidgetIds(new ComponentName(c, NoteWidget.class));
    for (int id : notes) m.updateAppWidget(id, NoteWidget.views(c, id));
    if (notes.length > 0) m.notifyAppWidgetViewDataChanged(notes, R.id.n_list);
  }

  static int count(Context c) {
    AppWidgetManager m = AppWidgetManager.getInstance(c);
    return m.getAppWidgetIds(new ComponentName(c, ListWidget.class)).length + m.getAppWidgetIds(new ComponentName(c, NoteWidget.class)).length;
  }

  static PendingIntent create(Context c, String kind, int code) {
    return PendingIntent.getActivity(c, code,
        new Intent(c, MainActivity.class).setAction("jp.yaegaki.theta.memo.NEW." + kind).putExtra(EXTRA_NEW, kind).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  static Bitmap thumb(Context c, JSONObject n, int size) {
    try {
      String cover = Notes.cover(n);
      if (cover == null) return null;
      File t = Thumbs.get(Store.get(c), cover);
      return t == null ? null : Thumbs.image(t, size);
    } catch (Exception e) {
      return null;
    }
  }

  // ---- the note list --------------------------------------------------------------

  public static class ListWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
      for (int id : ids) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_list);
        Intent svc = new Intent(c, ListService.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        v.setRemoteAdapter(R.id.w_list, svc);
        v.setEmptyView(R.id.w_list, R.id.w_empty);
        v.setPendingIntentTemplate(R.id.w_list,
            PendingIntent.getActivity(c, 20, new Intent(c, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
        v.setOnClickPendingIntent(R.id.w_title,
            PendingIntent.getActivity(c, 21, new Intent(c, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        v.setOnClickPendingIntent(R.id.w_new_text, create(c, "text", 22));
        v.setOnClickPendingIntent(R.id.w_new_list, create(c, "list", 23));
        v.setOnClickPendingIntent(R.id.w_new_ink, create(c, "ink", 24));
        v.setOnClickPendingIntent(R.id.w_new_audio, create(c, "audio", 25));
        v.setOnClickPendingIntent(R.id.w_new_photo, create(c, "photo", 26));
        m.updateAppWidget(id, v);
      }
    }
  }

  public static class ListService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
      return new ListFactory(getApplicationContext());
    }
  }

  static class ListFactory implements RemoteViewsService.RemoteViewsFactory {
    final Context c;
    List<JSONObject> rows = new ArrayList<>();
    final Map<String, Bitmap> thumbs = new HashMap<>();

    ListFactory(Context c) {
      this.c = c;
    }

    public void onCreate() {}

    public void onDataSetChanged() {
      try {
        List<JSONObject> all = Notes.active(Store.get(c).all());
        rows = all.subList(0, Math.min(40, all.size()));
      } catch (Exception e) {
        rows = new ArrayList<>();
      }
      clear();
    }

    void clear() {
      for (Bitmap b : thumbs.values()) if (b != null) b.recycle();
      thumbs.clear();
    }

    public void onDestroy() {
      clear();
    }

    public int getCount() {
      return rows.size();
    }

    public RemoteViews getViewAt(int i) {
      RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_list_row);
      if (i >= rows.size()) return v;
      JSONObject n = rows.get(i);
      String title = n.optString("title").trim();
      StringBuilder body = new StringBuilder();
      try {
        for (JSONObject l : Notes.lines(n, 6)) {
          if (title.isEmpty() && body.length() == 0 && l.optString("type").equals("heading")) {
            title = l.optString("text");
            continue;
          }
          if (body.length() > 0) body.append('\n');
          if (l.optString("type").equals("check")) body.append(l.optBoolean("checked") ? "☑ " : "☐ ");
          body.append(l.optString("text"));
        }
      } catch (JSONException ignored) {
      }
      if (n.optBoolean("pinned")) title = "📌 " + title;
      v.setTextViewText(R.id.r_name, title);
      v.setViewVisibility(R.id.r_name, title.isEmpty() ? View.GONE : View.VISIBLE);
      v.setTextViewText(R.id.r_body, body);
      v.setViewVisibility(R.id.r_body, body.length() == 0 ? View.GONE : View.VISIBLE);
      Integer color = Notes.COLOR.get(n.optString("color"));
      v.setInt(R.id.r_bg, "setColorFilter", color == null ? 0xFFFFFFFF : color);
      String id = n.optString("id");
      if (!thumbs.containsKey(id)) thumbs.put(id, thumb(c, n, 360));
      Bitmap b = thumbs.get(id);
      if (b != null) {
        v.setImageViewBitmap(R.id.r_thumb, b);
        v.setViewVisibility(R.id.r_thumb, View.VISIBLE);
      } else v.setViewVisibility(R.id.r_thumb, View.GONE);
      v.setOnClickFillInIntent(R.id.r_root, new Intent().putExtra(EXTRA_OPEN, id));
      return v;
    }

    public RemoteViews getLoadingView() {
      return null;
    }

    public int getViewTypeCount() {
      return 1;
    }

    public long getItemId(int i) {
      return i < rows.size() ? rows.get(i).optString("id").hashCode() : i;
    }

    public boolean hasStableIds() {
      return true;
    }
  }

  // ---- a single note --------------------------------------------------------------

  public static class NoteWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
      for (int id : ids) m.updateAppWidget(id, views(c, id));
    }

    @Override
    public void onDeleted(Context c, int[] ids) {
      SharedPreferences.Editor e = c.getSharedPreferences("prefs", 0).edit();
      for (int id : ids) e.remove(PREF + id);
      e.apply();
    }

    static RemoteViews views(Context c, int widgetId) {
      RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_note);
      String noteId = c.getSharedPreferences("prefs", 0).getString(PREF + widgetId, "");
      JSONObject n = null;
      try {
        n = noteId.isEmpty() ? null : Store.get(c).find(noteId);
      } catch (Exception ignored) {
      }
      Intent svc = new Intent(c, NoteService.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
      svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
      v.setRemoteAdapter(R.id.n_list, svc);
      v.setEmptyView(R.id.n_list, R.id.n_empty);
      v.setPendingIntentTemplate(R.id.n_list,
          PendingIntent.getActivity(c, 30 + widgetId, new Intent(c, WidgetAction.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
      Intent open = n == null
          ? new Intent(c, WidgetPick.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId).setData(Uri.parse("memo-widget://" + widgetId))
          : new Intent(c, MainActivity.class).putExtra(EXTRA_OPEN, noteId).setData(Uri.parse("memo-note://" + noteId));
      PendingIntent pi = PendingIntent.getActivity(c, 10000 + widgetId, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      v.setOnClickPendingIntent(R.id.n_open, pi);
      v.setOnClickPendingIntent(R.id.n_title, pi);
      String title = n == null ? (noteId.isEmpty() ? "メモを選ぶ" : "メモが見つかりません") : Notes.title(n);
      if (n != null && n.optLong("trashed") > 0) title += "（ゴミ箱）";
      v.setTextViewText(R.id.n_title, title);
      Integer color = n == null ? null : Notes.COLOR.get(n.optString("color"));
      v.setInt(R.id.n_bg, "setColorFilter", color == null ? 0xFFFFFFFF : color);
      return v;
    }
  }

  public static class NoteService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
      return new NoteFactory(getApplicationContext(), intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0));
    }
  }

  static class NoteFactory implements RemoteViewsService.RemoteViewsFactory {
    final Context c;
    final int widgetId;
    String noteId = "";
    List<JSONObject> rows = new ArrayList<>();
    Bitmap cover;

    NoteFactory(Context c, int widgetId) {
      this.c = c;
      this.widgetId = widgetId;
    }

    public void onCreate() {}

    public void onDataSetChanged() {
      rows = new ArrayList<>();
      if (cover != null) cover.recycle();
      cover = null;
      noteId = c.getSharedPreferences("prefs", 0).getString(PREF + widgetId, "");
      try {
        JSONObject n = noteId.isEmpty() ? null : Store.get(c).find(noteId);
        if (n == null) return;
        cover = thumb(c, n, 480);
        if (cover != null) rows.add(new JSONObject().put("type", "image"));
        boolean skipTitle = n.optString("title").trim().isEmpty();
        for (JSONObject l : Notes.lines(n, 80)) {
          if (skipTitle && l.optString("text").equals(Notes.title(n))) {
            skipTitle = false;
            continue;
          }
          rows.add(l);
        }
      } catch (Exception ignored) {
      }
    }

    public void onDestroy() {
      if (cover != null) cover.recycle();
      cover = null;
    }

    public int getCount() {
      return rows.size();
    }

    public RemoteViews getViewAt(int i) {
      if (i >= rows.size()) return new RemoteViews(c.getPackageName(), R.layout.widget_note_line);
      JSONObject l = rows.get(i);
      Intent fill = new Intent().putExtra(EXTRA_OPEN, noteId).putExtra(EXTRA_WIDGET, widgetId);
      if (l.optString("type").equals("image")) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_note_image);
        if (cover != null) v.setImageViewBitmap(R.id.i_img, cover);
        v.setOnClickFillInIntent(R.id.i_img, fill);
        return v;
      }
      RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_note_line);
      String text = l.optString("text");
      SpannableString s = new SpannableString(text);
      boolean check = l.optString("type").equals("check");
      if (check) {
        v.setViewVisibility(R.id.l_box, View.VISIBLE);
        v.setImageViewResource(R.id.l_box, l.optBoolean("checked") ? R.drawable.ic_w_checked : R.drawable.ic_w_box);
        if (l.optBoolean("checked")) {
          s.setSpan(new StrikethroughSpan(), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
          v.setTextColor(R.id.l_text, 0xFF80868B);
        }
        fill.putExtra(EXTRA_LINE, l.optInt("line"));
      } else v.setViewVisibility(R.id.l_box, View.GONE);
      if (l.optString("type").equals("heading")) s.setSpan(new StyleSpan(Typeface.BOLD), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      v.setTextViewText(R.id.l_text, s);
      v.setOnClickFillInIntent(R.id.l_row, fill);
      return v;
    }

    public RemoteViews getLoadingView() {
      return null;
    }

    public int getViewTypeCount() {
      return 2;
    }

    public long getItemId(int i) {
      return i;
    }

    public boolean hasStableIds() {
      return false;
    }
  }
}
