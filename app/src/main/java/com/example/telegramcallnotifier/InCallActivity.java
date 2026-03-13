package com.example.telegramcallnotifier;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class InCallActivity extends AppCompatActivity {

    private TextView textCallStatus;
    private Button btnHangup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_incall);

        textCallStatus = findViewById(R.id.textCallStatus);
        btnHangup = findViewById(R.id.btnHangup);

        textCallStatus.setText("Call in progress");

        btnHangup.setOnClickListener(v -> {
            if (MyInCallService.currentCall != null) {
                MyInCallService.currentCall.disconnect();
            }
            finish();
        });
    }
}
