/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.telecom.CallException.CODE_CALL_IS_NOT_BEING_TRACKED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.isA;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;


import android.content.ComponentName;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.telecom.CallException;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;

import com.android.internal.telecom.ICallControl;
import com.android.internal.telecom.ICallEventCallback;
import com.android.server.telecom.AnomalyReporterAdapter;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.TransactionalServiceRepository;
import com.android.server.telecom.TransactionalServiceWrapper;
import com.android.server.telecom.callsequencing.CallSequencingController;
import com.android.server.telecom.callsequencing.CallsManagerCallSequencingAdapter;
import com.android.server.telecom.callsequencing.TransactionManager;
import com.android.server.telecom.callsequencing.voip.EndCallTransaction;
import com.android.server.telecom.callsequencing.voip.HoldCallTransaction;
import com.android.server.telecom.callsequencing.voip.SerialTransaction;
import com.android.server.telecom.callsequencing.voip.SetGroupCallStateTransaction;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CompletableFuture;

@RunWith(JUnit4.class)
public class TransactionalServiceWrapperTest extends TelecomTestCase {

    private static final PhoneAccountHandle SERVICE_HANDLE = new PhoneAccountHandle(
            ComponentName.unflattenFromString("com.foo/.Blah"), "Service1");

    private static final String CALL_ID_1 = "1";
    private static final String CALL_ID_2 = "2";

    TransactionalServiceWrapper mTransactionalServiceWrapper;

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock private Call mMockCall1;
    @Mock private Call mMockCall2;
    @Mock private CallsManager mCallsManager;
    @Mock private CallsManagerCallSequencingAdapter mCallsManagerCallSequencingAdapter;
    @Mock private TransactionManager mTransactionManager;
    @Mock private ICallEventCallback mCallEventCallback;
    @Mock private TransactionalServiceRepository mRepository;
    @Mock private AnomalyReporterAdapter mAnomalyReporterAdapter;
    @Mock private IBinder mIBinder;
    @Mock private ResultReceiver mMockResultReceiver;
    private final TelecomSystem.SyncRoot mLock = new TelecomSystem.SyncRoot() {};

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        Mockito.when(mMockCall1.getId()).thenReturn(CALL_ID_1);
        Mockito.when(mMockCall2.getId()).thenReturn(CALL_ID_2);
        Mockito.when(mCallsManager.getLock()).thenReturn(mLock);
        Mockito.when(mCallEventCallback.asBinder()).thenReturn(mIBinder);
        Mockito.when(mCallsManager.getCallSequencingAdapter())
                .thenReturn(mCallsManagerCallSequencingAdapter);
        mTransactionalServiceWrapper = new TransactionalServiceWrapper(mCallEventCallback,
                mCallsManager, SERVICE_HANDLE, mMockCall1, mRepository, mTransactionManager,
                mFeatureFlags, mAnomalyReporterAdapter);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testTransactionalServiceWrapperStartState() throws Exception {
        TransactionalServiceWrapper service =
                new TransactionalServiceWrapper(mCallEventCallback,
                        mCallsManager, SERVICE_HANDLE, mMockCall1, mRepository, mTransactionManager,
                        mFeatureFlags, mAnomalyReporterAdapter);

        assertEquals(SERVICE_HANDLE, service.getPhoneAccountHandle());
        assertEquals(1, service.getNumberOfTrackedCalls());
    }

    @Test
    public void testTransactionalServiceWrapperCallCount() throws Exception {
        TransactionalServiceWrapper service =
                new TransactionalServiceWrapper(mCallEventCallback,
                        mCallsManager, SERVICE_HANDLE, mMockCall1, mRepository, mTransactionManager,
                        mFeatureFlags, mAnomalyReporterAdapter);

        assertEquals(1, service.getNumberOfTrackedCalls());
        service.trackCall(mMockCall2);
        assertEquals(2, service.getNumberOfTrackedCalls());

        assertTrue(service.untrackCall(mMockCall2));
        assertEquals(1, service.getNumberOfTrackedCalls());

        assertTrue(service.untrackCall(mMockCall1));
        assertFalse(service.untrackCall(mMockCall1));
        assertEquals(0, service.getNumberOfTrackedCalls());
    }

