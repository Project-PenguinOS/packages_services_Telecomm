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
import static org.mockito.Mockito.verify;

import android.telecom.DisconnectCause;
import android.telecom.StreamingCall;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.Call;
import com.android.server.telecom.StreamingCallAdapter;
import com.android.server.telecom.TransactionalServiceWrapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

@RunWith(JUnit4.class)
public class StreamingCallAdapterTest extends TelecomTestCase {

    @Mock
    private TransactionalServiceWrapper mTransactionalServiceWrapper;
    @Mock
    private Call mCall;

    private StreamingCallAdapter mStreamingCallAdapter;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mStreamingCallAdapter = new StreamingCallAdapter(
                mTransactionalServiceWrapper, mCall, "pkg");
    }

    @SmallTest
    @Test
    public void testSetStreamingStateStreaming() throws Exception {
        mStreamingCallAdapter.setStreamingState(StreamingCall.STATE_STREAMING);
        verify(mTransactionalServiceWrapper).onSetActive(mCall);
    }

    @SmallTest
    @Test
    public void testSetStreamingStateHolding() throws Exception {
        mStreamingCallAdapter.setStreamingState(StreamingCall.STATE_HOLDING);
        verify(mTransactionalServiceWrapper).onSetInactive(mCall);
    }

    @SmallTest
    @Test
    public void testSetStreamingStateDisconnected() throws Exception {
        mStreamingCallAdapter.setStreamingState(StreamingCall.STATE_DISCONNECTED);
        verify(mTransactionalServiceWrapper).onDisconnect(eq(mCall), any(DisconnectCause.class));
    }
}
