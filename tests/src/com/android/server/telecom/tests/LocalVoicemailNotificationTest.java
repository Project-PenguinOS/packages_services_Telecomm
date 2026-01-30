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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.telecom.TelecomManager;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.AppLabelProxy;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallState;
import com.android.server.telecom.LocalVoicemailController;
import com.android.server.telecom.ui.LocalVoicemailNotification;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

import java.util.concurrent.Executor;

@RunWith(JUnit4.class)
public class LocalVoicemailNotificationTest extends TelecomTestCase {

    @Mock
    private AppLabelProxy mAppLabelProxy;
    @Mock
    private LocalVoicemailController mLocalVoicemailController;
    private Executor mExecutor = Runnable::run;

    private LocalVoicemailNotification mLocalVoicemailNotification;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mLocalVoicemailNotification = new LocalVoicemailNotification(mContext, mAppLabelProxy,
                mExecutor, mFeatureFlags, mLocalVoicemailController);
        when(mContext.getString(anyInt())).thenReturn("string");
        when(mContext.getText(anyInt())).thenReturn("text");
        when(mAppLabelProxy.getAppLabel(anyString(), any())).thenReturn("AppName");
    }

    @SmallTest
    @Test
    public void testOnCallAddedLocalVoicemail() {
        Call call = mock(Call.class);
        when(call.getState()).thenReturn(CallState.LOCAL_VOICEMAIL);
        when(call.getId()).thenReturn("1");
        when(call.getHandlePresentation()).thenReturn(TelecomManager.PRESENTATION_ALLOWED);

        mLocalVoicemailNotification.onCallAdded(call);
        // Verify notification logic - would need to mock UserUtil.processNotification
    }

    @SmallTest
    @Test
    public void testOnCallStateChanged() {
        Call call = mock(Call.class);
        when(call.getId()).thenReturn("1");
        when(call.getHandlePresentation()).thenReturn(TelecomManager.PRESENTATION_ALLOWED);

        mLocalVoicemailNotification.onCallStateChanged(call, CallState.ACTIVE,
                CallState.LOCAL_VOICEMAIL);
        // Verify tracked
    }
}
