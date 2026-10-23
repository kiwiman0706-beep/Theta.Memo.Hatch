package jp.yaegaki.theta.memo;

import android.content.*;
import android.media.AudioFormat;
import android.os.*;
import android.speech.*;
import java.io.*;
import java.util.*;

// Writes a recording out as text. "auto": the phone's own on-device recognizer when it can read
// a file (Android 13+, free, offline), otherwise Gemini with the user's key. The sound file is
// kept in the note either way. Runs its callback on the main thread when finished.
public final class Transcriber {
  public static final Set<String> busy = Collections.synchronizedSet(new HashSet<>());

  static String mode(Context c) {
    return c.getSharedPreferences("prefs", 0).getString("transcribe", "auto");
  }

  public static void run(Context c, String note, String name, File pcm, int rate, int channels, Runnable done) {
    String mode = mode(c);
    if (mode.equals("off") || note == null || note.isEmpty()) {
      done.run();
      return;
    }
    busy.add(name);
    MainActivity.post("transcribing", name);
    Runnable gemini =
        () ->
            new Thread(
                    () -> {
                      String text = null, err = null;
                      try {
                        if (Gemini.available(c)) text = new Gemini(c).transcribePcm(pcm, rate, channels);
                        else err = "書き起こしには、端末の音声認識（Android 13以降）か、設定のGemini APIキーが必要です";
                      } catch (Exception e) {
                        err = "書き起こしできませんでした：" + e.getMessage();
                      }
                      finish(c, note, name, text, err, done);
                    })
                .start();
    if (!mode.equals("gemini") && Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(c)) {
      device(c, pcm, rate, channels, (text, error) -> {
        if (text != null && !text.trim().isEmpty()) finish(c, note, name, text, null, done);
        else if (!mode.equals("device") && Gemini.available(c)) gemini.run();
        else finish(c, note, name, null, error == null ? "音声から文字を読み取れませんでした" : error, done);
      });
    } else if (mode.equals("device")) finish(c, note, name, null, "この端末では、ファイルからの音声認識を使えません（Android 13以降が必要）", done);
    else gemini.run();
  }

  static void finish(Context c, String note, String name, String text, String err, Runnable done) {
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              busy.remove(name);
              try {
                if (text != null && !text.trim().isEmpty()) Memo.transcript(c, note, name, text.trim());
                else if (err != null) MainActivity.post("toast", err);
              } catch (Exception e) {
                MainActivity.post("error", e.getMessage());
              }
              MainActivity.post("transcribing", "");
              done.run();
            });
  }

  interface Result {
    void got(String text, String error);
  }

  // Android 13+: the on-device recognizer reads raw PCM from a file descriptor, segment by
  // segment, so long recordings work too.
  static void device(Context c, File pcm, int rate, int channels, Result result) {
    if (Build.VERSION.SDK_INT < 33) {
      result.got(null, null);
      return;
    }
    final StringBuilder text = new StringBuilder();
    final boolean[] over = {false};
    final SpeechRecognizer r = SpeechRecognizer.createOnDeviceSpeechRecognizer(c);
    final ParcelFileDescriptor fd;
    try {
      fd = ParcelFileDescriptor.open(pcm, ParcelFileDescriptor.MODE_READ_ONLY);
    } catch (Exception e) {
      result.got(null, e.getMessage());
      return;
    }
    Handler h = new Handler(Looper.getMainLooper());
    Runnable end =
        () -> {
          if (over[0]) return;
          over[0] = true;
          try {
            r.destroy();
            fd.close();
          } catch (Exception ignored) {
          }
          result.got(text.toString(), null);
        };
    // Upper bound: the recognizer works faster than real time; never hang the recorder.
    long seconds = pcm.length() / (rate * channels * 2L);
    h.postDelayed(end, Math.max(60_000L, seconds * 1500L + 30_000L));
    r.setRecognitionListener(
        new RecognitionListener() {
          public void onReadyForSpeech(Bundle b) {}

          public void onBeginningOfSpeech() {}

          public void onRmsChanged(float v) {}

          public void onBufferReceived(byte[] b) {}

          public void onEndOfSpeech() {}

          public void onError(int e) {
            if (over[0]) return;
            if (e == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || e == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
              // Ask Android to fetch the Japanese model for next time.
              try {
                r.triggerModelDownload(intent(fd, rate, channels));
              } catch (Exception ignored) {
              }
            }
            if (text.length() > 0 || e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
              end.run();
              return;
            }
            over[0] = true;
            try {
              r.destroy();
              fd.close();
            } catch (Exception ignored) {
            }
            result.got(null, "端末の音声認識が使えませんでした（エラー " + e + "）");
          }

          public void onResults(Bundle b) {
            add(b);
            end.run();
          }

          public void onSegmentResults(Bundle b) {
            add(b);
          }

          public void onEndOfSegmentedSession() {
            end.run();
          }

          void add(Bundle b) {
            ArrayList<String> got = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (got != null && !got.isEmpty() && !got.get(0).trim().isEmpty()) {
              if (text.length() > 0) text.append(text.charAt(text.length() - 1) == '。' ? "\n" : " ");
              text.append(got.get(0).trim());
            }
          }

          public void onPartialResults(Bundle b) {}

          public void onEvent(int t, Bundle b) {}
        });
    r.startListening(intent(fd, rate, channels));
  }

  static Intent intent(ParcelFileDescriptor fd, int rate, int channels) {
    Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP");
    i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
    if (Build.VERSION.SDK_INT >= 33) {
      i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, fd);
      i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, channels);
      i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
      i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, rate);
      i.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
    }
    return i;
  }

  // "Write out again" for a recording already in a note (also Keep's .3gp recordings).
  public static void again(Context c, String note, String name) {
    new Thread(
            () -> {
              try {
                File src = Store.get(c).mediaFile(name);
                File dir = new File(c.getCacheDir(), "rec");
                dir.mkdirs();
                File pcm = new File(dir, name + ".again.pcm");
                int[] f = AudioCodec.decodeToPcm(src, pcm);
                new Handler(Looper.getMainLooper()).post(() -> run(c, note, name, pcm, f[0], f[1], pcm::delete));
              } catch (Exception e) {
                MainActivity.post("error", "音声を読み込めません：" + e.getMessage());
              }
            })
        .start();
  }
}
