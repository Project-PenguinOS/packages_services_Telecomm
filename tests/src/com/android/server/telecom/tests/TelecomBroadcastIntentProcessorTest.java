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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.telecom.VideoProfile;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.MissedCallNotifier;
import com.android.server.telecom.TelecomBroadcastIntentProcessor;
import com.android.server.telecom.ui.DisconnectedCallNotifier;
import com.android.server.telecom.ui.IncomingCallNotifier;
import com.android.server.telecom.ui.UiConstants;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

@RunWith(JUnit4.class)
public class TelecomBroadcastIntentProcessorTest extends TelecomTestCase {

    @Mock
    private CallsManager mCallsManager;
    @Mock
    private MissedCallNotifier mMissedCallNotifier;
    @Mock
    private DisconnectedCallNotifier mDisconnectedCallNotifier;
    @Mock
    private IncomingCallNotifier mIncomingCallNotifier;

    private TelecomBroadcastIntentProcessor mProcessor;
    private final UserHandle mUserHandle = UserHandle.of(10);

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mProcessor = new TelecomBroadcastIntentProcessor(mContext, mCallsManager, mFeatureFlags);
        when(mCallsManager.getMissedCallNotifier()).thenReturn(mMissedCallNotifier);
        when(mCallsManager.getDisconnectedCallNotifier()).thenReturn(mDisconnectedCallNotifier);
        when(mCallsManager.getIncomingCallNotifier()).thenReturn(mIncomingCallNotifier);
    }

    @SmallTest
    @Test
    public void testActionSendSmsFromNotification() {
        Intent intent = new Intent(
                TelecomBroadcastIntentProcessor.ACTION_SEND_SMS_FROM_NOTIFICATION);
        intent.putExtra(TelecomBroadcastIntentProcessor.EXTRA_USERHANDLE, mUserHandle);
        intent.setData(Uri.parse("tel:12345"));

        Context userContext = mock(Context.class);
        PackageManager packageManager = mock(PackageManager.class);
        when(mContext.createContextAsUser(mUserHandle, 0)).thenReturn(userContext);
        when(userContext.getPackageManager()).thenReturn(packageManager);

        List<ResolveInfo> activities = new ArrayList<>();
        activities.add(new ResolveInfo());
        when(packageManager.queryIntentActivities(any(Intent.class), anyInt()))
                .thenReturn(activities);

        mProcessor.processIntent(intent);

        verify(mMissedCallNotifier).clearMissedCalls(mUserHandle);
        verify(mContext).sendBroadcastAsUser(any(Intent.class), eq(UserHandle.ALL), isNull(),
                any(Bundle.class));
        verify(mContext).startActivityAsUser(any(Intent.class), eq(mUserHandle));
    }

    @SmallTest
    @Test
    public void testActionCallBackFromNotification() {
        Intent intent = new Intent(
                TelecomBroadcastIntentProcessor.ACTION_CALL_BACK_FROM_NOTIFICATION);
        intent.putExtra(TelecomBroadcastIntentProcessor.EXTRA_USERHANDLE, mUserHandle);
        intent.setData(Uri.parse("tel:12345"));

        mProcessor.processIntent(intent);

        verify(mMissedCallNotifier).clearMissedCalls(mUserHandle);
        verify(mContext).sendBroadcastAsUser(any(Intent.class), eq(UserHandle.ALL), isNull(),
                any(Bundle.class));
        verify(mContext).startActivityAsUser(any(Intent.class), eq(mUserHandle));
    }

    @SmallTest
    @Test
    public void testActionClearMissedCalls() {
        Intent intent = new Intent(TelecomBroadcastIntentProcessor.ACTION_CLEAR_MISSED_CALLS);
        intent.putExtra(TelecomBroadcastIntentProcessor.EXTRA_USERHANDLE, mUserHandle);

        mProcessor.processIntent(intent);

        verify(mMissedCallNotifier).clearMissedCalls(mUserHandle);
    }

    @SmallTest
    @Test
    public void testActionDisconnectedSendSmsFromNotification() {
        Intent intent = new Intent(
                TelecomBroadcastIntentProcessor.ACTION_DISCONNECTED_SEND_SMS_FROM_NOTIFICATION);
        intent.putExtra(TelecomBroadcastIntentProcessor.EXTRA_USERHANDLE, mUserHandle);
        intent.setData(Uri.parse("tel:12345"));

        Context userContext = mock(Context.class);
        PackageManager packageManager = mock(PackageManager.class);
        when(mContext.createContextAsUser(mUserHandle, 0)).thenReturn(userContext);
        when(userContext.getPackageManager()).thenReturn(packageManager);

        List<ResolveInfo> activities = new ArrayList<>();
        activities.add(new ResolveInfo());
        when(packageManager.queryIntentActivities(any(Intent.class), anyInt()))
                .thenReturn(activities);

        mProcessor.processIntent(intent);

        verify(mDisconnectedCallNotifier).clearNotification(mUserHandle);
        verify(mContext).sendBroadcastAsUser(any(Intent.class), eq(UserHandle.ALL), isNull(),
                any(Bundle.class));
        verify(mContext).startActivityAsUser(any(Intent.class), eq(mUserHandle));
    }

    @SmallTest
    @Test
    public void testActionDisconnectedCallBackFromNotification() {
        Intent intent = new Intent(
                TelecomBroadcastIntentProcessor.ACTION_DISCONNECTED_CALL_BACK_FROM_NOTIFICATION);
        intent.putExtra(TelecomBroadcastIntentProcessor.EXTRA_USERHANDLE, mUserHandle);
        intent.setData(Uri.parse("tel:12345"));

        mProcessor.processIntent(intent);

        verify(mDisconnectedCallNotifier).clearNotification(mUserHandle);
        verify(mContext).sendBroadcastAsUser(any(Intent.class), eq(UserHandle.ALL), isNull(),
                any(Bundle.class));
        verify(mContext).startActivityAsUser(any(Intent.class), eq(mUserHandle));
    }

    @SmallTest
    @Test
    public void testAnswerFromNotification() {
        Call call = mock(Call.class);
        when(call.getVideoState()).thenReturn(VideoProfile.STATE_AUDIO_ONLY);
        when(mIncomingCallNotifier.getIncomingCall()).thenReturn(call);

        Intent intent = new Intent(TelecomBroadcastIntentProcessor.ACTION_ANSWER_FROM_NOTIFICATION);

        mProcessor.processIntent(intent);

        verify(mCallsManager).answerCall(eq(call), eq(VideoProfile.STATE_AUDIO_ONLY), anyInt());
    }

    @SmallTest
    @Test
    public void testRejectFromNotification() {
        Call call = mock(Call.class);
        when(mIncomingCallNotifier.getIncomingCall()).thenReturn(call);

        Intent intent = new Intent(TelecomBroadcastIntentProcessor.ACTION_REJECT_FROM_NOTIFICATION);

        mProcessor.processIntent(intent);

        verify(mCallsManager).rejectCall(eq(call), eq(false), isNull());
    }

    @SmallTest
    @Test
    public void testProceedWithCall() {
        String callId = "test_call_id";
        Intent intent = new Intent(TelecomBroadcastIntentProcessor.ACTION_PROCEED_WITH_CALL);
        intent.putExtra(UiConstants.EXTRA_OUTGOING_CALL_ID, callId);

        mProcessor.processIntent(intent);

        verify(mCallsManager).confirmPendingCall(callId);
    }

    @SmallTest
    @Test
    public void testCancelCall() {
        String callId = "test_call_id";
        Intent intent = new Intent(TelecomBroadcastIntentProcessor.ACTION_CANCEL_CALL);
        intent.putExtra(UiConstants.EXTRA_OUTGOING_CALL_ID, callId);

        mProcessor.processIntent(intent);

        verify(mCallsManager).cancelPendingCall(callId);
    }

    @SmallTest
    @Test
    public void testHangupCall() {
        String callId = "test_call_id";
        Call call = mock(Call.class);
        when(mCallsManager.getCall(callId)).thenReturn(call);

        Intent intent = new Intent(TelecomBroadcastIntentProcessor.ACTION_HANGUP_CALL);
        intent.setData(Uri.fromParts("callid", callId, null));

        mProcessor.processIntent(intent);

        verify(mCallsManager).disconnectCall(call);
    }

    @SmallTest
    @Test
    public void testStopStreaming() {
        String callId = "test_call_id";
        Call call = mock(Call.class);
        when(mCallsManager.getCall(callId)).thenReturn(call);

        Intent intent = new Intent(TelecomBroadcastIntentProcessor.ACTION_STOP_STREAMING);
        intent.setData(Uri.fromParts("callid", callId, null));

        mProcessor.processIntent(intent);

        verify(mCallsManager).stopCallStreaming(call);
    }

    @SmallTest
    @Test
    public void testPlaceRedirectedCall() {
        String callId = "test_call_id";
        Intent intent = new Intent(TelecomBroadcastIntentProcessor.ACTION_PLACE_REDIRECTED_CALL);
        intent.putExtra(TelecomBroadcastIntentProcessor.EXTRA_REDIRECTION_OUTGOING_CALL_ID, callId);

        mProcessor.processIntent(intent);

        verify(mCallsManager).processRedirectedOutgoingCallAfterUserInteraction(eq(callId),
                eq(TelecomBroadcastIntentProcessor.ACTION_PLACE_REDIRECTED_CALL));
    }

    @SmallTest
    @Test
    public void testPlaceUnredirectedCall() {
        String callId = "test_call_id";
        Intent intent = new Intent(TelecomBroadcastIntentProcessor.ACTION_PLACE_UNREDIRECTED_CALL);
        intent.putExtra(TelecomBroadcastIntentProcessor.EXTRA_REDIRECTION_OUTGOING_CALL_ID, callId);

        mProcessor.processIntent(intent);

        verify(mCallsManager).processRedirectedOutgoingCallAfterUserInteraction(eq(callId),
                eq(TelecomBroadcastIntentProcessor.ACTION_PLACE_UNREDIRECTED_CALL));
    }

    @SmallTest
    @Test
    public void testCancelRedirectedCall() {
        String callId = "test_call_id";
        Intent intent = new Intent(TelecomBroadcastIntentProcessor.ACTION_CANCEL_REDIRECTED_CALL);
        intent.putExtra(TelecomBroadcastIntentProcessor.EXTRA_REDIRECTION_OUTGOING_CALL_ID, callId);

        mProcessor.processIntent(intent);

        verify(mCallsManager).processRedirectedOutgoingCallAfterUserInteraction(eq(callId),
                eq(TelecomBroadcastIntentProcessor.ACTION_CANCEL_REDIRECTED_CALL));
    }

    @SmallTest
    @Test
    public void testNullUserHandle() {
        Intent intent = new Intent(TelecomBroadcastIntentProcessor.ACTION_CLEAR_MISSED_CALLS);
        // EXTRA_USERHANDLE is missing

        mProcessor.processIntent(intent);

        verify(mMissedCallNotifier, never()).clearMissedCalls(any());
    }
}
