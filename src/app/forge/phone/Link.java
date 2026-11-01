package app.forge.phone;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Paired mode's network side (Relay task 666): a phone pairs with a released
 * Forge's phone link (phone/hub.py with PHONE_AUTH=paired) the way Devices,
 * Phones describes it. Plain Java with no Android classes, so the laptop's
 * JDK runs this same code against a real hub (phone/tests/test_apk_link.py).
 *
 * The hub's certificate is this computer's own, which no phone knows. The
 * first handshake sends nothing: it only reads the certificate, whose check
 * code must match the one Devices shows. From then on every connection
 * trusts exactly that certificate (its SHA-256), never the system's list.
 * Only home-network, loopback and Tailscale address literals are accepted,
 * never a name, so nothing off the home network is ever asked.
 */
public final class Link {
  public static final int HUB_PORT = 9464;
  public static final String COOKIE = "__Host-forge-phone";
  static final String PREAUTH = "__Host-forge-phone-login";
  static final int TIMEOUT = 8000;
  static final int BODY_LIMIT = 1_000_000;
  static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  private Link() {}

  /** What went wrong, in words for the phone's screen. */
  public static final class Failure extends Exception {
    public Failure(String message) {
      super(message);
    }
  }

  /** A pairing that worked: the address, the pinned certificate, the token. */
  public static final class Paired {
    public final String host;
    public final String fingerprint;
    public final String token;

    Paired(String host, String fingerprint, String token) {
      this.host = host;
      this.fingerprint = fingerprint;
      this.token = token;
    }
  }

  /** A hub reply: status, body and the cookies it set. */
  public static final class Reply {
    public final int status;
    public final String body;
    final List<String> cookies;

    Reply(int status, String body, List<String> cookies) {
      this.status = status;
      this.body = body;
      this.cookies = cookies;
    }

    String cookie(String name) {
      for (String line : cookies) {
        String first = line.split(";", 2)[0].trim();
        if (first.startsWith(name + "=")) return first.substring(name.length() + 1);
      }
      return "";
    }
  }

  // -- addresses -------------------------------------------------------------

  /**
   * The address in its one written form (dotted decimal, or RFC 5952 IPv6
   * without brackets), the form the WebView also reads it in, or null unless
   * it is a private (10/8, 172.16/12, 192.168/16, IPv6 fc00::/7), IPv4
   * link-local or Tailscale (100.64/10, the address the hub's own home-network
   * rule allows too) address literal. Loopback only on an emulator, where
   * the test computer is reached through it (on a phone it is the phone
   * itself). A pasted https://ADDRESS:9464/ is accepted.
   */
  public static String homeAddress(String text) {
    String s = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    if (s.startsWith("https://")) s = s.substring(8);
    int slash = s.indexOf('/');
    if (slash >= 0) s = s.substring(0, slash);
    if (s.startsWith("[")) {
      int end = s.indexOf(']');
      if (end < 0 || !(s.substring(end + 1).isEmpty() || s.substring(end + 1).equals(":" + HUB_PORT))) {
        return null;
      }
      s = s.substring(1, end);
    } else if (s.endsWith(":" + HUB_PORT) && s.indexOf(':') == s.lastIndexOf(':')) {
      s = s.substring(0, s.length() - (":" + HUB_PORT).length());
    }
    byte[] b = bytesOf(s);
    if (b == null) return null; // only literals: a name would need a lookup
    int a0 = b[0] & 0xff, a1 = b[1] & 0xff;
    boolean ok;
    if (b.length == 4) {
      ok = (a0 == 127 && loopbackOk) || a0 == 10 || (a0 == 172 && a1 >= 16 && a1 <= 31)
          || (a0 == 192 && a1 == 168) || (a0 == 169 && a1 == 254)
          || (a0 == 100 && a1 >= 64 && a1 <= 127);
    } else {
      boolean loop = true;
      for (int i = 0; i < 15; i++) loop &= b[i] == 0;
      ok = (loop && b[15] == 1 && loopbackOk) || (a0 & 0xfe) == 0xfc;
    }
    return ok ? written(b) : null;
  }

  /** Loopback counts as home only on an emulator (Main sets it) or in the laptop's test. */
  public static boolean loopbackOk = Boolean.getBoolean("forge.test.loopback");

