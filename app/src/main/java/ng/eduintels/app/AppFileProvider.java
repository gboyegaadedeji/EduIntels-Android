package ng.eduintels.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Minimal private cache provider used only for camera capture URIs. */
public class AppFileProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) throw new FileNotFoundException();
        File dir = new File(getContext().getCacheDir(), "camera");
        File f = new File(dir, name);
        try {
            String base = dir.getCanonicalPath() + File.separator;
            String actual = f.getCanonicalPath();
            if (!actual.startsWith(base)) throw new FileNotFoundException();
        } catch (Exception e) { throw new FileNotFoundException(); }
        return f;
    }
    @Override public String getType(Uri uri) { return "image/jpeg"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int flags = ParcelFileDescriptor.MODE_READ_ONLY;
        if (mode != null && mode.contains("w")) flags = ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_CREATE;
        return ParcelFileDescriptor.open(fileFor(uri), flags);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols);
        Object[] row = new Object[cols.length];
        try {
            File f = fileFor(uri);
            for (int i=0;i<cols.length;i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i]=f.getName();
                else if (OpenableColumns.SIZE.equals(cols[i])) row[i]=f.length();
                else row[i]=null;
            }
            c.addRow(row);
        } catch (Exception ignored) {}
        return c;
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { try { return fileFor(uri).delete()?1:0; } catch(Exception e){return 0;} }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
