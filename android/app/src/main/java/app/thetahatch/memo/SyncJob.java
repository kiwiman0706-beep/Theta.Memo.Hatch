package app.thetahatch.memo;

import android.app.job.*;
import android.content.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

// Background sync with the Theta Vault: hourly while the phone is online, and soon after the app
// is left with unsynced changes. The vault can be on the NAS (the primary copy), on Google Drive, or
// both: the NAS is synced first, then Drive, then the NAS again if Drive brought changes, so both
// end up the same. Drive uses the permission the user granted in the app; if Google asks for the
// user again, Drive waits for the next sync started from the app.
public class SyncJob extends JobService {
  static final int PERIODIC = 1, SOON = 2;
  static final ReentrantLock LOCK = new ReentrantLock();

  static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("prefs", 0);
  }

  public static boolean driveOn(Context c) {
    return prefs(c).getBoolean("syncEnabled", false);
  }

  public static boolean nasOn(Context c) {
    return prefs(c).getBoolean("nasEnabled", false);
  }

  public static boolean any(Context c) {
    return driveOn(c) || nasOn(c);
  }

  public static void schedule(Context c) {
    JobScheduler js = c.getSystemService(JobScheduler.class);
    if (!any(c)) {
      js.cancel(PERIODIC);
      return;
    }
    if (js.getPendingJob(PERIODIC) == null)
      js.schedule(new JobInfo.Builder(PERIODIC, new ComponentName(c, SyncJob.class))
          .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
          .setPeriodic(60 * 60 * 1000L)
          .setPersisted(true)
          .build());
  }

  public static void soon(Context c) {
    if (!any(c)) return;
    c.getSystemService(JobScheduler.class)
        .schedule(new JobInfo.Builder(SOON, new ComponentName(c, SyncJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(5000)
            .setOverrideDeadline(10 * 60 * 1000L)
            .build());
  }

  public static NasRemote nas(Context c) {
    String root = prefs(c).getString("nasRoot", "");
    if (root.isEmpty()) throw new IllegalArgumentException("NAS の Theta Vault の場所を設定してください");
    return new NasRemote(new Nas(c, prefs(c)), root, new java.io.File(c.getCacheDir(), "nas"));
  }

  static void add(VaultSync.Result to, VaultSync.Result r, String label) {
    to.up += r.up;
    to.down += r.down;
    to.merged += r.merged;
    to.deleted += r.deleted;
    to.mediaUp += r.mediaUp;
    to.mediaDown += r.mediaDown;
    to.conflicts += r.conflicts;
    for (String e : r.errors) to.errors.add(label + "：" + e);
  }

  static int changes(VaultSync.Result r) {
    return r.down + r.merged + r.deleted + r.conflicts;
  }

  // One run over every vault in use (token: Google Drive's, or null). Never runs twice at once.
  public static VaultSync.Result runWith(Context c, String token, VaultSync.Progress p) throws Exception {
    if (!LOCK.tryLock()) throw new IllegalStateException("同期中です");
    try {
      Store s = Store.get(c);
      List<String> all = new ArrayList<>();
      if (nasOn(c)) all.add(VaultSync.NAS);
      if (driveOn(c)) all.add(VaultSync.DRIVE);
      VaultSync.Result total = new VaultSync.Result();
      NasRemote nas = null;
      if (nasOn(c)) {
        nas = nas(c);
        try {
          add(total, VaultSync.run(s, nas, t -> p.update("NAS：" + t), VaultSync.NAS, all), "NAS");
          series(c, nas, total, "NAS");
        } catch (Exception e) {
          total.errors.add("NAS：" + e.getMessage());
          nas = null; // Drive still goes ahead
        }
      }
      if (driveOn(c)) {
        if (token == null) total.errors.add("Google ドライブ：アプリを開いて「今すぐ同期」を押してください（Google の再許可が必要です）");
        else {
          VaultRemote.OnDrive drive = new VaultRemote.OnDrive(new Drive(token));
          VaultSync.Result r = VaultSync.run(s, drive, t -> p.update("Google ドライブ：" + t), VaultSync.DRIVE, all);
          add(total, r, "Google ドライブ");
          series(c, drive, total, "Google ドライブ");
          // Changes made through Drive (Obsidian on a PC) go on to the NAS right away.
          if (nas != null && changes(r) > 0) add(total, VaultSync.run(s, nas, t -> p.update("NAS：" + t), VaultSync.NAS, all), "NAS");
        }
      }
      prefs(c).edit()
          .putLong("lastSync", System.currentTimeMillis())
          .putString("syncError", total.errors.isEmpty() ? "" : String.join("\n", total.errors))
          .putLong("syncedChange", s.changedAt)
          .apply();
      if (changes(total) > 0) {
        Widgets.update(c);
        MainActivity.post("changed", "");
      }
      return total;
    } finally {
      LOCK.unlock();
    }
  }

  static void series(Context c, VaultRemote d, VaultSync.Result total, String label) {
    try {
      Series.run(c, d);
    } catch (Exception e) {
      total.errors.add(label + "：シリーズの設定（.theta/settings.json）を同期できません（" + e.getMessage() + "）");
    }
  }

  @Override
  public boolean onStartJob(JobParameters params) {
    new Thread(
            () -> {
              boolean retry = false;
              try {
                String token = null;
                if (driveOn(this))
                  try {
                    token = DriveAuth.silentToken(this);
                  } catch (Exception e) {
                    // Drive waits for the app; the NAS still syncs
                  }
                runWith(this, token, t -> {});
              } catch (IllegalStateException busy) {
                // the app is syncing right now
              } catch (Exception e) {
                prefs(this).edit().putString("syncError", String.valueOf(e.getMessage())).apply();
                retry = params.getJobId() == SOON;
              }
              jobFinished(params, retry);
            })
        .start();
    return true;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    return true;
  }
}
