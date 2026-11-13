package dev.immich.media;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps the synthetic MediaStore style ids handed out to the apps back to Immich UUIDs.
 * The mapping has to survive process death because host apps (Tumblr) persist drafts that
 * reference the content URIs we returned.
 */
public final class IdStore extends SQLiteOpenHelper {

    private static final String DATABASE = "immich_media.db";
    private static final int VERSION = 1;

    private static final String CREATE =
            "CREATE TABLE ids ("
                    + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "uuid TEXT UNIQUE NOT NULL,"
                    + "name TEXT,"
                    + "mime TEXT,"
                    + "media_type INTEGER,"
                    + "width INTEGER,"
                    + "height INTEGER,"
                    + "date_added INTEGER,"
                    + "duration INTEGER,"
                    + "size INTEGER"
                    + ")";

    private static IdStore instance;

    private final Map<String, Long> idByUuid = new HashMap<String, Long>();

    public static synchronized IdStore get(Context context) {
        if (instance == null) {
            instance = new IdStore(context.getApplicationContext());
        }
        return instance;
    }

    private IdStore(Context context) {
        super(context, DATABASE, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS ids");
        onCreate(db);
    }

    public synchronized long idFor(ImmichAsset asset) {
        Long cached = idByUuid.get(asset.key);
        if (cached != null) {
            return cached.longValue();
        }
        SQLiteDatabase db = getWritableDatabase();
        long existing = findId(db, asset.key);
        if (existing > 0L) {
            remember(asset.key, existing);
            return existing;
        }
        ContentValues values = new ContentValues();
        values.put("uuid", asset.key);
        values.put("name", asset.name);
        values.put("mime", asset.mime);
        values.put("media_type", asset.mediaType);
        values.put("width", asset.width);
        values.put("height", asset.height);
        values.put("date_added", asset.dateAdded);
        values.put("duration", asset.durationMs);
        values.put("size", asset.size);
        long inserted = db.insert("ids", null, values);
        if (inserted <= 0L) {
            // Lost a race with another process; reuse the row that won.
            inserted = findId(db, asset.key);
        }
        if (inserted > 0L) {
            remember(asset.key, inserted);
        }
        return inserted;
    }

    public synchronized void updateSize(long id, long size) {
        if (id <= 0L || size <= 0L) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("size", size);
        getWritableDatabase().update("ids", values, "_id=?", new String[]{String.valueOf(id)});
    }

    public synchronized ImmichAsset assetFor(long id) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT uuid, name, mime, media_type, width, height, date_added, duration, size"
                        + " FROM ids WHERE _id=?", new String[]{String.valueOf(id)});
        try {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return new ImmichAsset(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getString(2),
                    cursor.getInt(3),
                    cursor.getInt(4),
                    cursor.getInt(5),
                    0,
                    cursor.getLong(6),
                    cursor.getLong(7),
                    cursor.getLong(8));
        } finally {
            cursor.close();
        }
    }

    private static long findId(SQLiteDatabase db, String uuid) {
        Cursor cursor = db.rawQuery("SELECT _id FROM ids WHERE uuid=?", new String[]{uuid});
        try {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1L;
        } finally {
            cursor.close();
        }
    }

    private void remember(String uuid, long id) {
        if (idByUuid.size() > 20000) {
            idByUuid.clear();
        }
        idByUuid.put(uuid, Long.valueOf(id));
    }
}
