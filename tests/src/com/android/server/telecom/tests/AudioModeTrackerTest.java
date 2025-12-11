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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.media.AudioManager;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.flag.junit.SetFlagsRule;
import android.telecom.Log;
import android.telecom.flags.Flags;
import android.util.IndentingPrintWriter;

import com.android.server.telecom.AudioModeTracker;
import com.android.server.telecom.AudioModeTracker.AudioModeListener;
import com.android.server.telecom.TelecomSystem;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.Executor;

@RunWith(JUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_LOCAL_VOICEMAIL)
public class AudioModeTrackerTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    // A direct executor that runs tasks on the same thread.
    private static class DirectExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    @Mock
    private AudioManager mMockAudioManager;
    @Mock
    private AudioModeListener mMockListener1;
    @Mock
    private AudioModeListener mMockListener2;
    @Mock
    private TelecomSystem.SyncRoot mMockLock;
    @Mock
    private Log mMockLog; // To prevent static calls to Log from crashing tests

    private AudioModeTracker mAudioModeTracker;
    private Executor mDirectExecutor;
    private AudioManager.OnModeChangedListener mOnModeChangedListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDirectExecutor = new DirectExecutor();
        // A real lock object is used to ensure thread safety logic is tested correctly.
        mMockLock = new TelecomSystem.SyncRoot() {
        };

        // Capture the listener registered with AudioManager
        ArgumentCaptor<AudioManager.OnModeChangedListener> listenerCaptor =
                ArgumentCaptor.forClass(AudioManager.OnModeChangedListener.class);

        // Set initial audio mode
        when(mMockAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);

        // Instantiate the class under test
        mAudioModeTracker = new AudioModeTracker(mMockAudioManager, mDirectExecutor, mMockLock);

        // Verify that the listener was registered and capture it for later use
        verify(mMockAudioManager).addOnModeChangedListener(eq(mDirectExecutor),
                listenerCaptor.capture());
        mOnModeChangedListener = listenerCaptor.getValue();
    }

    @Test
    public void testConstructor_initializesAndRegistersListener() {
        // Verify that the initial audio mode was fetched from AudioManager
        assertEquals(AudioManager.MODE_NORMAL, mAudioModeTracker.getAudioMode());
        // Verification of listener registration is done in setUp()
    }

    @Test
    public void testOnModeChanged_notifiesListenersOnModeChange() {
        mAudioModeTracker.addListener(mMockListener1);
        mAudioModeTracker.addListener(mMockListener2);

        // Simulate a mode change event from AudioManager
        mOnModeChangedListener.onModeChanged(AudioManager.MODE_IN_CALL);

        // Verify the new mode is cached
        assertEquals(AudioManager.MODE_IN_CALL, mAudioModeTracker.getAudioMode());

        // Verify all registered listeners were notified with the new mode
        verify(mMockListener1).onAudioModeChanged(AudioManager.MODE_IN_CALL);
        verify(mMockListener2).onAudioModeChanged(AudioManager.MODE_IN_CALL);
    }

    @Test
    public void testOnModeChanged_doesNothingOnSameMode() {
        mAudioModeTracker.addListener(mMockListener1);
        int initialMode = mAudioModeTracker.getAudioMode();

        // Simulate a mode change event with the same mode
        mOnModeChangedListener.onModeChanged(initialMode);

        // Verify listeners were not notified
        verify(mMockListener1, never()).onAudioModeChanged(any(int.class));
    }

    @Test
    public void testAddAndRemoveListener() {
        // Add a listener and verify it's notified
        mAudioModeTracker.addListener(mMockListener1);
        mOnModeChangedListener.onModeChanged(AudioManager.MODE_RINGTONE);
        verify(mMockListener1).onAudioModeChanged(AudioManager.MODE_RINGTONE);

        // Remove the listener and verify it's no longer notified
        mAudioModeTracker.removeListener(mMockListener1);
        mOnModeChangedListener.onModeChanged(AudioManager.MODE_NORMAL);
        // The previous call was the only one, so verify it was only called once.
        verify(mMockListener1, times(1)).onAudioModeChanged(any(int.class));
    }

    @Test
    public void testAddRemoveNullListener_doesNotCrash() {
        mAudioModeTracker.addListener(null);
        mAudioModeTracker.removeListener(null);
        // No exception should be thrown
    }

    @Test
    public void testGetAudioMode_returnsCurrentMode() {
        assertEquals(AudioManager.MODE_NORMAL, mAudioModeTracker.getAudioMode());
        mOnModeChangedListener.onModeChanged(AudioManager.MODE_IN_COMMUNICATION);
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, mAudioModeTracker.getAudioMode());
    }

    @Test
    public void testDump_writesHistoryToPrintWriter() {
        StringWriter stringWriter = new StringWriter();
        IndentingPrintWriter pw = new IndentingPrintWriter(new PrintWriter(stringWriter));

        // Simulate a few mode changes
        mOnModeChangedListener.onModeChanged(AudioManager.MODE_IN_CALL);
        mOnModeChangedListener.onModeChanged(AudioManager.MODE_NORMAL);

        mAudioModeTracker.dump(pw);
        String output = pw.toString();
        // The LocalLog adds its own formatting, so we check for containment
        // instead of exact equality.
        assert (output.contains("Audio Mode History:"));
        assert (output.contains("MODE_NORMAL -> MODE_IN_CALL"));
        assert (output.contains("MODE_IN_CALL -> MODE_NORMAL"));
    }

    @Test
    public void testAudioModeToString_returnsCorrectStrings() {
        assertEquals("MODE_INVALID", AudioModeTracker.audioModeToString(
                AudioManager.MODE_INVALID));
        assertEquals("MODE_CURRENT", AudioModeTracker.audioModeToString(AudioManager.MODE_CURRENT));
        assertEquals("MODE_NORMAL", AudioModeTracker.audioModeToString(AudioManager.MODE_NORMAL));
        assertEquals("MODE_RINGTONE",
                AudioModeTracker.audioModeToString(AudioManager.MODE_RINGTONE));
        assertEquals("MODE_IN_CALL", AudioModeTracker.audioModeToString(AudioManager.MODE_IN_CALL));
        assertEquals("MODE_IN_COMMUNICATION",
                AudioModeTracker.audioModeToString(AudioManager.MODE_IN_COMMUNICATION));
        assertEquals("MODE_CALL_SCREENING",
                AudioModeTracker.audioModeToString(AudioManager.MODE_CALL_SCREENING));
        assertEquals("MODE_CALL_REDIRECT",
                AudioModeTracker.audioModeToString(AudioManager.MODE_CALL_REDIRECT));
        assertEquals("MODE_COMMUNICATION_REDIRECT",
                AudioModeTracker.audioModeToString(AudioManager.MODE_COMMUNICATION_REDIRECT));
        assertEquals("MODE_UNKNOWN_99", AudioModeTracker.audioModeToString(99));
    }
}
