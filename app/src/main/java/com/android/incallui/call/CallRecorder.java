/*
 * Copyright (C) 2014 The CyanogenMod Project
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

package com.android.incallui.call;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.fissy.dialer.R;
import com.fissy.dialer.callrecord.CallRecordingDataStore;
import com.fissy.dialer.callrecord.CallRecording;
import com.fissy.dialer.callrecord.ICallRecorderService;
import com.fissy.dialer.callrecord.impl.CallRecorderService;
import com.fissy.dialer.location.GeoUtil;
import com.android.incallui.call.state.DialerCallState;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * InCall UI's interface to the call recorder
 *
 * Manages the call recorder service lifecycle.  We bind to the service whenever an active call
 * is established, and unbind when all calls have been disconnected.
 */
public class CallRecorder implements CallList.Listener {
  public static final String TAG = "CallRecorder";

  public static final String[] REQUIRED_PERMISSIONS = 
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU 
        ? new String[] {
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.READ_MEDIA_AUDIO
          }
        : new String[] {
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
          };
  private static final HashMap<String, Boolean> RECORD_ALLOWED_STATE_BY_COUNTRY = new HashMap<>();

  private static CallRecorder instance = null;
  private Context context;
  private boolean initialized = false;
  private ICallRecorderService service = null;
  private PendingRecording pendingRecording = null;
  private int bindRetryCount = 0;
  private static final int MAX_RETRIES = 3;
  private static final long RETRY_BASE_DELAY_MS = 2000L; // 2 seconds base delay
  private Handler retryHandler = new Handler(Looper.getMainLooper());

  private HashSet<RecordingProgressListener> progressListeners =
      new HashSet<RecordingProgressListener>();
  private Handler handler = new Handler();
  
  private static class PendingRecording {
    String phoneNumber;
    long creationTime;
    
    PendingRecording(String phoneNumber, long creationTime) {
      this.phoneNumber = phoneNumber;
      this.creationTime = creationTime;
    }
  }

  private ServiceConnection connection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
      Log.i(TAG, "✓ Service connected successfully");
      CallRecorder.this.service = ICallRecorderService.Stub.asInterface(service);
      
