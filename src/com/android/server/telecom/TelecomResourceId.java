/*
 * Copyright (C) 2026 The Android Open Source Project
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

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.util.Log;

import java.util.List;

/**
 * Helper class to get resource IDs from the Telecom package.
 */
public class TelecomResourceId {
    private static final String TAG = "TelecomResourceId";
    private static final String RESOURCES_APK_INTENT =
            "com.android.server.telecom.intent.action.SERVICE_TELECOM_RESOURCES_APK";
    private static final String TELEPHONY_MODULE_NAME = "com.android.telephonycore";
    private static final String TELECOM_RES_PKG_DIR = "/apex/" + TELEPHONY_MODULE_NAME + "/";

    private static String sTelecomPackageName;
    private static Context sTelecomContext;

    @com.android.internal.annotations.VisibleForTesting
    public static synchronized void setTelecomContext(Context context) {
        sTelecomContext = context;
        if (context != null) {
            sTelecomPackageName = context.getPackageName();
        } else {
            sTelecomPackageName = null;
        }
    }

    public static synchronized Context getTelecomContext(Context context) {
        if (sTelecomContext == null) {
            PackageManager pm = context.getPackageManager();
            if (pm != null) {
                final List<ResolveInfo> pkgs = pm.queryIntentActivities(
                        new Intent(RESOURCES_APK_INTENT), PackageManager.MATCH_SYSTEM_ONLY);

                pkgs.removeIf(pkg -> !pkg.activityInfo.applicationInfo.sourceDir.startsWith(
                        TELECOM_RES_PKG_DIR));

                if (pkgs.size() > 1) {
                    Log.wtf(TAG, "More than one telecom resources package found: " + pkgs);
                }

                if (!pkgs.isEmpty()) {
                    final String resPkg = pkgs.get(0).activityInfo.applicationInfo.packageName;
                    try {
                        sTelecomContext = context.createPackageContext(resPkg, 0);
                        sTelecomPackageName = resPkg;
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.e(TAG, "Resolved resource package not found: " + resPkg, e);
                    }
                }
            }

            // If discovery fails, fallback to the caller's context and identity.
            if (sTelecomContext == null) {
                Log.w(TAG, "No telecom resource package found via intent. Using caller context.");
                sTelecomContext = context;
                sTelecomPackageName = context.getPackageName();
            }
        }
        return sTelecomContext != null ? sTelecomContext : context;
    }

    public static Resources getResources(Context context) {
        return getTelecomContext(context).getResources();
    }

    public static int getIdentifier(Context context, String name, String type) {
        return getResources(context).getIdentifier(name, type, sTelecomPackageName);
    }

    public static String getString(Context context, String name) {
        return getTelecomContext(context).getString(getIdentifier(context, name, "string"));
    }

    public static String getString(Context context, String name, Object... formatArgs) {
        return getTelecomContext(context).getString(getIdentifier(context, name, "string"),
                formatArgs);
    }

    public static CharSequence getText(Context context, String name) {
        return getTelecomContext(context).getText(getIdentifier(context, name, "string"));
    }

    public static int getInteger(Context context, String name) {
        return getResources(context).getInteger(getIdentifier(context, name, "integer"));
    }

    public static int getColor(Context context, String name) {
        return getResources(context).getColor(getIdentifier(context, name, "color"), null);
    }

    public static boolean getBoolean(Context context, String name) {
        return getResources(context).getBoolean(getIdentifier(context, name, "bool"));
    }

    public static String[] getStringArray(Context context, String name) {
        return getResources(context).getStringArray(getIdentifier(context, name, "array"));
    }
}
