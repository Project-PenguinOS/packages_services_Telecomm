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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.QuickResponseUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

@RunWith(JUnit4.class)
public class QuickResponseUtilsTest extends TelecomTestCase {

    @Mock
    private SharedPreferences mPrefs;
    @Mock
    private SharedPreferences.Editor mEditor;
    @Mock
    private Context mTelephonyContext;
    @Mock
    private SharedPreferences mOldPrefs;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        when(mContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mPrefs);
        when(mPrefs.edit()).thenReturn(mEditor);
        when(mEditor.putString(anyString(), anyString())).thenReturn(mEditor);
        when(mEditor.remove(anyString())).thenReturn(mEditor);
    }

    @SmallTest
    @Test
    public void testMaybeMigrateLegacyQuickResponses_AlreadyExist() throws Exception {
        when(mPrefs.contains(anyString())).thenReturn(true);
        QuickResponseUtils.maybeMigrateLegacyQuickResponses(mContext);
        verify(mContext, never()).createPackageContext(anyString(), anyInt());
    }

    @SmallTest
    @Test
    public void testMaybeMigrateLegacyQuickResponses_Migrate() throws Exception {
        when(mPrefs.contains(anyString())).thenReturn(false);
        when(mContext.createPackageContext(eq("com.android.phone"), anyInt()))
                .thenReturn(mTelephonyContext);
        when(mTelephonyContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mOldPrefs);
        when(mOldPrefs.contains(QuickResponseUtils.KEY_CANNED_RESPONSE_PREF_1)).thenReturn(true);
        when(mOldPrefs.getString(anyString(), anyString())).thenReturn("old response");

        QuickResponseUtils.maybeMigrateLegacyQuickResponses(mContext);

        verify(mEditor, times(4)).putString(anyString(), eq("old response"));
        verify(mEditor).commit();
    }

    }

