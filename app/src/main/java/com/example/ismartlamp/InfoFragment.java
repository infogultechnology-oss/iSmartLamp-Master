package com.example.ismartlamp;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

import java.io.InputStream;

public class InfoFragment extends Fragment {

    private static final int CAMERA_PERMISSION_CODE = 101;
    private static final int PICK_IMAGE_REQUEST = 102;

    private View view;
    private DecoratedBarcodeView qrScannerView;
    private View loggedInView;
    private TextView loggedInText;
    private Button logoutButton;
    private ImageButton selectImageButton;

    private boolean scanned = false;
    private String savedUsername;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_info, container, false);

        qrScannerView = view.findViewById(R.id.qrScannerView);
        loggedInView = view.findViewById(R.id.loggedInView);
        loggedInText = view.findViewById(R.id.loggedInName);
        logoutButton = view.findViewById(R.id.logoutButton);
        selectImageButton = view.findViewById(R.id.selectImageButton);

        savedUsername = requireActivity().getSharedPreferences("qr_info", getContext().MODE_PRIVATE).getString("username", null);

        if (savedUsername != null) {
            showLoggedInView(savedUsername);
        } else {
            selectImageButton.setVisibility(View.VISIBLE);
            checkCameraPermissionAndStartScanner();
        }

        logoutButton.setOnClickListener(v -> confirmLogout());
        selectImageButton.setOnClickListener(v -> openImagePicker());

        return view;
    }

    private void showLoggedInView(String username) {
        qrScannerView.setVisibility(View.GONE);
        loggedInView.setVisibility(View.VISIBLE);
        selectImageButton.setVisibility(View.GONE);
        loggedInText.setText("Username: " + username);
    }

    private void showQRScanner() {
        loggedInView.setVisibility(View.GONE);
        qrScannerView.setVisibility(View.VISIBLE);
        scanned = false;

        qrScannerView.decodeContinuous(new BarcodeCallback() {
            @Override
            public void barcodeResult(BarcodeResult result) {
                if (!scanned) {
                    scanned = true;
                    handleQRData(result.getText());
                }
            }
        });

        qrScannerView.resume();
    }

    private void handleQRData(String qrText) {
        try {
            Uri uri = Uri.parse(qrText);
            String label = uri.getLastPathSegment();
            String username = (label != null && label.contains(":")) ? label.split(":")[1] : "Unknown";

            String secret = uri.getQueryParameter("secret");
            String issuer = uri.getQueryParameter("issuer");
            String uid = uri.getQueryParameter("uid");

            if (secret == null || uid == null) {
                throw new IllegalArgumentException("Missing data in QR");
            }

            requireActivity().getSharedPreferences("qr_info", getContext().MODE_PRIVATE).edit()
                    .putString("username", username)
                    .putString("secret", secret)
                    .putString("issuer", issuer)
                    .putString("uid", uid)
                    .apply();

            WorkScheduler.scheduleDailySync(requireContext());
            Toast.makeText(requireContext(), "Logging in as " + username, Toast.LENGTH_SHORT).show();
            showLoggedInView(username);

        } catch (Exception e) {
            Toast.makeText(requireContext(), "Invalid QR Code", Toast.LENGTH_SHORT).show();
            scanned = false;
        }
    }

    private void confirmLogout() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Logout")
                .setMessage("Are you sure you want to logout?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    requireActivity().getSharedPreferences("qr_info", getContext().MODE_PRIVATE).edit().clear().apply();
                    savedUsername = null;
                    selectImageButton.setVisibility(View.VISIBLE);
                    checkCameraPermissionAndStartScanner();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void checkCameraPermissionAndStartScanner() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        } else {
            showQRScanner();
        }
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (savedUsername == null && qrScannerView != null) {
            qrScannerView.resume();
        }
    }

    @Override
    public void onPause() {
        if (qrScannerView != null) {
            qrScannerView.pause();
        }
        super.onPause();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_IMAGE_REQUEST && resultCode == AppCompatActivity.RESULT_OK && data != null) {
            Uri imageUri = data.getData();
            if (imageUri != null) {
                try {
                    InputStream inputStream = requireActivity().getContentResolver().openInputStream(imageUri);
                    Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                    decodeQRFromBitmap(bitmap);
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Failed to load image", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void decodeQRFromBitmap(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
        BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));

        try {
            Result result = new MultiFormatReader().decode(binaryBitmap);
            handleQRData(result.getText());
        } catch (Exception e) {
            Toast.makeText(requireContext(), "No valid QR code found in image", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == CAMERA_PERMISSION_CODE &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showQRScanner();
        } else {
            Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_SHORT).show();
        }
    }
}
