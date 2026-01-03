/* ... license header unchanged ... */

package com.fissy.dialer.contactsfragment;

import static android.Manifest.permission.READ_CONTACTS;

import android.app.Fragment;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.legacy.app.FragmentCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.Recycler;
import androidx.recyclerview.widget.RecyclerView.State;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnScrollChangeListener;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.fissy.dialer.R;
import com.fissy.dialer.common.Assert;
import com.fissy.dialer.common.FragmentUtils;
import com.fissy.dialer.common.LogUtil;
import com.fissy.dialer.performancereport.PerformanceReport;
import com.fissy.dialer.util.DialerUtils;
import com.fissy.dialer.util.IntentUtil;
import com.fissy.dialer.util.PermissionsUtil;
import com.fissy.dialer.widget.EmptyContentView;
import com.fissy.dialer.widget.EmptyContentView.OnEmptyViewActionButtonClickedListener;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

// indicatorfastscroll imports
import com.reddit.indicatorfastscroll.FastScrollerView;
import com.reddit.indicatorfastscroll.FastScrollerThumbView;
import com.reddit.indicatorfastscroll.FastScrollItemIndicator;
import kotlin.jvm.functions.Function1;

/**
 * Fragment containing a list of all contacts.
 */
public class ContactsFragment extends Fragment
        implements LoaderCallbacks<Cursor>,
        OnScrollChangeListener,
        OnEmptyViewActionButtonClickedListener {

    public static final int READ_CONTACTS_PERMISSION_REQUEST_CODE = 1;
    private static final String EXTRA_HEADER = "extra_header";
    private static final String EXTRA_HAS_PHONE_NUMBERS = "extra_has_phone_numbers";

    private FastScrollerView fastScroller;
    private FastScrollerThumbView fastScrollerThumb;
    private TextView anchoredHeader;
    private RecyclerView recyclerView;
    private LinearLayoutManager manager;
    private ContactsAdapter adapter;
    private EmptyContentView emptyContentView;

    // Map from indicator text (letter) to first adapter position for that letter
    private final Map<String, Integer> indicatorIndexMap = new HashMap<>();

    // Flag to avoid calling setupWithRecyclerView more than once (library throws otherwise)
    private boolean fastScrollerSetupDone = false;

    /**
     * Listen to broadcast events about permissions in order to be notified if the READ_CONTACTS
     * permission is granted via the UI in another fragment.
     */
    private final BroadcastReceiver readContactsPermissionGrantedReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    loadContacts();
                }
            };
    private @Header
    int header;
    private boolean hasPhoneNumbers;
    private String query;

    // ... remainder of class unchanged until onLoadFinished ...

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
        LogUtil.enterBlock("ContactsFragment.onLoadFinished");
        if (cursor == null || cursor.getCount() == 0) {
            emptyContentView.setDescription(R.string.all_contacts_empty);
            emptyContentView.setActionLabel(R.string.all_contacts_empty_add_contact_action);
            emptyContentView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyContentView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            adapter.updateCursor(cursor);

            PerformanceReport.logOnScrollStateChange(recyclerView);

            // Build fast-scroller index map and attach scroller (guarded)
            recyclerView.post(new Runnable() {
                @Override
                public void run() {
                    final int itemCount = adapter.getItemCount();

                    // Recompute index map on every load so it stays accurate after rotation/changes
                    indicatorIndexMap.clear();
                    for (int i = 0; i < itemCount; i++) {
                        String header = adapter.getHeaderString(i);
                        if (header == null) continue;
                        String key = header.trim().isEmpty() ? "#" : header.substring(0, 1).toUpperCase();
                        if (!indicatorIndexMap.containsKey(key)) {
                            indicatorIndexMap.put(key, i);
                        }
                    }

                    boolean needFast = recyclerView.computeVerticalScrollRange() > recyclerView.getHeight();
                    if (needFast && fastScroller != null && fastScrollerThumb != null) {

                        // Setup provider (relying on adapter at runtime). We only call setupWithRecyclerView once.
                        final Function1<Integer, FastScrollItemIndicator> provider = new Function1<Integer, FastScrollItemIndicator>() {
                            @Override
                            public FastScrollItemIndicator invoke(Integer position) {
                                String headerString = adapter.getHeaderString(position);
                                String text = "#";
                                if (headerString != null && !headerString.isEmpty()) {
                                    text = headerString.substring(0, 1).toUpperCase();
                                }
                                return new FastScrollItemIndicator.Text(text);
                            }
                        };

                        if (!fastScrollerSetupDone) {
                            try {
                                fastScroller.setupWithRecyclerView(recyclerView, provider);
                                fastScrollerSetupDone = true;
                            } catch (IllegalStateException ise) {
                                // Library can throw "Only set this view's RecyclerView once!" — treat as already set
                                fastScrollerSetupDone = true;
                            } catch (Throwable t) {
                                // Any other failure, don't mark setupDone so future attempts can retry
                                t.printStackTrace();
                            }
                        } // else: already set, skip calling setupWithRecyclerView again

                        // Thumb can be bound repeatedly; safe to (re)bind so thumb and scroller remain synced
                        try {
                            fastScrollerThumb.setupWithFastScroller(fastScroller);
                        } catch (Throwable ignored) {
                        }

                        // Disable default scroller behavior if the library exposes the setter
                        try {
                            fastScroller.setUseDefaultScroller(false);
                        } catch (Throwable ignored) {
                        }

                        // Register selection callback through public getter and add
                        try {
                            fastScroller.getItemIndicatorSelectedCallbacks().add(new FastScrollerView.ItemIndicatorSelectedCallback() {
                                @Override
                                public void onItemIndicatorSelected(FastScrollItemIndicator indicator, int indicatorCenterY, int itemPosition) {

                                    // Try to extract the indicator text via public getter
                                    String indicatorText = null;
                                    if (indicator instanceof FastScrollItemIndicator.Text) {
                                        try {
                                            indicatorText = ((FastScrollItemIndicator.Text) indicator).getText();
                                        } catch (Throwable t) {
                                            indicatorText = null;
                                        }
                                    }

                                    int targetPos = RecyclerView.NO_POSITION;

                                    if (indicatorText != null) {
                                        indicatorText = indicatorText.trim().toUpperCase();
                                        Integer mapped = indicatorIndexMap.get(indicatorText);
                                        if (mapped != null) {
                                            targetPos = mapped;
                                        }
                                    }

                                    // Fallback: use adapter-derived header for provided itemPosition to obtain a precise start pos
                                    if (targetPos == RecyclerView.NO_POSITION && itemPosition >= 0 && itemPosition < itemCount) {
                                        String derivedHeader = adapter.getHeaderString(itemPosition);
                                        if (derivedHeader != null && !derivedHeader.isEmpty()) {
                                            Integer mapped = indicatorIndexMap.get(derivedHeader.substring(0, 1).toUpperCase());
                                            if (mapped != null) {
                                                targetPos = mapped;
                                            } else {
                                                targetPos = itemPosition;
                                            }
                                        } else {
                                            targetPos = itemPosition;
                                        }
                                    }

                                    if (targetPos != RecyclerView.NO_POSITION) {
                                        // Immediate jump to make scroller very responsive
                                        manager.scrollToPositionWithOffset(targetPos, 0);
                                    }
                                }
                            });
                        } catch (Throwable ignored) {
                            // If the library API differs, ignore; this is best-effort.
                        }
                    } else if (fastScroller != null) {
                        fastScroller.setVisibility(View.GONE);
                    }
                }
            });
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        recyclerView.setAdapter(null);
        recyclerView.setOnScrollChangeListener(null);
        adapter = null;
        indicatorIndexMap.clear();
        // Reset setup flag when loader is reset so a subsequent valid load can re-setup if needed
        fastScrollerSetupDone = false;
    }

    // ... rest of file unchanged ...
}