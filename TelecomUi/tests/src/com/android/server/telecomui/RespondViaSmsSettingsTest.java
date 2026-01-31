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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.preference.Preference;
import android.view.MenuItem;

import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RespondViaSmsSettingsTest {

    @Rule
    public ActivityTestRule<RespondViaSmsSettings> mActivityRule =
            new ActivityTestRule<>(RespondViaSmsSettings.class);

    @Test
    public void testActivityLaunch() {
        RespondViaSmsSettings activity = mActivityRule.getActivity();
        assertNotNull("Activity should be launched", activity);
    }

    @Test
    public void testPreferenceChange() throws Throwable {
        RespondViaSmsSettings activity = mActivityRule.getActivity();
        Preference pref = activity.findPreference(QuickResponseUtils.KEY_CANNED_RESPONSE_PREF_1);
        assertNotNull("Preference should exist", pref);

        String newValue = "New Response Text";
        mActivityRule.runOnUiThread(() -> {
            boolean result = activity.onPreferenceChange(pref, newValue);
            assertTrue("Preference change should be accepted", result);
        });

        assertEquals("Preference title should be updated", newValue, pref.getTitle().toString());
    }

    @Test
    public void testPreferenceChange_EmptyValue() throws Throwable {
        RespondViaSmsSettings activity = mActivityRule.getActivity();
        Preference pref = activity.findPreference(QuickResponseUtils.KEY_CANNED_RESPONSE_PREF_1);
        String oldTitle = (String) pref.getTitle();

        String newValue = "";
        mActivityRule.runOnUiThread(() -> {
            boolean result = activity.onPreferenceChange(pref, newValue);
            assertTrue("Preference change should still be accepted (returns true)", result);
        });

        assertEquals("Preference title should NOT be updated to empty string",
                oldTitle, pref.getTitle().toString());
    }

    @Test
    public void testOnOptionsItemSelected_Home() throws Throwable {
        RespondViaSmsSettings activity = mActivityRule.getActivity();
        MenuItem item = mock(MenuItem.class);
        when(item.getItemId()).thenReturn(android.R.id.home);

        mActivityRule.runOnUiThread(() -> {
            boolean result = activity.onOptionsItemSelected(item);
            assertTrue("Menu item selection should be handled", result);
            assertTrue("Activity should be finishing", activity.isFinishing());
        });
    }
}