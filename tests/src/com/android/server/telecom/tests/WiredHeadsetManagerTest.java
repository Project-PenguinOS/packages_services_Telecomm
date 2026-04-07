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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.telecom.WiredHeadsetManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class WiredHeadsetManagerTest extends TelecomTestCase {

    @Mock
    private Context mMockContext;
    @Mock
    private AudioManager mMockAudioManager;
    @Mock
    private WiredHeadsetManager.Listener mMockListener;

    private WiredHeadsetManager mWiredHeadsetManager;
    private AudioDeviceCallback mAudioDeviceCallback;
    private MockitoSession mMockitoSession;

    private final int mDeviceType;

    public WiredHeadsetManagerTest(int deviceType) {
        this.mDeviceType = deviceType;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {AudioDeviceInfo.TYPE_WIRED_HEADSET},
                {AudioDeviceInfo.TYPE_WIRED_HEADPHONES},
                {AudioDeviceInfo.TYPE_USB_HEADSET},
                {AudioDeviceInfo.TYPE_USB_DEVICE},
                {AudioDeviceInfo.TYPE_LINE_ANALOG}
        });
    }

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mMockitoSession = ExtendedMockito.mockitoSession()
                .strictness(Strictness.LENIENT)
                .mockStatic(com.android.internal.telecom.flags.Flags.class)
                .startMocking();
        ExtendedMockito.when(com.android.internal.telecom.flags.Flags.callAudioRouteRf())
                .thenReturn(false);

        when(mMockContext.getSystemService(Context.AUDIO_SERVICE)).thenReturn(mMockAudioManager);

        // Capture the callback to simulate device connection/disconnection
        ArgumentCaptor<AudioDeviceCallback> callbackCaptor =
                ArgumentCaptor.forClass(AudioDeviceCallback.class);
        doNothing().when(mMockAudioManager).registerAudioDeviceCallback(callbackCaptor.capture(),
                any());

        // Default to no devices connected at startup
        when(mMockAudioManager.getDevices(anyInt())).thenReturn(new AudioDeviceInfo[0]);
        mWiredHeadsetManager = new WiredHeadsetManager(mMockContext);
        mAudioDeviceCallback = callbackCaptor.getValue();
        mWiredHeadsetManager.addListener(mMockListener);
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
    public void testDeviceConnectionRecognizedAsWiredHeadset() {
        // Initial state: no devices connected
        assertFalse(mWiredHeadsetManager.isPluggedIn());

        // Simulate connection of a wired device
        AudioDeviceInfo wiredDevice = createMockDevice(mDeviceType);
        AudioDeviceInfo[] devices = {wiredDevice};
        when(mMockAudioManager.getDevices(anyInt())).thenReturn(devices);

        // Trigger the callback
        mAudioDeviceCallback.onAudioDevicesAdded(devices);

        // Verify state and listener notification
        assertTrue(mWiredHeadsetManager.isPluggedIn());
        verify(mMockListener).onWiredHeadsetPluggedInChanged(false, true);
    }

    @SmallTest
    @Test
    public void testDeviceDisconnection() {
        // Initial state: wired device is connected
        AudioDeviceInfo wiredDevice = createMockDevice(mDeviceType);
        AudioDeviceInfo[] initialDevices = {wiredDevice};
        when(mMockAudioManager.getDevices(anyInt())).thenReturn(initialDevices);
        mAudioDeviceCallback.onAudioDevicesAdded(initialDevices);
        assertTrue(mWiredHeadsetManager.isPluggedIn());
        reset(mMockListener); // Reset mock to ignore the initial connection notification

        // Simulate disconnection
        when(mMockAudioManager.getDevices(anyInt())).thenReturn(new AudioDeviceInfo[0]);

        // Trigger the callback
        mAudioDeviceCallback.onAudioDevicesRemoved(initialDevices);

        // Verify state and listener notification
        assertFalse(mWiredHeadsetManager.isPluggedIn());
        verify(mMockListener).onWiredHeadsetPluggedInChanged(true, false);
    }

    @SmallTest
    @Test
    public void testInitialStateWhenPluggedIn() {
        // Simulate a device being plugged in before the manager is constructed
        AudioDeviceInfo wiredDevice = createMockDevice(mDeviceType);
        when(mMockAudioManager.getDevices(anyInt())).thenReturn(new AudioDeviceInfo[]{wiredDevice});

        // Re-create the manager to test its constructor
        mWiredHeadsetManager = new WiredHeadsetManager(mMockContext);

        // Verify the initial state is correctly set to plugged in
        assertTrue(mWiredHeadsetManager.isPluggedIn());
    }

    @SmallTest
    @Test
    public void testIrrelevantDeviceConnectionDoesNotChangeState() {
        // Initial state: no devices connected
        assertFalse(mWiredHeadsetManager.isPluggedIn());

        // Simulate connection of an irrelevant device (e.g., Bluetooth)
        AudioDeviceInfo btDevice = createMockDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        AudioDeviceInfo[] devices = {btDevice};
        when(mMockAudioManager.getDevices(anyInt())).thenReturn(devices);

        // Trigger the callback
        mAudioDeviceCallback.onAudioDevicesAdded(devices);

        // Verify state remains unchanged and no notification is sent
        assertFalse(mWiredHeadsetManager.isPluggedIn());
        verify(mMockListener, never()).onWiredHeadsetPluggedInChanged(any(Boolean.class),
                any(Boolean.class));
    }

    private AudioDeviceInfo createMockDevice(int deviceType) {
        AudioDeviceInfo mockDevice = mock(AudioDeviceInfo.class);
        when(mockDevice.getType()).thenReturn(deviceType);
        return mockDevice;
    }
}