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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.Uri;
import android.os.Bundle;
import android.telecom.CallAudioState;
import android.telecom.PhoneAccountHandle;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallEndpointController;
import com.android.server.telecom.CallIdMapper;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.InCallAdapter;
import com.android.server.telecom.TelecomSystem;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.Collections;

public class InCallAdapterTest extends TelecomTestCase {

    private static final String TEST_CALL_ID = "test_call_id";
    private static final String OTHER_CALL_ID = "other_call_id";
    private static final String OWNER_PACKAGE = "com.test.package";

    @Mock private CallsManager mCallsManager;
    @Mock private CallIdMapper mCallIdMapper;
    @Mock private Call mCall;
    @Mock private Call mOtherCall;
    @Mock private TelecomSystem.SyncRoot mLock;
    @Mock private CallEndpointController mCallEndpointController;

    private InCallAdapter mInCallAdapter;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        when(mCallsManager.getCallEndpointController()).thenReturn(mCallEndpointController);
        mInCallAdapter = new InCallAdapter(mCallsManager, mCallIdMapper,
                mLock, OWNER_PACKAGE);
        when(mCallIdMapper.getCall(TEST_CALL_ID)).thenReturn(mCall);
        when(mCallIdMapper.getCall(OTHER_CALL_ID)).thenReturn(mOtherCall);
    }

    @Test
    public void testAnswerCall() {
        mInCallAdapter.answerCall(TEST_CALL_ID, 0);
        verify(mCallsManager).answerCall(mCall, 0);
    }

    @Test
    public void testDeflectCall() {
        Uri address = Uri.parse("tel:12345");
        mInCallAdapter.deflectCall(TEST_CALL_ID, address);
        verify(mCallsManager).deflectCall(mCall, address);
    }

    @Test
    public void testRejectCall() {
        when(mCallsManager.isReplyWithSmsAllowed(anyInt(), any())).thenReturn(true);
        mInCallAdapter.rejectCall(TEST_CALL_ID, true, "message");
        verify(mCallsManager).rejectCall(mCall, true, "message");
    }

    @Test
    public void testRejectCallWithReason() {
        mInCallAdapter.rejectCallWithReason(TEST_CALL_ID, 1);
        verify(mCallsManager).rejectCall(mCall, 1);
    }

    @Test
    public void testTransferCall() {
        Uri address = Uri.parse("tel:54321");
        mInCallAdapter.transferCall(TEST_CALL_ID, address, false);
        verify(mCallsManager).transferCall(mCall, address, false);
    }

    @Test
    public void testConsultativeTransfer() {
        mInCallAdapter.consultativeTransfer(TEST_CALL_ID, OTHER_CALL_ID);
        verify(mCallsManager).transferCall(mCall, mOtherCall);
    }

    @Test
    public void testPlayAndStopDtmfTone() {
        mInCallAdapter.playDtmfTone(TEST_CALL_ID, '1');
        verify(mCallsManager).playDtmfTone(mCall, '1');
        mInCallAdapter.stopDtmfTone(TEST_CALL_ID);
        verify(mCallsManager).stopDtmfTone(mCall);
    }

    @Test
    public void testPostDialContinue() {
        mInCallAdapter.postDialContinue(TEST_CALL_ID, true);
        verify(mCallsManager).postDialContinue(mCall, true);
    }

    @Test
    public void testDisconnectCall() {
        mInCallAdapter.disconnectCall(TEST_CALL_ID);
        verify(mCallsManager).disconnectCall(mCall);
    }

    @Test
    public void testHoldAndUnholdCall() {
        mInCallAdapter.holdCall(TEST_CALL_ID);
        verify(mCallsManager).holdCall(mCall);
        mInCallAdapter.unholdCall(TEST_CALL_ID);
        verify(mCallsManager).unholdCall(mCall);
    }

    @Test
    public void testPhoneAccountSelected() {
        PhoneAccountHandle pah = mock(PhoneAccountHandle.class);
        mInCallAdapter.phoneAccountSelected(TEST_CALL_ID, pah, true);
        verify(mCallsManager).phoneAccountSelected(mCall, pah, true);
    }

    @Test
    public void testMute() {
        mInCallAdapter.mute(true);
        verify(mCallsManager).mute(true);
    }

    @Test
    public void testSetAudioRoute() {
        mInCallAdapter.setAudioRoute(CallAudioState.ROUTE_SPEAKER, null);
        verify(mCallsManager).setAudioRoute(anyInt(), eq(CallAudioState.ROUTE_SPEAKER), eq(null));
    }

    @Test
    public void testConference() {
        mInCallAdapter.conference(TEST_CALL_ID, OTHER_CALL_ID);
        verify(mCallsManager).conference(mCall, mOtherCall);
    }

    @Test
    public void testSplitFromConference() {
        mInCallAdapter.splitFromConference(TEST_CALL_ID);
        verify(mCall).splitFromConference();
    }

    @Test
    public void testMergeAndSwapConference() {
        mInCallAdapter.mergeConference(TEST_CALL_ID);
        verify(mCall).mergeConference();
        mInCallAdapter.swapConference(TEST_CALL_ID);
        verify(mCall).swapConference();
    }

    @Test
    public void testAddConferenceParticipants() {
        Uri participant = Uri.parse("tel:5551212");
        mInCallAdapter.addConferenceParticipants(TEST_CALL_ID,
                Collections.singletonList(participant));
        verify(mCall).addConferenceParticipants(Collections.singletonList(participant));
    }

    @Test
    public void testPullExternalCall() {
        mInCallAdapter.pullExternalCall(TEST_CALL_ID);
        verify(mCall).pullExternalCall();
    }

    @Test
    public void testSendCallEvent() {
        Bundle extras = new Bundle();
        mInCallAdapter.sendCallEvent(TEST_CALL_ID, "event", 0, extras);
        verify(mCall).sendCallEvent("event", extras);
    }

    @Test
    public void testPutAndRemoveExtras() {
        Bundle extras = new Bundle();
        mInCallAdapter.putExtras(TEST_CALL_ID, extras);
        verify(mCall).putInCallServiceExtras(extras, OWNER_PACKAGE);

        mInCallAdapter.removeExtras(TEST_CALL_ID, Collections.singletonList("key"));
        verify(mCall).removeExtras(Call.SOURCE_INCALL_SERVICE, Collections.singletonList("key"));
    }

    @Test
    public void testTurnOnAndOffProximitySensor() {
        mInCallAdapter.turnOnProximitySensor();
        verify(mCallsManager).turnOnProximitySensor();
        mInCallAdapter.turnOffProximitySensor(true);
        verify(mCallsManager).turnOffProximitySensor(true);
    }

    @Test
    public void testRttRequests() {
        mInCallAdapter.sendRttRequest(TEST_CALL_ID);
        verify(mCall).sendRttRequest();

        mInCallAdapter.respondToRttRequest(TEST_CALL_ID, 1, true);
        verify(mCall).handleRttRequestResponse(1, true);

        mInCallAdapter.stopRtt(TEST_CALL_ID);
        verify(mCall).stopRtt();

        mInCallAdapter.setRttMode(TEST_CALL_ID, 1);
        verify(mCall).setRttMode(1);
    }
}
