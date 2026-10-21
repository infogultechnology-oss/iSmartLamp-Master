package com.example.ismartlamp;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class SensorSyncWorker extends Worker {

    public SensorSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d("SensorSyncWorker", "🔁 Worker started for data sync");

        long lastSync = getApplicationContext()
                .getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                .getLong("last_sync_time", 0);
        long now = System.currentTimeMillis() / 1000;

        if ((now - lastSync) >= 86400) { // 1 day
            SensorDataSyncManager.syncData(getApplicationContext());
            Log.d("SensorSyncWorker", "✅ Sync triggered");
        } else {
            Log.d("SensorSyncWorker", "⏱️ Skipping sync: last sync < 1 day ago");
        }

        return Result.success();
    }
}
