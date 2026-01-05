# Full-Screen Notification and WRITE_SETTINGS Permission Implementation

## Overview

This document describes the implementation of automatic permission handling for full-screen notifications on Android 14+ and the WRITE_SETTINGS permission flow with warning dialog.

## Problem Statement

Previously, users had to manually enable:
1. "Allow full-screen notification" permission (Android 14+)
2. "Modify system settings" permission (WRITE_SETTINGS)

This created a poor user experience as these permissions are critical for the dialer app to function properly.

## Solution

### 1. Full-Screen Notification Permission (Android 14+)

#### Background
On Android 14+ (API 34+), apps need to explicitly request the `USE_FULL_SCREEN_INTENT` permission even though it's declared in AndroidManifest.xml. The permission is automatically granted on Android 13 and below.

#### Implementation

**PermissionManager.java**:
- `canUseFullScreenIntent()`: Checks if the app has full-screen intent permission
  - Uses `NotificationManager.canUseFullScreenIntent()` on Android 14+
  - Returns `true` automatically on Android 13 and below
  
- `checkAndRequestFullScreenIntent(callback)`: Checks and requests the permission if needed
  - Only triggers on Android 14+ if permission not granted
  - Launches settings via `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`
  
- **Automatic Flow**: In `initializeLaunchers()`, when `POST_NOTIFICATIONS` permission is granted, the system automatically checks and prompts for full-screen intent permission

#### Flow Diagram
```
User grants POST_NOTIFICATIONS
  ↓
System checks Android version
  ↓
Android 14+? 
  ↓ Yes
Check canUseFullScreenIntent()
  ↓ Not granted
Launch Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT
  ↓
User grants permission
  ↓
Callback: onFullScreenIntentPermissionResult(true)
```

### 2. WRITE_SETTINGS Permission

#### Background
The `WRITE_SETTINGS` permission allows the app to modify system settings. This is a special permission that requires user consent via system settings.

#### Implementation

**PermissionManager.java**:
- `canWriteSettings()`: Checks if the app can write system settings
  - Uses `Settings.System.canWrite()` on Android 6.0+
  
- `requestWriteSettingsPermission(callback)`: Requests the permission
  - Launches settings via `Settings.ACTION_MANAGE_WRITE_SETTINGS`
  
**WriteSettingsWarningDialogFragment.java**:
- New dialog fragment that explains why the permission is needed
- Shows before launching settings screen
- Follows existing permission dialog pattern

**MainActivity.java**:
- Implements `WriteSettingsWarningListener` interface
- `showWriteSettingsWarningDialog()`: Public method to show the warning dialog
- `onWriteSettingsAllowed()`: Called when user taps "Allow"
- `onWriteSettingsCancelled()`: Called when user taps "Cancel"

#### Flow Diagram
```
App needs WRITE_SETTINGS
  ↓
showWriteSettingsWarningDialog()
  ↓
Display warning explaining permission
  ↓
User taps "Allow"
  ↓
requestWriteSettingsPermission()
  ↓
Launch Settings.ACTION_MANAGE_WRITE_SETTINGS
  ↓
User grants permission
  ↓
Callback: onWriteSettingsPermissionResult(true)
```

## Usage

### For Full-Screen Intents (Automatic)

The full-screen intent permission is automatically handled when notification permission is granted. No additional code needed.

```java
// This is already integrated in MainActivity
permissionManager.requestPermissions(callback);
// If POST_NOTIFICATIONS is granted on Android 14+,
// full-screen intent will be automatically checked
```

### For WRITE_SETTINGS (Manual Trigger)

To request WRITE_SETTINGS permission:

```java
MainActivity mainActivity = ...;
mainActivity.showWriteSettingsWarningDialog();
```

Or directly through PermissionManager:

```java
permissionManager.requestWriteSettingsPermission(new PermissionManager.PermissionCallback() {
    @Override
    public void onWriteSettingsPermissionResult(boolean granted) {
        if (granted) {
            // Permission granted, can now modify system settings
        } else {
            // Permission denied
        }
    }
    
    @Override
    public void onPermissionsGranted(Map<String, Boolean> results) { }
    
    @Override
    public void onDefaultDialerRoleResult(boolean granted) { }
    
    @Override
    public void onFullScreenIntentPermissionResult(boolean granted) { }
});
```

## Files Modified

### Core Implementation
1. **app/src/main/java/com/fissy/dialer/util/PermissionManager.java**
   - Added full-screen intent permission checking and requesting
   - Added WRITE_SETTINGS permission checking and requesting
   - Added automatic full-screen permission check after notifications

2. **app/src/main/java/com/fissy/dialer/main/impl/MainActivity.java**
   - Updated to implement new callback interface methods
   - Added WRITE_SETTINGS warning dialog integration

### New Files
3. **app/src/main/java/com/fissy/dialer/main/impl/WriteSettingsWarningDialogFragment.java**
   - Warning dialog for WRITE_SETTINGS permission

4. **app/src/main/res/layout/dialog_write_settings_warning.xml**
   - Layout for WRITE_SETTINGS warning dialog

### Resources
5. **app/src/main/res/values/strings.xml**
   - Added strings for WRITE_SETTINGS warning dialog

## API Level Requirements

- **Full-Screen Intent Permission**: Android 14+ (API 34+)
  - Automatically granted on Android 13 and below
  - Uses `Build.VERSION_CODES.UPSIDE_DOWN_CAKE`

- **WRITE_SETTINGS Permission**: Android 6.0+ (API 23+)
  - Automatically granted on Android 5.1 and below
  - Uses `Settings.System.canWrite()`

## Testing

### Manual Testing

1. **Full-Screen Intent (Android 14+)**:
   - Install app on Android 14+ device
   - Launch app and go through permission flow
   - Grant notification permission
   - Verify that full-screen intent settings screen opens automatically
   - Grant full-screen intent permission
   - Make a test call to verify full-screen notification works

2. **WRITE_SETTINGS**:
   - Call `showWriteSettingsWarningDialog()` in MainActivity
   - Verify warning dialog appears with correct text
   - Tap "Allow" and verify settings screen opens
   - Grant permission in settings
   - Return to app and verify callback is triggered

3. **Edge Cases**:
   - Test permission denial (user returns without granting)
   - Test on Android 13 and below (full-screen should not prompt)
   - Test callback handling for all permission combinations

## Backward Compatibility

- All changes are backward compatible with Android API 26+
- Full-screen intent check only runs on Android 14+
- WRITE_SETTINGS check only runs on Android 6.0+
- Older Android versions continue to work as before

## Future Enhancements

Possible future improvements:
1. Add persistent notification to remind users about missing permissions
2. Add analytics to track permission grant rates
3. Add retry mechanism for permission requests
4. Integrate WRITE_SETTINGS request into main permission flow based on app features

## References

- [Android Full-Screen Intent Changes](https://developer.android.com/about/versions/14/changes/fsi-permission)
- [WRITE_SETTINGS Permission](https://developer.android.com/reference/android/provider/Settings.System#canWrite(android.content.Context))
- AndroidManifest.xml line 46: `USE_FULL_SCREEN_INTENT` declaration
- AndroidManifest.xml line 30: `WRITE_SETTINGS` declaration
- StatusBarNotifier.java line 1054: `configureFullScreenIntent()` method
