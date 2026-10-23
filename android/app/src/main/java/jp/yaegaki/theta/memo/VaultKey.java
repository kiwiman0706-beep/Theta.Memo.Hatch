package jp.yaegaki.theta.memo;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

public final class VaultKey {
  static javax.crypto.SecretKey key() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias("gemini")) {
      KeyGenerator g = KeyGenerator.getInstance("AES", "AndroidKeyStore");
      g.init(
          new KeyGenParameterSpec.Builder(
                  "gemini", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      g.generateKey();
    }
    return (javax.crypto.SecretKey) ks.getKey("gemini", null);
  }

  static void save(Context c, String value) throws Exception {
    if (value.isEmpty()) {
      c.getSharedPreferences("prefs", 0).edit().remove("key").apply();
      return;
    }
    Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
    ci.init(Cipher.ENCRYPT_MODE, key());
    String s =
        Base64.encodeToString(ci.getIV(), 2)
            + ":"
            + Base64.encodeToString(ci.doFinal(value.getBytes("UTF-8")), 2);
    if (!c.getSharedPreferences("prefs", 0).edit().putString("key", s).commit())
      throw new Exception("APIキーを保存できませんでした");
  }

  static String get(Context c) throws Exception {
    String s = c.getSharedPreferences("prefs", 0).getString("key", "");
    if (s.isEmpty()) return "";
    String[] p = s.split(":");
    Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
    ci.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(p[0], 2)));
    return new String(ci.doFinal(Base64.decode(p[1], 2)), "UTF-8");
  }
}
