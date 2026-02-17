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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.CallLog;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.util.CallLogUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.stubbing.Answer;

import java.util.Collections;

@RunWith(JUnit4.class)
public class CallLogUtilsTest extends TelecomTestCase {

    @Mock
    private UserManager mUserManager;
    @Mock
    private TelecomManager mTelecomManager;
    @Mock
    private ContentProvider mContentProvider;

    private static final PhoneAccountHandle TEST_HANDLE = new PhoneAccountHandle(
            new ComponentName("com.android.server.telecom.tests", "TestService"),
            "test_id", UserHandle.of(0));

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        when(mContext.getSystemService(UserManager.class)).thenReturn(mUserManager);
        when(mContext.getSystemService(TelecomManager.class)).thenReturn(mTelecomManager);

        when(mContext.getContentResolver()).thenReturn(ContentResolver.wrap(mContentProvider));

        Answer<Uri> insertAnswer = invocation -> invocation.getArgument(0);

        doAnswer(insertAnswer).when(mContentProvider).insert(any(Uri.class),
                any(ContentValues.class));
        doAnswer(insertAnswer).when(mContentProvider).insert(any(Uri.class),
                any(ContentValues.class), any());

        when(mContentProvider.delete(any(Uri.class), any(), any())).thenReturn(0);
        when(mContentProvider.delete(any(Uri.class), any(String.class), any())).thenReturn(0);
        when(mContentProvider.delete(any(Uri.class), any(Bundle.class))).thenReturn(0);
    }

    @SmallTest
    @Test
    public void testAddCallBasic() {
        setupMocks(true);
        CallLogUtils.addCall(null, mContext, "12345", CallLog.Calls.PRESENTATION_ALLOWED,
                CallLog.Calls.INCOMING_TYPE, 0, TEST_HANDLE, 1000L, 10, null, 0, 0);

        ArgumentCaptor<ContentValues> captor = ArgumentCaptor.forClass(ContentValues.class);
        verify(mContentProvider, atLeastOnce()).insert(any(Uri.class), captor.capture(), isNull());
        assertEquals("12345", captor.getValue().getAsString(CallLog.Calls.NUMBER));
    }

    @SmallTest
    @Test
    public void testAddCallRestricted() {
        setupMocks(true);
        CallLogUtils.addCall(null, mContext, "12345", CallLog.Calls.PRESENTATION_RESTRICTED,
                CallLog.Calls.INCOMING_TYPE, 0, TEST_HANDLE, 1000L, 10, null, 0, 0);

        ArgumentCaptor<ContentValues> captor = ArgumentCaptor.forClass(ContentValues.class);
        verify(mContentProvider, atLeastOnce()).insert(any(Uri.class), captor.capture(), isNull());
        assertEquals("", captor.getValue().getAsString(CallLog.Calls.NUMBER));
        assertEquals(Integer.valueOf(CallLog.Calls.PRESENTATION_RESTRICTED),
                captor.getValue().getAsInteger(CallLog.Calls.NUMBER_PRESENTATION));
    }

    @SmallTest
    @Test
    public void testAddCallShadow() {
        setupMocks(false);
        CallLogUtils.addCall(null, mContext, "12345", CallLog.Calls.PRESENTATION_ALLOWED,
                CallLog.Calls.INCOMING_TYPE, 0, TEST_HANDLE, 1000L, 10, null, 0, 0);

        ArgumentCaptor<Uri> uriCaptor = ArgumentCaptor.forClass(Uri.class);
        verify(mContentProvider, atLeastOnce()).insert(uriCaptor.capture(),
                any(ContentValues.class), isNull());
        assertTrue(uriCaptor.getValue().toString().contains("call_log_shadow"));
    }

    @SmallTest
    @Test
    public void testPresentationTypes() {
        setupMocks(true);

        // PAYPHONE
        CallLogUtils.addCall(null, mContext, "12345", CallLog.Calls.PRESENTATION_PAYPHONE,
                CallLog.Calls.INCOMING_TYPE, 0, TEST_HANDLE, 1000L, 10, null, 0, 0);
        ArgumentCaptor<ContentValues> captor = ArgumentCaptor.forClass(ContentValues.class);
        verify(mContentProvider, atLeastOnce()).insert(any(Uri.class), captor.capture(), isNull());
        assertEquals(Integer.valueOf(CallLog.Calls.PRESENTATION_PAYPHONE),
                captor.getValue().getAsInteger(CallLog.Calls.NUMBER_PRESENTATION));
        clearInvocations(mContentProvider);

        // UNAVAILABLE
        CallLogUtils.addCall(null, mContext, "12345", CallLog.Calls.PRESENTATION_UNAVAILABLE,
                CallLog.Calls.INCOMING_TYPE, 0, TEST_HANDLE, 1000L, 10, null, 0, 0);
        verify(mContentProvider, atLeastOnce()).insert(any(Uri.class), captor.capture(), isNull());
        assertEquals(Integer.valueOf(CallLog.Calls.PRESENTATION_UNAVAILABLE),
                captor.getValue().getAsInteger(CallLog.Calls.NUMBER_PRESENTATION));
        clearInvocations(mContentProvider);

        // UNKNOWN (Empty number)
        CallLogUtils.addCall(null, mContext, "", CallLog.Calls.PRESENTATION_ALLOWED,
                CallLog.Calls.INCOMING_TYPE, 0, TEST_HANDLE, 1000L, 10, null, 0, 0);
        verify(mContentProvider, atLeastOnce()).insert(any(Uri.class), captor.capture(), isNull());
        assertEquals(Integer.valueOf(CallLog.Calls.PRESENTATION_UNKNOWN),
                captor.getValue().getAsInteger(CallLog.Calls.NUMBER_PRESENTATION));
    }

    private void setupMocks(boolean unlocked) {
        when(mUserManager.isUserUnlocked(any(UserHandle.class))).thenReturn(unlocked);
        when(mUserManager.isUserRunning(any(UserHandle.class))).thenReturn(true);
        when(mUserManager.getUserHandles(any(boolean.class)))
                .thenReturn(Collections.singletonList(UserHandle.of(0)));
    }
}
