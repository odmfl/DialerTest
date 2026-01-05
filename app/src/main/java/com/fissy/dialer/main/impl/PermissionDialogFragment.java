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
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.fissy.dialer.R;
import com.fissy.dialer.util.PermissionManager;

import java.util.List;

/**
 * Dialog fragment to explain permissions and request them
 */
public class PermissionDialogFragment extends DialogFragment {

    private static final String ARG_PERMISSIONS = "permissions";
    private PermissionDialogListener listener;

    public interface PermissionDialogListener {
        void onPermissionsRequested();
        void onPermissionsDenied();
    }

    public static PermissionDialogFragment newInstance(List<String> permissions) {
        PermissionDialogFragment fragment = new PermissionDialogFragment();
        Bundle args = new Bundle();
        args.putStringArray(ARG_PERMISSIONS, permissions.toArray(new String[0]));
        fragment.setArguments(args);
        return fragment;
    }

    public void setListener(PermissionDialogListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_permission_request, null);

        // Get permissions from arguments
        String[] permissions = getArguments() != null ? 
                getArguments().getStringArray(ARG_PERMISSIONS) : new String[0];

        // Setup permission list
        LinearLayout permissionListContainer = view.findViewById(R.id.permission_list_container);
        for (String permission : permissions) {
            View permissionItem = LayoutInflater.from(getContext())
                    .inflate(R.layout.item_permission, permissionListContainer, false);
            
            TextView nameView = permissionItem.findViewById(R.id.permission_item_name);
            TextView descView = permissionItem.findViewById(R.id.permission_item_description);
            
            nameView.setText(PermissionManager.getPermissionDisplayName(permission));
            descView.setText(PermissionManager.getPermissionExplanation(permission));
            
            permissionListContainer.addView(permissionItem);
        }

        // Create dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity())
                .setView(view);

        AlertDialog dialog = builder.create();

        // Setup buttons
        Button grantButton = view.findViewById(R.id.permission_grant_button);
        Button denyButton = view.findViewById(R.id.permission_deny_button);

        grantButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPermissionsRequested();
            }
            dismiss();
        });

        denyButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPermissionsDenied();
            }
            dismiss();
        });

        return dialog;
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        if (listener != null) {
            listener.onPermissionsDenied();
        }
    }
}
