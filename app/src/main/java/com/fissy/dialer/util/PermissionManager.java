/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.fissy.dialer.util;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.telecom.TelecomManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.fissy.dialer.common.LogUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages runtime permissions for the Dialer app.
 * Handles requesting permissions including notification, phone, contacts, and default dialer role.
 */
public class PermissionManager {

    private static final String PREFS_NAME = "dialer_permission_manager";
    private static final String KEY_PERMISSIONS_REQUESTED = "permissions_requested";
    private static final String KEY_DIALER_ROLE_REQUESTED = "dialer_role_requested";
    private static final String KEY_FULL_SCREEN_INTENT_REQUESTED = "full_screen_intent_requested";
    private static final String KEY_WRITE_SETTINGS_REQUESTED = "write_settings_requested";

    // Required permissions for the app
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.MANAGE_OWN_CALLS,
    };

    private final FragmentActivity activity;
    private final SharedPreferences prefs;
    private PermissionCallback callback;
    private ActivityResultLauncher<String[]> multiplePermissionsLauncher;
    private ActivityResultLauncher<Intent> dialerRoleLauncher;
    private ActivityResultLauncher<Intent> fullScreenIntentLauncher;
    private ActivityResultLauncher<Intent> writeSettingsLauncher;
    private boolean isInitialized = false;

    public interface PermissionCallback {
        void onPermissionsGranted(Map<String, Boolean> results);
        void onDefaultDialerRoleResult(boolean granted);
        void onFullScreenIntentPermissionResult(boolean granted);
        void onWriteSettingsPermissionResult(boolean granted);
    }

    public PermissionManager(@NonNull FragmentActivity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Initialize launchers. This should be called from onCreate or a similar early lifecycle method.
     */
    public void initialize() {
        if (isInitialized) {
            return;
        }
        initializeLaunchers();
        isInitialized = true;
    }

    private void initializeLaunchers() {
        // Multiple permissions launcher
        multiplePermissionsLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    LogUtil.i("PermissionManager", "Permissions result: " + result);
                    if (callback != null) {
                        callback.onPermissionsGranted(result);
                    }
                    markPermissionsRequested();
                    
                    // Check if POST_NOTIFICATIONS was granted and if so, check full-screen intent
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Boolean notificationGranted = result.get(Manifest.permission.POST_NOTIFICATIONS);
                        if (notificationGranted != null && notificationGranted) {
                            // Automatically check for full-screen intent permission after notification permission
                            checkAndRequestFullScreenIntent(callback);
                        }
                    }
                }
        );

        // Default dialer role launcher
        dialerRoleLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    boolean granted = isDefaultDialer();
                    LogUtil.i("PermissionManager", "Default dialer role result: " + granted);
                    if (callback != null) {
                        callback.onDefaultDialerRoleResult(granted);
                    }
                    markDialerRoleRequested();
                }
        );

        // Full-screen intent permission launcher (Android 14+)
        fullScreenIntentLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    boolean granted = canUseFullScreenIntent();
                    LogUtil.i("PermissionManager", "Full-screen intent permission result: " + granted);
                    if (callback != null) {
                        callback.onFullScreenIntentPermissionResult(granted);
                    }
                    markFullScreenIntentRequested();
                }
        );

        // WRITE_SETTINGS permission launcher
        writeSettingsLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    boolean granted = canWriteSettings();
                    LogUtil.i("PermissionManager", "Write settings permission result: " + granted);
                    if (callback != null) {
                        callback.onWriteSettingsPermissionResult(granted);
                    }
                    markWriteSettingsRequested();
                }
        );
    }

    /**
     * Check if this is the first time we're requesting permissions
     */
    public boolean isFirstRequest() {
        return !prefs.getBoolean(KEY_PERMISSIONS_REQUESTED, false);
    }

    /**
     * Check if all required permissions are granted
     */
    public boolean hasAllRequiredPermissions() {
        List<String> permissions = getRequiredPermissionsList();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get list of required permissions based on Android version
     */
    private List<String> getRequiredPermissionsList() {
        List<String> permissions = new ArrayList<>();
        for (String permission : REQUIRED_PERMISSIONS) {
            permissions.add(permission);
        }

        // Add POST_NOTIFICATIONS for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        return permissions;
    }

    /**
     * Get list of permissions that are currently denied
     */
    public List<String> getDeniedPermissions() {
        List<String> denied = new ArrayList<>();
        List<String> required = getRequiredPermissionsList();
        
        for (String permission : required) {
            if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                denied.add(permission);
            }
        }
        
        return denied;
    }

    /**
     * Request all required permissions
     */
    public void requestPermissions(PermissionCallback callback) {
        this.callback = callback;
        List<String> denied = getDeniedPermissions();
        
        if (denied.isEmpty()) {
            LogUtil.i("PermissionManager", "All permissions already granted");
            if (callback != null) {
                Map<String, Boolean> results = new HashMap<>();
                for (String permission : getRequiredPermissionsList()) {
                    results.put(permission, true);
                }
                callback.onPermissionsGranted(results);
            }
            return;
        }

        LogUtil.i("PermissionManager", "Requesting permissions: " + denied);
        multiplePermissionsLauncher.launch(denied.toArray(new String[0]));
    }

    /**
     * Check if the app is set as default dialer
     */
    public boolean isDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            TelecomManager telecomManager = (TelecomManager) activity.getSystemService(Context.TELECOM_SERVICE);
            if (telecomManager != null) {
                String defaultDialer = telecomManager.getDefaultDialerPackage();
                return activity.getPackageName().equals(defaultDialer);
            }
        }
        return false;
    }

    /**
     * Request to become the default dialer app
     */
    public void requestDefaultDialerRole(PermissionCallback callback) {
        this.callback = callback;
        
        if (isDefaultDialer()) {
            LogUtil.i("PermissionManager", "Already default dialer");
            if (callback != null) {
                callback.onDefaultDialerRoleResult(true);
            }
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requestDefaultDialerRoleViaRoleManager();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestDefaultDialerRoleViaIntent();
        } else {
            LogUtil.w("PermissionManager", "Default dialer role not supported on this API level");
            if (callback != null) {
                callback.onDefaultDialerRoleResult(false);
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void requestDefaultDialerRoleViaRoleManager() {
        RoleManager roleManager = (RoleManager) activity.getSystemService(Context.ROLE_SERVICE);
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            if (!roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER);
                dialerRoleLauncher.launch(intent);
            } else {
                LogUtil.i("PermissionManager", "Role already held");
                if (callback != null) {
                    callback.onDefaultDialerRoleResult(true);
                }
            }
        } else {
            LogUtil.w("PermissionManager", "RoleManager not available");
            requestDefaultDialerRoleViaIntent();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void requestDefaultDialerRoleViaIntent() {
        Intent intent = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
        intent.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, 
                activity.getPackageName());
        dialerRoleLauncher.launch(intent);
    }

    /**
     * Open app settings for user to manually grant permissions
     */
    public void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(android.net.Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(intent);
    }

    /**
     * Mark that permissions have been requested
     */
    private void markPermissionsRequested() {
        prefs.edit().putBoolean(KEY_PERMISSIONS_REQUESTED, true).apply();
    }

    /**
     * Mark that dialer role has been requested
     */
    private void markDialerRoleRequested() {
        prefs.edit().putBoolean(KEY_DIALER_ROLE_REQUESTED, true).apply();
    }

    /**
     * Check if dialer role has been requested before
     */
    public boolean hasDialerRoleBeenRequested() {
        return prefs.getBoolean(KEY_DIALER_ROLE_REQUESTED, false);
    }

    /**
     * Get human-readable permission name for display
     */
    public static String getPermissionDisplayName(String permission) {
        switch (permission) {
            case Manifest.permission.CALL_PHONE:
                return "Phone Calls";
            case Manifest.permission.READ_PHONE_STATE:
                return "Phone State";
            case Manifest.permission.READ_CALL_LOG:
                return "Call Log (Read)";
            case Manifest.permission.WRITE_CALL_LOG:
                return "Call Log (Write)";
            case Manifest.permission.READ_CONTACTS:
                return "Contacts (Read)";
            case Manifest.permission.WRITE_CONTACTS:
                return "Contacts (Write)";
            case Manifest.permission.POST_NOTIFICATIONS:
                return "Notifications";
            case Manifest.permission.MANAGE_OWN_CALLS:
                return "Call Management";
            default:
                return permission;
        }
    }

    /**
     * Get human-readable permission explanation
     */
    public static String getPermissionExplanation(String permission) {
        switch (permission) {
            case Manifest.permission.CALL_PHONE:
                return "Required to make phone calls directly from the app";
            case Manifest.permission.READ_PHONE_STATE:
                return "Required to read phone state and manage calls";
            case Manifest.permission.READ_CALL_LOG:
                return "Required to display your call history";
            case Manifest.permission.WRITE_CALL_LOG:
                return "Required to save call records";
            case Manifest.permission.READ_CONTACTS:
                return "Required to display contact information during calls";
            case Manifest.permission.WRITE_CONTACTS:
                return "Required to update contact information";
            case Manifest.permission.POST_NOTIFICATIONS:
                return "Required to show incoming call and message notifications";
            case Manifest.permission.MANAGE_OWN_CALLS:
                return "Required to manage and handle incoming calls";
            default:
                return "Required for app functionality";
        }
    }

    /**
     * Check if the app can use full-screen intents (Android 14+)
     */
    public boolean canUseFullScreenIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14 (API 34)
            NotificationManager notificationManager = 
                    (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                return notificationManager.canUseFullScreenIntent();
            }
        }
        // For Android 13 and below, full-screen intent is granted automatically
        return true;
    }

    /**
     * Check and request full-screen intent permission if needed (Android 14+)
     */
    public void checkAndRequestFullScreenIntent(PermissionCallback callback) {
        this.callback = callback;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14 (API 34)
            if (!canUseFullScreenIntent()) {
                LogUtil.i("PermissionManager", "Requesting full-screen intent permission");
                requestFullScreenIntentPermission();
            } else {
                LogUtil.i("PermissionManager", "Full-screen intent already granted");
                if (callback != null) {
                    callback.onFullScreenIntentPermissionResult(true);
                }
            }
        } else {
            // Not needed for Android 13 and below
            LogUtil.i("PermissionManager", "Full-screen intent not required on this API level");
            if (callback != null) {
                callback.onFullScreenIntentPermissionResult(true);
            }
        }
    }

    /**
     * Request full-screen intent permission by launching settings (Android 14+)
     */
    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void requestFullScreenIntentPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
        intent.setData(Uri.parse("package:" + activity.getPackageName()));
        fullScreenIntentLauncher.launch(intent);
    }

    /**
     * Check if full-screen intent has been requested before
     */
    public boolean hasFullScreenIntentBeenRequested() {
        return prefs.getBoolean(KEY_FULL_SCREEN_INTENT_REQUESTED, false);
    }

    /**
     * Mark that full-screen intent has been requested
     */
    private void markFullScreenIntentRequested() {
        prefs.edit().putBoolean(KEY_FULL_SCREEN_INTENT_REQUESTED, true).apply();
    }

    /**
     * Check if the app can write system settings
     */
    public boolean canWriteSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.System.canWrite(activity);
        }
        return true; // Pre-M, permission is granted automatically
    }

    /**
     * Request WRITE_SETTINGS permission by launching settings
     */
    public void requestWriteSettingsPermission(PermissionCallback callback) {
        this.callback = callback;
        
        if (canWriteSettings()) {
            LogUtil.i("PermissionManager", "Write settings already granted");
            if (callback != null) {
                callback.onWriteSettingsPermissionResult(true);
            }
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            writeSettingsLauncher.launch(intent);
        } else {
            LogUtil.w("PermissionManager", "Write settings permission not required on this API level");
            if (callback != null) {
                callback.onWriteSettingsPermissionResult(true);
            }
        }
    }

    /**
     * Check if WRITE_SETTINGS has been requested before
     */
    public boolean hasWriteSettingsBeenRequested() {
        return prefs.getBoolean(KEY_WRITE_SETTINGS_REQUESTED, false);
    }

    /**
     * Mark that WRITE_SETTINGS has been requested
     */
    private void markWriteSettingsRequested() {
        prefs.edit().putBoolean(KEY_WRITE_SETTINGS_REQUESTED, true).apply();
    }
}
