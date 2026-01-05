package com.fissy.dialer.diagnostics;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.fissy.dialer.R;
import com.fissy.dialer.logging.LogManager;
import com.google.android.material.button.MaterialButton;

import java.io.File;

public class RecordingDiagnosticsActivity extends AppCompatActivity {
    
    private TextView diagnosticsText;
    private MaterialButton exportButton;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recording_diagnostics);
        
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Recording Diagnostics");
        }
        
        diagnosticsText = findViewById(R.id.diagnostics_text);
        exportButton = findViewById(R.id.export_logs_button);
        
        exportButton.setOnClickListener(v -> exportLogs());
        
        runDiagnostics();
    }
    
    private void runDiagnostics() {
        StringBuilder report = new StringBuilder();
        
        report.append("CALL RECORDING DIAGNOSTICS\n");
        report.append("==================================================\n\n");
        
        // Check permissions
        report.append("PERMISSIONS:\n");
        report.append("--------------------------------------------------\n");
        checkPermission(report, "RECORD_AUDIO", Manifest.permission.RECORD_AUDIO);
        checkPermission(report, "READ_PHONE_STATE", Manifest.permission.READ_PHONE_STATE);
        checkPermission(report, "WRITE_EXTERNAL_STORAGE", Manifest.permission.WRITE_EXTERNAL_STORAGE);
        checkPermission(report, "FOREGROUND_SERVICE", Manifest.permission.FOREGROUND_SERVICE);
        report.append("\n");
        
        // Check audio sources
        report.append("AUDIO SOURCES:\n");
        report.append("--------------------------------------------------\n");
        checkAudioSource(report, "MIC", MediaRecorder.AudioSource.MIC);
        checkAudioSource(report, "VOICE_RECOGNITION", MediaRecorder.AudioSource.VOICE_RECOGNITION);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            checkAudioSource(report, "VOICE_COMMUNICATION", MediaRecorder.AudioSource.VOICE_COMMUNICATION);
        }
        report.append("\n");
        
        // Check storage
        report.append("STORAGE:\n");
        report.append("--------------------------------------------------\n");
        File recordingsDir = new File(getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "CallRecordings");
        report.append("Recordings Directory: ").append(recordingsDir.getAbsolutePath()).append("\n");
        report.append("Directory Exists: ").append(recordingsDir.exists() ? "✓ YES" : "✗ NO").append("\n");
        report.append("Directory Writable: ").append(recordingsDir.canWrite() ? "✓ YES" : "✗ NO").append("\n");
        report.append("\n");
        
        // Check service
        report.append("SERVICE:\n");
        report.append("--------------------------------------------------\n");
        report.append("CallRecorderService: Checking...\n");
        // Add service binding check here
        report.append("\n");
        
        // System info
        report.append("SYSTEM:\n");
        report.append("--------------------------------------------------\n");
        report.append("Android Version: ").append(android.os.Build.VERSION.RELEASE)
            .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        report.append("Manufacturer: ").append(android.os.Build.MANUFACTURER).append("\n");
        report.append("Model: ").append(android.os.Build.MODEL).append("\n");
        
        diagnosticsText.setText(report.toString());
    }
    
    private void checkPermission(StringBuilder report, String name, String permission) {
        boolean granted = ContextCompat.checkSelfPermission(this, permission) 
            == PackageManager.PERMISSION_GRANTED;
        report.append(String.format("%-30s %s\n", name + ":", granted ? "✓ GRANTED" : "✗ DENIED"));
    }
    
    private void checkAudioSource(StringBuilder report, String name, int audioSource) {
        try {
            MediaRecorder recorder = new MediaRecorder();
            recorder.setAudioSource(audioSource);
            recorder.release();
            report.append(String.format("%-30s %s\n", name + ":", "✓ AVAILABLE"));
        } catch (Exception e) {
            report.append(String.format("%-30s %s\n", name + ":", "✗ NOT AVAILABLE"));
        }
    }
    
    private void exportLogs() {
        try {
            File logFile = LogManager.getInstance().exportLogs();
            LogManager.getInstance().shareLogs(this, logFile);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Error exporting logs: " + e.getMessage(), 
                android.widget.Toast.LENGTH_LONG).show();
        }
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
