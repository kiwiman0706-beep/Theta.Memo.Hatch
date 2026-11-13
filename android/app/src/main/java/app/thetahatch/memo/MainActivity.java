package app.thetahatch.memo;

import android.Manifest;
import android.app.*;
import android.appwidget.AppWidgetManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.*;
import android.webkit.*;
import android.widget.FrameLayout;
import androidx.core.content.FileProvider;
import com.google.android.gms.auth.api.identity.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

// The app: one WebView with the shared UI (assets/), and this bridge for what needs the phone
// (files, camera, microphone, Google Drive, widgets). UI → here: Native.call(JSON {action,…});
// here → UI: window.onNative({type, data}) — the Theta Hatch series bridge.
public class MainActivity extends Activity {
  static final String HOST = "memo.theta.local";
  static final int PICK_FILES = 11, CAMERA = 12, IMPORT_KEEP = 13, DRIVE_AUTH = 14, SCAN_DOC = 15, CAPTURE = 16, PERMS = 44;
  static final String CAMERA_HATCH = "app.thetahatch.camera", ACTION_SCAN = "app.thetahatch.action.SCAN";
  static final String ACTION_CAPTURE = "app.thetahatch.action.CAPTURE";
  // Opened by another series app's "scan" button (spec §6.2 SCAN): back to it when the note is closed.
  boolean scanCaller = false;
  static volatile MainActivity instance;
  public static volatile String editing = "";
  WebView web;
  Store store;
  boolean ready, busy;
  final ExecutorService io = Executors.newSingleThreadExecutor();
  final ExecutorService bg = Executors.newCachedThreadPool();
  JSONObject pending = new JSONObject(); // what a picker / camera result belongs to

  // Kept on disk too: Android may close this app while the camera is open, and what was taken
  // must still find its note when the app comes back.
  void setPending(JSONObject p) {
    pending = p;
    prefs().edit().putString("pending", p.toString()).apply();
  }
  Runnable afterPermission;
  String launch = "";

  interface Work {
    void run() throws Exception;
  }

  interface TokenWork {
    void run(String token) throws Exception;
  }

  TokenWork pendingToken;

