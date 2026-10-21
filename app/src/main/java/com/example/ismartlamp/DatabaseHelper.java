package com.example.ismartlamp;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "lamp_data.db";
    private static final int DB_VERSION = 5;

    private static final Map<String, Float> GAS_THRESHOLDS = new HashMap<String, Float>() {{
        put("O2", 29.5f);
        put("CO", 35.0f);
        put("H2S", 10.0f);
        put("CH4", 1000f);
        put("Temp", 17f);
        put("Hum", 80f);
    }};

    public DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sensor_data (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "lamp_id TEXT," +
                "lamp_name TEXT," +
                "h2s REAL," +
                "o2 REAL," +
                "co REAL," +
                "ch4 REAL," +
                "temperature REAL," +
                "humidity REAL," +
                "datetime TEXT," +
                "synced INTEGER DEFAULT 0," +
                "latitude REAL," +
                "longitude REAL" +
                ")");
    }



    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.d("OnUpgrade " , "On Upgrade Called");
        if (oldVersion < 5) {
            // Add latitude and longitude columns if they don’t exist yet
            db.execSQL("ALTER TABLE sensor_data ADD COLUMN latitude REAL;");
            db.execSQL("ALTER TABLE sensor_data ADD COLUMN longitude REAL;");
        }

    }

    public boolean insertSensorRecord(String lampId, String lampName, float h2s, float o2, float co, float ch4,
                                      float temp, float humidity, String datetime,
                                      double latitude, double longitude) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("lamp_id", lampId);
        values.put("lamp_name", lampName);
        values.put("h2s", h2s);
        values.put("o2", o2);
        values.put("co", co);
        values.put("ch4", ch4);
        values.put("temperature", temp);
        values.put("humidity", humidity);
        values.put("datetime", datetime);
        values.put("latitude", latitude);
        values.put("longitude", longitude);
        values.put("synced", 0); // Default is unsynced

        long result = db.insert("sensor_data", null, values);

        if (result != -1) {
            Log.d("DB", "🧾 Saved: " + lampId + ", " + lampName + ", " + h2s + ", " + o2 + ", " + co + ", " + ch4 + ", "
                    + temp + ", " + humidity + " at " + datetime + " | 📍 " + latitude + ", " + longitude);
        } else {
            Log.e("DB", "❌ Failed to insert record");
        }

        return result != -1;
    }


    public List<SensorRecord> getSensorData(String lampName) {
        List<SensorRecord> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        Cursor cursor = db.rawQuery("SELECT * FROM sensor_data WHERE lamp_name = ? ORDER BY datetime", new String[]{lampName});
        while (cursor.moveToNext()) {
            list.add(new SensorRecord(
                    cursor.getString(cursor.getColumnIndexOrThrow("lamp_name")),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("h2s")),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("o2")),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("co")),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("ch4")),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("temperature")),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("humidity")),
                    cursor.getString(cursor.getColumnIndexOrThrow("datetime")),
                    cursor.getString(cursor.getColumnIndexOrThrow("lamp_id")),
                    cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")),
                    cursor.getDouble(cursor.getColumnIndexOrThrow("longitude"))
            ));
        }


        cursor.close();
        return list;
    }

    public String getLastSyncedDate(String lampName) {
        SQLiteDatabase db = this.getReadableDatabase();
        String lastDate = null;

        Cursor cursor = db.rawQuery(
                "SELECT datetime FROM sensor_data WHERE lamp_name = ? ORDER BY datetime DESC LIMIT 1",
                new String[]{lampName}
        );

        if (cursor.moveToFirst()) {
            lastDate = cursor.getString(0);
        }

        cursor.close();
        return lastDate;
    }



    public List<SensorRecord> getSensorDataByDate(String lampName, String date) {
        List<SensorRecord> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        Cursor cursor = db.rawQuery(
                "SELECT * FROM sensor_data WHERE lamp_name = ? AND substr(datetime, 1, 10) = ? ORDER BY datetime",
                new String[]{lampName, date}
        );

        while (cursor.moveToNext()) {
            list.add(new SensorRecord(
                    cursor.getString(cursor.getColumnIndexOrThrow("lamp_name")),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("h2s")),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("o2")),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("co")),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("ch4")),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("temperature")),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("humidity")),
                    cursor.getString(cursor.getColumnIndexOrThrow("datetime")),
                    cursor.getString(cursor.getColumnIndexOrThrow("lamp_id")),
                    cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")),
                    cursor.getDouble(cursor.getColumnIndexOrThrow("longitude"))
            ));
        }


        cursor.close();
        return list;
    }

    public List<SensorRecord> getUnsyncedRecords() {
        List<SensorRecord> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM sensor_data WHERE synced = 0", null);

        while (cursor.moveToNext()) {
            list.add(new SensorRecord(
                    cursor.getString(cursor.getColumnIndexOrThrow("lamp_name")),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("h2s")),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("o2")),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("co")),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("ch4")),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("temperature")),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("humidity")),
                    cursor.getString(cursor.getColumnIndexOrThrow("datetime")),
                    cursor.getString(cursor.getColumnIndexOrThrow("lamp_id")),
                    cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")),
                    cursor.getDouble(cursor.getColumnIndexOrThrow("longitude"))
            ));
        }


        cursor.close();
        return list;
    }

    public void markRecordsAsSynced(List<SensorRecord> records) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            for (SensorRecord record : records) {
                db.execSQL("UPDATE sensor_data SET synced = 1 WHERE lamp_name = ? AND datetime = ?",
                        new Object[]{record.getLampName(), record.getDatetime()});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void removeDuplicateRecords() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.execSQL("DELETE FROM sensor_data WHERE id NOT IN (" +
                "SELECT MIN(id) FROM sensor_data GROUP BY lamp_name, datetime)");
        db.close();
    }

    public List<String> getAllLampNames() {
        List<String> lampNames = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT DISTINCT lamp_name FROM sensor_data", null);

        if (cursor.moveToFirst()) {
            do {
                lampNames.add(cursor.getString(0));
            } while (cursor.moveToNext());
        }

        cursor.close();
        db.close();
        return lampNames;
    }

    public String getLatestDangerousTimestamp(String lampName) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT datetime FROM sensor_data WHERE lamp_name = ? AND " +
                        "(h2s > ? OR o2 < ? OR co > ? OR ch4 > ? OR temperature > ? OR humidity > ?) " +
                        "ORDER BY datetime DESC LIMIT 1",
                new String[]{
                        lampName,
                        String.valueOf(GAS_THRESHOLDS.get("H2S")),
                        String.valueOf(GAS_THRESHOLDS.get("O2")),
                        String.valueOf(GAS_THRESHOLDS.get("CO")),
                        String.valueOf(GAS_THRESHOLDS.get("CH4")),
                        String.valueOf(GAS_THRESHOLDS.get("Temp")),
                        String.valueOf(GAS_THRESHOLDS.get("Hum"))
                }
        );

        String result = null;
        if (cursor.moveToFirst()) {
            result = cursor.getString(0);
        }
        cursor.close();
        return result;
    }

    public void deleteDataForLampOnDate(String lampName, String date) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete("sensor_data", "lamp_name = ? AND substr(datetime, 1, 10) = ?", new String[]{lampName, date});
    }

    public List<String> getDatesForLamp(String lampName) {
        List<String> dates = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();

        Cursor cursor = db.rawQuery(
                "SELECT DISTINCT substr(datetime, 1, 10) AS date " +
                        "FROM sensor_data WHERE lamp_name = ? ORDER BY date ASC",
                new String[]{lampName}
        );

        if (cursor.moveToFirst()) {
            do {
                dates.add(cursor.getString(0));
            } while (cursor.moveToNext());
        }

        cursor.close();
        db.close();
        return dates;
    }

    public SQLiteDatabase getWritable() {
        return this.getWritableDatabase();
    }
}
