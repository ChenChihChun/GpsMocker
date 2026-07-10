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
    private static final int DB_VERSION = 3;

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

    public static final String CATEGORY_PERMANENT = "permanent";
    public static final String CATEGORY_EVENT = "event";

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
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_NAME, name);
        cv.put(COL_LAT, lat);
        cv.put(COL_LNG, lng);
        cv.put(COL_ORIG_LAT, lat);
        cv.put(COL_ORIG_LNG, lng);
        cv.put(COL_CATEGORY, category);
        cv.put(COL_CORRECTED, 0);
        cv.put(COL_CREATED_AT, System.currentTimeMillis());
        return db.insert(TABLE_FLOWER_POTS, null, cv);
    }

    public void deleteFlowerPot(long id) {
        getWritableDatabase().delete(TABLE_FLOWER_POTS, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    public void deleteAllFlowerPots() {
        getWritableDatabase().delete(TABLE_FLOWER_POTS, null, null);
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

    /** 取得所有花盆：常駐在前、活動在後，各依建立時間排序。 */
    public List<FlowerPot> getAllFlowerPots() {
        List<FlowerPot> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        String orderBy = "CASE " + COL_CATEGORY + " WHEN '" + CATEGORY_PERMANENT
                + "' THEN 0 ELSE 1 END ASC, " + COL_CREATED_AT + " ASC";
        try (Cursor c = db.query(TABLE_FLOWER_POTS, null, null, null, null, null, orderBy)) {
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
    }
}
