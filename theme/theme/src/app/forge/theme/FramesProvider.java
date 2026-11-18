/* Hands the home screen the particles widget's frames (files/widget/*.png)
   so it can show them itself. Read-only, and only those picture files: any
   other name is refused. A frame can show a picture of your own, so every
   drawing gets 64 random bits in its names: only the home screen, which is
   handed the addresses, can find them. */
package app.forge.theme;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.regex.Pattern;

public final class FramesProvider extends ContentProvider {
  /** app.forge.theme.frames in Forge Theme, app.forge.phone.frames inside the main
      Forge app (task 1316): two installed apps may not share an authority. */
  static String authority(android.content.Context c) {
    return c.getPackageName() + ".frames";
  }
  private static final Pattern NAME = Pattern.compile("p\\d+-\\d+-\\d+-[0-9a-f]{1,16}\\.png");

  @Override public boolean onCreate() {
    return true;
  }

  @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    String name = uri.getLastPathSegment();
    if (!"r".equals(mode) || name == null || !NAME.matcher(name).matches()
        || uri.getPathSegments().size() != 1 || getContext() == null) {
      throw new FileNotFoundException("not a widget frame");
    }
    File f = new File(new File(getContext().getFilesDir(), "widget"), name);
    return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
  }

  @Override public String getType(Uri uri) {
    return "image/png";
  }

  @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
    return null;
  }

  @Override public Uri insert(Uri uri, ContentValues values) {
    return null;
  }

  @Override public int delete(Uri uri, String s, String[] a) {
    return 0;
  }

  @Override public int update(Uri uri, ContentValues v, String s, String[] a) {
    return 0;
  }
}
