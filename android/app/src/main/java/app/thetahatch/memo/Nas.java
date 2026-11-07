package app.thetahatch.memo;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Theta Home Hatch の Nas.java を写し、Vault の同期に要る書き込み（アップロード・名前の変更・移動・フォルダ作成・情報）を足したもの。
 * Synology NAS（DSM File Station API）への接続と、ネットワーク・Tailscale・NAS の状態。DSBooks と同じ手順：
 * entry.cgi で使える API を問い合わせ → SYNO.API.Auth でログイン → SYNO.FileStation.List / Download / Thumb。
 * 2 段階認証は初回だけコードを入れ、以後はデバイストークン（did）で通す。
 * https の自己署名証明書は、初回に指紋（SHA-256）を見せて信頼してもらい、以後はその証明書だけを受け入れる。
 * Tailscale は外部アプリとして扱い、勝手にオンにはしない。VPN が無いだけで Tailscale がオフとは断定しない。
 */
public final class Nas {
    public static final String TAILSCALE = "com.tailscale.ipn";
    public static final int UNSET = 0, CHECKING = 1, OK = 2, UNREACHABLE = 3, AUTH = 4, NEED_OTP = 5, CERT = 6;

    public static final class Status {
        public boolean network, vpn, tailscale;
        public int nas = UNSET;
        public String message = "";
        /** CERT のとき、信頼するか尋ねる証明書の指紋。 */
        public String cert;
        public long checkedAt;
    }

    public static final class Item {
        public String name, path;
        public boolean dir;
        public long size, modified;
    }

    public static class AuthException extends IOException {
        AuthException(String m) { super(m); }
    }

    public static final class OtpException extends AuthException {
        OtpException(String m) { super(m); }
    }

    /** 端末が信頼していない証明書。fingerprint を見せて、信頼するか尋ねる。 */
    public static final class CertException extends IOException {
        public final String fingerprint;
        CertException(String fingerprint) {
            super("信頼されていない証明書です");
            this.fingerprint = fingerprint;
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private JSONObject apis;
    private String sid;
    /** 次のログインで 1 回だけ使う 2 段階認証のコード。 */
    private String otp;

    public Nas(Context context, SharedPreferences prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
    }

    public String base() { return prefs.getString("nasUrl", ""); }
    public String account() { return prefs.getString("nasAccount", ""); }
    public boolean configured() { return !base().isEmpty() && !account().isEmpty(); }

    /**
     * 入力を URL に整える。「100.1.2.3」のように方式を省いたときは DSM の既定のポート（http 5000）を補う。
     * 方式を書いたときはそのまま使う（リバースプロキシで 443 にしている場合など）。
     */
    public static String normalize(String url) {
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.isEmpty()) return u;
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            String host = u.contains("/") ? u.substring(0, u.indexOf('/')) : u;
            u = "http://" + u;
            if (!host.contains(":")) u = "http://" + host + ":5000" + u.substring(("http://" + host).length());
        }
        return u;
    }

    public void save(String url, String account, String password) throws Exception {
        String u = normalize(url);
        SharedPreferences.Editor e = prefs.edit().putString("nasUrl", u).putString("nasAccount", account.trim());
        if (!password.isEmpty()) e.putString("nasPassword", Secret.seal(password));
        // 接続先かアカウントが変わったら、デバイストークンと証明書の信頼はやり直す
        boolean moved = !u.equals(base()) || !account.trim().equals(account());
        if (moved) e.remove("nasDid").remove("nasCertPin");
        e.apply();
        if (moved) { Series.changed(context, "nas.url"); Series.changed(context, "nas.user"); }
        synchronized (this) { sid = null; apis = null; }
    }

    /** 2 段階認証のコード。次のログインで 1 回だけ使う。 */
    public synchronized void setOtp(String code) {
        otp = code == null || code.trim().isEmpty() ? null : code.trim();
        if (otp != null) sid = null;
    }

    /** 見せた指紋の証明書を信頼する。 */
    public synchronized void trustCert(String fingerprint) {
        prefs.edit().putString("nasCertPin", fingerprint).apply();
        sid = null;
        apis = null;
    }

    public String trustedCert() { return prefs.getString("nasCertPin", ""); }

