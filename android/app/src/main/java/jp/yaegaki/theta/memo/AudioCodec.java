package jp.yaegaki.theta.memo;

import android.media.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// Raw PCM ⇄ files. Recordings are captured as 16-bit PCM (so the speech recognizer can read them)
// and kept in the vault as AAC .m4a (small, plays everywhere, Obsidian embeds it).
public final class AudioCodec {
  public static byte[] wav(byte[] pcm, int len, int rate, int channels) {
    ByteBuffer b = ByteBuffer.allocate(44 + len).order(ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes()).putInt(36 + len).put("WAVE".getBytes());
    b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels).putInt(rate)
        .putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
    b.put("data".getBytes()).putInt(len).put(pcm, 0, len);
    return b.array();
  }

  public static void encodeM4a(File pcm, int rate, int channels, File out) throws Exception {
    MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels);
    f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
    f.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
    f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
    MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
    codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    codec.start();
    MediaMuxer mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    int track = -1;
    boolean inputDone = false, started = false;
    long totalBytes = 0;
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    try (InputStream in = new BufferedInputStream(new FileInputStream(pcm))) {
      byte[] buf = new byte[8192];
      while (true) {
        if (!inputDone) {
          int i = codec.dequeueInputBuffer(10000);
          if (i >= 0) {
            ByteBuffer ib = codec.getInputBuffer(i);
            ib.clear();
            int n = in.read(buf, 0, Math.min(buf.length, ib.remaining()));
            long pts = totalBytes * 1_000_000L / (rate * channels * 2L);
            if (n <= 0) {
              codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              inputDone = true;
            } else {
              ib.put(buf, 0, n);
              totalBytes += n;
              codec.queueInputBuffer(i, 0, n, pts, 0);
            }
          }
        }
        int o = codec.dequeueOutputBuffer(info, 10000);
        if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          track = mux.addTrack(codec.getOutputFormat());
          mux.start();
          started = true;
        } else if (o >= 0) {
          ByteBuffer ob = codec.getOutputBuffer(o);
          if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
          if (info.size > 0 && started) {
            ob.position(info.offset);
            ob.limit(info.offset + info.size);
            mux.writeSampleData(track, ob, info);
          }
          codec.releaseOutputBuffer(o, false);
          if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
        }
      }
    } finally {
      codec.stop();
      codec.release();
      if (started) mux.stop();
      mux.release();
    }
  }

  // Any audio Android can decode (m4a, 3gp/AMR from Keep, mp3…) as 16-bit PCM.
  // Returns {sampleRate, channels}.
  public static int[] decodeToPcm(File src, File pcm) throws Exception {
    MediaExtractor ex = new MediaExtractor();
    ex.setDataSource(src.getPath());
    int track = -1;
    MediaFormat format = null;
    for (int i = 0; i < ex.getTrackCount(); i++) {
      MediaFormat f = ex.getTrackFormat(i);
      if (String.valueOf(f.getString(MediaFormat.KEY_MIME)).startsWith("audio/")) {
        track = i;
        format = f;
        break;
      }
    }
    if (track < 0) throw new IOException("音声が見つかりません");
    ex.selectTrack(track);
    MediaCodec codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
    codec.configure(format, null, null, 0);
    codec.start();
    int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    boolean inputDone = false;
    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(pcm))) {
      byte[] tmp = new byte[0];
      while (true) {
        if (!inputDone) {
          int i = codec.dequeueInputBuffer(10000);
          if (i >= 0) {
            int n = ex.readSampleData(codec.getInputBuffer(i), 0);
            if (n < 0) {
              codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              inputDone = true;
            } else {
              codec.queueInputBuffer(i, 0, n, ex.getSampleTime(), 0);
              ex.advance();
            }
          }
        }
        int o = codec.dequeueOutputBuffer(info, 10000);
        if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat of = codec.getOutputFormat();
          rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
          channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        } else if (o >= 0) {
          ByteBuffer ob = codec.getOutputBuffer(o);
          if (info.size > 0) {
            if (tmp.length < info.size) tmp = new byte[info.size];
            ob.position(info.offset);
            ob.get(tmp, 0, info.size);
            out.write(tmp, 0, info.size);
          }
          codec.releaseOutputBuffer(o, false);
          if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
        }
      }
    } finally {
      codec.stop();
      codec.release();
      ex.release();
    }
    return new int[] {rate, channels};
  }
}
