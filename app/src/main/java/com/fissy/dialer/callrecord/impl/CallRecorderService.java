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

package com.fissy.dialer.callrecord.impl;

import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.provider.Settings;
import android.util.Log;

import com.fissy.dialer.callrecord.CallRecording;
import com.fissy.dialer.callrecord.ICallRecorderService;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import com.fissy.dialer.R;

public class CallRecorderService extends Service {
  private static final String TAG = "CallRecorderService";
  private static final boolean DBG = false;
  private static final String PERMISSION_CAPTURE_AUDIO_OUTPUT = "android.permission.CAPTURE_AUDIO_OUTPUT";

  private MediaRecorder mMediaRecorder = null;
  private CallRecording mCurrentRecording = null;

  private SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyMMdd_HHmmssSSS");

  private final ICallRecorderService.Stub mBinder = new ICallRecorderService.Stub() {
    @Override
    public CallRecording stopRecording() {
      Log.i(TAG, "AIDL stopRecording called");
      return stopRecordingInternal();
    }

    @Override
    public boolean startRecording(String phoneNumber, long creationTime) throws RemoteException {
      // Mask phone number for privacy - only show last 4 digits
      String maskedNumber = phoneNumber != null && phoneNumber.length() > 4
          ? "***" + phoneNumber.substring(phoneNumber.length() - 4)
          : "****";
      Log.i(TAG, "==========================================");
      Log.i(TAG, "AIDL START RECORDING CALLED");
      Log.i(TAG, "Phone: " + maskedNumber);
      Log.i(TAG, "Time: " + creationTime);
      Log.i(TAG, "==========================================");
      return startRecordingInternal(phoneNumber, creationTime);
    }

    @Override
    public boolean isRecording() throws RemoteException {
      boolean recording = mMediaRecorder != null;
      Log.i(TAG, "AIDL isRecording called - result: " + recording);
      return recording;
    }

    @Override
    public CallRecording getActiveRecording() throws RemoteException {
      Log.i(TAG, "AIDL getActiveRecording called - result: " + (mCurrentRecording != null ? "recording active" : "null"));
      return mCurrentRecording;
    }
  };

  @Override
  public void onCreate() {
    super.onCreate();
    Log.i(TAG, "==========================================");
    Log.i(TAG, "SERVICE CREATED");
    Log.i(TAG, "==========================================");
  }

  @Override
  public IBinder onBind(Intent intent) {
    Log.i(TAG, "==========================================");
    Log.i(TAG, "SERVICE BIND CALLED");
    Log.i(TAG, "Intent: " + intent);
    Log.i(TAG, "==========================================");
    return mBinder;
  }

  private int getAudioSource() {
    // Android 10+ (API 29+) can use VOICE_CALL through InCallService
    // This captures the actual call audio stream (both sides)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
      Log.i(TAG, "==========================================");
      Log.i(TAG, "AUDIO SOURCE SELECTION");
      Log.i(TAG, "Android version: " + android.os.Build.VERSION.SDK_INT);
      Log.i(TAG, "Using VOICE_CALL audio source (value: 4)");
      Log.i(TAG, "==========================================");
      return MediaRecorder.AudioSource.VOICE_CALL;
    }
    
