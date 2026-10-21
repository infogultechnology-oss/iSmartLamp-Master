package com.example.ismartlamp;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;

import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import android.location.Location;


public class HomeFragment extends Fragment {

    private boolean isTimeout = false;
    private static final int REQUEST_BT_PERMISSIONS = 100;
    private static final String PREF_LAMPS = "paired_lamps";

    private View view;
    private LinearLayout pairedDevicesContainer, availableDevicesContainer;
    private TextView noAvailableDevicesText;
    private ProgressBar progressBar;
    private FloatingActionButton scanButton;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private ScanCallback scanCallback;

    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;

    private static final UUID WRITE_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    private static final UUID READ_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");

    private final ArrayList<BluetoothDevice> availableDevicesList = new ArrayList<>();
    // FIXED: was a plain HashMap — written from BLE scan callback threads and
    // read from the UI thread, which could throw ConcurrentModificationException.
    private final Map<String, Integer> availableDevicesMap = Collections.synchronizedMap(new HashMap<>());
    private final ArrayList<LampModel> pairedLamps = new ArrayList<>();
    // FIXED: was a plain ArrayList — appended to from the BLE callback thread
    // while potentially read/cleared from a timeout on the main thread.
    private final List<byte[]> chunkList = Collections.synchronizedList(new ArrayList<>());
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());

    // FIXED: new guard so processReceivedChunks() can't run twice for the same
    // transfer if both the "end chunk received" path and the timeout path fire
    // close together (previously this could double-save the same records).
    private final AtomicBoolean transferProcessed = new AtomicBoolean(false);

    private AlertDialog receivingDialog;
    private String currentLampName = "";
    private int expectedRecords = 0;
    private int receivedRecords = 0;

    private LocationManager locationManager;
    private boolean locationFetched = false;
    private Double currentLatitude = 0.0;
    private Double currentLongitude = 0.0;

    private DatabaseHelper dbHelper;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_home, container, false);

        pairedDevicesContainer = view.findViewById(R.id.pairedDevicesContainer);
        availableDevicesContainer = view.findViewById(R.id.availableDevicesContainer);
        noAvailableDevicesText = view.findViewById(R.id.noAvailableDevicesText);
        progressBar = view.findViewById(R.id.progressBar);
        scanButton = view.findViewById(R.id.scanButton);

        dbHelper = new DatabaseHelper(requireContext());

        BluetoothManager manager = (BluetoothManager) requireContext().getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager.getAdapter();
        if (bluetoothAdapter != null) {
            bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        }

        ImageView infoPaired = view.findViewById(R.id.infoPairedDevices);
        ImageView infoAvailable = view.findViewById(R.id.infoAvailableDevices);

        infoPaired.setOnClickListener(v -> {
            View markerView = LayoutInflater.from(requireContext()).inflate(R.layout.info_marker, null);
            PopupWindow popup = new PopupWindow(markerView,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, true);

            popup.setOutsideTouchable(true);
            popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            popup.showAsDropDown(v, 0, 10);
        });

        infoAvailable.setOnClickListener(v -> {
            View markerView = LayoutInflater.from(requireContext()).inflate(R.layout.info_marker, null);
            TextView markerText = markerView.findViewById(R.id.markerText);
            markerText.setText("This section lists nearby helmet devices you can connect to.");

            PopupWindow popup = new PopupWindow(markerView,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, true);

            popup.setOutsideTouchable(true);
            popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            popup.showAsDropDown(v, 0, 10);
        });


        scanButton.setOnClickListener(v -> {
            if (!isBluetoothEnabled()) {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                return;
            }
            if (!hasPermissions()) {
                requestPermissions();
                return;
            }
            // FIXED: guard against a null scanner (e.g. device has no BLE hardware),
            // which previously would NPE on bleScanner.startScan(...) below.
            if (bleScanner == null) {
                Toast.makeText(requireContext(), "Bluetooth LE is not available on this device", Toast.LENGTH_SHORT).show();
                return;
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) { // Android 11 and below
                LocationManager lm = (LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);
                if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    Toast.makeText(requireContext(), "⚠️ Please enable Location (GPS) to scan devices", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            startScan();
        });


        loadPairedLamps();

        return view;
    }

    private void fetchCurrentLocation() {
        if (!isAdded()) return;
        locationManager = (LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);

        locationFetched = false;

        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(), "❌ Location permission not granted", Toast.LENGTH_SHORT).show();
            return;
        }

        Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (location == null) {
            location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        }

        if (location != null) {
            currentLatitude = location.getLatitude();
            currentLongitude = location.getLongitude();
            locationFetched = true;
            Log.d("LOCATION", "✅ Location fetched: " + currentLatitude + ", " + currentLongitude);
        } else {
            currentLatitude = 0.0;
            currentLongitude = 0.0;
            Toast.makeText(requireContext(), "⚠️ Unable to fetch SmartLamp location. Using 0.0", Toast.LENGTH_LONG).show();
            Log.w("LOCATION", "📍 Location is null, fallback to 0.0");
        }
    }


    private void refreshAvailableDevices() {
        if (!isAdded()) return;
        availableDevicesContainer.removeAllViews();

        for (BluetoothDevice device : availableDevicesList) {
            addAvailableDevice(device);
        }
    }




    private boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }


    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            }, REQUEST_BT_PERMISSIONS);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, REQUEST_BT_PERMISSIONS);
        }
    }


    private void startScan() {
        if (!isAdded() || bleScanner == null) return;

        progressBar.setVisibility(View.VISIBLE);
        availableDevicesContainer.removeAllViews();
        availableDevicesList.clear();
        availableDevicesMap.clear();

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, @NonNull ScanResult result) {
                // Safety: skip if fragment is not attached
                if (!isAdded() || getActivity() == null) {
                    Log.w("BLE", "⚠️ Fragment not attached — skipping onScanResult");
                    return;
                }

                BluetoothDevice device = result.getDevice();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) {
                        Log.w("BLE", "❌ BLUETOOTH_CONNECT permission not granted, skipping scan result");
                        return;
                    }
                }

                if (device != null && device.getName() != null) {
                    availableDevicesMap.put(device.getAddress(), result.getRssi());

                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        if (!isDeviceAlreadyPaired(device) && !availableDevicesList.contains(device)) {
                            availableDevicesList.add(device);
                        }
                    });
                }
            }


            @Override
            public void onBatchScanResults(@NonNull List<ScanResult> results) {
                if (!isAdded()) return;
                for (ScanResult result : results) {
                    BluetoothDevice device = result.getDevice();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
                                != PackageManager.PERMISSION_GRANTED) {
                            return;
                        }
                    }
                    if (device != null && device.getName() != null && !isDeviceAlreadyPaired(device)) {
                        availableDevicesMap.put(device.getAddress(), result.getRssi());
                        if (!availableDevicesList.contains(device)) {
                            availableDevicesList.add(device);
                        }
                    }
                }
            }

            // FIXED: was missing entirely — scan failures were silently ignored.
            @Override
            public void onScanFailed(int errorCode) {
                Log.e("BLE", "❌ Scan failed with error code: " + errorCode);
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(requireContext(), "BLE scan failed (code " + errorCode + ")", Toast.LENGTH_SHORT).show();
                });
            }
        };

        bleScanner.startScan(scanCallback);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isAdded()) {
                Log.w("BLE", "⚠️ Scan finished but fragment not attached, skipping UI update");
                return;
            }

            stopBleScanSafely();
            progressBar.setVisibility(View.GONE);

            refreshAvailableDevices();
            refreshPairedDevices();

            if (availableDevicesList.isEmpty()) {
                noAvailableDevicesText.setVisibility(View.VISIBLE);
            } else {
                noAvailableDevicesText.setVisibility(View.GONE);
            }
        }, 5000);
    }


    private void addAvailableDevice(BluetoothDevice device) {
        if (!isAdded()) return;

        View item = LayoutInflater.from(requireContext()).inflate(R.layout.item_device, availableDevicesContainer, false);

        TextView name = item.findViewById(R.id.deviceName);
        TextView rssiText = item.findViewById(R.id.deviceRssi);
        ImageView greenDot = item.findViewById(R.id.greenDot);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w("BLE", "❌ BLUETOOTH_CONNECT permission not granted, skipping scan result");
                return;
            }
        }

        Integer rssi = availableDevicesMap.get(device.getAddress());
        name.setText(device.getName());
        rssiText.setText("RSSI: " + (rssi != null ? rssi : "--"));

        greenDot.setVisibility(View.VISIBLE);

        item.setOnClickListener(v -> {
            boolean alreadyPaired = false;
            for (LampModel lamp : pairedLamps) {
                if (lamp.mac.equals(device.getAddress())) {
                    alreadyPaired = true;
                    break;
                }
            }

            if (!alreadyPaired) {
                pairedLamps.add(new LampModel(device.getAddress(), device.getName()));
                savePairedLamps();
                refreshPairedDevices();

                availableDevicesList.remove(device);
                availableDevicesContainer.removeView(item);

                Log.d("BLE", "🔁 Removed " + device.getName() + " from available devices after pairing");
            } else {
                Toast.makeText(requireContext(), "⚠️ Already added", Toast.LENGTH_SHORT).show();
            }
        });

        availableDevicesContainer.addView(item);
    }


    private boolean isDeviceAlreadyPaired(BluetoothDevice device) {
        for (LampModel lamp : pairedLamps) {
            if (lamp.mac.equals(device.getAddress())) return true;
        }
        return false;
    }

    private void loadPairedLamps() {
        SharedPreferences prefs = requireContext().getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE);
        String json = prefs.getString(PREF_LAMPS, "[]");
        pairedLamps.clear();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                pairedLamps.add(new LampModel(obj.getString("mac"), obj.getString("name")));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        refreshPairedDevices();
    }

    private void savePairedLamps() {
        SharedPreferences prefs = requireContext().getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE);
        JSONArray arr = new JSONArray();
        for (LampModel lamp : pairedLamps) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("mac", lamp.mac);
                obj.put("name", lamp.name);
                arr.put(obj);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        prefs.edit().putString(PREF_LAMPS, arr.toString()).apply();
    }

    private void refreshPairedDevices() {

        Context context = getContext();
        if (context == null) {
            Log.w("BLE", "⚠️ Skipping refreshPairedDevices — fragment not attached");
            return;
        }
        pairedDevicesContainer.removeAllViews();

        for (LampModel lamp : pairedLamps) {
            View item = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_device, pairedDevicesContainer, false);

            TextView name = item.findViewById(R.id.deviceName);
            TextView rssiText = item.findViewById(R.id.deviceRssi);
            ImageView greenDot = item.findViewById(R.id.greenDot);
            ImageButton editButton = item.findViewById(R.id.renameButton);

            name.setText(lamp.name);

            Integer rssi = availableDevicesMap.get(lamp.mac);
            if (rssi != null) {
                rssiText.setText("RSSI: " + rssi);
                greenDot.setVisibility(View.VISIBLE);
            } else {
                rssiText.setText("Offline");
                greenDot.setVisibility(View.GONE);
            }

            editButton.setOnClickListener(v -> showRenameDialog(lamp));

            item.setOnClickListener(v -> {
                if (!isAdded() || bleScanner == null) return;
                progressBar.setVisibility(View.VISIBLE);
                final boolean[] deviceFound = {false};

                scanCallback = new ScanCallback() {
                    @Override
                    public void onScanResult(int callbackType, @NonNull ScanResult result) {
                        if (!isAdded()) return;
                        BluetoothDevice device = result.getDevice();

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
                                    != PackageManager.PERMISSION_GRANTED) {
                                return;
                            }
                        }

                        if (device != null && lamp.mac.equals(device.getAddress())) {
                            deviceFound[0] = true;
                            stopBleScanSafely();
                            progressBar.setVisibility(View.GONE);
                            showFetchHoursDialog(lamp);
                        }
                    }

                    @Override
                    public void onScanFailed(int errorCode) {
                        if (!isAdded()) return;
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(requireContext(), "BLE scan failed", Toast.LENGTH_SHORT).show();
                    }
                };

                bleScanner.startScan(scanCallback);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (!isAdded()) return;
                    stopBleScanSafely();
                    progressBar.setVisibility(View.GONE);
                    if (!deviceFound[0]) {
                        Toast.makeText(requireContext(),
                                "Device is out of range or not available",
                                Toast.LENGTH_SHORT).show();
                    }
                }, 5000);
            });

            item.setOnLongClickListener(v -> {
                showDeleteDialog(lamp);
                return true;
            });

            pairedDevicesContainer.addView(item);
        }
    }



    private void showDeleteDialog(LampModel lamp) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Forget Lamp")
                .setMessage("Remove this lamp from paired list?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    pairedLamps.remove(lamp);
                    savePairedLamps();
                    refreshPairedDevices();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }


    private void showRenameDialog(LampModel lamp) {
        EditText input = new EditText(requireContext());
        input.setText(lamp.name);

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Rename Lamp")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    lamp.name = input.getText().toString();
                    savePairedLamps();
                    refreshPairedDevices();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }


    private void showFetchHoursDialog(LampModel lamp) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_fetch_hours, null);
        Spinner spinner = dialogView.findViewById(R.id.hourSpinner);

        String[] options = {"1 hour", "2 hours", "4 hours", "8 hours", "12 hours", "24 hours"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Fetch Data")
                .setView(dialogView)
                .setPositiveButton("Fetch", (dialog, which) -> {
                    String selected = spinner.getSelectedItem().toString();
                    int hours = Integer.parseInt(selected.split(" ")[0]);
                    fetchDataFromLamp(lamp, hours);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }


    private void fetchDataFromLamp(LampModel lamp, int hours) {
        int records = hours * 30;
        currentLampName = lamp.name;
        // FIXED: reset transfer state at the start of every fetch attempt.
        transferProcessed.set(false);
        chunkList.clear();

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(lamp.mac);

        if (device == null) {
            Toast.makeText(requireContext(), "Device not found", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(requireContext(), "Connecting to " + lamp.name + " to fetch data...", Toast.LENGTH_SHORT).show();

        fetchCurrentLocation();
        connectToLamp(device, records);
    }

    private void connectToLamp(BluetoothDevice device, int recordsToFetch) {
        if (!isAdded()) return;
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e("BLE", "❌ BLUETOOTH_CONNECT permission not granted");
            return;
        }

        bluetoothGatt = device.connectGatt(requireContext(), false, new BluetoothGattCallback() {

            // FIXED: every callback below now starts with an isAdded()/getActivity()
            // check. These run on a BLE binder thread independent of the Fragment's
            // lifecycle — if the user navigated away mid-transfer, calling
            // requireContext()/getActivity() here previously could NPE or throw
            // IllegalStateException.

            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (!isAdded() || getActivity() == null) return;

                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    Log.d("BLE", "✅ Connected to GATT server");
                    if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED) {
                        gatt.discoverServices();
                    } else {
                        Log.e("Permission", "BLUETOOTH_CONNECT permission not granted");
                    }

                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    Log.d("BLE", "❌ Disconnected from GATT server");
                    requireActivity().runOnUiThread(() -> {
                        if (isAdded()) {
                            Toast.makeText(requireContext(), "Disconnected from device", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (!isAdded() || getActivity() == null) return;

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w("BLE", "❌ Service discovery failed with status: " + status);
                    return;
                }

                writeCharacteristic = null;
                notifyCharacteristic = null;

                for (BluetoothGattService service : gatt.getServices()) {
                    for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                        UUID uuid = characteristic.getUuid();
                        int props = characteristic.getProperties();

                        if (uuid.equals(WRITE_UUID)) {
                            writeCharacteristic = characteristic;
                        }
                        if (uuid.equals(READ_UUID)) {
                            notifyCharacteristic = characteristic;
                        }

                        if (writeCharacteristic == null && (props & BluetoothGattCharacteristic.PROPERTY_WRITE) > 0) {
                            writeCharacteristic = characteristic;
                        }
                        if (notifyCharacteristic == null && (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                            notifyCharacteristic = characteristic;
                        }
                    }
                }

                if (writeCharacteristic != null && notifyCharacteristic != null) {
                    // FIXED: previously the fetch command was written here AND
                    // again in onDescriptorWrite below — sending it twice caused
                    // duplicate/overlapping data transfers. Now only notifications
                    // are enabled here; the command is sent once, in
                    // onDescriptorWrite, after the descriptor write is confirmed.
                    enableNotifications(gatt, notifyCharacteristic);
                } else {
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(), "❌ Could not find required characteristics", Toast.LENGTH_SHORT).show()
                    );
                }
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                if (!isAdded()) return;

                byte[] data = characteristic.getValue();

                if (isEndChunk(data)) {
                    Log.d("BLE", "✅ End chunk received. Total received: " + chunkList.size());
                    isTimeout = false;
                    finishTransfer();
                    return;
                }

                chunkList.add(data);
            }

            private boolean isEndChunk(byte[] data) {
                return data.length >= 5 &&
                        (data[0] & 0xFF) == 0xFF &&
                        (data[1] & 0xFF) == 0x14 &&
                        (data[2] & 0xFF) == 0xB1 &&
                        (data[3] & 0xFF) == 0xFF &&
                        (data[4] & 0xFF) == 0xFF;
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                if (!isAdded() || getActivity() == null) return;

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d("BLE", "✅ Descriptor write confirmed");

                    byte[] command = new byte[]{(byte) 0xA5, (byte) 0xFF, (byte) 0xA1, 0x00, (byte) recordsToFetch};
                    Log.d("BLE", "📤 Sending command: " + bytesToHex(command));

                    writeCharacteristic.setValue(command);

                    if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        Log.e("BLE", "Missing BLUETOOTH_CONNECT permission");
                        return;
                    }

                    gatt.writeCharacteristic(writeCharacteristic);

                    requireActivity().runOnUiThread(() -> {
                        if (isAdded()) showReceivingDialog();
                    });
                    expectedRecords = recordsToFetch;
                    receivedRecords = 0;
                    transferProcessed.set(false);

                    // FIXED: was two separate timers (30s here originally, plus a
                    // duplicate 50s one from the old duplicate-write path). Now
                    // there is exactly one 30s timeout.
                    timeoutHandler.removeCallbacksAndMessages(null);
                    timeoutHandler.postDelayed(() -> {
                        if (!isAdded() || getActivity() == null) {
                            Log.w("BLE", "⚠️ Skipping timeout UI update — fragment not attached");
                            return;
                        }
                        isTimeout = true;
                        if (receivingDialog != null && receivingDialog.isShowing()) {
                            receivingDialog.dismiss();
                            Toast.makeText(requireContext(), "⏰ Timeout: Incomplete data received", Toast.LENGTH_LONG).show();
                        }
                        finishTransfer();
                    }, 30000);
                } else {
                    Log.e("BLE", "❌ Descriptor write failed with status: " + status);
                }
            }

            // FIXED: new helper — ensures processReceivedChunks() runs exactly
            // once per transfer, whether triggered by the end-chunk marker or
            // the timeout, instead of both potentially firing and double-saving.
            private void finishTransfer() {
                if (transferProcessed.compareAndSet(false, true)) {
                    timeoutHandler.removeCallbacksAndMessages(null);
                    List<byte[]> snapshot;
                    synchronized (chunkList) {
                        snapshot = new ArrayList<>(chunkList);
                    }
                    processReceivedChunks(snapshot);
                }
            }
        });
    }




    private void enableNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (!isAdded()) return;
        boolean success = false;
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
            success = gatt.setCharacteristicNotification(characteristic, true);
            Log.d("BLE", "🔄 setCharacteristicNotification: " + success);
        } else {
            requestPermissions(
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    REQUEST_BT_PERMISSIONS
            );
            return;
        }

        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean writeSuccess = gatt.writeDescriptor(descriptor);
            Log.d("BLE", "✅ Notification descriptor written: " + descriptor.getUuid() + " | Success: " + writeSuccess);
        } else {
            Log.e("BLE", "❌  Descriptor not found for notifications");
        }
    }

    private void showReceivingDialog() {
        if (!isAdded()) return;
        if (receivingDialog != null && receivingDialog.isShowing()) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Receiving Data")
                .setMessage("Please wait while data is being received...")
                .setCancelable(false);
        receivingDialog = builder.create();
        receivingDialog.show();
    }



    private SensorRecord parseRecord(byte[] data, String lampName) {
        try {
            int dtIndex = -1;
            for (int i = 0; i < data.length - 7; i++) {
                int possibleYear = ((data[i + 1] & 0xFF) << 8) | (data[i] & 0xFF);
                if (possibleYear >= 2022 && possibleYear <= 2027) {
                    dtIndex = i;
                    break;
                }
            }

            if (dtIndex == -1) {
                Log.e("BLE", "❌ Date signature not found in data.");
                return null;
            }

            byte[] dt = Arrays.copyOfRange(data, dtIndex, dtIndex + 7);

            int year   = ((dt[1] & 0xFF) << 8) | (dt[0] & 0xFF);
            int month  = dt[2] & 0xFF;
            int day    = dt[3] & 0xFF;
            int hour   = dt[4] & 0xFF;
            int minute = dt[5] & 0xFF;
            int second = dt[6] & 0xFF;

            if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 59) {
                Log.e("BLE", "❌ Invalid timestamp values");
                return null;
            }

            String timestamp = String.format("%04d-%02d-%02d %02d:%02d:%02d",
                    year, month, day, hour, minute, second);

            int offset = 10;
            // FIXED: guard against a short/truncated byte array before reading
            // sensor floats — previously this could throw IndexOutOfBoundsException.
            if (data.length < offset + 24) {
                Log.e("BLE", "❌ Record too short to contain sensor values");
                return null;
            }

            // FIXED (round 2): H2S and CH4 were also swapped. The device's
            // actual sensor order matches its own screen layout: CH4, CO, O2,
            // H2S, Temp, Hum. The first fix correctly resolved CO/O2 (they
            // were positions 2 and 3), but H2S (position 4) and CH4
            // (position 1) were still swapped. Verify with a fresh
            // side-by-side test against the handheld device before relying
            // on this for safety decisions.
            float ch4  = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
            float co   = ByteBuffer.wrap(data, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
            float o2   = ByteBuffer.wrap(data, offset + 8, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
            float h2s  = ByteBuffer.wrap(data, offset + 12, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
            float temp = ByteBuffer.wrap(data, offset + 16, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
            float hum  = ByteBuffer.wrap(data, offset + 20, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();

            if (h2s == 0.0f && o2 < 0.01f && co == 0.0f && ch4 == 0.0f && temp == 0.0f && hum == 0.0f) {
                Log.w("BLE", "⚠️ Ignored zombie record (all sensor values are 0 or invalid O2)");
                return null;
            }

            String lampId = getLampIdFromName(lampName);
            double latitude = currentLatitude != null ? currentLatitude : 0.0;
            double longitude = currentLongitude != null ? currentLongitude : 0.0;

            return new SensorRecord(lampName, h2s, o2, co, ch4, temp, hum, timestamp, lampId, latitude, longitude);

        } catch (Exception e) {
            Log.e("BLE", "❌ Failed to parse record: " + e.getMessage(), e);
            return null;
        }
    }

    private void processReceivedChunks(List<byte[]> chunkList) {
        List<SensorRecord> parsedRecords = new ArrayList<>();
        int leftover = chunkList.size() % 3;

        if (leftover != 0) {
            Log.e("BLE", "⚠️ Incomplete record data. Dropping last " + leftover + " chunks.");
        }

        for (int i = 0; i <= chunkList.size() - 3; i += 3) {
            ByteArrayOutputStream recordStream = new ByteArrayOutputStream();
            try {
                recordStream.write(chunkList.get(i));
                recordStream.write(chunkList.get(i + 1));
                recordStream.write(chunkList.get(i + 2));
                byte[] fullRecord = recordStream.toByteArray();

                SensorRecord parsed = parseRecord(fullRecord, currentLampName);
                if (parsed != null) {
                    parsedRecords.add(parsed);
                }
            } catch (IOException e) {
                Log.e("BLE", "❌ Failed to combine chunks into record", e);
            }
        }

        int saved = 0;
        for (SensorRecord record : parsedRecords) {
            String lampId = getLampIdFromName(record.getLampName());
            boolean ok = dbHelper.insertSensorRecord(
                    lampId,
                    record.getLampName(),
                    record.getH2s(),
                    record.getO2(),
                    record.getCo(),
                    record.getCh4(),
                    record.getTemp(),
                    record.getHum(),
                    record.getDatetime(),
                    currentLatitude,
                    currentLongitude
            );
            if (ok) saved++;
        }

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.execSQL("DELETE FROM sensor_data WHERE id NOT IN (" +
                "SELECT MIN(id) FROM sensor_data GROUP BY lamp_name, datetime)");
        Log.d("DB", "🧹 Duplicate records deleted");

        final int finalSaved = saved;
        FragmentActivity activity = getActivity();
        if (activity != null && isAdded()) {
            activity.runOnUiThread(() -> {
                if (receivingDialog != null && receivingDialog.isShowing()) {
                    receivingDialog.dismiss();
                }

                if (finalSaved == 0) {
                    Toast.makeText(requireContext(), "❌ No data saved. Incomplete or corrupt transmission.", Toast.LENGTH_LONG).show();
                } else if (isTimeout) {
                    Toast.makeText(requireContext(), "⚠️ Incomplete data received — " + finalSaved + " records saved", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(requireContext(), "✅ Data saved (" + finalSaved + " records)", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            Log.e("BLE", "❌ Cannot update UI: Fragment is not attached or activity is null.");
        }

        timeoutHandler.removeCallbacksAndMessages(null);
        disconnectGatt();
        chunkList.clear();
        isTimeout = false;
    }




    private void disconnectGatt() {
        if (bluetoothGatt != null) {
            Context context = getContext();
            if (context != null && ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } else {
                Log.w("BLE", "❌ Missing permission: Cannot disconnect/close GATT");
            }

            bluetoothGatt = null;
            Log.d("BLE", "Bluetooth GATT disconnected and closed.");
        }
    }


    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }


    private String getLampIdFromName(String lampName) {
        for (LampModel lamp : pairedLamps) {
            if (lamp.name.equals(lampName)) {
                return lamp.mac;
            }
        }
        return "unknown";
    }


    private boolean isLocationEnabled() {
        LocationManager lm = (LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);
        return lm != null && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
    }


    @Override
    public void onResume() {
        super.onResume();
        Log.d("BLE", "🔄 HomeFragment resumed, refreshing paired devices...");
        refreshPairedDevices();

        if (!isBluetoothEnabled()) {
            Log.w("BLE", "❌ Bluetooth is not enabled");
            return;
        }

        if (!hasPermissions()) {
            Log.w("BLE", "❌ Required permissions not granted");
            Toast.makeText(getContext(), "Bluetooth or Location permissions missing", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isLocationEnabled()) {
            Log.w("BLE", "❌ Location services OFF (required for BLE scanning on Android 10/11)");
            Toast.makeText(getContext(), "Enable Location Services to scan BLE devices", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d("BLE", "✅ All checks passed. Starting BLE scan...");
        startScan();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopBleScanSafely();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopBleScanSafely();
        timeoutHandler.removeCallbacksAndMessages(null);
        disconnectGatt();
    }

    private void stopBleScanSafely() {
        if (bleScanner != null && scanCallback != null) {
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN)
                                == PackageManager.PERMISSION_GRANTED) {
                    bleScanner.stopScan(scanCallback);
                    Log.d("BLE", "🛑 BLE scan stopped (fragment paused/destroyed)");
                }
            } catch (Exception e) {
                Log.e("BLE", "⚠️ Failed to stop BLE scan safely: " + e.getMessage());
            }
        }
    }
}