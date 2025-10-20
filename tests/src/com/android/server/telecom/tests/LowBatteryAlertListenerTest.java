/*
 * Copyright (C) 2025 The Android Open Source Project
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Bundle;
import android.telecom.PhoneAccount;
import android.telecom.VideoProfile;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallState;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.InCallTonePlayer;
import com.android.server.telecom.LowBatteryAlertListener;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LowBatteryAlertListenerTest extends TelecomTestCase {

    @Mock
    private CallsManager mCallsManager;
    @Mock
    private InCallTonePlayer.Factory mToneGeneratorFactory;
    @Mock
    private InCallTonePlayer mTonePlayer;
    @Mock
    private Call mCall;
    @Mock
    private PhoneAccount mPhoneAccount;
    @Mock
    private ScheduledExecutorService mScheduledExecutorService;

    private LowBatteryAlertListener mLowBatteryAlertListener;
    private BroadcastReceiver mBatteryStateReceiver;

    private static final int LOW_BATTERY_LEVEL = 10;
    private static final int ALERT_INTERVAL = 10;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        mContext = spy(mContext);
        doNothing().when(mContext).unregisterReceiver(any(BroadcastReceiver.class));
        mLowBatteryAlertListener = new LowBatteryAlertListener(mContext, mScheduledExecutorService);
        mLowBatteryAlertListener.registerForLowBatteryListener(mToneGeneratorFactory);
        when(mToneGeneratorFactory.createPlayer(any(Call.class),
                eq(InCallTonePlayer.TONE_LOW_BATTERY))).thenReturn(mTonePlayer);
    }

    @Test
    public void testReceiverRegisteredWhenActiveVoiceCall() {
        setupCall(true, true);
        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.DIALING, CallState.ACTIVE);
        verify(mContext).registerReceiver(any(BroadcastReceiver.class), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
    }

    @Test
    public void testReceiverNotRegisteredWhenFeatureDisabled() {
        setupCall(false, true);
        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.DIALING, CallState.ACTIVE);
        verify(mContext, never()).registerReceiver(any(BroadcastReceiver.class), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
    }

    @Test
    public void testTonePlayedWhenBatteryLowNotCharging() {
        setupCall(true, true);
        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.DIALING, CallState.ACTIVE);
        ArgumentCaptor<BroadcastReceiver> receiverCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        verify(mContext).registerReceiver(receiverCaptor.capture(), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
        mBatteryStateReceiver = receiverCaptor.getValue();

        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.putExtra(BatteryManager.EXTRA_LEVEL, 5);
        intent.putExtra(BatteryManager.EXTRA_SCALE, 100);
        intent.putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);

        mBatteryStateReceiver.onReceive(mContext, intent);

        // Capture the runnable and execute it to simulate the executor service.
        verify(mScheduledExecutorService).scheduleAtFixedRate(runnableCaptor.capture(),
                anyLong(), anyLong(), any(TimeUnit.class));
        runnableCaptor.getValue().run();

        verify(mTonePlayer, timeout(2000).times(1)).startTone();
    }

    @Test
    public void testToneNotPlayedWhenBatteryLowAndCharging() {
        setupCall(true, true);
        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.DIALING, CallState.ACTIVE);
        ArgumentCaptor<BroadcastReceiver> receiverCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        verify(mContext).registerReceiver(receiverCaptor.capture(), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
        mBatteryStateReceiver = receiverCaptor.getValue();

        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.putExtra(BatteryManager.EXTRA_LEVEL, 5);
        intent.putExtra(BatteryManager.EXTRA_SCALE, 100);
        intent.putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING);

        mBatteryStateReceiver.onReceive(mContext, intent);
        verify(mTonePlayer, never()).startTone();
    }

    @Test
    public void testToneStoppedWhenCallEnded() {
        setupCall(true, true);
        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.DIALING, CallState.ACTIVE);
        ArgumentCaptor<BroadcastReceiver> receiverCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        verify(mContext).registerReceiver(receiverCaptor.capture(), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
        mBatteryStateReceiver = receiverCaptor.getValue();

        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.putExtra(BatteryManager.EXTRA_LEVEL, 5);
        intent.putExtra(BatteryManager.EXTRA_SCALE, 100);
        intent.putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);

        mBatteryStateReceiver.onReceive(mContext, intent);

        verify(mScheduledExecutorService).scheduleAtFixedRate(runnableCaptor.capture(),
                anyLong(), anyLong(), any(TimeUnit.class));
        runnableCaptor.getValue().run();

        verify(mTonePlayer, timeout(2000).times(1)).startTone();

        when(mCallsManager.getActiveCall()).thenReturn(null);
        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.ACTIVE,
                CallState.DISCONNECTED);
        verify(mContext).unregisterReceiver(mBatteryStateReceiver);
        verify(mTonePlayer, timeout(2000).times(1)).stopTone();
    }

    @Test
    public void testReceiverNotRegistered_WhenVideoCall() {
        setupCall(true, VideoProfile.STATE_BIDIRECTIONAL, false, false, false);
        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.DIALING, CallState.ACTIVE);
        verify(mContext, never()).registerReceiver(any(BroadcastReceiver.class), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
    }

    @Test
    public void testReceiverNotRegistered_WhenRttCall() {
        setupCall(true, VideoProfile.STATE_AUDIO_ONLY, true, false, false);
        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.DIALING, CallState.ACTIVE);
        verify(mContext, never()).registerReceiver(any(BroadcastReceiver.class), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
    }

    @Test
    public void testReceiverNotRegistered_WhenSelfManagedCall() {
        setupCall(true, VideoProfile.STATE_AUDIO_ONLY, false, true, false);
        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.DIALING, CallState.ACTIVE);
        verify(mContext, never()).registerReceiver(any(BroadcastReceiver.class), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
    }

    @Test
    public void testReceiverNotRegistered_WhenExternalCall() {
        setupCall(true, VideoProfile.STATE_AUDIO_ONLY, false, false, true);
        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.DIALING, CallState.ACTIVE);
        verify(mContext, never()).registerReceiver(any(BroadcastReceiver.class), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
    }

    @Test
    public void testTonePlayed_WhenBatteryAlreadyLow() {
        setupCall(true, true);
        // Assume call is already active when the listener is added
        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.DIALING, CallState.ACTIVE);
        ArgumentCaptor<BroadcastReceiver> receiverCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        verify(mContext).registerReceiver(receiverCaptor.capture(), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
        mBatteryStateReceiver = receiverCaptor.getValue();

        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.putExtra(BatteryManager.EXTRA_LEVEL, 5);
        intent.putExtra(BatteryManager.EXTRA_SCALE, 100);
        intent.putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);

        mBatteryStateReceiver.onReceive(mContext, intent);

        verify(mScheduledExecutorService).scheduleAtFixedRate(runnableCaptor.capture(),
                anyLong(), anyLong(), any(TimeUnit.class));
        runnableCaptor.getValue().run();

        verify(mTonePlayer, timeout(2000).times(1)).startTone();
    }

    @Test
    public void testFeatureDisabled_WhenPartialConfig_ThresholdMissing() {
        setupCallWithPartialConfig(true, false); // interval is present, threshold is not
        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.DIALING, CallState.ACTIVE);
        verify(mContext, never()).registerReceiver(any(BroadcastReceiver.class), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
    }

    @Test
    public void testFeatureDisabled_WhenPartialConfig_IntervalMissing() {
        setupCallWithPartialConfig(false, true); // threshold is present, interval is not
        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.DIALING, CallState.ACTIVE);
        verify(mContext, never()).registerReceiver(any(BroadcastReceiver.class), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
    }

    @Test
    public void testTonePlayedDuringConferenceCall() {
        setupCall(true, true);
        Call childCall1 = mock(Call.class);
        Call childCall2 = mock(Call.class);
        when(mCall.isConference()).thenReturn(true);
        when(mCall.getChildCalls()).thenReturn(java.util.Arrays.asList(childCall1, childCall2));

        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.DIALING, CallState.ACTIVE);
        ArgumentCaptor<BroadcastReceiver> receiverCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        verify(mContext).registerReceiver(receiverCaptor.capture(), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
        mBatteryStateReceiver = receiverCaptor.getValue();

        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.putExtra(BatteryManager.EXTRA_LEVEL, 5);
        intent.putExtra(BatteryManager.EXTRA_SCALE, 100);
        intent.putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);

        mBatteryStateReceiver.onReceive(mContext, intent);

        // Capture the runnable and execute it to simulate the executor service.
        verify(mScheduledExecutorService).scheduleAtFixedRate(runnableCaptor.capture(),
                anyLong(), anyLong(), any(TimeUnit.class));
        runnableCaptor.getValue().run();

        verify(mTonePlayer, timeout(2000).times(1)).startTone();
    }

    private void setupCall(boolean featureEnabled, boolean isVoiceCall) {
        when(mCallsManager.getActiveCall()).thenReturn(mCall);
        when(mCall.getPhoneAccountFromHandle()).thenReturn(mPhoneAccount);
        Bundle extras = new Bundle();
        if (featureEnabled) {
            extras.putInt(PhoneAccount.EXTRA_LOW_BATTERY_ALERT_LEVEL_THRESHOLD, LOW_BATTERY_LEVEL);
            extras.putInt(PhoneAccount.EXTRA_LOW_BATTERY_ALERT_INTERVAL_SECONDS, ALERT_INTERVAL);
        }
        when(mPhoneAccount.getExtras()).thenReturn(extras);
        when(mCall.getVideoState()).thenReturn(
                isVoiceCall ? VideoProfile.STATE_AUDIO_ONLY : VideoProfile.STATE_BIDIRECTIONAL);
        when(mCall.isRttCall()).thenReturn(false);
        when(mCall.isSelfManaged()).thenReturn(false);
        when(mCall.isExternalCall()).thenReturn(false);
    }

    /**
     * Overloaded helper method for setting up calls with different properties.
     */
    private void setupCall(boolean featureEnabled, int videoState, boolean isRtt,
            boolean isSelfManaged, boolean isExternal) {
        when(mCallsManager.getActiveCall()).thenReturn(mCall);
        when(mCall.getPhoneAccountFromHandle()).thenReturn(mPhoneAccount);
        Bundle extras = new Bundle();
        if (featureEnabled) {
            extras.putInt(PhoneAccount.EXTRA_LOW_BATTERY_ALERT_LEVEL_THRESHOLD, LOW_BATTERY_LEVEL);
            extras.putInt(PhoneAccount.EXTRA_LOW_BATTERY_ALERT_INTERVAL_SECONDS, ALERT_INTERVAL);
        }
        when(mPhoneAccount.getExtras()).thenReturn(extras);
        when(mCall.getVideoState()).thenReturn(videoState);
        when(mCall.isRttCall()).thenReturn(isRtt);
        when(mCall.isSelfManaged()).thenReturn(isSelfManaged);
        when(mCall.isExternalCall()).thenReturn(isExternal);
    }

    @Test
    public void testMultipleActiveCalls() {
        setupCall(true, true);
        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.DIALING, CallState.ACTIVE);
        ArgumentCaptor<BroadcastReceiver> receiverCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        verify(mContext).registerReceiver(receiverCaptor.capture(), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
        mBatteryStateReceiver = receiverCaptor.getValue();

        Call newCall = mock(Call.class);
        setupNewCall(newCall);

        mLowBatteryAlertListener.onCallStateChanged(newCall, CallState.DIALING, CallState.ACTIVE);

        verify(mContext).unregisterReceiver(mBatteryStateReceiver);
        verify(mContext, times(2)).registerReceiver(any(BroadcastReceiver.class), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
    }

    private void setupNewCall(Call call) {
        when(mCallsManager.getActiveCall()).thenReturn(call);
        when(call.getPhoneAccountFromHandle()).thenReturn(mPhoneAccount);
        Bundle extras = new Bundle();
        extras.putInt(PhoneAccount.EXTRA_LOW_BATTERY_ALERT_LEVEL_THRESHOLD, LOW_BATTERY_LEVEL);
        extras.putInt(PhoneAccount.EXTRA_LOW_BATTERY_ALERT_INTERVAL_SECONDS, ALERT_INTERVAL);
        when(mPhoneAccount.getExtras()).thenReturn(extras);
        when(call.getVideoState()).thenReturn(VideoProfile.STATE_AUDIO_ONLY);
        when(call.isRttCall()).thenReturn(false);
        when(call.isSelfManaged()).thenReturn(false);
        when(call.isExternalCall()).thenReturn(false);
    }

    @Test
    public void testCallStateTransition_ActiveToHoldToActive() {
        setupCall(true, true);
        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.DIALING, CallState.ACTIVE);
        ArgumentCaptor<BroadcastReceiver> receiverCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        verify(mContext).registerReceiver(receiverCaptor.capture(), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
        mBatteryStateReceiver = receiverCaptor.getValue();

        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.ACTIVE, CallState.ON_HOLD);
        verify(mContext).unregisterReceiver(mBatteryStateReceiver);

        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.ON_HOLD, CallState.ACTIVE);
        verify(mContext, times(2)).registerReceiver(any(BroadcastReceiver.class), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
    }

    @Test
    public void testToneRepeatsAtInterval() {
        setupCall(true, true);
        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.DIALING, CallState.ACTIVE);
        ArgumentCaptor<BroadcastReceiver> receiverCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        verify(mContext).registerReceiver(receiverCaptor.capture(), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
        mBatteryStateReceiver = receiverCaptor.getValue();

        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.putExtra(BatteryManager.EXTRA_LEVEL, 5);
        intent.putExtra(BatteryManager.EXTRA_SCALE, 100);
        intent.putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);

        mBatteryStateReceiver.onReceive(mContext, intent);

        verify(mScheduledExecutorService).scheduleAtFixedRate(runnableCaptor.capture(),
                eq(0L), eq((long) ALERT_INTERVAL), eq(TimeUnit.SECONDS));

        Runnable toneRunnable = runnableCaptor.getValue();
        toneRunnable.run();
        toneRunnable.run();
        toneRunnable.run();

        verify(mTonePlayer, times(3)).startTone();
    }

    @Test
    public void testTonePlayerCreationFailure() {
        setupCall(true, true);
        when(mToneGeneratorFactory.createPlayer(any(Call.class),
                eq(InCallTonePlayer.TONE_LOW_BATTERY)))
                .thenThrow(new RuntimeException("Test Exception"));

        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.DIALING, CallState.ACTIVE);
        ArgumentCaptor<BroadcastReceiver> receiverCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        verify(mContext).registerReceiver(receiverCaptor.capture(), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
        mBatteryStateReceiver = receiverCaptor.getValue();

        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.putExtra(BatteryManager.EXTRA_LEVEL, 5);
        intent.putExtra(BatteryManager.EXTRA_SCALE, 100);
        intent.putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);

        mBatteryStateReceiver.onReceive(mContext, intent);

        // Capture the runnable and execute it to simulate the executor service.
        verify(mScheduledExecutorService).scheduleAtFixedRate(runnableCaptor.capture(),
                anyLong(), anyLong(), any(TimeUnit.class));
        runnableCaptor.getValue().run();

        verify(mTonePlayer, never()).startTone();
    }

    @Test
    public void testToneNotPlayedWhenBatteryNotLow() {
        setupCall(true, true);
        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.DIALING, CallState.ACTIVE);
        ArgumentCaptor<BroadcastReceiver> receiverCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        verify(mContext).registerReceiver(receiverCaptor.capture(), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
        mBatteryStateReceiver = receiverCaptor.getValue();

        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.putExtra(BatteryManager.EXTRA_LEVEL, 50);
        intent.putExtra(BatteryManager.EXTRA_SCALE, 100);
        intent.putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING);

        mBatteryStateReceiver.onReceive(mContext, intent);
        verify(mTonePlayer, never()).startTone();
    }

    @Test
    public void testUnregisterReceiverOnCallRemoved() {
        setupCall(true, true);
        mLowBatteryAlertListener.onCallStateChanged(mCall, CallState.DIALING, CallState.ACTIVE);
        ArgumentCaptor<BroadcastReceiver> receiverCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        verify(mContext).registerReceiver(receiverCaptor.capture(), any(),
                eq(Context.RECEIVER_NOT_EXPORTED));
        mBatteryStateReceiver = receiverCaptor.getValue();

        when(mCallsManager.getCalls()).thenReturn(java.util.Collections.emptyList());
        mLowBatteryAlertListener.onCallRemoved(mCall);
        verify(mContext).unregisterReceiver(mBatteryStateReceiver);
    }

    /**
     * Helper method for setting up calls with partial configurations.
     */
    private void setupCallWithPartialConfig(boolean withInterval, boolean withThreshold) {
        when(mCallsManager.getActiveCall()).thenReturn(mCall);
        when(mCall.getPhoneAccountFromHandle()).thenReturn(mPhoneAccount);
        Bundle extras = new Bundle();
        if (withInterval) {
            extras.putInt(PhoneAccount.EXTRA_LOW_BATTERY_ALERT_INTERVAL_SECONDS, ALERT_INTERVAL);
        }
        if (withThreshold) {
            extras.putInt(PhoneAccount.EXTRA_LOW_BATTERY_ALERT_LEVEL_THRESHOLD, LOW_BATTERY_LEVEL);
        }
        when(mPhoneAccount.getExtras()).thenReturn(extras);
        when(mCall.getVideoState()).thenReturn(VideoProfile.STATE_AUDIO_ONLY);
        when(mCall.isRttCall()).thenReturn(false);
        when(mCall.isSelfManaged()).thenReturn(false);
        when(mCall.isExternalCall()).thenReturn(false);
    }
}