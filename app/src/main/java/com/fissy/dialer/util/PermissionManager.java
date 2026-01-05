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
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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

    public interface PermissionCallback {
        void onPermissionsGranted(Map<String, Boolean> results);
        void onDefaultDialerRoleResult(boolean granted);
    }

    public PermissionManager(@NonNull FragmentActivity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        initializeLaunchers();
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
            if (ContextCompat.checkSelfPermission(activity, permission) 
                    != PackageManager.PERMISSION_GRANTED) {
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
            if (ContextCompat.checkSelfPermission(activity, permission) 
                    != PackageManager.PERMISSION_GRANTED) {
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
}
