package name.caiyao.fakegps.dao;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import name.caiyao.fakegps.data.DbHelper;
import name.caiyao.fakegps.ui.profile.FieldSpec;

/**
 * Full-column CRUD for the temp table.
 * Writes ContentValues where null/absent columns = SQL NULL = hook passthrough.
 */
public class ProfileDao {

    private final DbHelper dbHelper;

    public ProfileDao(DbHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    /** Insert a new profile row. Absent columns default to SQL NULL (passthrough). */
    public long insertProfile(ContentValues cv) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return db.insert(DbHelper.APP_TEMP_NAME, null, cv);
    }

    /**
     * Update an existing profile row.
     * Resets ALL known columns to NULL first, then applies the user's values.
     * This ensures cleared fields revert to passthrough.
     */
    public int updateProfile(long id, ContentValues userValues) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = buildResetValues();
        // Overwrite with user's non-null values
        cv.putAll(userValues);
        return db.update(DbHelper.APP_TEMP_NAME, cv, "id = ?",
                new String[]{String.valueOf(id)});
    }

    /** Load one profile row into ContentValues (only non-null columns). */
    public ContentValues loadProfile(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        ContentValues cv = new ContentValues();
        Cursor c = null;
        try {
            c = db.query(DbHelper.APP_TEMP_NAME, null, "id = ?",
                    new String[]{String.valueOf(id)}, null, null, null);
            if (c != null && c.moveToFirst()) {
                for (int i = 0; i < c.getColumnCount(); i++) {
                    if (c.isNull(i)) continue;
                    String col = c.getColumnName(i);
                    int type = c.getType(i);
                    switch (type) {
                        case Cursor.FIELD_TYPE_INTEGER:
                            cv.put(col, c.getLong(i));
                            break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            cv.put(col, c.getDouble(i));
                            break;
                        case Cursor.FIELD_TYPE_STRING:
                            cv.put(col, c.getString(i));
                            break;
                    }
                }
            }
        } finally {
            if (c != null) c.close();
        }
        return cv;
    }

    /** Delete a profile by id. */
    public void deleteProfile(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(DbHelper.APP_TEMP_NAME, "id = ?", new String[]{String.valueOf(id)});
    }

    /** List all profiles with summary info. */
    public List<ProfileSummary> listProfiles() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<ProfileSummary> list = new ArrayList<>();
        Cursor c = null;
        try {
            c = db.query(DbHelper.APP_TEMP_NAME,
                    new String[]{"id", "addname", "latitude", "longitude"},
                    null, null, null, null, "id ASC");
            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String name = c.isNull(1) ? "" : c.getString(1);
                    double lat = c.isNull(2) ? 0 : c.getDouble(2);
                    double lon = c.isNull(3) ? 0 : c.getDouble(3);
                    list.add(new ProfileSummary(id, name, lat, lon));
                }
            }
        } finally {
            if (c != null) c.close();
        }
        return list;
    }

    /** Build ContentValues with all known field columns set to NULL. */
    private ContentValues buildResetValues() {
        ContentValues cv = new ContentValues();
        for (Map.Entry<String, List<FieldSpec>> entry : FieldSpec.allCategories().entrySet()) {
            for (FieldSpec spec : entry.getValue()) {
                cv.putNull(spec.dbColumn);
            }
        }
        // Also reset base fields
        cv.putNull("latitude");
        cv.putNull("longitude");
        cv.putNull("addname");
        return cv;
    }

    public static class ProfileSummary {
        public final long id;
        public final String name;
        public final double latitude;
        public final double longitude;

        public ProfileSummary(long id, String name, double latitude, double longitude) {
            this.id = id;
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
}
