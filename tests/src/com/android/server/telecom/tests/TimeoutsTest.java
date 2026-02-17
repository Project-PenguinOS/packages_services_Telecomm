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

package com.android.server.telecom.tests;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.Timeouts;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

@RunWith(JUnit4.class)
public class TimeoutsTest extends TelecomTestCase {

    @Mock
    private ContentResolver mContentResolver;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
    }

    @SmallTest
    @Test
    public void testAdapterMethods() {
        Timeouts.Adapter adapter = new Timeouts.Adapter();

        assertEquals(5000L, adapter.getCallScreeningTimeoutMillis(mContext, mFeatureFlags));
        assertEquals(25000L, adapter.getEmergencyCallTimeoutMillis(mContext, mFeatureFlags));
        assertEquals(250L, adapter.getCallStartAppOpDebounceIntervalMillis());
        assertEquals(5000L, adapter.getVoipCallTransitoryStateTimeoutMillis());
    }

    @SmallTest
    @Test
    public void testStaticMethods() {
        assertEquals(5000L, Timeouts.getCallScreeningTimeoutMillis(mContext, mFeatureFlags));
        assertEquals(300000L, Timeouts.getEmergencyCallbackWindowMillis(mContext, mFeatureFlags));
        assertEquals(25000L, Timeouts.getEmergencyCallTimeoutMillis(mContext, mFeatureFlags));
        assertEquals(500L, Timeouts.getNewOutgoingCallCancelMillis(mContext, mFeatureFlags));
        assertEquals(300L, Timeouts.getDelayBetweenDtmfTonesMillis(mContext, mFeatureFlags));
        assertEquals(10000L, Timeouts.getMaxNewOutgoingCallCancelMillis(mContext, mFeatureFlags));
        assertEquals(60000L,
                Timeouts.getEmergencyCallTimeoutRadioOffMillis(mContext, mFeatureFlags));
        assertEquals(2000L,
                Timeouts.getCallBindBluetoothInCallServicesDelay(mContext, mFeatureFlags));
        assertEquals(2000L,
                Timeouts.getCallRemoveUnbindInCallServicesDelay(mContext, mFeatureFlags));
        assertEquals(5000L,
                Timeouts.getBluetoothPendingTimeoutMillis(mContext, mFeatureFlags));
        assertEquals(500L,
                Timeouts.getRetryBluetoothConnectAudioBackoffMillis(mContext, mFeatureFlags));
        assertEquals(5000L,
                Timeouts.getPhoneAccountSuggestionServiceTimeout(mContext, mFeatureFlags));
        assertEquals(5000L,
                Timeouts.getUserDefinedCallRedirectionTimeoutMillis(mContext, mFeatureFlags));
        assertEquals(5000L,
                Timeouts.getCarrierCallRedirectionTimeoutMillis(mContext, mFeatureFlags));
        assertEquals(15000L,
                Timeouts.getCallRecordingToneRepeatIntervalMillis(mContext, mFeatureFlags));
        assertEquals(2000L,
                Timeouts.getCallDiagnosticServiceTimeoutMillis(mContext, mFeatureFlags));
        assertEquals(20000L,
                Timeouts.getEmergencyCallTimeBeforeUserDisconnectThresholdMillis());
        assertEquals(15000L, Timeouts.getEmergencyCallActiveTimeThresholdMillis());
        assertEquals(30, Timeouts.getDaysBackToSearchEmergencyDiagnosticEntries());
        assertEquals(5000L, Timeouts.getVoipEmergencyCallTransitoryStateTimeoutMillis());
        assertEquals(10000L, Timeouts.getNonVoipCallTransitoryStateTimeoutMillis());
        assertEquals(10000L, Timeouts.getNonVoipEmergencyCallTransitoryStateTimeoutMillis());
        assertEquals(120000L, Timeouts.getVoipCallIntermediateStateTimeoutMillis());
        assertEquals(60000L, Timeouts.getVoipEmergencyCallIntermediateStateTimeoutMillis());
        assertEquals(120000L, Timeouts.getNonVoipCallIntermediateStateTimeoutMillis());
        assertEquals(60000L, Timeouts.getNonVoipEmergencyCallIntermediateStateTimeoutMillis());
        assertEquals(30000L,
                Timeouts.getDialerMissedCallPowerSaveExemptionTimeMillis(mContext, mFeatureFlags));
    }
}
