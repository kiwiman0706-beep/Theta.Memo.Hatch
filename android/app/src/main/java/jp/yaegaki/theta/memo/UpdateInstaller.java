package jp.yaegaki.theta.memo;

import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import org.json.JSONObject;

// Installs a new version from GitHub Releases inside the app (UpdateCheck finds it).
// The APK is downloaded in the background (Wi-Fi only unless asked), checked against the SHA-256
// GitHub publishes, and handed to Android's PackageInstaller; Android itself refuses an APK that
// is not signed with this app's key. The first update needs the user's OK; from Android 12 on,
// once this app is the installer of its own package, later updates install without asking while
// the app is in the background.
public final class UpdateInstaller {
  static final String PREFIX = "https://github.com/kiwiman0706-beep/Theta.Memo.Hatch/releases/download/";

  static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("prefs", 0);
  }

  static File dir(Context c) {
    File d = new File(c.getFilesDir(), "updates");
    d.mkdirs();
    return d;
  }

  // The downloaded APK for this version, or null.
  public static File ready(Context c, String version) {
    if (!version.equals(prefs(c).getString("updateFileVersion", ""))) return null;
    File f = new File(dir(c), "theta-memo-hatch-" + version + ".apk");
    return f.isFile() && f.length() > 0 ? f : null;
  }

  public static boolean unmetered(Context c) {
    ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
    return cm != null && cm.getActiveNetwork() != null && !cm.isActiveNetworkMetered();
  }

  // Downloads (once) the APK described by UpdateCheck.pending(); returns the file.
  public static synchronized File download(Context c, JSONObject u) throws Exception {
    String version = u.getString("version"), url = u.optString("url");
    File done = ready(c, version);
    if (done != null) return done;
    if (!url.startsWith(PREFIX)) throw new IOException("更新ファイルの場所が正しくありません");
    for (File old : dir(c).listFiles()) old.delete(); // one version at a time
    File part = new File(dir(c), "download.part");
    MessageDigest sha = MessageDigest.getInstance("SHA-256");
    HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
    con.setConnectTimeout(20000);
    con.setReadTimeout(60000);
    con.setInstanceFollowRedirects(true);
    if (con.getResponseCode() != 200) throw new IOException("更新ファイルをダウンロードできません（HTTP " + con.getResponseCode() + "）");
    long size = 0;
    try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(part)) {
      byte[] buf = new byte[65536];
      for (int n; (n = in.read(buf)) > 0; ) {
        size += n;
        if (size > 200L * 1024 * 1024) throw new IOException("更新ファイルが大きすぎます");
        sha.update(buf, 0, n);
        out.write(buf, 0, n);
      }
    }
    String digest = u.optString("sha256");
    if (!digest.isEmpty() && !digest.equalsIgnoreCase(hex(sha.digest()))) {
      part.delete();
      throw new IOException("更新ファイルが壊れています（チェックサム不一致）");
    }
    File apk = new File(dir(c), "theta-memo-hatch-" + version + ".apk");
    if (!part.renameTo(apk)) throw new IOException("更新ファイルを保存できません");
    prefs(c).edit().putString("updateFileVersion", version).apply();
    return apk;
  }

  static String hex(byte[] b) {
    StringBuilder s = new StringBuilder();
    for (byte x : b) s.append(String.format("%02x", x));
    return s.toString();
  }

  // This app installed itself last time: Android 12+ lets it update without asking.
  public static boolean silent(Context c) {
    if (Build.VERSION.SDK_INT < 31) return false;
    try {
      String installer = c.getPackageManager().getInstallSourceInfo(c.getPackageName()).getInstallingPackageName();
      return c.getPackageName().equals(installer) && c.getPackageManager().canRequestPackageInstalls();
    } catch (Exception e) {
      return false;
    }
  }

  public static boolean allowed(Context c) {
    return c.getPackageManager().canRequestPackageInstalls();
  }

  public static void install(Context c, File apk) throws Exception {
    PackageInstaller pi = c.getPackageManager().getPackageInstaller();
    PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
    params.setAppPackageName(c.getPackageName());
    if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
    int id = pi.createSession(params);
    try (PackageInstaller.Session s = pi.openSession(id)) {
      try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("app.apk", 0, apk.length())) {
        byte[] buf = new byte[65536];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        s.fsync(out);
      }
      Intent result = new Intent(c, Result.class).setPackage(c.getPackageName());
      int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
      s.commit(PendingIntent.getBroadcast(c, 31, result, flags).getIntentSender());
    }
  }

  // Android reports back here: ask the user when it needs to, or remember why it failed.
  public static class Result extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
      int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
      if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
        Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
        if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      } else if (status != PackageInstaller.STATUS_SUCCESS) {
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        prefs(c).edit().putString("updateError", status == PackageInstaller.STATUS_FAILURE_ABORTED ? "" : "更新できませんでした" + (msg == null ? "" : "：" + msg)).apply();
      }
    }
  }

  static boolean installed(Context c, String version) {
    try {
      return UpdateCheck.compare(c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName, version) >= 0;
    } catch (PackageManager.NameNotFoundException e) {
      return false;
    }
  }
}
