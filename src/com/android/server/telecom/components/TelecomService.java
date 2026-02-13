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

package com.android.server.telecom.components;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.telecom.TelecomServiceInitializerRepository;
import android.util.Log;

public class TelecomService extends Service {

    private static final String TELECOM_UI_PACKAGE = "com.android.server.telecomui";

    @Override
    public IBinder onBind(Intent intent) {
        Log.i("TelecomService", "onBind");

        // Enable TelecomUi since the mainline module is active
        try {
            getPackageManager().setApplicationEnabledSetting(
                    TELECOM_UI_PACKAGE,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            Log.i("TelecomService", "Successfully enabled TelecomUi");
        } catch (IllegalArgumentException e) {
            Log.e("TelecomService", "Failed to enable TelecomUi", e);
        }

        if (TelecomServiceInitializerRepository.getInitializer() != null) {
            return TelecomServiceInitializerRepository.getInitializer().initialize(this);
        } else {
            Log.wtf("TelecomService", "no telecom library loaded!");
        }
        return null;
    }
}
