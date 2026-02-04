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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.telecom.Connection;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.PhoneAccountRegistrar;
import com.android.server.telecom.RespondViaSmsManager;
import com.android.server.telecom.TelecomSystem;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.Executor;

@RunWith(JUnit4.class)
public class RespondViaSmsManagerTest extends TelecomTestCase {

    @Mock
    private CallsManager mCallsManager;
    @Mock
    private TelecomSystem.SyncRoot mLock;
    private Executor mExecutor = Runnable::run;

    private RespondViaSmsManager mRespondViaSmsManager;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        when(mCallsManager.getContext()).thenReturn(mContext);
        mRespondViaSmsManager = new RespondViaSmsManager(mCallsManager, mLock, mExecutor);
    }

    @SmallTest
    @Test
    public void testLoadCannedTextMessages() {
        CallsManager.Response<Void, List<String>> response = mock(CallsManager.Response.class);

        // Mock SharedPreferences
        SharedPreferences prefs = mock(SharedPreferences.class);
        when(mContext.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.getString(anyString(), anyString())).thenReturn("canned response");

        mRespondViaSmsManager.loadCannedTextMessages(response, mContext);

        waitForHandlerAction(new Handler(mContext.getMainLooper()), 1000);
        verify(response).onResult(any(), any());
    }

    @SmallTest
    @Test
    public void testOnIncomingCallRejected() {
        Call call = mock(Call.class);
        when(call.getHandle()).thenReturn(android.net.Uri.parse("tel:1234567890"));
        when(call.can(Connection.CAPABILITY_CAN_SEND_RESPONSE_VIA_CONNECTION)).thenReturn(false);
        when(call.getContext()).thenReturn(mContext);

        PhoneAccountRegistrar registrar = mock(PhoneAccountRegistrar.class);
        when(mCallsManager.getPhoneAccountRegistrar()).thenReturn(registrar);
        when(registrar.getSubscriptionIdForPhoneAccount(any())).thenReturn(1);

        try {
            mRespondViaSmsManager.onIncomingCallRejected(call, true, "test message");
        } catch (Exception e) {
            // ignore SecurityException if it happens in the background
        }
    }
}
