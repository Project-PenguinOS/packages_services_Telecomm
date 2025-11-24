/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.app.backup.BackupAgentHelper;
import android.app.backup.SharedPreferencesBackupHelper;
import android.os.UserHandle;
import android.telecom.Log;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Provides backup and restore functionality for Telecom services.
 *
 * This agent specifically handles the backup of SharedPreferences for call log integration, which
 * stores user preferences for which VoIP apps are enabled or disabled. This may be extended to
 * other general use cases in the future.
 */
public class TelecomBackupAgent extends BackupAgentHelper {

    // A key to uniquely identify the SharedPreferences data in the backup set.
    public static final String CALL_LOG_INTEGRATION_BACKUP_KEY =
            "call_log_integration_backup_key";
    public final Executor mExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        if (android.telecom.flags.Flags.integratedCallLogsStage2()) {
            Log.i(this, "Handle backup for call log integration");
            // Instantiate SharedPreferencesBackupHelper to manage the backup/restore of the call
            // log integration SharedPreferences file.
            SharedPreferencesBackupHelper callLogIntegrationBackupHelper =
                    new SharedPreferencesBackupHelper(this,
                            CallLogIntegrationAdapterImpl.SHARED_PREFERENCES_NAME);
            // Add the helper to the BackupAgentHelper with the backup key.
            addHelper(CALL_LOG_INTEGRATION_BACKUP_KEY, callLogIntegrationBackupHelper);
        }
    }

    @Override
    public void onRestoreFinished() {
        super.onRestoreFinished();
        if (android.telecom.flags.Flags.integratedCallLogsStage2()) {
            UserHandle user = getUser();
            Log.i(this, "onRestoreFinished for user %s", user);
            // Get the shared preference for the user and notify all apps in that list.
            CallLogIntegrationAdapterImpl.handleNotifyAppsOfPreferenceOnRestore(this, user,
                    mExecutor);
        }
    }
}

