package com.example.telegramcallnotifier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Display;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executor;

public class CallMonitorService extends Service {

    private static final String CHANNEL_ID = "CallMonitorChannel";
    private static final int NOTIFICATION_ID = 1;
    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;
    // Keep strong references to listeners to prevent GC
    private java.util.List<PhoneStateListener> activeListeners = new java.util.ArrayList<>();
    private Object telephonyCallback; // For API 31+
    private TelegramSender telegramSender;
    // Removed heartbeat fields
    private long callStartTime = 0;
    private boolean isRinging = false;
    private String lastIncomingNumber = "";
    private PowerManager.WakeLock wakeLock;
    // Removed BroadcastReceiver to prevent conflict with SubscriptionManager

    // Debounce fields
    private Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable sendNotificationRunnable;
    private String pendingNumber = null;
    private int pendingSimSlot = -1;

    private final Handler autoHangupHandler = new Handler(Looper.getMainLooper());
    private Runnable autoHangupRunnable;
    private boolean autoAnswerTriggered = false;
    private boolean autoHangupScheduled = false;

    // Battery & Status Monitoring
    private BatteryReceiver batteryReceiver;
    private int lastBatteryLevel = -1;
    private boolean lastChargingState = false;
    private static final long PERIODIC_INTERVAL = 60 * 60 * 1000; // 60 Minutes
    private static final String ACTION_SEND_PERIODIC_REPORT = "com.example.telegramcallnotifier.ACTION_SEND_PERIODIC_REPORT";
    
