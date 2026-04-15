/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.telephony.TelephonyManager.EVENT_DISPLAY_EMERGENCY_MESSAGE;
import static android.telecom.Call.STATE_NEW;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.UserHandle;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.telecom.BluetoothCallQualityReport;
import android.telecom.Call.Details;
import android.telecom.CallAttributes;
import android.telecom.CallEndpoint;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.ParcelableConference;
import android.telecom.ParcelableConnection;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.StatusHints;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.CallQuality;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.telecom.flags.Flags;
import com.android.server.telecom.CachedAvailableEndpointsChange;
import com.android.server.telecom.CachedCallEventQueue;
import com.android.server.telecom.CachedCurrentEndpointChange;
import com.android.server.telecom.CachedMuteStateChange;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallIdMapper;
import com.android.server.telecom.CallState;
import com.android.server.telecom.CallerInfoLookupHelper;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.ClockProxy;
import com.android.server.telecom.ConnectionServiceWrapper;
import com.android.server.telecom.CreateConnectionProcessor;
import com.android.server.telecom.EmergencyCallHelper;
import com.android.server.telecom.PhoneAccountRegistrar;
import com.android.server.telecom.PhoneNumberUtilsAdapter;
import com.android.server.telecom.RespondViaSmsManager;
import com.android.server.telecom.TelecomResourceId;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.TransactionalServiceWrapper;
import com.android.server.telecom.callsequencing.voip.VoipCallMonitor;
import com.android.server.telecom.ui.ToastFactory;
import com.android.server.telecom.util.CallerInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.io.FileDescriptor;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RunWith(AndroidJUnit4.class)
public class CallTest extends TelecomTestCase {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    private static final Uri TEST_ADDRESS = Uri.parse("tel:555-1212");
    private static final ComponentName COMPONENT_NAME_1 = ComponentName
            .unflattenFromString("com.foo/.Blah");
    private static final ComponentName COMPONENT_NAME_2 = ComponentName
            .unflattenFromString("com.bar/.Blah");
    private static final PhoneAccountHandle SIM_1_HANDLE = new PhoneAccountHandle(
            COMPONENT_NAME_1, "Sim1");
    private static final PhoneAccount SIM_1_ACCOUNT = new PhoneAccount.Builder(SIM_1_HANDLE, "Sim1")
            .setCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION
                    | PhoneAccount.CAPABILITY_CALL_PROVIDER)
            .setIsEnabled(true)
            .build();
    private static final PhoneAccountHandle SIM_2_HANDLE = new PhoneAccountHandle(
            COMPONENT_NAME_2, "Sim2");
    private static final long TIMEOUT_MILLIS = 1000;

    @Mock private CallsManager mMockCallsManager;
    @Mock private CallerInfoLookupHelper mMockCallerInfoLookupHelper;
    @Mock private PhoneAccountRegistrar mMockPhoneAccountRegistrar;
    @Mock private ClockProxy mMockClockProxy;
    @Mock private ToastFactory mMockToastProxy;
    @Mock private PhoneNumberUtilsAdapter mMockPhoneNumberUtilsAdapter;
    @Mock private ConnectionServiceWrapper mMockConnectionService;
    @Mock private TransactionalServiceWrapper mMockTransactionalService;
    @Mock private Resources mMockResources;

    private final TelecomSystem.SyncRoot mLock = new TelecomSystem.SyncRoot() { };

    @Before
    public void setUp() throws Exception {
        super.setUp();
        when(mContext.getResources()).thenReturn(mMockResources);
        TelecomResourceId.setTelecomContext(mContext);
        doReturn(mMockCallerInfoLookupHelper).when(mMockCallsManager).getCallerInfoLookupHelper();
        doReturn(mMockPhoneAccountRegistrar).when(mMockCallsManager).getPhoneAccountRegistrar();
        doReturn(0L).when(mMockClockProxy).elapsedRealtime();
        doReturn(SIM_1_ACCOUNT).when(mMockPhoneAccountRegistrar).getPhoneAccountUnchecked(
                eq(SIM_1_HANDLE));
        doReturn(new ComponentName(mContext, CallTest.class))
                .when(mMockConnectionService).getComponentName();
        doReturn(UserHandle.CURRENT).when(mMockCallsManager).getCurrentUserHandle();
        when(mMockResources.getBoolean(R.bool.skip_loading_canned_text_response))
                .thenReturn(false);
        when(mMockResources.getString(R.string.skip_incoming_caller_info_account_package))
                .thenReturn("");
        when(mMockResources.getIdentifier(eq("skip_incoming_caller_info_account_package"),
                eq("string"), anyString()))
                .thenReturn(R.string.skip_incoming_caller_info_account_package);
        when(mMockResources.getIdentifier(eq("skip_loading_canned_text_response"),
                eq("bool"), anyString()))
                .thenReturn(R.bool.skip_loading_canned_text_response);

        EmergencyCallHelper helper = mock(EmergencyCallHelper.class);
        doReturn(helper).when(mMockCallsManager).getEmergencyCallHelper();
    }

    @After
    public void tearDown() throws Exception {
        TelecomResourceId.setTelecomContext(null);
        super.tearDown();
    }

    @Test
    @SmallTest
    public void testSetHasGoneActive() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        assertFalse(call.hasGoneActiveBefore());
        call.setState(CallState.ACTIVE, "");
        assertTrue(call.hasGoneActiveBefore());
        call.setState(CallState.AUDIO_PROCESSING, "");
        assertTrue(call.hasGoneActiveBefore());
    }

    /**
     * Verify that transactional calls remap the [CallAttributes#CallCapability]s to
     * Connection capabilities.
     */
    @Test
    @SmallTest
    public void testTransactionalCallCapabilityRemapping() {
        Bundle extras = new Bundle();
        Call call2 = createCall("2", Call.CALL_DIRECTION_INCOMING);
        extras.putInt(CallAttributes.CALL_CAPABILITIES_KEY,
                CallAttributes.SUPPORTS_SET_INACTIVE);
        call2.setTransactionalCapabilities(extras);
        assertTrue(call2.can(Connection.CAPABILITY_HOLD));
        assertTrue(call2.can(Connection.CAPABILITY_SUPPORT_HOLD));
    }

    /**
     * Verify Call#setVideoState will only upgrade to video if the PhoneAccount supports video
     * state capabilities
     */
    @Test
    @SmallTest
    public void testSetVideoStateForTransactionalCalls() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        TransactionalServiceWrapper tsw = Mockito.mock(TransactionalServiceWrapper.class);
        call.setIsTransactionalCall(true);
        call.setTransactionServiceWrapper(tsw);
        assertTrue(call.isTransactionalCall());
        assertNotNull(call.getTransactionServiceWrapper());
        when(mFeatureFlags.transactionalVideoState()).thenReturn(true);

        // VoIP apps using transactional APIs must register a PhoneAccount that supports
        // video calling capabilities or the video state will be defaulted to audio
        assertFalse(call.isVideoCallingSupportedByPhoneAccount());
        call.setVideoState(VideoProfile.STATE_BIDIRECTIONAL);
        assertEquals(VideoProfile.STATE_AUDIO_ONLY, call.getVideoState());

        call.setVideoCallingSupportedByPhoneAccount(true);
        assertTrue(call.isVideoCallingSupportedByPhoneAccount());

        // After the PhoneAccount signals it supports video calling, video state changes can occur
        call.setVideoState(VideoProfile.STATE_BIDIRECTIONAL);
        assertEquals(VideoProfile.STATE_BIDIRECTIONAL, call.getVideoState());
        verify(tsw, times(1)).onVideoStateChanged(call, CallAttributes.VIDEO_CALL);
    }

    /**
     * Verify all video state changes are echoed out to the TransactionalServiceWrapper
     */
    @Test
    @SmallTest
    public void testToggleTransactionalVideoState() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        TransactionalServiceWrapper tsw = Mockito.mock(TransactionalServiceWrapper.class);
        call.setIsTransactionalCall(true);
        call.setTransactionServiceWrapper(tsw);
        call.setVideoCallingSupportedByPhoneAccount(true);
        assertTrue(call.isTransactionalCall());
        assertNotNull(call.getTransactionServiceWrapper());
        assertTrue(call.isVideoCallingSupportedByPhoneAccount());
        when(mFeatureFlags.transactionalVideoState()).thenReturn(true);

        call.setVideoState(VideoProfile.STATE_BIDIRECTIONAL);
        assertEquals(VideoProfile.STATE_BIDIRECTIONAL, call.getVideoState());
        verify(tsw, times(1)).onVideoStateChanged(call, CallAttributes.VIDEO_CALL);

        call.setVideoState(VideoProfile.STATE_BIDIRECTIONAL);
        assertEquals(VideoProfile.STATE_BIDIRECTIONAL, call.getVideoState());
        verify(tsw, times(2)).onVideoStateChanged(call, CallAttributes.VIDEO_CALL);

        call.setVideoState(VideoProfile.STATE_AUDIO_ONLY);
        assertEquals(VideoProfile.STATE_AUDIO_ONLY, call.getVideoState());
        verify(tsw, times(1)).onVideoStateChanged(call, CallAttributes.AUDIO_CALL);

        call.setVideoState(VideoProfile.STATE_BIDIRECTIONAL);
        assertEquals(VideoProfile.STATE_BIDIRECTIONAL, call.getVideoState());
        verify(tsw, times(3)).onVideoStateChanged(call, CallAttributes.VIDEO_CALL);
    }

    @Test
    public void testMultipleCachedCallEvents() {
        TransactionalServiceWrapper tsw = Mockito.mock(TransactionalServiceWrapper.class);
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);

        assertNull(call.getTransactionServiceWrapper());

        String testEvent1 = "test1";
        Bundle testBundle1 = new Bundle();
        testBundle1.putInt("testKey", 1);
        call.sendCallEvent(testEvent1, testBundle1);
        assertEquals(1,
                call.getCachedServiceCallbacksCopy().get(CachedCallEventQueue.ID).size());

        String testEvent2 = "test2";
        Bundle testBundle2 = new Bundle();
        testBundle2.putInt("testKey", 2);
        call.sendCallEvent(testEvent2, testBundle2);
        assertEquals(2,
                call.getCachedServiceCallbacksCopy().get(CachedCallEventQueue.ID).size());

        String testEvent3 = "test3";
        Bundle testBundle3 = new Bundle();
        testBundle2.putInt("testKey", 3);
        call.sendCallEvent(testEvent3, testBundle3);
        assertEquals(3,
                call.getCachedServiceCallbacksCopy().get(CachedCallEventQueue.ID).size());

        verify(tsw, times(0)).sendCallEvent(any(), any(), any());
        call.setTransactionServiceWrapper(tsw);
        verify(tsw, times(1)).sendCallEvent(any(), eq(testEvent1), eq(testBundle1));
        verify(tsw, times(1)).sendCallEvent(any(), eq(testEvent2), eq(testBundle2));
        verify(tsw, times(1)).sendCallEvent(any(), eq(testEvent3), eq(testBundle3));
        assertEquals(0, call.getCachedServiceCallbacksCopy().size());
    }

    @Test
    public void testMultipleCachedMuteStateChanges() {
        TransactionalServiceWrapper tsw = Mockito.mock(TransactionalServiceWrapper.class);
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);

        assertNull(call.getTransactionServiceWrapper());

        call.cacheServiceCallback(new CachedMuteStateChange(true));
        assertEquals(1,
                call.getCachedServiceCallbacksCopy().get(CachedMuteStateChange.ID).size());

        call.cacheServiceCallback(new CachedMuteStateChange(false));
        assertEquals(1,
                call.getCachedServiceCallbacksCopy().get(CachedMuteStateChange.ID).size());

        CachedMuteStateChange currentCacheMuteState = (CachedMuteStateChange) call
                .getCachedServiceCallbacksCopy()
                .get(CachedMuteStateChange.ID)
                .getLast();

        assertFalse(currentCacheMuteState.isMuted());

        call.setTransactionServiceWrapper(tsw);
        verify(tsw, times(1)).onMuteStateChanged(any(), eq(false));
        assertEquals(0, call.getCachedServiceCallbacksCopy().size());
    }

    @Test
    public void testCacheAfterServiceSet() {
        TransactionalServiceWrapper tsw = Mockito.mock(TransactionalServiceWrapper.class);
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);

        assertNull(call.getTransactionServiceWrapper());
        call.setTransactionServiceWrapper(tsw);
        call.cacheServiceCallback(new CachedMuteStateChange(true));
        // Ensure that we do not lose events if for some reason a CachedCallback is cached after
        // the service is set
        verify(tsw, times(1)).onMuteStateChanged(any(), eq(true));
        assertEquals(0, call.getCachedServiceCallbacksCopy().size());
    }

    @Test
    public void testMultipleCachedCurrentEndpointChanges() {
        TransactionalServiceWrapper tsw = Mockito.mock(TransactionalServiceWrapper.class);
        CallEndpoint earpiece = Mockito.mock(CallEndpoint.class);
        CallEndpoint speaker = Mockito.mock(CallEndpoint.class);
        when(earpiece.getEndpointType()).thenReturn(CallEndpoint.TYPE_EARPIECE);
        when(speaker.getEndpointType()).thenReturn(CallEndpoint.TYPE_SPEAKER);

        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);

        assertNull(call.getTransactionServiceWrapper());

        call.cacheServiceCallback(new CachedCurrentEndpointChange(earpiece));
        assertEquals(1,
                call.getCachedServiceCallbacksCopy().get(CachedCurrentEndpointChange.ID).size());

        call.cacheServiceCallback(new CachedCurrentEndpointChange(speaker));
        assertEquals(1,
                call.getCachedServiceCallbacksCopy().get(CachedCurrentEndpointChange.ID).size());

        CachedCurrentEndpointChange currentEndpointChange = (CachedCurrentEndpointChange) call
                .getCachedServiceCallbacksCopy()
                .get(CachedCurrentEndpointChange.ID)
                .getLast();

        assertEquals(CallEndpoint.TYPE_SPEAKER,
                currentEndpointChange.getCurrentCallEndpoint().getEndpointType());

        call.setTransactionServiceWrapper(tsw);
        verify(tsw, times(1)).onCallEndpointChanged(any(), any());
        assertEquals(0, call.getCachedServiceCallbacksCopy().size());
    }

    @Test
    public void testMultipleCachedAvailableEndpointChanges() {
        TransactionalServiceWrapper tsw = Mockito.mock(TransactionalServiceWrapper.class);
        CallEndpoint earpiece = Mockito.mock(CallEndpoint.class);
        CallEndpoint bluetooth = Mockito.mock(CallEndpoint.class);
        Set<CallEndpoint> initialSet = Set.of(earpiece);
        Set<CallEndpoint> finalSet = Set.of(earpiece, bluetooth);
        when(earpiece.getEndpointType()).thenReturn(CallEndpoint.TYPE_EARPIECE);
        when(bluetooth.getEndpointType()).thenReturn(CallEndpoint.TYPE_BLUETOOTH);

        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);

        assertNull(call.getTransactionServiceWrapper());

        call.cacheServiceCallback(new CachedAvailableEndpointsChange(initialSet));
        assertEquals(1,
                call.getCachedServiceCallbacksCopy().get(CachedAvailableEndpointsChange.ID).size());

        call.cacheServiceCallback(new CachedAvailableEndpointsChange(finalSet));
        assertEquals(1,
                call.getCachedServiceCallbacksCopy().get(CachedAvailableEndpointsChange.ID).size());

        CachedAvailableEndpointsChange availableEndpoints = (CachedAvailableEndpointsChange) call
                .getCachedServiceCallbacksCopy()
                .get(CachedAvailableEndpointsChange.ID)
                .getLast();

        assertEquals(2, availableEndpoints.getAvailableEndpoints().size());

        call.setTransactionServiceWrapper(tsw);
        verify(tsw, times(1)).onAvailableCallEndpointsChanged(any(), any());
        assertEquals(0, call.getCachedServiceCallbacksCopy().size());
    }

    /**
     * verify that if multiple types of cached callbacks are added to the call, the call executes
     * all the callbacks once the service is set.
     */
    @Test
    public void testAllCachedCallbacks() {
        TransactionalServiceWrapper tsw = Mockito.mock(TransactionalServiceWrapper.class);
        CallEndpoint earpiece = Mockito.mock(CallEndpoint.class);
        CallEndpoint bluetooth = Mockito.mock(CallEndpoint.class);
        Set<CallEndpoint> availableEndpointsSet = Set.of(earpiece, bluetooth);
        when(earpiece.getEndpointType()).thenReturn(CallEndpoint.TYPE_EARPIECE);
        when(bluetooth.getEndpointType()).thenReturn(CallEndpoint.TYPE_BLUETOOTH);
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);

        // The call should have a null service so that callbacks are cached
        assertNull(call.getTransactionServiceWrapper());

        // add cached callbacks
        call.cacheServiceCallback(new CachedMuteStateChange(false));
        assertEquals(1, call.getCachedServiceCallbacksCopy().size());
        call.cacheServiceCallback(new CachedCurrentEndpointChange(earpiece));
        assertEquals(2, call.getCachedServiceCallbacksCopy().size());
        call.cacheServiceCallback(new CachedAvailableEndpointsChange(availableEndpointsSet));
        assertEquals(3, call.getCachedServiceCallbacksCopy().size());
        String testEvent = "testEvent";
        Bundle testBundle = new Bundle();
        call.sendCallEvent("testEvent", testBundle);

        // verify the cached callbacks are stored properly within the cache map and the values
        // can be evaluated
        CachedMuteStateChange currentCacheMuteState = (CachedMuteStateChange) call
                .getCachedServiceCallbacksCopy()
                .get(CachedMuteStateChange.ID)
                .getLast();
        CachedCurrentEndpointChange currentEndpointChange = (CachedCurrentEndpointChange) call
                .getCachedServiceCallbacksCopy()
                .get(CachedCurrentEndpointChange.ID)
                .getLast();
        CachedAvailableEndpointsChange availableEndpoints = (CachedAvailableEndpointsChange) call
                .getCachedServiceCallbacksCopy()
                .get(CachedAvailableEndpointsChange.ID)
                .getLast();
        assertFalse(currentCacheMuteState.isMuted());
        assertEquals(CallEndpoint.TYPE_EARPIECE,
                currentEndpointChange.getCurrentCallEndpoint().getEndpointType());
        assertEquals(2, availableEndpoints.getAvailableEndpoints().size());

        // set the service to a non-null value
        call.setTransactionServiceWrapper(tsw);

        // ensure the cached callbacks were executed
        verify(tsw, times(1)).onMuteStateChanged(any(), anyBoolean());
        verify(tsw, times(1)).onCallEndpointChanged(any(), any());
        verify(tsw, times(1)).onAvailableCallEndpointsChanged(any(), any());
        verify(tsw, times(1)).sendCallEvent(any(), eq(testEvent), eq(testBundle));

        // the cache map should be cleared
        assertEquals(0, call.getCachedServiceCallbacksCopy().size());
    }

    /**
     * Basic tests to check which call states are considered transitory.
     */
    @Test
    @SmallTest
    public void testIsCallStateTransitory() {
        assertTrue(CallState.isTransitoryState(CallState.NEW));
        assertTrue(CallState.isTransitoryState(CallState.CONNECTING));
        assertTrue(CallState.isTransitoryState(CallState.DISCONNECTING));
        assertTrue(CallState.isTransitoryState(CallState.ANSWERED));

        assertFalse(CallState.isTransitoryState(CallState.SELECT_PHONE_ACCOUNT));
        assertFalse(CallState.isTransitoryState(CallState.DIALING));
        assertFalse(CallState.isTransitoryState(CallState.RINGING));
        assertFalse(CallState.isTransitoryState(CallState.ACTIVE));
        assertFalse(CallState.isTransitoryState(CallState.ON_HOLD));
        assertFalse(CallState.isTransitoryState(CallState.DISCONNECTED));
        assertFalse(CallState.isTransitoryState(CallState.ABORTED));
        assertFalse(CallState.isTransitoryState(CallState.PULLING));
        assertFalse(CallState.isTransitoryState(CallState.AUDIO_PROCESSING));
        assertFalse(CallState.isTransitoryState(CallState.SIMULATED_RINGING));
    }

    /**
     * Basic tests to check which call states are considered intermediate.
     */
    @Test
    @SmallTest
    public void testIsCallStateIntermediate() {
        assertTrue(CallState.isIntermediateState(CallState.DIALING));
        assertTrue(CallState.isIntermediateState(CallState.RINGING));

        assertFalse(CallState.isIntermediateState(CallState.NEW));
        assertFalse(CallState.isIntermediateState(CallState.CONNECTING));
        assertFalse(CallState.isIntermediateState(CallState.DISCONNECTING));
        assertFalse(CallState.isIntermediateState(CallState.ANSWERED));
        assertFalse(CallState.isIntermediateState(CallState.SELECT_PHONE_ACCOUNT));
        assertFalse(CallState.isIntermediateState(CallState.ACTIVE));
        assertFalse(CallState.isIntermediateState(CallState.ON_HOLD));
        assertFalse(CallState.isIntermediateState(CallState.DISCONNECTED));
        assertFalse(CallState.isIntermediateState(CallState.ABORTED));
        assertFalse(CallState.isIntermediateState(CallState.PULLING));
        assertFalse(CallState.isIntermediateState(CallState.AUDIO_PROCESSING));
        assertFalse(CallState.isIntermediateState(CallState.SIMULATED_RINGING));
    }

    @SmallTest
    @Test
    public void testIsCreateConnectionComplete() {
        // A new call with basic info.
        Call call = new Call(
                "1", /* callId */
                mContext,
                mMockCallsManager,
                mLock,
                null /* ConnectionServiceRepository */,
                mMockPhoneNumberUtilsAdapter,
                TEST_ADDRESS,
                null /* GatewayInfo */,
                null /* connectionManagerPhoneAccountHandle */,
                SIM_1_HANDLE,
                Call.CALL_DIRECTION_INCOMING,
                false /* shouldAttachToExistingConnection*/,
                false /* isConference */,
                mMockClockProxy,
                mMockToastProxy,
                mFeatureFlags);

        // To start with connection creation isn't complete.
        assertFalse(call.isCreateConnectionComplete());

        // Need the bare minimum to get connection creation to complete.
        ParcelableConnection connection = new ParcelableConnection(null, 0, 0, 0, 0, null, 0, null,
                0, null, 0, false, false, 0L, 0L, null, null, Collections.emptyList(), null, null,
                0, 0);
        call.handleCreateConnectionSuccess(Mockito.mock(CallIdMapper.class), connection);
        assertTrue(call.isCreateConnectionComplete());
    }

    @Test
    @SmallTest
    public void testDisconnectCauseWhenAudioProcessing() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        call.setState(CallState.AUDIO_PROCESSING, "");
        call.disconnect();
        call.setDisconnectCause(new DisconnectCause(DisconnectCause.LOCAL));
        assertEquals(DisconnectCause.REJECTED, call.getDisconnectCause().getCode());
    }

    @Test
    @SmallTest
    public void testDisconnectCauseWhenAudioProcessingAfterActive() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        call.setState(CallState.AUDIO_PROCESSING, "");
        call.setState(CallState.ACTIVE, "");
        call.setState(CallState.AUDIO_PROCESSING, "");
        call.disconnect();
        call.setDisconnectCause(new DisconnectCause(DisconnectCause.LOCAL));
        assertEquals(DisconnectCause.LOCAL, call.getDisconnectCause().getCode());
    }

    @Test
    @SmallTest
    public void testDisconnectCauseWhenSimulatedRingingAndDisconnect() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        call.setState(CallState.SIMULATED_RINGING, "");
        call.disconnect();
        call.setDisconnectCause(new DisconnectCause(DisconnectCause.LOCAL));
        assertEquals(DisconnectCause.MISSED, call.getDisconnectCause().getCode());
    }

    @Test
    @SmallTest
    public void testDisconnectCauseWhenSimulatedRingingAndReject() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        call.setState(CallState.SIMULATED_RINGING, "");
        call.reject(false, "");
        call.setDisconnectCause(new DisconnectCause(DisconnectCause.LOCAL));
        assertEquals(DisconnectCause.REJECTED, call.getDisconnectCause().getCode());
    }

    @Test
    @SmallTest
    public void testIllegalAudioProcessingTransition_ExternalCall() {
        // An external call in AUDIO_PROCESSING state.
        when(mFeatureFlags.preventIllegalAudioProcessingExit()).thenReturn(true);
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        call.setConnectionService(mMockConnectionService);
        call.setState(CallState.AUDIO_PROCESSING, "test");
        call.setConnectionProperties(Connection.PROPERTY_IS_EXTERNAL_CALL);

        // Attempt to transition to ACTIVE state.
        boolean transitionResult = call.setState(CallState.ACTIVE, "test");

        // The transition should be allowed because the call is external.
        assertTrue("State transition should be allowed for external calls", transitionResult);
        assertEquals(CallState.ACTIVE, call.getState());
        verify(mMockConnectionService, never()).disconnect(eq(call));
    }

    @Test
    @SmallTest
    public void testCanPullCallRemovedDuringEmergencyCall() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        boolean[] hasCalledConnectionCapabilitiesChanged = new boolean[1];
        call.addListener(new Call.ListenerBase() {
            @Override
            public void onConnectionCapabilitiesChanged(Call call) {
                hasCalledConnectionCapabilitiesChanged[0] = true;
            }
        });
        call.setConnectionService(mMockConnectionService);
        call.setConnectionProperties(Connection.PROPERTY_IS_EXTERNAL_CALL);
        call.setConnectionCapabilities(Connection.CAPABILITY_CAN_PULL_CALL);
        call.setState(CallState.ACTIVE, "");
        assertTrue(hasCalledConnectionCapabilitiesChanged[0]);
        // Capability should be present
        assertTrue((call.getConnectionCapabilities() | Connection.CAPABILITY_CAN_PULL_CALL) > 0);
        hasCalledConnectionCapabilitiesChanged[0] = false;
        // Emergency call in progress
        call.setIsPullExternalCallSupported(false /*isPullCallSupported*/);
        assertTrue(hasCalledConnectionCapabilitiesChanged[0]);
        // Capability should not be present
        assertEquals(0, call.getConnectionCapabilities() & Connection.CAPABILITY_CAN_PULL_CALL);
        hasCalledConnectionCapabilitiesChanged[0] = false;
        // Emergency call complete
        call.setIsPullExternalCallSupported(true /*isPullCallSupported*/);
        assertTrue(hasCalledConnectionCapabilitiesChanged[0]);
        // Capability should be present
        assertEquals(Connection.CAPABILITY_CAN_PULL_CALL,
                call.getConnectionCapabilities() & Connection.CAPABILITY_CAN_PULL_CALL);
    }

    @Test
    @SmallTest
    public void testCanNotPullCallDuringEmergencyCall() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        call.setConnectionService(mMockConnectionService);
        call.setConnectionProperties(Connection.PROPERTY_IS_EXTERNAL_CALL);
        call.setConnectionCapabilities(Connection.CAPABILITY_CAN_PULL_CALL);
        call.setState(CallState.ACTIVE, "");
        // Emergency call in progress, this should show a toast and never call pullExternalCall
        // on the ConnectionService.
        doReturn(true).when(mMockCallsManager).isInEmergencyCall();
        call.pullExternalCall();
        verify(mMockConnectionService, never()).pullExternalCall(any());
    }

    @Test
    @SmallTest
    public void testCallDirection() {
        Call call = createCall("1");
        boolean[] hasCallDirectionChanged = new boolean[1];
        call.addListener(new Call.ListenerBase() {
            @Override
            public void onCallDirectionChanged(Call call) {
                hasCallDirectionChanged[0] = true;
            }
        });
        assertFalse(call.isIncoming());
        call.setCallDirection(Call.CALL_DIRECTION_INCOMING);
        assertTrue(hasCallDirectionChanged[0]);
        assertTrue(call.isIncoming());
    }

    @Test
    public void testIsSuppressedByDoNotDisturbExtra() {
        Call call = new Call(
                "1", /* callId */
                mContext,
                mMockCallsManager,
                mLock,
                null /* ConnectionServiceRepository */,
                mMockPhoneNumberUtilsAdapter,
                TEST_ADDRESS,
                null /* GatewayInfo */,
                null /* connectionManagerPhoneAccountHandle */,
                SIM_1_HANDLE,
                Call.CALL_DIRECTION_UNDEFINED,
                false /* shouldAttachToExistingConnection*/,
                true /* isConference */,
                mMockClockProxy,
                mMockToastProxy,
                mFeatureFlags);

        assertFalse(call.wasDndCheckComputedForCall());
        assertFalse(call.isCallSuppressedByDoNotDisturb());
        call.setCallIsSuppressedByDoNotDisturb(true);
        assertTrue(call.wasDndCheckComputedForCall());
        assertTrue(call.isCallSuppressedByDoNotDisturb());
    }

    @Test
    public void testGetConnectionServiceWrapper() {
        Call call = new Call(
                "1", /* callId */
                mContext,
                mMockCallsManager,
                mLock,
                null /* ConnectionServiceRepository */,
                mMockPhoneNumberUtilsAdapter,
                TEST_ADDRESS,
                null /* GatewayInfo */,
                null /* connectionManagerPhoneAccountHandle */,
                SIM_1_HANDLE,
                Call.CALL_DIRECTION_UNDEFINED,
                false /* shouldAttachToExistingConnection*/,
                true /* isConference */,
                mMockClockProxy,
                mMockToastProxy,
                mFeatureFlags);

        assertNull(call.getConnectionServiceWrapper());
        assertFalse(call.isTransactionalCall());
        call.setConnectionService(mMockConnectionService);
        assertEquals(mMockConnectionService, call.getConnectionServiceWrapper());
        call.setIsTransactionalCall(true);
        assertTrue(call.isTransactionalCall());
        assertNull(call.getConnectionServiceWrapper());
        call.setTransactionServiceWrapper(mMockTransactionalService);
        assertEquals(mMockTransactionalService, call.getTransactionServiceWrapper());
    }

    @Test
    public void testCallEventCallbacksWereCalled() throws ExecutionException, InterruptedException {
        Call call = new Call(
                "1", /* callId */
                mContext,
                mMockCallsManager,
                mLock,
                null /* ConnectionServiceRepository */,
                mMockPhoneNumberUtilsAdapter,
                TEST_ADDRESS,
                null /* GatewayInfo */,
                null /* connectionManagerPhoneAccountHandle */,
                SIM_1_HANDLE,
                Call.CALL_DIRECTION_UNDEFINED,
                false /* shouldAttachToExistingConnection*/,
                true /* isConference */,
                mMockClockProxy,
                mMockToastProxy,
                mFeatureFlags);

        // setup
        call.setIsTransactionalCall(true);
        assertTrue(call.isTransactionalCall());
        assertNull(call.getConnectionServiceWrapper());
        call.setTransactionServiceWrapper(mMockTransactionalService);
        assertEquals(mMockTransactionalService, call.getTransactionServiceWrapper());

        // assert CallEventCallback#onSetInactive is called
        call.setState(CallState.ACTIVE, "test");
        call.hold();
        verify(mMockTransactionalService, times(1)).onSetInactive(call);

        // assert CallEventCallback#onSetActive is called
        call.setState(CallState.ON_HOLD, "test");
        call.unhold();
        verify(mMockTransactionalService, times(1)).onSetActive(call);

        // assert CallEventCallback#onAnswer is called
        call.setState(CallState.RINGING, "test");
        when(mMockTransactionalService.onAnswer(any(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(Boolean.TRUE));

        call.answer(0).get(); // Call .get() to wait for the async callback
        verify(mMockTransactionalService, times(1)).onAnswer(call, 0);

        // assert CallEventCallback#onDisconnect is called
        call.setState(CallState.ACTIVE, "test");
        call.disconnect();
        verify(mMockTransactionalService, times(1)).onDisconnect(call,
                call.getDisconnectCause());
    }

    @Test
    @SmallTest
    public void testSetConnectionPropertiesRttOnOff() {
        Call call = createCall("1");
        call.setConnectionService(mMockConnectionService);

        call.setConnectionProperties(Connection.PROPERTY_IS_RTT);
        verify(mMockCallsManager).playRttUpgradeToneForCall(any());
        assertNotNull(null, call.getInCallToCsRttPipeForCs());
        assertNotNull(null, call.getCsToInCallRttPipeForInCall());

        call.setConnectionProperties(0);
        assertNull(null, call.getInCallToCsRttPipeForCs());
        assertNull(null, call.getCsToInCallRttPipeForInCall());
    }

    @Test
    @SmallTest
    public void testGetFromCallerInfo() {
        Call call = createCall("1");

        CallerInfo info = new CallerInfo();
        info.setName("name");
        info.setPhoneNumber("number");
        info.cachedPhoto = new ColorDrawable();
        info.cachedPhotoIcon = Bitmap.createBitmap(24, 24, Bitmap.Config.ALPHA_8);

        ArgumentCaptor<CallerInfoLookupHelper.OnQueryCompleteListener> listenerCaptor =
                ArgumentCaptor.forClass(CallerInfoLookupHelper.OnQueryCompleteListener.class);
        verify(mMockCallerInfoLookupHelper).startLookup(any(), listenerCaptor.capture());
        listenerCaptor.getValue().onCallerInfoQueryComplete(call.getHandle(), info);

        assertEquals(info, call.getCallerInfo());
        assertEquals(info.getName(), call.getName());
        assertEquals(info.getPhoneNumber(), call.getPhoneNumber());
        assertEquals(info.cachedPhoto, call.getPhoto());
        assertEquals(info.cachedPhotoIcon, call.getPhotoIcon());
        assertEquals(call.getHandle(), call.getContactUri());
    }

    @Test
    @SmallTest
    public void testGetFromCallerInfo_skipLookup() {
        when(mMockResources.getString(R.string.skip_incoming_caller_info_account_package))
                .thenReturn("com.foo");

        createCall("1");

        verify(mMockCallerInfoLookupHelper, never()).startLookup(any(), any());
    }

    @Test
    @SmallTest
    public void testOriginalCallIntent() {
        Call call = createCall("1");

        Intent i = new Intent();
        call.setOriginalCallIntent(i);

        assertEquals(i, call.getOriginalCallIntent());
    }

    @Test
    @SmallTest
    public void testHandleCreateConferenceSuccessNotifiesListeners() {
        Call.Listener listener = mock(Call.Listener.class);

        Call incomingCall = createCall("1", Call.CALL_DIRECTION_INCOMING);
        incomingCall.setConnectionService(mMockConnectionService);
        incomingCall.addListener(listener);
        Call outgoingCall = createCall("2", Call.CALL_DIRECTION_OUTGOING);
        outgoingCall.setConnectionService(mMockConnectionService);
        outgoingCall.addListener(listener);

        StatusHints statusHints = mock(StatusHints.class);
        Bundle extra = new Bundle();
        ParcelableConference conference =
                new ParcelableConference.Builder(SIM_1_HANDLE, Connection.STATE_NEW)
                    .setAddress(TEST_ADDRESS, TelecomManager.PRESENTATION_ALLOWED)
                    .setConnectionCapabilities(123)
                    .setVideoAttributes(null, VideoProfile.STATE_AUDIO_ONLY)
                    .setRingbackRequested(true)
                    .setStatusHints(statusHints)
                    .setExtras(extra)
                    .build();

        incomingCall.handleCreateConferenceSuccess(null, conference);
        verify(listener).onSuccessfulIncomingCall(incomingCall);

        outgoingCall.handleCreateConferenceSuccess(null, conference);
        verify(listener).onSuccessfulOutgoingCall(outgoingCall, CallState.NEW);
    }

    @Test
    @SmallTest
    public void testHandleCreateConferenceSuccess() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        call.setConnectionService(mMockConnectionService);

        StatusHints statusHints = mock(StatusHints.class);
        Bundle extra = new Bundle();
        ParcelableConference conference =
                new ParcelableConference.Builder(SIM_1_HANDLE, Connection.STATE_NEW)
                    .setAddress(TEST_ADDRESS, TelecomManager.PRESENTATION_ALLOWED)
                    .setConnectionCapabilities(123)
                    .setVideoAttributes(null, VideoProfile.STATE_AUDIO_ONLY)
                    .setRingbackRequested(true)
                    .setStatusHints(statusHints)
                    .setExtras(extra)
                    .build();

        call.handleCreateConferenceSuccess(null, conference);

        assertEquals(SIM_1_HANDLE, call.getTargetPhoneAccount());
        assertEquals(TEST_ADDRESS, call.getHandle());
        assertEquals(123, call.getConnectionCapabilities());
        assertNull(call.getVideoProviderProxy());
        assertEquals(VideoProfile.STATE_AUDIO_ONLY, call.getVideoState());
        assertTrue(call.isRingbackRequested());
        assertEquals(statusHints, call.getStatusHints());
    }

    @Test
    @SmallTest
    public void testHandleCreateConferenceFailure() {
        Call.Listener listener = mock(Call.Listener.class);

        Call incomingCall = createCall("1", Call.CALL_DIRECTION_INCOMING);
        incomingCall.setConnectionService(mMockConnectionService);
        incomingCall.addListener(listener);
        Call outgoingCall = createCall("2", Call.CALL_DIRECTION_OUTGOING);
        outgoingCall.setConnectionService(mMockConnectionService);
        outgoingCall.addListener(listener);

        final DisconnectCause cause = new DisconnectCause(DisconnectCause.REJECTED);

        incomingCall.handleCreateConferenceFailure(cause);
        assertEquals(cause, incomingCall.getDisconnectCause());
        verify(listener).onFailedIncomingCall(incomingCall);

        outgoingCall.handleCreateConferenceFailure(cause);
        assertEquals(cause, outgoingCall.getDisconnectCause());
        verify(listener).onFailedOutgoingCall(outgoingCall, cause);
    }

    @Test
    @SmallTest
    public void testWasConferencePreviouslyMerged() {
        Call call = createCall("1");
        call.setConnectionService(mMockConnectionService);
        call.setConnectionCapabilities(Connection.CAPABILITY_MERGE_CONFERENCE);

        assertFalse(call.wasConferencePreviouslyMerged());

        call.mergeConference();

        assertTrue(call.wasConferencePreviouslyMerged());
    }

    @Test
    @SmallTest
    public void testSwapConference() {
        Call.Listener listener = mock(Call.Listener.class);

        Call call = createCall("1");
        call.setConnectionService(mMockConnectionService);
        call.setConnectionCapabilities(Connection.CAPABILITY_SWAP_CONFERENCE);
        call.addListener(listener);

        call.swapConference();
        assertNull(call.getConferenceLevelActiveCall());

        Call childCall1 = createCall("child1");
        childCall1.setChildOf(call);
        call.swapConference();
        assertEquals(childCall1, call.getConferenceLevelActiveCall());

        Call childCall2 = createCall("child2");
        childCall2.setChildOf(call);
        call.swapConference();
        assertEquals(childCall1, call.getConferenceLevelActiveCall());
        call.swapConference();
        assertEquals(childCall2, call.getConferenceLevelActiveCall());

        verify(listener, times(4)).onCdmaConferenceSwap(call);
    }

    @Test
    @SmallTest
    public void testHandleCreateConnectionFailure() {
        Call.Listener listener = mock(Call.Listener.class);

        Call incomingCall = createCall("1", Call.CALL_DIRECTION_INCOMING);
        incomingCall.setConnectionService(mMockConnectionService);
        incomingCall.addListener(listener);
        Call outgoingCall = createCall("2", Call.CALL_DIRECTION_OUTGOING);
        outgoingCall.setConnectionService(mMockConnectionService);
        outgoingCall.addListener(listener);
        Call unknownCall = createCall("3", Call.CALL_DIRECTION_UNKNOWN);
        unknownCall.setConnectionService(mMockConnectionService);
        unknownCall.addListener(listener);

        final DisconnectCause cause = new DisconnectCause(DisconnectCause.REJECTED);

        incomingCall.handleCreateConnectionFailure(cause);
        assertEquals(cause, incomingCall.getDisconnectCause());
        verify(listener).onFailedIncomingCall(incomingCall);

        outgoingCall.handleCreateConnectionFailure(cause);
        assertEquals(cause, outgoingCall.getDisconnectCause());
        verify(listener).onFailedOutgoingCall(outgoingCall, cause);

        unknownCall.handleCreateConnectionFailure(cause);
        assertEquals(cause, unknownCall.getDisconnectCause());
        verify(listener).onFailedUnknownCall(unknownCall);
    }

    /**
     * ensure a Call object does not throw an NPE when the CallingPackageIdentity is not set and
     * the correct values are returned when set
     */
    @Test
    @SmallTest
    public void testCallingPackageIdentity() {
        final int packageUid = 123;
        final int packagePid = 1;

        Call call = createCall("1");

        // assert default values for a Calls CallingPackageIdentity are -1 unless set via the setter
        assertEquals(-1, call.getCallingPackageIdentity().mCallingPackageUid);
        assertEquals(-1, call.getCallingPackageIdentity().mCallingPackagePid);

        // set the Call objects CallingPackageIdentity via the setter and a bundle
        Bundle extras = new Bundle();
        extras.putInt(CallAttributes.CALLER_UID_KEY, packageUid);
        extras.putInt(CallAttributes.CALLER_PID_KEY, packagePid);
        // assert that the setter removed the extras
        assertEquals(packageUid, extras.getInt(CallAttributes.CALLER_UID_KEY));
        assertEquals(packagePid, extras.getInt(CallAttributes.CALLER_PID_KEY));
        call.setCallingPackageIdentity(extras);
        // assert that the setter removed the extras
        assertEquals(0, extras.getInt(CallAttributes.CALLER_UID_KEY));
        assertEquals(0, extras.getInt(CallAttributes.CALLER_PID_KEY));
        // assert the properties are fetched correctly
        assertEquals(packageUid, call.getCallingPackageIdentity().mCallingPackageUid);
        assertEquals(packagePid, call.getCallingPackageIdentity().mCallingPackagePid);
    }

    @Test
    @SmallTest
    public void testGetCreationTimeMillis_HandlesZeroInitialTime() {
        // This test verifies the defensive check in getCreationTimeMillis.
        // In some rare cases, a Call object might be created with a creation time of 0.
        // The getter should handle this by setting a proper creation time.

        // Arrange:
        // Simulate the clock returning 0 during the Call's constructor.
        when(mMockClockProxy.currentTimeMillis()).thenReturn(0L);
        Call call = createCall("1");

        // Arrange:
        // Now, have the clock return a valid time for the getter call.
        long expectedCreationTime = 1700000000L;
        when(mMockClockProxy.currentTimeMillis()).thenReturn(expectedCreationTime);

        // Act:
        // Call the getter, which should trigger the defensive check.
        long actualCreationTime = call.getCreationTimeMillis();

        // Assert:
        // The returned time should be the valid time, not the initial 0.
        assertEquals(expectedCreationTime, actualCreationTime);
        // Verify the clock was called once in the constructor and once in the getter.
        verify(mMockClockProxy, times(2)).currentTimeMillis();
    }

    @Test
    @SmallTest
    public void testGetCrsMode() {
        Call call = createCall("1");
        Bundle extras = new Bundle();

        // 1. When extras are null, should default to MODE_IN_CALL.
        assertEquals(AudioManager.MODE_IN_CALL, call.getCrsMode());

        // 2. When extras are set but the CRS extra is not present, should default to MODE_IN_CALL.
        call.putConnectionServiceExtras(extras);
        assertEquals(AudioManager.MODE_IN_CALL, call.getCrsMode());

        // 3. When the CRS extra is explicitly set to MODE_IN_CALL, should return MODE_IN_CALL.
        extras.putInt(android.telecom.Call.EXTRA_CRS_AUDIO_MODE, AudioManager.MODE_IN_CALL);
        call.putConnectionServiceExtras(extras);
        assertEquals(AudioManager.MODE_IN_CALL, call.getCrsMode());

        // 4. When the CRS extra is set to MODE_RINGTONE, should return MODE_RINGTONE.
        extras.putInt(android.telecom.Call.EXTRA_CRS_AUDIO_MODE, AudioManager.MODE_RINGTONE);
        call.putConnectionServiceExtras(extras);
        assertEquals(AudioManager.MODE_RINGTONE, call.getCrsMode());
    }

    @Test
    @SmallTest
    public void testOnConnectionEventNotifiesListener() {
        Call.Listener listener = mock(Call.Listener.class);
        Call call = createCall("1");
        call.addListener(listener);

        call.onConnectionEvent(Connection.EVENT_ON_HOLD_TONE_START, null);
        verify(listener).onHoldToneRequested(call);
        assertTrue(call.isRemotelyHeld());

        call.onConnectionEvent(Connection.EVENT_ON_HOLD_TONE_END, null);
        verify(listener, times(2)).onHoldToneRequested(call);
        assertFalse(call.isRemotelyHeld());

        call.onConnectionEvent(Connection.EVENT_CALL_HOLD_FAILED, null);
        verify(listener).onCallHoldFailed(call);

        call.onConnectionEvent(Connection.EVENT_CALL_SWITCH_FAILED, null);
        verify(listener).onCallSwitchFailed(call);

        call.onConnectionEvent(Connection.EVENT_CALL_RESUME_FAILED, null);
        verify(listener).onCallResumeFailed(call);

        call.setIsEmergencyCall(false);
        // Verify no onConnectionEvent received
        call.onConnectionEvent(EVENT_DISPLAY_EMERGENCY_MESSAGE, null);
        verify(listener, never()).onConnectionEvent(eq(call), anyString(), nullable(Bundle.class));

        final int d2dType = 1;
        final int d2dValue = 2;
        final Bundle d2dExtras = new Bundle();
        d2dExtras.putInt(Connection.EXTRA_DEVICE_TO_DEVICE_MESSAGE_TYPE, d2dType);
        d2dExtras.putInt(Connection.EXTRA_DEVICE_TO_DEVICE_MESSAGE_VALUE, d2dValue);
        call.onConnectionEvent(Connection.EVENT_DEVICE_TO_DEVICE_MESSAGE, d2dExtras);
        verify(listener).onReceivedDeviceToDeviceMessage(call, d2dType, d2dValue);

        final CallQuality quality = new CallQuality();
        final Bundle callQualityExtras = new Bundle();
        callQualityExtras.putParcelable(Connection.EXTRA_CALL_QUALITY_REPORT, quality);
        call.onConnectionEvent(Connection.EVENT_CALL_QUALITY_REPORT, callQualityExtras);
        verify(listener).onReceivedCallQualityReport(call, quality);
    }

    @Test
    @SmallTest
    public void testOnConnectionEventTS() {
        Call.Listener listener = mock(Call.Listener.class);
        Call call = createCall("1");
        call.addListener(listener);
        call.setIsTransactionalCall(true);
        call.onConnectionEvent(Connection.EVENT_CALL_HOLD_FAILED, null);
        verify(listener).onConnectionEvent(eq(call), eq(Connection.EVENT_CALL_HOLD_FAILED),
                nullable(Bundle.class));
    }

    @Test
    @SmallTest
    public void testDiagnosticMessage() {
        Call.Listener listener = mock(Call.Listener.class);
        Call call = createCall("1");
        call.addListener(listener);

        final int id = 1;
        final String message = "msg";

        call.displayDiagnosticMessage(id, message);
        verify(listener).onConnectionEvent(
                eq(call),
                eq(android.telecom.Call.EVENT_DISPLAY_DIAGNOSTIC_MESSAGE),
                argThat(extras -> {
                    return extras.getInt(android.telecom.Call.EXTRA_DIAGNOSTIC_MESSAGE_ID) == id &&
                            extras.getCharSequence(android.telecom.Call.EXTRA_DIAGNOSTIC_MESSAGE)
                                .toString().equals(message);
                }));

        call.clearDiagnosticMessage(id);
        verify(listener).onConnectionEvent(
                eq(call),
                eq(android.telecom.Call.EVENT_CLEAR_DIAGNOSTIC_MESSAGE),
                argThat(extras -> {
                    return extras.getInt(android.telecom.Call.EXTRA_DIAGNOSTIC_MESSAGE_ID) == id;
                }));
    }

    @Test
    @SmallTest
    public void testExcludesInCallServiceFromDoNotLogCallExtra() {
        Call call = createCall("any");
        Bundle extra = new Bundle();
        extra.putBoolean(TelecomManager.EXTRA_DO_NOT_LOG_CALL, true);

        call.putInCallServiceExtras(extra, "packageName");

        assertFalse(call.getExtras().containsKey(TelecomManager.EXTRA_DO_NOT_LOG_CALL));
    }

    /**
     * Verify that a Call can handle a case where no telephony stack is present to detect emergency
     * numbers.
     */
    @Test
    @SmallTest
    public void testNoTelephonyEmergencyBehavior() {
        when(mComponentContextFixture.getTelephonyManager().isEmergencyNumber(any()))
                .thenReturn(true);
        Call testCall = createCall("1", Call.CALL_DIRECTION_OUTGOING, Uri.parse("tel:911"));
        assertTrue(testCall.isEmergencyCall());

        when(mComponentContextFixture.getTelephonyManager().isEmergencyNumber(any()))
                .thenThrow(new UnsupportedOperationException("Bee-boop"));
        Call testCall2 = createCall("2", Call.CALL_DIRECTION_OUTGOING, Uri.parse("tel:911"));
        assertTrue(!testCall2.isEmergencyCall());
    }

    @Test
    @SmallTest
    public void testExcludesConnectionServiceWithoutModifyStatePermissionFromDoNotLogCallExtra() {
        PackageManager packageManager = mContext.getPackageManager();
        Bundle extra = new Bundle();
        extra.putBoolean(TelecomManager.EXTRA_DO_NOT_LOG_CALL, true);
        String packageName = SIM_1_HANDLE.getComponentName().getPackageName();
        doReturn(PackageManager.PERMISSION_DENIED)
                .when(packageManager)
                .checkPermission(android.Manifest.permission.MODIFY_PHONE_STATE, packageName);
        Call call = createCall("any");

        call.putConnectionServiceExtras(extra);

        assertFalse(call.getExtras().containsKey(TelecomManager.EXTRA_DO_NOT_LOG_CALL));
    }

    @Test
    @SmallTest
    public void testDoesNotExcludeConnectionServiceWithModifyStatePermissionFromDoNotLogCallExtra() {
        String packageName = SIM_1_HANDLE.getComponentName().getPackageName();
        Bundle extra = new Bundle();
        extra.putBoolean(TelecomManager.EXTRA_DO_NOT_LOG_CALL, true);
        PackageManager packageManager = mContext.getPackageManager();
        doReturn(PackageManager.PERMISSION_GRANTED)
                .when(packageManager)
                .checkPermission(android.Manifest.permission.MODIFY_PHONE_STATE, packageName);
        Call call = createCall("any");

        call.putConnectionServiceExtras(extra);

        assertTrue(call.getExtras().containsKey(TelecomManager.EXTRA_DO_NOT_LOG_CALL));
    }

    @Test
    @SmallTest
    public void testSkipLoadingCannedTextResponse() {
        Call call = createCall("any");
        when(mMockResources.getBoolean(R.bool.skip_loading_canned_text_response))
                .thenReturn(true);


        assertFalse(call.isRespondViaSmsCapable());
    }

    @Test
    public void testLogTransactionalCall() {
        when(mFeatureFlags.integratedCallLogs()).thenReturn(true);
        Call call = new Call(
                "1", /* callId */
                mContext,
                mMockCallsManager,
                mLock,
                null /* ConnectionServiceRepository */,
                mMockPhoneNumberUtilsAdapter,
                TEST_ADDRESS,
                null /* GatewayInfo */,
                null /* connectionManagerPhoneAccountHandle */,
                SIM_1_HANDLE,
                Call.CALL_DIRECTION_UNDEFINED,
                false /* shouldAttachToExistingConnection*/,
                true /* isConference */,
                mMockClockProxy,
                mMockToastProxy,
                mFeatureFlags);

        call.setIsTransactionalCall(true);
        call.setAssociatedUser(UserHandle.CURRENT);
        PackageManager pm = mock(PackageManager.class);
        ResolveInfo resolveInfo = mock(ResolveInfo.class);
        when(mContext.getPackageManager()).thenReturn(pm);
        when(pm.queryIntentActivities(any(Intent.class), eq(PackageManager.MATCH_ALL)))
                .thenReturn(List.of(resolveInfo));
        // Ensure call log pref setting is enabled if the integrated call logs stage 2 flag
        // is enabled.
        if (android.telecom.flags.Flags.integratedCallLogsStage2()) {
            when(mMockCallsManager.isCallLogPrefEnabledForPackage(any(UserHandle.class),
                    anyString())).thenReturn(true);
        }
        // Verify that we will log the transactional call when the integrated call logs flags is
        // enabled.
        assertTrue(call.isLoggedTransactional());

        // Assert logs are excluded if there's no pkg that supports the intent
        when(pm.queryIntentActivities(any(Intent.class), eq(PackageManager.MATCH_ALL)))
                .thenReturn(List.of());
        assertFalse(call.isLoggedTransactional());

        // Assert logs are excluded if the user pref setting is disabled
        if (android.telecom.flags.Flags.integratedCallLogsStage2()) {
            when(pm.queryIntentActivities(any(Intent.class), eq(PackageManager.MATCH_ALL)))
                    .thenReturn(List.of(resolveInfo));
            when(mMockCallsManager.isCallLogPrefEnabledForPackage(any(UserHandle.class),
                    anyString())).thenReturn(false);
            assertFalse(call.isLoggedTransactional());
        }
    }

    @Test
    public void testDoNotLogSelfManagedCall() {
        when(mFeatureFlags.integratedCallLogs()).thenReturn(true);
        Call call = new Call(
                "1", /* callId */
                mContext,
                mMockCallsManager,
                mLock,
                null /* ConnectionServiceRepository */,
                mMockPhoneNumberUtilsAdapter,
                TEST_ADDRESS,
                null /* GatewayInfo */,
                null /* connectionManagerPhoneAccountHandle */,
                SIM_1_HANDLE,
                Call.CALL_DIRECTION_UNDEFINED,
                false /* shouldAttachToExistingConnection*/,
                true /* isConference */,
                mMockClockProxy,
                mMockToastProxy,
                mFeatureFlags);

        call.setIsSelfManaged(true);
        // Verify that we will not log the self-managed call when the integrated call logs flags is
        // enabled.
        assertFalse(call.isLoggedSelfManaged());
    }

    @Test
    @SmallTest
    public void testNotifyAnswerRequested() {
        Call call = createCall("1");
        Call.InCallServiceToVoipAppListener listener =
                mock(Call.InCallServiceToVoipAppListener.class);
        call.addInCallServiceToVoipAppListener(listener);

        // Target: catch (Exception e) block in notifyAnswerRequested
        doThrow(new RuntimeException("Test Exception")).when(listener)
                .onAnswerRequested(any(), anyInt(), any());

        OutcomeReceiver<Object, Exception> or = mock(OutcomeReceiver.class);
        call.notifyAnswerRequested(VideoProfile.STATE_AUDIO_ONLY, or);

        verify(listener).onAnswerRequested(call, VideoProfile.STATE_AUDIO_ONLY, or);
        call.removeInCallServiceToVoipAppListener(listener);
    }

    @Test
    @SmallTest
    public void testSetHandleEmergencyErrorPaths() {
        Call call = createCall("1");
        TelephonyManager mockTelephonyManager = mock(TelephonyManager.class);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mockTelephonyManager);

        // Target: catch (UnsupportedOperationException) in setHandle
        doThrow(new UnsupportedOperationException()).when(mockTelephonyManager)
                .isEmergencyNumber(anyString());
        call.setHandle(Uri.parse("tel:911"), TelecomManager.PRESENTATION_ALLOWED);
        assertFalse(call.isEmergencyCall());

        // Target: catch (IllegalStateException) in setHandle
        doThrow(new IllegalStateException()).when(mockTelephonyManager)
                .isEmergencyNumber(anyString());
        call.setHandle(Uri.parse("tel:912"), TelecomManager.PRESENTATION_ALLOWED);
        assertFalse(call.isEmergencyCall());

        // Target: catch (RuntimeException) in setHandle
        doThrow(new RuntimeException()).when(mockTelephonyManager)
                .isEmergencyNumber(anyString());
        call.setHandle(Uri.parse("tel:913"), TelecomManager.PRESENTATION_ALLOWED);
        assertFalse(call.isEmergencyCall());
    }

    @Test
    @SmallTest
    public void testIsTestEmergencyCall() {
        String testNumber = "123";
        Call call = createCall("1");
        TelephonyManager mockTelephonyManager = mock(TelephonyManager.class);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mockTelephonyManager);

        // Target: Normal true case
        EmergencyNumber mockEmergencyNumber = mock(EmergencyNumber.class);
        List<EmergencyNumber> emergencyNumbersList = List.of(mockEmergencyNumber);
        when(mockEmergencyNumber.isFromSources(anyInt())).thenReturn(true);
        when(mockEmergencyNumber.getNumber()).thenReturn(testNumber);
        when(mockTelephonyManager.getEmergencyNumberList())
                .thenReturn(Map.of(0, emergencyNumbersList));
        call.setHandle(Uri.parse("tel:911"), TelecomManager.PRESENTATION_ALLOWED);
        assertTrue(call.isTestEmergencyCall(testNumber));

        // Target: catch (UnsupportedOperationException) in isTestEmergencyCall
        doThrow(new UnsupportedOperationException()).when(mockTelephonyManager)
                .getEmergencyNumberList();
        call.setHandle(Uri.parse("tel:911"), TelecomManager.PRESENTATION_ALLOWED);
        assertFalse(call.isTestEmergencyCall());

        // Target: catch (IllegalStateException) in isTestEmergencyCall
        doThrow(new IllegalStateException()).when(mockTelephonyManager)
                .getEmergencyNumberList();
        call.setHandle(Uri.parse("tel:911"), TelecomManager.PRESENTATION_ALLOWED);
        assertFalse(call.isTestEmergencyCall(testNumber));
    }

    @Test
    @SmallTest
    public void testMaybeLoadCannedSmsResponsesCallback_onError() {
        processMaybeLoadCannedSmsResponsesCallback(false /* onResult */);
    }

    @Test
    @SmallTest
    public void testMaybeLoadCannedSmsResponsesCallback_onResult() {
        processMaybeLoadCannedSmsResponsesCallback(true /* onResult */);
    }

    @Test
    @SmallTest
    public void testSetTransactionalCallSupportsVideoCallingValidation() {
        Call call = createCall("1");
        CallAttributes attributes = new CallAttributes.Builder(
                SIM_1_HANDLE, CallAttributes.DIRECTION_OUTGOING, "John Doe",
                Uri.parse("tel:123")).build();
        CallAttributes videoCallAttributes = new CallAttributes.Builder(
                SIM_1_HANDLE, CallAttributes.DIRECTION_OUTGOING, "John Doe",
                Uri.parse("tel:123"))
                .setCallCapabilities(CallAttributes.SUPPORTS_VIDEO_CALLING).build();
        call.setIsTransactionalCall(false);

        // Target: if (!mIsTransactionalCall)
        call.setTransactionalCallSupportsVideoCalling(attributes);
        assertFalse(call.isTransactionalCallSupportsVideoCalling());

        // Target: null attributes
        call.setTransactionalCallSupportsVideoCalling(null);
        assertFalse(call.isTransactionalCallSupportsVideoCalling());

        call.setIsTransactionalCall(true);
        // Target: attributes don't support video calling
        call.setTransactionalCallSupportsVideoCalling(attributes);
        assertFalse(call.isTransactionalCallSupportsVideoCalling());

        // Target: attributes support video calling
        call.setTransactionalCallSupportsVideoCalling(videoCallAttributes);
        assertTrue(call.isTransactionalCallSupportsVideoCalling());
    }

    @Test
    @SmallTest
    @EnableFlags({Flags.FLAG_CONNECTION_SERVICE_BAL,
            Flags.FLAG_VOIP_BACKGROUND_ACTIVITY_LAUNCH_FIX})
    public void testWaitForConnectionServiceBind() throws Exception {
        Call call = spy(createCall("1"));
        ConnectionServiceWrapper csWrapper = mock(ConnectionServiceWrapper.class);
        call.setConnectionService(csWrapper);
        call.setIsSelfManaged(true);
        call.setState(CallState.RINGING, "1");
        // This triggers the internal OutcomeReceiver in waitForConnectionServiceBind
        CompletableFuture<Boolean> future = call.answer(VideoProfile.STATE_AUDIO_ONLY);
        ArgumentCaptor<OutcomeReceiver<Object, Exception>> captor =
                ArgumentCaptor.forClass(OutcomeReceiver.class);
        verify(call).notifyAnswerRequested(eq(VideoProfile.STATE_AUDIO_ONLY), captor.capture());
        OutcomeReceiver<Object, Exception> receiver = captor.getValue();

        // Target: Path unexpected result
        receiver.onResult("Unexpected String");
        // Target: onError path
        receiver.onError(new Exception("Test Error"));
        assertFalse(future.isDone());
        future.complete(true);

        future = call.answer(VideoProfile.STATE_AUDIO_ONLY);
        captor = ArgumentCaptor.forClass(OutcomeReceiver.class);
        verify(call, times(2)).notifyAnswerRequested(
                eq(VideoProfile.STATE_AUDIO_ONLY), captor.capture());
        receiver = captor.getValue();
        // Target: onResult successful path
        receiver.onResult(mock(VoipCallMonitor.class));
        future.complete(true);
    }

    @Test
    @SmallTest
    public void testAnswerForAudioProcessing() {
        Call call = spy(createCall("1"));
        // Target: state is not ringing
        call.answerForAudioProcessing();
        call.setState(CallState.RINGING, "1");
        // Target: CS is null
        call.answerForAudioProcessing();
        // CS is not null
        ConnectionServiceWrapper mockCSWrapper = mock(ConnectionServiceWrapper.class);
        call.setConnectionService(mockCSWrapper);
        call.answerForAudioProcessing();
    }

    @Test
    @SmallTest
    public void testTransferWithUri() {
        Call call = spy(createCall("1"));
        ConnectionServiceWrapper mockCSWrapper = mock(ConnectionServiceWrapper.class);
        TransactionalServiceWrapper mockTSWrapper = mock(TransactionalServiceWrapper.class);
        // Target: Call state is not active or on hold
        call.setState(CallState.DIALING, "1");
        call.transfer(TEST_ADDRESS, true /* isConfirmationRequired */);

        call.setState(CallState.ACTIVE, "1");
        // Target: CS + TS are null
        call.transfer(TEST_ADDRESS, true /* isConfirmationRequired */);

        // Target: CS is not null
        call.setConnectionService(mockCSWrapper);
        call.transfer(TEST_ADDRESS, true /* isConfirmationRequired */);

        // Target: TS is not null (this order of mocking works b/c the TS check happens first)
        call.setTransactionServiceWrapper(mockTSWrapper);
        call.transfer(TEST_ADDRESS, true /* isConfirmationRequired */);
    }

    @Test
    @SmallTest
    public void testTransferWithCall() {
        Call call = spy(createCall("1"));
        Call otherCall = spy(createCall("2"));
        ConnectionServiceWrapper mockCSWrapper = mock(ConnectionServiceWrapper.class);
        TransactionalServiceWrapper mockTSWrapper = mock(TransactionalServiceWrapper.class);
        // Target: Call state active but other call is not on hold
        call.setState(CallState.ACTIVE, "1");
        call.transfer(otherCall);

        otherCall.setState(CallState.ON_HOLD, "2");
        // Target: CS + TS are null
        call.transfer(otherCall);

        // Target: CS is not null
        call.setConnectionService(mockCSWrapper);
        call.transfer(otherCall);

        // Target: TS is not null (this order of mocking works b/c the TS check happens first)
        call.setTransactionServiceWrapper(mockTSWrapper);
        call.transfer(otherCall);
    }

    @Test
    @SmallTest
    public void testRejectWithReasonCodeSimulatedRingingTransactional() {
        Call call = spy(createCall("1"));
        call.setState(CallState.SIMULATED_RINGING, "test");
        call.setTransactionServiceWrapper(mMockTransactionalService);
        when(mMockTransactionalService.onDisconnect(any(), any())).thenReturn(
                CompletableFuture.completedFuture(true));

        CompletableFuture<Boolean> result = call.reject(
                android.telecom.Call.REJECT_REASON_DECLINED);

        verify(mMockTransactionalService).onDisconnect(eq(call), any(DisconnectCause.class));
        assertTrue(result.isDone());
    }

    @Test
    @SmallTest
    public void testRejectWithReasonCodeSimulatedRingingConnectionService() {
        Call call = spy(createCall("1"));
        call.setState(CallState.SIMULATED_RINGING, "test");
        call.setConnectionService(mMockConnectionService);
        doReturn(CompletableFuture.completedFuture(true))
                .when(call).awaitCallStateChangeAndMaybeDisconnectCall(anyBoolean(), anyString(),
                        any());

        CompletableFuture<Boolean> future =
                call.reject(android.telecom.Call.REJECT_REASON_DECLINED);

        verify(mMockConnectionService).disconnect(call);
        verify(call).setOverrideDisconnectCauseCode(any(DisconnectCause.class));
        future.complete(true);
    }

    @Test
    @SmallTest
    public void testRejectWithReasonCodeSimulatedRingingNoService() {
        Call call = spy(createCall("1"));
        call.setState(CallState.SIMULATED_RINGING, "test");

        CompletableFuture<Boolean> result = call.reject(
                android.telecom.Call.REJECT_REASON_DECLINED);

        assertTrue(result.isDone());
        assertFalse(result.join());
    }

    @Test
    @SmallTest
    public void testRejectWithReasonCodeRingingTransactional() {
        Call call = spy(createCall("1"));
        call.setState(CallState.RINGING, "test");
        call.setTransactionServiceWrapper(mMockTransactionalService);
        when(mMockTransactionalService.onDisconnect(any(), any())).thenReturn(
                CompletableFuture.completedFuture(true));

        CompletableFuture<Boolean> future =
                call.reject(android.telecom.Call.REJECT_REASON_DECLINED);
        verify(mMockTransactionalService).onDisconnect(eq(call), any(DisconnectCause.class));
        future.complete(true);
    }

    @Test
    @SmallTest
    public void testRejectWithReasonCodeRingingConnectionService() {
        Call call = spy(createCall("1"));
        call.setState(CallState.RINGING, "test");
        call.setConnectionService(mMockConnectionService);
        doReturn(CompletableFuture.completedFuture(true))
                .when(call).awaitCallStateChangeAndMaybeDisconnectCall(anyBoolean(), anyString(),
                        any());
        int rejectReason = android.telecom.Call.REJECT_REASON_DECLINED;
        CompletableFuture<Boolean> future = call.reject(rejectReason);
        future.complete(true);
    }

    @Test
    @SmallTest
    public void testRejectWithReasonCodeAnsweredConnectionService() {
        Call call = spy(createCall("1"));
        call.setState(CallState.ANSWERED, "test");
        call.setConnectionService(mMockConnectionService);
        doReturn(CompletableFuture.completedFuture(true))
                .when(call).awaitCallStateChangeAndMaybeDisconnectCall(anyBoolean(), anyString(),
                        any());
        int rejectReason = android.telecom.Call.REJECT_REASON_DECLINED;
        CompletableFuture<Boolean> future = call.reject(rejectReason);
        future.complete(true);
    }

    @Test
    @SmallTest
    public void testRejectWithReasonCodeRingingNoService() {
        Call call = spy(createCall("1"));
        call.setState(CallState.RINGING, "test");

        CompletableFuture<Boolean> result = call.reject(
                android.telecom.Call.REJECT_REASON_DECLINED);

        assertTrue(result.isDone());
        assertFalse(result.join());
    }

    @Test
    @SmallTest
    public void testRejectWithReasonCodeInvalidState() {
        Call call = spy(createCall("1"));
        call.setState(CallState.ACTIVE, "test");

        CompletableFuture<Boolean> result = call.reject(
                android.telecom.Call.REJECT_REASON_DECLINED);

        assertTrue(result.isDone());
        assertFalse(result.join());
    }

    @Test
    @SmallTest
    public void testHoldSelfManaged() throws Exception {
        when(mFeatureFlags.transactionalCsVerifier()).thenReturn(true);
        Call call = spy(createCall("1"));
        call.setState(CallState.ACTIVE, "1");
        ConnectionServiceWrapper csWrapper = mock(ConnectionServiceWrapper.class);
        call.setConnectionService(csWrapper);
        call.setIsSelfManaged(true);
        // Successful completion verification
        CompletableFuture<Boolean> holdFuture = call.hold("hold call");
        holdFuture.complete(true);
        // Failed completion verification
        holdFuture = call.hold("hold call");
        holdFuture.complete(false);
    }

    @Test
    @SmallTest
    public void testHoldNoService() {
        Call call = spy(createCall("1"));
        call.setState(CallState.ACTIVE, "1");
        assertNull(call.getConnectionService());
        assertNull(call.getTransactionServiceWrapper());
        // Hold will do nothing
        CompletableFuture<Boolean> future = call.hold("hold call");
        future.complete(true);
    }

    @Test
    @SmallTest
    @EnableFlags(Flags.FLAG_DISCONNECT_VOIP_ON_HOLD_FAIL)
    public void testTransactionalHoldTimeoutCompletesSuccessfully() throws Exception {
        Call call = spy(createCall("1"));
        call.setState(CallState.ACTIVE, "1");
        call.setTransactionServiceWrapper(mMockTransactionalService);

        CompletableFuture<Boolean> holdResult = CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> disconnectResult = CompletableFuture.completedFuture(true);
        when(mMockTransactionalService.onSetInactive(call)).thenReturn(holdResult);
        when(mMockTransactionalService.onDisconnect(eq(call), any(DisconnectCause.class)))
                .thenReturn(disconnectResult);

        CompletableFuture<Boolean> testFuture = call.hold("test");
        holdResult.complete(false);

        assertTrue(testFuture.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        assertTrue(call.isLocallyDisconnecting());
        assertEquals(DisconnectCause.ERROR, call.getOverrideDisconnectCauseCode().getCode());
    }

    @Test
    @SmallTest
    public void testPutExtras() {
        // This specific test is mainly to encompass missing coverage for this Call#putExtras
        Call call = spy(createCall("1"));
        Bundle bundle = new Bundle();
        // Audio codec verification paths
        // Case (1)
        bundle.putInt(Connection.EXTRA_AUDIO_CODEC, Connection.AUDIO_CODEC_EVS_SWB);
        call.putConnectionServiceExtras(bundle);
        assertTrue(call.isHdPlus());
        // Case (2)
        bundle = new Bundle();
        bundle.putInt(Connection.EXTRA_AUDIO_CODEC, Connection.AUDIO_CODEC_EVS_FB);
        call.putConnectionServiceExtras(bundle);
        assertFalse(call.isHdPlus());
        // Additional coverage paths
        bundle.putInt(TelecomManager.EXTRA_CALL_NETWORK_TYPE, TelephonyManager.NETWORK_TYPE_NR);
        bundle.putString(Connection.EXTRA_ORIGINAL_CONNECTION_ID, "123");
        bundle.putInt(Connection.EXTRA_CALLER_NUMBER_VERIFICATION_STATUS,
                android.telecom.Connection.VERIFICATION_STATUS_PASSED);
        call.setCallerNumberVerificationStatus(Connection.VERIFICATION_STATUS_NOT_VERIFIED);
        bundle.putCharSequence(Connection.EXTRA_ANSWERING_DROPS_FG_CALL_APP_NAME, "testFgApp");
        bundle.putParcelable(Connection.EXTRA_REMOTE_PHONE_ACCOUNT_HANDLE,
                mock(PhoneAccountHandle.class));
        call.putConnectionServiceExtras(bundle);
    }

    @Test
    @SmallTest
    public void testRemoveExtras() {
        List<String> keys = List.of("key1", "key2", "key3");
        Call call = spy(createCall("1"));
        // Target: Remove with null extras
        call.setExtrasForTest(null);
        call.removeExtras(Call.SOURCE_INCALL_SERVICE, keys);
        // Target: Remove with no CS + TS
        call.setExtrasForTest(new Bundle());
        call.removeExtras(Call.SOURCE_INCALL_SERVICE, keys);
        // Target: Remove with non-null TS
        call.setTransactionServiceWrapper(mock(TransactionalServiceWrapper.class));
        call.removeExtras(Call.SOURCE_INCALL_SERVICE, keys);
    }

    @Test
    @SmallTest
    public void getRingtone() {
        Call call = spy(createCall("1"));
        call.getRingtone();
        CallerInfo callerInfoMock = mock(CallerInfo.class);
        callerInfoMock.contactRingtoneUri = TEST_ADDRESS;
        call.setCallerInfoForTesting(callerInfoMock);
        assertEquals(TEST_ADDRESS, call.getRingtone());
    }

    @Test
    @SmallTest
    public void testOnPostDialWait() {
        Call call = spy(createCall("1"));
        Call.Listener listener = mock(Call.Listener.class);
        String remaining = "123";
        call.addListener(listener);
        call.onPostDialWait(remaining);
        verify(listener).onPostDialWait(eq(call), eq(remaining));
    }

    @Test
    @SmallTest
    public void testOnPostDialChar() {
        Call call = spy(createCall("1"));
        Call.Listener listener = mock(Call.Listener.class);
        call.addListener(listener);
        call.onPostDialChar('a');
        verify(listener).onPostDialChar(eq(call), eq('a'));
    }

    @Test
    @SmallTest
    public void testPostDialContinue() {
        Call call = spy(createCall("1"));
        ConnectionServiceWrapper csWrapper = mock(ConnectionServiceWrapper.class);
        TransactionalServiceWrapper tsWrapper = mock(TransactionalServiceWrapper.class);
        call.postDialContinue(true);
        call.setConnectionService(csWrapper);
        call.postDialContinue(true);
        call.setTransactionServiceWrapper(tsWrapper);
        call.postDialContinue(true);
    }

    @Test
    @SmallTest
    public void testConferenceWith() {
        Call call = spy(createCall("1"));
        Call otherCall = spy(createCall("2"));
        ConnectionServiceWrapper csWrapper = mock(ConnectionServiceWrapper.class);
        TransactionalServiceWrapper tsWrapper = mock(TransactionalServiceWrapper.class);
        call.conferenceWith(otherCall);
        call.setConnectionService(csWrapper);
        call.conferenceWith(otherCall);
        call.setTransactionServiceWrapper(tsWrapper);
        call.conferenceWith(otherCall);
    }

    @Test
    @SmallTest
    public void testSplitFromConference() {
        Call call = spy(createCall("1"));
        ConnectionServiceWrapper csWrapper = mock(ConnectionServiceWrapper.class);
        TransactionalServiceWrapper tsWrapper = mock(TransactionalServiceWrapper.class);
        call.splitFromConference();
        call.setConnectionService(csWrapper);
        call.splitFromConference();
        call.setTransactionServiceWrapper(tsWrapper);
        call.splitFromConference();
    }

    @Test
    @SmallTest
    public void testMergeConferencePaths() {
        Call call = spy(createCall("1"));
        ConnectionServiceWrapper csWrapper = mock(ConnectionServiceWrapper.class);
        TransactionalServiceWrapper tsWrapper = mock(TransactionalServiceWrapper.class);
        call.mergeConference();
        call.setConnectionService(csWrapper);
        call.mergeConference();
        call.setTransactionServiceWrapper(tsWrapper);
        call.mergeConference();
    }

    @Test
    @SmallTest
    public void testSwapConferencePaths() {
        Call call = spy(createCall("1"));
        ConnectionServiceWrapper csWrapper = mock(ConnectionServiceWrapper.class);
        TransactionalServiceWrapper tsWrapper = mock(TransactionalServiceWrapper.class);
        call.swapConference();
        call.setConnectionService(csWrapper);
        call.swapConference();
        call.setTransactionServiceWrapper(tsWrapper);
        call.swapConference();
    }

    @Test
    @SmallTest
    public void testAddConferenceParticipants() {
        Call call = spy(createCall("1"));
        List<Uri> participants = List.of(mock(Uri.class));
        ConnectionServiceWrapper csWrapper = mock(ConnectionServiceWrapper.class);
        TransactionalServiceWrapper tsWrapper = mock(TransactionalServiceWrapper.class);
        // Null CS + TS
        call.addConferenceParticipants(participants);
        // CS path without CAPABILITY_ADD_PARTICIPANT
        call.setConnectionService(csWrapper);
        call.addConferenceParticipants(participants);
        // CS path with capability
        call.setConnectionProperties(Connection.CAPABILITY_ADD_PARTICIPANT);
        call.addConferenceParticipants(participants);
        // Non-null TS path
        call.setTransactionServiceWrapper(tsWrapper);
        call.addConferenceParticipants(participants);
    }

    @Test
    @SmallTest
    public void testPullExternalCall_NullWrapper() {
        Call call = spy(createCall("1"));
        ConnectionServiceWrapper csWrapper = mock(ConnectionServiceWrapper.class);
        TransactionalServiceWrapper tsWrapper = mock(TransactionalServiceWrapper.class);
        // CS null
        call.pullExternalCall();
        // TS non-null
        call.setTransactionServiceWrapper(tsWrapper);
        call.pullExternalCall();
        // CS non-null
        call.setTransactionServiceWrapper(null);
        call.setConnectionService(csWrapper);
        call.pullExternalCall();
    }

    @Test
    @SmallTest
    public void testSendCallEventBtQualityReport() {
        Call call = spy(createCall("1"));
        Call.Listener listener = mock(Call.Listener.class);
        call.addListener(listener);
        ConnectionServiceWrapper csWrapper = mock(ConnectionServiceWrapper.class);
        TransactionalServiceWrapper tsWrapper = mock(TransactionalServiceWrapper.class);
        // Test the TS path
        call.setTransactionServiceWrapper(tsWrapper);
        Bundle bundle = new Bundle();
        bundle.putParcelable(BluetoothCallQualityReport.EXTRA_BLUETOOTH_CALL_QUALITY_REPORT,
                mock(android.telecom.BluetoothCallQualityReport.class));
        call.sendCallEvent(BluetoothCallQualityReport.EVENT_BLUETOOTH_CALL_QUALITY_REPORT, bundle);
        verify(listener).onBluetoothCallQualityReport(eq(call),
                any(BluetoothCallQualityReport.class));
        // Test the CS path
        call.setConnectionService(csWrapper);
        call.sendCallEvent(BluetoothCallQualityReport.EVENT_BLUETOOTH_CALL_QUALITY_REPORT, bundle);
        verify(listener, times(2)).onBluetoothCallQualityReport(eq(call),
                any(BluetoothCallQualityReport.class));
    }

    @Test
    @SmallTest
    public void testSetParentCall() {
        Call call = spy(createCall("1"));
        // Try setting parent as self
        call.setParentCall(call);
        assertNull(call.getParentCall());
        Call parentCall = spy(createCall("2"));
        call.setParentCall(parentCall);
        assertEquals(parentCall, call.getParentCall());
        // Set a new parent and verify that the old parent removes the child dependency
        Call newParentCall = spy(createCall("3"));
        call.setParentCall(newParentCall);
        verify(parentCall).removeChildCall(eq(call));
    }

    @Test
    @SmallTest
    public void testSetConferenceableCalls() {
        Call call = spy(createCall("1"));
        Call.Listener listener = mock(Call.Listener.class);
        call.addListener(listener);
        List<Call> conferenceableCalls = List.of(spy(createCall("2")));
        call.setConferenceableCalls(conferenceableCalls);
        verify(listener).onConferenceableCallsChanged(call);
    }

    @Test
    @SmallTest
    public void testRemoveChild() {
        Call call = spy(createCall("1"));
        Call childCall = spy(createCall("2"));
        Call.Listener listener = mock(Call.Listener.class);
        call.addListener(listener);
        call.addChildCallForTesting(childCall);
        call.removeChildCall(childCall);
        verify(listener).onChildrenChanged(eq(call));
    }

    @Test
    @SmallTest
    public void testSendRttRequest() {
        ConnectionServiceWrapper csWrapper = mock(ConnectionServiceWrapper.class);
        TransactionalServiceWrapper tsWrapper = mock(TransactionalServiceWrapper.class);
        Call call = spy(createCall("1"));
        // The setters below are mainly for coverage purposes
        call.setRttMode(android.telecom.Call.RttCall.RTT_MODE_FULL);
        call.setRequestedToStartWithRtt();
        // Send rtt verification - CS
        call.setConnectionService(csWrapper);
        call.sendRttRequest();
        // Send rtt verification - TS
        call.setTransactionServiceWrapper(tsWrapper);
        call.sendRttRequest();
    }

    @Test
    @SmallTest
    public void testStopRtt() {
        ConnectionServiceWrapper csWrapper = mock(ConnectionServiceWrapper.class);
        TransactionalServiceWrapper tsWrapper = mock(TransactionalServiceWrapper.class);
        Call call = spy(createCall("1"));
        // Stop rtt verification no CS nor TS
        call.setTransactionServiceWrapper(null);
        call.stopRtt();
        // Stop rtt with CS
        call.setConnectionService(csWrapper);
        call.stopRtt();
        // Stop rtt with TS
        call.setTransactionServiceWrapper(tsWrapper);
        call.stopRtt();
    }

    @Test
    @SmallTest
    public void testOnRttConnectionFailure() {
        Call call = spy(createCall("1"));
        Call.Listener listener = mock(Call.Listener.class);
        call.addListener(listener);
        call.onRttConnectionFailure(0 /* reason */);
        verify(listener).onRttInitiationFailure(eq(call), eq(0));
    }

    @Test
    @SmallTest
    public void testOnRemoteRttRequest() {
        Call call = spy(createCall("1"));
        Call.Listener listener = mock(Call.Listener.class);
        call.addListener(listener);
        // Path for non-RTT call
        call.onRemoteRttRequest();
        verify(listener).onRemoteRttRequest(eq(call), anyInt());
        // Path for RTT call
        call.setConnectionCapabilities(Connection.PROPERTY_IS_RTT);
        call.onRemoteRttRequest();
    }

    @Test
    @SmallTest
    public void testHandleRttRequestResponse() {
        ConnectionServiceWrapper csWrapper = mock(ConnectionServiceWrapper.class);
        TransactionalServiceWrapper tsWrapper = mock(TransactionalServiceWrapper.class);
        Call call = spy(createCall("1"));
        // Undefined request id
        call.handleRttRequestResponse(0, true);
        // Request id mismatch
        call.setPendingRttRequestId(1);
        call.handleRttRequestResponse(0, true);
        // Accept -> false
        call.setConnectionService(csWrapper);
        call.handleRttRequestResponse(1, false);
        // Accept -> true
        call.handleRttRequestResponse(1, true);
        // Request with TS
        call.setTransactionServiceWrapper(tsWrapper);
        call.handleRttRequestResponse(1, true);
    }

    @Test
    @SmallTest
    public void testInCallToCsRttPipeForInCall() {
        Call call = spy(createCall("1"));
        ParcelFileDescriptor [] fileDescriptor = new ParcelFileDescriptor[2];
        fileDescriptor[1] = new ParcelFileDescriptor(mock(FileDescriptor.class));
        // Verify the null case
        assertNull(call.getInCallToCsRttPipeForInCall());
        call.setInCallToCsRttPipeForTesting(fileDescriptor);
        assertNotNull(call.getInCallToCsRttPipeForInCall());
    }

    @Test
    @SmallTest
    public void testGetStateFromConnectionState() {
        assertEquals(CallState.CONNECTING, Call.getStateFromConnectionState(
                Connection.STATE_INITIALIZING));
        assertEquals(CallState.PULLING, Call.getStateFromConnectionState(
                Connection.STATE_PULLING_CALL));
        assertEquals(CallState.DISCONNECTED, Call.getStateFromConnectionState(
                Connection.STATE_DISCONNECTED));
        assertEquals(CallState.ON_HOLD, Call.getStateFromConnectionState(
                Connection.STATE_HOLDING));
        assertEquals(CallState.RINGING, Call.getStateFromConnectionState(
                Connection.STATE_RINGING));
        assertEquals(CallState.DISCONNECTED, Call.getStateFromConnectionState(
                Connection.STATE_SIMULATED_RINGING));
    }

    @Test
    @SmallTest
    public void testRequestHandover() {
        Call call = spy(createCall("1"));
        Call.Listener listener = mock(Call.Listener.class);
        call.addListener(listener);
        PhoneAccountHandle mockPhoneAccountHandle = mock(PhoneAccountHandle.class);
        call.handoverTo(mockPhoneAccountHandle, VideoProfile.STATE_AUDIO_ONLY, null);
        verify(listener).onHandoverRequested(eq(call), any(PhoneAccountHandle.class), anyInt(),
                nullable(Bundle.class), anyBoolean());
    }

    @Test
    @SmallTest
    public void testMaybeEnableSpeakerForVideoUpgrade() {
        Call call = spy(createCall("1"));
        when(mMockCallsManager.isSpeakerphoneAutoEnabledForVideoCalls(anyInt()))
                .thenReturn(false);
        call.maybeEnableSpeakerForVideoUpgrade(VideoProfile.STATE_BIDIRECTIONAL);
        verify(mMockCallsManager, never()).setAudioRoute(anyInt(), anyInt(), anyString());
        when(mMockCallsManager.isSpeakerphoneAutoEnabledForVideoCalls(anyInt()))
                .thenReturn(true);
        call.maybeEnableSpeakerForVideoUpgrade(VideoProfile.STATE_BIDIRECTIONAL);
        verify(mMockCallsManager).setAudioRoute(anyInt(), anyInt(), nullable(String.class));
    }

    @Test
    @SmallTest
    public void testRemappedCallDirection() {
        assertEquals(Call.CALL_DIRECTION_INCOMING, Call.getRemappedCallDirection(
                android.telecom.Call.Details.DIRECTION_INCOMING));
        assertEquals(Call.CALL_DIRECTION_OUTGOING, Call.getRemappedCallDirection(
                android.telecom.Call.Details.DIRECTION_OUTGOING));
        assertEquals(Call.CALL_DIRECTION_UNDEFINED, Call.getRemappedCallDirection(
                android.telecom.Call.Details.DIRECTION_UNKNOWN));
        assertEquals(Call.CALL_DIRECTION_UNDEFINED, Call.getRemappedCallDirection(-2));
    }

    @Test
    @SmallTest
    public void testDiagnosticFutureException() {
        Call call = spy(createCall("1"));
        CompletableFuture<Boolean> future = call.initializeDiagnosticCompleteFuture(100);
        assertNotNull(future);
        future.completeExceptionally(new Throwable());
    }

    @Test
    @SmallTest
    public void testCleanup() {
        Call call = spy(createCall("1"));
        CompletableFuture<Boolean> future = call.initializeDiagnosticCompleteFuture(100);
        assertNotNull(call.getDiagnosticCompleteFuture());
        call.cleanup();
        assertNull(call.getDiagnosticCompleteFuture());
        future.complete(true);
    }

    @Test
    public void testCallRemovalFuture() {
        Call call = spy(createCall("1"));
        CompletableFuture<Void> future = new CompletableFuture<>();
        call.setRemovalFuture(future);
        assertTrue(call.isRemovalPending());
        future.complete(null);
        assertFalse(call.isRemovalPending());
    }

    @Test
    @SmallTest
    public void testWaitForBtIcs() throws ExecutionException, InterruptedException {
        Call call = spy(createCall("1"));
        CompletableFuture<Boolean> btFuture = CompletableFuture.completedFuture(true);
        call.setBtIcsFuture(btFuture);
        call.waitForBtIcs();
        // Now verify the exception handling
        CompletableFuture<Boolean> btFutureMock = mock(CompletableFuture.class);
        call.setBtIcsFuture(btFutureMock);
        doThrow(new InterruptedException()).when(btFutureMock).get();
        call.waitForBtIcs();
    }

    @Test
    @SmallTest
    public void testCallerInfoOnContactPhotoQuery() {
        Call call = createCall("1");
        CallerInfo info = new CallerInfo();
        info.setName("name");
        info.setPhoneNumber("number");
        info.cachedPhoto = new ColorDrawable();
        info.cachedPhotoIcon = Bitmap.createBitmap(24, 24, Bitmap.Config.ALPHA_8);

        ArgumentCaptor<CallerInfoLookupHelper.OnQueryCompleteListener> listenerCaptor =
                ArgumentCaptor.forClass(CallerInfoLookupHelper.OnQueryCompleteListener.class);
        verify(mMockCallerInfoLookupHelper).startLookup(any(), listenerCaptor.capture());
        listenerCaptor.getValue().onContactPhotoQueryComplete(call.getHandle(), info);

        assertEquals(info, call.getCallerInfo());
        assertEquals(info.getName(), call.getName());
        assertEquals(info.getPhoneNumber(), call.getPhoneNumber());
        assertEquals(info.cachedPhoto, call.getPhoto());
        assertEquals(info.cachedPhotoIcon, call.getPhotoIcon());
        assertEquals(call.getHandle(), call.getContactUri());
    }

    @Test
    @SmallTest
    public void testHandleOverrideDisconnectMessage() {
        Call call = spy(createCall("1"));
        when(call.isDisconnectHandledViaFuture()).thenReturn(false);
        assertEquals(DisconnectCause.UNKNOWN, call.getOverrideDisconnectCauseCode().getCode());
        CompletableFuture<Boolean> future = call.initializeDiagnosticCompleteFuture(100);
        when(call.isDisconnectHandledViaFuture()).thenReturn(true);
        call.handleOverrideDisconnectMessage("test");
        assertEquals(DisconnectCause.ERROR, call.getOverrideDisconnectCauseCode().getCode());
        future.complete(true);
    }

    @Test
    @SmallTest
    public void testSetStateIllegalAudioProcessing() {
        when(mFeatureFlags.preventIllegalAudioProcessingExit()).thenReturn(true);
        Call call = spy(createCall("1"));
        call.setState(CallState.AUDIO_PROCESSING, "1");
        call.setIsProperlyExitingAudioProcessing(false);
        call.setAudioProcessingUseCase(
                android.telecom.Call.AUDIO_PROCESSING_USE_CASE_CALL_SCREENING);
        assertFalse(call.setState(CallState.DIALING, "1"));
    }

    @Test
    @SmallTest
    public void testSetStateDisconnecting() {
        Call call = spy(createCall("1"));
        CreateConnectionProcessor mockProcessor = mock(CreateConnectionProcessor.class);
        when(mockProcessor.isProcessingComplete()).thenReturn(true);
        when(mockProcessor.hasMorePhoneAccounts()).thenReturn(true);
        CompletableFuture<Void> removalFuture = new CompletableFuture<>();
        call.setCreateConnectionProcessorForTesting(mockProcessor);
        call.setIsEmergencyCall(true);
        call.setDisconnectCause(new DisconnectCause(DisconnectCause.ERROR));
        CompletableFuture<Boolean> future = call.initializeDiagnosticCompleteFuture(500);
        call.setRemovalFuture(removalFuture);
        assertFalse(call.setState(CallState.DISCONNECTED, "1"));
        future.complete(true);
    }

    @Test
    @SmallTest
    public void testCallStateProperties() {
        Call call = createCall("1");
        Call.Listener listener = mock(Call.Listener.class);
        call.addListener(listener);
        Uri testUri = Uri.parse("123");
        String testNumber = "123";
        String testDisplayName = "testDisplayName";
        int testPresentationNumber = 12345;
        CallerInfo info = new CallerInfo();
        info.contactExists = true;
        info.lookupKey = null;
        PhoneAccountHandle mockPhoneAccountHandle = mock(PhoneAccountHandle.class);
        Call handoverDestCall = mock(Call.class);
        Call handoverSourceCall = mock(Call.class);
        CharSequence appName = "testApp";
        String connectionId = "123";
        CompletableFuture<Void> future = new CompletableFuture<>();

        assertFalse(call.hadChildren());
        call.setParticipants(List.of());
        assertTrue(call.getParticipants().isEmpty());
        call.clearPostDialDigits();
        call.setViaNumber(testNumber);
        assertEquals(testNumber, call.getViaNumber());
        call.setCallerDisplayName(testDisplayName, testPresentationNumber);
        assertEquals(testDisplayName, call.getCallerDisplayName());
        assertEquals(testPresentationNumber, call.getCallerDisplayNamePresentation());
        call.setCallerInfoForTesting(info);
        assertNull(call.getContactUri());
        call.setContactPhotoUri(testUri);
        call.setRemotePhoneAccountHandle(mockPhoneAccountHandle);
        assertEquals(mockPhoneAccountHandle, call.getRemotePhoneAccountHandle());
        assertEquals(call.getDelegatePhoneAccountHandle(), call.getRemotePhoneAccountHandle());
        assertFalse(call.isUsingCallRecordingTone());
        call.setHandoverDestinationCall(handoverDestCall);
        assertEquals(handoverDestCall, call.getHandoverDestinationCall());
        call.setHandoverSourceCall(handoverSourceCall);
        assertEquals(handoverSourceCall, call.getHandoverSourceCall());
        call.setConnectElapsedTimeMillis(100L);
        call.setAudioProcessingRequestingApp(appName);
        assertEquals(appName.toString(), call.getAudioProcessingRequestingApp().toString());
        call.setState(CallState.ANSWERED, "1");
        assertTrue(call.isAnswered("test"));
        call.setState(CallState.NEW, "1");
        assertFalse(call.isAnswered("test"));
        call.setOriginalConnectionId(connectionId);
        assertEquals(connectionId, call.getOriginalConnectionId());
        // Not much we can verify for this
        call.sendDeviceToDeviceMessage(0, 1);
        call.setDisconnectFuture(future);
        assertFalse(call.getDisconnectFuture().isDone());
        future.complete(null);
        assertTrue(call.getDisconnectFuture().isDone());
        call.setVoipContactLookupUri(testUri);
        assertEquals(testUri, call.getVoipContactLookupUri());
        call.setBulkStateUpdateInProgress(true);
        assertTrue(call.isBulkStateUpdateInProgress());
        call.setNewOutgoingCallIntentBroadcastIsDone();
        assertTrue(call.isNewOutgoingCallIntentBroadcastDone());
        call.setIsGroupCall(true);
        assertTrue(call.isGroupCall());
        call.setIsTransactionalLogExcluded(true);
        assertTrue(call.isTransactionalLogExcluded());
        call.setIsVoipAudioMode(true);
        verify(listener).onIsVoipAudioModeChanged(call);
    }

    @Test
    @SmallTest
    public void testGetTargetPhoneAccountLabel() {
        Call call = createCall("1");
        PhoneAccountRegistrar mockPhoneAccountRegistrar = mock(PhoneAccountRegistrar.class);
        PhoneAccount mockPhoneAccount = mock(PhoneAccount.class);
        String testLabel = "testLabel";
        when(mockPhoneAccount.getLabel()).thenReturn(testLabel);
        call.setTargetPhoneAccount(null);
        assertNull(call.getTargetPhoneAccountLabel());
        call.setTargetPhoneAccount(SIM_1_HANDLE);
        when(mMockCallsManager.getPhoneAccountRegistrar()).thenReturn(mockPhoneAccountRegistrar);
        when(mockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(null);
        assertNull(call.getTargetPhoneAccountLabel());
        when(mockPhoneAccountRegistrar.getPhoneAccountUnchecked(any(PhoneAccountHandle.class)))
                .thenReturn(mockPhoneAccount);
        assertEquals(testLabel.toString(), call.getTargetPhoneAccountLabel().toString());
    }

    @Test
    @SmallTest
    public void testReplaceConnectionService() {
        Call call = createCall("1");
        ConnectionServiceWrapper service1 = mock(ConnectionServiceWrapper.class);
        ConnectionServiceWrapper service2 = mock(ConnectionServiceWrapper.class);
        call.setConnectionService(service1);
        call.replaceConnectionService(service2);
        assertEquals(service2, call.getService());
    }

    @Test
    @SmallTest
    public void testDtmfTone() {
        Call call = createCall("1");
        ConnectionServiceWrapper service = mock(ConnectionServiceWrapper.class);
        call.setConnectionService(service);
        call.playDtmfTone('a');
        call.stopDtmfTone();
    }

    @Test
    @SmallTest
    public void testSilence() {
        Call call = createCall("1");
        ConnectionServiceWrapper service = mock(ConnectionServiceWrapper.class);
        call.silence();
        call.setConnectionService(service);
        call.silence();
    }

    private Call createCall(String id) {
        return createCall(id, Call.CALL_DIRECTION_UNDEFINED);
    }

    private Call createCall(String id, int callDirection) {
        return createCall(id, callDirection, TEST_ADDRESS);
    }

    @Test
    @SmallTest
    public void testDetailsEquals_withIntents() {
        Bundle extras1 = new Bundle();
        Intent intent1 = new Intent("action");
        intent1.putExtra("extra_key", "extra_value");
        extras1.putParcelable("intent_key", intent1);

        Bundle extras2 = new Bundle();
        Intent intent2 = new Intent("action");
        intent2.putExtra("extra_key", "different_value"); // filterEquals ignores extras
        extras2.putParcelable("intent_key", intent2);

        Bundle extras3 = new Bundle();
        Intent intent3 = new Intent("different_action");
        extras3.putParcelable("intent_key", intent3);

        Details details1 = new Details(STATE_NEW, "1", Uri.parse("tel:123"), 0, "name",
                0, null, 0, 0, null, 0, null, 0, null, extras1,
                null, 0, null, 0, 0, null, null);
        Details details2 = new Details(STATE_NEW, "1", Uri.parse("tel:123"), 0, "name",
                0, null, 0, 0, null, 0, null, 0, null, extras2,
                null, 0, null, 0, 0, null, null);
        Details details3 = new Details(STATE_NEW, "1", Uri.parse("tel:123"), 0, "name",
                0, null, 0, 0, null, 0, null, 0, null, extras3,
                null, 0, null, 0, 0, null, null);

        assertEquals(details1, details2);
        assertNotEquals(details1, details3);
    }

    @Test
    @SmallTest
    public void testNetworkIdentifiedEmergencyCallUpdateProperty() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        call.setConnectionProperties(
                Connection.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL);
        // Setting the NIE connection property should make the call
        // an emergency call.
        assertTrue(call.isEmergencyCall());
    }

    private Call createCall(String id, int callDirection, Uri address) {
        return new Call(
                id,
                mContext,
                mMockCallsManager,
                mLock,
                null,
                mMockPhoneNumberUtilsAdapter,
                address,
                null /* GatewayInfo */,
                null,
                SIM_1_HANDLE,
                callDirection,
                false,
                false,
                mMockClockProxy,
                mMockToastProxy,
                mFeatureFlags);
    }

    private void processMaybeLoadCannedSmsResponsesCallback(boolean onResult) {
        Call call = spy(createCall("1", Call.CALL_DIRECTION_INCOMING));
        call.setState(CallState.RINGING, "1");
        when(mMockResources.getBoolean(R.bool.skip_loading_canned_text_response))
                .thenReturn(false);
        TelephonyManager mMockTelephonyManager = mock(TelephonyManager.class);
        when(mContext.getSystemService(TelephonyManager.class))
                .thenReturn(mMockTelephonyManager);
        when(mMockTelephonyManager.getAndUpdateDefaultRespondViaMessageApplication())
                .thenReturn(mock(ComponentName.class));
        RespondViaSmsManager mockSmsManager = mock(RespondViaSmsManager.class);
        when(mMockCallsManager.getRespondViaSmsManager()).thenReturn(mockSmsManager);

        // Pre-condition for maybeLoadCannedSmsResponses
        call.setConnectionCapabilities(Connection.CAPABILITY_RESPOND_VIA_TEXT);

        Call.Listener listener = mock(Call.Listener.class);
        call.addListener(listener);
        call.maybeLoadCannedSmsResponses();

        // Target: anonymous Response.onResult loop
        ArgumentCaptor<CallsManager.Response<Void, List<String>>> captor =
                ArgumentCaptor.forClass(CallsManager.Response.class);
        verify(mockSmsManager).loadCannedTextMessages(captor.capture(), eq(mContext));

        if (onResult) {
            List<String> responses = List.of("Message 1", "Message 2");
            captor.getValue().onResult(null, responses);
            verify(listener).onCannedSmsResponsesLoaded(call);
        } else {
            captor.getValue().onError(null, -1, "");
            verify(listener, never()).onCannedSmsResponsesLoaded(call);
        }
    }
}
