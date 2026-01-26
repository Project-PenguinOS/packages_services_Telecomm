/* * Copyright (C) 2017 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.media.ToneGenerator;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.Call;
import com.android.server.telecom.DtmfLocalTonePlayer;
import com.android.server.telecom.R;
import com.android.server.telecom.TelecomResourceId;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

@RunWith(JUnit4.class)
public class DtmfLocalTonePlayerTest extends TelecomTestCase {
    private static final int TIMEOUT = 2000;
    private static final int CUSTOM_VOLUME = 120;
    @Mock DtmfLocalTonePlayer.ToneGeneratorProxy mToneProxy;
    @Mock Call mCall;
    @Mock Resources mResources;

    DtmfLocalTonePlayer mPlayer;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mContext = mComponentContextFixture.getTestDouble().getApplicationContext();
        TelecomResourceId.setTelecomContext(mContext);
        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getIdentifier(anyString(), anyString(), anyString())).thenReturn(1);

        mPlayer = new DtmfLocalTonePlayer(mToneProxy, mFeatureFlags);
        when(mCall.getContext()).thenReturn(mContext);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        TelecomResourceId.setTelecomContext(null);
        super.tearDown();
    }

    @SmallTest
    @Test
    public void testSupportedStart() {
        when(mResources.getBoolean(1)).thenReturn(true);
        when(mToneProxy.isPresent()).thenReturn(true);
        mPlayer.onForegroundCallChanged(null, mCall);
        waitForHandlerAction(mPlayer.getHandler(), TIMEOUT);
        verify(mToneProxy).create(DtmfLocalTonePlayer.DEFAULT_VOLUME);
    }

    @SmallTest
    @Test
    public void testSupportedStartWithCustomVolume() {
        when(mResources.getBoolean(1)).thenReturn(true);
        when(mToneProxy.isPresent()).thenReturn(true);
        when(mResources.getInteger(1)).thenReturn(CUSTOM_VOLUME);

        DtmfLocalTonePlayer playerWithCustomVolume =
                new DtmfLocalTonePlayer(mToneProxy, CUSTOM_VOLUME, mFeatureFlags);

        playerWithCustomVolume.onForegroundCallChanged(null, mCall);
        waitForHandlerAction(playerWithCustomVolume.getHandler(), TIMEOUT);

        // Verify with the custom volume
        verify(mToneProxy).create(CUSTOM_VOLUME);
    }

    @SmallTest
    @Test
    public void testUnsupportedStart() {
        when(mResources.getBoolean(1)).thenReturn(false);
        when(mToneProxy.isPresent()).thenReturn(true);
        mPlayer.onForegroundCallChanged(null, mCall);
        waitForHandlerAction(mPlayer.getHandler(), TIMEOUT);
        verify(mToneProxy, never()).create();
    }

    @SmallTest
    @Test
    public void testPlayToneWhenUninitialized() {
        when(mResources.getBoolean(1)).thenReturn(false);
        when(mToneProxy.isPresent()).thenReturn(false);
        mPlayer.onForegroundCallChanged(null, mCall);
        mPlayer.playTone(mCall, '9');
        waitForHandlerAction(mPlayer.getHandler(), TIMEOUT);
        verify(mToneProxy, never()).startTone(anyInt(), anyInt());
    }

    @SmallTest
    @Test
    public void testPlayToneWhenInitialized() {
        when(mResources.getBoolean(1)).thenReturn(true);
        when(mToneProxy.isPresent()).thenReturn(true);
        mPlayer.onForegroundCallChanged(null, mCall);
        mPlayer.playTone(mCall, '9');
        waitForHandlerAction(mPlayer.getHandler(), TIMEOUT);
        verify(mToneProxy).startTone(eq(ToneGenerator.TONE_DTMF_9), eq(-1));
    }

    @SmallTest
    @Test
    public void testStopToneWhenUninitialized() {
        when(mResources.getBoolean(1)).thenReturn(false);
        when(mToneProxy.isPresent()).thenReturn(false);
        mPlayer.onForegroundCallChanged(null, mCall);
        mPlayer.stopTone(mCall);
        waitForHandlerAction(mPlayer.getHandler(), TIMEOUT);
        verify(mToneProxy, never()).stopTone();
    }

    @SmallTest
    @Test
    public void testStopToneWhenInitialized() {
        when(mResources.getBoolean(1)).thenReturn(true);
        when(mToneProxy.isPresent()).thenReturn(true);
        mPlayer.onForegroundCallChanged(null, mCall);
        mPlayer.stopTone(mCall);
        waitForHandlerAction(mPlayer.getHandler(), TIMEOUT);
        verify(mToneProxy).stopTone();
    }

    @SmallTest
    @Test
    public void testProperTeardown() {
        when(mResources.getBoolean(1)).thenReturn(true);
        when(mToneProxy.isPresent()).thenReturn(true);
        mPlayer.onForegroundCallChanged(null, mCall);
        mPlayer.onForegroundCallChanged(mCall, null);
        waitForHandlerAction(mPlayer.getHandler(), TIMEOUT);
        verify(mToneProxy).release();
    }
}
