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
 * limitations under the License
 */

package com.android.server.telecom.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.net.Uri;
import android.telecom.GatewayInfo;
import androidx.test.filters.SmallTest;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.PhoneAccountRegistrar;
import com.android.server.telecom.callredirection.CallRedirectionProcessorHelper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

@RunWith(JUnit4.class)
public class CallRedirectionProcessorHelperTest extends TelecomTestCase {

    @Mock private CallsManager mCallsManager;
    @Mock private PhoneAccountRegistrar mPhoneAccountRegistrar;

    private static final String GATEWAY_PKG = "com.android.gateway";
    private static final Uri GATEWAY_URI = Uri.parse("tel:6505550000");
    private static final Uri DESTINATION_URI = Uri.parse("tel:6505551111");
    private static final String POST_DIAL_DIGITS = ",,1234";

    @SmallTest
    @Test
    public void testGetGatewayInfoFromGatewayUri() {
        GatewayInfo info = CallRedirectionProcessorHelper.getGatewayInfoFromGatewayUri(
                GATEWAY_PKG, GATEWAY_URI, DESTINATION_URI, POST_DIAL_DIGITS);

        assertEquals(GATEWAY_PKG, info.getGatewayProviderPackageName());
        assertEquals(Uri.parse("tel:6505550000,,1234"), info.getGatewayAddress());
        assertEquals(DESTINATION_URI, info.getOriginalAddress());
    }

    @SmallTest
    @Test
    public void testGetGatewayInfoFromGatewayUriNoPostDial() {
        GatewayInfo info = CallRedirectionProcessorHelper.getGatewayInfoFromGatewayUri(
                GATEWAY_PKG, GATEWAY_URI, DESTINATION_URI, null);

        assertEquals(GATEWAY_URI, info.getGatewayAddress());
    }

    @SmallTest
    @Test
    public void testGetGatewayInfoFromGatewayUriNullPackage() {
        GatewayInfo info = CallRedirectionProcessorHelper.getGatewayInfoFromGatewayUri(
                null, GATEWAY_URI, DESTINATION_URI, POST_DIAL_DIGITS);

        assertNull(info);
    }

    @SmallTest
    @Test
    public void testGetUpdatedUriwithPostDial() {
        Uri updated = CallRedirectionProcessorHelper.getUpdatedUriwithPostDial(
                DESTINATION_URI, POST_DIAL_DIGITS);

        assertEquals(Uri.parse("tel:6505551111,,1234"), updated);
    }

    @SmallTest
    @Test
    public void testGetUpdatedUriwithPostDialNoPostDial() {
        Uri updated = CallRedirectionProcessorHelper.getUpdatedUriwithPostDial(
                DESTINATION_URI, null);

        assertEquals(DESTINATION_URI, updated);
    }

    @SmallTest
    @Test
    public void testGetUpdatedUriwithPostDialNullDestination() {
        Uri updated = CallRedirectionProcessorHelper.getUpdatedUriwithPostDial(
                null, POST_DIAL_DIGITS);

        assertNull(updated);
    }

    @SmallTest
    @Test
    public void testGetPostDialDigits() {
        CallRedirectionProcessorHelper helper = new CallRedirectionProcessorHelper(
                mContext, mCallsManager, mPhoneAccountRegistrar, mFeatureFlags);
        assertEquals(",,1234", helper.getPostDialDigits(Uri.parse("tel:6505551111,,1234")));
        assertEquals("", helper.getPostDialDigits(Uri.parse("tel:6505551111")));
    }
}
