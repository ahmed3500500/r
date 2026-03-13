package com.example.telegramcallnotifier;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

public class DialerActivity extends AppCompatActivity {

    private EditText editNumber;
    private Button btnDial;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dialer);

        editNumber = findViewById(R.id.editNumber);
        btnDial = findViewById(R.id.btnDial);

        btnDial.setOnClickListener(v -> {
            String number = editNumber.getText().toString().trim();
            if (!number.isEmpty()) {
                Intent intent = new Intent(Intent.ACTION_CALL);
                intent.setData(Uri.parse("tel:" + number));
                startActivity(intent);
            }
        });

        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_DIAL.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null) {
                String schemeSpecific = data.getSchemeSpecificPart();
                if (schemeSpecific != null) {
                    editNumber.setText(schemeSpecific);
                    editNumber.setSelection(schemeSpecific.length());
                }
            }
        }
    }
}
