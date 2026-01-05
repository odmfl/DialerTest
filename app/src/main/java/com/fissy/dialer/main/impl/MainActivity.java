/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.fissy.dialer.main.impl;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.fissy.dialer.blockreportspam.ShowBlockReportSpamDialogReceiver;
import com.fissy.dialer.calllog.config.CallLogConfigComponent;
import com.fissy.dialer.common.Assert;
import com.fissy.dialer.common.LogUtil;
import com.fissy.dialer.interactions.PhoneNumberInteraction.DisambigDialogDismissedListener;
import com.fissy.dialer.interactions.PhoneNumberInteraction.InteractionErrorCode;
import com.fissy.dialer.interactions.PhoneNumberInteraction.InteractionErrorListener;
import com.fissy.dialer.main.MainActivityPeer;
import com.fissy.dialer.main.impl.bottomnav.BottomNavBar.TabIndex;
import com.fissy.dialer.util.PermissionManager;
import com.fissy.dialer.util.TransactionSafeActivity;

import java.util.List;
import java.util.Map;

/**
 * This is the main activity for dialer. It hosts favorites, call log, search, dialpad, etc...
 */
// TODO(calderwoodra): Do not extend TransactionSafeActivity after new SpeedDial is launched
public class MainActivity extends TransactionSafeActivity
        implements MainActivityPeer.PeerSupplier,
        // TODO(calderwoodra): remove these 2 interfaces when we migrate to new speed dial fragment
        InteractionErrorListener,
        DisambigDialogDismissedListener,
        PermissionDialogFragment.PermissionDialogListener,
        WriteSettingsWarningDialogFragment.WriteSettingsWarningListener {

    private MainActivityPeer activePeer;
    private PermissionManager permissionManager;
    private boolean permissionsChecked = false;

    /**
     * {@link android.content.BroadcastReceiver} that shows a dialog to block a number and/or report
     * it as spam when notified.
     */
    private ShowBlockReportSpamDialogReceiver showBlockReportSpamDialogReceiver;

    public static Intent getShowCallLogIntent(Context context) {
        return getShowTabIntent(context, TabIndex.CALL_LOG);
    }

    /**
     * Returns intent that will open MainActivity to the specified tab.
     */
    public static Intent getShowTabIntent(Context context, @TabIndex int tabIndex) {
        if (CallLogConfigComponent.get(context).callLogConfig().isNewPeerEnabled()) {
            // TODO(calderwoodra): implement this in NewMainActivityPeer
            return null;
        }
        return OldMainActivityPeer.getShowTabIntent(context, tabIndex);
    }

    /**
     * @param context Context of the application package implementing MainActivity class.
     * @return intent for MainActivity.class
     */
    public static Intent getIntent(Context context) {
        return new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LogUtil.enterBlock("MainActivity.onCreate");
        
        // Initialize permission manager
        permissionManager = new PermissionManager(this);
        permissionManager.initialize();
        
        // If peer was set by the super, don't reset it.
        activePeer = getNewPeer();
        activePeer.onActivityCreate(savedInstanceState);

        showBlockReportSpamDialogReceiver =
                new ShowBlockReportSpamDialogReceiver(getSupportFragmentManager());
    }

    protected MainActivityPeer getNewPeer() {
        if (CallLogConfigComponent.get(this).callLogConfig().isNewPeerEnabled()) {
            return new NewMainActivityPeer(this);
        } else {
            return new OldMainActivityPeer(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        activePeer.onNewIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        activePeer.onActivityResume();

        LocalBroadcastManager.getInstance(this)
                .registerReceiver(
                        showBlockReportSpamDialogReceiver, ShowBlockReportSpamDialogReceiver.getIntentFilter());
        
        // Check and request permissions on first resume
        if (!permissionsChecked) {
            permissionsChecked = true;
            checkAndRequestPermissions();
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        activePeer.onUserLeaveHint();
    }

    @Override
    protected void onPause() {
        super.onPause();
        activePeer.onActivityPause();

        LocalBroadcastManager.getInstance(this).unregisterReceiver(showBlockReportSpamDialogReceiver);
    }

    @Override
    protected void onStop() {
        super.onStop();
        activePeer.onActivityStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle bundle) {
        super.onSaveInstanceState(bundle);
        activePeer.onSaveInstanceState(bundle);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        activePeer.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (activePeer.onBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void interactionError(@InteractionErrorCode int interactionErrorCode) {
        switch (interactionErrorCode) {
            case InteractionErrorCode.USER_LEAVING_ACTIVITY:
                // This is expected to happen if the user exits the activity before the interaction occurs.
                return;
            case InteractionErrorCode.CONTACT_NOT_FOUND:
            case InteractionErrorCode.CONTACT_HAS_NO_NUMBER:
            case InteractionErrorCode.OTHER_ERROR:
            default:
                // All other error codes are unexpected. For example, it should be impossible to start an
                // interaction with an invalid contact from this activity.
                throw Assert.createIllegalStateFailException(
                        "PhoneNumberInteraction error: " + interactionErrorCode);
        }
    }

    @Override
    public void onDisambigDialogDismissed() {
        // Don't do anything; the app will remain open with favorites tiles displayed.
    }

    @Override
    public MainActivityPeer getPeer() {
        return activePeer;
    }

    /**
     * Check and request necessary permissions
     */
    private void checkAndRequestPermissions() {
        // Check if we need to request permissions
        List<String> deniedPermissions = permissionManager.getDeniedPermissions();
        
        if (!deniedPermissions.isEmpty() && permissionManager.isFirstRequest()) {
            // Show explanation dialog
            PermissionDialogFragment dialog = PermissionDialogFragment.newInstance(deniedPermissions);
            dialog.setListener(this);
            dialog.show(getSupportFragmentManager(), "permissions");
        } else if (!permissionManager.hasAllRequiredPermissions()) {
            // Request permissions directly if not first request
            requestPermissions();
        } else {
            // All permissions granted, check default dialer role
            checkDefaultDialerRole();
        }
    }

    /**
     * Request runtime permissions
     */
    private void requestPermissions() {
        permissionManager.requestPermissions(new PermissionManager.PermissionCallback() {
            @Override
            public void onPermissionsGranted(Map<String, Boolean> results) {
                LogUtil.i("MainActivity", "Permissions result: " + results);
                // Check if all critical permissions were granted
                boolean allGranted = true;
                for (Boolean granted : results.values()) {
                    if (!granted) {
                        allGranted = false;
                        break;
                    }
                }
                
                if (allGranted) {
                    // Check default dialer role after permissions are granted
                    checkDefaultDialerRole();
                }
            }

            @Override
            public void onDefaultDialerRoleResult(boolean granted) {
                LogUtil.i("MainActivity", "Default dialer role: " + granted);
            }

            @Override
            public void onFullScreenIntentPermissionResult(boolean granted) {
                LogUtil.i("MainActivity", "Full-screen intent permission: " + granted);
            }

            @Override
            public void onWriteSettingsPermissionResult(boolean granted) {
                LogUtil.i("MainActivity", "Write settings permission: " + granted);
            }
        });
    }

    /**
     * Check and request default dialer role
     */
    private void checkDefaultDialerRole() {
        if (!permissionManager.isDefaultDialer() && !permissionManager.hasDialerRoleBeenRequested()) {
            permissionManager.requestDefaultDialerRole(new PermissionManager.PermissionCallback() {
                @Override
                public void onPermissionsGranted(Map<String, Boolean> results) {
                    // Not used for dialer role
                }

                @Override
                public void onDefaultDialerRoleResult(boolean granted) {
                    LogUtil.i("MainActivity", "Default dialer role granted: " + granted);
                }

                @Override
                public void onFullScreenIntentPermissionResult(boolean granted) {
                    // Not used for dialer role
                }

                @Override
                public void onWriteSettingsPermissionResult(boolean granted) {
                    // Not used for dialer role
                }
            });
        }
    }

    @Override
    public void onPermissionsRequested() {
        // User agreed to grant permissions, show system dialog
        requestPermissions();
    }

    @Override
    public void onPermissionsDenied() {
        // User declined to grant permissions
        LogUtil.i("MainActivity", "User declined permissions");
    }

    @Override
    public void onWriteSettingsAllowed() {
        // User agreed to grant WRITE_SETTINGS, launch settings
        LogUtil.i("MainActivity", "User agreed to grant WRITE_SETTINGS");
        permissionManager.requestWriteSettingsPermission(new PermissionManager.PermissionCallback() {
            @Override
            public void onPermissionsGranted(Map<String, Boolean> results) {
                // Not used for write settings
            }

            @Override
            public void onDefaultDialerRoleResult(boolean granted) {
                // Not used for write settings
            }

            @Override
            public void onFullScreenIntentPermissionResult(boolean granted) {
                // Not used for write settings
            }

            @Override
            public void onWriteSettingsPermissionResult(boolean granted) {
                LogUtil.i("MainActivity", "Write settings permission result: " + granted);
            }
        });
    }

    @Override
    public void onWriteSettingsCancelled() {
        // User cancelled WRITE_SETTINGS request
        LogUtil.i("MainActivity", "User cancelled WRITE_SETTINGS request");
    }

    /**
     * Show warning dialog for WRITE_SETTINGS permission
     */
    public void showWriteSettingsWarningDialog() {
        WriteSettingsWarningDialogFragment dialog = WriteSettingsWarningDialogFragment.newInstance();
        dialog.setListener(this);
        dialog.show(getSupportFragmentManager(), "write_settings_warning");
    }
}
