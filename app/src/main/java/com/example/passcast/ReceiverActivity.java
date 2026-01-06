package com.example.passcast;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.*;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class ReceiverActivity extends AppCompatActivity {

    private static final String TAG = "ReceiverActivity";
    private static final String SERVICE_ID = "com.example.passcast.SERVICE_ID";
    private static final int REQUEST_PERMISSIONS = 200;
    private static final String CHANNEL_ID = "passcast_channel";

    private Button btnScan;
    private ListView lvSenders;
    private TextView tvStatus;
    private String enteredPassword;
    private String incomingFileName = "received_file";

    private ArrayList<String> names = new ArrayList<>();
    private Map<String, String> endpoints = new HashMap<>();
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receiver);
        createNotificationChannel();

        btnScan = findViewById(R.id.btnScan);
        lvSenders = findViewById(R.id.lvSenders);
        tvStatus = findViewById(R.id.tvStatus);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        lvSenders.setAdapter(adapter);

        btnScan.setOnClickListener(v -> checkPermissionsAndDiscover());

        lvSenders.setOnItemClickListener((p, view, i, id) -> {
            String name = names.get(i);
            String endpointId = endpoints.get(name);
            if (endpointId != null) showPasswordDialog(endpointId);
        });
    }

    /** PERMISSION HANDLING **/
    private void checkPermissionsAndDiscover() {
        ArrayList<String> perms = new ArrayList<>(Arrays.asList(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE
        ));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }

        boolean all = true;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                all = false;
                break;
            }
        }

        if (!all)
            ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), REQUEST_PERMISSIONS);
        else
            startDiscovery();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode == REQUEST_PERMISSIONS)
            checkPermissionsAndDiscover();
    }

    /** DISCOVERY **/
    private void startDiscovery() {
        names.clear();
        endpoints.clear();
        adapter.notifyDataSetChanged();
        tvStatus.setText("Scanning...");

        Nearby.getConnectionsClient(this)
                .startDiscovery(
                        SERVICE_ID,
                        discoveryCallback,
                        new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
                )
                .addOnSuccessListener(unused -> tvStatus.setText("Searching for senders..."))
                .addOnFailureListener(e -> tvStatus.setText("Discovery failed: " + e.getMessage()));
    }

    private final EndpointDiscoveryCallback discoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            runOnUiThread(() -> {
                if (!endpoints.containsKey(info.getEndpointName())) {
                    names.add(info.getEndpointName());
                    endpoints.put(info.getEndpointName(), endpointId);
                    adapter.notifyDataSetChanged();
                }
            });
        }

        @Override
        public void onEndpointLost(@NonNull String endpointId) {
            Log.d(TAG, "Endpoint lost: " + endpointId);
        }
    };

    /** PASSWORD DIALOG **/
    private void showPasswordDialog(String endpointId) {
        EditText input = new EditText(this);
        input.setHint("Enter password");
        new AlertDialog.Builder(this)
                .setTitle("Connect to sender")
                .setView(input)
                .setPositiveButton("Connect", (d, w) -> {
                    enteredPassword = input.getText().toString();
                    if (enteredPassword.isEmpty()) {
                        Toast.makeText(this, "Enter password", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Nearby.getConnectionsClient(this)
                            .requestConnection("Receiver", endpointId, connectionLifecycleCallback);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** CONNECTION HANDLING **/
    private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo info) {
            Nearby.getConnectionsClient(ReceiverActivity.this)
                    .acceptConnection(endpointId, payloadCallback);
        }

        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution res) {
            if (res.getStatus().getStatusCode() == ConnectionsStatusCodes.STATUS_OK) {
                tvStatus.setText("Connected. Receiving...");
            } else {
                tvStatus.setText("Connection failed.");
            }
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            tvStatus.setText("Disconnected.");
        }
    };

    /** PAYLOAD HANDLING **/
    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.asBytes() != null) {
                incomingFileName = new String(payload.asBytes());
                Log.d(TAG, "Filename received: " + incomingFileName);
            } else if (payload.asFile() != null) {
                new Thread(() -> {
                    try {
                        ParcelFileDescriptor pfd = payload.asFile().asParcelFileDescriptor();
                        if (pfd != null)
                            decryptAndSave(pfd, enteredPassword, incomingFileName);
                    } catch (Exception e) {
                        Log.e(TAG, "Decryption error", e);
                        runOnUiThread(() -> tvStatus.setText("Decryption error: " + e.getMessage()));
                    }
                }).start();
            }
        }

        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {
            if (update.getStatus() == PayloadTransferUpdate.Status.SUCCESS) {
                tvStatus.setText("Transfer complete");
            }
        }
    };

    /** DECRYPT AND SAVE **/
    private void decryptAndSave(ParcelFileDescriptor pfd, String password, String fileName) {
        try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {

            // Read IV first 12 bytes
            byte[] iv = new byte[12];
            if (fis.read(iv) != 12)
                throw new Exception("Invalid IV");

            // Derive AES key from password
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha.digest(password.getBytes("UTF-8"));
            byte[] aesKey = Arrays.copyOf(keyBytes, 16);
            SecretKeySpec key = new SecretKeySpec(aesKey, "AES");

            // Prepare cipher
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));

            // Prepare output file in Downloads
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, getMimeTypeFromName(fileName));
            values.put(MediaStore.Downloads.RELATIVE_PATH, "Download");

            Uri outUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);

            try (OutputStream out = getContentResolver().openOutputStream(outUri);
                 CipherInputStream cis = new CipherInputStream(fis, cipher)) {

                byte[] buffer = new byte[4096];
                int n;
                while ((n = cis.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
            }

            showNotification(outUri, fileName);
            runOnUiThread(() -> tvStatus.setText("Decrypted: " + fileName));

        } catch (AEADBadTagException e) {
            runOnUiThread(() -> tvStatus.setText("❌ Wrong password! File not saved."));
        } catch (Exception e) {
            runOnUiThread(() -> tvStatus.setText("Error: " + e.getMessage()));
        }
    }

    /** DYNAMIC MIME DETECTION **/
    private String getMimeTypeFromName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        return "application/octet-stream";
    }

    /** NOTIFICATION **/
    private void showNotification(Uri uri, String fileName) {
        Intent open = new Intent(Intent.ACTION_VIEW);
        open.setDataAndType(uri, getContentResolver().getType(uri));
        open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("📥 File Received")
                .setContentText("Tap to open: " + fileName)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setAutoCancel(true);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify((int) System.currentTimeMillis(), nb.build());
    }

    /** CHANNEL **/
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "PassCast", NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        }
    }
}
