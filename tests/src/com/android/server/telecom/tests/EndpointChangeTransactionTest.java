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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Bundle;
import android.os.ResultReceiver;
import android.telecom.CallEndpoint;
import android.telecom.CallException;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.CallsManager;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.callsequencing.CallTransactionResult;
import com.android.server.telecom.callsequencing.voip.EndpointChangeTransaction;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

@RunWith(JUnit4.class)
public class EndpointChangeTransactionTest extends TelecomTestCase {

    @Mock private CallsManager mCallsManager;
    @Mock private CallEndpoint mCallEndpoint;

    private static final int TEST_UID = 12345;
    private final TelecomSystem.SyncRoot mLock = new TelecomSystem.SyncRoot() { };

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        when(mCallsManager.getLock()).thenReturn(mLock);
    }

    @Test
    @SmallTest
    public void testProcessTransactionSuccess() throws ExecutionException, InterruptedException {
        EndpointChangeTransaction transaction =
                new EndpointChangeTransaction(mCallEndpoint, mCallsManager, TEST_UID);

        CompletionStage<CallTransactionResult> resultFuture = transaction.processTransaction(null);

        ArgumentCaptor<ResultReceiver> captor = ArgumentCaptor.forClass(ResultReceiver.class);
        verify(mCallsManager).requestCallEndpointChange(eq(TEST_UID), eq(mCallEndpoint),
                captor.capture());

        ResultReceiver receiver = captor.getValue();
        receiver.send(CallEndpoint.ENDPOINT_OPERATION_SUCCESS, new Bundle());

        CallTransactionResult result = resultFuture.toCompletableFuture().get();
        assertEquals(CallTransactionResult.RESULT_SUCCEED, result.getResult());
    }

    @Test
    @SmallTest
    public void testProcessTransactionFailure() throws ExecutionException, InterruptedException {
        EndpointChangeTransaction transaction =
                new EndpointChangeTransaction(mCallEndpoint, mCallsManager, TEST_UID);

        CompletionStage<CallTransactionResult> resultFuture = transaction.processTransaction(null);

        ArgumentCaptor<ResultReceiver> captor = ArgumentCaptor.forClass(ResultReceiver.class);
        verify(mCallsManager).requestCallEndpointChange(eq(TEST_UID), eq(mCallEndpoint),
                captor.capture());

        ResultReceiver receiver = captor.getValue();
        receiver.send(CallEndpoint.ENDPOINT_OPERATION_FAILED, new Bundle());

        CallTransactionResult result = resultFuture.toCompletableFuture().get();
        assertEquals(CallException.CODE_ERROR_UNKNOWN, result.getResult());
    }
}
