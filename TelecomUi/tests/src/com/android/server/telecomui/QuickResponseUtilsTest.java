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

package com.android.server.telecomui;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class QuickResponseUtilsTest {

    @Mock private Context mContext;
    @Mock private Context mTelephonyContext;
    @Mock private SharedPreferences mPrefs;
    @Mock private SharedPreferences.Editor mEditor;
    @Mock private SharedPreferences mOldPrefs;
    @Mock private Resources mResources;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mPrefs);
        when(mContext.createPackageContext(anyString(), anyInt())).thenReturn(mTelephonyContext);
        when(mContext.getResources()).thenReturn(mResources);
        when(mTelephonyContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mOldPrefs);
        when(mPrefs.edit()).thenReturn(mEditor);
        when(mEditor.putString(anyString(), anyString())).thenReturn(mEditor);
        when(mEditor.remove(anyString())).thenReturn(mEditor);

        // Default behavior for getString to avoid NPEs
        when(mPrefs.getString(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(mOldPrefs.getString(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(mResources.getString(anyInt())).thenReturn("Default");
    }

    @Test
    public void testMaybeMigrateLegacyQuickResponses_MigrationNeeded() {
        // Setup: Telecom prefs empty, Telephony prefs exist
        when(mPrefs.contains(anyString())).thenReturn(false);
        when(mOldPrefs.contains(QuickResponseUtils.KEY_CANNED_RESPONSE_PREF_1)).thenReturn(true);

        when(mOldPrefs.getString(eq(QuickResponseUtils.KEY_CANNED_RESPONSE_PREF_1), anyString()))
                .thenReturn("Response 1");
        when(mOldPrefs.getString(eq(QuickResponseUtils.KEY_CANNED_RESPONSE_PREF_2), anyString()))
                .thenReturn("Response 2");
        when(mOldPrefs.getString(eq(QuickResponseUtils.KEY_CANNED_RESPONSE_PREF_3), anyString()))
                .thenReturn("Response 3");
        when(mOldPrefs.getString(eq(QuickResponseUtils.KEY_CANNED_RESPONSE_PREF_4), anyString()))
                .thenReturn("Response 4");

        QuickResponseUtils.maybeMigrateLegacyQuickResponses(mContext);

        // Verify migration
        verify(mEditor).putString(QuickResponseUtils.KEY_CANNED_RESPONSE_PREF_1, "Response 1");
        verify(mEditor).putString(QuickResponseUtils.KEY_CANNED_RESPONSE_PREF_2, "Response 2");
        verify(mEditor).putString(QuickResponseUtils.KEY_CANNED_RESPONSE_PREF_3, "Response 3");
        verify(mEditor).putString(QuickResponseUtils.KEY_CANNED_RESPONSE_PREF_4, "Response 4");
        verify(mEditor, atLeastOnce()).commit();
    }

    @Test
    public void testMaybeMigrateLegacyQuickResponses_AlreadyExists() {
        // Setup: Telecom prefs exist
        when(mPrefs.contains(QuickResponseUtils.KEY_CANNED_RESPONSE_PREF_1)).thenReturn(true);

        QuickResponseUtils.maybeMigrateLegacyQuickResponses(mContext);

        // Verify no migration
        verify(mEditor, never()).commit();
    }

    @Test
    public void testMaybeResetQuickResponses_ResetNeeded() {
        String defaultResponse = "Default";
        // All values are default, so all should be reset
        when(mPrefs.getString(anyString(), eq(""))).thenReturn(defaultResponse);

        QuickResponseUtils.maybeResetQuickResponses(mContext, mPrefs);

        verify(mEditor, times(4)).remove(anyString());
        verify(mEditor, times(4)).apply();
    }

    @Test
    public void testMaybeResetQuickResponses_NoResetNeeded() {
        String customResponse = "Custom";
        when(mPrefs.getString(anyString(), eq(""))).thenReturn(customResponse);

        QuickResponseUtils.maybeResetQuickResponses(mContext, mPrefs);

        verify(mEditor, never()).remove(anyString());
    }
}
