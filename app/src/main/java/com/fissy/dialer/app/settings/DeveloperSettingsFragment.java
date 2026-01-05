package com.fissy.dialer.app.settings;

import android.content.Intent;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.util.Log;
import android.widget.Toast;

import com.fissy.dialer.R;
import com.fissy.dialer.diagnostics.RecordingDiagnosticsActivity;
import com.fissy.dialer.logging.LogManager;

import java.io.File;

public class DeveloperSettingsFragment extends PreferenceFragment
        implements Preference.OnPreferenceClickListener {

    private static final String TAG = "DeveloperSettings";
    
    private Preference exportLogsPreference;
    private Preference recordingDiagnosticsPreference;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.developer_settings);

        exportLogsPreference = findPreference("export_logs");
        recordingDiagnosticsPreference = findPreference("recording_diagnostics");

        if (exportLogsPreference != null) {
            exportLogsPreference.setOnPreferenceClickListener(this);
        }

        if (recordingDiagnosticsPreference != null) {
            recordingDiagnosticsPreference.setOnPreferenceClickListener(this);
        }
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        
        if ("export_logs".equals(key)) {
            exportLogs();
            return true;
        } else if ("recording_diagnostics".equals(key)) {
            Intent intent = new Intent(getActivity(), RecordingDiagnosticsActivity.class);
            startActivity(intent);
            return true;
        }
        
        return false;
    }

    private void exportLogs() {
        try {
            File logFile = LogManager.getInstance().exportLogs();
            LogManager.getInstance().shareLogs(getActivity(), logFile);
            Toast.makeText(getActivity(), "Logs exported successfully", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(getActivity(), "Error exporting logs: " + e.getMessage(), 
                Toast.LENGTH_LONG).show();
            Log.e(TAG, "Error exporting logs", e);
        }
    }
}
