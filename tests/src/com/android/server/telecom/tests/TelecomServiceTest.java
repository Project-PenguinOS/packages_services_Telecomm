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

import static org.junit.Assert.assertNotNull;

import android.content.Intent;
import android.telecom.TelecomServiceInitializerRepository;
import android.telecom.service.TelecomServiceInitializer;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.components.TelecomService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TelecomServiceTest extends TelecomTestCase {

    private TelecomService mTelecomService;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mTelecomService = new TelecomService();
    }

    @SmallTest
    @Test
    public void testOnBind() {
        assertNotNull(mTelecomService.onBind(new Intent()));
    }

    @SmallTest
    @Test
    public void testTelecomServiceInitializer() {
        TelecomServiceInitializer initializer = new TelecomServiceInitializer("pkg");
        TelecomServiceInitializerRepository.setInitializer(initializer);
        assertNotNull(TelecomServiceInitializerRepository.getInitializer());

        // We don't call initialize(mContext) here because it might
        // interfere with TelecomSystem singleton
    }
}
