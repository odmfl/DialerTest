package com.fissy.dialer.logging;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LogManager {
    private static final String TAG = "LogManager";
    private static final String LOG_DIR = "DialerLogs";
    private static LogManager instance;
    
    private Context context;
    
    public static LogManager getInstance() {
        if (instance == null) {
            instance = new LogManager();
        }
        return instance;
    }
    
    public void init(Context context) {
        this.context = context.getApplicationContext();
    }
    
    /**
     * Export all app logs to a file and return the file URI
     */
    public File exportLogs() throws IOException {
        if (context == null) {
            throw new IllegalStateException("LogManager not initialized");
        }
        
        // Create logs directory
        File logsDir = new File(context.getExternalFilesDir(null), LOG_DIR);
        if (!logsDir.exists()) {
            logsDir.mkdirs();
        }
        
        // Generate filename with timestamp
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(new Date());
        String fileName = "dialer_logs_" + timestamp + ".txt";
        File logFile = new File(logsDir, fileName);
        
        // Write logs to file
        BufferedWriter writer = new BufferedWriter(new FileWriter(logFile));
        
        // Write header with device info
        writeHeader(writer);
        
        // Write app logs
        writeAppLogs(writer);
        
        writer.close();
        
        Log.d(TAG, "Logs exported to: " + logFile.getAbsolutePath());
        return logFile;
    }
    
    private void writeHeader(BufferedWriter writer) throws IOException {
        writer.write("================================================================================");
        writer.newLine();
        writer.write("DIALER APP LOG EXPORT");
        writer.newLine();
        writer.write("Generated: " + new Date().toString());
        writer.newLine();
        writer.write("================================================================================");
        writer.newLine();
        writer.newLine();
        
        // Device information
        writer.write("DEVICE INFORMATION");
        writer.newLine();
        writer.write("--------------------------------------------------------------------------------");
        writer.newLine();
        writer.write("Manufacturer: " + Build.MANUFACTURER);
        writer.newLine();
        writer.write("Model: " + Build.MODEL);
        writer.newLine();
        writer.write("Android Version: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        writer.newLine();
        writer.write("Brand: " + Build.BRAND);
        writer.newLine();
        writer.write("Product: " + Build.PRODUCT);
        writer.newLine();
        writer.write("Device: " + Build.DEVICE);
        writer.newLine();
        writer.newLine();
        
        // App information
        writer.write("APP INFORMATION");
        writer.newLine();
        writer.write("--------------------------------------------------------------------------------");
        writer.newLine();
        try {
            String packageName = context.getPackageName();
            String versionName = context.getPackageManager()
                .getPackageInfo(packageName, 0).versionName;
            int versionCode = context.getPackageManager()
                .getPackageInfo(packageName, 0).versionCode;
            
            writer.write("Package: " + packageName);
            writer.newLine();
            writer.write("Version: " + versionName + " (" + versionCode + ")");
            writer.newLine();
        } catch (Exception e) {
            writer.write("Error getting app info: " + e.getMessage());
            writer.newLine();
        }
        writer.newLine();
    }
    
    private void writeAppLogs(BufferedWriter writer) throws IOException {
        writer.write("APPLICATION LOGS");
        writer.newLine();
        writer.write("--------------------------------------------------------------------------------");
        writer.newLine();
        
        try {
            // Get logcat output for our app
            String packageName = context.getPackageName();
            
            // Run logcat command - capture logs from ALL processes with relevant tags
            // This is necessary because CallRecorderService runs in a separate process (com.android.incallui)
            // as defined in AndroidManifest.xml. Using tag-based filtering instead of PID filtering
            // ensures we capture logs from both the main process and the service process.
            Process process = Runtime.getRuntime().exec(new String[]{
                "logcat",
                "-d",  // Dump logs
                "-v", "threadtime",  // Include timestamp and thread info
                // Don't filter by PID - we need logs from multiple processes
                "CallRecorder:V",           // CallRecorder class logs
                "CallRecorderService:V",    // Service logs (separate process!)
                "InCallFragment:V",         // UI logs
                "InCallActivity:V",         // Activity logs
                "Dialer:V",                 // General dialer logs
                "InCallServiceImpl:V",      // InCallService logs
                "MediaRecorder:V",          // MediaRecorder errors
                "DialpadFragment:V",        // Dialpad logs
                "*:S"                       // Silence everything else
            });
            
            BufferedReader bufferedReader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );
            
            String line;
            int lineCount = 0;
            int maxLines = 10000;  // Limit to prevent huge files
            
            // Read all matching lines (tag filter already applied by logcat)
            while ((line = bufferedReader.readLine()) != null && lineCount < maxLines) {
                writer.write(line);
                writer.newLine();
                lineCount++;
            }
            
            bufferedReader.close();
            
            writer.newLine();
            writer.write("Total relevant log lines: " + lineCount);
            writer.newLine();
            
            if (lineCount >= maxLines) {
                writer.write("NOTE: Log truncated at " + maxLines + " lines");
                writer.newLine();
            }
            
        } catch (IOException e) {
            writer.write("Error capturing logs: " + e.getMessage());
            writer.newLine();
            Log.e(TAG, "Error capturing logs", e);
        }
        
        writer.newLine();
        writer.write("================================================================================");
        writer.newLine();
        writer.write("END OF LOG");
        writer.newLine();
    }
    
    /**
     * Share the log file via Android sharing
     */
    public void shareLogs(Context context, File logFile) {
        try {
            Uri logUri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".files",
                logFile
            );
            
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, logUri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Dialer App Logs - " + 
                new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
            shareIntent.putExtra(Intent.EXTRA_TEXT, 
                "Dialer app logs for debugging call recording issues.");
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            Intent chooser = Intent.createChooser(shareIntent, "Share Logs");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
            
        } catch (Exception e) {
            Log.e(TAG, "Error sharing logs", e);
        }
    }
    
    /**
     * Get all log files
     */
    public File[] getAllLogFiles() {
        File logsDir = new File(context.getExternalFilesDir(null), LOG_DIR);
        if (logsDir.exists() && logsDir.isDirectory()) {
            return logsDir.listFiles((dir, name) -> name.endsWith(".txt"));
        }
        return new File[0];
    }
    
    /**
     * Delete old log files (keep only last 10)
     */
    public void cleanupOldLogs() {
        File[] logFiles = getAllLogFiles();
        if (logFiles != null && logFiles.length > 10) {
            // Sort by date (oldest first)
            java.util.Arrays.sort(logFiles, (f1, f2) -> 
                Long.compare(f1.lastModified(), f2.lastModified())
            );
            
            // Delete oldest files
            for (int i = 0; i < logFiles.length - 10; i++) {
                logFiles[i].delete();
                Log.d(TAG, "Deleted old log file: " + logFiles[i].getName());
            }
        }
    }
}
