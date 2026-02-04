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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.telecom.PhoneAccountHandle;
import android.content.ComponentName;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.AppLabelProxy;
import com.android.server.telecom.Call;
import com.android.server.telecom.ui.CallStreamingNotification;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

import java.util.concurrent.Executor;

@RunWith(JUnit4.class)
public class CallStreamingNotificationTest extends TelecomTestCase {

    @Mock
    private AppLabelProxy mAppLabelProxy;
    private Executor mExecutor = Runnable::run;

    private CallStreamingNotification mCallStreamingNotification;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mCallStreamingNotification = new CallStreamingNotification(mContext, mAppLabelProxy,
                mExecutor, mFeatureFlags);
        when(mAppLabelProxy.getAppLabel(anyString(), any())).thenReturn("AppName");
    }

    @SmallTest
    @Test
    public void testOnCallAddedStreaming() {
        Call call = mock(Call.class);
        when(call.isStreaming()).thenReturn(true);
        when(call.getId()).thenReturn("1");
        when(call.getTargetPhoneAccount()).thenReturn(new PhoneAccountHandle(
                new ComponentName("pkg", "cls"), "id"));

        mCallStreamingNotification.onCallAdded(call);

        verify(call).addListener(mCallStreamingNotification);
    }

    @SmallTest
    @Test
    public void testOnCallStreamingStateChanged() {
        Call call = mock(Call.class);
        when(call.getId()).thenReturn("1");
        when(call.getTargetPhoneAccount()).thenReturn(new PhoneAccountHandle(
                new ComponentName("pkg", "cls"), "id"));

        mCallStreamingNotification.onCallStreamingStateChanged(call, true);
        verify(call).addListener(mCallStreamingNotification);

        mCallStreamingNotification.onCallStreamingStateChanged(call, false);
        verify(call).removeListener(mCallStreamingNotification);
    }
}
