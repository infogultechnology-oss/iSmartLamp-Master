package com.example.ismartlamp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.content.IntentFilter;
import android.net.ConnectivityManager;

@SuppressLint("CustomSplashScreen")
public class SplashActivity extends AppCompatActivity {

    private static final int REQUEST_ALL_PERMISSIONS = 101;
    private static final int REQUEST_ENABLE_BT = 102;
    private static final int REQUEST_ENABLE_LOCATION = 103;

    private ConnectivityReceiver connectivityReceiver;
    private boolean locationPrompted = false; // prevent multiple settings launches

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        //insertFakeSensorDataIfNeeded();

        new Handler().postDelayed(this::checkEverything, 1500);
    }

    private void checkEverything() {
        Log.d("SplashCheck", "Checking permissions and states...");

        boolean bluetoothEnabled = isBluetoothEnabled();
        boolean locationEnabled = isLocationEnabled();
        boolean permissionsGranted = hasAllPermissions();

        Log.d("SplashCheck", "Bluetooth: " + bluetoothEnabled);
        Log.d("SplashCheck", "Location: " + locationEnabled);
        Log.d("SplashCheck", "Permissions: " + permissionsGranted);

        if (!bluetoothEnabled) {
            requestBluetoothEnable();
            return; // wait for user action
        }

        if (!locationEnabled) {
            if (!locationPrompted) {
                locationPrompted = true;
                promptToEnableLocation();
            }
            return; // do not continue
        }

        if (!permissionsGranted) {
            requestAllPermissions();
            return;
        }

        // All checks passed
        goToHome();
    }


    private boolean isBluetoothEnabled() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }

    private void requestBluetoothEnable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    REQUEST_ALL_PERMISSIONS);
        } else {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }
    }

    private boolean isLocationEnabled() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        return lm != null && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
    }

    private void promptToEnableLocation() {
        new AlertDialog.Builder(this)
                .setTitle("Enable Location")
                .setMessage("Location services are required for Bluetooth scanning. Please enable them.")
                .setPositiveButton("Go to Settings", (dialog, which) -> {
                    Intent intent = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    startActivityForResult(intent, REQUEST_ENABLE_LOCATION);
                })
                .setNegativeButton("Exit", (dialog, which) -> finish())
                .setCancelable(false)
                .show();
    }

    private boolean hasAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    }, REQUEST_ALL_PERMISSIONS);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_ALL_PERMISSIONS);
        }
    }

    private void goToHome() {
        Log.d("SplashCheck", "All checks passed. Launching HomeActivity.");

        connectivityReceiver = new ConnectivityReceiver();
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(connectivityReceiver, filter);

        SensorDataSyncManager.syncData(this);

        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_ALL_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (!allGranted) {
                Toast.makeText(this, "Permissions are required for the app to function.", Toast.LENGTH_LONG).show();
            }

            new Handler().postDelayed(this::checkEverything, 500);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_ENABLE_BT || requestCode == REQUEST_ENABLE_LOCATION) {
            // Reset flag to allow re-check
            locationPrompted = false;
            new Handler().postDelayed(this::checkEverything, 500);
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        moveTaskToBack(true);
    }
}
