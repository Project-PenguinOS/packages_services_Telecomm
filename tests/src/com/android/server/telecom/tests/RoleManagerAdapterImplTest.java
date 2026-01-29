/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law of an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import android.app.role.RoleManager;
import android.os.UserHandle;

import com.android.server.telecom.RoleManagerAdapterImpl;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.Collections;

public class RoleManagerAdapterImplTest extends TelecomTestCase {

    @Mock private RoleManager mRoleManager;

    private RoleManagerAdapterImpl mRoleManagerAdapter;
    private final UserHandle mUserHandle = UserHandle.of(1);
    private static final String TEST_PACKAGE_NAME = "com.test.package";

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mRoleManagerAdapter = new RoleManagerAdapterImpl(mContext, mRoleManager);
    }

    @Test
    public void testGetDefaultCallRedirectionApp() {
        when(mRoleManager.getRoleHoldersAsUser(anyString(), any(UserHandle.class)))
                .thenReturn(Collections.singletonList(TEST_PACKAGE_NAME));
        String result = mRoleManagerAdapter.getDefaultCallRedirectionApp(mUserHandle);
        assertEquals(TEST_PACKAGE_NAME, result);
    }

    @Test
    public void testGetDefaultCallRedirectionAppWithOverride() {
        mRoleManagerAdapter.setTestDefaultCallRedirectionApp("override.package");
        String result = mRoleManagerAdapter.getDefaultCallRedirectionApp(mUserHandle);
        assertEquals("override.package", result);
    }

    @Test
    public void testGetDefaultCallScreeningApp() {
        when(mRoleManager.getRoleHoldersAsUser(anyString(), any(UserHandle.class)))
                .thenReturn(Collections.singletonList(TEST_PACKAGE_NAME));
        String result = mRoleManagerAdapter.getDefaultCallScreeningApp(mUserHandle);
        assertEquals(TEST_PACKAGE_NAME, result);
    }

    @Test
    public void testGetDefaultCallScreeningAppWithOverride() {
        mRoleManagerAdapter.setTestDefaultCallScreeningApp("override.package");
        String result = mRoleManagerAdapter.getDefaultCallScreeningApp(mUserHandle);
        assertEquals("override.package", result);
    }

    @Test
    public void testGetDefaultDialerApp() {
        when(mRoleManager.getRoleHoldersAsUser(anyString(), any(UserHandle.class)))
                .thenReturn(Collections.singletonList(TEST_PACKAGE_NAME));
        String result = mRoleManagerAdapter.getDefaultDialerApp(mUserHandle.getIdentifier());
        assertEquals(TEST_PACKAGE_NAME, result);
    }

    @Test
    public void testGetDefaultDialerAppWithOverride() {
        mRoleManagerAdapter.setTestDefaultDialer("override.package");
        String result = mRoleManagerAdapter.getDefaultDialerApp(mUserHandle.getIdentifier());
        assertEquals("override.package", result);
    }

    @Test
    public void testNoRoleHolder() {
        when(mRoleManager.getRoleHoldersAsUser(anyString(), any(UserHandle.class)))
                .thenReturn(Collections.emptyList());
        assertNull(mRoleManagerAdapter.getDefaultCallRedirectionApp(mUserHandle));
        assertNull(mRoleManagerAdapter.getDefaultCallScreeningApp(mUserHandle));
        assertNull(mRoleManagerAdapter.getDefaultDialerApp(mUserHandle.getIdentifier()));
    }
}
