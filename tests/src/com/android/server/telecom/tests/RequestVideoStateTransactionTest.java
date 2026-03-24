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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.telecom.CallException;
import android.telecom.VideoProfile;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.callsequencing.CallTransactionResult;
import com.android.server.telecom.callsequencing.voip.RequestVideoStateTransaction;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.concurrent.ExecutionException;

@RunWith(JUnit4.class)
public class RequestVideoStateTransactionTest extends TelecomTestCase {

    private CallsManager mCallsManager;
    private Call mCall;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        mCallsManager = mock(CallsManager.class);
        mCall = mock(Call.class);
        when(mCallsManager.getLock()).thenReturn(new TelecomSystem.SyncRoot() {
        });
    }

    @Test
    public void testProcessTransaction_VideoNotSupported()
                                throws ExecutionException, InterruptedException {
        when(mCall.isVideoCallingSupportedByPhoneAccount()).thenReturn(false);

        RequestVideoStateTransaction transaction = new RequestVideoStateTransaction(
                mCallsManager, mCall, android.telecom.CallAttributes.VIDEO_CALL);

        CallTransactionResult result =
                          transaction.processTransaction(null).toCompletableFuture().get();

        assertEquals(CallException.CODE_ERROR_UNKNOWN, result.getResult());
        assertEquals("Video calling is not supported by the target account",
                                                result.getMessage());
    }

    @Test
    public void testProcessTransaction_VideoSupported()
                                throws ExecutionException, InterruptedException {
        when(mCall.isVideoCallingSupportedByPhoneAccount()).thenReturn(true);

        RequestVideoStateTransaction transaction = new RequestVideoStateTransaction(
                mCallsManager, mCall, android.telecom.CallAttributes.VIDEO_CALL);

        CallTransactionResult result =
                          transaction.processTransaction(null).toCompletableFuture().get();

        assertEquals(CallTransactionResult.RESULT_SUCCEED, result.getResult());
        assertEquals("The Video State was changed successfully", result.getMessage());
        verify(mCall).setVideoState(VideoProfile.STATE_BIDIRECTIONAL);
    }
}
