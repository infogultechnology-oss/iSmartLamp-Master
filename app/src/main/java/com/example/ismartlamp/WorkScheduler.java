package com.example.ismartlamp;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public class WorkScheduler {

    public static void scheduleDailySync(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest dailyRequest = new PeriodicWorkRequest.Builder(
                SensorSyncWorker.class,
                1, TimeUnit.DAYS
        ).setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "DailySensorDataSync",
                ExistingPeriodicWorkPolicy.KEEP,
                dailyRequest
        );
    }
}
