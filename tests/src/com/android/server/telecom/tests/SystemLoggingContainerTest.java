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

import androidx.test.filters.SmallTest;

import com.android.server.telecom.SystemLoggingContainer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SystemLoggingContainerTest extends TelecomTestCase {

    private SystemLoggingContainer mSystemLoggingContainer;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mSystemLoggingContainer = new SystemLoggingContainer();
    }

    @SmallTest
    @Test
    public void testLogging() {
        mSystemLoggingContainer.v("TAG", "msg");
        mSystemLoggingContainer.d("TAG", "msg");
        mSystemLoggingContainer.i("TAG", "msg");
        mSystemLoggingContainer.w("TAG", "msg");
        mSystemLoggingContainer.e("TAG", "msg", new Throwable());
        mSystemLoggingContainer.wtf("TAG", "msg", new Throwable());
    }
}
