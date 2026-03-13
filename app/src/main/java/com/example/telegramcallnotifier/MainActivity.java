package com.example.telegramcallnotifier;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int BATTERY_OPTIMIZATION_REQUEST_CODE = 200;
    private static final int EXACT_ALARM_REQUEST_CODE = 201;
    private static final int REQ_DIALER_ROLE = 3001;
    private static final int REQ_CHANGE_DEFAULT_DIALER = 3002;
    
    private TelegramSender telegramSender;
    private Button btnToggleService;
    private Button btnViewLogs;
    private TextView textStatus;
    private CustomExceptionHandler exceptionHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Setup Crash Handler
        exceptionHandler = new CustomExceptionHandler(this);
        Thread.setDefaultUncaughtExceptionHandler(exceptionHandler);

        setContentView(R.layout.activity_main);

        telegramSender = new TelegramSender(this);

        btnToggleService = findViewById(R.id.btnToggleService);
        btnViewLogs = findViewById(R.id.btnViewLogs);
        textStatus = findViewById(R.id.textStatus);

        btnToggleService.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isServiceRunning()) {
                    stopService();
                } else {
                    checkPermissionsAndStartService();
                }
            }
        });

        btnViewLogs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLogFile();
            }
        });

        updateUI();

        // Log start
        CustomExceptionHandler.log(this, "App Started. SDK: " + Build.VERSION.SDK_INT);
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }

    private void updateUI() {
        if (isServiceRunning()) {
            btnToggleService.setText("RUNNING");
            btnToggleService.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4CAF50"))); // Green
            textStatus.setText("Status: Service is Active");
        } else {
            btnToggleService.setText("START");
            btnToggleService.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#F44336"))); // Red
            textStatus.setText("Status: Service Stopped");
        }
    }

    private void showLogs() {
        String logs = CustomExceptionHandler.getLogContent(this);
        textStatus.setText(logs);
    }

    private void openLogFile() {
        File logFile = CustomExceptionHandler.getLogFile(this);
        if (logFile == null || !logFile.exists()) {
            Toast.makeText(this, "No logs found.", Toast.LENGTH_LONG).show();
            showLogs();
            return;
        }

        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", logFile);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "text/plain");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(Intent.createChooser(intent, "Open logs"));
        } catch (Exception e) {
            showLogs();
        }
    }

    private boolean isServiceRunning() {
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (CallMonitorService.class.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }
    
    private void stopService() {
        Intent serviceIntent = new Intent(this, CallMonitorService.class);
        stopService(serviceIntent);
        updateUI();
        // Delay update to double check
        new android.os.Handler().postDelayed(this::updateUI, 500);
    }

    private boolean needsExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            return alarmManager != null && !alarmManager.canScheduleExactAlarms();
        }
        return false;
    }

    private void checkExactAlarmAndStart() {
        if (needsExactAlarmPermission()) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, EXACT_ALARM_REQUEST_CODE);
                Toast.makeText(this, "Please allow exact alarms so the 60-minute report works while the screen is off", Toast.LENGTH_LONG).show();
                return;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        checkBatteryAndStart();
    }

    private void checkBatteryAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, BATTERY_OPTIMIZATION_REQUEST_CODE); // 200 = Battery Request
                    return;
                } catch (Exception e) {
                    e.printStackTrace();
                    // If it fails, just try to start service anyway
                }
            }
        }
        startService();
    }

    private void checkPermissionsAndStartService() {
        List<String> permsList = new ArrayList<>();
        permsList.add(Manifest.permission.READ_PHONE_STATE);
        permsList.add(Manifest.permission.READ_CALL_LOG);
        permsList.add(Manifest.permission.ANSWER_PHONE_CALLS);
        permsList.add(Manifest.permission.CALL_PHONE);
        
        if (Build.VERSION.SDK_INT >= 26) {
            permsList.add(Manifest.permission.READ_PHONE_NUMBERS);
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permsList.add(Manifest.permission.FOREGROUND_SERVICE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             permsList.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        
        if (Build.VERSION.SDK_INT >= 34) {
             permsList.add(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC);
        }

        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permsList) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }

        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        } else {
            if (isAppDefaultDialer()) {
                checkExactAlarmAndStart();
            } else {
                requestDefaultDialerRoleIfNeeded();
            }
        }
    }

    private void startService() {
        Intent serviceIntent = new Intent(this, CallMonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            new android.os.Handler().postDelayed(this::updateUI, 500);
        } catch (Throwable e) {
            e.printStackTrace();
            Toast.makeText(this, "Error starting service: " + e.getMessage(), Toast.LENGTH_LONG).show();
            textStatus.setText("Error: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                if (isAppDefaultDialer()) {
                    checkExactAlarmAndStart();
                } else {
                    requestDefaultDialerRoleIfNeeded();
                }
            } else {
                Toast.makeText(this, "All required permissions must be granted", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_DIALER_ROLE || requestCode == REQ_CHANGE_DEFAULT_DIALER) {
            if (isAppDefaultDialer()) {
                CustomExceptionHandler.log(this, "Default dialer granted");
                checkExactAlarmAndStart();
            } else {
                CustomExceptionHandler.log(this, "Default dialer NOT granted");
                Toast.makeText(this, "Please set the app as default Phone app for auto answer", Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (requestCode == BATTERY_OPTIMIZATION_REQUEST_CODE) {
            startService();
        } else if (requestCode == EXACT_ALARM_REQUEST_CODE) {
            checkBatteryAndStart();
        }
    }

    private void requestDefaultDialerRoleIfNeeded() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.app.role.RoleManager roleManager = (android.app.role.RoleManager) getSystemService(Context.ROLE_SERVICE);
                if (roleManager != null
                        && roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_DIALER)
                        && !roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER)) {
                    Intent intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER);
                    startActivityForResult(intent, REQ_DIALER_ROLE);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.telecom.TelecomManager telecomManager =
                        (android.telecom.TelecomManager) getSystemService(Context.TELECOM_SERVICE);

                if (telecomManager != null &&
                        !getPackageName().equals(telecomManager.getDefaultDialerPackage())) {
                    Intent intent = new Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
                    intent.putExtra(
                            android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                            getPackageName()
                    );
                    startActivityForResult(intent, REQ_CHANGE_DEFAULT_DIALER);
                }
            }
        } catch (Exception e) {
            CustomExceptionHandler.log(this, "requestDefaultDialerRoleIfNeeded exception: " + e.getMessage());
            CustomExceptionHandler.logError(this, e);
        }
    }

    private boolean isAppDefaultDialer() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.app.role.RoleManager roleManager = (android.app.role.RoleManager) getSystemService(Context.ROLE_SERVICE);
                return roleManager != null && roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER);
            } else {
                android.telecom.TelecomManager telecomManager =
                        (android.telecom.TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                return telecomManager != null &&
                        getPackageName().equals(telecomManager.getDefaultDialerPackage());
            }
        } catch (Exception e) {
            return false;
        }
    }
}