    private final Handler periodicHandler = new Handler(Looper.getMainLooper());
    private final Runnable periodicRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                sendPeriodicStatusReport();
            } finally {
                periodicHandler.postDelayed(this, PERIODIC_INTERVAL);
                scheduleNextReport();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        telegramSender = new TelegramSender(this);
        
        // Log Service Start (Local log only)
        CustomExceptionHandler.log(this, "Service onCreate. SDK: " + Build.VERSION.SDK_INT);

        // Acquire WakeLock
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            try {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "CallMonitorService::WakeLock");
                wakeLock.acquire();
            } catch (Exception e) {
                Log.e("CallMonitorService", "Error acquiring WakeLock", e);
            }
        }

        // Start Foreground Service
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Call Monitor Active")
                .setContentText("Listening for incoming calls...")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentIntent(pendingIntent)
                .build();

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable e) {
            Log.e("CallMonitorService", "Error starting foreground service", e);
            try {
                startForeground(NOTIFICATION_ID, notification);
            } catch (Throwable t) {
                Log.e("CallMonitorService", "Fatal error starting foreground", t);
            }
        }

        // Register Phone Listener
        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        registerPhoneListener();
        
        // Removed registerCallReceiver() to rely solely on SubscriptionManager/PhoneStateListener
        // This prevents the "Unknown SIM" (-1) from overwriting the correct SIM slot.

        // Removed Heartbeat and Start Notification per user request

        // Initialize Battery & Status Monitoring
        telegramSender.sendStatusMessage("Service started");
        startBatteryMonitoring();
        startPeriodicReporting();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SEND_PERIODIC_REPORT.equals(intent.getAction())) {
            sendPeriodicStatusReport();
            restartInProcessPeriodicLoop();
            scheduleNextReport();
            return START_STICKY;
        }

        restartInProcessPeriodicLoop();
        scheduleNextReport();
        return START_STICKY;
    }

    // Removed registerCallReceiver() to avoid conflict with SubscriptionManager


    private void registerPhoneListener() {
        // Multi-SIM Support
        android.telephony.SubscriptionManager subscriptionManager = getSystemService(android.telephony.SubscriptionManager.class);
        if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            java.util.List<android.telephony.SubscriptionInfo> subList = subscriptionManager.getActiveSubscriptionInfoList();
            if (subList != null && !subList.isEmpty()) {
                // Clear existing listeners to avoid duplicates
                activeListeners.clear();
                for (android.telephony.SubscriptionInfo subInfo : subList) {
                    int subId = subInfo.getSubscriptionId();
                    int slotIndex = subInfo.getSimSlotIndex(); // 0 or 1
                    registerListenerForSub(subId, slotIndex + 1);
                }
            } else {
                // Fallback for single SIM or if list is empty
                if (Build.VERSION.SDK_INT >= 31) {
                    registerTelephonyCallback();
                } else {
                    registerLegacyPhoneListener();
                }
            }
        }
    }

    private void registerListenerForSub(int subId, int simSlot) {
        TelephonyManager subTm = telephonyManager.createForSubscriptionId(subId);
        
        try {
            PhoneStateListener listener = new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String phoneNumber) {
                    CustomExceptionHandler.log(CallMonitorService.this,
                            "onCallStateChanged state=" + state + " number=" + phoneNumber + " sim=" + simSlot);
                    if (state == TelephonyManager.CALL_STATE_RINGING) {
                        CustomExceptionHandler.log(CallMonitorService.this,
                                "CALL_STATE_RINGING detected on SIM " + simSlot + " number=" + phoneNumber);

                        handleCallState(state, phoneNumber, simSlot);
                        return;
                    }
                    handleCallState(state, phoneNumber, simSlot);
                }
            };
            subTm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
            activeListeners.add(listener); // Keep strong reference
        } catch (Exception e) {
            Log.e("CallMonitorService", "Error registering listener for SIM " + simSlot, e);
            CustomExceptionHandler.logError(CallMonitorService.this, e);
        }
    }

    private void registerLegacyPhoneListener() {
        try {
            phoneStateListener = new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String phoneNumber) {
                    CustomExceptionHandler.log(CallMonitorService.this, "onCallStateChanged state=" + state + " number=" + phoneNumber);
                    handleCallState(state, phoneNumber, -1);
                }
            };
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
        } catch (SecurityException e) {
            Log.e("CallMonitorService", "Permission missing for phone listener", e);
        } catch (Exception e) {
            Log.e("CallMonitorService", "Error registering legacy listener", e);
        }
    }

    private void registerTelephonyCallback() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                telephonyCallback = new CallStateCallback();
                telephonyManager.registerTelephonyCallback(getMainExecutor(), (TelephonyCallback) telephonyCallback);
            } catch (SecurityException e) {
                 Log.e("CallMonitorService", "Permission missing for telephony callback", e);
            } catch (Exception e) {
                 Log.e("CallMonitorService", "Error registering telephony callback", e);
            }
        }
    }

    private class CallStateCallback extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            CustomExceptionHandler.log(CallMonitorService.this, "onCallStateChanged state=" + state + " number=" + null);
            handleCallState(state, null, -1);
        }
    }

    private void scheduleHangupAfterDelay() {
        try {
            if (autoHangupScheduled) {
                CustomExceptionHandler.log(this, "scheduleHangupAfterDelay skipped: already scheduled");
                return;
            }

            autoHangupScheduled = true;

            if (autoHangupRunnable != null) {
                autoHangupHandler.removeCallbacks(autoHangupRunnable);
            }

            autoHangupRunnable = () -> {
                try {
                    CustomExceptionHandler.log(this, "Auto hangup timer fired after 5 seconds");
                    attemptHangUp();
                } catch (Exception e) {
                    CustomExceptionHandler.log(this, "scheduleHangupAfterDelay exception: " + e.getMessage());
                    CustomExceptionHandler.logError(this, e);
                } finally {
                    autoHangupScheduled = false;
                }
            };

            CustomExceptionHandler.log(this, "Scheduling auto hangup after 5 seconds");
            autoHangupHandler.postDelayed(autoHangupRunnable, 5000);

        } catch (Exception e) {
            CustomExceptionHandler.log(this, "scheduleHangupAfterDelay outer exception: " + e.getMessage());
            CustomExceptionHandler.logError(this, e);
        }
    }

    private void cancelHangupTimer() {
        try {
            if (autoHangupRunnable != null) {
                autoHangupHandler.removeCallbacks(autoHangupRunnable);
            }
            autoHangupScheduled = false;
            CustomExceptionHandler.log(this, "Hangup timer cancelled");
        } catch (Exception e) {
            CustomExceptionHandler.log(this, "cancelHangupTimer exception: " + e.getMessage());
            CustomExceptionHandler.logError(this, e);
        }
    }
    
    private void handleCallState(int state, String incomingNumber, int simSlot) {
        try {
            CustomExceptionHandler.log(this,
                    "handleCallState state=" + state + " number=" + incomingNumber + " sim=" + simSlot);

            if (state == TelephonyManager.CALL_STATE_RINGING) {
                pendingSimSlot = simSlot;
                pendingNumber = incomingNumber;

                CustomExceptionHandler.log(this,
                        "CALL_STATE_RINGING detected. incomingNumber=" + incomingNumber + " sim=" + simSlot);

                if (sendNotificationRunnable != null) {
                    debounceHandler.removeCallbacks(sendNotificationRunnable);
                }

                sendNotificationRunnable = () -> {
                    try {
                        processRingingCall();
                    } catch (Exception e) {
                        CustomExceptionHandler.log(this, "processRingingCall runnable exception: " + e.getMessage());
                        CustomExceptionHandler.logError(this, e);
                    }
                };

                debounceHandler.postDelayed(sendNotificationRunnable, 1000);
                CustomExceptionHandler.log(this, "Debounce scheduled for ringing call");
                return;
            }

            if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                CustomExceptionHandler.log(this, "CALL_STATE_OFFHOOK detected");
                scheduleHangupAfterDelay();
                return;
            }

            if (state == TelephonyManager.CALL_STATE_IDLE) {
                CustomExceptionHandler.log(this, "CALL_STATE_IDLE detected");
                cancelHangupTimer();
                autoAnswerTriggered = false;
                isRinging = false;
                return;
            }

        } catch (Exception e) {
            CustomExceptionHandler.log(this, "handleCallState exception: " + e.getMessage());
            CustomExceptionHandler.logError(this, e);
        }
    }
    
    private void processRingingCall() {
        try {
            CustomExceptionHandler.log(this, "processRingingCall() START");
            CustomExceptionHandler.log(this, "Incoming number raw = " + pendingNumber);

            String number = pendingNumber;
            if (number == null || number.trim().isEmpty()) {
                number = "Unknown";
            }
            CustomExceptionHandler.log(this, "Incoming number final = " + number);
            CustomExceptionHandler.log(this, "Pending SIM slot = " + pendingSimSlot);

            if (lastIncomingNumber.equals(number) && isRinging) {
                CustomExceptionHandler.log(this, "processRingingCall() skipped: already handled. number=" + number);
                return;
            }

            if (pendingSimSlot == -1) {
                CustomExceptionHandler.log(this, "Pending SIM slot missing. Trying resolveSimSlot()");
                pendingSimSlot = resolveSimSlot();
                CustomExceptionHandler.log(this, "resolveSimSlot() result = " + pendingSimSlot);
            }

            isRinging = true;
            callStartTime = System.currentTimeMillis();
            lastIncomingNumber = number;

            String simInfo;
            if (pendingSimSlot != -1) {
                simInfo = (pendingSimSlot == 1) ? "SIM 1" :
                        (pendingSimSlot == 2) ? "SIM 2" :
                                "SIM " + pendingSimSlot;
            } else {
                simInfo = "Unknown SIM";
                if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    android.telephony.SubscriptionManager subscriptionManager = getSystemService(android.telephony.SubscriptionManager.class);
                    if (subscriptionManager != null) {
                        java.util.List<android.telephony.SubscriptionInfo> subs = subscriptionManager.getActiveSubscriptionInfoList();
                        if (subs != null && subs.size() == 1) {
                            simInfo = "SIM " + (subs.get(0).getSimSlotIndex() + 1);
                        }
                    }
                }
            }

            CustomExceptionHandler.log(this, "Detected line = " + simInfo);

            String msg = "📞 Incoming Call Detected!\n" +
                    "🔢 Number: " + number + "\n" +
                    "📱 Line: " + simInfo + "\n" +
                    "⏰ Time: " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

            CustomExceptionHandler.log(this, "Call message built = " + msg.replace("\n", " | "));

            telegramSender.sendMessage(msg);
            CustomExceptionHandler.log(this, "telegramSender.sendMessage(msg) called");

            CustomExceptionHandler.log(this, "Calling attemptAutoAnswer()");
            attemptAutoAnswer();

        } catch (Exception e) {
            CustomExceptionHandler.log(this, "processRingingCall exception: " + e.getMessage());
            CustomExceptionHandler.logError(this, e);
        }
    }
    
    private int resolveSimSlot() {
        if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return -1;
        }

        android.telephony.SubscriptionManager subscriptionManager = getSystemService(android.telephony.SubscriptionManager.class);
        if (subscriptionManager == null) return -1;

        java.util.List<android.telephony.SubscriptionInfo> subs = subscriptionManager.getActiveSubscriptionInfoList();
        if (subs == null || subs.isEmpty()) return -1;

        // Strategy 1: Check which specific Subscription ID is Ringing
        int bestSlot = -1;
        for (android.telephony.SubscriptionInfo sub : subs) {
            TelephonyManager subTm = telephonyManager.createForSubscriptionId(sub.getSubscriptionId());
            if (subTm.getCallState() == TelephonyManager.CALL_STATE_RINGING) {
                int slot = sub.getSimSlotIndex() + 1;
                CustomExceptionHandler.log(this, "Found Ringing SIM via polling: Slot " + slot);
                // Priority Logic: Pick the first one found if we don't have one yet.
                // If multiple are ringing, this might be ambiguous, but usually Slot 1 is checked first.
                if (bestSlot == -1) {
                    bestSlot = slot;
                }
            }
        }
        
        if (bestSlot != -1) {
            return bestSlot;
        }
        
        // Strategy 2: If only 1 SIM is active, assume it's that one
        if (subs.size() == 1) {
             return subs.get(0).getSimSlotIndex() + 1;
        }

        return -1;
    }

    private void attemptAutoAnswer() {
        try {
            if (autoAnswerTriggered) {
                CustomExceptionHandler.log(this, "attemptAutoAnswer skipped: already triggered");
                return;
            }

            autoAnswerTriggered = true;

            if (!isAppDefaultDialer()) {
                CustomExceptionHandler.log(this, "attemptAutoAnswer aborted: app is NOT default dialer");
                return;
            }

            android.telecom.TelecomManager tm = (android.telecom.TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            if (tm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                CustomExceptionHandler.log(this, "Calling TelecomManager.acceptRingingCall()");
                tm.acceptRingingCall();
                CustomExceptionHandler.log(this, "acceptRingingCall() invoked");
            } else {
                CustomExceptionHandler.log(this, "TelecomManager null or unsupported SDK");
            }
        } catch (Throwable e) {
            CustomExceptionHandler.log(this, "attemptAutoAnswer exception: " + e.getMessage());
            autoAnswerTriggered = false;
            CustomExceptionHandler.logError(this, e);
        }
    }


    private boolean isAppDefaultDialer() {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            android.telecom.TelecomManager tm = (android.telecom.TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            if (tm == null) return false;
            String defaultDialer = tm.getDefaultDialerPackage();
            return getPackageName().equals(defaultDialer);
        } catch (Throwable e) {
            CustomExceptionHandler.log(this, "isAppDefaultDialer exception: " + e.getMessage());
            CustomExceptionHandler.logError(this, e);
            return false;
        }
    }

    private void attemptHangUp() {
        if (Build.VERSION.SDK_INT >= 28) {
             android.telecom.TelecomManager tm = (android.telecom.TelecomManager) getSystemService(Context.TELECOM_SERVICE);
             if (tm != null) {
                 if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                     try {
                         tm.endCall();
                         CustomExceptionHandler.log(this, "Auto-ended call via TelecomManager");
                     } catch (Exception e) {
                         Log.e("CallMonitorService", "Failed to end call", e);
                         CustomExceptionHandler.logError(this, e);
                     }
                 }
             }
        }
    }

    private void startBatteryMonitoring() {
        batteryReceiver = new BatteryReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(batteryReceiver, filter);
    }

    private void stopBatteryMonitoring() {
        if (batteryReceiver != null) {
            unregisterReceiver(batteryReceiver);
            batteryReceiver = null;
        }
    }

    private void restartInProcessPeriodicLoop() {
        periodicHandler.removeCallbacks(periodicRunnable);
        periodicHandler.postDelayed(periodicRunnable, PERIODIC_INTERVAL);
    }

    private void startPeriodicReporting() {
        restartInProcessPeriodicLoop();
        scheduleNextReport();
    }

    private void scheduleNextReport() {
        android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, CallMonitorService.class);
        intent.setAction(ACTION_SEND_PERIODIC_REPORT);
        
        PendingIntent pendingIntent = PendingIntent.getService(this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        long triggerAtMillis = System.currentTimeMillis() + PERIODIC_INTERVAL;

        if (alarmManager == null) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                );
                return;
            }

            if (Build.VERSION.SDK_INT >= 23) {
                alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
            } else if (Build.VERSION.SDK_INT >= 19) {
                alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
            } else {
                alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
            }
        } catch (SecurityException e) {
            try {
                alarmManager.setAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                );
            } catch (Exception inner) {
                inner.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopPeriodicReporting() {
        periodicHandler.removeCallbacks(periodicRunnable);

        android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, CallMonitorService.class);
        intent.setAction(ACTION_SEND_PERIODIC_REPORT);
        PendingIntent pendingIntent = PendingIntent.getService(this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            
        if (alarmManager != null) {
            alarmManager.cancel(pendingIntent);
        }
    }

    private class BatteryReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                handleBatteryChanged(intent);
            } else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                sendBatteryAlert("🔋 Battery Status", "⚡ Charging: Yes\n🔌 Charger Connected");
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                sendBatteryAlert("🔋 Battery Status", "⚡ Charging: No\n🔌 Charger Disconnected");
            }
        }
    }

    private void handleBatteryChanged(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL;
        
        if (level != -1 && scale != -1) {
            int pct = (int) ((level / (float) scale) * 100);
            
            // Check Thresholds (20, 15, 10, 5)
            // We only alert if we drop TO or BELOW a threshold, and we weren't already there/below in the last check (or if it's a fresh start)
            // To avoid spam, we track lastBatteryLevel.
            
            if (lastBatteryLevel != -1) {
                checkThreshold(lastBatteryLevel, pct, 20);
                checkThreshold(lastBatteryLevel, pct, 15);
                checkThreshold(lastBatteryLevel, pct, 10);
                checkThreshold(lastBatteryLevel, pct, 5);
            }
            
            lastBatteryLevel = pct;
            lastChargingState = isCharging;
        }
    }

    private void checkThreshold(int oldLevel, int newLevel, int threshold) {
        if (oldLevel > threshold && newLevel <= threshold) {
            sendBatteryAlert("⚠️ Battery Low!", "📉 Level: " + newLevel + "%");
        }
    }

    private void sendBatteryAlert(String title, String extraInfo) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String batteryStatus = getBatteryInfoString();
        
        StringBuilder msg = new StringBuilder();
        msg.append(title).append("\n");
        msg.append(batteryStatus).append("\n");
        if (extraInfo != null && !extraInfo.isEmpty()) {
            msg.append(extraInfo).append("\n");
        }
        msg.append("⏰ Time: ").append(time);
        
        telegramSender.sendStatusMessage(msg.toString());
    }

    private void sendPeriodicStatusReport() {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        
        StringBuilder msg = new StringBuilder();
        msg.append("📊 Periodic Status Report\n");
        msg.append(getBatteryInfoString()).append("\n");
        msg.append(getNetworkStatusString()).append("\n");
        msg.append(getScreenStatusString()).append("\n");
        msg.append("⏰ Time: ").append(time);
        
        telegramSender.sendStatusMessage(msg.toString());
    }

    private String getBatteryInfoString() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);
        
        if (batteryStatus == null) return "🔋 Battery: Unknown";
        
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int pct = (int) ((level / (float) scale) * 100);
        
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL;
        
        int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        String type = "Unknown";
        if (chargePlug == BatteryManager.BATTERY_PLUGGED_USB) type = "USB";
        else if (chargePlug == BatteryManager.BATTERY_PLUGGED_AC) type = "AC";
        else if (chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS) type = "Wireless";
        
        return "🔢 Battery: " + pct + "%\n" +
               "⚡ Charging: " + (isCharging ? "Yes" : "No") + 
               (isCharging ? ("\n🔌 Type: " + type) : "");
    }

    private String getNetworkStatusString() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "📶 Network: Unknown";
        
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        String netType = isConnected ? activeNetwork.getTypeName() : "None";
        
        WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        boolean isWifiEnabled = (wifiManager != null && wifiManager.isWifiEnabled());
        
        return "📶 Network: " + (isConnected ? "Connected (" + netType + ")" : "Disconnected") + "\n" +
               "🌐 Wi-Fi: " + (isWifiEnabled ? "On" : "Off");
    }

    private String getScreenStatusString() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean isScreenOn = false;
        if (Build.VERSION.SDK_INT >= 20) {
            isScreenOn = pm.isInteractive();
        } else {
            isScreenOn = pm.isScreenOn();
        }
        
        return "📱 Screen: " + (isScreenOn ? "On" : "Off");
    }


    private String getNetworkType() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "Unknown";
            
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            if (activeNetwork != null && activeNetwork.isConnected()) {
                if (activeNetwork.getType() == ConnectivityManager.TYPE_WIFI) return "WiFi";
                if (activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE) return "Mobile Data";
                return "Connected";
            }
            return "No Internet";
        } catch (Exception e) {
            Log.e("CallMonitorService", "Error checking network", e);
            return "Unknown (Error)";
        }
    }

    private String formatDuration(long seconds) {
        long absSeconds = Math.abs(seconds);
        String positive = String.format(
                "%d:%02d:%02d",
                absSeconds / 3600,
                (absSeconds % 3600) / 60,
                absSeconds % 60);
        return positive;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Call Monitor Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        
        stopBatteryMonitoring();
        stopPeriodicReporting();

        // Removed callReceiver unregister
        if (telephonyManager != null) {
            // Unregister all multi-sim listeners
            for (PhoneStateListener listener : activeListeners) {
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE);
            }
            activeListeners.clear();

            if (Build.VERSION.SDK_INT >= 31 && telephonyCallback != null) {
                telephonyManager.unregisterTelephonyCallback((TelephonyCallback) telephonyCallback);
            } else if (phoneStateListener != null) {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            }
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        // Removed stop notification
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
