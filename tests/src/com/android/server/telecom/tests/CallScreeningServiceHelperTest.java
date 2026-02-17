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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telecom.CallScreeningService;
import android.telecom.ParcelableCallResponse;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.telecom.ICallScreeningService;
import com.android.server.telecom.AppLabelProxy;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallScreeningServiceHelper;
import com.android.server.telecom.ParcelableCallUtils;
import com.android.server.telecom.TelecomSystem;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

@RunWith(JUnit4.class)
public class CallScreeningServiceHelperTest extends TelecomTestCase {
    @Mock
    private TelecomSystem.SyncRoot mLock;
    @Mock
    private ParcelableCallUtils.Converter mConverter;
    @Mock
    private Call mCall;
    @Mock
    private AppLabelProxy mAppLabelProxy;
    @Mock
    private IBinder mBinder;
    @Mock
    private ICallScreeningService mCallScreeningService;

    private PackageManager mPackageManager;
    private static final String PKG_NAME = "com.android.services.telecom.tests";
    private static final String CLS_NAME = "CallScreeningService";
    private static final ComponentName COMPONENT_NAME = new ComponentName(PKG_NAME, CLS_NAME);
    private static final UserHandle USER_HANDLE = UserHandle.of(10);

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mPackageManager = mContext.getPackageManager();
        when(mBinder.queryLocalInterface(anyString())).thenReturn(mCallScreeningService);
        doReturn(mBinder).when(mCallScreeningService).asBinder();
        // Force context operations through the spy.
        doReturn(mContext).when(mContext).getApplicationContext();
        doReturn(mContext).when(mContext).createContextAsUser(any(), anyInt());

