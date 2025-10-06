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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.media.AudioDeviceInfo;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.AudioRoute;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.HashMap;
import java.util.Map;

@RunWith(JUnit4.class)
public class AudioRouteTest extends TelecomTestCase {
    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @SmallTest
    @Test
    public void testDeviceInfoTypeToAudioRouteTypeMappings() {
        Map<Integer, Integer> expectedMappings = new HashMap<>();
        expectedMappings.put(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioRoute.TYPE_EARPIECE);
        expectedMappings.put(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioRoute.TYPE_SPEAKER);
        expectedMappings.put(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioRoute.TYPE_WIRED);
        expectedMappings.put(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioRoute.TYPE_WIRED);
        expectedMappings.put(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioRoute.TYPE_BLUETOOTH_SCO);
        expectedMappings.put(AudioDeviceInfo.TYPE_USB_DEVICE, AudioRoute.TYPE_WIRED);
        expectedMappings.put(AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioRoute.TYPE_WIRED);
        expectedMappings.put(AudioDeviceInfo.TYPE_DOCK, AudioRoute.TYPE_DOCK);
        expectedMappings.put(AudioDeviceInfo.TYPE_USB_HEADSET, AudioRoute.TYPE_WIRED);
        expectedMappings.put(AudioDeviceInfo.TYPE_HEARING_AID, AudioRoute.TYPE_BLUETOOTH_HA);
        expectedMappings.put(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioRoute.TYPE_BLUETOOTH_LE);
        expectedMappings.put(AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioRoute.TYPE_BLUETOOTH_LE);
        expectedMappings.put(AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioRoute.TYPE_BLUETOOTH_LE);
        expectedMappings.put(AudioDeviceInfo.TYPE_LINE_ANALOG, AudioRoute.TYPE_WIRED);
        expectedMappings.put(AudioDeviceInfo.TYPE_DOCK_ANALOG, AudioRoute.TYPE_DOCK);
        expectedMappings.put(AudioDeviceInfo.TYPE_BUS, AudioRoute.TYPE_BUS);

        assertEquals(
                "DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE does not match expected mappings",
                expectedMappings, AudioRoute.DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE);
    }
}