package jp.yaegaki.theta.memo;

import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.os.ParcelFileDescriptor;
import java.io.*;

// Card thumbnails for pictures, PDFs (first page) and videos. Made on each device from the
// attachment itself and kept outside the vault (thumbs/), so the vault holds only originals.
public final class Thumbs {
  static final int SIZE = 480;

  public static boolean supported(String name) {
    return name.matches("(?i).*\\.(jpe?g|png|gif|webp|heic|heif|bmp|pdf|mp4|mov|webm|3gp|mkv)$") && !name.endsWith(".ink.json");
  }

  // The thumbnail JPEG, made when missing; null when it cannot be made.
  public static synchronized File get(Store s, String name) {
    try {
      File src = s.mediaFile(name);
      if (!src.isFile() || !supported(name)) return null;
      File out = new File(s.thumbs, name + ".jpg");
      if (out.isFile() && out.lastModified() >= src.lastModified()) return out;
      Bitmap b = make(src, name.toLowerCase());
      if (b == null) return null;
      File part = new File(out.getPath() + ".part");
      try (OutputStream o = new FileOutputStream(part)) {
        b.compress(Bitmap.CompressFormat.JPEG, 82, o);
      }
      b.recycle();
      part.renameTo(out);
      return out;
    } catch (Throwable e) {
      return null;
    }
  }

  static Bitmap make(File f, String lower) throws Exception {
    if (lower.endsWith(".pdf")) return pdf(f);
    if (lower.matches(".*\\.(mp4|mov|webm|3gp|mkv)$")) {
      MediaMetadataRetriever r = new MediaMetadataRetriever();
      try {
        r.setDataSource(f.getPath());
        Bitmap b = r.getFrameAtTime(1_000_000);
        return b == null ? null : scale(b);
      } finally {
        r.release();
      }
    }
    return image(f, SIZE);
  }

  public static Bitmap image(File f, int size) throws Exception {
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(f.getPath(), o);
    if (o.outWidth <= 0) return null;
    int sample = 1;
    while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= size) sample *= 2;
    o = new BitmapFactory.Options();
    o.inSampleSize = sample;
    Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
    if (b == null) return null;
    int rot = 0;
    try {
      switch (new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
        case ExifInterface.ORIENTATION_ROTATE_90: rot = 90; break;
        case ExifInterface.ORIENTATION_ROTATE_180: rot = 180; break;
        case ExifInterface.ORIENTATION_ROTATE_270: rot = 270; break;
      }
    } catch (Exception ignored) {
    }
    if (rot != 0) {
      Matrix m = new Matrix();
      m.postRotate(rot);
      Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
      if (r != b) b.recycle();
      b = r;
    }
    return scaleTo(b, size);
  }

  static Bitmap pdf(File f) throws Exception {
    try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer r = new PdfRenderer(fd)) {
      if (r.getPageCount() == 0) return null;
      try (PdfRenderer.Page p = r.openPage(0)) {
        float k = (float) SIZE / Math.max(p.getWidth(), p.getHeight());
        Bitmap b = Bitmap.createBitmap(Math.max(1, (int) (p.getWidth() * k)), Math.max(1, (int) (p.getHeight() * k)), Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        return b;
      }
    }
  }

  static Bitmap scale(Bitmap b) {
    return scaleTo(b, SIZE);
  }

  static Bitmap scaleTo(Bitmap b, int size) {
    int m = Math.max(b.getWidth(), b.getHeight());
    if (m <= size) return b;
    float k = (float) size / m;
    Bitmap r = Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * k)), Math.max(1, (int) (b.getHeight() * k)), true);
    if (r != b) b.recycle();
    return r;
  }

  // Number of pages, shown on PDF cards.
  public static int pages(File f) {
    try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer r = new PdfRenderer(fd)) {
      return r.getPageCount();
    } catch (Exception e) {
      return 0;
    }
  }
}
