/*
 * Copyright (C) 2024 The Android Open Source Project
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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallIntentProcessor;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.DefaultDialerCache;
import com.android.server.telecom.PhoneNumberUtilsAdapter;
import com.android.server.telecom.TelephonyUtil;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoSession;

import java.util.concurrent.CompletableFuture;

/** Unit tests for CollIntentProcessor class. */
@RunWith(JUnit4.class)
public class CallIntentProcessorTest extends TelecomTestCase {

    @Mock
    private CallsManager mCallsManager;
    @Mock
    private DefaultDialerCache mDefaultDialerCache;
    @Mock
    private Context mMockCreateContextAsUser;
    @Mock
    private UserManager mMockCurrentUserManager;
    @Mock
    private PhoneNumberUtilsAdapter mPhoneNumberUtilsAdapter;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private ResolveInfo mResolveInfo;
    @Mock
    private ComponentName mComponentName;
    @Mock
    private ComponentInfo mComponentInfo;
    @Mock
    private CompletableFuture<Call> mCallFuture;
    private CallIntentProcessor mCallIntentProcessor;
    private static final UserHandle PRIVATE_SPACE_USERHANDLE = new UserHandle(12);
    private static final String TEST_PACKAGE_NAME = "testPackageName";
    private static final Uri TEST_PHONE_NUMBER = Uri.parse("tel:1234567890");
    private static final Uri TEST_EMERGENCY_PHONE_NUMBER = Uri.parse("tel:911");

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mContext = mComponentContextFixture.getTestDouble().getApplicationContext();
        when(mContext.createContextAsUser(any(UserHandle.class), eq(0))).thenReturn(
                mMockCreateContextAsUser);
        when(mMockCreateContextAsUser.getSystemService(UserManager.class)).thenReturn(
                mMockCurrentUserManager);
        when(mMockCreateContextAsUser.getPackageManager()).thenReturn(mPackageManager);
        mCallIntentProcessor = new CallIntentProcessor(mContext, mCallsManager, mDefaultDialerCache,
                mFeatureFlags);
        when(mFeatureFlags.telecomResolveHiddenDependencies()).thenReturn(false);
        when(mCallsManager.getPhoneNumberUtilsAdapter()).thenReturn(mPhoneNumberUtilsAdapter);
        when(mPhoneNumberUtilsAdapter.isUriNumber(anyString())).thenReturn(true);
        when(mCallsManager.startOutgoingCall(any(Uri.class), any(), any(Bundle.class),
                any(UserHandle.class), any(Intent.class), anyString())).thenReturn(mCallFuture);
        when(mCallFuture.thenAccept(any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    public void testDangerousCall_dialerPrivileged_noErrorDialog() {
        Intent intent = new Intent(Intent.ACTION_CALL);
        intent.setData(Uri.parse("tel:*72536")); // Dangerous MMI
        intent.putExtra(CallIntentProcessor.KEY_INITIATING_USER, UserHandle.CURRENT);

        // Not default dialer
        when(mDefaultDialerCache.isDefaultOrSystemDialer(eq(TEST_PACKAGE_NAME),
                        anyInt())).thenReturn(false);

        // Has CALL_PRIVILEGED permission
        when(mPackageManager.checkPermission(Manifest.permission.CALL_PRIVILEGED,
                TEST_PACKAGE_NAME)).thenReturn(PackageManager.PERMISSION_GRANTED);

        mCallIntentProcessor.processIntent(intent, TEST_PACKAGE_NAME);

        // Verify startOutgoingCall IS called
        verify(mCallsManager).startOutgoingCall(any(Uri.class), any(), any(Bundle.class),
                eq(UserHandle.CURRENT), eq(intent), eq(TEST_PACKAGE_NAME));
    }

    @Test
    public void testDangerousCall_dialerNotPrivileged_ErrorDialogShown() {
        Intent intent = new Intent(Intent.ACTION_CALL);
        intent.setData(Uri.parse("tel:*72536")); // Dangerous MMI
        intent.putExtra(CallIntentProcessor.KEY_INITIATING_USER, UserHandle.CURRENT);

        // Not default dialer
        when(mDefaultDialerCache.isDefaultOrSystemDialer(eq(TEST_PACKAGE_NAME),
             anyInt())).thenReturn(false);

        // No CALL_PRIVILEGED permission
        when(mPackageManager.checkPermission(Manifest.permission.CALL_PRIVILEGED,
                TEST_PACKAGE_NAME)).thenReturn(PackageManager.PERMISSION_DENIED);

        mCallIntentProcessor.processIntent(intent, TEST_PACKAGE_NAME);

        // Verify startOutgoingCall is NEVER called
        verify(mCallsManager, never()).startOutgoingCall(any(Uri.class), any(), any(Bundle.class),
                any(UserHandle.class), any(Intent.class), anyString());

        // Verify error dialog (startActivity)
        // Note: NewOutgoingCallIntentBroadcaster also calls
        // startActivityAsUser to launch system dialer
        // AND CallIntentProcessor calls startActivityAsUser to show error dialog.
        // So we expect at least one call.
        verify(mContext, org.mockito.Mockito.atLeastOnce()).startActivityAsUser(any(Intent.class),
                eq(UserHandle.CURRENT));
    }

    @Test
    public void testNonPrivateSpaceCall_noConsentDialogShown() {
        Intent intent = new Intent(Intent.ACTION_CALL);
        intent.setData(TEST_PHONE_NUMBER);
        intent.putExtra(CallIntentProcessor.KEY_INITIATING_USER, UserHandle.CURRENT);
        when(mCallsManager.isSelfManaged(any(), eq(UserHandle.CURRENT))).thenReturn(false);

        mCallIntentProcessor.processIntent(intent, TEST_PACKAGE_NAME);

        verify(mContext, never()).startActivityAsUser(any(Intent.class), any(UserHandle.class));

        // Verify that the call proceeds as normal since the dialog was not shown
        verify(mCallsManager).startOutgoingCall(any(Uri.class), any(), any(Bundle.class),
                eq(UserHandle.CURRENT), eq(intent), eq(TEST_PACKAGE_NAME));
    }

    @Test
    public void testPrivateSpaceCall_isSelfManaged_noDialogShown() {
        markInitiatingUserAsPrivateProfile();
        resolveAsIntentForwarderActivity();

        Intent intent = new Intent(Intent.ACTION_CALL);
        intent.setData(TEST_PHONE_NUMBER);
        intent.putExtra(CallIntentProcessor.KEY_INITIATING_USER, PRIVATE_SPACE_USERHANDLE);
        when(mCallsManager.isSelfManaged(any(), eq(PRIVATE_SPACE_USERHANDLE))).thenReturn(true);

        mCallIntentProcessor.processIntent(intent, TEST_PACKAGE_NAME);

        verify(mContext, never()).startActivityAsUser(any(Intent.class),
                eq(PRIVATE_SPACE_USERHANDLE));

        // Verify that the call proceeds as normal since the dialog was not shown
        verify(mCallsManager).startOutgoingCall(any(Uri.class), any(), any(Bundle.class),
                eq(PRIVATE_SPACE_USERHANDLE), eq(intent), eq(TEST_PACKAGE_NAME));
    }

    @Test
    public void testPrivateSpaceCall_isEmergency_noDialogShown() {
        MockitoSession session = ExtendedMockito.mockitoSession().mockStatic(
                TelephonyUtil.class).startMocking();
        ExtendedMockito.doReturn(true).when(
                () -> TelephonyUtil.shouldProcessAsEmergency(any(), any()));

        markInitiatingUserAsPrivateProfile();
        resolveAsIntentForwarderActivity();

        Intent intent = new Intent(Intent.ACTION_CALL);
        intent.setData(TEST_EMERGENCY_PHONE_NUMBER);
        intent.putExtra(CallIntentProcessor.KEY_INITIATING_USER, PRIVATE_SPACE_USERHANDLE);
        when(mCallsManager.isSelfManaged(any(), eq(PRIVATE_SPACE_USERHANDLE))).thenReturn(false);

        mCallIntentProcessor.processIntent(intent, TEST_PACKAGE_NAME);

        verify(mContext, never()).startActivityAsUser(any(Intent.class),
                eq(PRIVATE_SPACE_USERHANDLE));
        session.finishMocking();
    }

    @Test
    public void testPrivateSpaceCall_showConsentDialog() {
        markInitiatingUserAsPrivateProfile();
        resolveAsIntentForwarderActivity();

        Intent intent = new Intent(Intent.ACTION_CALL);
        intent.setData(TEST_PHONE_NUMBER);
        intent.putExtra(CallIntentProcessor.KEY_INITIATING_USER, PRIVATE_SPACE_USERHANDLE);
        when(mCallsManager.isSelfManaged(any(), eq(PRIVATE_SPACE_USERHANDLE))).thenReturn(false);

        mCallIntentProcessor.processIntent(intent, TEST_PACKAGE_NAME);

        // Consent dialog should be shown
        verify(mMockCreateContextAsUser).startActivity(any(Intent.class));

        /// Verify that the call does not proceeds as normal since the dialog was shown
        verify(mCallsManager, never()).startOutgoingCall(any(), any(), any(), any(), any(),
                anyString());
    }

    private void markInitiatingUserAsPrivateProfile() {
        when(mMockCurrentUserManager.isPrivateProfile()).thenReturn(true);
    }

    private void resolveAsIntentForwarderActivity() {
        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.packageName = TEST_PACKAGE_NAME;
        activityInfo.name = mCallIntentProcessor.FORWARD_INTENT_TO_PARENT;
        mResolveInfo.activityInfo = activityInfo;

        when(mContext.getPackageManager()).thenReturn(mPackageManager);

        when(mPackageManager.resolveActivity(any(Intent.class),
                any(PackageManager.ResolveInfoFlags.class))).thenReturn(mResolveInfo);
    }
}
