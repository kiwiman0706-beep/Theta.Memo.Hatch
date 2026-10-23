package jp.yaegaki.theta.memo;

import android.app.job.*;
import android.content.*;
import java.util.concurrent.locks.ReentrantLock;

// Background sync with the Theta Vault: hourly while the phone is online, and soon after the app
// is left with unsynced changes. Uses the Drive permission the user granted in the app; if Google
// asks for the user again, it waits for the next sync started from the app.
public class SyncJob extends JobService {
  static final int PERIODIC = 1, SOON = 2;
  static final ReentrantLock LOCK = new ReentrantLock();

  public static void schedule(Context c) {
    JobScheduler js = c.getSystemService(JobScheduler.class);
    if (!c.getSharedPreferences("prefs", 0).getBoolean("syncEnabled", false)) {
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
    if (!c.getSharedPreferences("prefs", 0).getBoolean("syncEnabled", false)) return;
    c.getSystemService(JobScheduler.class)
        .schedule(new JobInfo.Builder(SOON, new ComponentName(c, SyncJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(5000)
            .setOverrideDeadline(10 * 60 * 1000L)
            .build());
  }

  // One sync run with a token; returns the result text. Never runs twice at once.
  public static VaultSync.Result runWith(Context c, String token, VaultSync.Progress p) throws Exception {
    if (!LOCK.tryLock()) throw new IllegalStateException("同期中です");
    try {
      Store s = Store.get(c);
      VaultSync.Result r = VaultSync.run(s, new VaultRemote.OnDrive(new Drive(token)), p);
      c.getSharedPreferences("prefs", 0).edit()
          .putLong("lastSync", System.currentTimeMillis())
          .putString("syncError", r.errors.isEmpty() ? "" : String.join("\n", r.errors))
          .putLong("syncedChange", s.changedAt)
          .apply();
      if (r.down + r.merged + r.deleted + r.conflicts > 0) {
        Widgets.update(c);
        MainActivity.post("changed", "");
      }
      return r;
    } finally {
      LOCK.unlock();
    }
  }

  @Override
  public boolean onStartJob(JobParameters params) {
    new Thread(
            () -> {
              boolean retry = false;
              try {
                runWith(this, DriveAuth.silentToken(this), t -> {});
              } catch (IllegalStateException busy) {
                // the app is syncing right now
              } catch (Exception e) {
                getSharedPreferences("prefs", 0).edit().putString("syncError", String.valueOf(e.getMessage())).apply();
                retry = params.getJobId() == SOON && !String.valueOf(e.getMessage()).contains("再許可");
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
