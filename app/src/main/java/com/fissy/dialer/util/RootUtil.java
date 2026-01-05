package com.fissy.dialer.util;

import android.util.Log;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Utility for detecting root access and executing root commands.
 */
public class RootUtil {
    private static final String TAG = "RootUtil";
    
    /**
     * Check if device has root access.
     */
    public static boolean isRooted() {
        // Check for common root indicators
        return checkRootMethod1() || checkRootMethod2() || checkRootMethod3();
    }
    
    // Method 1: Check for su binary
    private static boolean checkRootMethod1() {
        String[] paths = {
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        };
        
        for (String path : paths) {
            if (new File(path).exists()) {
                Log.d(TAG, "Root indicator found: " + path);
                return true;
            }
        }
        return false;
    }
    
    // Method 2: Check for su command
    private static boolean checkRootMethod2() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[] { "/system/xbin/which", "su" });
            BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = in.readLine();
            if (line != null) {
                Log.d(TAG, "su command found at: " + line);
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
    
    // Method 3: Try to execute su
    private static boolean checkRootMethod3() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
    
    /**
     * Execute a command with root privileges.
     * @return true if command executed successfully
     */
    public static boolean executeRootCommand(String command) {
        Process process = null;
        BufferedReader reader = null;
        BufferedReader errorReader = null;
        DataOutputStream os = null;
        
        try {
            Log.i(TAG, "==========================================");
            Log.i(TAG, "EXECUTING ROOT COMMAND");
            Log.i(TAG, "Command: " + command);
            Log.i(TAG, "==========================================");
            
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            
            int exitCode = process.waitFor();
            
            // Read output
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            
            String line;
            StringBuilder output = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            
            StringBuilder errorOutput = new StringBuilder();
            while ((line = errorReader.readLine()) != null) {
                errorOutput.append(line).append("\n");
            }
            
            Log.i(TAG, "Exit code: " + exitCode);
            if (output.length() > 0) {
                Log.i(TAG, "Output: " + output.toString().trim());
            }
            if (errorOutput.length() > 0) {
                Log.w(TAG, "Error output: " + errorOutput.toString().trim());
            }
            
            boolean success = exitCode == 0;
            Log.i(TAG, success ? "✓ Command executed successfully" : "✗ Command failed");
            Log.i(TAG, "==========================================");
            
            return success;
            
        } catch (Exception e) {
            Log.e(TAG, "✗ Failed to execute root command", e);
            Log.i(TAG, "==========================================");
            return false;
        } finally {
            try {
                if (os != null) os.close();
                if (reader != null) reader.close();
                if (errorReader != null) errorReader.close();
                if (process != null) process.destroy();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
    }
}