    @Test
    public void testCallControlSetActive() throws RemoteException {
        // GIVEN
        mTransactionalServiceWrapper.trackCall(mMockCall1);
        Mockito.doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            OutcomeReceiver<Boolean, CallException> callback =
                    (OutcomeReceiver<Boolean, CallException>) args[2];
            callback.onResult(true);
            return callback;
        }).when(mCallsManagerCallSequencingAdapter).transactionHoldPotentialActiveCallForNewCall(
                any(Call.class), anyBoolean(), any(OutcomeReceiver.class));

        // WHEN
        ICallControl callControl = mTransactionalServiceWrapper.getICallControl();
        callControl.setActive(CALL_ID_1, new ResultReceiver(null));

        //THEN
        verify(mTransactionManager, times(1))
                .addTransaction(isA(SerialTransaction.class), isA(OutcomeReceiver.class));
    }

    @Test
    public void testCallControlRejectCall() throws RemoteException {
        // GIVEN
        mTransactionalServiceWrapper.trackCall(mMockCall1);

        // WHEN
        ICallControl callControl = mTransactionalServiceWrapper.getICallControl();
        callControl.disconnect(CALL_ID_1, new DisconnectCause(DisconnectCause.REJECTED),
                new ResultReceiver(null));

        //THEN
        verify(mTransactionManager, times(1))
                .addTransaction(isA(EndCallTransaction.class), isA(OutcomeReceiver.class));
    }

    @Test
    public void testCallControlDisconnectCall() throws RemoteException {
        // GIVEN
        mTransactionalServiceWrapper.trackCall(mMockCall1);

        // WHEN
        ICallControl callControl = mTransactionalServiceWrapper.getICallControl();
        callControl.disconnect(CALL_ID_1, new DisconnectCause(DisconnectCause.LOCAL),
                new ResultReceiver(null));

        //THEN
        verify(mTransactionManager, times(1))
                .addTransaction(isA(EndCallTransaction.class), isA(OutcomeReceiver.class));
    }

    @Test
    public void testCallControlSetInactive() throws RemoteException {
        // GIVEN
        mTransactionalServiceWrapper.trackCall(mMockCall1);

        // WHEN
        ICallControl callControl = mTransactionalServiceWrapper.getICallControl();
        callControl.setInactive(CALL_ID_1, new ResultReceiver(null));

        //THEN
        verify(mTransactionManager, times(1))
                .addTransaction(isA(HoldCallTransaction.class), isA(OutcomeReceiver.class));
    }

    @Test
    @EnableFlags(android.telecom.flags.Flags.FLAG_INTEGRATED_CALL_LOGS_STAGE2)
    public void testSetGroupCallState() throws RemoteException {
        // GIVEN a tracked call
        mTransactionalServiceWrapper.trackCall(mMockCall1);

        // WHEN setGroupCallState is called
        ICallControl callControl = mTransactionalServiceWrapper.getICallControl();
        callControl.setGroupCallState(CALL_ID_1, true, new ResultReceiver(null));

        // THEN verify that a SetGroupCallStateTransaction is created and added to the manager
        verify(mTransactionManager, times(1))
                .addTransaction(
                  isA(SetGroupCallStateTransaction.class), isA(OutcomeReceiver.class));
    }

    @Test
    @EnableFlags(android.telecom.flags.Flags.FLAG_INTEGRATED_CALL_LOGS_STAGE2)
    public void testSetGroupCallState_untrackedCall() throws RemoteException {
        // GIVEN a call ID that is not being tracked and anomaly reporting is enabled

        // WHEN setGroupCallState is called for the untracked call
        ICallControl callControl = mTransactionalServiceWrapper.getICallControl();
        callControl.setGroupCallState(CALL_ID_2, true, mMockResultReceiver);

        // THEN verify the correct error is returned and logged
        verifyUntrackedCallBehavior();
    }

    @Test
    @EnableFlags(android.telecom.flags.Flags.FLAG_INTEGRATED_CALL_LOGS_STAGE2)
    public void testSetContactUri() throws RemoteException {
        // GIVEN a tracked call
        mTransactionalServiceWrapper.trackCall(mMockCall1);
        Uri uri = Uri.fromParts("sip", "foo@bar.com", null);

        // WHEN setContactUri is called
        ICallControl callControl = mTransactionalServiceWrapper.getICallControl();
        callControl.setContactUri(CALL_ID_1, uri, new ResultReceiver(null));

        // THEN verify that a transaction is created and added to the manager.
        // Note: The source code incorrectly uses SetGroupCallStateTransaction for setContactUri.
        // This test verifies the current (potentially incorrect) behavior.
        verify(mTransactionManager, times(1))
                .addTransaction(
                  isA(SetGroupCallStateTransaction.class), isA(OutcomeReceiver.class));
    }

    @Test
    @EnableFlags(android.telecom.flags.Flags.FLAG_INTEGRATED_CALL_LOGS_STAGE2)
    public void testSetContactUri_untrackedCall() throws RemoteException {
        // GIVEN a call ID that is not being tracked and anomaly reporting is enabled
        Uri uri = Uri.fromParts("sip", "foo@bar.com", null);

        // WHEN setContactUri is called for the untracked call
        ICallControl callControl = mTransactionalServiceWrapper.getICallControl();
        callControl.setContactUri(CALL_ID_2, uri, mMockResultReceiver);

        // THEN verify the correct error is returned and logged
        verifyUntrackedCallBehavior();
    }

    private void verifyUntrackedCallBehavior() {
        // Verify no transaction is created
        verify(mTransactionManager, never()).addTransaction(any(), any());
        // Verify the callback receives an error
        verify(mMockResultReceiver).send(eq(CODE_CALL_IS_NOT_BEING_TRACKED), any(Bundle.class));
        // Verify an anomaly is reported
        verify(mAnomalyReporterAdapter).reportAnomaly(
                eq(TransactionalServiceWrapper.CALL_IS_NO_LONGER_BEING_TRACKED_ERROR_UUID),
                eq(TransactionalServiceWrapper.CALL_IS_NO_LONGER_BEING_TRACKED_ERROR_MSG));
    }
}
