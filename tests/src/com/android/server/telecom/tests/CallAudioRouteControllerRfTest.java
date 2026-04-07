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
 * limitations under the License
 */

package com.android.server.telecom.tests;

import static com.android.server.telecom.CallAudioRouteAdapter.ACTIVE_FOCUS;
import static com.android.server.telecom.CallAudioRouteAdapter.ON_CALL_ADDED;
import static com.android.server.telecom.CallAudioRouteAdapter.SWITCH_FOCUS;
import static com.android.server.telecom.CallAudioRouteAdapter.USER_SWITCH_SPEAKER;
import static com.android.server.telecom.CallAudioRouteAdapter.VIDEO_STATE_CHANGED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioModeSession;
import android.os.UserHandle;
import android.telecom.CallAudioState;
import android.telecom.VideoProfile;

import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.telecom.AnomalyReporterAdapter;
import com.android.server.telecom.AsyncRingtonePlayer;
import com.android.server.telecom.AudioRoute;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallAudioManager;
import com.android.server.telecom.CallAudioRouteController;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.StatusBarNotifier;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.WiredHeadsetManager;
import com.android.server.telecom.bluetooth.BluetoothDeviceManager;
import com.android.server.telecom.bluetooth.BluetoothRouteManager;
import com.android.server.telecom.metrics.TelecomMetricsController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;

@RunWith(JUnit4.class)
public class CallAudioRouteControllerRfTest extends TelecomTestCase {
    private static final long TEST_TIMEOUT = 5000;

    @Mock private AudioModeSession mAudioModeSession;
    @Mock private Call mCall;
    @Mock private AudioDeviceInfo mSpeakerDeviceInfo;
    @Mock private AudioDeviceInfo mEarpieceDeviceInfo;
    @Mock private WiredHeadsetManager mWiredHeadsetManager;
    @Mock private AudioManager mAudioManager;
    @Mock private CallsManager mCallsManager;
    @Mock private CallAudioManager mCallAudioManager;
    @Mock private BluetoothRouteManager mBluetoothRouteManager;
    @Mock private BluetoothDeviceManager mBluetoothDeviceManager;
    @Mock private BluetoothAdapter mBluetoothAdapter;
    @Mock private StatusBarNotifier mockStatusBarNotifier;
    @Mock private TelecomMetricsController mMockitoTelecomMetricsController;
    @Mock private AsyncRingtonePlayer mRingtonePlayer;
    @Mock private AnomalyReporterAdapter mAnomalyReporterAdapter;
    @Mock private TelecomSystem.SyncRoot mLock;
    @Mock private Context mUserContext;
    @Mock private AudioManager mUserAudioManager;

    private CallAudioRouteController mController;
    private MockitoSession mMockitoSession;
    private UserHandle mCurrentUser = UserHandle.SYSTEM;

    private AudioRoute.Factory mAudioRouteFactory =
            new AudioRoute.Factory() {
                @Override
                public AudioRoute create(
                        @AudioRoute.AudioRouteType int type,
                        String bluetoothAddress,
                        AudioManager audioManager,
                        boolean isScoManagedByAudio) {
                    return new AudioRoute(type, bluetoothAddress, null, isScoManagedByAudio);
                }
            };

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mMockitoSession =
                ExtendedMockito.mockitoSession()
                        .strictness(Strictness.LENIENT)
                        .mockStatic(com.android.internal.telecom.flags.Flags.class)
                        .startMocking();

