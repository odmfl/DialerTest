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
 * Dialog fragment to show retry dialog when WRITE_SETTINGS permission is not granted
 */
public class WriteSettingsRetryDialogFragment extends DialogFragment {

    private WriteSettingsRetryListener listener;

    public interface WriteSettingsRetryListener {
        void onWriteSettingsRetry();
        void onWriteSettingsSkip();
    }

    public static WriteSettingsRetryDialogFragment newInstance() {
        return new WriteSettingsRetryDialogFragment();
    }

    public void setListener(WriteSettingsRetryListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_write_settings_retry, null);

        // Create dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity())
                .setView(view);

        AlertDialog dialog = builder.create();

        // Setup buttons
        Button retryButton = view.findViewById(R.id.write_settings_retry_button);
        Button skipButton = view.findViewById(R.id.write_settings_skip_button);

        retryButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onWriteSettingsRetry();
            }
            dismiss();
        });

        skipButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onWriteSettingsSkip();
            }
            dismiss();
        });

        return dialog;
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        if (listener != null) {
            listener.onWriteSettingsSkip();
        }
    }
}
