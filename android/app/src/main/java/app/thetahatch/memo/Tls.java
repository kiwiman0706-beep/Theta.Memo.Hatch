package app.thetahatch.memo;

import java.net.URL;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * NAS の自己署名証明書を、利用者が確かめた 1 枚だけ受け入れる（初回に信頼する方式）。
 * ほかの証明書は一切受け入れない。端末が普通に信頼する証明書なら、この仕組みは使わない。
 */
final class Tls {
    private Tls() {}

    static String fingerprint(X509Certificate c) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            if (i > 0) b.append(':');
            b.append(String.format(Locale.ROOT, "%02X", d[i]));
        }
        return b.toString();
    }

    /** 指紋が一致する証明書だけを受け入れる。名前（ホスト名）は証明書の一致で代える（IP や ts.net でつなぐため）。 */
    static void pin(HttpsURLConnection c, String pin) {
        try {
            X509TrustManager tm = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    throw new CertificateException("client");
                }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    try {
                        if (chain == null || chain.length == 0 || !fingerprint(chain[0]).equals(pin)) throw new CertificateException("証明書が変わりました");
                    } catch (CertificateException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new CertificateException(e);
                    }
                }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, null);
            c.setSSLSocketFactory(ctx.getSocketFactory());
            c.setHostnameVerifier((host, session) -> {
                try {
                    return fingerprint((X509Certificate) session.getPeerCertificates()[0]).equals(pin);
                } catch (Exception e) {
                    return false;
                }
            });
        } catch (Exception ignored) {
        }
    }

    /** 相手の証明書の指紋だけを取り出す。取り出したら必ず接続を断つ（何も送らない）。 */
    static String probe(String url, int timeout) {
        final String[] seen = new String[1];
        try {
            X509TrustManager tm = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    throw new CertificateException("client");
                }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    try {
                        if (chain != null && chain.length > 0) seen[0] = fingerprint(chain[0]);
                    } catch (Exception ignored) {
                    }
                    throw new CertificateException("probe");
                }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, null);
            HttpsURLConnection c = (HttpsURLConnection) new URL(url).openConnection();
            c.setSSLSocketFactory(ctx.getSocketFactory());
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            try {
                c.connect();
            } finally {
                c.disconnect();
            }
        } catch (Exception ignored) {
        }
        return seen[0];
    }
}
