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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.net.Uri;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.DialerCodeReceiver;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

@RunWith(JUnit4.class)
public class DialerCodeReceiverTest extends TelecomTestCase {

    @Mock
    private CallsManager mCallsManager;

    private DialerCodeReceiver mDialerCodeReceiver;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mDialerCodeReceiver = new DialerCodeReceiver(mCallsManager);
    }

    @SmallTest
    @Test
    public void testDebugOn() {
        Intent intent = new Intent(DialerCodeReceiver.SECRET_CODE_ACTION);
        intent.setData(Uri.parse("android_secret_code://"
                + DialerCodeReceiver.TELECOM_SECRET_CODE_DEBUG_ON));
        mDialerCodeReceiver.onReceive(mContext, intent);
    }

    @SmallTest
    @Test
    public void testDebugOff() {
        Intent intent = new Intent(DialerCodeReceiver.SECRET_CODE_ACTION);
        intent.setData(Uri.parse("android_secret_code://"
                + DialerCodeReceiver.TELECOM_SECRET_CODE_DEBUG_OFF));
        mDialerCodeReceiver.onReceive(mContext, intent);
    }

    @SmallTest
    @Test
    public void testMark() {
        Call call = mock(Call.class);
        when(mCallsManager.getActiveCall()).thenReturn(call);

        Intent intent = new Intent(DialerCodeReceiver.SECRET_CODE_ACTION);
        intent.setData(Uri.parse("android_secret_code://"
                + DialerCodeReceiver.TELECOM_SECRET_CODE_MARK));
        mDialerCodeReceiver.onReceive(mContext, intent);

        verify(mCallsManager).getActiveCall();
    }
}