      // If there's a pending recording request, start it now
      if (pendingRecording != null) {
        Log.i(TAG, "Processing pending recording request");
        PendingRecording pending = pendingRecording;
        // Clear before calling - the recursive call will now take the service != null path
        pendingRecording = null;
        startRecording(pending.phoneNumber, pending.creationTime);
      }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      Log.i(TAG, "✗ Service disconnected");
      CallRecorder.this.service = null;
    }
  };

  public static CallRecorder getInstance() {
    if (instance == null) {
      Log.i(TAG, "Creating new CallRecorder instance");
      instance = new CallRecorder();
    }
    return instance;
  }

  public boolean isEnabled() {
    return CallRecorderService.isEnabled(context);
  }

  public boolean canRecordInCurrentCountry() {
      if (!isEnabled()) {
          return false;
      }
      if (RECORD_ALLOWED_STATE_BY_COUNTRY.isEmpty()) {
          loadAllowedStates();
      }

      String currentCountryIso = GeoUtil.getCurrentCountryIso(context);
      Boolean allowedState = RECORD_ALLOWED_STATE_BY_COUNTRY.get(currentCountryIso);

      return allowedState != null && allowedState;
  }

  private CallRecorder() {
    Log.i(TAG, "CallRecorder constructor called");
    CallList.getInstance().addListener(this);
  }

  public void setUp(Context context) {
    this.context = context.getApplicationContext();
    Log.i(TAG, "==========================================");
    Log.i(TAG, "CALLRECORDER SETUP");
    Log.i(TAG, "Context: " + (context != null ? "✓ Valid" : "✗ Null"));
    Log.i(TAG, "==========================================");
  }

  private void initialize() {
    if (isEnabled() && !initialized) {
      Log.i(TAG, "==========================================");
      Log.i(TAG, "INITIALIZING CALLRECORDER - BINDING TO SERVICE");
      Log.i(TAG, "==========================================");
      bindRetryCount = 0;  // Reset retry count
      bindService();
      initialized = true;
    } else {
      Log.i(TAG, "Initialize called - enabled: " + isEnabled() + ", initialized: " + initialized);
    }
  }
  
  private void bindService() {
    Log.i(TAG, "bindService() attempt #" + (bindRetryCount + 1));
    
    if (context == null) {
      Log.e(TAG, "✗ Context is null, cannot bind service");
      return;
    }
    
    Intent serviceIntent = new Intent(context, CallRecorderService.class);
    
    try {
      // Try to start the service first to ensure it's running
      try {
        context.startService(serviceIntent);
        Log.i(TAG, "✓ Service started");
      } catch (Exception e) {
        Log.i(TAG, "⚠ Could not start service: " + e.getMessage());
      }
      
      // Then bind
      boolean bound = context.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
      Log.i(TAG, "Service binding initiated: " + (bound ? "✓" : "✗"));
      
      if (bound) {
        bindRetryCount = 0;  // Reset retry count on success
      } else {
        Log.i(TAG, "✗ bindService returned false");
        scheduleRetry();
      }
    } catch (Exception e) {
      Log.e(TAG, "✗ Exception binding service", e);
      scheduleRetry();
    }
  }
  
  private void scheduleRetry() {
    if (bindRetryCount < MAX_RETRIES) {
      bindRetryCount++;
      // Use bit shifting for exponential backoff: 2s, 4s, 8s
      long delayMs = RETRY_BASE_DELAY_MS << (bindRetryCount - 1);
      
      Log.i(TAG, "⚠ Scheduling retry #" + bindRetryCount + " in " + delayMs + "ms");
      
      retryHandler.postDelayed(new Runnable() {
        @Override
        public void run() {
          Log.i(TAG, "Retrying service bind...");
          bindService();
        }
      }, delayMs);
    } else {
      Log.e(TAG, "✗✗✗ Max retries reached, service binding failed ✗✗✗");
      if (context != null) {
        Toast.makeText(context, R.string.call_recording_failed_message, Toast.LENGTH_LONG).show();
      }
    }
  }

  private void uninitialize() {
    if (initialized) {
      Log.i(TAG, "==========================================");
      Log.i(TAG, "UNINITIALIZING CALLRECORDER - UNBINDING SERVICE");
      Log.i(TAG, "==========================================");
      
      // Cancel any pending retries
      retryHandler.removeCallbacksAndMessages(null);
      
      try {
        context.unbindService(connection);
        Log.i(TAG, "✓ Service unbound");
      } catch (Exception e) {
        Log.e(TAG, "✗ Error unbinding service", e);
      }
      
      initialized = false;
      service = null;
    }
  }

  public boolean startRecording(final String phoneNumber, final long creationTime) {
    // Mask phone number for privacy - only show last 4 digits
    String maskedNumber = phoneNumber != null && phoneNumber.length() > 4 
        ? "***" + phoneNumber.substring(phoneNumber.length() - 4) 
        : "****";
    Log.i(TAG, "==========================================");
    Log.i(TAG, "START RECORDING CALLED");
    Log.i(TAG, "Phone: " + maskedNumber);
    Log.i(TAG, "Time: " + creationTime);
    Log.i(TAG, "Context: " + (context != null ? "✓" : "✗"));
    Log.i(TAG, "Initialized: " + (initialized ? "✓" : "✗"));
    Log.i(TAG, "Service: " + (service != null ? "✓" : "✗"));
    Log.i(TAG, "==========================================");
    
    if (service == null) {
      Log.i(TAG, "Service is null - checking if we should queue or fail");
      
      // If we're initialized but service isn't bound yet, queue the request
      if (initialized) {
        Log.i(TAG, "⚠ Service binding in progress, queueing recording request");
        pendingRecording = new PendingRecording(phoneNumber, creationTime);
        Toast.makeText(context, R.string.call_recording_starting, Toast.LENGTH_SHORT).show();
        // Don't show toast here - wait for actual recording to start to avoid duplicate toasts
        return true;
      } else {
        Log.e(TAG, "✗ Service not initialized - cannot start recording");
        Toast.makeText(context, R.string.call_recording_failed_message, Toast.LENGTH_SHORT)
            .show();
        return false;
      }
    }

    try {
      Log.i(TAG, "Calling service.startRecording()...");
      if (service.startRecording(phoneNumber, creationTime)) {
        Log.i(TAG, "✓ Recording started successfully");
        for (RecordingProgressListener l : progressListeners) {
          l.onStartRecording();
        }
        updateRecordingProgressTask.run();
        Toast.makeText(context, R.string.onscreenCallRecordText, Toast.LENGTH_SHORT).show();
        return true;
      } else {
        Log.e(TAG, "✗ Service returned false for startRecording");
        Toast.makeText(context, R.string.call_recording_failed_message, Toast.LENGTH_SHORT)
            .show();
      }
    } catch (RemoteException e) {
      Log.e(TAG, "✗ RemoteException when starting recording", e);
      Toast.makeText(context, R.string.call_recording_failed_message, Toast.LENGTH_SHORT)
          .show();
    }

    return false;
  }

  public boolean isRecording() {
    if (service == null) {
      return false;
    }

    try {
      return service.isRecording();
    } catch (RemoteException e) {
      Log.w(TAG, "Exception checking recording status", e);
    }
    return false;
  }

  public CallRecording getActiveRecording() {
    if (service == null) {
      return null;
    }

    try {
      return service.getActiveRecording();
    } catch (RemoteException e) {
      Log.w("Exception getting active recording", e);
    }
    return null;
  }

  public void finishRecording() {
    Log.i(TAG, "==========================================");
    Log.i(TAG, "FINISH RECORDING CALLED");
    Log.i(TAG, "==========================================");
    if (service != null) {
      try {
        Log.i(TAG, "Calling service.stopRecording()");
        final CallRecording recording = service.stopRecording();
        if (recording != null) {
          // Mask phone number for privacy
          String maskedNumber = recording.phoneNumber != null && recording.phoneNumber.length() > 4
              ? "***" + recording.phoneNumber.substring(recording.phoneNumber.length() - 4)
              : "****";
          Log.i(TAG, "✓ Recording stopped successfully - phoneNumber: " + maskedNumber 
              + ", fileName: " + recording.fileName);
          if (!TextUtils.isEmpty(recording.phoneNumber)) {
            new Thread(() -> {
              CallRecordingDataStore dataStore = new CallRecordingDataStore();
              dataStore.open(context);
              dataStore.putRecording(recording);
              dataStore.close();
            }).start();
          } else {
            // Data store is an index by number so that we can link recordings in the
            // call detail page.  If phone number is not available (conference call or
            // unknown number) then just display a toast.
            String msg = context.getResources().getString(
                R.string.call_recording_file_location, recording.fileName);
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
          }
        } else {
          Log.i(TAG, "⚠ Recording was null after stop");
        }
      } catch (RemoteException e) {
        Log.e(TAG, "✗ Failed to stop recording", e);
      }
    } else {
      Log.i(TAG, "✗ Service is null, cannot stop recording");
    }

    for (RecordingProgressListener l : progressListeners) {
      l.onStopRecording();
    }
    handler.removeCallbacks(updateRecordingProgressTask);
  }

  //
  // Call list listener methods.
  //
  @Override
  public void onIncomingCall(DialerCall call) {
    // do nothing
  }

  @Override
  public void onCallListChange(final CallList callList) {
    if (!initialized && callList.getActiveCall() != null) {
      // we'll come here if this is the first active call
      initialize();
    } else {
      // we can come down this branch to resume a call that was on hold
      CallRecording active = getActiveRecording();
      if (active != null) {
        DialerCall call =
            callList.getCallWithStateAndNumber(DialerCallState.ONHOLD, active.phoneNumber);
        if (call != null) {
          // The call associated with the active recording has been placed
          // on hold, so stop the recording.
          finishRecording();
        }
      }
    }
  }

  @Override
  public void onDisconnect(final DialerCall call) {
    CallRecording active = getActiveRecording();
    if (active != null && TextUtils.equals(call.getNumber(), active.phoneNumber)) {
      // finish the current recording if the call gets disconnected
      finishRecording();
    }

    // tear down the service if there are no more active calls
    if (CallList.getInstance().getActiveCall() == null) {
      uninitialize();
    }
  }

  @Override
  public void onUpgradeToVideo(DialerCall call) {}

  @Override
  public void onSessionModificationStateChange(DialerCall call) {}

  @Override
  public void onWiFiToLteHandover(DialerCall call) {}

  @Override
  public void onHandoverToWifiFailed(DialerCall call) {}

  @Override
  public void onInternationalCallOnWifi(DialerCall call) {}

  // allow clients to listen for recording progress updates
  public interface RecordingProgressListener {
    void onStartRecording();
    void onStopRecording();
    void onRecordingTimeProgress(long elapsedTimeMs);
  }

  public void addRecordingProgressListener(RecordingProgressListener listener) {
    progressListeners.add(listener);
  }

  public void removeRecordingProgressListener(RecordingProgressListener listener) {
    progressListeners.remove(listener);
  }

  private static final int UPDATE_INTERVAL = 500;

  private Runnable updateRecordingProgressTask = new Runnable() {
    @Override
    public void run() {
      CallRecording active = getActiveRecording();
      if (active != null) {
        long elapsed = System.currentTimeMillis() - active.startRecordingTime;
        for (RecordingProgressListener l : progressListeners) {
          l.onRecordingTimeProgress(elapsed);
        }
      }
      handler.postDelayed(this, UPDATE_INTERVAL);
    }
  };

  private void loadAllowedStates() {
    XmlResourceParser parser = context.getResources().getXml(R.xml.call_record_states);
    try {
        // Consume all START_DOCUMENT which can appear more than once.
        while (parser.next() == XmlPullParser.START_DOCUMENT) {}

        parser.require(XmlPullParser.START_TAG, null, "call-record-allowed-flags");

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            parser.require(XmlPullParser.START_TAG, null, "country");

            String iso = parser.getAttributeValue(null, "iso");
            String allowed = parser.getAttributeValue(null, "allowed");
            if (iso != null && ("true".equals(allowed) || "false".equals(allowed))) {
                for (String splittedIso : iso.split(",")) {
                    RECORD_ALLOWED_STATE_BY_COUNTRY.put(
                            splittedIso.toUpperCase(Locale.US), Boolean.valueOf(allowed));
                }
            } else {
                throw new XmlPullParserException("Unexpected country specification", parser, null);
            }
        }
        Log.i(TAG, "✓ Loaded " + RECORD_ALLOWED_STATE_BY_COUNTRY.size() + " country records");
    } catch (XmlPullParserException | IOException e) {
        Log.e(TAG, "✗ Could not parse allowed country list", e);
        RECORD_ALLOWED_STATE_BY_COUNTRY.clear();
    } finally {
        parser.close();
    }
  }
}
