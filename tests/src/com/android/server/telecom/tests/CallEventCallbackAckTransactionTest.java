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

import static android.telecom.TelecomManager.TELECOM_TRANSACTION_SUCCESS;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import android.os.Bundle;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.telecom.CallAttributes;
import android.telecom.DisconnectCause;

import androidx.test.filters.SmallTest;

import com.android.internal.telecom.ICallEventCallback;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.TransactionalServiceWrapper;
import com.android.server.telecom.callsequencing.CallTransactionResult;
import com.android.server.telecom.callsequencing.voip.CallEventCallbackAckTransaction;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public class CallEventCallbackAckTransactionTest extends TelecomTestCase {
    private static final String CALL_ID = "call1";
    private final TelecomSystem.SyncRoot mLock = new TelecomSystem.SyncRoot() { };

    @Mock private ICallEventCallback mMockICallEventCallback;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    @SmallTest
    @Test
    public void testOnSetActiveSuccess() throws Exception {
        CallEventCallbackAckTransaction transaction = new CallEventCallbackAckTransaction(
                mMockICallEventCallback, TransactionalServiceWrapper.ON_SET_ACTIVE, CALL_ID, mLock);

        doAnswer(invocation -> {
            ResultReceiver receiver = invocation.getArgument(1);
            receiver.send(TELECOM_TRANSACTION_SUCCESS, new Bundle());
            return null;
        }).when(mMockICallEventCallback).onSetActive(eq(CALL_ID), any(ResultReceiver.class));

        CompletionStage<CallTransactionResult> resultStage = transaction.processTransaction(null);
        CallTransactionResult result = resultStage.toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(CallTransactionResult.RESULT_SUCCEED, result.getResult());
        verify(mMockICallEventCallback).onSetActive(eq(CALL_ID), any(ResultReceiver.class));
    }

    @SmallTest
    @Test
    public void testOnSetInactiveSuccess() throws Exception {
        CallEventCallbackAckTransaction transaction = new CallEventCallbackAckTransaction(
                mMockICallEventCallback, TransactionalServiceWrapper.ON_SET_INACTIVE,
                CALL_ID, mLock);

        doAnswer(invocation -> {
            ResultReceiver receiver = invocation.getArgument(1);
            receiver.send(TELECOM_TRANSACTION_SUCCESS, new Bundle());
            return null;
        }).when(mMockICallEventCallback).onSetInactive(eq(CALL_ID), any(ResultReceiver.class));

        CompletionStage<CallTransactionResult> resultStage = transaction.processTransaction(null);
        CallTransactionResult result = resultStage.toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(CallTransactionResult.RESULT_SUCCEED, result.getResult());
        verify(mMockICallEventCallback).onSetInactive(eq(CALL_ID), any(ResultReceiver.class));
    }

    @SmallTest
    @Test
    public void testOnDisconnectSuccess() throws Exception {
        DisconnectCause cause = new DisconnectCause(DisconnectCause.LOCAL);
        CallEventCallbackAckTransaction transaction = new CallEventCallbackAckTransaction(
                mMockICallEventCallback, TransactionalServiceWrapper.ON_DISCONNECT,
                CALL_ID, cause, mLock);

        doAnswer(invocation -> {
            ResultReceiver receiver = invocation.getArgument(2);
            receiver.send(TELECOM_TRANSACTION_SUCCESS, new Bundle());
            return null;
        }).when(mMockICallEventCallback).onDisconnect(eq(CALL_ID), eq(cause),
                any(ResultReceiver.class));

        CompletionStage<CallTransactionResult> resultStage = transaction.processTransaction(null);
        CallTransactionResult result = resultStage.toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(CallTransactionResult.RESULT_SUCCEED, result.getResult());
        verify(mMockICallEventCallback).onDisconnect(eq(CALL_ID), eq(cause),
                any(ResultReceiver.class));
    }

    @SmallTest
    @Test
    public void testOnAnswerSuccess() throws Exception {
        int videoState = CallAttributes.AUDIO_CALL;
        CallEventCallbackAckTransaction transaction = new CallEventCallbackAckTransaction(
                mMockICallEventCallback, TransactionalServiceWrapper.ON_ANSWER,
                CALL_ID, videoState, mLock);

        doAnswer(invocation -> {
            ResultReceiver receiver = invocation.getArgument(2);
            receiver.send(TELECOM_TRANSACTION_SUCCESS, new Bundle());
            return null;
        }).when(mMockICallEventCallback).onAnswer(eq(CALL_ID), eq(videoState),
                any(ResultReceiver.class));

        CompletionStage<CallTransactionResult> resultStage = transaction.processTransaction(null);
        CallTransactionResult result = resultStage.toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(CallTransactionResult.RESULT_SUCCEED, result.getResult());
        verify(mMockICallEventCallback).onAnswer(eq(CALL_ID), eq(videoState),
                any(ResultReceiver.class));
    }

    @SmallTest
    @Test
    public void testOnStreamingStartedSuccess() throws Exception {
        CallEventCallbackAckTransaction transaction = new CallEventCallbackAckTransaction(
                mMockICallEventCallback, TransactionalServiceWrapper.ON_STREAMING_STARTED,
                CALL_ID, mLock);

        doAnswer(invocation -> {
            ResultReceiver receiver = invocation.getArgument(1);
            receiver.send(TELECOM_TRANSACTION_SUCCESS, new Bundle());
            return null;
        }).when(mMockICallEventCallback).onCallStreamingStarted(eq(CALL_ID),
                any(ResultReceiver.class));

        CompletionStage<CallTransactionResult> resultStage = transaction.processTransaction(null);
        CallTransactionResult result = resultStage.toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(CallTransactionResult.RESULT_SUCCEED, result.getResult());
        verify(mMockICallEventCallback).onCallStreamingStarted(eq(CALL_ID),
                any(ResultReceiver.class));
    }

    @SmallTest
    @Test
    public void testRemoteException() throws Exception {
        CallEventCallbackAckTransaction transaction = new CallEventCallbackAckTransaction(
                mMockICallEventCallback, TransactionalServiceWrapper.ON_SET_ACTIVE, CALL_ID, mLock);

        doAnswer(invocation -> {
            throw new RemoteException("Test RemoteException");
        }).when(mMockICallEventCallback).onSetActive(eq(CALL_ID), any(ResultReceiver.class));

        CompletionStage<CallTransactionResult> resultStage = transaction.processTransaction(null);
        CallTransactionResult result = resultStage.toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(android.telecom.CallException.CODE_OPERATION_TIMED_OUT, result.getResult());
    }

    @SmallTest
    @Test
    public void testTransactionTimeout() throws Exception {
        CallEventCallbackAckTransaction transaction = new CallEventCallbackAckTransaction(
                mMockICallEventCallback, TransactionalServiceWrapper.ON_SET_ACTIVE, CALL_ID, mLock);

        // Don't send result, let it timeout.
        // Note: In real scenarios, this will take 5 seconds.

        CompletionStage<CallTransactionResult> resultStage = transaction.processTransaction(null);
        CallTransactionResult result = resultStage.toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(android.telecom.CallException.CODE_OPERATION_TIMED_OUT, result.getResult());
    }

    @SmallTest
    @Test
    public void testUnknownAction() throws Exception {
        CallEventCallbackAckTransaction transaction = new CallEventCallbackAckTransaction(
                mMockICallEventCallback, "UNKNOWN_ACTION", CALL_ID, mLock);

        CompletionStage<CallTransactionResult> resultStage = transaction.processTransaction(null);
        CallTransactionResult result = resultStage.toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(android.telecom.CallException.CODE_OPERATION_TIMED_OUT, result.getResult());
    }
}