  /** An address's one written form: what Chromium's URL parser makes of it. */
  static String written(byte[] b) {
    if (b.length == 4) return (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + "." + (b[3] & 0xff);
    int[] g = new int[8];
    for (int i = 0; i < 8; i++) g[i] = ((b[2 * i] & 0xff) << 8) | (b[2 * i + 1] & 0xff);
    int best = -1, bestLen = 1; // the longest run of two or more zero groups, the first on a tie
    for (int i = 0; i < 8; ) {
      int j = i;
      while (j < 8 && g[j] == 0) j++;
      if (j - i > bestLen) {
        best = i;
        bestLen = j - i;
      }
      i = j == i ? i + 1 : j;
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < 8; i++) {
      if (i == best) {
        out.append("::");
        i += bestLen - 1;
        continue;
      }
      if (out.length() > 0 && out.charAt(out.length() - 1) != ':') out.append(':');
      out.append(Integer.toHexString(g[i]));
    }
    return out.toString();
  }

  /** An IPv4 or IPv6 literal's bytes, parsed here (never a lookup), or null. */
  static byte[] bytesOf(String s) {
    if (s.matches("(\\d{1,3}\\.){3}\\d{1,3}")) {
      byte[] out = new byte[4];
      String[] parts = s.split("\\.");
      for (int i = 0; i < 4; i++) {
        // a leading zero reads as octal in a URL (010 is 8): refuse it
        if (parts[i].length() > 1 && parts[i].startsWith("0")) return null;
        int v = Integer.parseInt(parts[i]);
        if (v > 255) return null;
        out[i] = (byte) v;
      }
      return out;
    }
    if (!s.matches("[0-9a-f:]{2,39}") || s.indexOf("::") != s.lastIndexOf("::")) return null;
    String[] halves = s.split("::", -1);
    String[] head = halves[0].isEmpty() ? new String[0] : halves[0].split(":", -1);
    String[] tail = halves.length < 2 || halves[1].isEmpty() ? new String[0] : halves[1].split(":", -1);
    int groups = head.length + tail.length;
    if (halves.length == 1 ? groups != 8 : groups > 7) return null;
    byte[] out = new byte[16];
    for (int i = 0; i < head.length + tail.length; i++) {
      String g = i < head.length ? head[i] : tail[i - head.length];
      if (g.isEmpty() || g.length() > 4) return null;
      int v = Integer.parseInt(g, 16);
      int at = i < head.length ? i : 8 - tail.length + (i - head.length);
      out[2 * at] = (byte) (v >> 8);
      out[2 * at + 1] = (byte) v;
    }
    return out;
  }

  static String urlHost(String host) {
    return host.contains(":") ? "[" + host + "]" : host;
  }

  public static String origin(String host) {
    return "https://" + urlHost(host) + ":" + HUB_PORT;
  }

  // -- certificates ------------------------------------------------------------

  public static String fingerprint(byte[] der) {
    StringBuilder out = new StringBuilder();
    for (byte x : sha256(der)) out.append(String.format("%02x", x & 0xff));
    return out.toString();
  }

  /** shared/forge_devices.check_code: base32 of the first 10 bytes, in fours. */
  public static String checkCode(byte[] der) {
    byte[] d = sha256(der);
    StringBuilder raw = new StringBuilder();
    int bits = 0, value = 0;
    for (int i = 0; i < 10; i++) {
      value = (value << 8) | (d[i] & 0xff);
      bits += 8;
      while (bits >= 5) {
        raw.append(B32.charAt((value >> (bits - 5)) & 31));
        bits -= 5;
      }
    }
    return raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12)
        + "-" + raw.substring(12, 16);
  }

  /** A typed check code with case, spaces and dashes taken out. */
  public static String plainCode(String text) {
    return (text == null ? "" : text).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
  }

