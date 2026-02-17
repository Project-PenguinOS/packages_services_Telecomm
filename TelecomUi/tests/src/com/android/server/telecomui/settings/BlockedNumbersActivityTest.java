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

package com.android.server.telecomui.settings;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.telecomui.R;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BlockedNumbersActivityTest {

    @Rule
    public ActivityTestRule<BlockedNumbersActivity> mActivityRule =
            new ActivityTestRule<>(BlockedNumbersActivity.class);

    @Test
    public void testActivityLaunch() {
        BlockedNumbersActivity activity = mActivityRule.getActivity();
        assertNotNull("Activity should be launched", activity);

        TextView addButton = activity.findViewById(R.id.add_blocked);
        assertNotNull("Add button should exist", addButton);
    }

    @Test
    public void testOnClickAddButton() throws Throwable {
        BlockedNumbersActivity activity = mActivityRule.getActivity();
        View addButton = activity.findViewById(R.id.add_blocked);

        mActivityRule.runOnUiThread(() -> {
            addButton.performClick();
        });
    }

    @Test
    public void testOnTextChanged() throws Throwable {
        BlockedNumbersActivity activity = mActivityRule.getActivity();

        mActivityRule.runOnUiThread(() -> {
            activity.onTextChanged("123", 0, 0, 3);
        });
    }

    @Test
    public void testOnBlocked() throws Throwable {
        BlockedNumbersActivity activity = mActivityRule.getActivity();
        View addButton = activity.findViewById(R.id.add_blocked);

        mActivityRule.runOnUiThread(() -> {
            addButton.setEnabled(false);
            activity.onBlocked("12345", false);
            assertTrue("Add button should be re-enabled", addButton.isEnabled());
        });
    }

    @Test
    public void testOnOptionsItemSelected_Home() throws Throwable {
        BlockedNumbersActivity activity = mActivityRule.getActivity();
        MenuItem item = mock(MenuItem.class);
        when(item.getItemId()).thenReturn(android.R.id.home);

        mActivityRule.runOnUiThread(() -> {
            boolean result = activity.onOptionsItemSelected(item);
            assertTrue("Menu item selection should be handled", result);
            assertTrue("Activity should be finishing", activity.isFinishing());
        });
    }

    @Test
    public void testIsEmergencyNumber() {
        BlockedNumbersActivity activity = mActivityRule.getActivity();
        BlockedNumbersActivity.isEmergencyNumber(activity, "911");
    }
}