/*
 * Copyright (C) 2024 The Android Open Source Project
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.telecom.CallAudioState;
import android.telecom.CallEndpoint;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.DisconnectCause;
import android.telecom.GatewayInfo;
import android.telecom.Logging.Session;
import android.telecom.ParcelableConference;
import android.telecom.ParcelableConnection;
import android.telecom.PhoneAccountHandle;
import android.telecom.QueryLocationException;
import android.telecom.StatusHints;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.CellIdentity;
import android.telephony.TelephonyManager;

import com.android.internal.telecom.IConnectionService;
import com.android.internal.telecom.IConnectionServiceAdapter;
import com.android.internal.telecom.IVideoProvider;
import com.android.internal.telecom.RemoteServiceCallback;
import com.android.server.telecom.AudioRoute;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.ConnectionServiceFocusManager;
import com.android.server.telecom.ConnectionServiceRepository;
import com.android.server.telecom.ConnectionServiceWrapper;
import com.android.server.telecom.CreateConnectionResponse;
import com.android.server.telecom.EmergencyCallHelper;
import com.android.server.telecom.PhoneAccountRegistrar;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.flags.FeatureFlags;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@RunWith(JUnit4.class)
public class ConnectionServiceWrapperTest extends TelecomTestCase {
    private static final String CALL_ID = "call_id";
    private static final String CONF_CALL_ID = "conf_call_id";
    private static final ComponentName COMPONENT_NAME = new ComponentName("foo", "bar");

    @Mock
    private ConnectionServiceRepository mMockRepository;
    @Mock
    private PhoneAccountRegistrar mMockPhoneAccountRegistrar;
    @Mock
    private CallsManager mMockCallsManager;
    @Mock
    private FeatureFlags mMockFeatureFlags;
    @Mock
    private IConnectionService mMockConnectionService;
    @Mock
    private IBinder mMockBinder;
    @Mock
    private Call mMockCall;
    @Mock
    private Call mMockConferenceCall;
    @Mock
    private AppOpsManager mAppOpsManager;
    @Mock
    private LocationManager mLocationManager;
    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private TelecomManager mTelecomManager;
    @Mock
    private EmergencyCallHelper mEmergencyCallHelper;
    @Mock
    private PackageManager mPackageManager;

    private static class TestableConnectionServiceWrapper extends ConnectionServiceWrapper {
        private boolean mIsServiceValidOverride = false;

        private TestableConnectionServiceWrapper(
                ComponentName componentName,
                ConnectionServiceRepository connectionServiceRepository,
                PhoneAccountRegistrar phoneAccountRegistrar,
                CallsManager callsManager,
                Context context,
                TelecomSystem.SyncRoot lock,
                UserHandle userHandle,
                FeatureFlags featureFlags) {
            super(componentName, connectionServiceRepository, phoneAccountRegistrar, callsManager,
                    context, lock, userHandle, featureFlags);
        }

        @Override
        public void setServiceInterface(IBinder binder) {
            super.setServiceInterface(binder);
            mIsServiceValidOverride = (binder != null);
        }

        @Override
        public boolean isServiceValid(String actionName) {
            if (mIsServiceValidOverride) return true;
            return super.isServiceValid(actionName);
        }

        @Override
        public void removeServiceInterface() {
            super.removeServiceInterface();
        }

        private void setServiceValid(boolean valid) {
            mIsServiceValidOverride = valid;
        }
    }

    private TestableConnectionServiceWrapper mWrapper;
    private IConnectionServiceAdapter.Stub mAdapter;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();

        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.getOpPackageName()).thenReturn("foo");
        when(mContext.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mAppOpsManager);
        when(mContext.getSystemService(Context.LOCATION_SERVICE)).thenReturn(mLocationManager);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        when(mContext.getSystemService(TelecomManager.class)).thenReturn(mTelecomManager);
        when(mContext.createAttributionContext(anyString())).thenReturn(mContext);
        when(mContext.createContextAsUser(any(), anyInt())).thenReturn(mContext);
        doReturn(true).when(mContext).bindServiceAsUser(any(), any(), anyInt(), any());
        doNothing().when(mContext).unbindService(any());

        PhoneAccountHandle simCallManager = new PhoneAccountHandle(COMPONENT_NAME, "sim_mgr");
        when(mTelecomManager.getSimCallManager()).thenReturn(simCallManager);

        mWrapper = new TestableConnectionServiceWrapper(
                COMPONENT_NAME,
                mMockRepository,
                mMockPhoneAccountRegistrar,
                mMockCallsManager,
                mContext,
                new TelecomSystem.SyncRoot() {
                },
                UserHandle.CURRENT,
                mMockFeatureFlags);

        when(mMockConnectionService.asBinder()).thenReturn(mMockBinder);
        when(mMockBinder.queryLocalInterface(anyString())).thenReturn(mMockConnectionService);

        mAdapter = mWrapper.getAdapter();

        when(mMockCall.getId()).thenReturn(CALL_ID);
        when(mMockCall.getConnectionId()).thenReturn(CALL_ID);
        mWrapper.addCall(mMockCall);
        when(mMockCallsManager.createConferenceCall(anyString(),
                any(PhoneAccountHandle.class), any(ParcelableConference.class)))
                .thenReturn(mMockConferenceCall);
        when(mMockCallsManager.getEmergencyCallHelper()).thenReturn(mEmergencyCallHelper);
        when(mMockCallsManager.createCallForExistingConnection(anyString(), any()))
                .thenReturn(mock(Call.class));
    }

    private void triggerBindSuccess() {
        triggerBindSuccess(COMPONENT_NAME, mMockBinder);
    }

    private void triggerBindSuccess(ComponentName name, IBinder binder) {
        ArgumentCaptor<android.content.ServiceConnection> captor =
                ArgumentCaptor.forClass(android.content.ServiceConnection.class);
        verify(mContext, atLeastOnce()).bindServiceAsUser(any(), captor.capture(), anyInt(), any());
        List<android.content.ServiceConnection> connections = captor.getAllValues();
        // Trigger for the specific component if multiple were requested, otherwise just the last.
        connections.get(connections.size() - 1).onServiceConnected(name, binder);
    }

    private void triggerBindFailure() {
        triggerBindFailure(COMPONENT_NAME);
    }

    private void triggerBindFailure(ComponentName name) {
        ArgumentCaptor<android.content.ServiceConnection> captor =
                ArgumentCaptor.forClass(android.content.ServiceConnection.class);
        verify(mContext, atLeastOnce()).bindServiceAsUser(any(), captor.capture(), anyInt(), any());
        List<android.content.ServiceConnection> connections = captor.getAllValues();
        connections.get(connections.size() - 1).onNullBinding(name);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    // =============================================================================================
    // Connection Lifecycle & Service Control
    // =============================================================================================

    @Test
    public void testAbort() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.abort(mMockCall);
        verify(mMockConnectionService).abort(eq(CALL_ID), any());
    }

    @Test
    public void testAbort_RemoteException() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService).abort(anyString(), any());
        mWrapper.abort(mMockCall);
        // Even if remote exception occurs, local removal should still proceed
        assertNull(mWrapper.getCallIdMapper().getCallId(mMockCall));
    }

    @Test
    public void testSilence() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.silence(mMockCall);
        verify(mMockConnectionService).silence(eq(CALL_ID), any());
    }

    @Test
    public void testSilence_RemoteException() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService).silence(anyString(), any());
        mWrapper.silence(mMockCall);
    }

    @Test
    public void testHold() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.hold(mMockCall);
        verify(mMockConnectionService).hold(eq(CALL_ID), any());
    }

    @Test
    public void testHold_RemoteException() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService).hold(anyString(), any());
        mWrapper.hold(mMockCall);
    }

    @Test
    public void testUnhold() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.unhold(mMockCall);
        verify(mMockConnectionService).unhold(eq(CALL_ID), any());
    }

    @Test
    public void testUnhold_RemoteException() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService).unhold(anyString(), any());
        mWrapper.unhold(mMockCall);
    }

    @Test
    public void testDisconnect() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.disconnect(mMockCall);
        verify(mMockConnectionService).disconnect(eq(CALL_ID), any());
    }

    @Test
    public void testDisconnect_RemoteException() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService).disconnect(anyString(), any());
        mWrapper.disconnect(mMockCall);
    }

    @Test
    public void testAnswer() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.answer(mMockCall, VideoProfile.STATE_AUDIO_ONLY);
        verify(mMockConnectionService).answer(eq(CALL_ID), any());
        mWrapper.answer(mMockCall, VideoProfile.STATE_BIDIRECTIONAL);
        verify(mMockConnectionService).answerVideo(eq(CALL_ID),
                eq(VideoProfile.STATE_BIDIRECTIONAL), any());
    }

    @Test
    public void testAnswer_RemoteException() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService).answer(anyString(), any());
        mWrapper.answer(mMockCall, 0);
    }

    @Test
    public void testDeflect_RemoteException() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService).deflect(anyString(),
                any(), any());
        mWrapper.deflect(mMockCall, Uri.parse("tel:123"));
    }

    @Test
    public void testReject_WithMessage() throws RemoteException {
        when(mMockCall.can(Connection.CAPABILITY_CAN_SEND_RESPONSE_VIA_CONNECTION))
                .thenReturn(true);
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.reject(mMockCall, true, "message");
        verify(mMockConnectionService).rejectWithMessage(eq(CALL_ID), eq("message"), any());
    }

    @Test
    public void testReject_NoMessage() throws RemoteException {
        when(mMockCall.can(Connection.CAPABILITY_CAN_SEND_RESPONSE_VIA_CONNECTION))
                .thenReturn(false);
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.reject(mMockCall, true, "message");
        verify(mMockConnectionService).reject(eq(CALL_ID), any());
    }

    @Test
    public void testRejectWithReason() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.rejectWithReason(mMockCall, 1);
        verify(mMockConnectionService).rejectWithReason(eq(CALL_ID), eq(1), any());
    }

    @Test
    public void testRejectWithReason_RemoteException() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService)
                .rejectWithReason(anyString(), anyInt(), any());
        mWrapper.rejectWithReason(mMockCall, 1);
    }

    @Test
    public void testTransfer() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        Uri address = Uri.parse("tel:5551212");
        mWrapper.transfer(mMockCall, address, true);
        verify(mMockConnectionService).transfer(eq(CALL_ID), eq(address), eq(true), any());
    }

    @Test
    public void testTransfer_RemoteException() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService)
                .transfer(anyString(), any(), anyBoolean(), any());
        mWrapper.transfer(mMockCall, Uri.parse("tel:123"), true);
    }

    @Test
    public void testConsultativeTransfer() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        Call otherCall = mock(Call.class);
        when(otherCall.getId()).thenReturn("other_id");
        when(otherCall.getConnectionId()).thenReturn("other_id");
        mWrapper.addCall(otherCall);
        mWrapper.transfer(mMockCall, otherCall);
        verify(mMockConnectionService).consultativeTransfer(eq(CALL_ID), eq("other_id"), any());
    }

    @Test
    public void testConsultativeTransfer_RemoteException() throws RemoteException {
        Call otherCall = mock(Call.class);
        when(otherCall.getId()).thenReturn("other_id");
        when(otherCall.getConnectionId()).thenReturn("other_id");
        mWrapper.addCall(otherCall);
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService)
                .consultativeTransfer(anyString(), anyString(), any());
        mWrapper.transfer(mMockCall, otherCall);
    }

    @Test
    public void testOnPostDialContinue() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.onPostDialContinue(mMockCall, true);
        verify(mMockConnectionService).onPostDialContinue(eq(CALL_ID), eq(true), any());
    }

    @Test
    public void testOnPostDialContinue_RemoteException() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService)
                .onPostDialContinue(anyString(), anyBoolean(), any());
        mWrapper.onPostDialContinue(mMockCall, true);
    }

    @Test
    public void testConference() throws RemoteException {
        String otherCallId = "OTHER_CALL_ID";
        Call otherCall = mock(Call.class);
        when(otherCall.getId()).thenReturn(otherCallId);
        when(otherCall.getConnectionId()).thenReturn(otherCallId);
        mWrapper.addCall(otherCall);
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.conference(mMockCall, otherCall);
        verify(mMockConnectionService).conference(eq(CALL_ID), eq(otherCallId), any());
    }

    @Test
    public void testConference_RemoteException() throws RemoteException {
        String otherCallId = "OTHER_CALL_ID";
        Call otherCall = mock(Call.class);
        when(otherCall.getId()).thenReturn(otherCallId);
        when(otherCall.getConnectionId()).thenReturn(otherCallId);
        mWrapper.addCall(otherCall);
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService)
                .conference(anyString(), anyString(), any());
        mWrapper.conference(mMockCall, otherCall);
    }

    @Test
    public void testSplitFromConference() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.splitFromConference(mMockCall);
        verify(mMockConnectionService).splitFromConference(eq(CALL_ID), any());
    }

    @Test
    public void testSplitFromConference_RemoteException() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService)
                .splitFromConference(anyString(), any());
        mWrapper.splitFromConference(mMockCall);
    }

    @Test
    public void testMergeConference() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.mergeConference(mMockCall);
        verify(mMockConnectionService).mergeConference(eq(CALL_ID), any());
    }

    @Test
    public void testMergeConference_RemoteException() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService)
                .mergeConference(anyString(), any());
        mWrapper.mergeConference(mMockCall);
    }

    @Test
    public void testSwapConference() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.swapConference(mMockCall);
        verify(mMockConnectionService).swapConference(eq(CALL_ID), any());
    }

    @Test
    public void testSwapConference_RemoteException() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService)
                .swapConference(anyString(), any());
        mWrapper.swapConference(mMockCall);
    }

    @Test
    public void testAddConferenceParticipants() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        List<Uri> participants = new ArrayList<>();
        mWrapper.addConferenceParticipants(mMockCall, participants);
        verify(mMockConnectionService).addConferenceParticipants(eq(CALL_ID),
                eq(participants), any());
    }

    @Test
    public void testAddConferenceParticipants_RemoteException() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService)
                .addConferenceParticipants(anyString(), any(), any());
        mWrapper.addConferenceParticipants(mMockCall, new ArrayList<>());
    }

    @Test
    public void testStartRtt() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        ParcelFileDescriptor fd1 = mock(ParcelFileDescriptor.class);
        ParcelFileDescriptor fd2 = mock(ParcelFileDescriptor.class);
        mWrapper.startRtt(mMockCall, fd1, fd2);
        verify(mMockConnectionService).startRtt(eq(CALL_ID), eq(fd1), eq(fd2), any());
    }

    @Test
    public void testStartRtt_RemoteException() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService)
                .startRtt(anyString(), any(), any(), any());
        mWrapper.startRtt(mMockCall, mock(ParcelFileDescriptor.class),
                mock(ParcelFileDescriptor.class));
    }

    @Test
    public void testStopRtt() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.stopRtt(mMockCall);
        verify(mMockConnectionService).stopRtt(eq(CALL_ID), any());
    }

    @Test
    public void testStopRtt_RemoteException() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService).stopRtt(anyString(), any());
        mWrapper.stopRtt(mMockCall);
    }

    @Test
    public void testRespondToRttRequest() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        ParcelFileDescriptor fd1 = mock(ParcelFileDescriptor.class);
        ParcelFileDescriptor fd2 = mock(ParcelFileDescriptor.class);
        mWrapper.respondToRttRequest(mMockCall, fd1, fd2);
        verify(mMockConnectionService).respondToRttUpgradeRequest(eq(CALL_ID), eq(fd1),
                eq(fd2), any());
    }

    @Test
    public void testRespondToRttRequest_RemoteException() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService)
                .respondToRttUpgradeRequest(anyString(), any(), any(), any());
        mWrapper.respondToRttRequest(mMockCall, mock(ParcelFileDescriptor.class),
                mock(ParcelFileDescriptor.class));
    }

    // =============================================================================================
    // Call Events and State Updates
    // =============================================================================================

    @Test
    public void testOnCallAudioStateChanged() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        CallAudioState state = new CallAudioState(false, CallAudioState.ROUTE_EARPIECE,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER);
        mWrapper.onCallAudioStateChanged(mMockCall, state);
        verify(mMockConnectionService).onCallAudioStateChanged(eq(CALL_ID), eq(state), any());
    }

    @Test
    public void testOnCallEndpointChanged() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        CallEndpoint endpoint = mock(CallEndpoint.class);
        mWrapper.onCallEndpointChanged(mMockCall, endpoint);
        verify(mMockConnectionService).onCallEndpointChanged(eq(CALL_ID), eq(endpoint), any());
    }

    @Test
    public void testOnAvailableCallEndpointsChanged() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        java.util.Set<CallEndpoint> endpoints = new java.util.HashSet<>();
        mWrapper.onAvailableCallEndpointsChanged(mMockCall, endpoints);
        verify(mMockConnectionService).onAvailableCallEndpointsChanged(eq(CALL_ID), any(), any());
    }

    @Test
    public void testOnMuteStateChanged() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.onMuteStateChanged(mMockCall, true);
        verify(mMockConnectionService).onMuteStateChanged(eq(CALL_ID), eq(true), any());
    }

    @Test
    public void testOnTrackedByNonUiService() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.onTrackedByNonUiService(mMockCall, true);
        verify(mMockConnectionService).onTrackedByNonUiService(eq(CALL_ID), eq(true), any());
    }

    @Test
    public void testPullExternalCall() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.pullExternalCall(mMockCall);
        verify(mMockConnectionService).pullExternalCall(eq(CALL_ID), any());
    }

    @Test
    public void testSendCallEvent() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.sendCallEvent(mMockCall, "event", null);
        verify(mMockConnectionService).sendCallEvent(eq(CALL_ID), eq("event"), any(), any());
    }

    @Test
    public void testOnExtrasChanged() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        Bundle extras = new Bundle();
        mWrapper.onExtrasChanged(mMockCall, extras);
        verify(mMockConnectionService).onExtrasChanged(eq(CALL_ID), eq(extras), any());
    }

    @Test
    public void testOnCallFilteringCompleted_PermissionGranted() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        when(mPackageManager.checkPermission(eq(android.Manifest.permission.READ_CONTACTS),
                anyString())).thenReturn(PackageManager.PERMISSION_GRANTED);

        mWrapper.onCallFilteringCompleted(mMockCall, null);
        verify(mMockConnectionService).onCallFilteringCompleted(eq(CALL_ID), any(), any());
    }

    @Test
    public void testOnCallFilteringCompleted_PermissionDenied() throws RemoteException {
        when(mPackageManager.checkPermission(eq(android.Manifest.permission.READ_CONTACTS),
                anyString())).thenReturn(PackageManager.PERMISSION_DENIED);

        mWrapper.onCallFilteringCompleted(mMockCall, null);
        verify(mMockConnectionService, never()).onCallFilteringCompleted(anyString(), any(), any());
    }

    // =============================================================================================
    // Asynchronous Binding and Connection Creation
    // =============================================================================================

    @Test
    public void testConnectionServiceFocusLost_BindSuccess() throws RemoteException {
        mWrapper.connectionServiceFocusLost();
        triggerBindSuccess(COMPONENT_NAME, mMockBinder);
        verify(mMockConnectionService).connectionServiceFocusLost(any());
    }

    @Test
    public void testConnectionServiceFocusGained_BindSuccess() throws RemoteException {
        mWrapper.connectionServiceFocusGained();
        triggerBindSuccess(COMPONENT_NAME, mMockBinder);
        verify(mMockConnectionService).connectionServiceFocusGained(any());
    }

    @Test
    public void testCreateConnection_Success() throws RemoteException {
        CreateConnectionResponse response = mock(CreateConnectionResponse.class);
        GatewayInfo gatewayInfo = mock(GatewayInfo.class);
        when(gatewayInfo.getGatewayProviderPackageName()).thenReturn("testGatewayPkg");
        when(gatewayInfo.getOriginalAddress()).thenReturn(Uri.parse("tel:123"));
        when(mMockCall.getGatewayInfo()).thenReturn(gatewayInfo);
        Bundle callIntentExtras = new Bundle();
        callIntentExtras.putString("TEST_EXTRA_KEY", "TEST_EXTRA_VALUE");
        when(mMockCall.getIntentExtras()).thenReturn(callIntentExtras);
        mWrapper.createConnection(mMockCall, response);
        triggerBindSuccess();
        verify(mMockConnectionService).createConnection(any(), eq(CALL_ID), any(),
                anyBoolean(), anyBoolean(), any());
    }

    @Test
    public void testCreateConnection_MissingConnectionId() {
        String callId = "MISSING_ID";
        Call call = mock(Call.class);
        when(call.getId()).thenReturn(callId);
        when(call.getConnectionId()).thenReturn(callId);
        CreateConnectionResponse response = mock(CreateConnectionResponse.class);
        mWrapper.createConnection(call, response);
        triggerBindSuccess();
        verify(response).handleCreateConnectionFailure(any());
    }

    @Test
    public void testCreateConnection_BindFailure() {
        CreateConnectionResponse response = mock(CreateConnectionResponse.class);
        mWrapper.createConnection(mMockCall, response);
        triggerBindFailure();
        verify(response).handleCreateConnectionFailure(any());
    }

    @Test
    public void testCreateConnection_Timeout() {
        CreateConnectionResponse response = mock(CreateConnectionResponse.class);
        ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        doReturn(mock(ScheduledFuture.class)).when(mockExecutor).schedule(runnableCaptor.capture(),
                anyLong(), any(TimeUnit.class));
        when(mockExecutor.isShutdown()).thenReturn(false);
        mWrapper.setScheduledExecutorService(mockExecutor);

        mWrapper.createConnection(mMockCall, response);
        triggerBindSuccess();
        when(mMockCall.isCreateConnectionComplete()).thenReturn(false);
        runnableCaptor.getValue().run();
        verify(response).handleCreateConnectionFailure(any());
    }

    @Test
    public void testCreateConnection_EmergencyDeep() throws Exception {
        CreateConnectionResponse response = mock(CreateConnectionResponse.class);
        when(mMockCall.isEmergencyCall()).thenReturn(true);
        com.android.server.telecom.EmergencyCallHelper mockHelper =
                mock(com.android.server.telecom.EmergencyCallHelper.class);
        when(mMockCallsManager.getEmergencyCallHelper()).thenReturn(mockHelper);
        when(mockHelper.getLastEmergencyCallTimeMillis()).thenReturn(12345L);
        when(mMockCall.isIncoming()).thenReturn(true);
        doReturn(1234).when(mPackageManager).getPackageUid(anyString(), anyInt());
        doReturn(1234).when(mPackageManager).getPackageUid(anyString(),
                any(PackageManager.PackageInfoFlags.class));

        mWrapper.createConnection(mMockCall, response);
        triggerBindSuccess(COMPONENT_NAME, mMockBinder);

        ArgumentCaptor<ConnectionRequest> requestCaptor =
                ArgumentCaptor.forClass(ConnectionRequest.class);
        verify(mMockConnectionService).createConnection(any(), any(), requestCaptor.capture(),
                anyBoolean(), anyBoolean(), any());
        assertEquals(12345L, requestCaptor.getValue().getExtras()
                .getLong(android.telecom.Call.EXTRA_LAST_EMERGENCY_CALLBACK_TIME_MILLIS));
    }

    @Test
    public void testCreateConnection_Handover() throws RemoteException {
        CreateConnectionResponse response = mock(CreateConnectionResponse.class);
        Call mockSourceCall = mock(Call.class);
        when(mMockCall.isIncoming()).thenReturn(true);
        when(mMockCall.getHandoverSourceCall()).thenReturn(mockSourceCall);
        when(mockSourceCall.getTargetPhoneAccount()).thenReturn(mock(PhoneAccountHandle.class));
        when(mEmergencyCallHelper.getLastEmergencyCallTimeMillis()).thenReturn(10L);
        mWrapper.createConnection(mMockCall, response);
        triggerBindSuccess(COMPONENT_NAME, mMockBinder);
        verify(mMockConnectionService).createConnection(any(), eq(CALL_ID), any(), anyBoolean(),
                anyBoolean(), any());
    }

    @Test
    public void testCreateConnection_Emergency() throws Exception {
        CreateConnectionResponse response = mock(CreateConnectionResponse.class);
        when(mMockCall.isEmergencyCall()).thenReturn(true);
        doReturn(1234).when(mPackageManager).getPackageUid(anyString(), anyInt());
        doReturn(1234).when(mPackageManager).getPackageUid(anyString(),
                any(PackageManager.PackageInfoFlags.class));

        mWrapper.createConnection(mMockCall, response);
        triggerBindSuccess(COMPONENT_NAME, mMockBinder);
        verify(mMockConnectionService).createConnection(any(), eq(CALL_ID), any(),
                anyBoolean(), anyBoolean(), any());
    }

    @Test
    public void testCreateConnection_ComplexFlags() throws RemoteException {
        CreateConnectionResponse response = mock(CreateConnectionResponse.class);
        when(mMockCall.shouldAttachToExistingConnection()).thenReturn(true);
        when(mMockCall.isUnknown()).thenReturn(true);

        mWrapper.createConnection(mMockCall, response);
        triggerBindSuccess(COMPONENT_NAME, mMockBinder);
        verify(mMockConnectionService).createConnection(any(), eq(CALL_ID), any(), eq(true),
                eq(true), any());
    }

    @Test
    public void testCreateConnection_ScheduledExecutorNull() throws RemoteException {
        CreateConnectionResponse response = mock(CreateConnectionResponse.class);
        mWrapper.setScheduledExecutorService(null);
        mWrapper.createConnection(mMockCall, response);
    }

    @Test
    public void testCreateConnection_ScheduledExecutorShutdown() throws RemoteException {
        CreateConnectionResponse response = mock(CreateConnectionResponse.class);
        java.util.concurrent.ScheduledExecutorService mockExecutor =
                mock(java.util.concurrent.ScheduledExecutorService.class);
        when(mockExecutor.isShutdown()).thenReturn(true);
        mWrapper.setScheduledExecutorService(mockExecutor);
        mWrapper.createConnection(mMockCall, response);
        verify(mockExecutor, never()).schedule(any(Runnable.class), anyLong(), any());
    }

    @Test
    public void testCreateConnection_RejectedExecution() throws RemoteException {
        CreateConnectionResponse response = mock(CreateConnectionResponse.class);
        java.util.concurrent.ScheduledExecutorService mockExecutor =
                mock(java.util.concurrent.ScheduledExecutorService.class);
        when(mockExecutor.isShutdown()).thenReturn(false);
        when(mockExecutor.schedule(any(Runnable.class), anyLong(), any()))
                .thenThrow(new java.util.concurrent.RejectedExecutionException());
        mWrapper.setScheduledExecutorService(mockExecutor);
        mWrapper.createConnection(mMockCall, response);
    }

    @Test
    public void testCreateConnection_ServiceInterfaceNull() throws RemoteException {
        CreateConnectionResponse response = mock(CreateConnectionResponse.class);
        // Ensure no leftover pending responses
        mWrapper.removeCall(mMockCall);
        mWrapper.addCall(mMockCall);

        mWrapper.createConnection(mMockCall, response);
        triggerBindSuccess(COMPONENT_NAME, mMockBinder);

        // Re-simulate being unbound but with pending response
        mWrapper.setServiceInterfaceForTesting(null);
        mWrapper.setServiceValid(true);
        mWrapper.createConnection(mMockCall, response);
        // Use a binder that asInterface returns null for
        triggerBindSuccess(COMPONENT_NAME, mock(IBinder.class));

        verify(response).handleCreateConnectionFailure(any());
    }

    @Test
    public void testCreateConnection_RemoteException() throws RemoteException {
        CreateConnectionResponse response = mock(CreateConnectionResponse.class);
        doThrow(new RemoteException()).when(mMockConnectionService).createConnection(any(), any(),
                any(), anyBoolean(), anyBoolean(), any());
        mWrapper.createConnection(mMockCall, response);
        triggerBindSuccess(COMPONENT_NAME, mMockBinder);
        verify(response).handleCreateConnectionFailure(any());
    }

    @Test
    public void testCreateConference_Success() throws RemoteException {
        CreateConnectionResponse response = mock(CreateConnectionResponse.class);
        when(mMockCall.getTargetPhoneAccount()).thenReturn(mock(PhoneAccountHandle.class));
        when(mMockCall.getHandle()).thenReturn(Uri.parse("tel:123"));

        mWrapper.createConference(mMockCall, response);
        triggerBindSuccess(COMPONENT_NAME, mMockBinder);
        verify(mMockConnectionService).createConference(any(), eq(CALL_ID), any(),
                anyBoolean(), anyBoolean(), any());
    }

    @Test
    public void testCreateConference_BindFailure() throws RemoteException {
        CreateConnectionResponse response = mock(CreateConnectionResponse.class);
        mWrapper.createConference(mMockCall, response);
        triggerBindFailure();
        verify(response).handleCreateConferenceFailure(any());
    }

    @Test
    public void testCreateConferenceFailed_Success() throws RemoteException {
        when(mMockCall.getTargetPhoneAccount()).thenReturn(mock(PhoneAccountHandle.class));
        when(mMockCall.getHandle()).thenReturn(Uri.parse("tel:123"));

        mWrapper.createConferenceFailed(mMockCall);
        triggerBindSuccess(COMPONENT_NAME, mMockBinder);
        verify(mMockConnectionService).createConferenceFailed(any(), eq(CALL_ID), any(),
                anyBoolean(), any());
    }

    @Test
    public void testCreateConnectionFailed_BindFailure() throws RemoteException {
        mWrapper.createConferenceFailed(mMockCall);
        triggerBindFailure();
    }

    @Test
    public void testCreateConnectionFailed_Success() throws RemoteException {
        when(mMockCall.getTargetPhoneAccount()).thenReturn(mock(PhoneAccountHandle.class));
        when(mMockCall.getHandle()).thenReturn(Uri.parse("tel:123"));
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);

        mWrapper.createConnectionFailed(mMockCall);
        triggerBindSuccess();

        verify(mMockConnectionService).createConnectionFailed(any(), eq(CALL_ID), any(),
                anyBoolean(), any());
        verify(mMockCall).setDisconnectCause(any(DisconnectCause.class));
        verify(mMockCall).disconnect();
    }

    @Test
    public void testCreateConnectionFailed_RemoteException() throws RemoteException {
        when(mMockCall.getTargetPhoneAccount()).thenReturn(mock(PhoneAccountHandle.class));
        when(mMockCall.getHandle()).thenReturn(Uri.parse("tel:123"));
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService).createConnectionFailed(
                any(), anyString(), any(), anyBoolean(), any());

        mWrapper.createConnectionFailed(mMockCall);
        triggerBindSuccess();

        verify(mMockConnectionService).createConnectionFailed(any(), eq(CALL_ID), any(),
                anyBoolean(), any());
    }

    @Test
    public void testCreateConnectionFailed_onFailure() {
        mWrapper.createConnectionFailed(mMockCall);
        triggerBindFailure();
    }

    @Test
    public void testHandoverFailed_Success() throws RemoteException {
        when(mMockCall.getTargetPhoneAccount()).thenReturn(mock(PhoneAccountHandle.class));
        when(mMockCall.getHandle()).thenReturn(Uri.parse("tel:123"));
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        int reason = 1;

        mWrapper.handoverFailed(mMockCall, reason);
        triggerBindSuccess();

        verify(mMockConnectionService).handoverFailed(eq(CALL_ID), any(), eq(reason), any());
    }

    @Test
    public void testHandoverFailed_RemoteException() throws RemoteException {
        when(mMockCall.getTargetPhoneAccount()).thenReturn(mock(PhoneAccountHandle.class));
        when(mMockCall.getHandle()).thenReturn(Uri.parse("tel:123"));
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService).handoverFailed(
                anyString(), any(), anyInt(), any());

        mWrapper.handoverFailed(mMockCall, 1);
        triggerBindSuccess();

        verify(mMockConnectionService).handoverFailed(eq(CALL_ID), any(), anyInt(), any());
    }

    @Test
    public void testHandoverFailed_onFailure() {
        mWrapper.handoverFailed(mMockCall, 1);
        triggerBindFailure();
    }

    @Test
    public void testHandoverComplete_Success() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);

        mWrapper.handoverComplete(mMockCall);
        triggerBindSuccess();

        verify(mMockConnectionService).handoverComplete(eq(CALL_ID), any());
    }

    @Test
    public void testHandoverComplete_RemoteException() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        doThrow(new RemoteException()).when(mMockConnectionService).handoverComplete(
                anyString(), any());

        mWrapper.handoverComplete(mMockCall);
        triggerBindSuccess();

        verify(mMockConnectionService).handoverComplete(eq(CALL_ID), any());
    }

    @Test
    public void testHandoverComplete_onFailure() {
        mWrapper.handoverComplete(mMockCall);
        triggerBindFailure();
    }

    @Test
    public void testHandleConnectionServiceDeath_NullService() throws RemoteException {
        ConnectionServiceFocusManager.ConnectionServiceFocusListener connSvrFocusListener =
                mock(ConnectionServiceFocusManager.ConnectionServiceFocusListener.class);
        ScheduledExecutorService executorService = mock(ScheduledExecutorService.class);
        mWrapper.setConnectionServiceFocusListener(connSvrFocusListener);
        mWrapper.setScheduledExecutorService(executorService);
        mWrapper.removeServiceInterface();
        verify(mMockCallsManager, never()).handleConnectionServiceDeath(mWrapper);
        verify(executorService, never()).shutdown();
        verify(connSvrFocusListener, never()).onConnectionServiceDeath(mWrapper);
    }

    @Test
    public void testHandleConnectionServiceDeath_WithPending() {
        ConnectionServiceFocusManager.ConnectionServiceFocusListener connSvrFocusListener =
                mock(ConnectionServiceFocusManager.ConnectionServiceFocusListener.class);
        CreateConnectionResponse mockResponse = mock(CreateConnectionResponse.class);
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.addPendingResponse(CALL_ID, mockResponse);
        mWrapper.setConnectionServiceFocusListener(connSvrFocusListener);
        mWrapper.removeServiceInterface();
        verify(connSvrFocusListener).onConnectionServiceDeath(mWrapper);
        verify(mockResponse).handleCreateConnectionFailure(any(DisconnectCause.class));
    }

    // =============================================================================================
    // Adapter Call State Reporting
    // =============================================================================================

    @Test
    public void testAdapterSetActive() throws RemoteException {
        mAdapter.setActive(CALL_ID, null);
        verify(mMockCallsManager).markCallAsActive(mMockCall);
    }

    @Test
    public void testAdapterSetActive_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCallsManager).markCallAsActive(any(Call.class));
        try {
            mAdapter.setActive(CALL_ID, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterSetDialing() throws RemoteException {
        mAdapter.setDialing(CALL_ID, null);
        verify(mMockCallsManager).markCallAsDialing(mMockCall);
    }

    @Test
    public void testAdapterSetDialing_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCallsManager).markCallAsDialing(any(Call.class));
        try {
            mAdapter.setDialing(CALL_ID, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterSetRinging() throws RemoteException {
        mAdapter.setRinging(CALL_ID, null);
        verify(mMockCallsManager).markCallAsRinging(mMockCall);
    }

    @Test
    public void testAdapterSetRinging_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCallsManager).markCallAsRinging(any(Call.class));
        try {
            mAdapter.setRinging(CALL_ID, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterSetOnHold() throws RemoteException {
        mAdapter.setOnHold(CALL_ID, null);
        verify(mMockCallsManager).markCallAsOnHold(mMockCall);
    }

    @Test
    public void testAdapterSetOnHold_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCallsManager).markCallAsOnHold(any(Call.class));
        try {
            mAdapter.setOnHold(CALL_ID, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterSetDisconnected() throws RemoteException {
        mAdapter.setDisconnected(CALL_ID, new DisconnectCause(DisconnectCause.LOCAL), null);
        verify(mMockCallsManager).markCallAsDisconnected(eq(mMockCall),
                any(DisconnectCause.class));
    }

    @Test
    public void testAdapterSetDisconnected_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCallsManager)
                .markCallAsDisconnected(any(Call.class), any(DisconnectCause.class));
        try {
            mAdapter.setDisconnected(CALL_ID, new DisconnectCause(DisconnectCause.LOCAL), null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterSetConnectionProperties() throws RemoteException {
        mAdapter.setConnectionProperties(CALL_ID, Connection.PROPERTY_IS_RTT, null);
        verify(mMockCall).setConnectionProperties(Connection.PROPERTY_IS_RTT);
    }

    @Test
    public void testAdapterSetConnectionProperties_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).setConnectionProperties(anyInt());
        try {
            mAdapter.setConnectionProperties(CALL_ID, 1, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterResetConnectionTime() throws RemoteException {
        mAdapter.resetConnectionTime(CALL_ID, null);
        verify(mMockCallsManager).resetConnectionTime(mMockCall);
    }

    @Test
    public void testAdapterSetPulling() throws RemoteException {
        mAdapter.setPulling(CALL_ID, null);
        verify(mMockCallsManager).markCallAsPulling(mMockCall);
    }

    @Test
    public void testAdapterSetPulling_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCallsManager).markCallAsPulling(any(Call.class));
        try {
            mAdapter.setPulling(CALL_ID, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterSetAudioProcessing() throws RemoteException {
        when(mMockCall.isExternalCall()).thenReturn(true);
        mAdapter.setAudioProcessing(CALL_ID, null, 1);
        verify(mMockCallsManager).markCallAsAudioProcessing(mMockCall, 1);
    }

    @Test
    public void testAdapterSetAudioProcessing_Throwable() throws RemoteException {
        when(mMockCall.isExternalCall()).thenReturn(true);
        doThrow(new RuntimeException()).when(mMockCallsManager)
                .markCallAsAudioProcessing(any(Call.class), anyInt());
        try {
            mAdapter.setAudioProcessing(CALL_ID, null, 1);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterSetAudioProcessingNotExternal() throws RemoteException {
        when(mMockCall.isExternalCall()).thenReturn(false);
        try {
            mAdapter.setAudioProcessing(CALL_ID, null, 1);
            fail("Should've thrown an IllegalStateException");
        } catch (IllegalStateException e) {
            // Success
        }
    }

    @Test
    public void testAdapterSetSimulatedRinging() throws RemoteException {
        when(mMockCall.isExternalCall()).thenReturn(true);
        mAdapter.setSimulatedRinging(CALL_ID, null);
        verify(mMockCallsManager).markCallAsSimulatedRinging(mMockCall);
    }

    @Test
    public void testAdapterSetSimulatedRinging_Throwable() throws RemoteException {
        when(mMockCall.isExternalCall()).thenReturn(true);
        doThrow(new RuntimeException()).when(mMockCallsManager)
                .markCallAsSimulatedRinging(any(Call.class));
        try {
            mAdapter.setSimulatedRinging(CALL_ID, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterSetSimulatedRinging_NotExternal() throws RemoteException {
        when(mMockCall.isExternalCall()).thenReturn(false);
        mAdapter.setSimulatedRinging(CALL_ID, null);
        verify(mMockCallsManager, never()).markCallAsSimulatedRinging(mMockCall);
    }

    @Test
    public void testAdapterSetRingbackRequested() throws RemoteException {
        mAdapter.setRingbackRequested(CALL_ID, true, null);
        verify(mMockCall).setRingbackRequested(true);
    }

    @Test
    public void testAdapterSetRingbackRequested_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).setRingbackRequested(anyBoolean());
        try {
            mAdapter.setRingbackRequested(CALL_ID, true, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterRemoveCall() throws RemoteException {
        when(mMockCall.isAlive()).thenReturn(true);
        mAdapter.removeCall(CALL_ID, null);
        verify(mMockCallsManager).markCallAsDisconnected(eq(mMockCall), any());
        verify(mMockCallsManager).markCallAsRemoved(mMockCall);
    }

    @Test
    public void testAdapterRemoveCall_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCallsManager).markCallAsRemoved(any(Call.class));
        try {
            mAdapter.removeCall(CALL_ID, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterRemoveCall_NotAlive() throws RemoteException {
        when(mMockCall.isAlive()).thenReturn(false);
        mAdapter.removeCall(CALL_ID, null);
        verify(mMockCallsManager, never()).markCallAsDisconnected(eq(mMockCall), any());
        verify(mMockCallsManager).markCallAsRemoved(mMockCall);
    }

    @Test
    public void testAdapterSetIsConferenced() throws RemoteException {
        Call confCall = mock(Call.class);
        when(confCall.getId()).thenReturn(CONF_CALL_ID);
        when(confCall.getConnectionId()).thenReturn(CONF_CALL_ID);
        mWrapper.addCall(confCall);

        mAdapter.setIsConferenced(CALL_ID, CONF_CALL_ID, null);
        verify(mMockCall).setParentAndChildCall(confCall);

        mAdapter.setIsConferenced(CALL_ID, null, null);
        verify(mMockCall).setParentAndChildCall(null);
    }

    @Test
    public void testAdapterSetIsConferenced_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).setParentAndChildCall(any());
        Call confCall = mock(Call.class);
        when(confCall.getId()).thenReturn(CONF_CALL_ID);
        when(confCall.getConnectionId()).thenReturn(CONF_CALL_ID);
        mWrapper.addCall(confCall);
        try {
            mAdapter.setIsConferenced(CALL_ID, CONF_CALL_ID, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterSetVideoState() throws RemoteException {
        mAdapter.setVideoState(CALL_ID, 1, null);
        verify(mMockCall).setVideoState(1);
    }

    @Test
    public void testAdapterSetVideoState_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).setVideoState(anyInt());
        try {
            mAdapter.setVideoState(CALL_ID, 1, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterSetVideoProvider() throws RemoteException {
        IVideoProvider videoProvider = mock(IVideoProvider.class);
        mAdapter.setVideoProvider(CALL_ID, videoProvider, null);
        verify(mMockCall).setVideoProvider(any());
    }

    @Test
    public void testAdapterSetVideoProvider_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).setVideoProvider(any());
        try {
            mAdapter.setVideoProvider(CALL_ID, mock(IVideoProvider.class), null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterSetStatusHints() throws RemoteException {
        StatusHints hints = mock(StatusHints.class);
        mAdapter.setStatusHints(CALL_ID, hints, null);
        verify(mMockCall).setStatusHints(hints);
    }

    @Test
    public void testAdapterSetStatusHints_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).setStatusHints(any());
        try {
            mAdapter.setStatusHints(CALL_ID, mock(StatusHints.class), null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterPutExtras() throws RemoteException {
        Bundle extras = new Bundle();
        mAdapter.putExtras(CALL_ID, extras, null);
        verify(mMockCall).putConnectionServiceExtras(extras);
    }

    @Test
    public void testAdapterPutExtras_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).putConnectionServiceExtras(any());
        try {
            mAdapter.putExtras(CALL_ID, new Bundle(), null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterRemoveExtras() throws RemoteException {
        List<String> keys = List.of("TEST_EXTRA_KEY");
        mAdapter.removeExtras(CALL_ID, keys, null);
        verify(mMockCall).removeExtras(anyInt(), eq(keys));
    }

    // =============================================================================================
    // Adapter Metadata and Advanced Features
    // =============================================================================================

    @Test
    public void testAdapterRemoveExtras_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).removeExtras(anyInt(), anyList());
        try {
            mAdapter.removeExtras(CALL_ID, new ArrayList<>(), null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterSetAddress() throws RemoteException {
        Uri address = Uri.parse("tel:5551212");
        mAdapter.setAddress(CALL_ID, address, 1, null);
        verify(mMockCall).setHandle(address, 1);
    }

    @Test
    public void testAdapterSetAddress_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).setHandle(any(), anyInt());
        try {
            mAdapter.setAddress(CALL_ID, Uri.parse("tel:123"), 1, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterSetCallerDisplayName() throws RemoteException {
        mAdapter.setCallerDisplayName(CALL_ID, "name", 1, null);
        verify(mMockCall).setCallerDisplayName("name", 1);
    }

    @Test
    public void testAdapterSetCallerDisplayName_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).setCallerDisplayName(anyString(), anyInt());
        try {
            mAdapter.setCallerDisplayName(CALL_ID, "name", 1, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterSetConnectionCapabilities() throws RemoteException {
        mAdapter.setConnectionCapabilities(CALL_ID, 123, null);
        verify(mMockCall).setConnectionCapabilities(123);
    }

    @Test
    public void testAdapterSetConnectionCapabilities_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).setConnectionCapabilities(anyInt());
        try {
            mAdapter.setConnectionCapabilities(CALL_ID, 1, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterSetConferenceMergeFailed() throws RemoteException {
        mAdapter.setConferenceMergeFailed(CALL_ID, null);
        verify(mMockCall).onConnectionEvent(Connection.EVENT_CALL_MERGE_FAILED, null);
    }

    @Test
    public void testAdapterSetConferenceMergeFailed_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).onConnectionEvent(anyString(), any());
        try {
            mAdapter.setConferenceMergeFailed(CALL_ID, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterSetIsVoipAudioMode() throws RemoteException {
        mAdapter.setIsVoipAudioMode(CALL_ID, true, null);
        verify(mMockCall).setIsVoipAudioMode(true);
    }

    @Test
    public void testAdapterSetIsVoipAudioMode_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).setIsVoipAudioMode(anyBoolean());
        try {
            mAdapter.setIsVoipAudioMode(CALL_ID, true, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterOnPhoneAccountChanged() throws RemoteException {
        PhoneAccountHandle pah = new PhoneAccountHandle(
                new ComponentName("pkg", "cls"), "id");
        mAdapter.onPhoneAccountChanged(CALL_ID, pah, null);
        verify(mMockCall).setTargetPhoneAccount(pah);
    }

    @Test
    public void testAdapterOnPhoneAccountChanged_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).setTargetPhoneAccount(any());
        try {
            mAdapter.onPhoneAccountChanged(CALL_ID, mock(PhoneAccountHandle.class), null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterOnPostDialWait() throws RemoteException {
        mAdapter.onPostDialWait(CALL_ID, "123", null);
        verify(mMockCall).onPostDialWait("123");
    }

    @Test
    public void testAdapterOnPostDialWait_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).onPostDialWait(anyString());
        try {
            mAdapter.onPostDialWait(CALL_ID, "remaining", null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterOnPostDialChar() throws RemoteException {
        mAdapter.onPostDialChar(CALL_ID, 'a', null);
        verify(mMockCall).onPostDialChar('a');
    }

    @Test
    public void testAdapterOnPostDialChar_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).onPostDialChar(anyChar());
        try {
            mAdapter.onPostDialChar(CALL_ID, 'a', null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterSetCallDirection() throws RemoteException {
        when(mContext.checkCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        mAdapter.setCallDirection(CALL_ID, 1, null);
        verify(mMockCall).setCallDirection(anyInt());
    }

    @Test
    public void testAdapterSetCallDirection_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).setCallDirection(anyInt());
        try {
            mAdapter.setCallDirection(CALL_ID, 1, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterSetConferenceState() throws RemoteException {
        when(mContext.checkCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        mAdapter.setConferenceState(CALL_ID, true, null);
        verify(mMockCall).setConferenceState(true);
    }

    @Test
    public void testAdapterSetConferenceState_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).setConferenceState(anyBoolean());
        try {
            mAdapter.setConferenceState(CALL_ID, true, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterOnRttInitiationSuccess() throws RemoteException {
        mAdapter.onRttInitiationSuccess(CALL_ID, null);
    }

    @Test
    public void testAdapterOnRttInitiationFailure() throws RemoteException {
        mAdapter.onRttInitiationFailure(CALL_ID, 1, null);
        verify(mMockCall).onRttConnectionFailure(1);
    }

    @Test
    public void testAdapterOnRttInitiationFailure_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).onRttConnectionFailure(anyInt());
        try {
            mAdapter.onRttInitiationFailure(CALL_ID, 1, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterOnRemoteRttRequest() throws RemoteException {
        mAdapter.onRemoteRttRequest(CALL_ID, null);
        verify(mMockCall).onRemoteRttRequest();
    }

    @Test
    public void testAdapterOnRemoteRttRequest_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).onRemoteRttRequest();
        try {
            mAdapter.onRemoteRttRequest(CALL_ID, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterOnRttSessionRemotelyTerminated() throws RemoteException {
        mAdapter.onRttSessionRemotelyTerminated(CALL_ID, null);
    }

    @Test
    public void testAdapterOnConnectionEvent() throws RemoteException {
        Bundle extras = new Bundle();
        mAdapter.onConnectionEvent(CALL_ID, null, extras, null);
        verify(mMockCall).onConnectionEvent(null, extras);
    }

    @Test
    public void testAdapterOnConnectionEvent_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).onConnectionEvent(anyString(), any());
        try {
            mAdapter.onConnectionEvent(CALL_ID, "event", new Bundle(), null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterOnConnectionServiceFocusReleased() throws RemoteException {
        ConnectionServiceFocusManager.ConnectionServiceFocusListener listener =
                mock(ConnectionServiceFocusManager.ConnectionServiceFocusListener.class);
        mWrapper.setConnectionServiceFocusListener(listener);
        mAdapter.onConnectionServiceFocusReleased(null);
        verify(listener).onConnectionServiceReleased(mWrapper);
    }

    @Test
    public void testAdapterOnConnectionServiceFocusReleased_Throwable() throws RemoteException {
        ConnectionServiceFocusManager.ConnectionServiceFocusListener connSvrFocusListener =
                mock(ConnectionServiceFocusManager.ConnectionServiceFocusListener.class);
        doThrow(new RuntimeException()).when(connSvrFocusListener)
                .onConnectionServiceReleased(any());
        mWrapper.setConnectionServiceFocusListener(connSvrFocusListener);
        try {
            mAdapter.onConnectionServiceFocusReleased(null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterQueryLocation_Success() throws Exception {
        ResultReceiver callback = mock(ResultReceiver.class);
        when(mMockCall.isEmergencyCall()).thenReturn(true);
        Context userContext = mock(Context.class);
        when(mContext.createContextAsUser(any(UserHandle.class), anyInt()))
                .thenReturn(userContext);
        when(userContext.getPackageManager()).thenReturn(mPackageManager);
        doReturn(Binder.getCallingUid()).when(mPackageManager)
                .getPackageUid(anyString(), any(PackageManager.PackageInfoFlags.class));

        mAdapter.queryLocation(CALL_ID, 1000L, "gps", callback, null);

        verify(mLocationManager).getCurrentLocation(anyString(), any(), any(), any(), any());
    }

    @Test
    public void testAdapterQueryLocation_Throwable() throws RemoteException {
        when(mContext.getSystemService(TelecomManager.class)).thenThrow(new RuntimeException());
        try {
            mAdapter.queryLocation(CALL_ID, 1000, "provider", mock(ResultReceiver.class), null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterQueryLocation_NotEmergency() throws Exception {
        ResultReceiver callback = mock(ResultReceiver.class);
        when(mMockCall.isEmergencyCall()).thenReturn(false);
        Context userContext = mock(Context.class);
        when(mContext.createContextAsUser(any(UserHandle.class), anyInt())).thenReturn(userContext);
        when(userContext.getPackageManager()).thenReturn(mPackageManager);
        doReturn(Binder.getCallingUid()).when(mPackageManager).getPackageUid(anyString(),
                any(PackageManager.PackageInfoFlags.class));

        mAdapter.queryLocation(CALL_ID, 1000L, "gps", callback, null);
        verify(mLocationManager, never())
                .getCurrentLocation(anyString(), any(), any(), any(), any());
    }

    @Test
    public void testAdapterQueryLocation_EmergencyNoPermission() throws Exception {
        ResultReceiver callback = mock(ResultReceiver.class);
        when(mMockCall.isEmergencyCall()).thenReturn(true);

        PhoneAccountHandle pah = new PhoneAccountHandle(
                new ComponentName("other", "pkg"), "id");
        when(mTelecomManager.getSimCallManager()).thenReturn(pah);

        mAdapter.queryLocation(CALL_ID, 1000L, "gps", callback, null);
        verify(mLocationManager, never())
                .getCurrentLocation(anyString(), any(), any(), any(), any());
    }

    @Test
    public void testAdapterQueryLocation_SimManagerMismatch() throws Exception {
        ResultReceiver callback = mock(ResultReceiver.class);
        when(mMockCall.isEmergencyCall()).thenReturn(true);

        PhoneAccountHandle pah = new PhoneAccountHandle(
                new ComponentName("other", "pkg"), "id");
        when(mTelecomManager.getSimCallManager()).thenReturn(pah);

        mAdapter.queryLocation(CALL_ID, 1000L, "gps", callback, null);

        verify(mLocationManager, never())
                .getCurrentLocation(anyString(), any(), any(), any(), any());
    }

    @Test
    public void testAdapterQueryRemoteConnectionServices_NotManager() throws RemoteException {
        com.android.internal.telecom.RemoteServiceCallback callback =
                mock(com.android.internal.telecom.RemoteServiceCallback.class);
        PhoneAccountHandle simHandle = new PhoneAccountHandle(
                new ComponentName("pkg", "cls"), "id");
        when(mMockPhoneAccountRegistrar.getSimPhoneAccounts(any()))
                .thenReturn(Collections.singletonList(simHandle));
        when(mMockPhoneAccountRegistrar.getSubscriptionIdForPhoneAccount(simHandle))
                .thenReturn(1);
        // Return a different manager so isCallerConnectionManager remains false
        when(mMockPhoneAccountRegistrar.getSimCallManager(eq(1), any()))
                .thenReturn(new PhoneAccountHandle(
                        new ComponentName("other", "mgr"), "id"));

        mAdapter.queryRemoteConnectionServices(callback, null, null);

        verify(callback).onResult(eq(Collections.EMPTY_LIST), eq(Collections.EMPTY_LIST));
    }

    @Test
    public void testAdapterQueryRemoteConnectionServices_Success() throws Exception {
        com.android.internal.telecom.RemoteServiceCallback callback =
                mock(com.android.internal.telecom.RemoteServiceCallback.class);

        // Setup SIM service 1
        PhoneAccountHandle simHandle1 =
                new PhoneAccountHandle(new ComponentName("pkg1", "cls1"), "id1");
        TestableConnectionServiceWrapper simService1 = spy(new TestableConnectionServiceWrapper(
                simHandle1.getComponentName(), mMockRepository, mMockPhoneAccountRegistrar,
                mMockCallsManager, mContext, new TelecomSystem.SyncRoot() {
        }, UserHandle.CURRENT,
                mMockFeatureFlags));
        simService1.setServiceValid(true);
        simService1.setServiceInterfaceForTesting(mock(IConnectionService.class));

        when(mMockPhoneAccountRegistrar.getSimPhoneAccounts(any()))
                .thenReturn(Collections.singletonList(simHandle1));
        when(mMockPhoneAccountRegistrar.getSubscriptionIdForPhoneAccount(simHandle1))
                .thenReturn(1);
        when(mMockPhoneAccountRegistrar.getSimCallManager(eq(1), any()))
                .thenReturn(new PhoneAccountHandle(COMPONENT_NAME, "id"));
        when(mMockRepository.getService(eq(simHandle1.getComponentName()), any()))
                .thenReturn(simService1);

        mAdapter.queryRemoteConnectionServices(callback, COMPONENT_NAME.getPackageName(), null);

        triggerBindSuccess(simHandle1.getComponentName(), mMockBinder);

        verify(callback).onResult(any(), any());
    }

    @Test
    public void testAdapterQueryRemoteConnectionServices_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockPhoneAccountRegistrar)
                .getSimPhoneAccounts(any(UserHandle.class));
        try {
            mAdapter.queryRemoteConnectionServices(mock(RemoteServiceCallback.class),
                    "pkg", null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterQueryRemoteConnectionServices_ServiceDied() throws Exception {
        com.android.internal.telecom.RemoteServiceCallback callback =
                mock(com.android.internal.telecom.RemoteServiceCallback.class);

        PhoneAccountHandle simHandle1 =
                new PhoneAccountHandle(new ComponentName("pkg1", "cls1"), "id1");
        TestableConnectionServiceWrapper simService1 = spy(new TestableConnectionServiceWrapper(
                simHandle1.getComponentName(), mMockRepository, mMockPhoneAccountRegistrar,
                mMockCallsManager, mContext, new TelecomSystem.SyncRoot() {
        }, UserHandle.CURRENT,
                mMockFeatureFlags));

        when(mMockPhoneAccountRegistrar.getSimPhoneAccounts(any()))
                .thenReturn(Collections.singletonList(simHandle1));
        when(mMockPhoneAccountRegistrar.getSubscriptionIdForPhoneAccount(simHandle1))
                .thenReturn(1);
        when(mMockPhoneAccountRegistrar.getSimCallManager(eq(1), any()))
                .thenReturn(new PhoneAccountHandle(COMPONENT_NAME, "id"));
        when(mMockRepository.getService(eq(simHandle1.getComponentName()), any()))
                .thenReturn(simService1);

        mAdapter.queryRemoteConnectionServices(callback, COMPONENT_NAME.getPackageName(), null);

        triggerBindFailure(simHandle1.getComponentName());

        verify(callback).onResult(eq(Collections.EMPTY_LIST), eq(Collections.EMPTY_LIST));
    }

    @Test
    public void testAdapterSetConferenceableConnections() throws RemoteException {
        List<String> ids = new ArrayList<>();
        ids.add("other_id");
        Call otherCall = mock(Call.class);
        when(otherCall.getId()).thenReturn("other_id");
        when(otherCall.getConnectionId()).thenReturn("other_id");
        mWrapper.addCall(otherCall);

        mAdapter.setConferenceableConnections(CALL_ID, ids, null);
        verify(mMockCall).setConferenceableCalls(any());
    }

    @Test
    public void testAdapterSetConferenceableConnections_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).setConferenceableCalls(anyList());
        try {
            mAdapter.setConferenceableConnections(CALL_ID, new ArrayList<>(), null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterSetConferenceableConnections_Complex() throws RemoteException {
        List<String> ids = new ArrayList<>();
        ids.add("other_id");
        ids.add(CALL_ID);

        Call otherCall = mock(Call.class);
        when(otherCall.getId()).thenReturn("other_id");
        when(otherCall.getConnectionId()).thenReturn("other_id");
        mWrapper.addCall(otherCall);

        mAdapter.setConferenceableConnections(CALL_ID, ids, null);

        verify(mMockCall).setConferenceableCalls(any());
    }

    @Test
    public void testAdapterRequestCallEndpointChange() throws RemoteException {
        CallEndpoint endpoint = mock(CallEndpoint.class);
        when(endpoint.getEndpointName()).thenReturn("name");
        ResultReceiver callback = mock(ResultReceiver.class);
        mAdapter.requestCallEndpointChange(CALL_ID, endpoint, callback, null);
        verify(mMockCallsManager).requestCallEndpointChange(anyInt(), eq(endpoint), eq(callback));
    }

    @Test
    public void testAdapterRequestCallEndpointChange_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCallsManager)
                .requestCallEndpointChange(anyInt(), any(), any());
        try {
            mAdapter.requestCallEndpointChange(CALL_ID, mock(CallEndpoint.class),
                    mock(ResultReceiver.class), null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterSetAudioRoute() throws RemoteException {
        mAdapter.setAudioRoute(CALL_ID, AudioRoute.TYPE_EARPIECE, null, null);
        verify(mMockCallsManager).setAudioRoute(anyInt(), eq(AudioRoute.TYPE_EARPIECE),
                nullable(String.class));
    }

    // =============================================================================================
    // Adapter Creation Completion
    // =============================================================================================

    @Test
    public void testAdapterSetAudioRoute_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCallsManager)
                .setAudioRoute(anyInt(), anyInt(), anyString());
        try {
            mAdapter.setAudioRoute(CALL_ID, 1, "address", null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testHandleCreateConnectionComplete_Success() throws RemoteException {
        StatusHints statusHints = mock(StatusHints.class);
        Icon icon = mock(Icon.class);
        when(statusHints.getIcon()).thenReturn(icon);
        // Explicitly set to TYPE_RESOURCE to avoid validation logic for cross-user check
        when(icon.getType()).thenReturn(Icon.TYPE_RESOURCE);
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        ParcelableConnection connection = new ParcelableConnection(
                mock(PhoneAccountHandle.class), Connection.STATE_ACTIVE, 0, 0, 0,
                Uri.parse("tel:123"), 1, "name", 1, null, 0, false, false, 100, 0,
                statusHints, null, new ArrayList<>(), null, null,
                android.telecom.Call.Details.DIRECTION_INCOMING,
                Connection.VERIFICATION_STATUS_NOT_VERIFIED);
        mAdapter.handleCreateConnectionComplete(CALL_ID, mock(ConnectionRequest.class),
                connection, null);
        verify(mMockConnectionService).createConnectionComplete(anyString(),
                any(Session.Info.class));
    }

    @Test
    public void testHandleCreateConnectionComplete_Throwable() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        ParcelableConnection connection = new ParcelableConnection(
                mock(PhoneAccountHandle.class), Connection.STATE_ACTIVE, 0, 0, 0,
                Uri.parse("tel:123"), 1, "name", 1, null, 0, false, false, 100, 0,
                null, null, new ArrayList<>(), null, null,
                android.telecom.Call.Details.DIRECTION_INCOMING,
                Connection.VERIFICATION_STATUS_NOT_VERIFIED);
        doThrow(new RuntimeException()).when(mMockConnectionService)
                .createConnectionComplete(anyString(), any());
        try {
            mAdapter.handleCreateConnectionComplete(CALL_ID, mock(ConnectionRequest.class),
                    connection, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testHandleCreateConnectionComplete_Disconnected() throws RemoteException {
        CreateConnectionResponse response = mock(CreateConnectionResponse.class);
        ParcelableConnection connection = new ParcelableConnection(
                mock(PhoneAccountHandle.class), Connection.STATE_DISCONNECTED, 0, 0, 0,
                Uri.parse("tel:123"), 1, "name", 1, null, 0, false, false, 100, 0, null,
                new DisconnectCause(DisconnectCause.REJECTED), new ArrayList<>(), null, null,
                android.telecom.Call.Details.DIRECTION_INCOMING,
                Connection.VERIFICATION_STATUS_NOT_VERIFIED);
        mWrapper.addPendingResponse(CALL_ID, response);
        mAdapter.handleCreateConnectionComplete(CALL_ID, mock(ConnectionRequest.class),
                connection, null);
        verify(mMockCall).setConnectTimeMillis(connection.getConnectTimeMillis());
        verify(mMockCall).clearPostDialDigits();
        verify(response).handleCreateConnectionFailure(any(DisconnectCause.class));
        assertFalse(mWrapper.getCallIdMapper().containsCallId(CALL_ID));
    }

    @Test
    public void testHandleCreateConnectionComplete_WithConnectTime() throws RemoteException {
        ParcelableConnection connection = new ParcelableConnection(
                mock(PhoneAccountHandle.class), Connection.STATE_DISCONNECTED, 0, 0, 0,
                Uri.parse("tel:123"), 1, "name", 1, null, 0, false, false, 500, 0, null,
                null, new ArrayList<>(), null, null,
                android.telecom.Call.Details.DIRECTION_INCOMING,
                Connection.VERIFICATION_STATUS_NOT_VERIFIED);
        mAdapter.handleCreateConnectionComplete(CALL_ID, mock(ConnectionRequest.class),
                connection, null);
        verify(mMockCall).setConnectTimeMillis(500);
    }

    @Test
    public void testAdapterHandleCreateConferenceComplete() throws RemoteException {
        StatusHints statusHints = mock(StatusHints.class);
        Icon icon = mock(Icon.class);
        when(statusHints.getIcon()).thenReturn(icon);
        // Explicitly set to TYPE_RESOURCE to avoid validation logic for cross-user check
        when(icon.getType()).thenReturn(Icon.TYPE_RESOURCE);
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        ConnectionRequest request = mock(ConnectionRequest.class);
        ParcelableConference conference = new ParcelableConference.Builder(
                mock(PhoneAccountHandle.class), Connection.STATE_ACTIVE)
                .setStatusHints(statusHints).build();
        mAdapter.handleCreateConferenceComplete(CALL_ID, request, conference, null);
        verify(mMockConnectionService).createConferenceComplete(eq(CALL_ID), any());
    }

    @Test
    public void testAdapterHandleCreateConferenceComplete_Throwable() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        ConnectionRequest request = mock(ConnectionRequest.class);
        ParcelableConference conference = new ParcelableConference.Builder(
                mock(PhoneAccountHandle.class), Connection.STATE_ACTIVE).build();
        doThrow(new RuntimeException()).when(mMockConnectionService)
                .createConferenceComplete(anyString(), any());
        try {
            mAdapter.handleCreateConferenceComplete(CALL_ID, request, conference, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterHandleCreateConferenceComplete_Disconnected() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        ConnectionRequest request = mock(ConnectionRequest.class);
        DisconnectCause cause = new DisconnectCause(DisconnectCause.ERROR);
        ParcelableConference conference = new ParcelableConference.Builder(
                mock(PhoneAccountHandle.class), Connection.STATE_DISCONNECTED)
                .setDisconnectCause(cause)
                .build();
        CreateConnectionResponse response = mock(CreateConnectionResponse.class);
        mWrapper.addPendingResponse(CALL_ID, response);

        mAdapter.handleCreateConferenceComplete(CALL_ID, request, conference, null);
        verify(mMockConnectionService).createConferenceComplete(anyString(), any());
        verify(response).handleCreateConnectionFailure(any(DisconnectCause.class));
        assertFalse(mWrapper.getCallIdMapper().containsCallId(CALL_ID));
    }

    @Test
    public void testAdapterAddConferenceCall_WithPermission() throws RemoteException {
        when(mContext.checkCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        ParcelableConference conference = new ParcelableConference.Builder(
                mock(PhoneAccountHandle.class), Connection.STATE_ACTIVE)
                .build();

        mAdapter.addConferenceCall(CONF_CALL_ID, conference, null);
        ArgumentCaptor<ParcelableConference> captor =
                ArgumentCaptor.forClass(ParcelableConference.class);
        verify(mMockCallsManager).createConferenceCall(eq(CONF_CALL_ID), any(), captor.capture());
    }

    @Test
    public void testAdapterAddConferenceCall_Throwable() throws RemoteException {
        when(mContext.checkCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        ParcelableConference conference = new ParcelableConference.Builder(
                mock(PhoneAccountHandle.class), Connection.STATE_ACTIVE)
                .build();
        doThrow(new RuntimeException()).when(mMockCallsManager)
                .createConferenceCall(anyString(), any(), any());

        try {
            mAdapter.addConferenceCall(CONF_CALL_ID, conference, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterAddConferenceCall_NoPermission() throws RemoteException {
        StatusHints statusHints = mock(StatusHints.class);
        Icon icon = mock(Icon.class);
        when(statusHints.getIcon()).thenReturn(icon);
        // Explicitly set to TYPE_RESOURCE to avoid validation logic for cross-user check
        when(icon.getType()).thenReturn(Icon.TYPE_RESOURCE);
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        when(mContext.checkCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        Bundle extras = new Bundle();
        extras.putString(Connection.EXTRA_ORIGINAL_CONNECTION_ID, "ORIGINAL_CONNECTION_ID");
        long connectTimeMillis = 0L;
        long connectElapsedTimeMillis = 1000L;
        ParcelableConference conference = new ParcelableConference.Builder(
                mock(PhoneAccountHandle.class),
                Connection.STATE_ACTIVE)
                .setConnectionCapabilities(Connection.CAPABILITY_HOLD)
                .setConnectionProperties(Connection.PROPERTY_IS_RTT)
                .setConnectionIds(List.of("CONNECTION_ID"))
                .setVideoAttributes(mock(IVideoProvider.class),
                        VideoProfile.STATE_AUDIO_ONLY)
                .setStatusHints(statusHints)
                .setExtras(new Bundle())
                .setAddress(Uri.parse("tel:123"), TelecomManager.PRESENTATION_ALLOWED)
                .setDisconnectCause(null)
                .setRingbackRequested(false)
                .setConnectTimeMillis(connectTimeMillis, connectElapsedTimeMillis)
                .build();
        Call childCall = mock(Call.class);
        when(childCall.getConnectionId()).thenReturn("CONNECTION_ID");
        when(childCall.getId()).thenReturn("CONNECTION_ID");
        mWrapper.addCall(childCall);

        mAdapter.addConferenceCall(CONF_CALL_ID, conference, null);
        ArgumentCaptor<ParcelableConference> captor =
                ArgumentCaptor.forClass(ParcelableConference.class);
        verify(mMockCallsManager).createConferenceCall(eq(CONF_CALL_ID), any(), captor.capture());
        verify(childCall).setParentAndChildCall(any(Call.class));
    }

    @Test
    public void testAdapterAddConferenceCallFromConnection() throws RemoteException {
        ParcelableConference conference = new ParcelableConference.Builder(
                mock(PhoneAccountHandle.class), Connection.STATE_ACTIVE).build();
        mAdapter.addConferenceCallFromConnection(CALL_ID, conference, null);
        verify(mMockCall).setConferenceState(true);
    }

    @Test
    public void testAdapterAddConferenceCallFromConnection_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCall).setConferenceState(anyBoolean());
        try {
            mAdapter.addConferenceCallFromConnection(CALL_ID, new ParcelableConference.Builder(
                    mock(PhoneAccountHandle.class), Connection.STATE_ACTIVE).build(), null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterAddConferenceCallFromConnection_NoPermission() throws RemoteException {
        StatusHints statusHints = mock(StatusHints.class);
        Icon icon = mock(Icon.class);
        when(statusHints.getIcon()).thenReturn(icon);
        // Explicitly set to TYPE_RESOURCE to avoid validation logic for cross-user check
        when(icon.getType()).thenReturn(Icon.TYPE_RESOURCE);
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        when(mContext.checkCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        Bundle extras = new Bundle();
        extras.putString(Connection.EXTRA_ORIGINAL_CONNECTION_ID, "ORIGINAL_CONNECTION_ID");
        long connectTimeMillis = 0L;
        long connectElapsedTimeMillis = 1000L;
        ParcelableConference conference = new ParcelableConference.Builder(
                mock(PhoneAccountHandle.class),
                Connection.STATE_ACTIVE)
                .setConnectionCapabilities(Connection.CAPABILITY_HOLD)
                .setConnectionProperties(Connection.PROPERTY_IS_RTT)
                .setConnectionIds(List.of("CONNECTION_ID"))
                .setVideoAttributes(mock(IVideoProvider.class),
                        VideoProfile.STATE_AUDIO_ONLY)
                .setStatusHints(statusHints)
                .setExtras(new Bundle())
                .setAddress(Uri.parse("tel:123"), TelecomManager.PRESENTATION_ALLOWED)
                .setDisconnectCause(null)
                .setRingbackRequested(false)
                .setConnectTimeMillis(connectTimeMillis, connectElapsedTimeMillis)
                .build();
        Call childCall = mock(Call.class);
        when(childCall.getConnectionId()).thenReturn("CONNECTION_ID");
        when(childCall.getId()).thenReturn("CONNECTION_ID");
        mWrapper.addCall(childCall);

        mAdapter.addConferenceCallFromConnection(CALL_ID, conference, null);
        verify(mMockCall).setConferenceState(true);
    }

    @Test
    public void testAdapterAddExistingConnection() throws RemoteException {
        PhoneAccountHandle ph = mock(PhoneAccountHandle.class);
        when(ph.getComponentName()).thenReturn(COMPONENT_NAME);
        ParcelableConnection connection = new ParcelableConnection(
                ph, Connection.STATE_ACTIVE, 0, 0, 0, Uri.parse("tel:123"), 1, "name", 1,
                null, 0, false, false, 0, 0, null, null, new ArrayList<>(), null, null,
                android.telecom.Call.Details.DIRECTION_INCOMING,
                Connection.VERIFICATION_STATUS_NOT_VERIFIED);

        when(mMockPhoneAccountRegistrar.getCallCapablePhoneAccounts(any(), anyBoolean(), any(),
                anyInt(), anyInt(), anyBoolean()))
                .thenReturn(Collections.singletonList(ph));

        mAdapter.addExistingConnection("new_id", connection, null);
        verify(mMockCallsManager).createCallForExistingConnection(eq("new_id"), any());
    }

    @Test
    public void testAdapterAddExistingConnection_Throwable() throws RemoteException {
        doThrow(new RuntimeException()).when(mMockCallsManager)
                .createCallForExistingConnection(anyString(), any());
        PhoneAccountHandle ph = mock(PhoneAccountHandle.class);
        when(ph.getComponentName()).thenReturn(COMPONENT_NAME);
        ParcelableConnection connection = new ParcelableConnection(
                ph, Connection.STATE_ACTIVE, 0, 0, 0, Uri.parse("tel:123"), 1, "name", 1,
                null, 0, false, false, 0, 0, null, null, new ArrayList<>(), null, null,
                android.telecom.Call.Details.DIRECTION_INCOMING,
                Connection.VERIFICATION_STATUS_NOT_VERIFIED);

        when(mMockPhoneAccountRegistrar.getCallCapablePhoneAccounts(any(), anyBoolean(), any(),
                anyInt(), anyInt(), anyBoolean()))
                .thenReturn(Collections.singletonList(ph));
        try {
            mAdapter.addExistingConnection("new_id", connection, null);
            fail("Failed to throw throwable");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testAdapterAddExistingConnection_NoAccountMatch() throws RemoteException {
        PhoneAccountHandle ph = mock(PhoneAccountHandle.class);
        when(ph.getComponentName()).thenReturn(new ComponentName("wrong", "package"));
        ParcelableConnection connection = new ParcelableConnection(
                ph, Connection.STATE_ACTIVE, 0, 0, 0, Uri.parse("tel:123"), 1, "name", 1,
                null, 0, false, false, 0, 0, null, null, new ArrayList<>(), null, null,
                android.telecom.Call.Details.DIRECTION_INCOMING,
                Connection.VERIFICATION_STATUS_NOT_VERIFIED);

        when(mMockPhoneAccountRegistrar.getCallCapablePhoneAccounts(any(), anyBoolean(), any(),
                anyInt(), anyInt(), anyBoolean()))
                .thenReturn(Collections.emptyList());

        mAdapter.addExistingConnection("new_id", connection, null);
        verify(mMockCallsManager, never()).createCallForExistingConnection(anyString(), any());
    }

    @Test
    public void testAdapterAddExistingConnection_Success() throws RemoteException {
        PhoneAccountHandle ph = new PhoneAccountHandle(COMPONENT_NAME, "id");
        ParcelableConnection connection = new ParcelableConnection(
                ph, Connection.STATE_ACTIVE, 0, 0, 0, Uri.parse("tel:123"), 1, "name", 1,
                null, 0, false, false, 0, 0, null, null, new ArrayList<>(), null, null,
                android.telecom.Call.Details.DIRECTION_INCOMING,
                Connection.VERIFICATION_STATUS_NOT_VERIFIED);

        when(mMockPhoneAccountRegistrar.getCallCapablePhoneAccounts(any(), anyBoolean(), any(),
                anyInt(), anyInt(), anyBoolean()))
                .thenReturn(Collections.singletonList(ph));

        mAdapter.addExistingConnection("new_id", connection, null);
        verify(mMockCallsManager).createCallForExistingConnection(eq("new_id"), any());
    }

    // =============================================================================================
    // Miscellaneous and Internal Logic
    // =============================================================================================

    @Test
    public void testGetQueryLocationResult() {
        Location location = new Location("gps");
        Bundle result = mWrapper.getQueryLocationResult(location);
        assertEquals(location, result.getParcelable(Connection.EXTRA_KEY_QUERY_LOCATION));
    }

    @Test
    public void testQueryCurrentLocation_Success() throws Exception {
        ResultReceiver callback = mock(ResultReceiver.class);
        ArgumentCaptor<Consumer<Location>> consumerCaptor =
                ArgumentCaptor.forClass(Consumer.class);

        doReturn(1234).when(mPackageManager).getPackageUid(anyString(), any());
        doReturn(1234).when(mPackageManager).getPackageUid(anyString(), anyInt());

        mWrapper.queryCurrentLocation(1000L, "gps", callback);

        verify(mLocationManager).getCurrentLocation(
                eq("gps"),
                any(LocationRequest.class),
                any(CancellationSignal.class),
                any(Executor.class),
                consumerCaptor.capture());

        Location mockLocation = new Location("gps");
        mockLocation.setLatitude(10.0);
        mockLocation.setLongitude(20.0);
        consumerCaptor.getValue().accept(mockLocation);

        verify(callback).send(eq(1), any(Bundle.class));
    }

    @Test
    public void testCallingUidMatchesPackageManagerRecords_Success() throws Exception {
        String packageName = "com.android.server.telecom.tests";
        // Can't mock Binder.getCallingUid(), so we just accept whatever it returns
        int currentUid = Binder.getCallingUid();
        Context userContext = mock(Context.class);
        when(mContext.createContextAsUser(any(UserHandle.class), anyInt())).thenReturn(userContext);
        when(userContext.getPackageManager()).thenReturn(mPackageManager);
        doReturn(currentUid).when(mPackageManager).getPackageUid(eq(packageName),
                any(PackageManager.PackageInfoFlags.class));

        assertTrue(mWrapper.callingUidMatchesPackageManagerRecords(packageName));
    }

    @Test
    public void testCallingUidMatchesPackageManagerRecords_Failure() throws Exception {
        String packageName = "com.android.server.telecom.tests";
        int currentUid = Binder.getCallingUid();
        doReturn(currentUid + 1).when(mPackageManager)
                .getPackageUid(eq(packageName), anyInt());

        assertFalse(mWrapper.callingUidMatchesPackageManagerRecords(packageName));
    }

    @Test
    public void testCallingUidMatchesPackageManagerRecords_NameNotFound() throws Exception {
        String packageName = "unknown.package";
        doThrow(new PackageManager.NameNotFoundException()).when(mPackageManager)
                .getPackageUid(eq(packageName), any(PackageManager.PackageInfoFlags.class));

        assertFalse(mWrapper.callingUidMatchesPackageManagerRecords(packageName));
    }

    @Test
    public void testCallingUidMatchesPackageManagerRecords_Exception() throws Exception {
        String packageName = "com.android.server.telecom.tests";
        doThrow(new RuntimeException()).when(mContext)
                .createContextAsUser(any(UserHandle.class), anyInt());

        assertFalse(mWrapper.callingUidMatchesPackageManagerRecords(packageName));
    }

    @Test
    public void testGetLastKnownCellIdWhenNoTelephony() {
        when(mTelephonyManager.getLastKnownCellIdentity())
                .thenThrow(new UnsupportedOperationException("Bee boop"));
        assertNull(mWrapper.getLastKnownCellIdentity());
    }

    @Test
    public void testGetLastKnownCellId_Success() {
        CellIdentity mockIdentity = mock(CellIdentity.class);
        when(mTelephonyManager.getLastKnownCellIdentity())
                .thenReturn(mockIdentity);

        assertEquals(mockIdentity, mWrapper.getLastKnownCellIdentity());
    }

    @Test
    public void testRemoveCall_WithDisconnectCause() {
        mWrapper.removeCall(mMockCall, new DisconnectCause(DisconnectCause.REMOTE));
        assertNull(mWrapper.getCallIdMapper().getCallId(mMockCall));
    }

    @Test
    public void testQueryLocationErrorResult() {
        Bundle extras = mWrapper.getQueryLocationErrorResult(
                QueryLocationException.ERROR_REQUEST_TIME_OUT);
        QueryLocationException exception = extras.getParcelable(
                QueryLocationException.QUERY_LOCATION_ERROR,
                QueryLocationException.class);
        assertTrue(exception.getMessage().contains("The operation was not completed on time"));

        extras = mWrapper.getQueryLocationErrorResult(
                QueryLocationException.ERROR_PREVIOUS_REQUEST_EXISTS);
        exception = extras.getParcelable(
                QueryLocationException.QUERY_LOCATION_ERROR,
                QueryLocationException.class);
        assertTrue(exception.getMessage().contains(
                "The operation was rejected due to a previous request exists"));

        extras = mWrapper.getQueryLocationErrorResult(
                QueryLocationException.ERROR_SERVICE_UNAVAILABLE);
        exception = extras.getParcelable(
                QueryLocationException.QUERY_LOCATION_ERROR,
                QueryLocationException.class);
        assertTrue(exception.getMessage().contains(
                "The operation has failed due to service is not available"));

        extras = mWrapper.getQueryLocationErrorResult(
                QueryLocationException.ERROR_UNSPECIFIED);
        exception = extras.getParcelable(
                QueryLocationException.QUERY_LOCATION_ERROR,
                QueryLocationException.class);
        assertTrue(exception.getMessage().contains(
                "The operation has failed due to an unknown or unspecified error"));
    }

    @Test
    public void testOnUsingAlternativeUi() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.onUsingAlternativeUi(mMockCall, true);
        verify(mMockConnectionService).onUsingAlternativeUi(eq(CALL_ID), eq(true), any());
    }

    @Test
    public void testPlayDtmfTone() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.playDtmfTone(mMockCall, 'a');
        verify(mMockConnectionService).playDtmfTone(eq(CALL_ID), eq('a'), any());
    }

    @Test
    public void testStopDtmfTone() throws RemoteException {
        mWrapper.setServiceValid(true);
        mWrapper.setServiceInterfaceForTesting(mMockConnectionService);
        mWrapper.stopDtmfTone(mMockCall);
        verify(mMockConnectionService).stopDtmfTone(eq(CALL_ID), any());
    }
}
