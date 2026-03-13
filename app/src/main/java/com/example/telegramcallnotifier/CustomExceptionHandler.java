package com.example.telegramcallnotifier;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CustomExceptionHandler implements Thread.UncaughtExceptionHandler {

    private final Context context;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    public CustomExceptionHandler(Context context) {
        this.context = context;
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        logError(context, throwable);
        
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(thread, throwable);
        } else {
            System.exit(1);
        }
    }

    public static void logError(Context context, Throwable throwable) {
        try {
            File logFile = getLogFile(context);
            
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            String stackTrace = sw.toString();

            String timeStamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            String logContent = "\n\n--- CRASH REPORT " + timeStamp + " ---\n" + stackTrace;

            FileOutputStream fos = new FileOutputStream(logFile, true);
            fos.write(logContent.getBytes());
            fos.close();
            
            Log.e("CrashHandler", "Crash saved to " + logFile.getAbsolutePath());
            
        } catch (Exception e) {
            Log.e("CrashHandler", "Failed to write crash log", e);
        }
    }
    
    public static void log(Context context, String message) {
         try {
            File logFile = getLogFile(context);
            String timeStamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            String logContent = "\n[" + timeStamp + "] " + message;
            FileOutputStream fos = new FileOutputStream(logFile, true);
            fos.write(logContent.getBytes());
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public static File getLogFile(Context context) {
        // Use standard documents directory inside app-specific storage
        // Path: /sdcard/Android/data/com.example.telegramcallnotifier/files/Documents/crash_log.txt
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, "crash_log.txt");
    }
    
    public static String getLogContent(Context context) {
        try {
            File logFile = getLogFile(context);
            if (!logFile.exists()) return "No logs found.";
            
            // Read last 4000 chars
            java.io.FileInputStream fis = new java.io.FileInputStream(logFile);
            byte[] data = new byte[(int) logFile.length()];
            fis.read(data);
            fis.close();
            return new String(data, "UTF-8");
        } catch (Exception e) {
            return "Error reading logs: " + e.getMessage();
        }
    }
}
