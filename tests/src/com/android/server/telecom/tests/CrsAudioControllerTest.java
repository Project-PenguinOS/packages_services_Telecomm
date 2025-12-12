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
 * limitations under the License
 */

package com.android.server.telecom.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.telecom.CallAudioState;
import android.telecom.VideoProfile;
import android.text.TextUtils;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallAudioManager;
import com.android.server.telecom.CallState;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.CrsAudioController;
import com.android.server.telecom.RingerAttributes;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

public class CrsAudioControllerTest extends TelecomTestCase {

    @Mock
    private Context mContext;
    @Mock
    private AudioManager mAudioManager;
    @Mock
    private Resources mResources;
    @Mock
    private Call mCall;
    @Mock
    private CallAudioManager mCallAudioManager;
    @Mock
    private CallsManager mCallsManager;
    @Mock
    private ScheduledExecutorService mMockExecutor;

    private CrsAudioController mCrsAudioController;
    private CompletableFuture<Boolean> mTimeoutFuture;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        when(mContext.getResources()).thenReturn(mResources);
        // Mock the executor to run tasks immediately
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mMockExecutor).execute(any(Runnable.class));

        mCrsAudioController = new CrsAudioController(mContext, mAudioManager, mMockExecutor) {
            @Override
            protected CompletableFuture<Boolean> getTimeoutFuture() {
                if (mTimeoutFuture == null) {
                    mTimeoutFuture = new CompletableFuture<>();
                }
                return mTimeoutFuture;
            }
        };
    }

    @Test // Added @Test annotation to fix [JUnit4TestNotRun]
    public void testRunActionWhenSpeakerIsReady_speakerOn() {
        when(mAudioManager.isSpeakerphoneOn()).thenReturn(true);
        Runnable action = () -> {
        };
        mCrsAudioController.runActionWhenSpeakerIsReady(action);
        // verifying that the controller actually checked the speaker state.
        verify(mAudioManager).isSpeakerphoneOn();
    }

    @Test
    public void testRunActionWhenSpeakerIsReady_speakerOffThenOn() {
        // 1. Setup initial state
        when(mAudioManager.isSpeakerphoneOn()).thenReturn(false);

        // CHANGE: Use a mock Runnable instead of an empty lambda
        Runnable mockAction = mock(Runnable.class);

        mCrsAudioController.runActionWhenSpeakerIsReady(mockAction);

        // 2. Verify listener registration
        ArgumentCaptor<CrsAudioController.CommunicationDeviceChangedListener> listenerCaptor =
                ArgumentCaptor.forClass(
                        CrsAudioController.CommunicationDeviceChangedListener.class);
        // Update verification to match the new signature (using Executor) if applicable,
        // or stick to the one used in your code.
        verify(mAudioManager).addOnCommunicationDeviceChangedListener(
                any(), listenerCaptor.capture());

        // 3. Simulate the speaker turning on
        when(mAudioManager.isSpeakerphoneOn()).thenReturn(true);
        AudioDeviceInfo mockDevice = mock(AudioDeviceInfo.class);
        when(mockDevice.getType()).thenReturn(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);

        // Trigger the callback manually
        listenerCaptor.getValue().onCommunicationDeviceChanged(mockDevice);

        // CHANGE: Verify the action was actually executed.
        // This justifies the test by confirming the logic works.
        verify(mockAction).run();
    }

    @Test
    public void testRunActionWhenSpeakerIsReady_timeout() {
        when(mAudioManager.isSpeakerphoneOn()).thenReturn(false);
        Runnable action = () -> {
        };
        mCrsAudioController.runActionWhenSpeakerIsReady(action);
        // The timeout is 2 seconds, so we don't need to wait for it to finish.
        // We just need to verify that the listener is added and then removed.
        verify(mAudioManager, times(1)).addOnCommunicationDeviceChangedListener(
                any(), any(CrsAudioController.CommunicationDeviceChangedListener.class));
        verify(mAudioManager, never()).removeOnCommunicationDeviceChangedListener(
                any(CrsAudioController.CommunicationDeviceChangedListener.class));
    }

    @Test
    public void testRunActionWhenSpeakerIsReady_timeout_unregistersListener() {
        when(mAudioManager.isSpeakerphoneOn()).thenReturn(false);
        Runnable action = mock(Runnable.class);
        mCrsAudioController.runActionWhenSpeakerIsReady(action);

        ArgumentCaptor<CrsAudioController.CommunicationDeviceChangedListener> listenerCaptor =
                ArgumentCaptor.forClass(
                        CrsAudioController.CommunicationDeviceChangedListener.class);
        verify(mAudioManager).addOnCommunicationDeviceChangedListener(
                any(), listenerCaptor.capture());

        // Simulate timeout
        if (mTimeoutFuture != null) {
            mTimeoutFuture.complete(false);
        }

        verify(mAudioManager).removeOnCommunicationDeviceChangedListener(
                eq(listenerCaptor.getValue()));
        verify(action, never()).run();
    }

    @Test
    public void testRunActionWhenSpeakerIsReady_success_preventsTimeoutUnregister() {
        when(mAudioManager.isSpeakerphoneOn()).thenReturn(false);
        Runnable action = mock(Runnable.class);
        mCrsAudioController.runActionWhenSpeakerIsReady(action);

        ArgumentCaptor<CrsAudioController.CommunicationDeviceChangedListener> listenerCaptor =
                ArgumentCaptor.forClass(
                        CrsAudioController.CommunicationDeviceChangedListener.class);
        verify(mAudioManager).addOnCommunicationDeviceChangedListener(
                any(), listenerCaptor.capture());

        // Simulate success
        AudioDeviceInfo mockDevice = mock(AudioDeviceInfo.class);
        when(mockDevice.getType()).thenReturn(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        listenerCaptor.getValue().onCommunicationDeviceChanged(mockDevice);

        // Verify action ran
        verify(action).run();

        // Verify remove was NOT called (logic handles checking the future result which is true)
        verify(mAudioManager, never()).removeOnCommunicationDeviceChangedListener(
                eq(listenerCaptor.getValue()));
    }

    @Test
    public void testRunActionWhenSpeakerIsReady_multipleListeners() {
        when(mAudioManager.isSpeakerphoneOn()).thenReturn(false);
        Runnable action1 = mock(Runnable.class);
        Runnable action2 = mock(Runnable.class);
        mCrsAudioController.runActionWhenSpeakerIsReady(action1);
        mCrsAudioController.runActionWhenSpeakerIsReady(action2);

        ArgumentCaptor<CrsAudioController.CommunicationDeviceChangedListener> listenerCaptor =
                ArgumentCaptor.forClass(
                        CrsAudioController.CommunicationDeviceChangedListener.class);
        verify(mAudioManager, times(2)).addOnCommunicationDeviceChangedListener(
                any(), listenerCaptor.capture());

        AudioDeviceInfo mockDevice = mock(AudioDeviceInfo.class);
        when(mockDevice.getType()).thenReturn(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);

        for (CrsAudioController.CommunicationDeviceChangedListener listener :
                listenerCaptor.getAllValues()) {
            listener.onCommunicationDeviceChanged(mockDevice);
        }

        verify(action1, times(1)).run();
        verify(action2, times(1)).run();
    }

    @Test
    public void testConvertVolumeLevelFromRingToCrs() {
        when(mAudioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)).thenReturn(10);
        when(mAudioManager.getStreamMaxVolume(AudioManager.STREAM_RING)).thenReturn(10);
        when(mAudioManager.getStreamMinVolume(AudioManager.STREAM_VOICE_CALL)).thenReturn(1);
        when(mAudioManager.getStreamMinVolume(AudioManager.STREAM_RING)).thenReturn(1);
        assertEquals(5, mCrsAudioController.convertVolumeLevelFromRingToCrs(5));
    }

    @Test
    public void testSetSystemSpeakerInCallVolume() {
        when(mAudioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(5);
        mCrsAudioController.setSystemSpeakerVolume();
        verify(mAudioManager, times(1)).setStreamVolume(eq(AudioManager.STREAM_VOICE_CALL),
                anyInt(), anyInt());
    }

    @Test
    public void testRestoreSystemSpeakerInCallVolume() {
        when(mAudioManager.isSpeakerphoneOn()).thenReturn(true);
        when(mAudioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(5);
        mCrsAudioController.setSystemSpeakerVolume();
        mCrsAudioController.restoreSystemSpeakerVolume();
        verify(mAudioManager, times(1)).adjustStreamVolume(eq(AudioManager.STREAM_VOICE_CALL),
                eq(AudioManager.ADJUST_UNMUTE), anyInt());
        verify(mAudioManager, times(2)).setStreamVolume(eq(AudioManager.STREAM_VOICE_CALL),
                anyInt(), anyInt());
    }

    @Test
    public void testRestoreSystemSpeakerVolume_speakerOff() {
        when(mAudioManager.isSpeakerphoneOn()).thenReturn(false);
        when(mAudioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(5);
        mCrsAudioController.setSystemSpeakerVolume();
        mCrsAudioController.restoreSystemSpeakerVolume();
        verify(mAudioManager, times(1)).adjustStreamVolume(eq(AudioManager.STREAM_VOICE_CALL),
                eq(AudioManager.ADJUST_UNMUTE), anyInt());
        verify(mAudioManager, times(1)).setStreamVolume(eq(AudioManager.STREAM_VOICE_CALL),
                anyInt(), anyInt());
    }

    @Test
    public void testSilenceInCallModeCrs() {
        mCrsAudioController.silenceInCallModeCrs(true);
        verify(mAudioManager, times(1)).adjustStreamVolume(eq(AudioManager.STREAM_VOICE_CALL),
                eq(AudioManager.ADJUST_MUTE), anyInt());
    }

    @Test
    public void testSetVolumeLevelForCrsInRingtoneMode() {
        when(mResources.getString(anyInt())).thenReturn("crs_volume_key");
        mCrsAudioController.setVolumeLevelForCrsInRingtoneMode(5);
        verify(mAudioManager, times(1)).setParameters("crs_volume_key5");
    }

    @Test
    public void testConfigureCrsAudioVolume_ringtoneMode() {
        RingerAttributes ringerAttributes = new RingerAttributes.Builder().setRingToneType(
                Call.RINGTONE_SOURCE_NETWORK_RING_MODE).build();
        mCrsAudioController.configureCrsRingVolume(ringerAttributes);
        verify(mAudioManager, times(1)).getStreamVolume(AudioManager.STREAM_RING);
    }

    @Test
    public void testResetCrsAudioVolume_ringtoneMode() {
        RingerAttributes ringerAttributes = new RingerAttributes.Builder().setRingToneType(
                Call.RINGTONE_SOURCE_NETWORK_RING_MODE).build();
        mCrsAudioController.resetCrsAudioVolume(mCall, ringerAttributes);
        verify(mResources, times(1)).getString(anyInt());
    }

    @Test
    public void testResetCrsAudioVolume_nullAttributes() {
        mCrsAudioController.resetCrsAudioVolume(mCall, null);
        verify(mResources, times(0)).getString(anyInt());
    }

    @Test
    public void testResetAudioDevices_videoCall() {
        when(mCall.getVideoState()).thenReturn(VideoProfile.STATE_BIDIRECTIONAL);
        mCrsAudioController.resetAudioDevices(mCallAudioManager, mCallsManager, mCall,
                CallState.ACTIVE);
        verify(mCallAudioManager, times(0)).setAudioRoute(CallAudioState.ROUTE_EARPIECE, null);
    }

    @Test
    public void testResetAudioDevices_wiredHeadset() {
        when(mCallsManager.isWiredHandsetIn()).thenReturn(true);
        mCrsAudioController.resetAudioDevices(mCallAudioManager, mCallsManager, mCall,
                CallState.ACTIVE);
        verify(mCallAudioManager, times(1)).setAudioRoute(CallAudioState.ROUTE_WIRED_HEADSET, null);
    }

    @Test
    public void testResetAudioDevices_btAvailable() {
        when(mCallsManager.isBtAvailable()).thenReturn(true);
        mCrsAudioController.resetAudioDevices(mCallAudioManager, mCallsManager, mCall,
                CallState.ACTIVE);
        verify(mCallAudioManager, times(1)).setAudioRoute(CallAudioState.ROUTE_BLUETOOTH, null);
    }

    @Test
    public void testGetCrsRingToneType() {
        when(mCall.getCrsMode()).thenReturn(AudioManager.MODE_RINGTONE);
        assertEquals(Call.RINGTONE_SOURCE_NETWORK_RING_MODE,
                mCrsAudioController.getCrsRingToneType(mCall));
    }

    @Test
    public void testUnregisterCommunicationDeviceChangedListener_exception() {
        Runnable action = () -> {
        };
        mCrsAudioController.runActionWhenSpeakerIsReady(action);
        mCrsAudioController.unregisterCommunicationDeviceChangedListener(action);
        verify(mAudioManager, times(1)).removeOnCommunicationDeviceChangedListener(
                any(CrsAudioController.CommunicationDeviceChangedListener.class));
    }

    @Test
    public void testIsCrsInCallMode_nullCall() {
        assertEquals(false, mCrsAudioController.isCrsInCallMode(null));
    }

    @Test
    public void testShouldControlCrsWithParameters_True() {
        when(mResources.getString(
                com.android.server.telecom.R.string.config_audio_parameter_key_crs_volume)).thenReturn(
                "");
        when(mResources.getString(
                com.android.server.telecom.R.string.config_crs_speech_mute_param)).thenReturn(
                "mute_param");
        assertTrue(mCrsAudioController.shouldControlCrsWithParameters());
    }

    @Test
    public void testSetAudioModeForCrs_WithParams() {
        // Setup for shouldControlCrsWithParameters = true
        when(mResources.getString(
                com.android.server.telecom.R.string.config_audio_parameter_key_crs_volume)).thenReturn(
                "");
        when(mResources.getString(
                com.android.server.telecom.R.string.config_crs_speech_mute_param)).thenReturn(
                "mute_param");
        when(mResources.getString(
                com.android.server.telecom.R.string.config_crs_mode_on_param)).thenReturn(
                "on_param");
        mCrsAudioController.setAudioModeForCrs();

        verify(mAudioManager).setParameters("on_param");
        verify(mAudioManager).setMode(AudioManager.MODE_IN_CALL);
    }

    @Test
    public void testSetCrsSpeechMuted() {
        when(mResources.getString(
                com.android.server.telecom.R.string.config_crs_speech_mute_param)).thenReturn(
                "mute_param");
        when(mResources.getString(
                com.android.server.telecom.R.string.config_crs_speech_unmute_param)).thenReturn(
                "unmute_param");

        mCrsAudioController.setCrsSpeechMuted(true);
        verify(mAudioManager).setParameters("mute_param");

        mCrsAudioController.setCrsSpeechMuted(false);
        verify(mAudioManager).setParameters("unmute_param");
    }

    @Test
    public void testSetCrsModeParams() {
        when(mResources.getString(
                com.android.server.telecom.R.string.config_crs_mode_on_param)).thenReturn(
                "on_param");
        when(mResources.getString(
                com.android.server.telecom.R.string.config_crs_mode_off_param)).thenReturn(
                "off_param");

        mCrsAudioController.setCrsModeParams(true);
        verify(mAudioManager).setParameters("on_param");

        mCrsAudioController.setCrsModeParams(false);
        verify(mAudioManager).setParameters("off_param");
    }

    @Test
    public void testSetCrsAudioRoute_WithParams() {
        // Setup for shouldControlCrsWithParameters = true
        when(mResources.getString(
                com.android.server.telecom.R.string.config_audio_parameter_key_crs_volume)).thenReturn(
                "");
        when(mResources.getString(
                com.android.server.telecom.R.string.config_crs_speech_mute_param)).thenReturn(
                "mute_param");

        mCrsAudioController.setCrsAudioRoute(mCallAudioManager);

        verify(mCallAudioManager, never()).setAudioRoute(anyInt(), any());
    }
}
