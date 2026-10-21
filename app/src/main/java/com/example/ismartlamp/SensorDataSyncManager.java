package com.example.ismartlamp;

import android.content.Context;
import android.util.Log;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class SensorDataSyncManager {

    private static final String TAG = "SyncDebug";
    private static final String SERVER_URL = "https://gulltechnology.com/dashboard/sensor_data.php";

    public static void syncData(Context context) {
        Log.d(TAG, "🔄 syncData() called");

        DatabaseHelper dbHelper = new DatabaseHelper(context);
        List<SensorRecord> records = dbHelper.getUnsyncedRecords();

        if (records.isEmpty()) {
            Log.d(TAG, "✅ No unsynced records found.");
            return;
        }
        else {
            Log.d(TAG, "✅ unsynced records found.");
        }

        // Get secret and mobile_user_id from shared preferences
        String secret = context.getSharedPreferences("qr_info", Context.MODE_PRIVATE).getString("secret", null);
        String uid = context.getSharedPreferences("qr_info", Context.MODE_PRIVATE).getString("uid", null);

        if (secret == null || uid == null) {
            Log.w(TAG, "❌ Cannot sync: missing secret or UID.");
            return;
        }

        String otp = generateTOTP(secret);
        Log.d(TAG, "🔐 OTP: " + otp);

        JSONArray sensorArray = new JSONArray();
        for (SensorRecord record : records) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("lamp_id", record.getLampId());
                obj.put("lamp_name", record.getLampName());
                obj.put("h2s", record.getH2s());
                obj.put("o2", record.getO2());
                obj.put("co", record.getCo());
                obj.put("ch4", record.getCh4());
                obj.put("temperature", record.getTemp());
                obj.put("humidity", record.getHum());
                obj.put("datetime", record.getDatetime());

                // ✅ Add these lines:
                obj.put("latitude", record.getLatitude());
                obj.put("longitude", record.getLongitude());

                sensorArray.put(obj);
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to build JSON: " + e.getMessage());
            }
        }


        JSONObject payload = new JSONObject();
        try {
            payload.put("otp", otp);
            payload.put("mobile_user_id", Integer.parseInt(uid));
            payload.put("sensor_data", sensorArray);
        } catch (Exception e) {
            Log.e(TAG, "❌ Payload creation failed: " + e.getMessage());
            return;
        }

        RequestQueue queue = Volley.newRequestQueue(context);

        JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, SERVER_URL, payload,
                response -> {
                    Log.d(TAG, "✅ Server responded: " + response.toString());
                    try {
                        if (response.has("status") && response.getString("status").equalsIgnoreCase("success")) {
                            dbHelper.markRecordsAsSynced(records);
                            Log.d(TAG, "📁 Marked " + records.size() + " records as synced.");
                            // Save sync timestamp
                            context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                                    .edit()
                                    .putLong("last_sync_time", System.currentTimeMillis() / 1000)
                                    .apply();
                        } else {
                            Log.w(TAG, "⚠️ Sync failed on server side: " + response.optString("message"));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "❌ Error parsing response: " + e.getMessage());
                    }
                },
                error -> Log.e(TAG, "❌ Sync request error: " + error.toString())
        );

        queue.add(request);
    }

    // TOTP Generator
    private static String generateTOTP(String base32Secret) {
        try {
            byte[] key = base32Decode(base32Secret);
            long timeStep = System.currentTimeMillis() / 1000 / 86400; // 1-day interval
            byte[] data = new byte[8];
            for (int i = 7; i >= 0; i--) {
                data[i] = (byte) (timeStep & 0xFF);
                timeStep >>= 8;
            }

            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0xF;

            int binary = ((hash[offset] & 0x7F) << 24) |
                    ((hash[offset + 1] & 0xFF) << 16) |
                    ((hash[offset + 2] & 0xFF) << 8) |
                    (hash[offset + 3] & 0xFF);

            int otp = binary % 1000000;
            return String.format("%06d", otp);

        } catch (Exception e) {
            Log.e(TAG, "❌ TOTP generation failed: " + e.getMessage());
            return "000000";
        }
    }

    private static byte[] base32Decode(String base32) {
        String base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        base32 = base32.replace("=", "").toUpperCase();

        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        int buffer = 0, bitsLeft = 0;

        for (char c : base32.toCharArray()) {
            int val = base32Chars.indexOf(c);
            if (val < 0) continue;
            buffer <<= 5;
            buffer |= val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                byteStream.write((buffer >> bitsLeft) & 0xFF);
            }
        }

        return byteStream.toByteArray();
    }
}
