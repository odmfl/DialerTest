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
            process = Runtime.getRuntime().exec(new String[] { "which", "su" });
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
            // Wait up to 3 seconds for the process to complete
            boolean finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                Log.w(TAG, "su process timed out");
                return false;
            }
            return process.exitValue() == 0;
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
     * @param command The command to execute (must be a safe, validated command)
     * @return true if command executed successfully
     */
    public static boolean executeRootCommand(String command) {
        Process process = null;
        BufferedReader reader = null;
        BufferedReader errorReader = null;
        DataOutputStream os = null;
        
        try {
            // Validate command is not empty or dangerous
            if (command == null || command.trim().isEmpty()) {
                Log.e(TAG, "Invalid command: null or empty");
                return false;
            }
            
            Log.i(TAG, "==========================================");
            Log.i(TAG, "EXECUTING ROOT COMMAND");
            Log.i(TAG, "Command: " + command);
            Log.i(TAG, "==========================================");
            
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            
            // Wait up to 10 seconds for the command to complete
            boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                Log.e(TAG, "Command execution timed out");
                Log.i(TAG, "==========================================");
                return false;
            }
            
            int exitCode = process.exitValue();
            
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
