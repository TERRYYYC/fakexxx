package name.caiyao.fakegps.data;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

public class AppInfoProvider extends ContentProvider {
    private static final String TAG = "AppInfoProvider";
    public static final Uri APP_CONTENT_URI = Uri.parse("content://" + ProviderAuthority.AUTHORITY + "/app");
    public static final Uri SETTINGS_CONTENT_URI = Uri.parse("content://" + ProviderAuthority.AUTHORITY + "/settings");
    public static final int APP_URI_CODE = 0;
    public static final int SETTINGS_URI_CODE = 1;
    private static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    /** Room database file name — must match AppDatabase.kt */
    private static final String ROOM_DB_NAME = "fakegps.db";
    private static final String TABLE_TEMP = "temp";

    static {
        sUriMatcher.addURI(ProviderAuthority.AUTHORITY, "app", APP_URI_CODE);
        sUriMatcher.addURI(ProviderAuthority.AUTHORITY, "settings", SETTINGS_URI_CODE);
    }

    private SQLiteDatabase mSQLiteDatabase;

    @Override
    public boolean onCreate() {
        // Lazy init — Room DB may not exist yet on first launch
        return true;
    }

    /**
     * Opens Room's internal database so hooks read the same data Compose UI writes.
     * Uses WAL mode for safe concurrent reads alongside Room's own connection.
     */
    private synchronized SQLiteDatabase getDatabase() {
        if (mSQLiteDatabase != null && mSQLiteDatabase.isOpen()) {
            return mSQLiteDatabase;
        }
        File dbFile = getContext().getDatabasePath(ROOM_DB_NAME);
        if (!dbFile.exists()) {
            Log.d(TAG, "Room database not yet created");
            return null;
        }
        try {
            mSQLiteDatabase = SQLiteDatabase.openDatabase(
                    dbFile.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open Room database", e);
            return null;
        }
        return mSQLiteDatabase;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        switch (sUriMatcher.match(uri)) {
            case SETTINGS_URI_CODE:
                return querySettings();
            case APP_URI_CODE:
                SQLiteDatabase db = getDatabase();
                if (db == null) return null;
                return db.query(TABLE_TEMP, projection, selection, selectionArgs, null, null, sortOrder, null);
            default:
                throw new IllegalArgumentException("Unsupported URI:" + uri);
        }
    }

    /** Returns spoof settings as a single-row cursor for hooks to read. */
    private Cursor querySettings() {
        SpoofSettings settings = SpoofSettings.Companion.getInstance(getContext());
        MatrixCursor cursor = new MatrixCursor(new String[]{
                SpoofSettings.KEY_SPOOF_MODE,
                SpoofSettings.KEY_ACTIVE_HOUR_START,
                SpoofSettings.KEY_ACTIVE_HOUR_END,
        });
        cursor.addRow(new Object[]{
                settings.getRawMode(),
                settings.getRawHourStart(),
                settings.getRawHourEnd(),
        });
        return cursor;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, ContentValues values) {
        // Legacy path — Compose UI writes via Room directly
        return uri;
    }

    @Override
    public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
        // Legacy path — Compose UI deletes via Room directly
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        // Legacy path — Compose UI updates via Room directly
        return 0;
    }

}
