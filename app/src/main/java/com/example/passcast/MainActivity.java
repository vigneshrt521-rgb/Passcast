package com.example.passcast;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    Button btnSend, btnReceive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnSend = findViewById(R.id.btnSend);
        btnReceive = findViewById(R.id.btnReceive);

        btnSend.setOnClickListener(v -> {
            Intent i = new Intent(MainActivity.this, SenderActivity.class);
            startActivity(i);
        });

        btnReceive.setOnClickListener(v -> {
            Intent i = new Intent(MainActivity.this, ReceiverActivity.class);
            startActivity(i);
        });
    }
}
