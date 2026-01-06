package com.example.passcast;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class SenderActivity extends AppCompatActivity {

    private static final String TAG = "SenderActivity";
    private static final String SERVICE_ID = "com.example.passcast.SERVICE_ID";
    private static final int REQUEST_PERMISSIONS = 100;

    private EditText etSenderName, etPassword;
    private TextView tvFileName, tvStatus;
    private Button btnSelectFile, btnStartBroadcast;

    private Uri selectedFileUri;
    private File encryptedFile;
    private Payload sendingPayload;
    private String fileName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sender);

        etSenderName = findViewById(R.id.etSenderName);
        etPassword = findViewById(R.id.etPassword);
        tvFileName = findViewById(R.id.tvFileName);
        tvStatus = findViewById(R.id.tvStatus);
        btnSelectFile = findViewById(R.id.btnSelectFile);
        btnStartBroadcast = findViewById(R.id.btnStartBroadcast);

        btnSelectFile.setOnClickListener(v -> openFilePicker());
        btnStartBroadcast.setOnClickListener(v -> checkPermissionsAndBroadcast());
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        filePickerLauncher.launch(Intent.createChooser(intent, "Select File"));
    }

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    selectedFileUri = result.getData().getData();
                    fileName = getFileNameFromUri(selectedFileUri);
                    tvFileName.setText(fileName);
                }
            });

    private String getFileNameFromUri(Uri uri) {
        String name = null;
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
            }
        } catch (Exception ignored) {}
        if (name == null) name = uri.getLastPathSegment();
        return name;
    }

    private void checkPermissionsAndBroadcast() {
        ArrayList<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_WIFI_STATE);
        perms.add(Manifest.permission.CHANGE_WIFI_STATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        boolean all = true;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                all = false;
                break;
            }
        }

        if (!all) {
            ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }

        startBroadcast();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode == REQUEST_PERMISSIONS) {
            checkPermissionsAndBroadcast();
        }
    }

    private void startBroadcast() {
        String name = etSenderName.getText().toString().trim();
        String password = etPassword.getText().toString();

        if (name.isEmpty() || password.isEmpty() || selectedFileUri == null) {
            Toast.makeText(this, "Enter name, password, and select a file", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            tvStatus.setText("Encrypting...");
            encryptedFile = encryptFile(selectedFileUri, password);
            sendingPayload = Payload.fromFile(encryptedFile);

            tvStatus.setText("Broadcasting...");
            Nearby.getConnectionsClient(this)
                    .startAdvertising(
                            name,
                            SERVICE_ID,
                            connectionLifecycleCallback,
                            new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
                    )
                    .addOnSuccessListener(unused -> tvStatus.setText("Broadcasting as " + name))
                    .addOnFailureListener(e -> tvStatus.setText("Broadcast failed: " + e.getMessage()));
        } catch (Exception e) {
            tvStatus.setText("Error: " + e.getMessage());
        }
    }

    private File encryptFile(Uri uri, String password) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) return null;

        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = sha.digest(password.getBytes("UTF-8"));
        byte[] aesKey = new byte[16];
        System.arraycopy(keyBytes, 0, aesKey, 0, 16);
        SecretKeySpec key = new SecretKeySpec(aesKey, "AES");

        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, spec);

        File outFile = new File(getCacheDir(), "encrypted_" + System.currentTimeMillis());
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(iv);
            try (CipherOutputStream cos = new CipherOutputStream(fos, cipher)) {
                byte[] buf = new byte[4096];
                int r;
                while ((r = in.read(buf)) != -1) cos.write(buf, 0, r);
            }
        }
        in.close();
        return outFile;
    }

    private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(String endpointId, com.google.android.gms.nearby.connection.ConnectionInfo connectionInfo) {
            Nearby.getConnectionsClient(SenderActivity.this).acceptConnection(endpointId, payloadCallback);
        }

        @Override
        public void onConnectionResult(String endpointId, ConnectionResolution result) {
            if (result.getStatus().getStatusCode() == ConnectionsStatusCodes.STATUS_OK) {
                tvStatus.setText("Connected — sending file...");
                try {
                    // send file name first
                    Payload namePayload = Payload.fromBytes(fileName.getBytes());
                    Nearby.getConnectionsClient(SenderActivity.this).sendPayload(endpointId, namePayload);
                    // then send encrypted file
                    Nearby.getConnectionsClient(SenderActivity.this).sendPayload(endpointId, sendingPayload);
                } catch (Exception e) {
                    tvStatus.setText("Send failed: " + e.getMessage());
                }
            } else {
                tvStatus.setText("Connection failed.");
            }
        }

        @Override
        public void onDisconnected(String endpointId) {
            tvStatus.setText("Disconnected.");
        }
    };

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(String s, Payload payload) {}

        @Override
        public void onPayloadTransferUpdate(String s, PayloadTransferUpdate update) {
            if (update.getStatus() == PayloadTransferUpdate.Status.SUCCESS) {
                tvStatus.setText("File sent successfully!");
            }
        }
    };
}