  // From services and other classes: deliver to the open UI, if any.
  public static void post(String type, Object data) {
    MainActivity a = instance;
    if (a == null) return;
    if (type.equals("changed")) a.sendNotes();
    else a.event(type, data);
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    try {
      store = Store.get(this);
    } catch (Exception e) {
      new AlertDialog.Builder(this).setTitle("データを開けません").setMessage(e.getMessage())
          .setPositiveButton("閉じる", (d, w) -> finish()).setCancelable(false).show();
      return;
    }
    instance = this;
    try {
      pending = new JSONObject(prefs().getString("pending", "{}"));
    } catch (Exception ignored) {
    }
    web = new WebView(this);
    web.setBackgroundColor(Color.rgb(248, 249, 250));
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(false);
    s.setAllowFileAccess(false);
    s.setAllowContentAccess(false);
    s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    s.setMediaPlaybackRequiresUserGesture(true);
    s.setTextZoom(100);
    web.addJavascriptInterface(new Bridge(), "Native");
    web.setWebViewClient(
        new WebViewClient() {
          public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            try {
              if (!HOST.equals(u.getHost()) || !"https".equals(u.getScheme())) return blocked();
              String p = u.getPath();
              if (p.equals("/") || p.equals("/index.html")) return response("text/html", getAssets().open("index.html"));
              if (p.matches("/(app|ink|editor|md|gtd|mindmap)\\.js")) return response("application/javascript", getAssets().open(p.substring(1)));
              if (p.equals("/style.css")) return response("text/css", getAssets().open("style.css"));
              if (p.matches("/lib/[A-Za-z0-9.-]+\\.js")) return response("application/javascript", getAssets().open(p.substring(1)));
              if (p.matches("/lib/[A-Za-z0-9.-]+\\.css")) return response("text/css", getAssets().open(p.substring(1)));
              if (p.matches("/lib/fonts/[a-z_]+\\.ttf")) return response("font/ttf", getAssets().open(p.substring(1)));
              if (p.startsWith("/media/")) return media(p.substring(7), r.getRequestHeaders());
              if (p.startsWith("/thumb/")) {
                File t = Thumbs.get(store, p.substring(7));
                if (t != null) return response("image/jpeg", new FileInputStream(t));
                return notFound();
              }
            } catch (Exception ignored) {
            }
            return blocked();
          }

          public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (HOST.equals(u.getHost())) return false;
            try {
              external(u.toString());
            } catch (Exception e) {
              error(e);
            }
            return true;
          }
        });
    web.setWebChromeClient(
        new WebChromeClient() {
          public boolean onJsConfirm(WebView v, String url, String message, JsResult result) {
            new AlertDialog.Builder(MainActivity.this).setMessage(message)
                .setPositiveButton("OK", (d, w) -> result.confirm())
                .setNegativeButton("キャンセル", (d, w) -> result.cancel())
                .setOnCancelListener(d -> result.cancel()).show();
            return true;
          }

          public boolean onJsAlert(WebView v, String url, String message, JsResult result) {
            new AlertDialog.Builder(MainActivity.this).setMessage(message)
                .setPositiveButton("OK", (d, w) -> result.confirm()).setOnCancelListener(d -> result.cancel()).show();
            return true;
          }
        });
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Color.rgb(248, 249, 250));
    root.addView(web, new FrameLayout.LayoutParams(-1, -1));
    setContentView(root);
    if (Build.VERSION.SDK_INT >= 33)
      getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
    if (Build.VERSION.SDK_INT >= 30) {
      getWindow().setDecorFitsSystemWindows(false);
      root.setOnApplyWindowInsetsListener(
          (v, insets) -> {
            android.graphics.Insets bar = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bar.left, bar.top, bar.right, Math.max(bar.bottom, ime.bottom));
            return insets;
          });
    }
    web.loadUrl("https://" + HOST + "/index.html");
    fromIntent(getIntent());
    io.execute(
        () -> {
          // Notes left in the app's trash for 30 days are deleted for good (as Keep does after 7).
          try {
            long limit = System.currentTimeMillis() - 30L * 86400 * 1000;
            for (JSONObject n : store.all()) if (n.optLong("trashed") > 0 && n.optLong("trashed") < limit) store.remove(n.getString("id"));
            store.sweep(System.currentTimeMillis() - 2L * 86400 * 1000);
          } catch (Exception ignored) {
          }
          if (UpdateCheck.run(this, versionName(), false)) refresh();
        });
    SyncJob.schedule(this);
  }

  WebResourceResponse response(String mime, InputStream in) {
    WebResourceResponse r = new WebResourceResponse(mime, mime.startsWith("text") || mime.contains("javascript") ? "UTF-8" : null, in);
    Map<String, String> h = new HashMap<>();
    h.put("Cache-Control", "no-store");
    r.setResponseHeaders(h);
    return r;
  }

  WebResourceResponse blocked() {
    return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", null, new ByteArrayInputStream(new byte[0]));
  }

  WebResourceResponse notFound() {
    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, new ByteArrayInputStream(new byte[0]));
  }

  // Attachments, with byte ranges so audio and video can seek.
  WebResourceResponse media(String name, Map<String, String> headers) throws Exception {
    File f = store.mediaFile(name);
    if (!f.isFile()) return notFound();
    String mime = Sync.mime(name);
    long len = f.length();
    String range = null;
    for (Map.Entry<String, String> e : headers.entrySet()) if (e.getKey().equalsIgnoreCase("Range")) range = e.getValue();
    Map<String, String> h = new HashMap<>();
    h.put("Accept-Ranges", "bytes");
    if (range != null && range.startsWith("bytes=")) {
      String[] p = range.substring(6).split("-", 2);
      long start = p[0].isEmpty() ? 0 : Long.parseLong(p[0].trim());
      long end = p.length > 1 && !p[1].trim().isEmpty() ? Long.parseLong(p[1].trim()) : len - 1;
      end = Math.min(end, len - 1);
      if (start > end) start = 0;
      RandomAccessFile raf = new RandomAccessFile(f, "r");
      raf.seek(start);
      final long count = end - start + 1;
      InputStream in =
          new InputStream() {
            long left = count;

            public int read() throws IOException {
              if (left <= 0) return -1;
              left--;
              return raf.read();
            }

            public int read(byte[] b, int off, int n) throws IOException {
              if (left <= 0) return -1;
              int r = raf.read(b, off, (int) Math.min(n, left));
              if (r > 0) left -= r;
              return r;
            }

            public void close() throws IOException {
              raf.close();
            }
          };
      h.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
      h.put("Content-Length", "" + count);
      return new WebResourceResponse(mime, null, 206, "Partial Content", h, in);
    }
    h.put("Content-Length", "" + len);
    return new WebResourceResponse(mime, null, 200, "OK", h, new FileInputStream(f));
  }

  void event(String type, Object data) {
    try {
      String json = new JSONObject().put("type", type).put("data", data == null ? JSONObject.NULL : data).toString();
      runOnUiThread(() -> {
        if (web != null && ready) web.evaluateJavascript("window.onNative(" + json + ")", null);
      });
    } catch (Exception ignored) {
    }
  }

  void error(Exception e) {
    event("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
  }

  void toast(String s) {
    event("toast", s);
  }

  SharedPreferences prefs() {
    return getSharedPreferences("prefs", 0);
  }

  String versionName() {
    try {
      return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (Exception e) {
      return "0";
    }
  }

  // ---- intents: widgets, Home.Hatch, share ------------------------------------------

  void fromIntent(Intent i) {
    if (i == null) return;
    String open = i.getStringExtra(Widgets.EXTRA_OPEN);
    String kind = i.getStringExtra(Widgets.EXTRA_NEW);
    if (ACTION_SCAN.equals(i.getAction())) {
      kind = "pick".equals(i.getStringExtra("mode")) ? "file" : captureIntent() != null ? "photo" : "scan";
      scanCaller = true;
      i.setAction(null);
    }
    if (kind == null && i.getAction() != null && i.getAction().startsWith("app.thetahatch.memo.NEW"))
      kind = i.getStringExtra("type") == null ? "text" : i.getStringExtra("type");
    if (open != null) launch = "open:" + open;
    else if (kind != null) launch = "new:" + kind + ":" + (i.getStringExtra(Intent.EXTRA_TEXT) == null ? "" : i.getStringExtra(Intent.EXTRA_TEXT));
    else return;
    i.removeExtra(Widgets.EXTRA_OPEN);
    i.removeExtra(Widgets.EXTRA_NEW);
    if (ready) runLaunch();
  }

  void runLaunch() {
    String l = launch;
    launch = "";
    try {
      if (l.startsWith("open:")) event("open", l.substring(5));
      else if (l.startsWith("new:")) {
        String[] p = l.split(":", 3);
        event("new", new JSONObject().put("kind", p[1]).put("text", p.length > 2 ? p[2] : ""));
      }
    } catch (Exception e) {
      error(e);
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    fromIntent(intent);
  }

  // ---- state --------------------------------------------------------------------------

  void refresh() {
    try {
      JSONObject s = new JSONObject();
      s.put("version", versionName())
          .put("platform", "android")
          .put("keySet", prefs().contains("key"))
          .put("model", prefs().getString("model", "gemini-2.5-flash"))
          .put("transcribe", prefs().getString("transcribe", "auto"))
          .put("deviceRecognizer", Build.VERSION.SDK_INT >= 33 && android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
          .put("shareOpen", prefs().getBoolean("shareOpen", false))
          .put("shelfPinned", prefs().getBoolean("shelfPinned", true))
          .put("scanPages", prefs().getString("scanPages", "pdf"))
          .put("layout", prefs().getString("layout", "grid"))
          .put("skin", prefs().getString("skin", "system"))
          .put("syncEnabled", prefs().getBoolean("syncEnabled", false))
          .put("nasEnabled", prefs().getBoolean("nasEnabled", false))
          .put("nasUrl", prefs().getString("nasUrl", ""))
          .put("nasAccount", prefs().getString("nasAccount", ""))
          .put("nasRoot", prefs().getString("nasRoot", ""))
          .put("nasPasswordSet", !prefs().getString("nasPassword", "").isEmpty())
          .put("hub", Hub.installed(this).isEmpty() ? "" : SeriesProvider.label(this, Hub.installed(this)))
          .put("lastSync", prefs().getLong("lastSync", 0))
          .put("syncError", prefs().getString("syncError", ""))
          .put("appPackage", getPackageName())
          .put("appSha1", signingSha1())
          .put("widgets", Widgets.count(this))
          .put("recording", RecordingService.activeNote)
          .put("recordingStart", RecordingService.startTime)
          .put("busy", busy);
      JSONObject update = UpdateCheck.pending(this, versionName());
      if (update != null) s.put("update", update);
      if (!prefs().contains("key")) {
        // The settings hub first (Home / KidsHome), then other series apps that still lend.
        JSONArray lenders = new JSONArray();
        String hub = Hub.installed(this);
        if (!hub.isEmpty()) lenders.put(new JSONObject().put("package", hub).put("label", SeriesProvider.label(this, hub)).put("hub", true));
        for (String[] l : SeriesProvider.lenders(this)) if (!l[0].equals(hub)) lenders.put(new JSONObject().put("package", l[0]).put("label", l[1]));
        if (lenders.length() > 0) s.put("keyLenders", lenders);
      }
      Series.state(this, s);
      event("state", s);
    } catch (Exception e) {
      error(e);
    }
  }

  void sendNotes() {
    bg.execute(() -> {
      try {
        event("notes", store.allJson());
      } catch (Exception e) {
        error(e);
      }
    });
  }

  String signingSha1() {
    try {
      android.content.pm.Signature[] sig =
          Build.VERSION.SDK_INT >= 28
              ? getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.getApkContentsSigners()
              : getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES).signatures;
      byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(sig[0].toByteArray());
      StringBuilder b = new StringBuilder();
      for (byte x : d) b.append(b.length() == 0 ? "" : ":").append(String.format("%02X", x));
      return b.toString();
    } catch (Exception e) {
      return "";
    }
  }

  // ---- work ---------------------------------------------------------------------------

  void task(String title, Work work) {
    if (busy) {
      toast("処理が終わるまでお待ちください");
      return;
    }
    busy = true;
    event("busy", title);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    io.execute(() -> {
      try {
        work.run();
      } catch (Exception e) {
        error(e);
      } finally {
        busy = false;
        refresh();
        event("busy", "");
        runOnUiThread(() -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
      }
    });
  }

  void withDriveToken(TokenWork work) {
    Identity.getAuthorizationClient(this)
        .authorize(DriveAuth.request())
        .addOnSuccessListener(r -> {
          if (r.hasResolution()) {
            pendingToken = work;
            try {
              startIntentSenderForResult(r.getPendingIntent().getIntentSender(), DRIVE_AUTH, null, 0, 0, 0);
            } catch (Exception e) {
              pendingToken = null;
              error(e);
            }
          } else runToken(work, r.getAccessToken());
        })
        .addOnFailureListener(e -> {
          try {
            event("driveSetup", new JSONObject().put("reason", "Google Driveに接続できませんでした：" + e.getMessage()));
          } catch (Exception ignored) {
          }
        });
  }

  void runToken(TokenWork work, String token) {
    if (token == null || token.isEmpty()) {
      event("driveSetup", "Google Driveの許可を取得できませんでした");
      return;
    }
    try {
      work.run(token);
    } catch (Exception e) {
      error(e);
    }
  }

  // Sync every vault in use: the NAS (the primary copy) and Google Drive (needs a token first).
  void sync(boolean quiet) {
    if (SyncJob.driveOn(this)) withDriveToken(token -> runSync(token, quiet));
    else runSync(null, quiet);
  }

  void runSync(String token, boolean quiet) {
    Work w = () -> {
      VaultSync.Result r = SyncJob.runWith(this, token, t -> event("busy", t));
      if (!quiet || !r.errors.isEmpty() || r.down + r.up + r.merged + r.deleted > 0) toast(r.text());
      sendNotes();
    };
    if (quiet) {
      io.execute(() -> {
        try {
          event("syncing", true);
          w.run();
        } catch (IllegalStateException ignored) {
        } catch (Exception e) {
          prefs().edit().putString("syncError", String.valueOf(e.getMessage())).apply();
        } finally {
          event("syncing", false);
          refresh();
        }
      });
    } else task("Theta Vaultと同期中", w);
  }

  Nas nas() {
    return new Nas(this, prefs());
  }

  // The NAS's state for the settings screen: ok, otp (code needed), cert (trust the fingerprint), or a problem.
  void nasCheck(boolean thenSync) {
    task("NAS に接続しています", () -> {
      Nas.Status st = nas().check();
      JSONObject r = new JSONObject().put("message", st.message).put("tailscale", st.tailscale).put("vpn", st.vpn);
      r.put("state", st.nas == Nas.OK ? "ok" : st.nas == Nas.NEED_OTP ? "otp" : st.nas == Nas.CERT ? "cert" : "error");
      if (st.cert != null) r.put("cert", st.cert);
      if (st.nas == Nas.OK) {
        String root = prefs().getString("nasRoot", "");
        Nas.Item it = root.isEmpty() ? null : nas().info(root);
        if (it == null || !it.dir) {
          r.put("state", "root");
          r.put("message", root.isEmpty() ? "Theta Vault を置くフォルダを入れてください" : "フォルダ " + root + " が NAS にありません（共有フォルダ名から入れてください。例：/home/Obsidian）");
        }
      }
      event("nasStatus", r);
      if (thenSync && r.getString("state").equals("ok")) {
        prefs().edit().putBoolean("nasEnabled", true).apply();
        SyncJob.schedule(this);
        runOnUiThread(() -> sync(false));
      }
    });
  }

  void permission(String[] perms, Runnable next) {
    List<String> missing = new ArrayList<>();
    for (String p : perms) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
    if (missing.isEmpty()) next.run();
    else {
      afterPermission = next;
      requestPermissions(missing.toArray(new String[0]), PERMS);
    }
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] p, int[] r) {
    super.onRequestPermissionsResult(code, p, r);
    if (code != PERMS) return;
    boolean granted = r.length > 0;
    for (int i = 0; i < r.length; i++)
      if (r[i] != PackageManager.PERMISSION_GRANTED && !p[i].equals(Manifest.permission.POST_NOTIFICATIONS)) granted = false;
    Runnable next = afterPermission;
    afterPermission = null;
    if (granted && next != null) next.run();
    else toast("必要な権限が許可されませんでした。設定 → アプリ → Theta Memo Hatch → 権限から変更できます");
  }

  class Bridge {
    @JavascriptInterface
    public void call(String json) {
      runOnUiThread(() -> {
        try {
          JSONObject q = new JSONObject(json);
          handle(q.optString("action"), q);
        } catch (Exception e) {
          error(e);
        }
      });
    }
  }

  long widgetTouched = 0;

  void handle(String action, JSONObject q) throws Exception {
    switch (action) {
      case "boot":
        ready = true;
        refresh();
        sendNotes();
        if (!launch.isEmpty()) runLaunch();
        bg.execute(() -> {
          if (Series.fromHub(this)) refresh();
        });
        if (SyncJob.any(this)) sync(true);
        return;
      case "refresh":
        refresh();
        return;
      case "editing":
        editing = q.optString("id");
        if (editing.isEmpty() && scanCaller) {
          scanCaller = false;
          setResult(RESULT_OK);
          finish();
        }
        return;
      case "save":
        {
          JSONObject n = q.getJSONObject("note");
          JSONObject old = store.find(n.getString("id"));
          if (old != null && Sync.hash(old).equals(Sync.hash(n))) return;
          if (old != null) for (String k : Sync.LOCAL) if (old.has(k) && !n.has(k)) n.put(k, old.get(k));
          store.put(n);
          // Widgets follow edits, at most every few seconds while typing.
          if (System.currentTimeMillis() - widgetTouched > 3000) {
            widgetTouched = System.currentTimeMillis();
            Widgets.update(this);
          }
          return;
        }
      case "saveMany":
        {
          JSONArray a = q.getJSONArray("notes");
          for (int i = 0; i < a.length(); i++) store.put(a.getJSONObject(i));
          Widgets.update(this);
          return;
        }
      case "delete":
        {
          JSONArray ids = q.optJSONArray("ids");
          if (ids == null) ids = new JSONArray().put(q.getString("id"));
          for (int i = 0; i < ids.length(); i++) store.remove(ids.getString(i));
          Widgets.update(this);
          sendNotes();
          return;
        }
      case "attach":
        setPending(new JSONObject().put("note", q.getString("note")).put("req", q.optString("req")));
        startActivityForResult(
            new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(q.optString("accept", "*/*")).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
            PICK_FILES);
        return;
      case "camera":
        if (capture(q, "photo")) return;
        {
          String name = Store.newName(".jpg");
          File f = store.mediaFile(name);
          setPending(new JSONObject().put("note", q.getString("note")).put("name", name).put("req", q.optString("req")));
          Uri u = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
          Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, u)
              .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
          i.setClipData(ClipData.newRawUri("photo", u));
          try {
            startActivityForResult(i, CAMERA);
          } catch (ActivityNotFoundException e) {
            toast("カメラのアプリが見つかりません");
          }
          return;
        }
      case "scan":
        if (!capture(q, "scan")) scan(q);
        return;
      case "saveInk":
        {
          String base = Store.newName("");
          String png = base + ".ink.png", strokes = base + ".ink.json";
          String data = q.getString("png");
          byte[] bytes = Base64.decode(data.substring(data.indexOf(',') + 1), Base64.DEFAULT);
          try (OutputStream o = new FileOutputStream(store.mediaFile(png))) {
            o.write(bytes);
          }
          try (OutputStream o = new FileOutputStream(store.mediaFile(strokes))) {
            o.write(q.getString("strokes").getBytes("UTF-8"));
          }
          event("inkSaved", new JSONObject().put("note", q.getString("note")).put("req", q.optString("req")).put("name", png));
          return;
        }
      case "inkStrokes":
        {
          String name = q.getString("name");
          File f = store.mediaFile(name.replaceFirst("\\.png$", ".json"));
          String strokes = f.isFile() ? Store.readText(f) : "";
          event("inkStrokes", new JSONObject().put("name", name).put("req", q.optString("req")).put("strokes", strokes));
          return;
        }
      case "inkText":
        {
          String note = q.getString("note"), name = q.getString("name");
          if (!Gemini.available(this)) {
            toast("手書きを文字にするには、設定でGemini APIキーを入力してください");
            return;
          }
          toast("手書きを文字にしています");
          bg.execute(() -> {
            try {
              String t = new Gemini(this).ink(store.mediaFile(name));
              if (t.isEmpty()) toast("文字は見つかりませんでした");
              else runOnUiThread(() -> {
                try {
                  Memo.transcript(this, note, name, t);
                } catch (Exception e) {
                  error(e);
                }
              });
            } catch (Exception e) {
              error(e);
            }
          });
          return;
        }
      case "record":
        {
          String note = q.optString("note");
          List<String> perms = new ArrayList<>(Collections.singletonList(Manifest.permission.RECORD_AUDIO));
          if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS);
          permission(perms.toArray(new String[0]), () -> RecordingService.start(this, note));
          return;
        }
      case "stopRecord":
        RecordingService.stop(this);
        return;
      case "transcribe":
        Transcriber.again(this, q.getString("note"), q.getString("name"));
        return;
      case "level":
        event("level", RecordingService.level);
        return;
      case "openFile":
        openFile(q.getString("name"), false);
        return;
      case "shareFile":
        openFile(q.getString("name"), true);
        return;
      case "shareNote":
        shareNote(q);
        return;
      case "syncNow":
        // First use (nothing on yet, or "drive" asked for): start with Google Drive.
        if ("drive".equals(q.optString("target")) || !SyncJob.any(this)) {
          prefs().edit().putBoolean("syncEnabled", true).apply();
          SyncJob.schedule(this);
        }
        sync(false);
        return;
      case "syncQuiet":
        if (SyncJob.any(this)) sync(true);
        return;
      case "syncOff":
        {
          boolean nasOnly = "nas".equals(q.optString("target"));
          prefs().edit().putBoolean(nasOnly ? "nasEnabled" : "syncEnabled", false).apply();
          SyncJob.schedule(this);
          refresh();
          toast((nasOnly ? "NAS" : "Google ドライブ") + "との同期を止めました（Vaultのファイルはそのまま残ります）");
          return;
        }
      case "nasSave":
        {
          Nas n = nas();
          bg.execute(() -> {
            try {
              n.save(q.optString("url"), q.optString("user"), q.optString("password"));
              String root = q.optString("root").trim().replaceAll("/+$", "");
              if (!root.isEmpty() && !root.startsWith("/")) root = "/" + root;
              if (!root.equals(prefs().getString("nasRoot", ""))) {
                prefs().edit().putString("nasRoot", root).apply();
                VaultSync.forget(store, VaultSync.NAS); // another folder: compare from the start
                Series.changed(this, "nas.shareRoot");
              }
              runOnUiThread(() -> nasCheck(q.optBoolean("start")));
            } catch (Exception e) {
              error(e);
            }
          });
          return;
        }
      case "nasCheck":
        nasCheck(q.optBoolean("start"));
        return;
      case "nasOtp":
        {
          Nas n = nas();
          n.setOtp(q.optString("code"));
          // The code is used by the next login, in the same Nas object.
          task("NAS にログインしています", () -> {
            Nas.Status st = n.check();
            event("nasStatus", new JSONObject().put("state", st.nas == Nas.OK ? "ok" : st.nas == Nas.NEED_OTP ? "otp" : "error").put("message", st.message));
            if (st.nas == Nas.OK && q.optBoolean("start")) runOnUiThread(() -> nasCheck(true));
          });
          return;
        }
      case "nasTrust":
        nas().trustCert(q.getString("cert"));
        nasCheck(q.optBoolean("start"));
        return;
      case "nasFromHub":
        {
          // The user chose to use the hub's NAS settings: address, account and folder, and the
          // password and device token (secrets: copied once, kept encrypted on this phone).
          String hub = Hub.installed(this);
          if (hub.isEmpty()) throw new IOException("Theta Home Hatch（設定のハブ）が見つかりません");
          java.util.Map<String, String[]> info = Hub.info(this);
          String url = info.containsKey("nas.url") ? info.get("nas.url")[0] : "", user = info.containsKey("nas.user") ? info.get("nas.user")[0] : "";
          if (url.isEmpty()) throw new IOException(SeriesProvider.label(this, hub) + " に NAS が登録されていません");
          String password = Hub.secret(this, hub, "nas.password"), did = Hub.secret(this, hub, "nas.deviceToken");
          nas().save(url, user, password);
          SharedPreferences.Editor e = prefs().edit();
          if (!did.isEmpty()) e.putString("nasDid", did);
          if (info.containsKey("nas.shareRoot") && prefs().getString("nasRoot", "").isEmpty()) e.putString("nasRoot", info.get("nas.shareRoot")[0]);
          e.apply();
          refresh();
          toast(SeriesProvider.label(this, hub) + " の NAS の設定を使います");
          nasCheck(false);
          return;
        }
      case "seriesAdopt":
        {
          Set<String> keys = new HashSet<>();
          JSONArray a = q.optJSONArray("keys");
          for (int i = 0; a != null && i < a.length(); i++) keys.add(a.getString(i));
          Series.adopt(this, keys);
          refresh();
          toast(keys.isEmpty() ? "このアプリの設定のまま使います" : keys.size() + "項目を引き継ぎました");
          return;
        }
      case "seriesFollow":
        Series.follow(this, q.getString("key"), q.getBoolean("on"));
        refresh();
        return;
      case "importKeep":
        setPending(new JSONObject().put("trashed", q.optBoolean("trashed")));
        startActivityForResult(
            new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"})
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
            IMPORT_KEEP);
        return;
      case "clip":
        {
          String url = Clipper.firstUrl(q.getString("url"));
          if (url == null) throw new IOException("URLを入力してください");
          JSONObject n = Store.empty();
          n.put("body", url).put("source", url);
          store.put(n);
          sendNotes();
          toast("ページの情報を取得しています");
          bg.execute(() -> {
            try {
              JSONObject cur = store.note(n.getString("id"));
              store.put(Clipper.enrich(store, cur, url, "", true));
              sendNotes();
              Widgets.update(this);
            } catch (Exception e) {
              error(e);
            }
          });
          return;
        }
      case "settings":
        {
          SharedPreferences.Editor e = prefs().edit();
          if (q.has("key")) VaultKey.save(this, q.getString("key").trim());
          if (q.has("model")) e.putString("model", q.getString("model").trim());
          if (q.has("skin") && Series.SKINS.contains(q.getString("skin"))) e.putString("skin", q.getString("skin"));
          if (q.has("transcribe")) e.putString("transcribe", q.getString("transcribe"));
          if (q.has("shareOpen")) e.putBoolean("shareOpen", q.getBoolean("shareOpen"));
          if (q.has("shelfPinned")) e.putBoolean("shelfPinned", q.getBoolean("shelfPinned"));
          if (q.has("scanPages")) e.putString("scanPages", "images".equals(q.getString("scanPages")) ? "images" : "pdf");
          if (q.has("layout")) e.putString("layout", q.getString("layout"));
          e.apply();
          if (q.has("skin")) Series.changed(this, "ui.skin");
          if (q.has("shelfPinned")) getContentResolver().notifyChange(SeriesProvider.shelfUri(this), null);
          if (q.has("model")) Series.changed(this, "gemini.model");
          refresh();
          return;
        }
      case "models":
        bg.execute(() -> {
          try {
            event("models", new Gemini(this).models());
          } catch (Exception e) {
            error(e);
          }
        });
        return;
      case "importKey":
        {
          String pkg = q.getString("package");
          String key = Arrays.asList(Hub.HUBS).contains(pkg) ? Hub.secret(this, pkg, "gemini.key") : SeriesProvider.borrow(this, pkg);
          if (key.isEmpty()) throw new IOException(SeriesProvider.label(this, pkg) + " には Gemini API キーが登録されていません");
          VaultKey.save(this, key);
          refresh();
          toast(SeriesProvider.label(this, pkg) + " のAPIキーを使います");
          return;
        }
      case "pinWidget":
        {
          AppWidgetManager m = AppWidgetManager.getInstance(this);
          String note = q.optString("note");
          Class<?> c = q.optString("kind").equals("note") || !note.isEmpty() ? Widgets.NoteWidget.class : Widgets.ListWidget.class;
          if (!m.isRequestPinAppWidgetSupported()) {
            toast("ホーム画面を長押し →「ウィジェット」→「Theta Memo Hatch」から追加してください");
            return;
          }
          // A given note: Android tells Widgets.Pinned the new widget's id once it is placed.
          PendingIntent placed = note.isEmpty() ? null
              : PendingIntent.getBroadcast(this, note.hashCode(), new Intent(this, Widgets.Pinned.class).putExtra(Widgets.EXTRA_OPEN, note),
                  PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
          m.requestPinAppWidget(new ComponentName(this, c), null, placed);
          return;
        }
      case "copyText":
        {
          ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
          cm.setPrimaryClip(ClipData.newPlainText("Theta Memo", q.optString("text")));
          if (Build.VERSION.SDK_INT < 33) toast("コピーしました");
          return;
        }
      case "openUrl":
        external(q.getString("url"));
        return;
      case "installUpdate":
        // Installing is the update hub's job (series spec §6.1); this only asks it.
        if (q.optBoolean("check")) {
          bg.execute(() -> {
            UpdateCheck.run(this, versionName(), true);
            refresh();
            try {
              toast(UpdateCheck.pending(this, versionName()) == null ? "最新の版です（" + versionName() + "）" : "新しい版があります");
            } catch (Exception e) {
              error(e);
            }
          });
          return;
        }
        UpdateCheck.ask(this);
        return;
      case "exit":
        finish();
        return;
      default:
        throw new IOException("不明な操作です：" + action);
    }
  }

  // Theta Camera Hatch, when installed (spec §6.2 CAPTURE): photos, videos and scans in one camera
  // screen, any number, handed back to this note. Without it, the phone's camera / Google's scanner.
  Intent captureIntent() {
    Intent i = new Intent(ACTION_CAPTURE).setPackage(CAMERA_HATCH);
    return i.resolveActivity(getPackageManager()) != null ? i : null;
  }

  boolean capture(JSONObject q, String mode) throws Exception {
    Intent i = captureIntent();
    if (i == null) return false;
    setPending(new JSONObject().put("note", q.getString("note")).put("req", q.optString("req")));
    i.putExtra("source", "memo")
        .putExtra("mode", mode)
        .putExtra("modes", new String[] {"photo", "video", "scan"})
        .putExtra("multiple", true)
        .putExtra("scanFormat", "images".equals(prefs().getString("scanPages", "pdf")) ? "images" : "pdf");
    try {
      startActivityForResult(i, CAPTURE);
      return true;
    } catch (Exception e) {
      return false; // e.g. an older Camera Hatch, or this app not signed with the series key
    }
  }

  // What Camera Hatch handed back: copied at once (its files go after 30 minutes).
  void captured(Intent data) throws Exception {
    final List<Uri> uris = new ArrayList<>();
    if (data != null && data.getClipData() != null)
      for (int k = 0; k < data.getClipData().getItemCount(); k++) {
        Uri u = data.getClipData().getItemAt(k).getUri();
        if (u != null) uris.add(u);
      }
    if (uris.isEmpty() && data != null && data.getData() != null) uris.add(data.getData());
    if (uris.isEmpty()) throw new IOException("Camera Hatch から受け取れるものがありませんでした");
    final JSONObject p = pending;
    task("撮ったものを取り込み中", () -> {
      JSONArray files = new JSONArray();
      String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HHmm", Locale.JAPAN).format(new Date());
      for (Uri u : uris) {
        JSONObject f = ShareActivity.importFile(this, u);
        String name = f.getString("name");
        // Pictures and videos show as themselves; a scanned PDF gets a readable name.
        if (name.endsWith(".pdf")) f.put("label", "スキャン " + stamp + ".pdf");
        else f.put("label", "");
        files.put(f);
      }
      deliverAttached(p, files);
    });
  }

  // Scan mode (as in Meishi / Chirashi): Google's document scanner finds the edges, straightens and
  // cleans up the page; several pages can be taken. One page becomes a picture in the note, more
  // pages one PDF (with the pages also as pictures when asked).
  void scan(JSONObject q) throws Exception {
    setPending(new JSONObject().put("note", q.getString("note")).put("req", q.optString("req")));
    com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions options =
        new com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setResultFormats(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build();
    toast("スキャンを準備中（初回は Google Play 開発者サービスが機能を取得します）");
    com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(options).getStartScanIntent(this)
        .addOnSuccessListener(sender -> {
          try {
            startIntentSenderForResult(sender, SCAN_DOC, null, 0, 0, 0);
          } catch (Exception e) {
            error(e);
          }
        })
        .addOnFailureListener(e -> event("error", "スキャンを使えません（Google Play 開発者サービスが必要です）：" + e.getMessage()));
  }

  void scanned(Intent data) throws Exception {
    com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult r =
        com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.fromActivityResultIntent(data);
    if (r == null || r.getPages() == null || r.getPages().isEmpty()) throw new IOException("スキャンした画像がありませんでした");
    final JSONObject p = pending;
    final boolean asPdf = r.getPages().size() > 1 && r.getPdf() != null && !"images".equals(prefs().getString("scanPages", "pdf"));
    final List<Uri> pages = new ArrayList<>();
    for (com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.Page page : r.getPages()) pages.add(page.getImageUri());
    final Uri pdf = r.getPdf() == null ? null : r.getPdf().getUri();
    task("スキャンを保存中", () -> {
      JSONArray files = new JSONArray();
      if (asPdf) {
        JSONObject f = ShareActivity.importFile(this, pdf);
        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HHmm", Locale.JAPAN).format(new Date());
        f.put("label", "スキャン " + stamp + "（" + pages.size() + "ページ）.pdf");
        files.put(f);
      } else
        for (Uri u : pages) {
          JSONObject f = ShareActivity.importFile(this, u);
          f.put("label", "").put("mime", "image/jpeg");
          files.put(f);
        }
      deliverAttached(p, files);
    });
  }

  // Files for a note: into the open editor, or straight into the stored note (created if it was a
  // new, still empty note) — nothing taken is ever dropped.
  void deliverAttached(JSONObject p, JSONArray files) throws Exception {
    String note = p.optString("note");
    setPending(new JSONObject());
    if (note.isEmpty()) note = Store.empty().getString("id");
    if (Memo.editing(note)) {
      event("attached", new JSONObject().put("note", note).put("req", p.optString("req")).put("files", files));
      return;
    }
    Memo.attach(this, note, files);
    toast(files.length() + " 件をメモに入れました");
  }

  void external(String url) throws Exception {
    Uri u = Uri.parse(url);
    if (!Arrays.asList("https", "http", "tel", "mailto", "geo").contains(String.valueOf(u.getScheme()))) throw new IOException("このリンクは開けません");
    startActivity(new Intent(Intent.ACTION_VIEW, u));
  }

  void openFile(String name, boolean share) throws Exception {
    File f = store.mediaFile(name);
    if (!f.isFile()) throw new IOException("ファイルがまだこの端末にありません。同期してからお試しください");
    Uri u = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
    String mime = Sync.mime(name);
    Intent i = new Intent(share ? Intent.ACTION_SEND : Intent.ACTION_VIEW).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    if (share) i.setType(mime).putExtra(Intent.EXTRA_STREAM, u);
    else i.setDataAndType(u, mime);
    i.setClipData(ClipData.newRawUri("file", u));
    startActivity(Intent.createChooser(i, share ? "共有先を選択" : "開くアプリを選択"));
  }

  void shareNote(JSONObject q) throws Exception {
    JSONObject n = store.note(q.getString("id"));
    String text = (n.optString("title").isEmpty() ? "" : n.optString("title") + "\n\n") + q.optString("text", n.optString("body"));
    ArrayList<Uri> files = new ArrayList<>();
    for (String m : NoteCodec.media(n.optString("body"))) {
      File f = store.mediaFile(m);
      if (f.isFile() && !m.endsWith(".ink.json")) files.add(FileProvider.getUriForFile(this, getPackageName() + ".files", f));
    }
    Intent i;
    if (files.isEmpty()) i = new Intent(Intent.ACTION_SEND).setType("text/plain");
    else {
      i = new Intent(files.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE).setType("*/*");
      if (files.size() == 1) i.putExtra(Intent.EXTRA_STREAM, files.get(0));
      else i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, files);
      i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    i.putExtra(Intent.EXTRA_TEXT, text);
    if (!n.optString("title").isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, n.optString("title"));
    startActivity(Intent.createChooser(i, "メモを送る"));
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    try {
      if (request == DRIVE_AUTH) {
        TokenWork w = pendingToken;
        pendingToken = null;
        if (result != RESULT_OK || w == null) {
          event("driveSetup", new JSONObject().put("reason", "Googleの許可画面が閉じられました（設定が足りない場合もこうなります）"));
          return;
        }
        AuthorizationResult r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data);
        runToken(w, r.getAccessToken());
        return;
      }
      // Camera Hatch may hand things back even when the person left without "完了".
      if (request == CAPTURE && data != null && (data.getClipData() != null || data.getData() != null)) {
        captured(data);
        return;
      }
      if (result != RESULT_OK) {
        if (request == CAMERA) store.mediaFile(pending.optString("name")).delete();
        return;
      }
      if (request == SCAN_DOC) {
        scanned(data);
        return;
      }
      if (request == CAMERA) {
        String name = pending.getString("name");
        if (!store.mediaFile(name).isFile() || store.mediaFile(name).length() == 0) throw new IOException("写真を受け取れませんでした");
        deliverAttached(pending, new JSONArray().put(new JSONObject().put("name", name).put("label", "").put("mime", "image/jpeg")));
        return;
      }
      List<Uri> uris = new ArrayList<>();
      if (data != null && data.getClipData() != null)
        for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
      else if (data != null && data.getData() != null) uris.add(data.getData());
      if (uris.isEmpty()) return;
      if (request == PICK_FILES) {
        final JSONObject p = pending;
        task("ファイルを取り込み中", () -> {
          JSONArray files = new JSONArray();
          for (Uri u : uris) files.put(ShareActivity.importFile(this, u));
          deliverAttached(p, files);
        });
      } else if (request == IMPORT_KEEP) {
        final boolean trashed = pending.optBoolean("trashed");
        task("Google Keepのデータを読み込み中", () -> {
          File dir = new File(getCacheDir(), "keep");
          dir.mkdirs();
          List<File> zips = new ArrayList<>();
          try {
            int k = 0;
            for (Uri u : uris) {
              File f = new File(dir, "takeout-" + (k++) + ".zip");
              try (InputStream in = getContentResolver().openInputStream(u); OutputStream out = new FileOutputStream(f)) {
                Store.copy(in, out, 50L * 1024 * 1024 * 1024);
              }
              zips.add(f);
            }
            KeepImport.Result r = KeepImport.run(store, zips, trashed, t -> event("busy", t));
            event("report", new JSONObject().put("title", "Google Keepからの取り込み").put("text", r.text()));
            sendNotes();
            Widgets.update(this);
            SyncJob.soon(this);
          } finally {
            for (File f : zips) f.delete();
          }
        });
      }
    } catch (Exception e) {
      error(e);
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    instance = this;
    if (ready) {
      refresh();
      if (SyncJob.any(this) && System.currentTimeMillis() - prefs().getLong("lastSync", 0) > 5 * 60 * 1000) sync(true);
    }
  }

  @Override
  protected void onStop() {
    super.onStop();
    if (web != null && ready) web.evaluateJavascript("window.flush && window.flush()", null);
    SyncJob.soon(this);
    Widgets.update(this);
  }

  @Override
  public void onBackPressed() {
    if (web != null && ready)
      web.evaluateJavascript("window.handleBack ? window.handleBack() : false", v -> {
        if (!"true".equals(v)) finish();
      });
    else super.onBackPressed();
  }

  @Override
  protected void onDestroy() {
    if (instance == this) instance = null;
    editing = "";
    io.shutdown();
    bg.shutdown();
    if (web != null) {
      web.removeJavascriptInterface("Native");
      web.destroy();
      web = null;
    }
    super.onDestroy();
  }
}
