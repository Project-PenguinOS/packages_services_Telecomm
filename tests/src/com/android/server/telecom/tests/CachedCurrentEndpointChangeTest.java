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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.verify;

import android.telecom.CallEndpoint;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.server.telecom.CachedCallback;
import com.android.server.telecom.CachedCurrentEndpointChange;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallSourceService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class CachedCurrentEndpointChangeTest {

    @Mock private CallSourceService mMockCallSourceService;
    @Mock private Call mMockCall;
    @Mock private CallEndpoint mMockCallEndpoint;
    @Mock private CallEndpoint mMockCallEndpoint2;

    private CachedCurrentEndpointChange mCachedCurrentEndpointChange;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mCachedCurrentEndpointChange = new CachedCurrentEndpointChange(mMockCallEndpoint);
    }

    @Test
    @SmallTest
    public void testGetCurrentCallEndpoint() {
        assertEquals(mMockCallEndpoint, mCachedCurrentEndpointChange.getCurrentCallEndpoint());
    }

    @Test
    @SmallTest
    public void testGetCacheType() {
        assertEquals(CachedCallback.TYPE_STATE, mCachedCurrentEndpointChange.getCacheType());
    }

    @Test
    @SmallTest
    public void testExecuteCallback() {
        mCachedCurrentEndpointChange.executeCallback(mMockCallSourceService, mMockCall);
        verify(mMockCallSourceService).onCallEndpointChanged(mMockCall, mMockCallEndpoint);
    }

    @Test
    @SmallTest
    public void testGetCallbackId() {
        assertEquals(CachedCurrentEndpointChange.ID, mCachedCurrentEndpointChange.getCallbackId());
    }

    @Test
    @SmallTest
    public void testEqualsAndHashCode() {
        CachedCurrentEndpointChange change1 = new CachedCurrentEndpointChange(mMockCallEndpoint);
        CachedCurrentEndpointChange change2 = new CachedCurrentEndpointChange(mMockCallEndpoint);
        CachedCurrentEndpointChange change3 = new CachedCurrentEndpointChange(mMockCallEndpoint2);

        assertEquals(change1, change2);
        assertEquals(change1.hashCode(), change2.hashCode());
        assertNotEquals(change1, change3);
        assertNotEquals(change1, null);
        assertNotEquals(change1, new Object());
    }
}
