package com.fissy.dialer.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import androidx.core.content.ContextCompat;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Handles granting CAPTURE_AUDIO_OUTPUT permission on rooted devices.
 */
public class PermissionGranter {
    private static final String TAG = "PermissionGranter";
    private static final String PERMISSION_CAPTURE_AUDIO_OUTPUT = "android.permission.CAPTURE_AUDIO_OUTPUT";
    
    // Single thread executor for background operations
    // This executor runs for the lifetime of the application and doesn't need explicit shutdown
    private static final Executor BACKGROUND_EXECUTOR = Executors.newSingleThreadExecutor();
    
    /**
     * Validates that a package name is safe to use in shell commands.
     * Prevents command injection by ensuring the package name only contains
     * alphanumeric characters, dots, and underscores, and doesn't have
     * suspicious patterns like consecutive dots.
     */
    private static boolean isValidPackageName(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        
        // Package names should only contain alphanumeric, dots, and underscores
        if (!packageName.matches("^[a-zA-Z0-9_.]+$")) {
            return false;
        }
        
        // Check for suspicious patterns
        if (packageName.contains("..") || packageName.startsWith(".") || packageName.endsWith(".")) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Attempt to grant CAPTURE_AUDIO_OUTPUT permission if device is rooted.
     * This method runs synchronously and should be called from a background thread.
     */
    private static void attemptGrantCaptureAudioOutputSync(Context context) {
        Log.i(TAG, "==========================================");
        Log.i(TAG, "CHECKING CAPTURE_AUDIO_OUTPUT PERMISSION");
        Log.i(TAG, "==========================================");
        
        String packageName = context.getPackageName();
        
        // Validate package name format to prevent command injection
        if (!isValidPackageName(packageName)) {
            Log.e(TAG, "Invalid or potentially malicious package name: " + packageName);
            Log.i(TAG, "==========================================");
            return;
        }
        
        // Check if permission is already granted
        boolean isGranted = ContextCompat.checkSelfPermission(context, PERMISSION_CAPTURE_AUDIO_OUTPUT) 
            == PackageManager.PERMISSION_GRANTED;
        
        Log.i(TAG, "Package: " + packageName);
        Log.i(TAG, "Permission already granted: " + (isGranted ? "YES" : "NO"));
        
        if (isGranted) {
            Log.i(TAG, "✓ CAPTURE_AUDIO_OUTPUT already granted, no action needed");
            Log.i(TAG, "==========================================");
            return;
        }
        
        // Check if device is rooted
        Log.i(TAG, "Checking for root access...");
        boolean isRooted = RootUtil.isRooted();
        Log.i(TAG, "Device rooted: " + (isRooted ? "YES" : "NO"));
        
        if (!isRooted) {
            Log.i(TAG, "⚠ Device not rooted, cannot grant permission");
            Log.i(TAG, "App will use fallback audio source (microphone only)");
            Log.i(TAG, "==========================================");
            return;
        }
        
        // Device is rooted - attempt to grant permission
        Log.i(TAG, "==========================================");
        Log.i(TAG, "ATTEMPTING TO GRANT PERMISSION VIA ROOT");
        Log.i(TAG, "==========================================");
        Log.i(TAG, "This will show a superuser prompt...");
        
        String command = "pm grant " + packageName + " " + PERMISSION_CAPTURE_AUDIO_OUTPUT;
        boolean success = RootUtil.executeRootCommand(command);
        
        if (success) {
            Log.i(TAG, "✓✓✓ PERMISSION GRANTED SUCCESSFULLY ✓✓✓");
            Log.i(TAG, "VOICE_CALL audio source should now work");
            Log.i(TAG, "Both sides of calls will be recorded");
            
            // Verify permission was actually granted (re-check after grant attempt)
            boolean verifyGranted = ContextCompat.checkSelfPermission(context, PERMISSION_CAPTURE_AUDIO_OUTPUT) 
                == PackageManager.PERMISSION_GRANTED;
            Log.i(TAG, "Verification: Permission granted = " + verifyGranted);
            
            if (!verifyGranted) {
                Log.w(TAG, "⚠ Warning: Command succeeded but permission not showing as granted");
                Log.w(TAG, "This may be a caching issue - try restarting the app");
            }
        } else {
            Log.e(TAG, "✗ Failed to grant permission");
            Log.e(TAG, "Possible reasons:");
            Log.e(TAG, "  - User denied superuser request");
            Log.e(TAG, "  - Root access not working properly");
            Log.e(TAG, "  - SELinux blocking the operation");
            Log.e(TAG, "App will use fallback audio source");
        }
        
        Log.i(TAG, "==========================================");
    }
    
    /**
     * Attempt to grant CAPTURE_AUDIO_OUTPUT permission if device is rooted.
     * This method runs the check on a background thread to avoid blocking the main thread.
     */
    public static void attemptGrantCaptureAudioOutput(Context context) {
        // Use application context to avoid potential memory leaks
        final Context appContext = context.getApplicationContext();
        
        // Run on background executor to avoid ANRs
        BACKGROUND_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    attemptGrantCaptureAudioOutputSync(appContext);
                } catch (Exception e) {
                    Log.e(TAG, "Error in background root permission grant", e);
                }
            }
        });
    }
}
