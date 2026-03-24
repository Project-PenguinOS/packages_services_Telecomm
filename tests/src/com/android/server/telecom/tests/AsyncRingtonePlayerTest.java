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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.media.Ringtone;
import android.net.Uri;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.AsyncRingtonePlayer;
import com.android.server.telecom.flags.FeatureFlags;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

@RunWith(JUnit4.class)
public class AsyncRingtonePlayerTest extends TelecomTestCase {

    @Mock private Ringtone mRingtone;
    @Mock private Supplier<Pair<Uri, Ringtone>> mRingtoneSupplier;
    @Mock private BiConsumer<Pair<Uri, Ringtone>, Boolean> mRingtoneConsumer;

    private AsyncRingtonePlayer mAsyncRingtonePlayer;
    private Uri mRingtoneUri = Uri.parse("content://media/internal/audio/media/1");

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mAsyncRingtonePlayer = new AsyncRingtonePlayer(mFeatureFlags);
        when(mRingtoneSupplier.get()).thenReturn(new Pair<>(mRingtoneUri, mRingtone));
    }

    @Override
    @After
    public void tearDown() throws Exception {
        mAsyncRingtonePlayer.stop();
        super.tearDown();
    }

    @SmallTest
    @Test
    public void testPlay() {
        mAsyncRingtonePlayer.play(mRingtoneSupplier, mRingtoneConsumer,
                false /* isHfpDeviceConnected */);

        verify(mRingtone, timeout(1000)).play();
        verify(mRingtoneConsumer, timeout(1000)).accept(any(), eq(false));
        assertTrue(mAsyncRingtonePlayer.isPlaying());
    }

    @SmallTest
    @Test
    public void testStop() {
        mAsyncRingtonePlayer.play(mRingtoneSupplier, mRingtoneConsumer, false);
        verify(mRingtone, timeout(1000)).play();

        mAsyncRingtonePlayer.stop();
        verify(mRingtone, timeout(1000)).stop();
        assertFalse(mAsyncRingtonePlayer.isPlaying());
    }

    @SmallTest
    @Test
    public void testPlayWithHfpDelay() throws InterruptedException {
        // HFP connected, but not active initially.
        mAsyncRingtonePlayer.play(mRingtoneSupplier, mRingtoneConsumer,
                true /* isHfpDeviceConnected */);

        // Should not play immediately
        verify(mRingtone, never()).play();
        assertTrue(mAsyncRingtonePlayer.isPlaying());

        // Now activate BT
        mAsyncRingtonePlayer.updateBtActiveState(true);

        // Should play now
        verify(mRingtone, timeout(1000)).play();
        verify(mRingtoneConsumer, timeout(1000)).accept(any(), eq(false));
    }

    @SmallTest
    @Test
    public void testStopDuringHfpDelay() throws InterruptedException {
        mAsyncRingtonePlayer.play(mRingtoneSupplier, mRingtoneConsumer,
                true /* isHfpDeviceConnected */);

        // Stop before BT becomes active
        mAsyncRingtonePlayer.stop();

        // Should not play even if we wait
        verify(mRingtone, never()).play();

        // Consumer should be called with stopped=true
        verify(mRingtoneConsumer, timeout(1000)).accept(any(), eq(true));
        assertFalse(mAsyncRingtonePlayer.isPlaying());
    }

    @SmallTest
    @Test
    public void testPlayWhenStoppedEarly() {
        // Verify mIsPlaying check at start of handlePlayExecutor

        when(mRingtoneSupplier.get()).thenReturn(null);
        mAsyncRingtonePlayer.play(mRingtoneSupplier, mRingtoneConsumer, false);

        verify(mRingtone, never()).play();
        verify(mRingtoneConsumer, timeout(1000)).accept(any(), eq(false));
    }

    @SmallTest
    @Test
    public void testUpdateBtActiveState() {
        // Verifying it doesn't crash when no latches are pending
        mAsyncRingtonePlayer.updateBtActiveState(true);
        mAsyncRingtonePlayer.updateBtActiveState(false);
    }

    @SmallTest
    @Test
    public void testExecutorRecreation() {
        mAsyncRingtonePlayer.play(mRingtoneSupplier, mRingtoneConsumer, false);
        verify(mRingtone, timeout(1000)).play();

        mAsyncRingtonePlayer.stop();
        verify(mRingtone, timeout(1000)).stop();

        // Call play again
        mAsyncRingtonePlayer.play(mRingtoneSupplier, mRingtoneConsumer, false);
        verify(mRingtone, timeout(1000).times(2)).play();
    }

    @SmallTest
    @Test
    public void testIsPlayingState() {
        assertFalse(mAsyncRingtonePlayer.isPlaying());

        mAsyncRingtonePlayer.play(mRingtoneSupplier, mRingtoneConsumer, false);
        assertTrue(mAsyncRingtonePlayer.isPlaying());

        mAsyncRingtonePlayer.stop();
        assertFalse(mAsyncRingtonePlayer.isPlaying());
    }

    @SmallTest
    @Test
    public void testGetLooper() {
        assertNotNull(mAsyncRingtonePlayer.getLooper());
    }
}
