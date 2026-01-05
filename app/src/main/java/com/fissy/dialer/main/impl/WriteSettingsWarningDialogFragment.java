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

package com.fissy.dialer.main.impl;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.fissy.dialer.R;

/**
 * Dialog fragment to warn about WRITE_SETTINGS permission before requesting it
 */
public class WriteSettingsWarningDialogFragment extends DialogFragment {

    private WriteSettingsWarningListener listener;

    public interface WriteSettingsWarningListener {
        void onWriteSettingsAllowed();
        void onWriteSettingsCancelled();
    }

    public static WriteSettingsWarningDialogFragment newInstance() {
        return new WriteSettingsWarningDialogFragment();
    }

    public void setListener(WriteSettingsWarningListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_write_settings_warning, null);

        // Create dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity())
                .setView(view);

        AlertDialog dialog = builder.create();

        // Setup buttons
        Button allowButton = view.findViewById(R.id.write_settings_allow_button);
        Button cancelButton = view.findViewById(R.id.write_settings_cancel_button);

        allowButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onWriteSettingsAllowed();
            }
            dismiss();
        });

        cancelButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onWriteSettingsCancelled();
            }
            dismiss();
        });

        return dialog;
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        if (listener != null) {
            listener.onWriteSettingsCancelled();
        }
    }
}
