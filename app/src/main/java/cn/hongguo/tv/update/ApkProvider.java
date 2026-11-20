package cn.hongguo.tv.update;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简文件 ContentProvider，只为把下载好的 APK 交给系统安装器。
 *
 * 为什么不用 androidx.core 的 FileProvider：
 *   项目刻意不引 AndroidX（见 build.gradle 的包体约束）。FileProvider
 *   会带进 support-core 一堆依赖，而我们只需要「按文件名读一个文件」。
 *
 * 安全边界：
 *   只暴露 cache/updates/ 这一个目录，且只允许读。任何试图用 ../ 跳出
 *   目录的路径都会被 basename 处理掉，不会读到别处的文件。
 */
public final class ApkProvider extends ContentProvider {

    static final String AUTHORITY_SUFFIX = ".apk";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("read-only provider");
        }
        return ParcelFileDescriptor.open(updateApk(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private File updateApk(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (getContext() == null || uri.getPathSegments().size() != 1 || name == null
                || !name.matches("hongguo-tv-[0-9]{1,9}[.][0-9]{1,9}[.][0-9]{1,9}[.]apk")) {
            throw new FileNotFoundException("invalid update package name");
        }
        File dir = new File(getContext().getCacheDir(), "updates");
        File f = new File(dir, name);
        final File canonical;
        try {
            canonical = f.getCanonicalFile();
            if (!dir.getCanonicalFile().equals(canonical.getParentFile())) {
                throw new FileNotFoundException("outside provider root");
            }
        } catch (java.io.IOException e) {
            throw new FileNotFoundException("path check failed");
        }
        if (!canonical.isFile()) throw new FileNotFoundException("update package unavailable");
        return canonical;
    }

    /** 让安装器能拿到文件名和大小（部分 ROM 会读这个）。 */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        final File f;
        try {
            f = updateApk(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                row[i] = f.getName();
            } else if (OpenableColumns.SIZE.equals(cols[i])) {
                row[i] = f.length();
            } else {
                row[i] = null;
            }
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
