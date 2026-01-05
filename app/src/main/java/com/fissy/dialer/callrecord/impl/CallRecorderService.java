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

  private MediaRecorder mMediaRecorder = null;
  private CallRecording mCurrentRecording = null;

  private SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyMMdd_HHmmssSSS");

  private final ICallRecorderService.Stub mBinder = new ICallRecorderService.Stub() {
    @Override
    public CallRecording stopRecording() {
      Log.d(TAG, "AIDL stopRecording called");
      return stopRecordingInternal();
    }

    @Override
    public boolean startRecording(String phoneNumber, long creationTime) throws RemoteException {
      Log.d(TAG, "AIDL startRecording called - phoneNumber: " + phoneNumber + ", time: " + creationTime);
      return startRecordingInternal(phoneNumber, creationTime);
    }

    @Override
    public boolean isRecording() throws RemoteException {
      boolean recording = mMediaRecorder != null;
      Log.d(TAG, "AIDL isRecording called - result: " + recording);
      return recording;
    }

    @Override
    public CallRecording getActiveRecording() throws RemoteException {
      Log.d(TAG, "AIDL getActiveRecording called - result: " + (mCurrentRecording != null ? mCurrentRecording.toString() : "null"));
      return mCurrentRecording;
    }
  };

  @Override
  public void onCreate() {
    super.onCreate();
    Log.d(TAG, "Service created");
  }

  @Override
  public IBinder onBind(Intent intent) {
    Log.d(TAG, "Service bound with intent: " + intent);
    return mBinder;
  }

  private int getAudioSource() {
    // Try different audio sources in order of preference for non-rooted devices
    // Starting with Android 9 (API 28), VOICE_CALL requires system privileges
    
    // For Android 12+ (API 31+), VOICE_RECOGNITION works best
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
      return MediaRecorder.AudioSource.VOICE_RECOGNITION;
    }
    
    // For Android 10-11 (API 29-30), try VOICE_COMMUNICATION
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
      return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
    }
    
    // For Android 9 and below, use MIC or config value
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
    Log.d(TAG, "startRecordingInternal called - phoneNumber: " + phoneNumber + ", creationTime: " + creationTime);
    
    if (mMediaRecorder != null) {
      Log.w(TAG, "Start called with recording in progress, stopping current recording");
      stopRecordingInternal();
    }

    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      Log.e(TAG, "Record audio permission not granted, can't record call");
      return false;
    }

    Log.d(TAG, "Starting recording - initializing MediaRecorder");

    mMediaRecorder = new MediaRecorder();
    
    // Try multiple audio sources with fallback for non-rooted devices
    boolean audioSourceSet = false;
    int audioSource = -1;
    
    // Try the preferred audio source first
    try {
      audioSource = getAudioSource();
      Log.d(TAG, "Trying primary audio source: " + audioSource);
      mMediaRecorder.setAudioSource(audioSource);
      audioSourceSet = true;
      Log.d(TAG, "Successfully set audio source: " + audioSource);
    } catch (IllegalStateException e) {
      Log.w(TAG, "Primary audio source not available, trying fallbacks", e);
      
      // Clean up failed MediaRecorder
      try {
        mMediaRecorder.release();
      } catch (Exception ex) {
        // Ignore cleanup errors
      }
      
      // Fallback 1: Try VOICE_RECOGNITION (most compatible for non-system apps)
      try {
        audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;
        mMediaRecorder = new MediaRecorder();
        mMediaRecorder.setAudioSource(audioSource);
        audioSourceSet = true;
        Log.d(TAG, "Using fallback VOICE_RECOGNITION audio source");
      } catch (IllegalStateException e2) {
        Log.w(TAG, "VOICE_RECOGNITION not available, trying MIC", e2);
        
        // Clean up failed MediaRecorder
        try {
          mMediaRecorder.release();
        } catch (Exception ex) {
          // Ignore cleanup errors
        }
        
        // Fallback 2: Try MIC (works on all devices)
        try {
          audioSource = MediaRecorder.AudioSource.MIC;
          mMediaRecorder = new MediaRecorder();
          mMediaRecorder.setAudioSource(audioSource);
          audioSourceSet = true;
          Log.d(TAG, "Using fallback MIC audio source");
        } catch (IllegalStateException e3) {
          Log.e(TAG, "No audio source available", e3);
        }
      }
    }
    
    if (!audioSourceSet) {
      Log.e(TAG, "Failed to set any audio source");
      mMediaRecorder.release();
      mMediaRecorder = null;
      return false;
    }
    
    try {
      int formatChoice = getAudioFormatChoice();
      Log.d(TAG, "Setting output format - choice: " + formatChoice);
      mMediaRecorder.setOutputFormat(formatChoice == 0
          ? MediaRecorder.OutputFormat.AMR_WB : MediaRecorder.OutputFormat.MPEG_4);
      mMediaRecorder.setAudioEncoder(formatChoice == 0
          ? MediaRecorder.AudioEncoder.AMR_WB : MediaRecorder.AudioEncoder.AAC);
      
      // For high-quality AAC recording, set bitrate and sample rate
      if (formatChoice != 0) {
        mMediaRecorder.setAudioEncodingBitRate(128000);
        mMediaRecorder.setAudioSamplingRate(44100);
      }
    } catch (IllegalStateException e) {
      Log.e(TAG, "Error initializing media recorder", e);
      mMediaRecorder.release();
      mMediaRecorder = null;
      return false;
    }

    String fileName = generateFilename(phoneNumber);
    Log.d(TAG, "Generated filename: " + fileName);
    Uri uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            CallRecording.generateMediaInsertValues(fileName, creationTime));
    Log.d(TAG, "Created media store entry: " + uri);

    try {
      ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w");
      if (pfd == null) {
        throw new IOException("Opening file for URI " + uri + " failed");
      }
      mMediaRecorder.setOutputFile(pfd.getFileDescriptor());
      Log.d(TAG, "MediaRecorder configured, preparing...");
      mMediaRecorder.prepare();
      Log.d(TAG, "MediaRecorder prepared, starting...");
      mMediaRecorder.start();

      long mediaId = Long.parseLong(uri.getLastPathSegment());
      mCurrentRecording = new CallRecording(phoneNumber, creationTime,
              fileName, System.currentTimeMillis(), mediaId);
      Log.d(TAG, "Recording started successfully: " + mCurrentRecording.toString());
      return true;
    } catch (IOException | IllegalStateException e) {
      Log.e(TAG, "Could not start recording", e);
      getContentResolver().delete(uri, null, null);
    } catch (RuntimeException e) {
      getContentResolver().delete(uri, null, null);
      // only catch exceptions thrown by the MediaRecorder JNI code
      String message = e.getMessage();
      if (message != null && message.contains("start failed")) {
        Log.e(TAG, "Could not start recording", e);
      } else {
        throw e;
      }
    }

    mMediaRecorder.release();
    mMediaRecorder = null;

    return false;
  }

  private synchronized CallRecording stopRecordingInternal() {
    CallRecording recording = mCurrentRecording;
    Log.d(TAG, "stopRecordingInternal called");
    if (mMediaRecorder != null) {
      try {
        Log.d(TAG, "Stopping MediaRecorder");
        mMediaRecorder.stop();
        mMediaRecorder.release();
        Log.d(TAG, "MediaRecorder stopped and released");
      } catch (IllegalStateException e) {
        Log.e(TAG, "Exception closing media recorder", e);
      }

      Uri uri = ContentUris.withAppendedId(
          MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mCurrentRecording.mediaId);
      getContentResolver().update(uri, CallRecording.generateCompletedValues(), null, null);
      Log.d(TAG, "Updated media store entry to completed");

      mMediaRecorder = null;
      mCurrentRecording = null;
    } else {
      Log.w(TAG, "MediaRecorder is null in stopRecordingInternal");
    }
    return recording;
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    Log.d(TAG, "Service destroyed");
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
