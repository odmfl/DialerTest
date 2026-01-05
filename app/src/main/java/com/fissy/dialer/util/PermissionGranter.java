package com.fissy.dialer.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Handles granting CAPTURE_AUDIO_OUTPUT permission on rooted devices.
 */
public class PermissionGranter {
    private static final String TAG = "PermissionGranter";
    private static final String PERMISSION_CAPTURE_AUDIO_OUTPUT = "android.permission.CAPTURE_AUDIO_OUTPUT";
    
    /**
     * Attempt to grant CAPTURE_AUDIO_OUTPUT permission if device is rooted.
     * This method runs synchronously and should be called from a background thread.
     */
    private static void attemptGrantCaptureAudioOutputSync(Context context) {
        Log.i(TAG, "==========================================");
        Log.i(TAG, "CHECKING CAPTURE_AUDIO_OUTPUT PERMISSION");
        Log.i(TAG, "==========================================");
        
        String packageName = context.getPackageName();
        
        // Validate package name format (basic validation to prevent command injection)
        if (packageName == null || !packageName.matches("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*$")) {
            Log.e(TAG, "Invalid package name format: " + packageName);
            Log.i(TAG, "==========================================");
            return;
        }
        
        // Check if permission is already granted
        boolean isGranted = context.checkSelfPermission(PERMISSION_CAPTURE_AUDIO_OUTPUT) 
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
            boolean verifyGranted = context.checkSelfPermission(PERMISSION_CAPTURE_AUDIO_OUTPUT) 
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
        // Run on a background thread to avoid ANRs
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    attemptGrantCaptureAudioOutputSync(context);
                } catch (Exception e) {
                    Log.e(TAG, "Error in background root permission grant", e);
                }
            }
        }, "RootPermissionGranter").start();
    }
}
