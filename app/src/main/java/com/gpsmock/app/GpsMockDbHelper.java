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
    private static final int DB_VERSION = 1;

    public static final String TABLE_PRESETS = "presets";
    public static final String COL_ID = "id";
    public static final String COL_NAME = "name";
    public static final String COL_LAT = "lat";
    public static final String COL_LNG = "lng";
    public static final String COL_CREATED_AT = "created_at";

    public static final String TABLE_SETTINGS = "settings";
    public static final String COL_KEY = "key";
    public static final String COL_VALUE = "value";

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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_PRESETS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_SETTINGS);
        onCreate(db);
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
        Cursor c = db.query(TABLE_PRESETS, null, null, null, null, null, COL_CREATED_AT + " DESC");
        while (c.moveToNext()) {
            Preset p = new Preset();
            p.id = c.getLong(c.getColumnIndexOrThrow(COL_ID));
            p.name = c.getString(c.getColumnIndexOrThrow(COL_NAME));
            p.lat = c.getDouble(c.getColumnIndexOrThrow(COL_LAT));
            p.lng = c.getDouble(c.getColumnIndexOrThrow(COL_LNG));
            list.add(p);
        }
        c.close();
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
        Cursor c = db.query(TABLE_SETTINGS, new String[]{COL_VALUE},
                COL_KEY + "=?", new String[]{key}, null, null, null);
        String result = defaultValue;
        if (c.moveToFirst()) {
            result = c.getString(0);
        }
        c.close();
        return result;
    }

    public static class Preset {
        public long id;
        public String name;
        public double lat;
        public double lng;
    }
}
