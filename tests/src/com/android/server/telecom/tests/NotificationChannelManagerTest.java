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

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.ui.NotificationChannelManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.List;

@RunWith(JUnit4.class)
public class NotificationChannelManagerTest extends TelecomTestCase {

    @Mock
    private NotificationManager mNotificationManager;

    private NotificationChannelManager mNotificationChannelManager;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mNotificationChannelManager = new NotificationChannelManager();
        when(mContext.getSystemService(NotificationManager.class)).thenReturn(mNotificationManager);
    }

    @SmallTest
    @Test
    public void testCreateChannels() {
        mNotificationChannelManager.createChannels(mContext);

        // Verify receiver registration
        ArgumentCaptor<IntentFilter> filterCaptor = ArgumentCaptor.forClass(IntentFilter.class);
        verify(mContext).registerReceiver(any(BroadcastReceiver.class), filterCaptor.capture());
        assertTrue(filterCaptor.getValue().hasAction(Intent.ACTION_LOCALE_CHANGED));

        // Verify channel creation
        ArgumentCaptor<NotificationChannel> channelCaptor =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mNotificationManager, times(7)).createNotificationChannel(channelCaptor.capture());

        List<NotificationChannel> channels = channelCaptor.getAllValues();
        assertChannelExists(channels, NotificationChannelManager.CHANNEL_ID_MISSED_CALLS);
        assertChannelExists(channels, NotificationChannelManager.CHANNEL_ID_INCOMING_CALLS);
        assertChannelExists(channels, NotificationChannelManager.CHANNEL_ID_CALL_BLOCKING);
        assertChannelExists(channels, NotificationChannelManager.CHANNEL_ID_AUDIO_PROCESSING);
        assertChannelExists(channels, NotificationChannelManager.CHANNEL_ID_DISCONNECTED_CALLS);
        assertChannelExists(channels,
                NotificationChannelManager.CHANNEL_ID_IN_CALL_SERVICE_CRASH);
        assertChannelExists(channels, NotificationChannelManager.CHANNEL_ID_CALL_STREAMING);
    }

    @SmallTest
    @Test
    public void testLocaleChange() {
        mNotificationChannelManager.createChannels(mContext);

        ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mContext).registerReceiver(receiverCaptor.capture(), any(IntentFilter.class));

        BroadcastReceiver receiver = receiverCaptor.getValue();
        receiver.onReceive(mContext, new Intent(Intent.ACTION_LOCALE_CHANGED));

        // Verify channels are created again (total 14 times: 7 initially + 7 on receive)
        verify(mNotificationManager, times(14)).createNotificationChannel(any());
    }

    private void assertChannelExists(List<NotificationChannel> channels, String id) {
        assertTrue("Channel " + id + " not found",
                channels.stream().anyMatch(c -> c.getId().equals(id)));
    }
}