        when(mAudioManager.createAudioModeSession(any(), any(), any()))
                .thenReturn(mAudioModeSession);
        when(mCallsManager.getForegroundCall()).thenReturn(mCall);
        when(mCall.getSupportedAudioRoutes()).thenReturn(CallAudioState.ROUTE_ALL);
        when(mCallsManager.getLock()).thenReturn(mLock);
        when(mCallsManager.getCurrentUserHandle()).thenReturn(mCurrentUser);
        when(mContext.createContextAsUser(mCurrentUser, 0)).thenReturn(mUserContext);
        when(mUserContext.getSystemService(AudioManager.class)).thenReturn(mUserAudioManager);
        when(mBluetoothDeviceManager.getBluetoothAdapter()).thenReturn(mBluetoothAdapter);
        when(mBluetoothRouteManager.getDeviceManager()).thenReturn(mBluetoothDeviceManager);
        when(mSpeakerDeviceInfo.getType()).thenReturn(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        when(mSpeakerDeviceInfo.isSink()).thenReturn(true);
        when(mSpeakerDeviceInfo.getAddress()).thenReturn("");
        when(mEarpieceDeviceInfo.getType()).thenReturn(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);
        when(mEarpieceDeviceInfo.isSink()).thenReturn(true);
        when(mEarpieceDeviceInfo.getAddress()).thenReturn("");
        when(mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                .thenReturn(new AudioDeviceInfo[] {mEarpieceDeviceInfo, mSpeakerDeviceInfo});

        mController =
                new CallAudioRouteController.Factory()
                        .create(
                                mContext,
                                mCallsManager,
                                mAudioRouteFactory,
                                mWiredHeadsetManager,
                                mBluetoothRouteManager,
                                mockStatusBarNotifier,
                                mFeatureFlags,
                                mMockitoTelecomMetricsController,
                                mRingtonePlayer,
                                mAnomalyReporterAdapter);
        mController.setAudioManager(mAudioManager);
        mController.setCallAudioManager(mCallAudioManager);
        when(mCallAudioManager.getForegroundCall()).thenReturn(mCall);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
        super.tearDown();
    }

    @SmallTest
    @Test
    public void testAudioModeSessionCreatedOnCallAdded_FlagEnabled() {
        ExtendedMockito.when(com.android.internal.telecom.flags.Flags.callAudioRouteRf())
                .thenReturn(true);

        mController.initialize();
        mController.sendMessageWithSessionInfo(ON_CALL_ADDED);
        waitForHandlerAction(mController.getAdapterHandler(), TEST_TIMEOUT);

        verify(mAudioManager).createAudioModeSession(any(), any(), any());
        assertNotNull(mController.getAudioModeSession());
    }

    @SmallTest
    @Test
    public void testAudioModeSessionNotCreatedOnCallAdded_FlagDisabled() {
        ExtendedMockito.when(com.android.internal.telecom.flags.Flags.callAudioRouteRf())
                .thenReturn(false);

        mController.initialize();
        mController.sendMessageWithSessionInfo(ON_CALL_ADDED);
        waitForHandlerAction(mController.getAdapterHandler(), TEST_TIMEOUT);

        verify(mAudioManager, never()).createAudioModeSession(any(), any(), any());
        assertNull(mController.getAudioModeSession());
    }

    @SmallTest
    @Test
    public void testSetRequestedRouteOnUserSwitchSpeaker() {
        ExtendedMockito.when(com.android.internal.telecom.flags.Flags.callAudioRouteRf())
                .thenReturn(true);
        mController.initialize();
        mController.sendMessageWithSessionInfo(ON_CALL_ADDED);
        mController.sendMessageWithSessionInfo(SWITCH_FOCUS, ACTIVE_FOCUS, 0);
        waitForHandlerAction(mController.getAdapterHandler(), TEST_TIMEOUT);

        when(mAudioManager.getAvailableCommunicationDevices())
                .thenReturn(List.of(mSpeakerDeviceInfo));

        mController.sendMessageWithSessionInfo(USER_SWITCH_SPEAKER);
        waitForHandlerAction(mController.getAdapterHandler(), TEST_TIMEOUT);

        ArgumentCaptor<AudioModeSession.AudioRoute> routeCaptor =
                ArgumentCaptor.forClass(AudioModeSession.AudioRoute.class);
        verify(mAudioModeSession, atLeastOnce()).setRequestedRoute(routeCaptor.capture());

        AudioModeSession.AudioRoute capturedRoute = routeCaptor.getValue();
        assertNotNull(capturedRoute);
        assertEquals(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, capturedRoute.getPrimaryDevice().getType());
    }

    @SmallTest
    @Test
    public void testVideoStateChangedUpdatesAudioModeSession() {
        ExtendedMockito.when(com.android.internal.telecom.flags.Flags.callAudioRouteRf())
                .thenReturn(true);
        mController.initialize();
        mController.sendMessageWithSessionInfo(ON_CALL_ADDED);
        waitForHandlerAction(mController.getAdapterHandler(), TEST_TIMEOUT);

        mController.sendMessageWithSessionInfo(
                VIDEO_STATE_CHANGED, VideoProfile.STATE_BIDIRECTIONAL);
        waitForHandlerAction(mController.getAdapterHandler(), TEST_TIMEOUT);

        verify(mAudioModeSession).setDisplayActiveUseCase(true);

        mController.sendMessageWithSessionInfo(VIDEO_STATE_CHANGED, VideoProfile.STATE_AUDIO_ONLY);
        waitForHandlerAction(mController.getAdapterHandler(), TEST_TIMEOUT);

        verify(mAudioModeSession).setDisplayActiveUseCase(false);
    }

    @SmallTest
    @Test
    public void testAvailableRoutesChangedUpdatesController() {
        ExtendedMockito.when(com.android.internal.telecom.flags.Flags.callAudioRouteRf())
                .thenReturn(true);
        ArgumentCaptor<AudioModeSession.Callback> callbackCaptor =
                ArgumentCaptor.forClass(AudioModeSession.Callback.class);
        mController.initialize();
        mController.sendMessageWithSessionInfo(ON_CALL_ADDED);
        waitForHandlerAction(mController.getAdapterHandler(), TEST_TIMEOUT);

        verify(mAudioManager).createAudioModeSession(any(), any(), callbackCaptor.capture());
        AudioModeSession.Callback callback = callbackCaptor.getValue();

        // Simulate available routes changed from AudioModeSession
        AudioDeviceAttributes speakerAttr =
                new AudioDeviceAttributes(
                        AudioDeviceAttributes.ROLE_OUTPUT,
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                        "");
        AudioModeSession.AudioRoute speakerRoute =
                new AudioModeSession.AudioRoute.Builder(speakerAttr).build();

        callback.onAvailableRoutesChanged(List.of(speakerRoute));
        waitForHandlerAction(mController.getAdapterHandler(), TEST_TIMEOUT);

        Set<AudioRoute> availableRoutes = mController.getAvailableRoutes();
        boolean hasSpeaker = false;
        for (AudioRoute route : availableRoutes) {
            if (route.getType() == AudioRoute.TYPE_SPEAKER) {
                hasSpeaker = true;
                break;
            }
        }
        assertTrue("Speaker should be in available routes", hasSpeaker);
    }

    @SmallTest
    @Test
    public void testSetAudioModeUpdatesAudioModeSession() {
        ExtendedMockito.when(com.android.internal.telecom.flags.Flags.callAudioRouteRf())
                .thenReturn(true);
        mController.initialize();
        mController.sendMessageWithSessionInfo(ON_CALL_ADDED);
        waitForHandlerAction(mController.getAdapterHandler(), TEST_TIMEOUT);

        mController.setAudioMode(AudioManager.MODE_IN_COMMUNICATION);

        verify(mAudioModeSession).setMode(AudioManager.MODE_IN_COMMUNICATION);
    }
}
