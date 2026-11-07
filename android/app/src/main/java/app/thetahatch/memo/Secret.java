package app.thetahatch.memo;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** NAS のパスワード（とデバイストークン）を端末の鍵（Android Keystore）で暗号化して保存する。鍵は端末の外へ出ない。 */
public final class Secret {
    private static final String ALIAS = "theta-memo-nas";

    private Secret() {}

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return g.generateKey();
    }

    public static String seal(String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = c.getIV(), body = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(ByteBuffer.allocate(1 + iv.length + body.length).put((byte) iv.length).put(iv).put(body).array(), Base64.NO_WRAP);
    }

    public static String open(String sealed) {
        if (sealed == null || sealed.isEmpty()) return "";
        try {
            ByteBuffer b = ByteBuffer.wrap(Base64.decode(sealed, Base64.NO_WRAP));
            byte[] iv = new byte[b.get()];
            b.get(iv);
            byte[] body = new byte[b.remaining()];
            b.get(body);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(body), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
