/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telecom.BluetoothCallQualityReport;
import android.telecom.CallAudioState;
import android.telecom.DisconnectCause;
import android.telecom.ParcelableCall;
import android.telephony.CallQuality;

import com.android.internal.telecom.ICallDiagnosticService;
import com.android.internal.telecom.ICallDiagnosticServiceAdapter;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallDiagnosticServiceController;
import com.android.server.telecom.CallState;
import com.android.server.telecom.TelecomSystem;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(JUnit4.class)
public class CallDiagnosticServiceControllerTest {
    private static final String TEST_CDS_PACKAGE = "com.test.stuff";
    private static final String TEST_PACKAGE = "com.android.telecom.calldiagnosticservice";
    private static final String TEST_CLASS =
            "com.android.telecom.calldiagnosticservice.TestService";
    private static final ComponentName TEST_COMPONENT = new ComponentName(TEST_PACKAGE, TEST_CLASS);
    private static final List<ResolveInfo> RESOLVE_INFOS = new ArrayList<>();
    private static final ResolveInfo TEST_RESOLVE_INFO = new ResolveInfo();
    static {
        TEST_RESOLVE_INFO.serviceInfo = new ServiceInfo();
        TEST_RESOLVE_INFO.serviceInfo.packageName = TEST_PACKAGE;
        TEST_RESOLVE_INFO.serviceInfo.name = TEST_CLASS;
        TEST_RESOLVE_INFO.serviceInfo.permission = Manifest.permission.BIND_CALL_DIAGNOSTIC_SERVICE;
        RESOLVE_INFOS.add(TEST_RESOLVE_INFO);
    }
    private static final String ID_1 = "1";
    private static final String ID_2 = "2";

    @Mock
    private CallDiagnosticServiceController.ContextProxy mContextProxy;
    @Mock
    private Call mCall;
    @Mock
    private Call mCallTwo;
    @Mock
    private ICallDiagnosticService mICallDiagnosticService;
    @Mock
    private IBinder mMockBinder;
    private TelecomSystem.SyncRoot mLock = new TelecomSystem.SyncRoot() { };

