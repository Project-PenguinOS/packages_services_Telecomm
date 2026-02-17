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

package com.android.server.telecomui.ui;

import static org.junit.Assert.assertNotNull;

import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ConfirmCallDialogActivityTest {

    @Rule
    public ActivityTestRule<ConfirmCallDialogActivity> mActivityRule =
            new ActivityTestRule<>(ConfirmCallDialogActivity.class);

    @Test
    public void testActivityLaunch_WithExtras() {
        android.content.Intent intent = new android.content.Intent();
        intent.putExtra("android.telecom.extra.ONGOING_PHONE_ACCOUNT_HANDLE",
                new android.telecom.PhoneAccountHandle(
                        new android.content.ComponentName("pkg", "cls"), "id"));
        mActivityRule.launchActivity(intent);
        ConfirmCallDialogActivity activity = mActivityRule.getActivity();
        assertNotNull("Activity should be launched", activity);
    }
}
