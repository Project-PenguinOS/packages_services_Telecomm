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
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.util.Log;

/**
 * Helper class to get resource IDs from the Telecom package.
 */
public class TelecomResourceId {
    private static final String TELECOM_PACKAGE = "com.android.server.telecom";
    private static String sTelecomPackageName = TELECOM_PACKAGE;
    private static Context sTelecomContext;

    @com.android.internal.annotations.VisibleForTesting
    public static void setTelecomContext(Context context) {
        sTelecomContext = context;
        if (context != null) {
            sTelecomPackageName = context.getPackageName();
        } else {
            sTelecomPackageName = TELECOM_PACKAGE;
        }
    }

    public static Context getTelecomContext(Context context) {
        if (sTelecomContext == null) {
            try {
                sTelecomContext = context.createPackageContext(TELECOM_PACKAGE, 0);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e("TelecomResourceId", "Could not create Telecom context", e);
                return context;
            }
        }
        return sTelecomContext;
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

    public static boolean getBoolean(Context context, String name) {
        return getResources(context).getBoolean(getIdentifier(context, name, "bool"));
    }

    public static String[] getStringArray(Context context, String name) {
        return getResources(context).getStringArray(getIdentifier(context, name, "array"));
    }
}
