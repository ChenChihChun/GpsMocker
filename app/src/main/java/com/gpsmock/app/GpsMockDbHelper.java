package com.gpsmock.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class GpsMockDbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "gpsmock.db";
    private static final int DB_VERSION = 4;

    public static final String TABLE_PRESETS = "presets";
    public static final String COL_ID = "id";
    public static final String COL_NAME = "name";
    public static final String COL_LAT = "lat";
    public static final String COL_LNG = "lng";
    public static final String COL_CREATED_AT = "created_at";

    public static final String TABLE_SETTINGS = "settings";
    public static final String COL_KEY = "key";
    public static final String COL_VALUE = "value";

    public static final String TABLE_FLOWER_POTS = "flower_pots";
    public static final String COL_ORIG_LAT = "orig_lat";
    public static final String COL_ORIG_LNG = "orig_lng";
    public static final String COL_CATEGORY = "category";
    public static final String COL_CORRECTED = "corrected";
    public static final String COL_EXPIRES_AT = "expires_at";

    public static final String CATEGORY_PERMANENT = "permanent";
    public static final String CATEGORY_EVENT = "event";
    public static final String CATEGORY_MUSHROOM = "mushroom";

    public static final String KEY_HOME_LAT = "home_lat";
    public static final String KEY_HOME_LNG = "home_lng";
    public static final String KEY_MUSHROOM_STAY_SEC = "mushroom_stay_sec";
    public static final String KEY_PATROL_STAY_SEC = "patrol_stay_sec";
    public static final String KEY_BIG_HOURS = "big_mushroom_hours";

    public GpsMockDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_PRESETS + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_NAME + " TEXT NOT NULL, "
                + COL_LAT + " REAL NOT NULL, "
                + COL_LNG + " REAL NOT NULL, "
                + COL_CREATED_AT + " INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE " + TABLE_SETTINGS + " ("
                + COL_KEY + " TEXT PRIMARY KEY, "
                + COL_VALUE + " TEXT)");

        createFlowerPotsTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 增量升級：保留使用者的 presets / settings，只補上新表。
        if (oldVersion < 2) {
            createFlowerPotsTable(db);
        }
        if (oldVersion < 3) {
            db.delete(TABLE_FLOWER_POTS, COL_CATEGORY + "=?", new String[]{CATEGORY_PERMANENT});
        }
        // oldVersion < 2 時上面已用新 schema 建表（含 expires_at），不需再補欄位
        if (oldVersion >= 2 && oldVersion < 4) {
            db.execSQL("ALTER TABLE " + TABLE_FLOWER_POTS + " ADD COLUMN "
                    + COL_EXPIRES_AT + " INTEGER NOT NULL DEFAULT 0");
        }
    }

    private void createFlowerPotsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_FLOWER_POTS + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_NAME + " TEXT NOT NULL, "
                + COL_LAT + " REAL NOT NULL, "
                + COL_LNG + " REAL NOT NULL, "
                + COL_ORIG_LAT + " REAL NOT NULL, "
                + COL_ORIG_LNG + " REAL NOT NULL, "
                + COL_CATEGORY + " TEXT NOT NULL, "
                + COL_CORRECTED + " INTEGER NOT NULL DEFAULT 0, "
                + COL_EXPIRES_AT + " INTEGER NOT NULL DEFAULT 0, "
                + COL_CREATED_AT + " INTEGER NOT NULL)");
    }


    public long insertPreset(String name, double lat, double lng) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_NAME, name);
        cv.put(COL_LAT, lat);
        cv.put(COL_LNG, lng);
        cv.put(COL_CREATED_AT, System.currentTimeMillis());
        return db.insert(TABLE_PRESETS, null, cv);
    }

    public void deletePreset(long id) {
        getWritableDatabase().delete(TABLE_PRESETS, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    public List<Preset> getAllPresets() {
        List<Preset> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(TABLE_PRESETS, null, null, null, null, null, COL_CREATED_AT + " DESC")) {
            while (c.moveToNext()) {
                Preset p = new Preset();
                p.id = c.getLong(c.getColumnIndexOrThrow(COL_ID));
                p.name = c.getString(c.getColumnIndexOrThrow(COL_NAME));
                p.lat = c.getDouble(c.getColumnIndexOrThrow(COL_LAT));
                p.lng = c.getDouble(c.getColumnIndexOrThrow(COL_LNG));
                list.add(p);
            }
        }
        return list;
    }

    public void setSetting(String key, String value) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_KEY, key);
        cv.put(COL_VALUE, value);
        db.insertWithOnConflict(TABLE_SETTINGS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String getSetting(String key, String defaultValue) {
        SQLiteDatabase db = getReadableDatabase();
        String result = defaultValue;
        try (Cursor c = db.query(TABLE_SETTINGS, new String[]{COL_VALUE},
                COL_KEY + "=?", new String[]{key}, null, null, null)) {
            if (c.moveToFirst()) {
                result = c.getString(0);
            }
        }
        return result;
    }

    public static class Preset {
        public long id;
        public String name;
        public double lat;
        public double lng;
    }

    // ---- 金色花盆 ----

    public long insertFlowerPot(String name, double lat, double lng, String category) {
        return insertFlowerPot(name, lat, lng, category, 0);
    }

    /** expiresAt：大菇到期時間（epoch ms），0 表示沒有大菇。 */
    public long insertFlowerPot(String name, double lat, double lng, String category, long expiresAt) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_NAME, name);
        cv.put(COL_LAT, lat);
        cv.put(COL_LNG, lng);
        cv.put(COL_ORIG_LAT, lat);
        cv.put(COL_ORIG_LNG, lng);
        cv.put(COL_CATEGORY, category);
        cv.put(COL_CORRECTED, 0);
        cv.put(COL_EXPIRES_AT, expiresAt);
        cv.put(COL_CREATED_AT, System.currentTimeMillis());
        return db.insert(TABLE_FLOWER_POTS, null, cv);
    }

    public void deleteFlowerPot(long id) {
        getWritableDatabase().delete(TABLE_FLOWER_POTS, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** 刪除所有花盆（不含大菇）。 */
    public void deleteAllFlowerPots() {
        getWritableDatabase().delete(TABLE_FLOWER_POTS, COL_CATEGORY + "!=?", new String[]{CATEGORY_MUSHROOM});
    }

    public void deleteAllMushrooms() {
        getWritableDatabase().delete(TABLE_FLOWER_POTS, COL_CATEGORY + "=?", new String[]{CATEGORY_MUSHROOM});
    }

    /** 設定菇點上大菇的到期時間（epoch ms，0 表示目前沒有大菇）。 */
    public void setExpiresAt(long id, long expiresAt) {
        ContentValues cv = new ContentValues();
        cv.put(COL_EXPIRES_AT, expiresAt);
        getWritableDatabase().update(TABLE_FLOWER_POTS, cv, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** 修正花盆座標：更新 lat/lng 並標記為已修正，保留原始座標供復原。 */
    public void correctFlowerPot(long id, double lat, double lng) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_LAT, lat);
        cv.put(COL_LNG, lng);
        cv.put(COL_CORRECTED, 1);
        db.update(TABLE_FLOWER_POTS, cv, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** 復原花盆座標為原始值。 */
    public void revertFlowerPot(long id) {
        getWritableDatabase().execSQL(
                "UPDATE " + TABLE_FLOWER_POTS + " SET "
                        + COL_LAT + "=" + COL_ORIG_LAT + ", "
                        + COL_LNG + "=" + COL_ORIG_LNG + ", "
                        + COL_CORRECTED + "=0 WHERE " + COL_ID + "=?",
                new Object[]{id});
    }

    /** 取得所有花盆（不含大菇）：常駐在前、活動在後，各依建立時間排序。 */
    public List<FlowerPot> getAllFlowerPots() {
        String orderBy = "CASE " + COL_CATEGORY + " WHEN '" + CATEGORY_PERMANENT
                + "' THEN 0 ELSE 1 END ASC, " + COL_CREATED_AT + " ASC";
        return queryFlowerPots(COL_CATEGORY + "!=?", new String[]{CATEGORY_MUSHROOM}, orderBy);
    }

    /** 取得所有大菇，依建立時間排序。 */
    public List<FlowerPot> getAllMushrooms() {
        return queryFlowerPots(COL_CATEGORY + "=?", new String[]{CATEGORY_MUSHROOM}, COL_CREATED_AT + " ASC");
    }

    private List<FlowerPot> queryFlowerPots(String selection, String[] args, String orderBy) {
        List<FlowerPot> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(TABLE_FLOWER_POTS, null, selection, args, null, null, orderBy)) {
            while (c.moveToNext()) {
                FlowerPot p = new FlowerPot();
                p.id = c.getLong(c.getColumnIndexOrThrow(COL_ID));
                p.name = c.getString(c.getColumnIndexOrThrow(COL_NAME));
                p.lat = c.getDouble(c.getColumnIndexOrThrow(COL_LAT));
                p.lng = c.getDouble(c.getColumnIndexOrThrow(COL_LNG));
                p.origLat = c.getDouble(c.getColumnIndexOrThrow(COL_ORIG_LAT));
                p.origLng = c.getDouble(c.getColumnIndexOrThrow(COL_ORIG_LNG));
                p.category = c.getString(c.getColumnIndexOrThrow(COL_CATEGORY));
                p.corrected = c.getInt(c.getColumnIndexOrThrow(COL_CORRECTED)) != 0;
                p.expiresAt = c.getLong(c.getColumnIndexOrThrow(COL_EXPIRES_AT));
                list.add(p);
            }
        }
        return list;
    }

    public static class FlowerPot {
        public long id;
        public String name;
        public double lat;
        public double lng;
        public double origLat;
        public double origLng;
        public String category;
        public boolean corrected;
        public long expiresAt;

        /** 菇點目前標記有大菇且尚未到期。 */
        public boolean hasBigMushroom() {
            return expiresAt > System.currentTimeMillis();
        }
    }
}
