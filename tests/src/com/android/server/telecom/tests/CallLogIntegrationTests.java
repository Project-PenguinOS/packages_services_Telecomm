/*
 * Copyright (C) 2025 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.telecom.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.CallLog;
import android.text.TextUtils;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.CallLogIntegrationAdapterImpl;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RunWith(JUnit4.class)
public class CallLogIntegrationTests extends TelecomTestCase {
    private static final int TIMEOUT = 5000;
    private static final String PKG_1 = "com.voip.app1";
    private static final String PKG_2 = "com.voip.app2";

    @Mock private Context mContext;
    @Mock private Context mUserContext;
    @Mock private PackageManager mPackageManager;
    @Mock private SharedPreferences mSharedPreferences;
    @Mock private SharedPreferences.Editor mEditor;
    @Mock private UserHandle mUserHandle;
    @Mock private ContentResolver mUserContentResolver;

    private CallLogIntegrationAdapterImpl mAdapter;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        when(mContext.createContextAsUser(any(UserHandle.class), anyInt()))
                .thenReturn(mUserContext);
        when(mUserContext.getContentResolver()).thenReturn(mUserContentResolver);
        when(mUserContext.getPackageManager()).thenReturn(mPackageManager);
        when(mUserContext.getSharedPreferences(anyString(), anyInt()))
                .thenReturn(mSharedPreferences);
        when(mSharedPreferences.edit()).thenReturn(mEditor);
        when(mEditor.putString(anyString(), anyString())).thenReturn(mEditor);
        mAdapter = new CallLogIntegrationAdapterImpl(mContext, mFeatureFlags);
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    @SmallTest
    public void testGetSupportedPackages_initialState_createsAndSavesDefaults() {
        // Set up for broadcast query to return two packages.
        when(mSharedPreferences.getString(anyString(), anyString())).thenReturn("");
        mockBroadcastQueryResult(Arrays.asList(PKG_1, PKG_2));

        Map<String, Boolean> result = mAdapter
                .getSupportedVoipCallLogIntegrationPackages(mUserHandle);
        // Verify both packages are included
        assertEquals(2, result.size());
        assertTrue(result.get(PKG_1));
        assertTrue(result.get(PKG_2));

        // Verify that packages are updated in shared preferences
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mEditor).putString(anyString(), captor.capture());
        verify(mEditor).apply();
        Map<String, Boolean> storedMap = deserializeSharedPrefString(captor.getValue());
        assertEquals(2, storedMap.size());
        assertTrue(storedMap.get(PKG_1));
        assertTrue(storedMap.get(PKG_2));
    }

    @Test
    @SmallTest
    public void testGetSupportedPackages_loadsFromPreferences() {
        // Add two packages with one as disabled in the shared preferences
        String storedValue = PKG_1 + ":true," + PKG_2 + ":false";
        when(mSharedPreferences.getString(anyString(), anyString())).thenReturn(storedValue);
        // Set up broadcast receiver to return both packages
        mockBroadcastQueryResult(Arrays.asList(PKG_1, PKG_2));

        Map<String, Boolean> result = mAdapter
                .getSupportedVoipCallLogIntegrationPackages(mUserHandle);
        // Verify that the shared preference storage is reflected in the result and not overridden
        // by the list returned by the broadcast query.
        assertEquals(2, result.size());
        assertTrue(result.get(PKG_1));
        assertFalse(result.get(PKG_2));

        // Verify that we never wrote back to the shared preferences
        verify(mEditor, never()).putString(anyString(), anyString());
    }

    @Test
    @SmallTest
    public void testSetEnabledState() {
        when(mFeatureFlags.integratedCallLogsStage2()).thenReturn(true);
        // Set up persistent storage to be empty and broadcast query to return one package.
        when(mSharedPreferences.getString(anyString(), anyString())).thenReturn("");
        mockBroadcastQueryResult(Collections.singletonList(PKG_1));
        mAdapter.getSupportedVoipCallLogIntegrationPackages(mUserHandle);

        // Disable the package
        mAdapter.setVoipPackageCallLogIntegrationEnabled(mUserHandle, PKG_1, false);

        // Verify that the update was reflected in the shared preferences
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mEditor, times(2)).putString(anyString(), captor.capture());
        verify(mEditor, times(2)).apply();
        Map<String, Boolean> storedMap = deserializeSharedPrefString(captor.getValue());
        assertEquals(1, storedMap.size());
        assertFalse(storedMap.get(PKG_1));

        // Verify that we deleted the call log entries for the package.
        Uri expectedUri = ContentProvider.createContentUriForUser(
                CallLog.Calls.CONTENT_URI_WITH_VOIP_CALLS, mUserHandle);
        String expectedSelection = CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME + " LIKE '"
                + PKG_1 + "%'";
        verify(mUserContentResolver, timeout(TIMEOUT)).delete(eq(expectedUri),
                eq(expectedSelection), eq(null));
    }

    @Test
    @SmallTest
    public void testPackageAddedUpdate() {
        // Arrange: Initial state has one package.
        when(mSharedPreferences.getString(anyString(), anyString())).thenReturn(PKG_1 + ":true");
        mockBroadcastQueryResult(Collections.singletonList(PKG_1));
        mAdapter.getSupportedVoipCallLogIntegrationPackages(mUserHandle);

        // Emulate adding a new package
        mAdapter.getPackagesToAdd().put(mUserHandle, Set.of(PKG_2));
        Map<String, Boolean> result = mAdapter
                .getSupportedVoipCallLogIntegrationPackages(mUserHandle);

        // Verify that the new package is now included in the list with default enabled state.
        assertEquals(2, result.size());
        assertTrue(result.get(PKG_1));
        assertTrue(result.get(PKG_2));

        // Verify that the new updated list was persisted to SharedPreferences.
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mEditor).putString(anyString(), captor.capture());
        Map<String, Boolean> storedMap = deserializeSharedPrefString(captor.getValue());
        assertEquals(2, storedMap.size());
    }

    @Test
    @SmallTest
    public void testPackageRemovedUpdate() {
        // Initialize with two packages available
        when(mSharedPreferences.getString(anyString(), anyString()))
                .thenReturn(PKG_1 + ":true," + PKG_2 + ":true");
        mockBroadcastQueryResult(Arrays.asList(PKG_1, PKG_2));
        mAdapter.getSupportedVoipCallLogIntegrationPackages(mUserHandle);

        // Emulate a package removal update
        mAdapter.getPackagesToRemove().put(mUserHandle, Set.of(PKG_2));
        Map<String, Boolean> result = mAdapter
                .getSupportedVoipCallLogIntegrationPackages(mUserHandle);

        // Verify that the removed package was updated in the list
        assertEquals(1, result.size());
        assertTrue(result.get(PKG_1));
        assertFalse(result.containsKey(PKG_2));

        // Verify that the new state persisted
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mEditor).putString(anyString(), captor.capture());
        Map<String, Boolean> storedMap = deserializeSharedPrefString(captor.getValue());
        assertEquals(1, storedMap.size());
        assertTrue(storedMap.containsKey(PKG_1));
    }

    @Test
    public void testPackageRemoved_deletesCallLogEntries() {
        when(mFeatureFlags.integratedCallLogsStage2()).thenReturn(true);
        // Initialize state with one available package.
        when(mPackageManager.queryBroadcastReceivers(any(), anyInt()))
                .thenReturn(Collections.singletonList(createResolveInfo(PKG_1)));
        mAdapter.getSupportedVoipCallLogIntegrationPackages(mUserHandle);

        // Simulate package being removed by broadcasting ACTION_PACKAGE_REMOVED.
        Intent intent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        intent.setData(Uri.parse("package:" + PKG_1));
        // Set a UID that corresponds to the test user.
        intent.putExtra(Intent.EXTRA_UID, mUserHandle.getIdentifier() * UserHandle.PER_USER_RANGE);
        mAdapter.getPackageChangedReceiver().onReceive(mContext, intent);

        // Verify that we deleted the call log entries for the package.
        Uri expectedUri = ContentProvider.createContentUriForUser(
                CallLog.Calls.CONTENT_URI_WITH_VOIP_CALLS, mUserHandle);
        String expectedSelection = CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME + " LIKE '"
                + PKG_1 + "%'";
        verify(mUserContentResolver, timeout(TIMEOUT)).delete(eq(expectedUri),
                eq(expectedSelection), eq(null));
    }

    @Test
    @SmallTest
    public void testNoPackageSupportedChange() {
        // Initialize with one package available
        mockBroadcastQueryResult(Collections.singletonList(PKG_1));
        mAdapter.getSupportedVoipCallLogIntegrationPackages(mUserHandle);

        // Query the getter again to verify that the list is unchanged
        Map<String, Boolean> result = mAdapter
                .getSupportedVoipCallLogIntegrationPackages(mUserHandle);
        assertEquals(1, result.size());
        assertTrue(result.get(PKG_1));

        // Verify that we only queried the broadcast receiver once during the first call.
        verify(mPackageManager, times(1))
                .queryBroadcastReceivers(any(Intent.class), anyInt());
    }

    private void mockBroadcastQueryResult(List<String> packageNames) {
        List<ResolveInfo> resolveInfos = packageNames.stream()
                .map(this::createResolveInfo)
                .collect(Collectors.toList());
        when(mPackageManager.queryBroadcastReceivers(any(Intent.class), anyInt()))
                .thenReturn(resolveInfos);
    }

    private ResolveInfo createResolveInfo(String packageName) {
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.packageName = packageName;
        return resolveInfo;
    }

    private Map<String, Boolean> deserializeSharedPrefString(String stored) {
        if (TextUtils.isEmpty(stored)) {
            return Collections.emptyMap();
        }
        return Arrays.stream(stored.split(","))
                .map(s -> s.split(":"))
                .filter(parts -> parts.length == 2)
                .collect(Collectors.toMap(
                        parts -> parts[0],
                        parts -> Boolean.parseBoolean(parts[1])
                ));
    }
}
