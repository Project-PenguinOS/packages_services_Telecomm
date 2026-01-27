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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Binder;
import android.os.UserHandle;
import android.os.UserManager;
import com.android.internal.telecom.ITelecomService;
import com.android.server.telecom.TelecomShellCommand;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import java.io.FileDescriptor;

public class TelecomShellCommandTest extends TelecomTestCase {

    @Mock
    private ITelecomService.Stub mTelecomService;
    @Mock
    private UserManager mUserManager;

    private TelecomShellCommand mShellCommand;
    private final Binder mBinder = new Binder();
    private final FileDescriptor mFd = new FileDescriptor();

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mShellCommand = new TelecomShellCommand(mTelecomService, mContext);
        when(mContext.getSystemService(UserManager.class)).thenReturn(mUserManager);
        when(mUserManager.getUserForSerialNumber(1L)).thenReturn(UserHandle.of(1));
    }

    private void exec(String... args) {
        mShellCommand.exec(mBinder, mFd, mFd, mFd, args);
    }

    @Test
    public void testSetPhoneAccountEnabled() throws Exception {
        exec("set-phone-account-enabled", "com.test/.MyClass", "test_id", "1");
        verify(mTelecomService).enablePhoneAccount(any(), eq(true));
    }

    @Test
    public void testSetPhoneAccountDisabled() throws Exception {
        exec("set-phone-account-disabled", "com.test/.MyClass", "test_id", "1");
        verify(mTelecomService).enablePhoneAccount(any(), eq(false));
    }

    @Test
    public void testRegisterPhoneAccount() throws Exception {
        exec("register-phone-account", "com.test/.MyClass", "test_id", "1", "Test");
        verify(mTelecomService).registerPhoneAccount(any(), anyString());
    }

    @Test
    public void testUnregisterPhoneAccount() throws Exception {
        exec("unregister-phone-account", "com.test/.MyClass", "test_id", "1");
        verify(mTelecomService).unregisterPhoneAccount(any(), anyString());
    }

    @Test
    public void testSetDefaultDialer() throws Exception {
        exec("set-default-dialer", "com.test.package");
        verify(mTelecomService).setTestDefaultDialer("com.test.package");
    }

    @Test
    public void testGetDefaultDialer() throws Exception {
        exec("get-default-dialer");
        verify(mTelecomService).getDefaultDialerPackage(anyString());
    }

    @Test
    public void testSetSystemDialer() throws Exception {
        exec("set-system-dialer", "com.test/.MyClass");
        verify(mTelecomService).setSystemDialer(any());
    }

    @Test
    public void testGetSystemDialer() throws Exception {
        exec("get-system-dialer");
        verify(mTelecomService).getSystemDialerPackage(anyString());
    }

    @Test
    public void testCleanupStuckCalls() throws Exception {
        exec("cleanup-stuck-calls");
        verify(mTelecomService).cleanupStuckCalls();
    }
}