        // Manual connection tracking to avoid crashes in FakeApplicationContext.
        doAnswer(invocation -> true).when(mContext).bindServiceAsUser(any(), any(), anyInt(),
                any());
        doNothing().when(mContext).unbindService(any());
    }

    @SmallTest
    @Test
    public void testBindCallScreeningService_EmptyPackage() throws Exception {
        boolean result = CallScreeningServiceHelper.bindCallScreeningService(mContext, USER_HANDLE,
                "", mock(ServiceConnection.class), mFeatureFlags);
        assertFalse(result);
    }

    @SmallTest
    @Test
    public void testBindCallScreeningService_NoResolveEntries() throws Exception {
        doReturn(Collections.emptyList()).when(mPackageManager)
                .queryIntentServices(any(Intent.class), anyInt());
        boolean result = CallScreeningServiceHelper.bindCallScreeningService(mContext, USER_HANDLE,
                PKG_NAME, mock(ServiceConnection.class), mFeatureFlags);
        assertFalse(result);
    }

    @SmallTest
    @Test
    public void testBindCallScreeningService_BadResolveEntry() throws Exception {
        ResolveInfo info = new ResolveInfo();
        info.serviceInfo = null;
        doReturn(Collections.singletonList(info)).when(mPackageManager)
                .queryIntentServices(any(Intent.class), anyInt());
        boolean result = CallScreeningServiceHelper.bindCallScreeningService(mContext, USER_HANDLE,
                PKG_NAME, mock(ServiceConnection.class), mFeatureFlags);
        assertFalse(result);
    }

    @SmallTest
    @Test
    public void testBindCallScreeningService_NoPermission() throws Exception {
        ResolveInfo info = new ResolveInfo();
        info.serviceInfo = new ServiceInfo();
        info.serviceInfo.packageName = PKG_NAME;
        info.serviceInfo.name = CLS_NAME;
        info.serviceInfo.permission = "wrong.permission";
        doReturn(Collections.singletonList(info)).when(mPackageManager)
                .queryIntentServices(any(Intent.class), anyInt());
        boolean result = CallScreeningServiceHelper.bindCallScreeningService(mContext, USER_HANDLE,
                PKG_NAME, mock(ServiceConnection.class), mFeatureFlags);
        assertFalse(result);
    }

    @SmallTest
    @Test
    public void testBindCallScreeningService_NullPermission() throws Exception {
        ResolveInfo info = new ResolveInfo();
        info.serviceInfo = new ServiceInfo();
        info.serviceInfo.packageName = PKG_NAME;
        info.serviceInfo.name = CLS_NAME;
        info.serviceInfo.permission = null;
        doReturn(Collections.singletonList(info)).when(mPackageManager)
                .queryIntentServices(any(Intent.class), anyInt());
        boolean result = CallScreeningServiceHelper.bindCallScreeningService(mContext, USER_HANDLE,
                PKG_NAME, mock(ServiceConnection.class), mFeatureFlags);
        assertFalse(result);
    }

    @SmallTest
    @Test
    public void testBindCallScreeningService_BindFailed() throws Exception {
        ResolveInfo info = new ResolveInfo();
        info.serviceInfo = new ServiceInfo();
        info.serviceInfo.packageName = PKG_NAME;
        info.serviceInfo.name = CLS_NAME;
        info.serviceInfo.permission = Manifest.permission.BIND_SCREENING_SERVICE;
        doReturn(Collections.singletonList(info)).when(mPackageManager)
                .queryIntentServices(any(Intent.class), anyInt());

        doReturn(false).when(mContext).bindServiceAsUser(any(Intent.class),
                any(ServiceConnection.class), anyInt(), any(UserHandle.class));

        boolean result = CallScreeningServiceHelper.bindCallScreeningService(mContext, USER_HANDLE,
                PKG_NAME, mock(ServiceConnection.class), mFeatureFlags);
        assertFalse(result);
    }

    @SmallTest
    @Test
    public void testBindCallScreeningService_Success() throws Exception {
        setupMockPackage();
        doReturn(true).when(mContext).bindServiceAsUser(any(), any(), anyInt(), any());

        boolean result = CallScreeningServiceHelper.bindCallScreeningService(mContext, USER_HANDLE,
                PKG_NAME, mock(ServiceConnection.class), mFeatureFlags);
        assertTrue(result);

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, atLeastOnce()).bindServiceAsUser(intentCaptor.capture(),
                any(ServiceConnection.class), anyInt(), any(UserHandle.class));
        Intent capturedIntent = intentCaptor.getValue();
        assertEquals(CallScreeningService.SERVICE_INTERFACE, capturedIntent.getAction());
        assertEquals(COMPONENT_NAME, capturedIntent.getComponent());
    }

    @SmallTest
    @Test
    public void testProcess_NoPackageName() throws Exception {
        CallScreeningServiceHelper helper = new CallScreeningServiceHelper(mContext, mLock, null,
                mConverter, USER_HANDLE, mCall, mAppLabelProxy, mFeatureFlags);
        CompletableFuture<ParcelableCallResponse> future =
                runOnMainThread(helper::process);
        assertTrue(future.isDone());
        assertEquals(null, future.get());
    }

    @SmallTest
    @Test
    public void testProcess_EmptyPackageName() throws Exception {
        CallScreeningServiceHelper helper = new CallScreeningServiceHelper(mContext, mLock, "",
                mConverter, USER_HANDLE, mCall, mAppLabelProxy, mFeatureFlags);
        CompletableFuture<ParcelableCallResponse> future =
                runOnMainThread(helper::process);
        assertTrue(future.isDone());
        assertEquals(null, future.get());
    }

    @SmallTest
    @Test
    public void testProcess_BindFailed() throws Exception {
        doReturn(Collections.emptyList()).when(mPackageManager)
                .queryIntentServices(any(Intent.class), anyInt());
        CallScreeningServiceHelper helper = new CallScreeningServiceHelper(mContext, mLock,
                PKG_NAME,
                mConverter, USER_HANDLE, mCall, mAppLabelProxy, mFeatureFlags);

        CompletableFuture<ParcelableCallResponse> future =
                runOnMainThread(helper::process);

        assertTrue(future.isDone());
        assertEquals(null, future.get());
    }

    @SmallTest
    @Test
    public void testProcess_OnServiceConnected() throws Exception {
        setupMockPackage();
        doReturn(true).when(mContext).bindServiceAsUser(any(), any(), anyInt(), any());

        CallScreeningServiceHelper helper = new CallScreeningServiceHelper(mContext, mLock,
                PKG_NAME,
                mConverter, USER_HANDLE, mCall, mAppLabelProxy, mFeatureFlags);
        CompletableFuture<ParcelableCallResponse> future =
                runOnMainThread(helper::process);
        assertFalse(future.isDone());

        ServiceConnection conn = verifyBind();
        conn.onServiceConnected(COMPONENT_NAME, mBinder);

        verify(mCallScreeningService).screenCall(any(), any());
    }

    @SmallTest
    @Test
    public void testProcess_OnServiceConnected_RemoteException() throws Exception {
        setupMockPackage();
        doReturn(true).when(mContext).bindServiceAsUser(any(), any(), anyInt(), any());

        CallScreeningServiceHelper helper = new CallScreeningServiceHelper(mContext, mLock,
                PKG_NAME,
                mConverter, USER_HANDLE, mCall, mAppLabelProxy, mFeatureFlags);

        CompletableFuture<ParcelableCallResponse> future =
                runOnMainThread(helper::process);

        ServiceConnection conn = verifyBind();
        doThrow(new RemoteException()).when(mCallScreeningService).screenCall(any(), any());
        conn.onServiceConnected(COMPONENT_NAME, mBinder);

        assertTrue(future.isDone());
        assertEquals(null, future.get());
    }

    @SmallTest
    @Test
    public void testProcess_OnServiceDisconnected() throws Exception {
        setupMockPackage();
        doReturn(true).when(mContext).bindServiceAsUser(any(), any(), anyInt(), any());

        CallScreeningServiceHelper helper = new CallScreeningServiceHelper(mContext, mLock,
                PKG_NAME,
                mConverter, USER_HANDLE, mCall, mAppLabelProxy, mFeatureFlags);

        CompletableFuture<ParcelableCallResponse> future =
                runOnMainThread(helper::process);

        ServiceConnection conn = verifyBind();
        conn.onServiceDisconnected(COMPONENT_NAME);

        assertTrue(future.isDone());
        assertEquals(null, future.get());
    }

    @SmallTest
    @Test
    public void testProcess_OnNullBinding() throws Exception {
        setupMockPackage();
        doReturn(true).when(mContext).bindServiceAsUser(any(), any(), anyInt(), any());

        CallScreeningServiceHelper helper = new CallScreeningServiceHelper(mContext, mLock,
                PKG_NAME,
                mConverter, USER_HANDLE, mCall, mAppLabelProxy, mFeatureFlags);

        CompletableFuture<ParcelableCallResponse> future =
                runOnMainThread(helper::process);

        ServiceConnection conn = verifyBind();
        conn.onNullBinding(COMPONENT_NAME);

        assertTrue(future.isDone());
        assertEquals(null, future.get());
    }

    @SmallTest
    @Test
    public void testBindCallScreeningService_NullUserContext() throws Exception {
        doReturn(null).when(mContext).createContextAsUser(any(), anyInt());
        setupMockPackage();
        boolean result = CallScreeningServiceHelper.bindCallScreeningService(mContext, USER_HANDLE,
                PKG_NAME, mock(ServiceConnection.class), mFeatureFlags);
        assertTrue(result);
        verify(mContext, atLeastOnce()).getPackageManager();
    }

    private void setupMockPackage() throws Exception {
        ResolveInfo info = new ResolveInfo();
        info.serviceInfo = new ServiceInfo();
        info.serviceInfo.packageName = PKG_NAME;
        info.serviceInfo.name = CLS_NAME;
        info.serviceInfo.permission = Manifest.permission.BIND_SCREENING_SERVICE;
        doReturn(Collections.singletonList(info)).when(mPackageManager)
                .queryIntentServices(any(Intent.class), anyInt());

        // Register the service in the fake context using reflection
        Method addServiceMethod = ComponentContextFixture.class.getDeclaredMethod(
                "addService", String.class, ComponentName.class, IInterface.class);
        addServiceMethod.setAccessible(true);
        addServiceMethod.invoke(mComponentContextFixture, CallScreeningService.SERVICE_INTERFACE,
                COMPONENT_NAME, mCallScreeningService);
    }

    private ServiceConnection verifyBind() {
        ArgumentCaptor<ServiceConnection> captor = ArgumentCaptor.forClass(ServiceConnection.class);
        verify(mContext, atLeastOnce()).bindServiceAsUser(any(Intent.class), captor.capture(),
                anyInt(), any(UserHandle.class));
        return captor.getValue();
    }

    private <T> CompletableFuture<T> runOnMainThread(
            java.util.concurrent.Callable<CompletableFuture<T>> callable) {
        CompletableFuture<T>[] result = new CompletableFuture[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                result[0] = callable.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return result[0];
    }
}