    private CallDiagnosticServiceController mCallDiagnosticServiceController;
    private ServiceConnection mServiceConnection;
    private ICallDiagnosticServiceAdapter mAdapter;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mCall.getId()).thenReturn(ID_1);
        when(mCall.isSimCall()).thenReturn(true);
        when(mCall.isExternalCall()).thenReturn(false);

        when(mCallTwo.getId()).thenReturn(ID_2);
        when(mCallTwo.isSimCall()).thenReturn(true);
        when(mCallTwo.isExternalCall()).thenReturn(false);
        mServiceConnection = null;

        // Mock out the context and other junk that we depend on.
        when(mContextProxy.queryIntentServicesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(RESOLVE_INFOS);
        when(mContextProxy.bindServiceAsUser(any(Intent.class), any(ServiceConnection.class),
                anyInt(), any(UserHandle.class))).thenReturn(true);
        when(mContextProxy.getCurrentUserHandle()).thenReturn(UserHandle.CURRENT);

        when(mMockBinder.queryLocalInterface(anyString())).thenReturn(mICallDiagnosticService);

        mCallDiagnosticServiceController = new CallDiagnosticServiceController(mContextProxy,
                TEST_PACKAGE, mLock);
    }

    /**
     * Verify no binding takes place for a non-sim call.
     */
    @Test
    public void testNoBindOnNonSimCall() {
        Call mockCall = Mockito.mock(Call.class);
        when(mockCall.isSimCall()).thenReturn(false);

        mCallDiagnosticServiceController.onCallAdded(mockCall);

        verify(mContextProxy, never()).bindServiceAsUser(any(Intent.class),
                any(ServiceConnection.class), anyInt(), any(UserHandle.class));
    }

    /**
     * Verify no binding takes place for a SIM external call.
     */
    @Test
    public void testNoBindOnExternalCall() {
        Call mockCall = Mockito.mock(Call.class);
        when(mockCall.isSimCall()).thenReturn(true);
        when(mockCall.isExternalCall()).thenReturn(true);

        mCallDiagnosticServiceController.onCallAdded(mockCall);

        verify(mContextProxy, never()).bindServiceAsUser(any(Intent.class),
                any(ServiceConnection.class), anyInt(), any(UserHandle.class));
    }

    /**
     * Verify a valid SIM call causes binding to initiate.
     */
    @Test
    public void testAddSimCallCausesBind() throws RemoteException {
        mCallDiagnosticServiceController.onCallAdded(mCall);

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        ArgumentCaptor<ServiceConnection> serviceConnectionCaptor = ArgumentCaptor.forClass(
                ServiceConnection.class);
        verify(mContextProxy).bindServiceAsUser(intentCaptor.capture(),
                serviceConnectionCaptor.capture(), anyInt(), any(UserHandle.class));
        assertEquals(TEST_PACKAGE, intentCaptor.getValue().getPackage());

        // Now we'll pretend bind completed and we sent back the binder.
        serviceConnectionCaptor.getValue().onServiceConnected(TEST_COMPONENT, mMockBinder);
        mServiceConnection = serviceConnectionCaptor.getValue();

        // Make sure it's sent
        verify(mICallDiagnosticService).initializeDiagnosticCall(any(ParcelableCall.class));
    }

    /**
     * Verify removing the active call causes it to be removed from the CallDiagnosticService and
     * that an unbind takes place.
     */
    @Test
    public void testRemoveSimCallCausesRemoveAndUnbind() throws RemoteException {
        testAddSimCallCausesBind();
        mCallDiagnosticServiceController.onCallRemoved(mCall);

        verify(mICallDiagnosticService).removeDiagnosticCall(eq(ID_1));
        verify(mContextProxy).unbindService(eq(mServiceConnection));
    }

    /**
     * Try to add and remove two and verify bind/unbind.
     */
    @Test
    public void testAddTwo() throws RemoteException {
        testAddSimCallCausesBind();
        mCallDiagnosticServiceController.onCallAdded(mCallTwo);
        verify(mICallDiagnosticService, times(2)).initializeDiagnosticCall(
                any(ParcelableCall.class));

        mCallDiagnosticServiceController.onCallRemoved(mCall);
        // Not yet!
        verify(mContextProxy, never()).unbindService(eq(mServiceConnection));

        mCallDiagnosticServiceController.onCallRemoved(mCallTwo);

        verify(mICallDiagnosticService).removeDiagnosticCall(eq(ID_1));
        verify(mICallDiagnosticService).removeDiagnosticCall(eq(ID_2));
        verify(mContextProxy).unbindService(eq(mServiceConnection));
    }

    /**
     * Verifies we can override the call diagnostic service package to a test package (used by CTS
     * tests).
     */
    @Test
    public void testTestOverride() {
        mCallDiagnosticServiceController.setTestCallDiagnosticService(TEST_CDS_PACKAGE);
        mCallDiagnosticServiceController.onCallAdded(mCall);

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContextProxy).bindServiceAsUser(intentCaptor.capture(),
                any(ServiceConnection.class), anyInt(), any(UserHandle.class));
        assertEquals(TEST_CDS_PACKAGE, intentCaptor.getValue().getPackage());
    }

    /**
     * Binds the service and captures the service connection and adapter for subsequent tests.
     */
    private void bindService() throws RemoteException {
        mCallDiagnosticServiceController.onCallAdded(mCall);
        ArgumentCaptor<ServiceConnection> serviceConnectionCaptor = ArgumentCaptor.forClass(
                ServiceConnection.class);
        verify(mContextProxy).bindServiceAsUser(any(Intent.class),
                serviceConnectionCaptor.capture(), anyInt(), any(UserHandle.class));
        mServiceConnection = serviceConnectionCaptor.getValue();

        ArgumentCaptor<ICallDiagnosticServiceAdapter> adapterCaptor =
                ArgumentCaptor.forClass(ICallDiagnosticServiceAdapter.class);
        mServiceConnection.onServiceConnected(TEST_COMPONENT, mMockBinder);
        verify(mICallDiagnosticService).setAdapter(adapterCaptor.capture());
        mAdapter = adapterCaptor.getValue();
    }

    /**
     * Verifies that when a call's state changes, the update is propagated to the CDS.
     */
    @Test
    public void testCallStateChangeIsPropagated() throws RemoteException {
        bindService();

        mCallDiagnosticServiceController.onCallStateChanged(mCall, CallState.NEW,
                CallState.DIALING);
        verify(mICallDiagnosticService, times(1)).updateCall(any(ParcelableCall.class));
    }

    /**
     * Verifies that when the CallAudioState changes, the update is propagated to the CDS.
     */
    @Test
    public void testCallAudioStateChangeIsPropagated() throws RemoteException {
        bindService();
        CallAudioState newState = new CallAudioState(false, CallAudioState.ROUTE_SPEAKER, 0);
        mCallDiagnosticServiceController.onCallAudioStateChanged(null, newState);
        verify(mICallDiagnosticService).updateCallAudioState(eq(newState));
    }

    /**
     * Verifies that a call quality report is forwarded to the CDS.
     */
    @Test
    public void testCallQualityReportIsPropagated() throws RemoteException {
        bindService();
        Call.Listener listener = getCallListener();
        CallQuality report = mock(CallQuality.class);
        listener.onReceivedCallQualityReport(mCall, report);
        verify(mICallDiagnosticService).callQualityChanged(eq(ID_1), eq(report));
    }

    /**
     * Verifies that a bluetooth call quality report is forwarded to the CDS.
     */
    @Test
    public void testBluetoothCallQualityReportIsPropagated() throws RemoteException {
        bindService();
        Call.Listener listener = getCallListener();
        BluetoothCallQualityReport report = mock(BluetoothCallQualityReport.class);
        listener.onBluetoothCallQualityReport(mCall, report);
        verify(mICallDiagnosticService).receiveBluetoothCallQualityReport(eq(report));
    }

    /**
     * Verifies that a D2D message received from the network is forwarded to the CDS.
     */
    @Test
    public void testReceivedD2DMessageIsPropagated() throws RemoteException {
        bindService();
        Call.Listener listener = getCallListener();
        listener.onReceivedDeviceToDeviceMessage(mCall, 1, 2);
        verify(mICallDiagnosticService).receiveDeviceToDeviceMessage(eq(ID_1), eq(1), eq(2));
    }

    /**
     * Verifies that a request from the CDS to display a message is passed to the correct call.
     */
    @Test
    public void testDisplayDiagnosticMessageCallback() throws RemoteException {
        bindService();
        mAdapter.displayDiagnosticMessage(ID_1, 123, "test message");
        verify(mCall).displayDiagnosticMessage(eq(123), eq("test message"));
    }

    /**
     * Verifies that a request from the CDS to clear a message is passed to the correct call.
     */
    @Test
    public void testClearDiagnosticMessageCallback() throws RemoteException {
        bindService();
        mAdapter.clearDiagnosticMessage(ID_1, 123);
        verify(mCall).clearDiagnosticMessage(eq(123));
    }

    /**
     * Verifies that a request from the CDS to send a D2D message is passed to the correct call.
     */
    @Test
    public void testSendD2DMessageCallback() throws RemoteException {
        bindService();
        mAdapter.sendDeviceToDeviceMessage(ID_1, 1, 2);
        verify(mCall).sendDeviceToDeviceMessage(eq(1), eq(2));
    }

    /**
     * Verifies that a request from the CDS to override a disconnect message is passed to the call.
     */
    @Test
    public void testOverrideDisconnectMessageCallback() throws RemoteException {
        bindService();
        mAdapter.overrideDisconnectMessage(ID_1, "new message");
        verify(mCall).handleOverrideDisconnectMessage(eq("new message"));
    }

    /**
     * Verifies that when a call is disconnected, the CDS is notified.
     */
    @Test
    public void testOnCallDisconnected() throws RemoteException {
        bindService();
        DisconnectCause cause = new DisconnectCause(DisconnectCause.LOCAL);
        assertTrue(mCallDiagnosticServiceController.onCallDisconnected(mCall, cause));
        verify(mICallDiagnosticService).notifyCallDisconnected(eq(ID_1), eq(cause));
    }

    /**
     * Verifies that the service is unbound when onBindingDied is called.
     */
    @Test
    public void testOnBindingDied() throws RemoteException {
        bindService();
        mServiceConnection.onBindingDied(TEST_COMPONENT);
        assertFalse(mCallDiagnosticServiceController.isConnected());
        // Should not trigger an explicit unbind, as the binding is already dead.
        verify(mContextProxy, never()).unbindService(any());
    }

    /**
     * Verifies that the service is unbound when onNullBinding is called.
     */
    @Test
    public void testOnNullBinding() throws RemoteException {
        bindService();
        mServiceConnection.onNullBinding(TEST_COMPONENT);
        assertFalse(mCallDiagnosticServiceController.isConnected());
        verify(mContextProxy).unbindService(eq(mServiceConnection));
    }

    /**
     * Verifies that no binding occurs if the resolved service does not require the correct
     * permission.
     */
    @Test
    public void testBindFailsWithIncorrectPermission() {
        ResolveInfo badResolveInfo = new ResolveInfo();
        badResolveInfo.serviceInfo = new ServiceInfo();
        badResolveInfo.serviceInfo.packageName = TEST_PACKAGE;
        badResolveInfo.serviceInfo.name = TEST_CLASS;
        badResolveInfo.serviceInfo.permission = "android.permission.INTERNET"; // Wrong permission
        when(mContextProxy.queryIntentServicesAsUser(any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(badResolveInfo));

        mCallDiagnosticServiceController.onCallAdded(mCall);

        verify(mContextProxy, never()).bindServiceAsUser(any(), any(), anyInt(), any());
    }

    /**
     * Extracts the Call.Listener that the controller adds to calls.
     */
    private Call.Listener getCallListener() {
        ArgumentCaptor<Call.Listener> captor = ArgumentCaptor.forClass(Call.Listener.class);
        verify(mCall, atLeastOnce()).addListener(captor.capture());
        return captor.getValue();
    }
}
