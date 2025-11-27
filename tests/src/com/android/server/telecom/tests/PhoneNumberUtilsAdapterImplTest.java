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
 * limitations under the License.
 */

package com.android.server.telecom.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.telephony.TelephonyManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.telecom.flags.FeatureFlags;
import com.android.server.telecom.PhoneNumberUtilsAdapterImpl;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class PhoneNumberUtilsAdapterImplTest {

    @Mock private Context mContext;
    @Mock private FeatureFlags mFeatureFlags;
    @Mock private TelephonyManager mTelephonyManager;

    private PhoneNumberUtilsAdapterImpl mAdapter;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        // Default behavior: system service is available
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);

        // Initialize the adapter
        mAdapter = new PhoneNumberUtilsAdapterImpl(mContext, mFeatureFlags);
    }

    @Test
    @SmallTest
    public void testIsSamePhoneNumber_FlagDisabled_UsesCompare() {
        when(mFeatureFlags.useAreSamePhoneNumber()).thenReturn(false);

        String number1 = "6505551234";
        String number2 = "650-555-1234";

        // Execute
        boolean result = mAdapter.isSamePhoneNumber(number1, number2);

        // Verify: Should rely on legacy PhoneNumberUtils.compare, NOT fetch TelephonyManager
        verify(mContext, never()).getSystemService(TelephonyManager.class);
        assertTrue("Numbers should match using legacy compare", result);
    }

    @Test
    @SmallTest
    public void testIsSamePhoneNumber_FlagEnabled_UsesAreSamePhoneNumberWithIso() {
        when(mFeatureFlags.useAreSamePhoneNumber()).thenReturn(true);
        when(mTelephonyManager.getNetworkCountryIso()).thenReturn("US");

        String number1 = "6505551234";
        String number2 = "650-555-1234";

        // Execute
        boolean result = mAdapter.isSamePhoneNumber(number1, number2);

        // Verify: Should fetch TelephonyManager and country ISO
        verify(mContext).getSystemService(TelephonyManager.class);
        verify(mTelephonyManager).getNetworkCountryIso();
        assertTrue("Numbers should match using ISO-based compare", result);
    }

    @Test
    @SmallTest
    public void testIsSamePhoneNumber_FlagEnabled_ServiceNull() {
        when(mFeatureFlags.useAreSamePhoneNumber()).thenReturn(true);
        // Simulate TelephonyManager not being available
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(null);

        String number1 = "6505551234";
        String number2 = "650-555-1234";

        // Execute
        boolean result = mAdapter.isSamePhoneNumber(number1, number2);

        // Verify: Should handle null service gracefully and not crash
        verify(mContext).getSystemService(TelephonyManager.class);
        assertFalse("Should fall back to legacy compare if TM is null", result);
    }

    @Test
    @SmallTest
    public void testIsSamePhoneNumber_NumbersNotMatch() {
        // Case 1: Flag Disabled
        when(mFeatureFlags.useAreSamePhoneNumber()).thenReturn(false);
        String number1 = "6505551234";
        String number2 = "6505551235"; // Different number
        assertFalse("Numbers should NOT match", mAdapter.isSamePhoneNumber(number1, number2));

        // Case 2: Flag Enabled
        when(mFeatureFlags.useAreSamePhoneNumber()).thenReturn(true);
        when(mTelephonyManager.getNetworkCountryIso()).thenReturn("US");
        assertFalse("Numbers should NOT match", mAdapter.isSamePhoneNumber(number1, number2));
    }

    @Test
    @SmallTest
    public void testIsUriNumber() {
        String uri = "test@example.com";
        assertTrue("Should identify valid URI number", mAdapter.isUriNumber(uri));
    }

    @Test
    @SmallTest
    public void testIsUriNumber_NotUri() {
        String number = "6505551234";
        assertFalse("Should NOT identify regular number as URI", mAdapter.isUriNumber(number));
    }

    @Test
    @SmallTest
    public void testGetNumberFromIntent() {
        Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:1234567890"));
        String result = mAdapter.getNumberFromIntent(intent, mContext);
        assertEquals("1234567890", result);
    }

    @Test
    @SmallTest
    public void testGetNumberFromIntent_NullUri() {
        Intent intent = new Intent(Intent.ACTION_CALL);
        // Intent with no data URI should return null
        String result = mAdapter.getNumberFromIntent(intent, mContext);
        assertNull("Should return null for intent with no URI", result);
    }

    @Test
    @SmallTest
    public void testConvertKeypadLettersToDigits() {
        String input = "1-800-GOOG-411";
        String result = mAdapter.convertKeypadLettersToDigits(input);
        assertEquals("1-800-4664-411", result);
    }

    @Test
    @SmallTest
    public void testConvertKeypadLettersToDigits_NonNumeric() {
        String input = "abcde";
        String result = mAdapter.convertKeypadLettersToDigits(input);
        assertEquals("22233", result);
    }

    @Test
    @SmallTest
    public void testStripSeparators() {
        String input = "(650) 555-1234";
        String result = mAdapter.stripSeparators(input);
        assertEquals("6505551234", result);
    }

    @Test
    @SmallTest
    public void testStripSeparators_WithSpecialChars() {
        String input = "650.555/1234";
        String result = mAdapter.stripSeparators(input);
        assertEquals("6505551234", result);
    }

    @Test
    @SmallTest
    public void testConvertKeypadLettersToDigits_EmptyString() {
        String input = "";
        String result = mAdapter.convertKeypadLettersToDigits(input);
        assertEquals("", result);
    }

    @Test
    @SmallTest
    public void testConvertKeypadLettersToDigits_MixedAlphaNumeric() {
        String input = "abc123";
        String result = mAdapter.convertKeypadLettersToDigits(input);
        assertEquals("222123", result);
    }
}
