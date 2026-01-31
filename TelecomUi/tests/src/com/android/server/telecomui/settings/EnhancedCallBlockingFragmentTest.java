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

import android.app.FragmentTransaction;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class EnhancedCallBlockingFragmentTest {

    @Rule
    public ActivityTestRule<TestActivity> mActivityRule =
            new ActivityTestRule<>(TestActivity.class);

    @Test
    public void testOnPreferenceChange() throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            EnhancedCallBlockingFragment fragment = new EnhancedCallBlockingFragment();
            FragmentTransaction ft = mActivityRule.getActivity().getFragmentManager()
                    .beginTransaction();
            ft.add(android.R.id.content, fragment);
            ft.commitNowAllowingStateLoss();

            android.preference.Preference pref = new android.preference.Preference(
                    mActivityRule.getActivity());
            pref.setKey("block_numbers_not_in_contacts_setting");

            boolean result = fragment.onPreferenceChange(pref, true);
            assertTrue("Preference change should be accepted", result);
        });
    }
}