    /** 端末のネットワークと VPN（Tailscale はここに出る）の有無。 */
    public static boolean[] network(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        boolean connected = false, vpn = false;
        if (cm != null) {
            NetworkCapabilities active = cm.getNetworkCapabilities(cm.getActiveNetwork());
            connected = active != null && active.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true;
            }
        }
        return new boolean[]{connected, vpn};
    }

    public static boolean tailscaleInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(TAILSCALE, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 状態を調べる。通信するのでメインスレッドでは呼ばない。 */
    public Status check() {
        Status s = new Status();
        boolean[] net = network(context);
        s.network = net[0];
        s.vpn = net[1];
        s.tailscale = tailscaleInstalled(context);
        s.checkedAt = System.currentTimeMillis();
        if (!configured()) {
            s.nas = UNSET;
            s.message = "NAS は未登録です";
            return s;
        }
        try {
            ensureLogin();
            call("SYNO.FileStation.List", "list_share", map("offset", "0", "limit", "1"));
            s.nas = OK;
            s.message = "NAS に接続しています";
        } catch (OtpException e) {
            s.nas = NEED_OTP;
            s.message = e.getMessage();
        } catch (CertException e) {
            s.nas = CERT;
            s.cert = e.fingerprint;
            s.message = "NAS の証明書を端末が信頼していません（自己署名など）。指紋を確かめて信頼すると、つながります。";
        } catch (AuthException e) {
            s.nas = AUTH;
            s.message = "NAS にログインできません（" + e.getMessage() + "）。設定でアカウントを確認してください。";
        } catch (Exception e) {
            s.nas = UNREACHABLE;
            s.message = !s.network ? "ネットワークに接続できません。"
                    : !s.vpn ? "NAS に届きません。Tailscale がオフならオンにしてください。"
                    : "NAS に届きません。Tailscale・NAS の電源・接続先を確認してください。";
        }
        return s;
    }

    /** フォルダの中身。path が空なら共有フォルダの一覧。 */
    public ArrayList<Item> list(String path) throws Exception {
        ensureLogin();
        ArrayList<Item> out = new ArrayList<>();
        boolean root = path == null || path.isEmpty();
        for (int offset = 0; offset < 5000; offset += 500) {
            JSONObject data = root
                    ? call("SYNO.FileStation.List", "list_share", map("offset", "" + offset, "limit", "500", "additional", "[\"time\"]"))
                    : call("SYNO.FileStation.List", "list", map("folder_path", path, "offset", "" + offset, "limit", "500", "additional", "[\"size\",\"time\"]"));
            JSONArray items = data.optJSONArray(root ? "shares" : "files");
            if (items == null) break;
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                Item it = new Item();
                it.name = o.optString("name");
                it.path = o.optString("path");
                it.dir = root || o.optBoolean("isdir");
                JSONObject add = o.optJSONObject("additional");
                if (add != null) {
                    it.size = add.optLong("size");
                    JSONObject time = add.optJSONObject("time");
                    if (time != null) it.modified = time.optLong("mtime") * 1000;
                }
                if (!it.name.startsWith(".") && !it.name.startsWith("#") && !it.name.equals("@eaDir")) out.add(it);
            }
            if (items.length() < 500) break;
        }
        out.sort((a, b) -> a.dir != b.dir ? (a.dir ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /**
     * ファイルを探す（SYNO.FileStation.Search）。folders が空なら全部の共有フォルダ。サブフォルダも含む。
     * pattern は名前の一部、mtimeFrom は更新日時の下限（秒、0 なら無し）。最大 limit 件・30 秒まで待つ。
     */
    public ArrayList<Item> search(java.util.List<String> folders, String pattern, long mtimeFrom, int limit) throws Exception {
        ensureLogin();
        java.util.List<String> roots = new ArrayList<>(folders);
        if (roots.isEmpty()) for (Item s : list("")) roots.add(s.path);
        JSONArray paths = new JSONArray();
        for (String r : roots) paths.put(r);
        Map<String, String> p = map("folder_path", paths.toString(), "recursive", "true", "filetype", "file");
        if (pattern != null && !pattern.trim().isEmpty()) p.put("pattern", "*" + pattern.trim() + "*");
        if (mtimeFrom > 0) p.put("mtime_from", "" + mtimeFrom);
        String task = call("SYNO.FileStation.Search", "start", p).optString("taskid");
        if (task.isEmpty()) throw new IOException("検索を始められませんでした");
        String quoted = JSONObject.quote(task);
        ArrayList<Item> out = new ArrayList<>();
        try {
            long until = System.currentTimeMillis() + 30000;
            while (true) {
                JSONObject data = call("SYNO.FileStation.Search", "list", map("taskid", quoted, "offset", "0", "limit", "" + limit,
                        "additional", "[\"size\",\"time\"]", "sort_by", "mtime", "sort_direction", "desc"));
                boolean finished = data.optBoolean("finished", true);
                if (finished || System.currentTimeMillis() > until) {
                    JSONArray items = data.optJSONArray("files");
                    if (items != null) for (int i = 0; i < items.length(); i++) {
                        JSONObject o = items.getJSONObject(i);
                        if (o.optBoolean("isdir")) continue;
                        Item it = new Item();
                        it.name = o.optString("name");
                        it.path = o.optString("path");
                        JSONObject add = o.optJSONObject("additional");
                        if (add != null) {
                            it.size = add.optLong("size");
                            JSONObject time = add.optJSONObject("time");
                            if (time != null) it.modified = time.optLong("mtime") * 1000;
                        }
                        if (!it.path.contains("/@eaDir/") && !it.path.contains("/#recycle/") && !it.name.startsWith(".")) out.add(it);
                    }
                    break;
                }
                Thread.sleep(700);
            }
        } finally {
            try {
                call("SYNO.FileStation.Search", "stop", map("taskid", quoted));
                call("SYNO.FileStation.Search", "clean", map("taskid", quoted));
            } catch (Exception ignored) {
            }
        }
        out.sort((a, b) -> Long.compare(b.modified, a.modified));
        return out;
    }

    /** NAS のファイルを端末に取得する。 */
    public void download(String path, File dest) throws Exception {
        ensureLogin();
        JSONObject api = api("SYNO.FileStation.Download");
        String url = base() + "/webapi/" + api.optString("path", "entry.cgi");
        String body = form(map("api", "SYNO.FileStation.Download", "version", "" + Math.min(2, api.optInt("maxVersion", 2)),
                "method", "download", "path", new JSONArray().put(path).toString(), "mode", "download", "_sid", sid));
        HttpURLConnection c = post(url, body, 60000);
        String type = c.getContentType();
        if (type != null && type.startsWith("application/json")) {
            JSONObject r = new JSONObject(read(c.getInputStream()));
            throw new IOException("取得できませんでした（" + r.optJSONObject("error") + "）");
        }
        File part = new File(dest.getPath() + ".part");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        if (!part.renameTo(dest)) throw new IOException("保存できませんでした");
    }

    /** NAS が作るサムネイル（SYNO.FileStation.Thumb）。写真・動画のみ。作れないときは null。 */
    public byte[] thumb(String path, boolean large) throws Exception {
        ensureLogin();
        JSONObject api = api("SYNO.FileStation.Thumb");
        String url = base() + "/webapi/" + api.optString("path", "entry.cgi");
        String body = form(map("api", "SYNO.FileStation.Thumb", "version", "" + Math.min(2, api.optInt("maxVersion", 2)),
                "method", "get", "path", path, "size", large ? "medium" : "small", "rotate", "1", "_sid", sid));
        HttpURLConnection c = post(url, body, 15000);
        String type = c.getContentType();
        if (type == null || !type.startsWith("image/")) return null;
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    // ---- 書き込み（Theta Vault の同期用） ----------------------------------------------------------

    /** ファイル・フォルダの情報。無ければ null。 */
    public Item info(String path) throws Exception {
        ensureLogin();
        JSONObject data;
        try {
            data = call("SYNO.FileStation.List", "getinfo", map("path", new JSONArray().put(path).toString(), "additional", "[\"size\",\"time\"]"));
        } catch (IOException e) {
            if (String.valueOf(e.getMessage()).matches(".*エラー (408|418)$")) return null; // 無い・パスが不正
            throw e;
        }
        JSONArray files = data.optJSONArray("files");
        if (files == null || files.length() == 0) return null;
        JSONObject o = files.getJSONObject(0);
        if (o.has("code") || o.optString("name").isEmpty()) return null; // 408：無い
        Item it = new Item();
        it.name = o.optString("name");
        it.path = o.optString("path");
        it.dir = o.optBoolean("isdir");
        JSONObject add = o.optJSONObject("additional");
        if (add != null) {
            it.size = add.optLong("size");
            JSONObject time = add.optJSONObject("time");
            if (time != null) it.modified = time.optLong("mtime") * 1000;
        }
        return it;
    }

    /** フォルダを作る（親もまとめて。既にあればそのまま）。 */
    public void mkdir(String parent, String name) throws Exception {
        ensureLogin();
        call("SYNO.FileStation.CreateFolder", "create", map("folder_path", new JSONArray().put(parent).toString(),
                "name", new JSONArray().put(name).toString(), "force_parent", "true"));
    }

    /** dir に name で書き込む（同じ名前は上書き）。 */
    public void upload(String dir, String name, File file) throws Exception {
        ensureLogin();
        JSONObject api = api("SYNO.FileStation.Upload");
        String url = base() + "/webapi/" + api.optString("path", "entry.cgi") + "?api=SYNO.FileStation.Upload&version="
                + Math.min(2, api.optInt("maxVersion", 2)) + "&method=upload&_sid=" + URLEncoder.encode(sid, "UTF-8");
        String boundary = "theta" + java.util.UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        for (String[] f : new String[][]{{"path", dir}, {"create_parents", "true"}, {"overwrite", "true"}})
            head.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f[0] + "\"\r\n\r\n" + f[1] + "\r\n").getBytes(StandardCharsets.UTF_8));
        head.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name.replace("\"", "") + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        String pin = trustedCert();
        if (c instanceof javax.net.ssl.HttpsURLConnection && !pin.isEmpty()) Tls.pin((javax.net.ssl.HttpsURLConnection) c, pin);
        c.setConnectTimeout(20000);
        c.setReadTimeout(300000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.setFixedLengthStreamingMode(head.size() + file.length() + tail.length);
        try (OutputStream o = c.getOutputStream(); InputStream in = new java.io.FileInputStream(file)) {
            o.write(head.toByteArray());
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            o.write(tail);
        }
        if (c.getResponseCode() / 100 != 2) throw new IOException("NAS へ書き込めません（HTTP " + c.getResponseCode() + "）");
        JSONObject r = new JSONObject(read(c.getInputStream()));
        if (!r.optBoolean("success")) throw new IOException("NAS へ書き込めません（DSM エラー " + (r.optJSONObject("error") == null ? 0 : r.getJSONObject("error").optInt("code")) + "）");
    }

    public void rename(String path, String name) throws Exception {
        ensureLogin();
        call("SYNO.FileStation.Rename", "rename", map("path", new JSONArray().put(path).toString(), "name", new JSONArray().put(name).toString()));
    }

    /** dest のフォルダへ移す（同じ名前があれば上書き）。終わるまで待つ。 */
    public void move(String path, String dest) throws Exception {
        ensureLogin();
        String task = call("SYNO.FileStation.CopyMove", "start", map("path", new JSONArray().put(path).toString(),
                "dest_folder_path", dest, "overwrite", "true", "remove_src", "true")).optString("taskid");
        if (task.isEmpty()) throw new IOException("移動を始められませんでした");
        long until = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < until) {
            JSONObject st = call("SYNO.FileStation.CopyMove", "status", map("taskid", JSONObject.quote(task)));
            if (st.optBoolean("finished")) return;
            Thread.sleep(400);
        }
        throw new IOException("移動が終わりませんでした");
    }

    private synchronized void ensureLogin() throws Exception {
        if (sid != null) return;
        String base = base();
        if (base.isEmpty()) throw new IOException("NAS は未登録です");
        JSONObject r = new JSONObject(read(post(base + "/webapi/entry.cgi",
                form(map("api", "SYNO.API.Info", "version", "1", "method", "query", "query", "all")), 8000).getInputStream()));
        apis = r.optJSONObject("data");
        if (apis == null || !apis.has("SYNO.API.Auth")) throw new IOException("DSM の応答ではありません");
        String password = Secret.open(prefs.getString("nasPassword", ""));
        if (password.isEmpty()) throw new AuthException("パスワード未設定");
        JSONObject auth = apis.getJSONObject("SYNO.API.Auth");
        String did = prefs.getString("nasDid", "");
        Map<String, String> p = map("api", "SYNO.API.Auth", "method", "login",
                "account", account(), "passwd", password, "session", "FileStation", "format", "sid");
        // 2 段階認証（コード・デバイストークン）は v6 から
        boolean twoStep = otp != null || !did.isEmpty();
        p.put("version", "" + Math.min(twoStep ? 6 : 3, auth.optInt("maxVersion", 3)));
        if (otp != null) {
            p.put("otp_code", otp);
            p.put("enable_device_token", "yes");
            p.put("device_name", "ThetaMemoHatch");
        } else if (!did.isEmpty()) {
            p.put("device_id", did);
            p.put("device_name", "ThetaMemoHatch");
        }
        otp = null;
        JSONObject login = new JSONObject(read(post(base + "/webapi/" + auth.optString("path", "entry.cgi"), form(p), 10000).getInputStream()));
        if (!login.optBoolean("success")) {
            int code = login.optJSONObject("error") == null ? 0 : login.getJSONObject("error").optInt("code");
            if (code == 403 || code == 406) {
                prefs.edit().remove("nasDid").apply();
                throw new OtpException("2 段階認証のコードを入れてください（NAS の設定から。1 回入れれば、この端末は次から要りません）");
            }
            if (code == 404) throw new OtpException("2 段階認証のコードが違います。新しいコードを入れてください");
            throw new AuthException(code == 400 ? "アカウントかパスワードが違います" : code == 407 ? "ログインの失敗が続いたため、NAS が一時的に拒否しています" : "エラー " + code);
        }
        JSONObject data = login.getJSONObject("data");
        sid = data.getString("sid");
        String newDid = data.optString("did", data.optString("device_id", ""));
        if (!newDid.isEmpty()) prefs.edit().putString("nasDid", newDid).apply();
    }

    private JSONObject api(String name) throws IOException {
        JSONObject a = apis == null ? null : apis.optJSONObject(name);
        if (a == null) throw new IOException(name + " を確認できません");
        return a;
    }

    private JSONObject call(String apiName, String method, Map<String, String> params) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            JSONObject api = api(apiName);
            Map<String, String> p = new LinkedHashMap<>(params);
            p.put("api", apiName);
            p.put("version", "" + Math.min(2, api.optInt("maxVersion", 2)));
            p.put("method", method);
            p.put("_sid", sid);
            JSONObject r = new JSONObject(read(post(base() + "/webapi/" + api.optString("path", "entry.cgi"), form(p), 15000).getInputStream()));
            if (r.optBoolean("success")) return r.optJSONObject("data") == null ? new JSONObject() : r.getJSONObject("data");
            int code = r.optJSONObject("error") == null ? 0 : r.getJSONObject("error").optInt("code");
            // 105〜107・119 はセッション切れ。1 回だけログインし直す
            if (attempt == 0 && (code == 105 || code == 106 || code == 107 || code == 119)) {
                synchronized (this) { sid = null; }
                ensureLogin();
                continue;
            }
            throw new IOException("DSM エラー " + code);
        }
        throw new IOException("DSM に接続できません");
    }

    private HttpURLConnection post(String url, String body, int timeout) throws IOException {
        try {
            return postOnce(url, body, timeout);
        } catch (javax.net.ssl.SSLException e) {
            // 端末が信頼しない証明書（自己署名など）。指紋を取り出して、信頼するか尋ねる
            String fp = Tls.probe(url, timeout);
            if (fp != null && !fp.equals(trustedCert())) throw new CertException(fp);
            throw e;
        }
    }

    private HttpURLConnection postOnce(String url, String body, int timeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        String pin = trustedCert();
        if (c instanceof javax.net.ssl.HttpsURLConnection && !pin.isEmpty()) Tls.pin((javax.net.ssl.HttpsURLConnection) c, pin);
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        if (c.getResponseCode() / 100 != 2) throw new IOException("HTTP " + c.getResponseCode());
        return c;
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static String form(Map<String, String> p) throws IOException {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (b.length() > 0) b.append('&');
            b.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
        }
        return b.toString();
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