  static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The certificate host:port presents, from a handshake that sends nothing else. */
  public static byte[] peek(String host, int port) throws Failure {
    final byte[][] seen = new byte[1][];
    X509TrustManager look = new X509TrustManager() {
      @Override
      public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException {
        throw new CertificateException("not a server");
      }

      @Override
      public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
        if (chain == null || chain.length == 0) throw new CertificateException("no certificate");
        seen[0] = chain[0].getEncoded(); // compared with the check code by the caller
      }

      @Override
      public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
      }
    };
    try {
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, new TrustManager[] {look}, null);
      try (SSLSocket socket = (SSLSocket) ctx.getSocketFactory().createSocket()) {
        socket.connect(new InetSocketAddress(InetAddress.getByAddress(bytesOf(host)), port), TIMEOUT);
        socket.setSoTimeout(TIMEOUT);
        socket.startHandshake();
      }
    } catch (IOException | java.security.GeneralSecurityException e) {
      if (seen[0] == null) throw new Failure(unreachable());
    }
    if (seen[0] == null) throw new Failure(unreachable());
    return seen[0];
  }

  static String unreachable() {
    return "The computer does not answer. Check that Forge is open there with the"
        + " phone link on (Devices, Phones), and that this phone is on the same Wi-Fi.";
  }

  /** A socket factory that trusts one certificate only, by its SHA-256. */
  public static SSLSocketFactory pinned(final String fingerprint) {
    X509TrustManager pin = new X509TrustManager() {
      @Override
      public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException {
        throw new CertificateException("not a server");
      }

      @Override
      public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
        if (chain == null || chain.length == 0
            || !MessageDigest.isEqual(
                fingerprint(chain[0].getEncoded()).getBytes(StandardCharsets.US_ASCII),
                fingerprint.getBytes(StandardCharsets.US_ASCII))) {
          throw new CertificateException("not the paired computer's certificate");
        }
      }

      @Override
      public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
      }
    };
    try {
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, new TrustManager[] {pin}, null);
      return ctx.getSocketFactory();
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  // -- the hub's own API -----------------------------------------------------------

  /** A connection to an https URL that trusts only the pinned certificate (no redirects, no cache). */
  public static HttpsURLConnection open(String url, String fingerprint) throws IOException {
    URL u = new URL(url);
    if (!"https".equals(u.getProtocol())) throw new IOException("not https");
    HttpsURLConnection c = (HttpsURLConnection) u.openConnection();
    c.setSSLSocketFactory(pinned(fingerprint));
    c.setHostnameVerifier((name, session) -> true); // the pin stands in for the name
    c.setInstanceFollowRedirects(false);
    c.setUseCaches(false);
    c.setConnectTimeout(TIMEOUT);
    c.setReadTimeout(TIMEOUT * 2);
    return c;
  }

  /** Whether a failed handshake was the certificate not matching the pin. */
  public static boolean wrongCertificate(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof CertificateException) return true;
    }
    return false;
  }

  /** One request to the hub over the pinned certificate (no redirects, no cache). */
  public static Reply request(String method, String host, String fingerprint, String path,
      String cookie, String csrf, String json) throws Failure {
    HttpsURLConnection c = null;
    try {
      c = open(origin(host) + path, fingerprint);
      c.setRequestMethod(method);
      c.setRequestProperty("Accept", "application/json");
      if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
      if (json != null) {
        c.setRequestProperty("Origin", origin(host));
        c.setRequestProperty("Content-Type", "application/json");
        if (csrf != null) c.setRequestProperty("X-CSRF-Token", csrf);
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        c.setDoOutput(true);
        try (OutputStream out = c.getOutputStream()) {
          out.write(data);
        }
      }
      int status = c.getResponseCode();
      InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
      String body = in == null ? "" : read(in);
      List<String> cookies = new java.util.ArrayList<>();
      for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
        if (h.getKey() != null && h.getKey().equalsIgnoreCase("set-cookie")) cookies.addAll(h.getValue());
      }
      return new Reply(status, body, cookies);
    } catch (javax.net.ssl.SSLException e) {
      if (!wrongCertificate(e)) {
        throw new Failure("The secure connection to the computer at " + host + " failed. Try again.");
      }
      throw new Failure("The computer at " + host + " answered with another certificate than"
          + " the one this phone paired with. If Forge was set up again there, forget it here"
          + " and pair again with a new code.");
    } catch (IOException e) {
      throw new Failure(unreachable());
    } finally {
      if (c != null) c.disconnect();
    }
  }

  static String read(InputStream in) throws IOException {
    try (InputStream src = in) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      for (int n; (n = src.read(buf)) > 0; ) {
        out.write(buf, 0, n);
        if (out.size() > BODY_LIMIT) throw new IOException("too large");
      }
      return out.toString("UTF-8");
    }
  }

  /**
   * Pair with the computer at address: its certificate must give the check
   * code typed (or scanned), then the pairing code from Devices goes in with
   * a name for this phone. Returns the token for the phone's cookie.
   */
  public static Paired pair(String address, String check, String code, String name) throws Failure {
    String host = homeAddress(address);
    if (host == null) {
      throw new Failure("Type the computer's home-network address as Devices shows it,"
          + " like 192.168.1.20. Addresses outside the home network are not used.");
    }
    if (plainCode(check).length() != 16) {
      throw new Failure("The check code is 16 letters and digits, as Devices shows it.");
    }
    byte[] der = peek(host, HUB_PORT);
    if (!MessageDigest.isEqual(plainCode(checkCode(der)).getBytes(StandardCharsets.US_ASCII),
        plainCode(check).getBytes(StandardCharsets.US_ASCII))) {
      throw new Failure("The check code does not match: this is not your computer, or the"
          + " code was mistyped. Nothing was sent.");
    }
    String fp = fingerprint(der);
    Reply boot = request("GET", host, fp, "/hub/api/bootstrap", null, null, null);
    String pre = boot.cookie(PREAUTH);
    if (boot.status != 200 || pre.isEmpty()) throw new Failure(said(boot));
    if (!"paired".equals(field(boot.body, "mode"))) {
      throw new Failure("That address is a Forge for its owner's own phone, not a home-network"
          + " phone link. Use the Tailscale way below for it.");
    }
    String body = "{\"code\":" + quote(code == null ? "" : code) + ",\"name\":"
        + quote(name == null ? "" : name) + "}";
    Reply r = request("POST", host, fp, "/hub/api/login", PREAUTH + "=" + pre, pre, body);
    String token = r.cookie(COOKIE);
    if (r.status != 200 || token.isEmpty()) throw new Failure(said(r));
    return new Paired(host, fp, token);
  }

  /** GET a hub path as the paired phone. */
  public static Reply get(String host, String fingerprint, String path, String token) throws Failure {
    return request("GET", host, fingerprint, path, COOKIE + "=" + token, null, null);
  }

  /** Sign out: the computer forgets this phone (best effort). */
  public static void signOut(String host, String fingerprint, String token) throws Failure {
    Reply boot = get(host, fingerprint, "/hub/api/bootstrap", token);
    String csrf = field(boot.body, "csrf");
    if (boot.status != 200 || csrf == null) return;
    request("POST", host, fingerprint, "/hub/api/logout", COOKIE + "=" + token, csrf, "{}");
  }

  /** The hub's own words for a refusal ({"detail": ...} or plain text). */
  static String said(Reply r) {
    String detail = field(r.body, "detail");
    if (detail != null && !detail.isEmpty()) return detail;
    String text = r.body == null ? "" : r.body.trim();
    if (!text.isEmpty() && text.length() < 200 && !text.startsWith("{")) return text;
    return "The computer said no (" + r.status + "). Try again with a new code from Devices.";
  }

  /** A top-level string field of a small JSON object (enough for the hub's replies). */
  static String field(String json, String key) {
    if (json == null) return null;
    Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
    if (!m.find()) return null;
    String raw = m.group(1);
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < raw.length(); i++) {
      char ch = raw.charAt(i);
      if (ch != '\\' || i + 1 >= raw.length()) {
        out.append(ch);
        continue;
      }
      char e = raw.charAt(++i);
      if (e == 'u' && i + 4 < raw.length()) {
        out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
        i += 4;
      } else {
        out.append(e == 'n' ? '\n' : e == 't' ? '\t' : e);
      }
    }
    return out.toString();
  }

  /** A JSON string literal. */
  public static String quote(String s) {
    StringBuilder out = new StringBuilder("\"");
    for (char ch : s.toCharArray()) {
      if (ch == '"' || ch == '\\') out.append('\\').append(ch);
      else if (ch < 0x20 || ch == 0x2028 || ch == 0x2029) out.append(String.format("\\u%04x", (int) ch));
      else out.append(ch);
    }
    return out.append('"').toString();
  }

  /**
   * The laptop's check of this code (phone/tests/test_apk_link.py):
   *   pair ADDRESS CHECK CODE NAME   prints host, fingerprint and token lines
   *   get HOST FINGERPRINT TOKEN PATH   prints the status and body
   *   signout HOST FINGERPRINT TOKEN
   *   address TEXT   prints the accepted address or "refused"
   */
  public static void main(String[] args) {
    try {
      switch (args[0]) {
        case "pair":
          Paired p = pair(args[1], args[2], args[3], args[4]);
          System.out.println(p.host + "\n" + p.fingerprint + "\n" + p.token);
          break;
        case "get":
          Reply r = get(args[1], args[2], args[4], args[3]);
          System.out.println(r.status + "\n" + r.body);
          break;
        case "signout":
          signOut(args[1], args[2], args[3]);
          break;
        case "address":
          String a = homeAddress(args[1]);
          System.out.println(a == null ? "refused" : a);
          break;
        default:
          System.exit(64);
      }
    } catch (Failure f) {
      System.out.println("failure: " + f.getMessage());
      System.exit(2);
    }
  }
}