    // Android 9 and below: limited to microphone-based sources
    // Use config value or fallback to VOICE_RECOGNITION
    Log.i(TAG, "Using legacy audio source (Android 9-)");
    return getResources().getInteger(R.integer.call_recording_audio_source);
  }

  private int getAudioFormatChoice() {
    // This replicates PreferenceManager.getDefaultSharedPreferences, except
    // that we need multi process preferences, as the pref is written in a separate
    // process (com.android.dialer vs. com.android.incallui)
    final String prefName = getPackageName() + "_preferences";
    final SharedPreferences prefs = getSharedPreferences(prefName, MODE_MULTI_PROCESS);

    try {
      String value = prefs.getString(getString(R.string.call_recording_format_key), null);
      if (value != null) {
        return Integer.parseInt(value);
      }
    } catch (NumberFormatException e) {
      // ignore and fall through
    }
    return 0;
  }

  private synchronized boolean startRecordingInternal(String phoneNumber, long creationTime) {
    // Mask phone number for privacy - only show last 4 digits
    String maskedNumber = phoneNumber != null && phoneNumber.length() > 4
        ? "***" + phoneNumber.substring(phoneNumber.length() - 4)
        : "****";
    Log.i(TAG, "==========================================");
    Log.i(TAG, "START RECORDING INTERNAL");
    Log.i(TAG, "Phone: " + maskedNumber);
    Log.i(TAG, "Already recording: " + (mMediaRecorder != null));
    Log.i(TAG, "==========================================");
    
    if (mMediaRecorder != null) {
      Log.i(TAG, "⚠ Start called with recording in progress, stopping current recording");
      stopRecordingInternal();
    }

    // Check RECORD_AUDIO permission
    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      Log.e(TAG, "✗ RECORD_AUDIO permission not granted");
      return false;
    }
    Log.i(TAG, "✓ RECORD_AUDIO permission granted");

    // Check CAPTURE_AUDIO_OUTPUT permission (needed for VOICE_CALL)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
      boolean hasCapturePermission = checkSelfPermission(PERMISSION_CAPTURE_AUDIO_OUTPUT) == PackageManager.PERMISSION_GRANTED;
      Log.i(TAG, "CAPTURE_AUDIO_OUTPUT permission: " + (hasCapturePermission ? "✓ granted" : "✗ denied"));
      
      if (hasCapturePermission) {
        Log.i(TAG, "✓✓✓ CAPTURE_AUDIO_OUTPUT GRANTED ✓✓✓");
        Log.i(TAG, "VOICE_CALL audio source will be used");
        Log.i(TAG, "Both sides of call will be recorded");
      } else {
        Log.w(TAG, "⚠ CAPTURE_AUDIO_OUTPUT not granted - VOICE_CALL may fail");
        Log.w(TAG, "If device is rooted, app should have requested this permission");
        Log.w(TAG, "Will attempt VOICE_CALL anyway, with fallback if it fails");
      }
    }

    Log.i(TAG, "✓ Starting recording - initializing MediaRecorder");
    mMediaRecorder = new MediaRecorder();
    Log.i(TAG, "✓ MediaRecorder instance created");

    
    // Try multiple audio sources with fallback for non-rooted devices
    boolean audioSourceSet = false;
    int audioSource = -1; // Track which audio source is being used
    
    // Try the preferred audio source first
    int requestedSource = getAudioSource();
    try {
      Log.i(TAG, "==========================================");
      Log.i(TAG, "SETTING AUDIO SOURCE");
      Log.i(TAG, "Requested audio source: " + requestedSource + " (4=VOICE_CALL, 6=VOICE_RECOGNITION, 1=MIC)");
      Log.i(TAG, "==========================================");
      
      mMediaRecorder.setAudioSource(requestedSource);
      audioSourceSet = true;
      audioSource = requestedSource;
      
      Log.i(TAG, "✓ Successfully set audio source: " + audioSource);
    } catch (IllegalStateException e) {
      Log.e(TAG, "==========================================");
      Log.e(TAG, "✗ PRIMARY AUDIO SOURCE FAILED");
      Log.e(TAG, "Failed audio source: " + requestedSource);
      Log.e(TAG, "This may happen if:");
      Log.e(TAG, "  1. App is not the active InCallService");
      Log.e(TAG, "  2. CAPTURE_AUDIO_OUTPUT permission denied");
      Log.e(TAG, "  3. Device doesn't support VOICE_CALL for third-party apps");
      Log.e(TAG, "Error message: " + e.getMessage());
      Log.e(TAG, "Error cause: " + (e.getCause() != null ? e.getCause().getMessage() : "null"));
      Log.e(TAG, "Attempting fallback to VOICE_RECOGNITION...");
      Log.e(TAG, "==========================================", e);
      
      // Clean up failed MediaRecorder
      try {
        mMediaRecorder.release();
      } catch (Exception ex) {
        Log.w(TAG, "Error releasing MediaRecorder: " + ex.getMessage());
      }
      
      // Fallback 1: Try VOICE_RECOGNITION (most compatible for non-system apps)
      try {
        int fallbackSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;
        Log.i(TAG, "Trying fallback: VOICE_RECOGNITION (6)");
        mMediaRecorder = new MediaRecorder();
        mMediaRecorder.setAudioSource(fallbackSource);
        audioSourceSet = true;
        audioSource = fallbackSource;
        Log.i(TAG, "✓ Fallback VOICE_RECOGNITION successful (may only record microphone)");
      } catch (IllegalStateException e2) {
        Log.e(TAG, "✗ VOICE_RECOGNITION also failed, trying MIC as last resort", e2);
        
        // Clean up failed MediaRecorder
        try {
          mMediaRecorder.release();
        } catch (Exception ex) {
          // Ignore cleanup errors
        }
        
        // Fallback 2: Try MIC (works on all devices)
        try {
          int lastResortSource = MediaRecorder.AudioSource.MIC;
          Log.i(TAG, "Trying last resort: MIC (1)");
          mMediaRecorder = new MediaRecorder();
          mMediaRecorder.setAudioSource(lastResortSource);
          audioSourceSet = true;
          audioSource = lastResortSource;
          Log.i(TAG, "✓ Fallback MIC successful (may only record microphone)");
        } catch (IllegalStateException e3) {
          Log.e(TAG, "==========================================");
          Log.e(TAG, "✗✗✗ ALL AUDIO SOURCES FAILED ✗✗✗");
          Log.e(TAG, "Tried: VOICE_CALL, VOICE_RECOGNITION, MIC");
          Log.e(TAG, "Device may not support call recording");
          Log.e(TAG, "Final error: " + e3.getMessage(), e3);
          Log.e(TAG, "==========================================");
          audioSource = -1; // Mark as failed
        }
      }
    }
    
    if (!audioSourceSet) {
      Log.e(TAG, "✗ Failed to set any audio source");
      mMediaRecorder.release();
      mMediaRecorder = null;
      return false;
    }
    
    try {
      int formatChoice = getAudioFormatChoice();
      Log.i(TAG, "==========================================");
      Log.i(TAG, "CONFIGURING MEDIA RECORDER");
      Log.i(TAG, "Format choice: " + formatChoice + " (0=AMR_WB, 1=AAC)");
      
      mMediaRecorder.setOutputFormat(formatChoice == 0
          ? MediaRecorder.OutputFormat.AMR_WB : MediaRecorder.OutputFormat.MPEG_4);
      Log.i(TAG, "✓ Output format set");
      
      mMediaRecorder.setAudioEncoder(formatChoice == 0
          ? MediaRecorder.AudioEncoder.AMR_WB : MediaRecorder.AudioEncoder.AAC);
      Log.i(TAG, "✓ Audio encoder set");
      
      // For high-quality AAC recording, set bitrate and sample rate
      if (formatChoice != 0) {
        mMediaRecorder.setAudioEncodingBitRate(128000);
        mMediaRecorder.setAudioSamplingRate(44100);
        Log.i(TAG, "✓ High quality settings applied (AAC 128kbps, 44.1kHz)");
      }
      Log.i(TAG, "==========================================");
    } catch (IllegalStateException e) {
      Log.e(TAG, "==========================================");
      Log.e(TAG, "✗ MEDIARECORDER CONFIGURATION FAILED");
      Log.e(TAG, "Error: " + e.getMessage(), e);
      Log.e(TAG, "==========================================");
      mMediaRecorder.release();
      mMediaRecorder = null;
      return false;
    }

    String fileName = generateFilename(phoneNumber);
    Log.i(TAG, "Generated filename: " + fileName);
    Uri uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            CallRecording.generateMediaInsertValues(fileName, creationTime));
    Log.i(TAG, "Created media store entry: " + uri);

    try {
      ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w");
      if (pfd == null) {
        throw new IOException("Opening file for URI " + uri + " failed");
      }
      mMediaRecorder.setOutputFile(pfd.getFileDescriptor());
      Log.i(TAG, "MediaRecorder configured, preparing...");
      mMediaRecorder.prepare();
      Log.i(TAG, "MediaRecorder prepared, starting...");
      mMediaRecorder.start();

      long mediaId = Long.parseLong(uri.getLastPathSegment());
      mCurrentRecording = new CallRecording(phoneNumber, creationTime,
              fileName, System.currentTimeMillis(), mediaId);
      Log.i(TAG, "✓✓✓ RECORDING STARTED SUCCESSFULLY ✓✓✓");
      Log.i(TAG, "Audio source used: " + audioSource);
      return true;
    } catch (IOException | IllegalStateException e) {
      Log.e(TAG, "✗ Could not start recording", e);
      getContentResolver().delete(uri, null, null);
    } catch (RuntimeException e) {
      String message = e.getMessage();
      boolean isStartFailure = message != null && message.contains("start failed");
      
      if (isStartFailure) {
        Log.e(TAG, "==========================================");
        Log.e(TAG, "✗ MEDIARECORDER START FAILED");
        Log.e(TAG, "Failed with audio source: " + audioSource);
        Log.e(TAG, "Error: " + message);
        
        // Check if this was VOICE_CALL and we should try fallback
        if (audioSource == MediaRecorder.AudioSource.VOICE_CALL) {
          Log.w(TAG, "VOICE_CALL failed (likely permission denied)");
          Log.w(TAG, "Attempting fallback to VOICE_RECOGNITION...");
          Log.e(TAG, "==========================================");
          
          getContentResolver().delete(uri, null, null);
          mMediaRecorder.release();
          mMediaRecorder = null;
          
          // Retry with fallback audio source
          return startRecordingWithFallback(phoneNumber, creationTime);
        }
        
        Log.e(TAG, "==========================================", e);
      }
      
      getContentResolver().delete(uri, null, null);
      if (!isStartFailure) {
        throw e;
      }
    }

    mMediaRecorder.release();
    mMediaRecorder = null;

    return false;
  }

  /**
   * Retry recording with fallback audio sources when VOICE_CALL fails.
   * Tries VOICE_RECOGNITION, then MIC as last resort.
   */
  private synchronized boolean startRecordingWithFallback(String phoneNumber, long creationTime) {
    Log.i(TAG, "==========================================");
    Log.i(TAG, "STARTING FALLBACK RECORDING");
    Log.i(TAG, "==========================================");
    
    // Try VOICE_RECOGNITION first (works on most devices, mic only)
    if (tryRecordingWithSource(MediaRecorder.AudioSource.VOICE_RECOGNITION, phoneNumber, creationTime)) {
      Log.i(TAG, "✓ Fallback successful with VOICE_RECOGNITION");
      Log.w(TAG, "⚠ Note: Recording may only capture microphone (your voice)");
      return true;
    }
    
    Log.w(TAG, "VOICE_RECOGNITION also failed, trying MIC as last resort...");
    
    // Last resort: MIC (should work on all devices)
    if (tryRecordingWithSource(MediaRecorder.AudioSource.MIC, phoneNumber, creationTime)) {
      Log.i(TAG, "✓ Fallback successful with MIC");
      Log.w(TAG, "⚠ Note: Recording may only capture microphone (your voice)");
      return true;
    }
    
    Log.e(TAG, "==========================================");
    Log.e(TAG, "✗✗✗ ALL AUDIO SOURCES FAILED ✗✗✗");
    Log.e(TAG, "Tried: VOICE_CALL, VOICE_RECOGNITION, MIC");
    Log.e(TAG, "Device may not support call recording");
    Log.e(TAG, "==========================================");
    return false;
  }

  /**
   * Try recording with a specific audio source.
   */
  private boolean tryRecordingWithSource(int audioSource, String phoneNumber, long creationTime) {
    Log.i(TAG, "Trying audio source: " + audioSource);
    
    MediaRecorder recorder = new MediaRecorder();
    
    try {
      // Set audio source
      recorder.setAudioSource(audioSource);
      
      // Configure format/encoder
      int formatChoice = getAudioFormatChoice();
      recorder.setOutputFormat(formatChoice == 0
          ? MediaRecorder.OutputFormat.AMR_WB : MediaRecorder.OutputFormat.MPEG_4);
      recorder.setAudioEncoder(formatChoice == 0
          ? MediaRecorder.AudioEncoder.AMR_WB : MediaRecorder.AudioEncoder.AAC);
      
      if (formatChoice != 0) {
        recorder.setAudioEncodingBitRate(128000);
        recorder.setAudioSamplingRate(44100);
      }
      
      // Generate filename and create media store entry
      String fileName = generateFilename(phoneNumber);
      Uri uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
              CallRecording.generateMediaInsertValues(fileName, creationTime));
      
      // Set output file
      ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w");
      if (pfd == null) {
        recorder.release();
        // Clean up the MediaStore entry
        getContentResolver().delete(uri, null, null);
        return false;
      }
      recorder.setOutputFile(pfd.getFileDescriptor());
      
      // Prepare and start
      recorder.prepare();
      recorder.start();
      
      // Success! Save recorder and recording info
      mMediaRecorder = recorder;
      long mediaId = Long.parseLong(uri.getLastPathSegment());
      mCurrentRecording = new CallRecording(phoneNumber, creationTime,
              fileName, System.currentTimeMillis(), mediaId);
      
      Log.i(TAG, "✓ Successfully started with audio source: " + audioSource);
      return true;
      
    } catch (IOException e) {
      Log.w(TAG, "Audio source " + audioSource + " failed with IOException: " + e.getMessage());
      try {
        recorder.release();
      } catch (Exception ex) {
        // Ignore cleanup errors
      }
      return false;
    } catch (IllegalStateException e) {
      Log.w(TAG, "Audio source " + audioSource + " failed with IllegalStateException: " + e.getMessage());
      try {
        recorder.release();
      } catch (Exception ex) {
        // Ignore cleanup errors
      }
      return false;
    } catch (RuntimeException e) {
      Log.w(TAG, "Audio source " + audioSource + " failed with RuntimeException: " + e.getMessage());
      try {
        recorder.release();
      } catch (Exception ex) {
        // Ignore cleanup errors
      }
      return false;
    }
  }

  private synchronized CallRecording stopRecordingInternal() {
    CallRecording recording = mCurrentRecording;
    Log.i(TAG, "==========================================");
    Log.i(TAG, "STOP RECORDING INTERNAL CALLED");
    Log.i(TAG, "==========================================");
    if (mMediaRecorder != null) {
      try {
        Log.i(TAG, "Stopping MediaRecorder");
        mMediaRecorder.stop();
        mMediaRecorder.release();
        Log.i(TAG, "✓ MediaRecorder stopped and released");
      } catch (IllegalStateException e) {
        Log.e(TAG, "✗ Exception closing media recorder", e);
      }

      Uri uri = ContentUris.withAppendedId(
          MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mCurrentRecording.mediaId);
      getContentResolver().update(uri, CallRecording.generateCompletedValues(), null, null);
      Log.i(TAG, "✓ Updated media store entry to completed");

      mMediaRecorder = null;
      mCurrentRecording = null;
    } else {
      Log.i(TAG, "⚠ MediaRecorder is null in stopRecordingInternal");
    }
    return recording;
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    Log.i(TAG, "==========================================");
    Log.i(TAG, "SERVICE DESTROYED");
    Log.i(TAG, "==========================================");
  }

  private String generateFilename(String number) {
    String timestamp = DATE_FORMAT.format(new Date());

    if (TextUtils.isEmpty(number)) {
      number = "unknown";
    }

    int formatChoice = getAudioFormatChoice();
    String extension = formatChoice == 0 ? ".amr" : ".m4a";
    return number + "_" + timestamp + extension;
  }

  public static boolean isEnabled(Context context) {
    return context.getResources().getBoolean(R.bool.call_recording_enabled);
  }
}
