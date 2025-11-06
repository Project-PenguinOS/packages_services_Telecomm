/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.telecom;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telecom.CallScreeningService;
import android.telecom.Log;
import android.telecom.Logging.Session;
import android.text.TextUtils;

import com.android.internal.telecom.ICallScreeningAdapter;
import com.android.internal.telecom.ICallScreeningService;
import com.android.server.telecom.flags.FeatureFlags;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Helper class for performing operations with {@link CallScreeningService}s.
 */
public class CallScreeningServiceHelper {
    private static final String TAG = CallScreeningServiceHelper.class.getSimpleName();

    /**
     * Binds to a {@link CallScreeningService}.
     * @param context The current context.
     * @param userHandle User to bind as.
     * @param packageName Package name of the {@link CallScreeningService}.
     * @param serviceConnection The {@link ServiceConnection} to be notified of binding.
     * @return {@code true} if binding succeeds, {@code false} otherwise.
     */
    public static boolean bindCallScreeningService(Context context, UserHandle userHandle,
            String packageName, ServiceConnection serviceConnection, FeatureFlags flags) {
        if (TextUtils.isEmpty(packageName)) {
            Log.i(TAG, "PackageName is empty. Not performing call screening.");
            return false;
        }

        Intent intent = new Intent(CallScreeningService.SERVICE_INTERFACE)
                .setPackage(packageName);

        List<ResolveInfo> entries;
        entries = UserUtil.getPackageManagerFromUserHandler(context,
                userHandle).queryIntentServicesAsUser(intent, 0, userHandle.getIdentifier());

        if (entries.isEmpty()) {
            Log.i(TAG, packageName + " has no call screening service defined.");
            return false;
        }

        ResolveInfo entry = entries.get(0);
        if (entry.serviceInfo == null) {
            Log.w(TAG, packageName + " call screening service has invalid service info");
            return false;
        }

        if (entry.serviceInfo.permission == null || !entry.serviceInfo.permission.equals(
                Manifest.permission.BIND_SCREENING_SERVICE)) {
            Log.w(TAG, "CallScreeningService must require BIND_SCREENING_SERVICE permission: " +
                    entry.serviceInfo.packageName);
            return false;
        }

        ComponentName componentName =
                new ComponentName(entry.serviceInfo.packageName, entry.serviceInfo.name);
        intent.setComponent(componentName);
        if (context.bindServiceAsUser(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE
                | Context.BIND_SCHEDULE_LIKE_TOP_APP,
                userHandle)) {
            Log.d(TAG,"bindServiceAsUser, found service,"
                    + "waiting for it to connect to user: %s", userHandle);
            return true;
        }

        return false;
    }
}
