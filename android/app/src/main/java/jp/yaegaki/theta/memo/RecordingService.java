package jp.yaegaki.theta.memo;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.*;
import java.io.*;
import org.json.*;

// Voice memo: records in the foreground (keeps going with the screen off), keeps the sound as
// .m4a in the note and then writes it out as text (Transcriber). Captured as 16 kHz mono PCM so
// the phone's own speech recognizer can read the same recording afterwards.
public class RecordingService extends Service {
  public static final String STOP = "jp.yaegaki.theta.memo.STOP_RECORDING";
  static final int RATE = 16000;
  public static volatile String activeNote = "";
  public static volatile long startTime = 0;
  public static volatile int level = 0; // 0..100, for the meter on screen
  volatile boolean running;
  Thread worker;
  File pcm;
  String name;

  public static void start(Context c, String noteId) {
    Intent i = new Intent(c, RecordingService.class).putExtra("note", noteId);
    if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
    else c.startService(i);
  }

  public static void stop(Context c) {
    c.startService(new Intent(c, RecordingService.class).setAction(STOP));
  }

  static Notification notice(Context c, String text, boolean stoppable) {
    NotificationManager nm = c.getSystemService(NotificationManager.class);
    nm.createNotificationChannel(new NotificationChannel("rec", "録音", NotificationManager.IMPORTANCE_LOW));
    Notification.Builder b =
        new Notification.Builder(c, "rec")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Theta Memo Hatch")
            .setContentText(text)
            .setOngoing(stoppable)
            .setContentIntent(PendingIntent.getActivity(c, 50, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
    if (stoppable)
      b.addAction(new Notification.Action.Builder(null, "停止",
          PendingIntent.getService(c, 51, new Intent(c, RecordingService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)).build());
    return b.build();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && STOP.equals(intent.getAction())) {
      finish();
      return START_NOT_STICKY;
    }
    if (running) return START_NOT_STICKY;
    String note = intent == null ? "" : intent.getStringExtra("note");
    Notification n = notice(this, "録音中…", true);
    if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
    else startForeground(7, n);
    try {
      begin(note == null ? "" : note);
    } catch (Exception e) {
      MainActivity.post("error", "録音を開始できません：" + e.getMessage());
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  void begin(String note) throws Exception {
    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
      throw new IOException("マイクの使用が許可されていません");
    int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, 32000));
    if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("マイクを使えません");
    File dir = new File(getCacheDir(), "rec");
    dir.mkdirs();
    name = Store.newName(".m4a");
    pcm = new File(dir, name + ".pcm");
    activeNote = note;
    startTime = System.currentTimeMillis();
    running = true;
    rec.startRecording();
    worker =
        new Thread(
            () -> {
              byte[] buf = new byte[3200];
              try (OutputStream out = new BufferedOutputStream(new FileOutputStream(pcm))) {
                while (running) {
                  int n = rec.read(buf, 0, buf.length);
                  if (n <= 0) continue;
                  out.write(buf, 0, n);
                  int peak = 0;
                  for (int i = 0; i + 1 < n; i += 2) peak = Math.max(peak, Math.abs((short) ((buf[i] & 0xff) | (buf[i + 1] << 8))));
                  level = Math.min(100, peak * 100 / 20000);
                }
              } catch (Exception e) {
                MainActivity.post("error", "録音を保存できません：" + e.getMessage());
              } finally {
                rec.stop();
                rec.release();
              }
            });
    worker.start();
    MainActivity.post("recording", new JSONObject().put("note", note).put("start", startTime));
  }

  void finish() {
    if (!running) {
      stopSelf();
      return;
    }
    running = false;
    final String note = activeNote, file = name;
    final long dur = System.currentTimeMillis() - startTime;
    final File raw = pcm;
    activeNote = "";
    startTime = 0;
    level = 0;
    getSystemService(NotificationManager.class).notify(7, notice(this, "録音を保存中…", false));
    new Thread(
            () -> {
              try {
                worker.join(3000);
                Store s = Store.get(this);
                AudioCodec.encodeM4a(raw, RATE, 1, s.mediaFile(file));
                String target = Memo.recorded(this, note, file, dur);
                new Handler(Looper.getMainLooper())
                    .post(() -> Transcriber.run(this, target, file, raw, RATE, 1, () -> {
                      raw.delete();
                      stopForeground(true);
                      stopSelf();
                    }));
              } catch (Exception e) {
                MainActivity.post("error", "録音を保存できません：" + e.getMessage());
                stopSelf();
              }
            })
        .start();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
