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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.telecom.CallEndpoint;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.telecom.CachedAvailableEndpointsChange;
import com.android.server.telecom.CachedCallback;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallSourceService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.HashSet;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class CachedAvailableEndpointsChangeTest extends TelecomTestCase {

    @Mock private CallSourceService mMockCallSourceService;
    @Mock private Call mMockCall;
    @Mock private CallEndpoint mMockEndpoint1;
    @Mock private CallEndpoint mMockEndpoint2;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
    }

    @Test
    @SmallTest
    public void testGetAvailableEndpoints() {
        Set<CallEndpoint> endpoints = new HashSet<>();
        endpoints.add(mMockEndpoint1);
        CachedAvailableEndpointsChange change = new CachedAvailableEndpointsChange(endpoints);
        assertEquals(endpoints, change.getAvailableEndpoints());
    }

    @Test
    @SmallTest
    public void testGetCacheType() {
        Set<CallEndpoint> endpoints = new HashSet<>();
        CachedAvailableEndpointsChange change = new CachedAvailableEndpointsChange(endpoints);
        assertEquals(CachedCallback.TYPE_STATE, change.getCacheType());
    }

    @Test
    @SmallTest
    public void testGetCallbackId() {
        Set<CallEndpoint> endpoints = new HashSet<>();
        CachedAvailableEndpointsChange change = new CachedAvailableEndpointsChange(endpoints);
        assertEquals(CachedAvailableEndpointsChange.class.getSimpleName(), change.getCallbackId());
    }

    @Test
    @SmallTest
    public void testExecuteCallback() {
        Set<CallEndpoint> endpoints = new HashSet<>();
        endpoints.add(mMockEndpoint1);
        CachedAvailableEndpointsChange change = new CachedAvailableEndpointsChange(endpoints);
        change.executeCallback(mMockCallSourceService, mMockCall);
        verify(mMockCallSourceService).onAvailableCallEndpointsChanged
              (eq(mMockCall), eq(endpoints));
    }

    @Test
    @SmallTest
    public void testEqualsAndHashCode() {
        Set<CallEndpoint> set1 = new HashSet<>();
        set1.add(mMockEndpoint1);
        Set<CallEndpoint> set2 = new HashSet<>();
        set2.add(mMockEndpoint1);
        Set<CallEndpoint> set3 = new HashSet<>();
        set3.add(mMockEndpoint2);

        CachedAvailableEndpointsChange change1 = new CachedAvailableEndpointsChange(set1);
        CachedAvailableEndpointsChange change2 = new CachedAvailableEndpointsChange(set2);
        CachedAvailableEndpointsChange change3 = new CachedAvailableEndpointsChange(set3);

        assertEquals(change1, change2);
        assertEquals(change1.hashCode(), change2.hashCode());
        assertNotEquals(change1, change3);
        assertNotEquals(change1, null);
        assertNotEquals(change1, new Object());
    }
}
