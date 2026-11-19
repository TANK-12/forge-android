package app.forge.phone;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A public web address fetched for an offline app that asks for it in
 * phone_apps.json (adapter.public_fetch, Relay task 1335: Shelf saving an
 * article). A WebView page cannot read another site (CORS), so the app asks
 * its own origin for /forge-public-fetch?url= and Main answers from here.
 *
 * Only http and https. Each hop resolves the name once, refuses it unless
 * every address is public (no loopback, home network, link-local, carrier
 * NAT, Tailscale, nor the tablet's own subnets) and then connects a plain
 * socket to that checked address itself, so a DNS answer that changes after
 * the check is never used, and no system proxy resolves it again. Redirects
 * are checked the same way. Bytes are capped after gzip is unpacked, and one
 * deadline covers the name lookup, connecting, headers and body of every hop
 * (a watchdog closes the socket when it passes).
 */
final class PublicFetch {
  static final int HOPS = 5;
  static final int TIMEOUT_MS = 20000;
  static final long DEADLINE_MS = 60000;
  static final int HEADER_BYTES = 65536;
  static final String AGENT =
      "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36";
  static final ExecutorService LOOKUPS = Executors.newCachedThreadPool(r -> daemon(r, "forge-dns"));
  static final ScheduledExecutorService WATCH =
      Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "forge-fetch-deadline"));

  static Thread daemon(Runnable r, String name) {
    Thread t = new Thread(r, name);
    t.setDaemon(true);
    return t;
  }

  static final class Got {
    int status;
    String type = "";
    String url = "";
    byte[] body = new byte[0];
  }

  /** A refusal or failure, in plain words, with the HTTP status to answer. */
  static final class Refused extends IOException {
    final int status;

    Refused(int status, String why) {
      super(why);
      this.status = status;
    }
  }

  static boolean isPublic(InetAddress a) {
    if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
        || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
    byte[] b = a.getAddress();
    if (b.length == 4) return publicV4(b, 0);
    int x = b[0] & 255;
    if ((x & 0xFE) == 0xFC) return false; // unique local fc00::/7
    if (x == 0xFE && (b[1] & 0xC0) == 0x80) return false; // fe80::/10
    if (x == 0x20 && b[1] == 0x01 && b[2] == 0x0d && (b[3] & 255) == 0xb8) return false;
    if (x == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0) return false; // Teredo 2001::/32
    if (x == 0x20 && b[1] == 0x02) return publicV4(b, 2); // 6to4 2002::/16
    boolean front = true;
    for (int i = 0; i < 8; i++) front &= b[i] == 0;
    // ::a.b.c.d, ::ffff:a.b.c.d and ::ffff:0:a.b.c.d carry an IPv4 address
    if (front && ((b[8] == 0 && b[9] == 0 && ((b[10] == 0 && b[11] == 0)
            || (b[10] == (byte) 0xff && b[11] == (byte) 0xff)))
        || (b[8] == (byte) 0xff && b[9] == (byte) 0xff && b[10] == 0 && b[11] == 0)))
      return publicV4(b, 12);
    // 64:ff9b::/96 and 64:ff9b:1::/48 are NAT64: judge the IPv4 address inside
    if (x == 0 && b[1] == 0x64 && (b[2] & 255) == 0xff && (b[3] & 255) == 0x9b) return publicV4(b, 12);
    return true;
  }

  static boolean publicV4(byte[] b, int at) {
    int x = b[at] & 255, y = b[at + 1] & 255, z = b[at + 2] & 255;
    if (x == 0 || x == 10 || x == 127 || x >= 224) return false;
    if (x == 100 && (y & 0xC0) == 64) return false; // carrier NAT and Tailscale
    if (x == 169 && y == 254) return false;
    if (x == 172 && (y & 0xF0) == 16) return false;
    if (x == 192 && y == 168) return false;
    if (x == 192 && y == 0 && (z == 0 || z == 2)) return false;
    if (x == 192 && y == 88 && z == 99) return false; // old 6to4 relays
    if (x == 198 && (y & 0xFE) == 18) return false;
    if (x == 198 && y == 51 && z == 100) return false;
    if (x == 203 && y == 0 && z == 113) return false;
    return true;
  }

  /** On one of the tablet's own networks (a home network on public IPv6 or IPv4)? */
  static boolean onOwnNetwork(InetAddress a) {
    try {
      for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!n.isUp() || n.isLoopback()) continue;
        for (InterfaceAddress ia : n.getInterfaceAddresses()) {
          InetAddress own = ia.getAddress();
          int bits = ia.getNetworkPrefixLength();
          if (own == null || (own instanceof Inet4Address) != (a instanceof Inet4Address)) continue;
          if (bits <= 0 || bits > own.getAddress().length * 8) bits = own.getAddress().length * 8;
          if (samePrefix(own.getAddress(), a.getAddress(), bits)) return true;
        }
      }
    } catch (SocketException | NullPointerException e) {
      // no interface list: the address rules alone
    }
    return false;
  }

  static boolean samePrefix(byte[] p, byte[] q, int bits) {
    if (p.length != q.length) return false;
    for (int i = 0; i < bits; i++) {
      int mask = 0x80 >> (i % 8);
      if ((p[i / 8] & mask) != (q[i / 8] & mask)) return false;
    }
    return true;
  }

  static boolean allowed(InetAddress a) {
    return isPublic(a) && !onOwnNetwork(a);
  }

  static URL parsed(String address) throws Refused {
    URL url;
    try {
      url = new URL(address);
    } catch (IOException e) {
      throw new Refused(400, "That is not a web address");
    }
    String scheme = url.getProtocol().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https"))
      throw new Refused(400, "Only http and https addresses can be fetched");
    if (url.getUserInfo() != null) throw new Refused(400, "Addresses with a sign-in in them are not fetched");
    if (bare(url.getHost()).isEmpty()) throw new Refused(400, "That address has no site in it");
    return url;
  }

  static String bare(String host) {
    if (host == null) return "";
    return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
  }

  static long left(long stop) throws Refused {
    long ms = stop - System.currentTimeMillis();
    if (ms <= 0) throw new Refused(504, "That site took too long");
    return ms;
  }

  /** The addresses a name resolves to, all of them allowed, within the deadline. */
  static InetAddress[] resolved(String host, long stop, Predicate<InetAddress> ok) throws Refused {
    Future<InetAddress[]> lookup = LOOKUPS.submit(() -> InetAddress.getAllByName(host));
    InetAddress[] all;
    try {
      all = lookup.get(left(stop), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      lookup.cancel(true);
      throw new Refused(504, "That site took too long");
    } catch (ExecutionException | InterruptedException e) {
      throw new Refused(502, "The tablet could not find that site. Check the internet connection.");
    }
    if (all.length == 0) throw new Refused(502, "The tablet could not find that site.");
    for (InetAddress a : all)
      if (!ok.test(a)) throw new Refused(403, "Only public addresses are fetched, not this network's own");
    return all;
  }

  /** Throws Refused when the address is not one to fetch (used by the address tests). */
  static URL checked(String address) throws Refused {
    URL url = parsed(address);
    resolved(bare(url.getHost()), System.currentTimeMillis() + DEADLINE_MS, PublicFetch::allowed);
    return url;
  }

  static Got fetch(String address, long most) throws Refused {
    return fetch(address, most, PublicFetch::allowed, DEADLINE_MS);
  }

  static Got fetch(String address, long most, Predicate<InetAddress> ok, long deadlineMs)
      throws Refused {
    long stop = System.currentTimeMillis() + deadlineMs;
    String at = address;
    for (int hop = 0; hop <= HOPS; hop++) {
      URL url = parsed(at);
      String host = bare(url.getHost());
      boolean tls = url.getProtocol().equalsIgnoreCase("https");
      int port = url.getPort() > 0 ? url.getPort() : tls ? 443 : 80;
      InetAddress[] all = resolved(host, stop, ok);
      Socket raw = null;
      ScheduledFuture<?> watchdog = null;
      try {
        raw = new Socket(Proxy.NO_PROXY);
        Socket closing = raw;
        watchdog = WATCH.schedule(() -> quietClose(closing), left(stop), TimeUnit.MILLISECONDS);
        IOException last = null;
        for (InetAddress a : all) {
          try {
            raw.connect(new InetSocketAddress(a, port), (int) Math.min(TIMEOUT_MS, left(stop)));
            last = null;
            break;
          } catch (Refused e) {
            throw e;
          } catch (IOException e) {
            last = e;
            if (raw.isClosed()) break;
            quietClose(raw);
            raw = new Socket(Proxy.NO_PROXY);
            Socket again = raw;
            watchdog.cancel(false);
            watchdog = WATCH.schedule(() -> quietClose(again), left(stop), TimeUnit.MILLISECONDS);
          }
        }
        if (last != null || !raw.isConnected()) {
          left(stop);
          throw new Refused(502, "That site could not be reached");
        }
        Socket s = raw;
        if (tls) s = secure(raw, host, port);
        s.setSoTimeout((int) Math.min(TIMEOUT_MS, left(stop)));
        String path = url.getFile().isEmpty() ? "/" : url.getFile().replace(" ", "%20");
        String hostHeader = url.getHost() + (url.getPort() > 0 ? ":" + url.getPort() : "");
        OutputStream out = s.getOutputStream();
        out.write(("GET " + path + " HTTP/1.1\r\nHost: " + hostHeader + "\r\nUser-Agent: " + AGENT
                + "\r\nAccept: text/html,application/xhtml+xml,image/*;q=0.9,*/*;q=0.5"
                + "\r\nAccept-Encoding: gzip\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        InputStream in = new BufferedInputStream(s.getInputStream());
        int code;
        Map<String, String> head;
        do {
          String status = line(in, stop);
          String[] parts = status.split(" ", 3);
          if (parts.length < 2 || !parts[0].startsWith("HTTP/"))
            throw new Refused(502, "That site did not answer like a web server");
          try {
            code = Integer.parseInt(parts[1]);
          } catch (NumberFormatException e) {
            throw new Refused(502, "That site did not answer like a web server");
          }
          head = headers(in, stop);
        } while (code >= 100 && code < 200);
        if (code >= 300 && code < 400 && code != 304) {
          String next = head.get("location");
          if (next == null) throw new Refused(502, "That site sent a redirect with nowhere to go");
          try {
            at = new URL(url, next).toString();
          } catch (IOException e) {
            throw new Refused(502, "That site sent a redirect to a broken address");
          }
          continue;
        }
        if (code >= 400) throw new Refused(502, "That site answered " + code);
        long size = -1;
        try {
          size = Long.parseLong(head.getOrDefault("content-length", "-1").trim());
        } catch (NumberFormatException e) {
          // unknown length: read to the end
        }
        String coding = head.getOrDefault("transfer-encoding", "").toLowerCase(Locale.ROOT);
        InputStream body;
        if (coding.contains("chunked")) body = new Chunked(in, stop);
        else if (size >= 0) body = new Limited(in, size);
        else body = in;
        boolean gzip = head.getOrDefault("content-encoding", "").toLowerCase(Locale.ROOT).contains("gzip");
        if (!gzip && size > most) throw new Refused(413, "That is too big to keep on the tablet");
        if (gzip) body = new GZIPInputStream(body, 65536);
        Got got = new Got();
        got.status = code;
        got.url = url.toString();
        got.type = head.getOrDefault("content-type", "");
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        for (int n; (n = body.read(buf)) > 0; ) {
          kept.write(buf, 0, n);
          if (kept.size() > most) throw new Refused(413, "That is too big to keep on the tablet");
          s.setSoTimeout((int) Math.min(TIMEOUT_MS, left(stop)));
        }
        got.body = kept.toByteArray();
        return got;
      } catch (Refused e) {
        throw e;
      } catch (IOException e) {
        if (System.currentTimeMillis() >= stop) throw new Refused(504, "That site took too long");
        throw new Refused(502, "That site could not be reached");
      } finally {
        if (watchdog != null) watchdog.cancel(false);
        quietClose(raw);
      }
    }
    throw new Refused(502, "That address redirects too many times");
  }

  /** TLS over the socket already connected to the checked address, with the
   * certificate checked for the site's name (SNI and hostname check). */
  static Socket secure(Socket raw, String host, int port) throws IOException {
    SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
        .createSocket(raw, host, port, true);
    SSLParameters p = ssl.getSSLParameters();
    p.setEndpointIdentificationAlgorithm("HTTPS");
    ssl.setSSLParameters(p);
    ssl.startHandshake();
    if (System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT).contains("android")
        && !HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.getSession()))
      throw new Refused(502, "That site's certificate is not for its name");
    return ssl;
  }

  static void quietClose(Socket s) {
    if (s == null) return;
    try {
      s.close();
    } catch (IOException e) {
      // already gone
    }
  }

  static String line(InputStream in, long stop) throws IOException {
    StringBuilder b = new StringBuilder();
    for (int c; (c = in.read()) != -1; ) {
      if (c == '\n') return b.toString().replaceAll("\r$", "");
      if (b.length() > HEADER_BYTES) throw new Refused(502, "That site sent too many headers");
      b.append((char) c);
      if ((b.length() & 255) == 0) left(stop);
    }
    if (b.length() == 0) throw new Refused(502, "That site closed the connection");
    return b.toString();
  }

  static Map<String, String> headers(InputStream in, long stop) throws IOException {
    Map<String, String> head = new LinkedHashMap<>();
    int total = 0;
    for (String l; !(l = line(in, stop)).isEmpty(); ) {
      total += l.length();
      if (total > HEADER_BYTES || head.size() > 200) throw new Refused(502, "That site sent too many headers");
      int colon = l.indexOf(':');
      if (colon <= 0) continue;
      String name = l.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = l.substring(colon + 1).trim();
      head.merge(name, value, (a, b) -> a + ", " + b);
      left(stop);
    }
    return head;
  }

  /** A body of a known length. */
  static final class Limited extends FilterInputStream {
    long left;

    Limited(InputStream in, long size) {
      super(in);
      left = size;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) <= 0 ? -1 : one[0] & 255;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (left <= 0) return -1;
      int n = super.read(b, off, (int) Math.min(len, left));
      if (n > 0) left -= n;
      return n;
    }
  }

  /** Transfer-Encoding: chunked. */
  static final class Chunked extends FilterInputStream {
    final long stop;
    long left = 0;
    boolean done = false;

    Chunked(InputStream in, long stop) {
      super(in);
      this.stop = stop;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) <= 0 ? -1 : one[0] & 255;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (done) return -1;
      if (left == 0) {
        String size = line(in, stop);
        if (size.isEmpty()) size = line(in, stop); // the CRLF after the last chunk
        int semi = size.indexOf(';');
        try {
          left = Long.parseLong((semi < 0 ? size : size.substring(0, semi)).trim(), 16);
        } catch (NumberFormatException e) {
          throw new Refused(502, "That site sent a broken answer");
        }
        if (left == 0) {
          done = true;
          return -1;
        }
      }
      int n = in.read(b, off, (int) Math.min(len, left));
      if (n <= 0) throw new Refused(502, "That site closed the connection");
      left -= n;
      return n;
    }
  }
}
