/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.telecom;

import static android.telephony.TelephonyManager.UNKNOWN_CARRIER_ID;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.ParcelUuid;
import android.provider.DeviceConfig;
import android.util.Log;
import android.telephony.TelephonyManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AnomalyReporterAdapterImpl implements AnomalyReporterAdapter {
    private static final String TAG = "AnomalyReporter";

    private static final String KEY_IS_TELEPHONY_ANOMALY_REPORT_ENABLED =
            "is_telephony_anomaly_report_enabled";

    private static Context sContext = null;

    private static Map<UUID, Integer> sEvents = new ConcurrentHashMap<>();

    /*
     * Because this is only supporting system packages, once we find a package, it will be the
     * same package until the next system upgrade. Thus, to save time in processing debug events
     * we can cache this info and skip the resolution process after it's done the first time.
     */
    private static String sDebugPackageName = null;

    public AnomalyReporterAdapterImpl() {}

    public AnomalyReporterAdapterImpl(Context context) {
        if (sContext == null) {
            initialize(context);
        }
    }

    /**
     * Initialize the AnomalyReporter with the current context.
     *
     * This method must be invoked before any calls to reportAnomaly() will succeed. This method
     * should only be invoked at most once.
     *
     * @param context a Context object used to initialize this singleton AnomalyReporter in
     *        the current process.
     */
    public static void initialize(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("AnomalyReporter needs a non-null context.");
        }


        sContext = context;

        // Check to see if there is a valid debug package; if there are multiple, that's a config
        // error, so just take the first one.
        PackageManager pm = sContext.getPackageManager();
        if (pm == null) return;
        List<ResolveInfo> packages = pm.queryBroadcastReceivers(
                new Intent(TelephonyManager.ACTION_ANOMALY_REPORTED),
                PackageManager.MATCH_SYSTEM_ONLY
                        | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
        if (packages == null || packages.isEmpty()) return;
        if (packages.size() > 1) {
            Log.w(TAG, "Multiple Anomaly Receivers installed.");
        }

        for (ResolveInfo r : packages) {
            if (r.activityInfo == null) {
                Log.w(TAG, "Found package without activity");
                continue;
            } else if (pm.checkPermission(
                            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
                            r.activityInfo.packageName)
                      != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Found package without proper permissions"
                                    + r.activityInfo.packageName);
                continue;
            }
            Log.d(TAG, "Found a valid package " + r.activityInfo.packageName);
            sDebugPackageName = r.activityInfo.packageName;
            break;
        }
        // Initialization may only be performed once.
    }

    @Override
    public void reportAnomaly(UUID eventId, String description) {
        reportAnomaly(eventId, description, UNKNOWN_CARRIER_ID);
    }

    private void reportAnomaly(UUID eventId, String description, int carrierId) {
        Log.i(TAG, "reportAnomaly: Received anomaly event report with eventId= " + eventId
                + " and description= " + description);
        if (sContext == null) {
            Log.w(TAG, "AnomalyReporter not yet initialized, dropping event=" + eventId);
            return;
        }

        // Don't report via Intent if the server-side flag isn't loaded, as it implies other anomaly
        // report related config hasn't loaded.
        try {
            boolean isAnomalyReportEnabledFromServer = DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_TELEPHONY, KEY_IS_TELEPHONY_ANOMALY_REPORT_ENABLED,
                    false);
            if (!isAnomalyReportEnabledFromServer) return;
        } catch (Exception e) {
            Log.w(TAG, "Unable to read device config, dropping event=" + eventId);
            return;
        }

        // If this event has already occurred, skip sending intents for it; regardless log its
        // invocation here.
        Integer count = sEvents.containsKey(eventId) ? sEvents.get(eventId) + 1 : 1;
        sEvents.put(eventId, count);
        if (count > 1) return;

        // Even if we are initialized, that doesn't mean that a package name has been found.
        // This is normal in many cases, such as when no debug package is installed on the system,
        // so drop these events silently.
        if (sDebugPackageName == null) return;

        Intent dbgIntent = new Intent(TelephonyManager.ACTION_ANOMALY_REPORTED);
        dbgIntent.putExtra(TelephonyManager.EXTRA_ANOMALY_ID, new ParcelUuid(eventId));
        if (description != null) {
            dbgIntent.putExtra(TelephonyManager.EXTRA_ANOMALY_DESCRIPTION, description);
        }
        dbgIntent.setPackage(sDebugPackageName);
        sContext.sendBroadcast(dbgIntent, android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
    }
}
