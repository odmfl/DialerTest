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
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.fissy.dialer.R;
import com.fissy.dialer.util.PermissionManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Dialog fragment that displays all required permissions with explanations on first app launch.
 */
public class AllPermissionsDialogFragment extends DialogFragment {

    public static final String TAG = "AllPermissionsDialogFragment";

    private static final String ARG_PERMISSIONS = "permissions";
    
    private AllPermissionsDialogListener listener;

    /**
     * Interface for handling dialog events
     */
    public interface AllPermissionsDialogListener {
        /**
         * Called when user taps "Grant Permissions" button
         */
        void onGrantPermissionsClicked();

        /**
         * Called when the dialog is dismissed without granting permissions
         */
        void onPermissionsDialogDismissed();
    }

    public static AllPermissionsDialogFragment newInstance(List<String> permissions) {
        AllPermissionsDialogFragment fragment = new AllPermissionsDialogFragment();
        Bundle args = new Bundle();
        args.putStringArrayList(ARG_PERMISSIONS, new ArrayList<>(permissions));
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof AllPermissionsDialogListener) {
            listener = (AllPermissionsDialogListener) context;
        } else {
            throw new RuntimeException(context.toString()
                    + " must implement AllPermissionsDialogListener");
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
        
        // Inflate the custom layout
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_all_permissions, null);
        
        // Get permissions from arguments
        List<String> requiredPermissions = getArguments() != null 
                ? getArguments().getStringArrayList(ARG_PERMISSIONS) 
                : new ArrayList<>();
        
        // Populate the permissions container
        LinearLayout permissionsContainer = dialogView.findViewById(R.id.permissions_container);
        for (String permission : requiredPermissions) {
            View permissionView = inflater.inflate(R.layout.item_permission, permissionsContainer, false);
            
            TextView nameTextView = permissionView.findViewById(R.id.permission_name);
            TextView explanationTextView = permissionView.findViewById(R.id.permission_explanation);
            
            nameTextView.setText(PermissionManager.getPermissionDisplayName(permission));
            explanationTextView.setText(PermissionManager.getPermissionExplanation(permission));
            
            permissionsContainer.addView(permissionView);
        }
        
        builder.setView(dialogView)
                .setPositiveButton(R.string.permission_grant_button, 
                        (dialog, which) -> {
                            if (listener != null) {
                                listener.onGrantPermissionsClicked();
                            }
                        })
                .setNegativeButton(R.string.permission_deny_button, 
                        (dialog, which) -> {
                            if (listener != null) {
                                listener.onPermissionsDialogDismissed();
                            }
                            dismiss();
                        })
                .setCancelable(false); // Prevent dismissing by tapping outside
        
        return builder.create();
    }

    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }
}
