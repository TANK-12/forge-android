package app.forge.phone;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Over-the-air updates (Relay task 1316). Forge asks the places a newer build
 * of itself may be: each paired computer (its phone link's GET
 * /hub/api/phone-app, over that computer's pinned certificate and sign-in),
 * the owner's laptop over Tailscale, and the Forge for Android release on
 * GitHub (a forge-android.json beside forge.apk in the latest release). The
 * newest build above this one wins. Its file is fetched into the app's cache,
 * its size and SHA-256 checked against what the source said, then Android's
 * own record of the file: the same package, a higher version and the same
 * signing certificate as this install. Only then does Android's package
 * installer get it; it asks the person to confirm and keeps the app's data
 * (pairings, settings), as it refuses any build signed with another key.
 *
 * What the sources answer: {"package", "version_code", "version_name",
 * "size", "sha256", "notes"} (the site's also "apk", its download address).
 */
final class Updates {
  static final String PACKAGE = "app.forge.phone";
  static final String SITE_JSON =
      "https://github.com/TANK-12/forge-android/releases/latest/download/forge-android.json";
  static final String SITE_APK = "https://github.com/TANK-12/forge-android/releases/download/";
  static final String HUB_JSON = "/hub/api/phone-app", HUB_APK = "/hub/api/phone-app/forge.apk";
  static final long MAX_APK = 200L * 1024 * 1024;
  static final long AUTO_EVERY = 12L * 60 * 60 * 1000;
  static final String DONE = "app.forge.phone.UPDATE_DONE";

  private Updates() {}

  /** A newer build somewhere: what it is and where it comes from. */
  static final class Offer {
    final String kind, host, where, name, sha256, notes, apk, signer;
    final long code, size;

    Offer(String kind, String host, String where, JSONObject o) throws JSONException {
      this.kind = kind;
      this.host = host;
      this.where = where;
      this.code = o.getLong("version_code");
      this.name = o.optString("version_name", String.valueOf(code));
      this.size = o.getLong("size");
      this.sha256 = o.getString("sha256").toLowerCase(Locale.ROOT);
      this.notes = o.optString("notes", "");
      this.apk = o.optString("apk", "");
      this.signer = o.optString("signer", "").toLowerCase(Locale.ROOT);
      if (!signer.isEmpty() && !signer.matches("[0-9a-f]{64}")) throw new JSONException("bad signer");
      if (!PACKAGE.equals(o.optString("package", PACKAGE))) throw new JSONException("another app");
      if (!sha256.matches("[0-9a-f]{64}") || size <= 0 || size > MAX_APK) throw new JSONException("bad file facts");
    }

    JSONObject json() {
      try {
        return new JSONObject().put("kind", kind).put("host", host).put("where", where)
            .put("version_code", code).put("version_name", name).put("size", size)
            .put("sha256", sha256).put("notes", notes).put("apk", apk).put("signer", signer);
      } catch (JSONException e) {
        return new JSONObject();
      }
    }

    static Offer of(JSONObject o) {
      if (o == null) return null;
      try {
        return new Offer(o.getString("kind"), o.optString("host"), o.optString("where"), o);
      } catch (JSONException e) {
        return null;
      }
    }
  }

  /** Something that stopped a check or an update, in words for the person. */
  static final class Stop extends Exception {
    final boolean badFile; // the file itself was wrong, not a dropped line: skip that build next time

    Stop(String words) {
      this(words, false);
    }

    Stop(String words, boolean badFile) {
      super(words);
      this.badFile = badFile;
    }
  }

  /** Builds whose file was wrong once (by SHA-256): a check passes them over for another source's. */
  static Set<String> refused(Context c) {
    return new HashSet<>(prefs(c).getStringSet("update_refused", new HashSet<>()));
  }

  static void refuse(Context c, Offer o) {
    Set<String> s = refused(c);
    s.add(o.sha256);
    prefs(c).edit().putStringSet("update_refused", s).remove("update_offer").apply();
  }

  /** SHA-256 of each certificate this Forge is signed with, as apksigner --print-certs shows it. */
  @SuppressWarnings("deprecation")
  static Set<String> ourSigners(Context c) {
    Set<String> out = new HashSet<>();
    int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      for (Signature s : signers(c.getPackageManager().getPackageInfo(c.getPackageName(), flags))) {
        out.add(hex(sha.digest(s.toByteArray())));
      }
    } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException e) {
      // unknown: offers are not sorted by key, and vet still reads the file
    }
    return out;
  }

  static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("forge", Context.MODE_PRIVATE);
  }

  @SuppressWarnings("deprecation")
  static long installedCode(Context c) {
    try {
      PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
      return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    } catch (PackageManager.NameNotFoundException e) {
      return 0;
    }
  }

  /** Installed over an older Forge (rather than fresh), as Android counts it. */
  static boolean updatedOver(Context c) {
    try {
      PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
      return p.lastUpdateTime > p.firstInstallTime;
    } catch (PackageManager.NameNotFoundException e) {
      return false;
    }
  }

  static String installedName(Context c) {
    try {
      return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
    } catch (PackageManager.NameNotFoundException e) {
      return "";
    }
  }

  /** One paired computer to ask: its address, pinned certificate, sign-in and name. */
  static final class Computer {
    final String host, pin, token, name;

    Computer(String host, String pin, String token, String name) {
      this.host = host;
      this.pin = pin;
      this.token = token;
      this.name = name;
    }
  }

  /**
   * Ask every source (on a background thread). The newest build above this
   * one, or null; what each source said lands in said (for the page).
   */
  static Offer check(Context c, List<Computer> computers, String owner, String ownerCookie,
      List<String> said) {
    long have = installedCode(c);
    List<Offer> found = new ArrayList<>();
    for (Computer pc : computers) {
      String where = pc.name.isEmpty() ? pc.host : pc.name;
      if (pc.token.isEmpty()) {
        said.add(where + ": not signed in");
        continue;
      }
      try {
        Link.Reply r = Link.get(pc.host, pc.pin, HUB_JSON, pc.token);
        found.add(offer(r.status, r.body, "computer", pc.host, where, said));
      } catch (Link.Failure f) {
        said.add(where + ": does not answer");
      }
    }
    if (!owner.isEmpty() && ownerCookie != null && !ownerCookie.isEmpty()) {
      try {
        HttpsURLConnection h = web("https://" + owner + ":" + Link.HUB_PORT + HUB_JSON, false);
        h.setRequestProperty("Cookie", ownerCookie);
        found.add(offer(h.getResponseCode(), body(h), "owner", owner, "your laptop", said));
      } catch (IOException e) {
        said.add("your laptop: does not answer");
      }
    }
    try {
      HttpsURLConnection h = web(SITE_JSON, true);
      Offer o = offer(h.getResponseCode(), body(h), "site", "", "the Forge release site", said);
      if (o != null && !o.apk.startsWith(SITE_APK)) {
        said.add("the Forge release site: a download address outside the release");
        o = null;
      }
      found.add(o);
    } catch (IOException e) {
      said.add("the Forge release site: not reachable");
    }
    // a build signed with another key (made on a computer without the release key)
    // or refused once must not hide a good one somewhere else
    Set<String> keys = ourSigners(c), refused = refused(c);
    Offer best = null;
    for (Offer o : found) {
      if (o == null || o.code <= have) continue;
      if (!o.signer.isEmpty() && !keys.isEmpty() && !keys.contains(o.signer)) {
        said.add(o.where + ": its " + o.name + " is signed with another key, so it is skipped");
        continue;
      }
      if (refused.contains(o.sha256)) {
        said.add(o.where + ": its " + o.name + " was refused before, so it is skipped");
        continue;
      }
      if (best == null || o.code > best.code) best = o;
    }
    return best;
  }

  static Offer offer(int status, String body, String kind, String host, String where, List<String> said) {
    if (status == 404) {
      said.add(where + ": has no phone build");
      return null;
    }
    if (status != 200) {
      said.add(where + ": answered " + status);
      return null;
    }
    try {
      JSONObject o = new JSONObject(body);
      if (o.optBoolean("none")) {
        said.add(where + ": has no phone build");
        return null;
      }
      Offer got = new Offer(kind, host, where, o);
      said.add(where + ": has " + got.name);
      return got;
    } catch (JSONException e) {
      said.add(where + ": answered something else");
      return null;
    }
  }

  /** A plain https request (the owner's laptop over Tailscale, the release site): system trust. */
  static HttpsURLConnection web(String url, boolean redirects) throws IOException {
    URL u = new URL(url);
    if (!"https".equals(u.getProtocol())) throw new IOException("not https");
    HttpsURLConnection h = (HttpsURLConnection) u.openConnection();
    h.setInstanceFollowRedirects(redirects); // https to https only, by HttpURLConnection's own rule
    h.setUseCaches(false);
    h.setConnectTimeout(Link.TIMEOUT);
    h.setReadTimeout(Link.TIMEOUT * 2);
    h.setRequestProperty("Accept", "application/json");
    return h;
  }

  static String body(HttpsURLConnection h) throws IOException {
    try {
      int code = h.getResponseCode();
      InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
      return in == null ? "" : Link.read(in);
    } finally {
      h.disconnect();
    }
  }

  /** Where the update is fetched to (the app's own cache, nobody else's). */
  static File file(Context c) {
    return new File(new File(c.getCacheDir(), "update"), "forge.apk");
  }

  public interface Progress {
    void at(long done, long size);
  }

  /** Fetch the offer's file, checking its size and SHA-256 on the way. */
  static File fetch(Context c, Offer o, Computer pc, String ownerCookie, Progress progress) throws Stop {
    File out = file(c);
    File dir = out.getParentFile();
    if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) throw new Stop("This device has no room for the update.");
    HttpsURLConnection h = null;
    try {
      if ("computer".equals(o.kind)) {
        if (pc == null) throw new Stop("That computer is no longer paired.");
        h = Link.open(Link.origin(pc.host) + HUB_APK, pc.pin);
        h.setRequestProperty("Cookie", Link.COOKIE + "=" + pc.token);
      } else if ("owner".equals(o.kind)) {
        h = web("https://" + o.host + ":" + Link.HUB_PORT + HUB_APK, false);
        if (ownerCookie != null) h.setRequestProperty("Cookie", ownerCookie);
      } else {
        if (!o.apk.startsWith(SITE_APK)) throw new Stop("The release site gave a download address outside the release.");
        h = web(o.apk, true);
      }
      h.setRequestProperty("Accept", "application/vnd.android.package-archive");
      if (h.getResponseCode() != 200) throw new Stop(o.where + " did not hand over the update (" + h.getResponseCode() + ").");
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      long done = 0;
      try (InputStream in = h.getInputStream(); OutputStream w = new FileOutputStream(out)) {
        byte[] buf = new byte[65536];
        long told = 0;
        for (int n; (n = in.read(buf)) > 0; ) {
          done += n;
          if (done > o.size) throw new Stop("The update from " + o.where + " is bigger than it said.", true);
          sha.update(buf, 0, n);
          w.write(buf, 0, n);
          if (progress != null && done - told > o.size / 50) {
            told = done;
            progress.at(done, o.size);
          }
        }
      }
      if (done != o.size) throw new Stop("The update from " + o.where + " stopped part way. Try again.");
      if (!hex(sha.digest()).equals(o.sha256)) throw new Stop("The update from " + o.where + " is not the file it said. Nothing was installed.", true);
      return out;
    } catch (IOException e) {
      out.delete();
      throw new Stop(o.where + " stopped answering during the download. Try again.");
    } catch (NoSuchAlgorithmException e) {
      out.delete();
      throw new Stop("This device cannot check the update.");
    } catch (Stop s) {
      out.delete();
      throw s;
    } finally {
      if (h != null) h.disconnect();
    }
  }

  static String hex(byte[] b) {
    StringBuilder s = new StringBuilder();
    for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
    return s.toString();
  }

  /** Android's own reading of the file: this app, a newer version, the same signing key. */
  @SuppressWarnings("deprecation")
  static void vet(Context c, File apk) throws Stop {
    PackageManager pm = c.getPackageManager();
    int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    PackageInfo got = pm.getPackageArchiveInfo(apk.getPath(), flags);
    if (got == null) throw new Stop("The update is not an app Android can read.", true);
    if (!c.getPackageName().equals(got.packageName)) throw new Stop("The update is another app, not Forge.", true);
    long code = Build.VERSION.SDK_INT >= 28 ? got.getLongVersionCode() : got.versionCode;
    if (code <= installedCode(c)) throw new Stop("The update is not newer than this Forge.", true);
    Set<Signature> theirs = signers(got), ours;
    try {
      ours = signers(pm.getPackageInfo(c.getPackageName(), flags));
    } catch (PackageManager.NameNotFoundException e) {
      ours = new HashSet<>();
    }
    // Android refuses another key anyway; said here first, in plain words
    if (!theirs.isEmpty() && !ours.isEmpty() && !theirs.equals(ours)) {
      throw new Stop("The update is signed by someone else, so Android would not put it over this Forge.", true);
    }
  }

  @SuppressWarnings("deprecation")
  static Set<Signature> signers(PackageInfo p) {
    Signature[] all = null;
    if (Build.VERSION.SDK_INT >= 28 && p.signingInfo != null) all = p.signingInfo.getApkContentsSigners();
    else if (p.signatures != null) all = p.signatures;
    return all == null ? new HashSet<>() : new HashSet<>(Arrays.asList(all));
  }

  /** Hand the checked file to Android's package installer; Done hears how it went. */
  static void install(Context c, File apk) throws Stop {
    PackageInstaller installer = c.getPackageManager().getPackageInstaller();
    PackageInstaller.SessionParams params =
        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
    params.setAppPackageName(c.getPackageName());
    params.setSize(apk.length());
    if (Build.VERSION.SDK_INT >= 31) {
      params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
    }
    int id;
    try {
      id = installer.createSession(params);
    } catch (IOException | RuntimeException e) {
      throw new Stop("Android's installer is busy. Try again in a moment.");
    }
    try (PackageInstaller.Session s = installer.openSession(id)) {
      try (OutputStream out = s.openWrite("forge.apk", 0, apk.length()); InputStream in = new FileInputStream(apk)) {
        Main.copy(in, out);
        s.fsync(out);
      }
      Intent done = new Intent(c, Done.class).setAction(DONE);
      int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
      s.commit(PendingIntent.getBroadcast(c, id, done, flags).getIntentSender());
    } catch (IOException | RuntimeException e) {
      try {
        installer.abandonSession(id);
      } catch (RuntimeException ignored) {
        // already gone
      }
      throw new Stop("The update could not be handed to Android's installer.");
    }
  }

  /** What Android's installer says: ask the person to confirm, or how it ended. */
  public static final class Done extends BroadcastReceiver {
    static volatile Main page;
    static volatile boolean front; // Forge on screen: Android lets it show the install question
    static volatile Intent pending; // the question, kept for when Forge is open again

    @Override
    public void onReceive(Context c, Intent intent) {
      int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
      if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
        Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        if (confirm != null) {
          confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          if (!front) {
            // Android quietly blocks a question from an app out of sight; asked on return
            pending = confirm;
            tell("{\"update\":\"confirm\"}");
            return;
          }
          try {
            c.startActivity(confirm);
            tell("{\"update\":\"confirm\"}");
            return;
          } catch (RuntimeException e) {
            // falls through to the words below
          }
        }
        tell("{\"update\":\"failed\",\"error\":\"Android did not show its install question. Try again.\"}");
        return;
      }
      pending = null;
      if (status == PackageInstaller.STATUS_SUCCESS) {
        file(c).delete();
        tell("{\"update\":\"installed\"}");
        return;
      }
      file(c).delete();
      String why;
      switch (status) {
        case PackageInstaller.STATUS_FAILURE_ABORTED:
          why = "The update was cancelled. Forge stays as it is.";
          break;
        case PackageInstaller.STATUS_FAILURE_CONFLICT:
        case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
          why = "Android would not put this build over Forge (another signing key or an older Android).";
          break;
        case PackageInstaller.STATUS_FAILURE_STORAGE:
          why = "This device has no room for the update.";
          break;
        default:
          why = "Android's installer stopped the update.";
      }
      tell("{\"update\":\"failed\",\"error\":" + Link.quote(why) + "}");
    }

    static void tell(String json) {
      Main m = page;
      if (m != null) m.answer(json);
    }
  }
}
