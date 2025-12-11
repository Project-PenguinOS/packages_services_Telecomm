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
 * limitations under the License
 */

package com.android.server.telecom.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.backup.BackupHelper;
import android.app.backup.SharedPreferencesBackupHelper;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.TelecomBackupAgent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(JUnit4.class)
public class TelecomBackupAgentTest extends TelecomTestCase {

    /**
     * A testable version of TelecomBackupAgent that allows us to verify the arguments sent via
     * addHelper().
     */
    private static class TestableTelecomBackupAgent extends TelecomBackupAgent {
        private Map<String, BackupHelper> mBackupHelpersMap = new HashMap<>();

        @Override
        public void addHelper(String key, BackupHelper helper) {
            mBackupHelpersMap.put(key, helper);
        }

        boolean containsBackupKey(String key) {
            return mBackupHelpersMap.containsKey(key);
        }

        BackupHelper getBackupHelper(String key) {
            return mBackupHelpersMap.get(key);
        }
    }

    private TestableTelecomBackupAgent mTestBackupAgent;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mTestBackupAgent = new TestableTelecomBackupAgent();
        // Need to attach a context to the agent before onCreate() is called.
        mTestBackupAgent.attach(mContext);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    @SmallTest
    public void testAddCallIntegrationSharedPrefBackup() {
        if (!android.telecom.flags.Flags.integratedCallLogsStage2()) {
            return;
        }
        mTestBackupAgent.onCreate();
        // Verify that addHelper was called for call log integration backup key
        assertTrue(mTestBackupAgent.containsBackupKey(
                TelecomBackupAgent.CALL_LOG_INTEGRATION_BACKUP_KEY));
        // Verify the contents of the backup helper
        BackupHelper addedHelper = mTestBackupAgent
                .getBackupHelper(TelecomBackupAgent.CALL_LOG_INTEGRATION_BACKUP_KEY);
        assertNotNull(addedHelper);
        assertTrue(addedHelper instanceof SharedPreferencesBackupHelper);
    }

    @Test
    @SmallTest
    public void testAddQuickResponsesSharedPrefBackup() {
        if (!com.android.internal.telecom.flags.Flags.quickResponsesBackup()) {
            return;
        }
        mTestBackupAgent.onCreate();
        // Verify that addHelper was called for quick responses backup key
        assertTrue(mTestBackupAgent.containsBackupKey(
                TelecomBackupAgent.QUICK_RESPONSES_BACKUP_KEY));
        // Verify the contents of the backup helper
        BackupHelper addedHelper = mTestBackupAgent
                .getBackupHelper(TelecomBackupAgent.QUICK_RESPONSES_BACKUP_KEY);
        assertNotNull(addedHelper);
        assertTrue(addedHelper instanceof SharedPreferencesBackupHelper);
    }
}

