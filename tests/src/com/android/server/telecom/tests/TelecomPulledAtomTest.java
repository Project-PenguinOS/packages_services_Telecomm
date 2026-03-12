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

import static com.android.server.telecom.AudioRoute.TYPE_BLUETOOTH_LE;
import static com.android.server.telecom.AudioRoute.TYPE_EARPIECE;
import static com.android.server.telecom.AudioRoute.TYPE_SPEAKER;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_BLUETOOTH_LE;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_EARPIECE;
import static com.android.server.telecom.TelecomStatsLog.CALL_STATS__ACCOUNT_TYPE__ACCOUNT_SIM;
import static com.android.server.telecom.TelecomStatsLog.CALL_STATS__CALL_DIRECTION__DIR_INCOMING;
import static com.android.server.telecom.TelecomStatsLog.CALL_STATS__RAT_ON_END__NETWORK_TYPE_SATELLITE;
import static com.android.server.telecom.TelecomStatsLog.CALL_STATS__RAT_ON_END__NETWORK_TYPE_WIFI;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.StatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Looper;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.TelephonyManager;
import android.util.StatsEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.server.telecom.AudioRoute;
import com.android.server.telecom.Call;
import com.android.server.telecom.PendingAudioRoute;
import com.android.server.telecom.metrics.ApiStats;
import com.android.server.telecom.metrics.AudioRouteStats;
import com.android.server.telecom.metrics.CallEndpointStats;
import com.android.server.telecom.metrics.CallSequencingStats;
import com.android.server.telecom.metrics.CallStats;
import com.android.server.telecom.metrics.ErrorStats;
import com.android.server.telecom.metrics.EventStats;
import com.android.server.telecom.nano.PulledAtomsClass;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@RunWith(AndroidJUnit4.class)
public class TelecomPulledAtomTest extends TelecomTestCase {
    private static final long MIN_PULL_INTERVAL_MILLIS = 5L * 60 * 60 * 1000;
    private static final long DEFAULT_TIMESTAMPS_MILLIS = 3000;
    private static final int DELAY_FOR_PERSISTENT_MILLIS = 30000;
    private static final int DELAY_TOLERANCE = 50;
    private static final int TEST_TIMEOUT = (int) AudioRouteStats.THRESHOLD_REVERT_MS + 1000;
    private static final String FILE_NAME_TEST_ATOM = "test_atom.pb";

    private static final int VALUE_ATOM_COUNT = 1;

    private static final int VALUE_UID = 10000 + 1;
    private static final int VALUE_API_ID = 1;
    private static final int VALUE_API_RESULT = 1;
    private static final int VALUE_API_COUNT = 1;

    private static final int VALUE_AUDIO_ROUTE_TYPE1 = 1;
    private static final int VALUE_AUDIO_ROUTE_TYPE2 = 2;
    private static final int VALUE_AUDIO_ROUTE_COUNT = 1;
    private static final int VALUE_AUDIO_ROUTE_LATENCY = 300;

    private static final int VALUE_CALL_DIRECTION = 1;
    private static final int VALUE_CALL_ACCOUNT_TYPE = 1;
    private static final int VALUE_CALL_RAT = 1;
    private static final int VALUE_CALL_COUNT = 1;
    private static final int VALUE_CALL_DURATION = 3000;

    private static final int VALUE_MODULE_ID = 1;
    private static final int VALUE_ERROR_ID = 1;
    private static final int VALUE_ERROR_COUNT = 1;

    private static final int VALUE_EVENT_ID = 1;
    private static final int VALUE_CAUSE_ID = 1;
    private static final int VALUE_EVENT_COUNT = 1;

    private static final int VALUE_ENDPOINT_TYPE1 = 1;
    private static final int VALUE_ENDPOINT_TYPE2 = 2;
    private static final int VALUE_ENDPOINT_RESULT = 1;
    private static final boolean VALUE_ENDPOINT_TIMEOUT = false;
    private static final int VALUE_ENDPOINT_LATENCY = 300;
    private static final int VALUE_ENDPOINT_COUNT = 1;

    @Rule
    public TemporaryFolder mTempFolder = new TemporaryFolder();
    @Mock
    FileOutputStream mFileOutputStream;
    @Mock
    PendingAudioRoute mMockPendingAudioRoute;
    @Mock
    AudioRoute mMockSourceRoute;
    @Mock
    AudioRoute mMockDestRoute;
    private File mTempFile;
    private Looper mLooper;
    private Context mSpyContext;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        mSpyContext = spy(mContext);
        mLooper = Looper.getMainLooper();
        mTempFile = mTempFolder.newFile(FILE_NAME_TEST_ATOM);
        doReturn(mTempFile).when(mSpyContext).getFileStreamPath(anyString());
        doReturn(mFileOutputStream).when(mSpyContext).openFileOutput(anyString(), anyInt());
        doReturn(mMockSourceRoute).when(mMockPendingAudioRoute).getOrigRoute();
        doReturn(mMockDestRoute).when(mMockPendingAudioRoute).getDestRoute();
        doReturn(TYPE_EARPIECE).when(mMockSourceRoute).getType();
        doReturn(TYPE_BLUETOOTH_LE).when(mMockDestRoute).getType();

        TelephonyManager tm = mock(TelephonyManager.class);
        doReturn(tm).when(mSpyContext).getSystemService(TelephonyManager.class);
        ConnectivityManager cm = mock(ConnectivityManager.class);
        doReturn(cm).when(mSpyContext).getSystemService(ConnectivityManager.class);
    }

    @After
    @Override
    public void tearDown() throws Exception {
        mTempFile.delete();
        super.tearDown();
    }

    @Test
    public void testNewPulledAtomsFromFileInvalid() throws Exception {
        mTempFile.delete();

        ApiStats apiStats = new ApiStats(mSpyContext, mLooper, false);

        assertNotNull(apiStats.mPulledAtoms);
        assertEquals(apiStats.mPulledAtoms.telecomApiStats.length, 0);

        AudioRouteStats audioRouteStats = new AudioRouteStats(mSpyContext, mLooper, false);

        assertNotNull(audioRouteStats.mPulledAtoms);
        assertEquals(audioRouteStats.mPulledAtoms.callAudioRouteStats.length, 0);

        CallStats callStats = new CallStats(mSpyContext, mLooper, false);

        assertNotNull(callStats.mPulledAtoms);
        assertEquals(callStats.mPulledAtoms.callStats.length, 0);

        ErrorStats errorStats = new ErrorStats(mSpyContext, mLooper, false);

        assertNotNull(errorStats.mPulledAtoms);
        assertEquals(errorStats.mPulledAtoms.telecomErrorStats.length, 0);

        CallSequencingStats callSequencingStats =
                new CallSequencingStats(mSpyContext, mLooper, false);

        assertNotNull(callSequencingStats.mPulledAtoms);
        assertEquals(callSequencingStats.mPulledAtoms.callSequencingStats.length, 0);

        CallEndpointStats callEndpointStats = new CallEndpointStats(mSpyContext, mLooper, false);

        assertNotNull(callEndpointStats.mPulledAtoms);
        assertEquals(callEndpointStats.mPulledAtoms.callEndpointStats.length, 0);
    }

    @Test
    public void testNewPulledAtomsFromFileValid() throws Exception {
        createTestFileForApiStats(DEFAULT_TIMESTAMPS_MILLIS);
        ApiStats apiStats = new ApiStats(mSpyContext, mLooper, false);

        verifyTestDataForApiStats(apiStats.mPulledAtoms, DEFAULT_TIMESTAMPS_MILLIS);

        createTestFileForAudioRouteStats(DEFAULT_TIMESTAMPS_MILLIS);
        AudioRouteStats audioRouteStats = new AudioRouteStats(mSpyContext, mLooper, false);

        verifyTestDataForAudioRouteStats(audioRouteStats.mPulledAtoms, DEFAULT_TIMESTAMPS_MILLIS);

        createTestFileForCallStats(DEFAULT_TIMESTAMPS_MILLIS);
        CallStats callStats = new CallStats(mSpyContext, mLooper, false);

        verifyTestDataForCallStats(callStats.mPulledAtoms, DEFAULT_TIMESTAMPS_MILLIS);

        createTestFileForErrorStats(DEFAULT_TIMESTAMPS_MILLIS);
        ErrorStats errorStats = new ErrorStats(mSpyContext, mLooper, false);

        verifyTestDataForErrorStats(errorStats.mPulledAtoms, DEFAULT_TIMESTAMPS_MILLIS);

        createTestFileForEventStats(DEFAULT_TIMESTAMPS_MILLIS);
        EventStats eventStats = new EventStats(mSpyContext, mLooper, false);

        verifyTestDataForEventStats(eventStats.mPulledAtoms, DEFAULT_TIMESTAMPS_MILLIS);

        createTestFileForCallSequencingStats(DEFAULT_TIMESTAMPS_MILLIS);
        CallSequencingStats callSequencingStats =
                new CallSequencingStats(mSpyContext, mLooper, false);

        verifyTestDataForCallSequencingStats(callSequencingStats.mPulledAtoms,
                DEFAULT_TIMESTAMPS_MILLIS);

        createTestFileForCallEndpointStats(DEFAULT_TIMESTAMPS_MILLIS);
        CallEndpointStats callEndpointStats = new CallEndpointStats(mSpyContext, mLooper, false);

        verifyTestDataForCallEndpointStats(callEndpointStats.mPulledAtoms,
                DEFAULT_TIMESTAMPS_MILLIS);
    }

    @Test
    public void testPullApiStatsLessThanMinPullIntervalShouldSkip() throws Exception {
        createTestFileForApiStats(System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS / 2);
        ApiStats apiStats = spy(new ApiStats(mSpyContext, mLooper, false));
        final List<StatsEvent> data = new ArrayList<>();

        int result = apiStats.pull(data);

        assertEquals(StatsManager.PULL_SUCCESS, result);
        verify(apiStats, never()).onPull(any());
        assertEquals(data.size(), 0);
    }

    @Test
    public void testPullApiStatsGreaterThanMinPullIntervalShouldNotSkip() throws Exception {
        createTestFileForApiStats(System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS - 1);
        ApiStats apiStats = spy(new ApiStats(mSpyContext, mLooper, false));
        final List<StatsEvent> data = new ArrayList<>();
        int sizePulled = apiStats.mPulledAtoms.telecomApiStats.length;

        int result = apiStats.pull(data);

        assertEquals(StatsManager.PULL_SUCCESS, result);
        verify(apiStats).onPull(eq(data));
        assertEquals(data.size(), sizePulled);
        assertEquals(apiStats.mPulledAtoms.telecomApiStats.length, 0);
    }

    @Test
    public void testPullAudioRouteStatsLessThanMinPullIntervalShouldSkip() throws Exception {
        createTestFileForAudioRouteStats(System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS / 2);
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper, false));
        final List<StatsEvent> data = new ArrayList<>();

        int result = audioRouteStats.pull(data);

        assertEquals(StatsManager.PULL_SUCCESS, result);
        verify(audioRouteStats, never()).onPull(any());
        assertEquals(data.size(), 0);
    }

    @Test
    public void testPullAudioRouteStatsGreaterThanMinPullIntervalShouldNotSkip() throws Exception {
        createTestFileForAudioRouteStats(System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS - 1);
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper, false));
        final List<StatsEvent> data = new ArrayList<>();
        int sizePulled = audioRouteStats.mPulledAtoms.callAudioRouteStats.length;

        int result = audioRouteStats.pull(data);

        assertEquals(StatsManager.PULL_SUCCESS, result);
        verify(audioRouteStats).onPull(eq(data));
        assertEquals(data.size(), sizePulled);
        assertEquals(audioRouteStats.mPulledAtoms.callAudioRouteStats.length, 0);
    }

    @Test
    public void testPullCallStatsLessThanMinPullIntervalShouldSkip() throws Exception {
        createTestFileForCallStats(System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS / 2);
        CallStats callStats = spy(new CallStats(mSpyContext, mLooper, false));
        final List<StatsEvent> data = new ArrayList<>();

        int result = callStats.pull(data);

        assertEquals(StatsManager.PULL_SUCCESS, result);
        verify(callStats, never()).onPull(any());
        assertEquals(data.size(), 0);
    }

    @Test
    public void testPullCallStatsGreaterThanMinPullIntervalShouldNotSkip() throws Exception {
        createTestFileForCallStats(System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS - 1);
        CallStats callStats = spy(new CallStats(mSpyContext, mLooper, false));
        final List<StatsEvent> data = new ArrayList<>();
        int sizePulled = callStats.mPulledAtoms.callStats.length;

        int result = callStats.pull(data);

        assertEquals(StatsManager.PULL_SUCCESS, result);
        verify(callStats).onPull(eq(data));
        assertEquals(data.size(), sizePulled);
        assertEquals(callStats.mPulledAtoms.callStats.length, 0);
    }

    @Test
    public void testPullErrorStatsLessThanMinPullIntervalShouldSkip() throws Exception {
        createTestFileForErrorStats(System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS / 2);
        ErrorStats errorStats = spy(new ErrorStats(mSpyContext, mLooper, false));
        final List<StatsEvent> data = new ArrayList<>();

        int result = errorStats.pull(data);

        assertEquals(StatsManager.PULL_SUCCESS, result);
        verify(errorStats, never()).onPull(any());
        assertEquals(data.size(), 0);
    }

    @Test
    public void testPullErrorStatsGreaterThanMinPullIntervalShouldNotSkip() throws Exception {
        createTestFileForErrorStats(System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS - 1);
        ErrorStats errorStats = spy(new ErrorStats(mSpyContext, mLooper, false));
        final List<StatsEvent> data = new ArrayList<>();
        int sizePulled = errorStats.mPulledAtoms.telecomErrorStats.length;

        int result = errorStats.pull(data);

        assertEquals(StatsManager.PULL_SUCCESS, result);
        verify(errorStats).onPull(eq(data));
        assertEquals(data.size(), sizePulled);
        assertEquals(errorStats.mPulledAtoms.telecomErrorStats.length, 0);
    }

    @Test
    public void testPullCallSequencingStatsLessThanMinPullIntervalShouldSkip() throws Exception {
        createTestFileForCallSequencingStats(
                System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS / 2);
        CallSequencingStats callSequencingStats =
                spy(new CallSequencingStats(mSpyContext, mLooper, false));
        final List<StatsEvent> data = new ArrayList<>();

        int result = callSequencingStats.pull(data);

        assertEquals(StatsManager.PULL_SUCCESS, result);
        verify(callSequencingStats, never()).onPull(any());
        assertEquals(data.size(), 0);
    }

    @Test
    public void testPullCallSequencingStatsGreaterThanMinPullIntervalShouldNotSkip()
            throws Exception {
        createTestFileForCallSequencingStats(
                System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS - 1);
        CallSequencingStats callSequencingStats =
                spy(new CallSequencingStats(mSpyContext, mLooper, false));
        final List<StatsEvent> data = new ArrayList<>();
        int sizePulled = callSequencingStats.mPulledAtoms.callSequencingStats.length;

        int result = callSequencingStats.pull(data);

        assertEquals(StatsManager.PULL_SUCCESS, result);
        verify(callSequencingStats).onPull(eq(data));
        assertEquals(data.size(), sizePulled);
        assertEquals(callSequencingStats.mPulledAtoms.callSequencingStats.length, 0);
    }

    @Test
    public void testPullCallEndpointStatsLessThanMinPullIntervalShouldSkip() throws Exception {
        createTestFileForCallEndpointStats(
                System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS / 2);
        CallEndpointStats callEndpointStats =
                spy(new CallEndpointStats(mSpyContext, mLooper, false));
        final List<StatsEvent> data = new ArrayList<>();

        int result = callEndpointStats.pull(data);

        assertEquals(StatsManager.PULL_SUCCESS, result);
        verify(callEndpointStats, never()).onPull(any());
        assertEquals(data.size(), 0);
    }

    @Test
    public void testApiStatsLogCount() throws Exception {
        ApiStats apiStats = spy(new ApiStats(mSpyContext, mLooper, false));
        ApiStats.ApiEvent event = new ApiStats.ApiEvent(VALUE_API_ID, VALUE_UID, VALUE_API_RESULT);

        for (int i = 0; i < 10; i++) {
            apiStats.log(event);
            waitForHandlerAction(apiStats, TEST_TIMEOUT);

            verify(apiStats, times(i + 1)).onAggregate();
            verify(apiStats, times(i + 1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
            assertEquals(apiStats.mPulledAtoms.telecomApiStats.length, 1);
            verifyMessageForApiStats(apiStats.mPulledAtoms.telecomApiStats[0], VALUE_API_ID,
                    VALUE_UID, VALUE_API_RESULT, i + 1);
        }
    }

    @Test
    public void testApiStatsLogEvent() throws Exception {
        final int[] apis = {
                ApiStats.API_UNSPECIFIC,
                ApiStats.API_ACCEPTHANDOVER,
                ApiStats.API_ACCEPTRINGINGCALL,
                ApiStats.API_ACCEPTRINGINGCALLWITHVIDEOSTATE,
                ApiStats.API_ADDCALL,
                ApiStats.API_ADDNEWINCOMINGCALL,
                ApiStats.API_ADDNEWINCOMINGCONFERENCE,
                ApiStats.API_ADDNEWUNKNOWNCALL,
                ApiStats.API_CANCELMISSEDCALLSNOTIFICATION,
                ApiStats.API_CLEARACCOUNTS,
                ApiStats.API_CREATELAUNCHEMERGENCYDIALERINTENT,
                ApiStats.API_CREATEMANAGEBLOCKEDNUMBERSINTENT,
                ApiStats.API_DUMP,
                ApiStats.API_DUMPCALLANALYTICS,
                ApiStats.API_ENABLEPHONEACCOUNT,
                ApiStats.API_ENDCALL,
                ApiStats.API_GETADNURIFORPHONEACCOUNT,
                ApiStats.API_GETALLPHONEACCOUNTHANDLES,
                ApiStats.API_GETALLPHONEACCOUNTS,
                ApiStats.API_GETALLPHONEACCOUNTSCOUNT,
                ApiStats.API_GETCALLCAPABLEPHONEACCOUNTS,
                ApiStats.API_GETCALLSTATE,
                ApiStats.API_GETCALLSTATEUSINGPACKAGE,
                ApiStats.API_GETCURRENTTTYMODE,
                ApiStats.API_GETDEFAULTDIALERPACKAGE,
                ApiStats.API_GETDEFAULTDIALERPACKAGEFORUSER,
                ApiStats.API_GETDEFAULTOUTGOINGPHONEACCOUNT,
                ApiStats.API_GETDEFAULTPHONEAPP,
                ApiStats.API_GETLINE1NUMBER,
                ApiStats.API_GETOWNSELFMANAGEDPHONEACCOUNTS,
                ApiStats.API_GETPHONEACCOUNT,
                ApiStats.API_GETPHONEACCOUNTSFORPACKAGE,
                ApiStats.API_GETPHONEACCOUNTSSUPPORTINGSCHEME,
                ApiStats.API_GETREGISTEREDPHONEACCOUNTS,
                ApiStats.API_GETSELFMANAGEDPHONEACCOUNTS,
                ApiStats.API_GETSIMCALLMANAGER,
                ApiStats.API_GETSIMCALLMANAGERFORUSER,
                ApiStats.API_GETSYSTEMDIALERPACKAGE,
                ApiStats.API_GETUSERSELECTEDOUTGOINGPHONEACCOUNT,
                ApiStats.API_GETVOICEMAILNUMBER,
                ApiStats.API_HANDLEPINMMI,
                ApiStats.API_HANDLEPINMMIFORPHONEACCOUNT,
                ApiStats.API_HASMANAGEONGOINGCALLSPERMISSION,
                ApiStats.API_ISINCALL,
                ApiStats.API_ISINCOMINGCALLPERMITTED,
                ApiStats.API_ISINEMERGENCYCALL,
                ApiStats.API_ISINMANAGEDCALL,
                ApiStats.API_ISINSELFMANAGEDCALL,
                ApiStats.API_ISOUTGOINGCALLPERMITTED,
                ApiStats.API_ISRINGING,
                ApiStats.API_ISTTYSUPPORTED,
                ApiStats.API_ISVOICEMAILNUMBER,
                ApiStats.API_PLACECALL,
                ApiStats.API_REGISTERPHONEACCOUNT,
                ApiStats.API_SETDEFAULTDIALER,
                ApiStats.API_SETUSERSELECTEDOUTGOINGPHONEACCOUNT,
                ApiStats.API_SHOWINCALLSCREEN,
                ApiStats.API_SILENCERINGER,
                ApiStats.API_STARTCONFERENCE,
                ApiStats.API_UNREGISTERPHONEACCOUNT,
        };
        final int[] results = {ApiStats.RESULT_UNKNOWN, ApiStats.RESULT_NORMAL,
                ApiStats.RESULT_EXCEPTION, ApiStats.RESULT_PERMISSION};
        ApiStats apiStats = spy(new ApiStats(mSpyContext, mLooper, false));
        Random rand = new Random();
        Map<ApiStats.ApiEvent, Integer> eventMap = new HashMap<>();

        for (int i = 0; i < 10; i++) {
            int api = apis[rand.nextInt(apis.length)];
            int uid = rand.nextInt(65535);
            int result = results[rand.nextInt(results.length)];
            ApiStats.ApiEvent event = new ApiStats.ApiEvent(api, uid, result);
            eventMap.put(event, eventMap.getOrDefault(event, 0) + 1);

            apiStats.log(event);
            waitForHandlerAction(apiStats, TEST_TIMEOUT);

            verify(apiStats, times(i + 1)).onAggregate();
            verify(apiStats, times(i + 1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
            assertEquals(apiStats.mPulledAtoms.telecomApiStats.length, eventMap.size());
            assertTrue(hasMessageForApiStats(apiStats.mPulledAtoms.telecomApiStats,
                    api, uid, result, eventMap.get(event)));
        }
    }

    @Test
    public void testAudioRouteStatsLog() throws Exception {
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper, false));

        audioRouteStats.log(VALUE_AUDIO_ROUTE_TYPE1, VALUE_AUDIO_ROUTE_TYPE2, true, false,
                VALUE_AUDIO_ROUTE_LATENCY);
        waitForHandlerAction(audioRouteStats, TEST_TIMEOUT);

        verify(audioRouteStats, times(1)).onAggregate();
        verify(audioRouteStats, times(1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(audioRouteStats.mPulledAtoms.callAudioRouteStats.length, 1);
        verifyMessageForAudioRouteStats(audioRouteStats.mPulledAtoms.callAudioRouteStats[0],
                VALUE_AUDIO_ROUTE_TYPE1, VALUE_AUDIO_ROUTE_TYPE2, true, false, 1,
                VALUE_AUDIO_ROUTE_LATENCY);

        audioRouteStats.log(VALUE_AUDIO_ROUTE_TYPE1, VALUE_AUDIO_ROUTE_TYPE2, true, false,
                VALUE_AUDIO_ROUTE_LATENCY);
        waitForHandlerAction(audioRouteStats, TEST_TIMEOUT);

        verify(audioRouteStats, times(2)).onAggregate();
        verify(audioRouteStats, times(2)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(audioRouteStats.mPulledAtoms.callAudioRouteStats.length, 1);
        verifyMessageForAudioRouteStats(audioRouteStats.mPulledAtoms.callAudioRouteStats[0],
                VALUE_AUDIO_ROUTE_TYPE1, VALUE_AUDIO_ROUTE_TYPE2, true, false, 2,
                VALUE_AUDIO_ROUTE_LATENCY);
    }

    @Test
    public void testAudioRouteStatsOnEnterThenExit() throws Exception {
        int latency = 500;
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper, false));

        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, latency);
        audioRouteStats.onRouteExit(mMockPendingAudioRoute, true);
        waitForHandlerAction(audioRouteStats, 100);

        // Verify that the stats should not be saved before the revert threshold is expired
        verify(audioRouteStats, never()).onAggregate();
        verify(audioRouteStats, never()).save(anyInt());
        assertTrue(audioRouteStats.hasMessages(AudioRouteStats.EVENT_REVERT_THRESHOLD_EXPIRED));

        // Verify that the stats should be saved when the revert threshold is expired
        waitForHandlerActionDelayed(
                audioRouteStats, TEST_TIMEOUT, AudioRouteStats.THRESHOLD_REVERT_MS);

        verify(audioRouteStats, times(1)).onAggregate();
        verify(audioRouteStats, times(1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(audioRouteStats.mPulledAtoms.callAudioRouteStats.length, 1);
        verifyMessageForAudioRouteStats(audioRouteStats.mPulledAtoms.callAudioRouteStats[0],
                CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_EARPIECE,
                CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_BLUETOOTH_LE, true, false, 1,
                latency);
    }

    @Test
    public void testAudioRouteStatsOnRevertToSourceInThreshold() throws Exception {
        int delay = 100;
        int latency = 500;
        int duration = 1000;
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper, false));

        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, latency);
        audioRouteStats.onRouteExit(mMockPendingAudioRoute, true);
        waitForHandlerAction(audioRouteStats, delay);

        // Verify that the stats should not be saved before the revert threshold is expired
        verify(audioRouteStats, never()).onAggregate();
        verify(audioRouteStats, never()).save(anyInt());
        assertTrue(audioRouteStats.hasMessages(AudioRouteStats.EVENT_REVERT_THRESHOLD_EXPIRED));

        // Verify that the event should be saved as revert when routing back to the source before
        // the revert threshold is expired
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, duration);

        // Reverse the audio types
        doReturn(TYPE_BLUETOOTH_LE).when(mMockSourceRoute).getType();
        doReturn(TYPE_EARPIECE).when(mMockDestRoute).getType();

        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerAction(audioRouteStats, delay);

        verify(audioRouteStats, times(1)).onAggregate();
        verify(audioRouteStats, times(1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(audioRouteStats.mPulledAtoms.callAudioRouteStats.length, 1);
        verifyMessageForAudioRouteStats(audioRouteStats.mPulledAtoms.callAudioRouteStats[0],
                CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_EARPIECE,
                CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_BLUETOOTH_LE, true, true, 1,
                latency);
    }

    @Test
    public void testAudioRouteStatsOnRevertToSourceBeyondThreshold() throws Exception {
        int delay = 100;
        int latency = 500;
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper, false));

        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, latency);
        audioRouteStats.onRouteExit(mMockPendingAudioRoute, true);
        waitForHandlerAction(audioRouteStats, delay);

        // Verify that the stats should not be saved before the revert threshold is expired
        verify(audioRouteStats, never()).onAggregate();
        verify(audioRouteStats, never()).save(anyInt());
        assertTrue(audioRouteStats.hasMessages(AudioRouteStats.EVENT_REVERT_THRESHOLD_EXPIRED));

        // Verify that the event should not be saved as revert when routing back to the source
        // after the revert threshold is expired
        waitForHandlerActionDelayed(
                audioRouteStats, TEST_TIMEOUT, AudioRouteStats.THRESHOLD_REVERT_MS);

        // Reverse the audio types
        doReturn(TYPE_BLUETOOTH_LE).when(mMockSourceRoute).getType();
        doReturn(TYPE_EARPIECE).when(mMockDestRoute).getType();

        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerAction(audioRouteStats, delay);

        verify(audioRouteStats, times(1)).onAggregate();
        verify(audioRouteStats, times(1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(audioRouteStats.mPulledAtoms.callAudioRouteStats.length, 1);
        verifyMessageForAudioRouteStats(audioRouteStats.mPulledAtoms.callAudioRouteStats[0],
                CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_EARPIECE,
                CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_BLUETOOTH_LE, true, false, 1,
                latency);
    }

    @Test
    public void testAudioRouteStatsOnRouteToAnotherDestInThreshold() throws Exception {
        int delay = 100;
        int latency = 500;
        int duration = 1000;
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper, false));

        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, latency);
        audioRouteStats.onRouteExit(mMockPendingAudioRoute, true);
        waitForHandlerAction(audioRouteStats, delay);

        // Verify that the stats should not be saved before the revert threshold is expired
        verify(audioRouteStats, never()).onAggregate();
        verify(audioRouteStats, never()).save(anyInt());
        assertTrue(audioRouteStats.hasMessages(AudioRouteStats.EVENT_REVERT_THRESHOLD_EXPIRED));

        // Verify that the event should not be saved as  revert when routing to a type different
        // as the source before the revert threshold is expired
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, duration);

        AudioRoute dest2 = mock(AudioRoute.class);
        doReturn(TYPE_SPEAKER).when(dest2).getType();
        doReturn(dest2).when(mMockPendingAudioRoute).getDestRoute();

        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerAction(audioRouteStats, delay);

        verify(audioRouteStats, times(1)).onAggregate();
        verify(audioRouteStats, times(1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(audioRouteStats.mPulledAtoms.callAudioRouteStats.length, 1);
        verifyMessageForAudioRouteStats(audioRouteStats.mPulledAtoms.callAudioRouteStats[0],
                CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_EARPIECE,
                CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_BLUETOOTH_LE, true, false, 1,
                latency);
    }

    @Test
    public void testAudioRouteStatsOnMultipleEnterWithoutExit() throws Exception {
        int latency = 500;
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper, false));

        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, latency);

        doReturn(mMockDestRoute).when(mMockPendingAudioRoute).getOrigRoute();
        AudioRoute dest2 = mock(AudioRoute.class);
        doReturn(TYPE_SPEAKER).when(dest2).getType();
        doReturn(dest2).when(mMockPendingAudioRoute).getDestRoute();
        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, latency);

        // Verify that the stats should not be saved without exit
        verify(audioRouteStats, never()).onAggregate();
        verify(audioRouteStats, never()).save(anyInt());
        assertTrue(audioRouteStats.hasMessages(AudioRouteStats.EVENT_REVERT_THRESHOLD_EXPIRED));
    }

    @Test
    public void testAudioRouteStatsOnMultipleEnterWithExit() throws Exception {
        int latency = 500;
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper, false));

        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, latency);
        audioRouteStats.onRouteExit(mMockPendingAudioRoute, true);
        waitForHandlerAction(audioRouteStats, 100);

        doReturn(mMockDestRoute).when(mMockPendingAudioRoute).getOrigRoute();
        AudioRoute dest2 = mock(AudioRoute.class);
        doReturn(TYPE_SPEAKER).when(dest2).getType();
        doReturn(dest2).when(mMockPendingAudioRoute).getDestRoute();
        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, latency);

        // Verify that the stats should be saved after exit
        verify(audioRouteStats, times(1)).onAggregate();
        verify(audioRouteStats, times(1)).save(anyInt());
        assertTrue(audioRouteStats.hasMessages(AudioRouteStats.EVENT_REVERT_THRESHOLD_EXPIRED));
    }

    @Test
    public void testAudioRouteStatsOnRouteToSameDestWithExit() throws Exception {
        int latency = 500;
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper, false));
        doReturn(mMockSourceRoute).when(mMockPendingAudioRoute).getDestRoute();

        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, latency);

        // Enter again to trigger the log
        AudioRoute dest2 = mock(AudioRoute.class);
        doReturn(TYPE_SPEAKER).when(dest2).getType();
        doReturn(dest2).when(mMockPendingAudioRoute).getDestRoute();
        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, latency);

        // Verify that the stats should not be saved without exit
        verify(audioRouteStats, never()).onAggregate();
        verify(audioRouteStats, never()).save(anyInt());
        assertTrue(audioRouteStats.hasMessages(AudioRouteStats.EVENT_REVERT_THRESHOLD_EXPIRED));
    }

    @Test
    public void testCallStatsLog() throws Exception {
        CallStats callStats = spy(new CallStats(mSpyContext, mLooper, false));

        callStats.log(VALUE_CALL_DIRECTION, false, false, true, VALUE_CALL_ACCOUNT_TYPE,
                VALUE_UID, 0, 0, false, VALUE_CALL_RAT, VALUE_CALL_DURATION);
        waitForHandlerAction(callStats, TEST_TIMEOUT);

        verify(callStats, times(1)).onAggregate();
        verify(callStats, times(1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(callStats.mPulledAtoms.callStats.length, 1);
        verifyMessageForCallStats(callStats.mPulledAtoms.callStats[0], VALUE_CALL_DIRECTION,
                false, false, true, VALUE_CALL_ACCOUNT_TYPE, VALUE_UID, 1, VALUE_CALL_DURATION,
                VALUE_CALL_RAT);

        callStats.log(VALUE_CALL_DIRECTION, false, false, true, VALUE_CALL_ACCOUNT_TYPE,
                VALUE_UID, 0, 0, false, VALUE_CALL_RAT, VALUE_CALL_DURATION);
        waitForHandlerAction(callStats, TEST_TIMEOUT);

        verify(callStats, times(2)).onAggregate();
        verify(callStats, times(2)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(callStats.mPulledAtoms.callStats.length, 1);
        verifyMessageForCallStats(callStats.mPulledAtoms.callStats[0], VALUE_CALL_DIRECTION,
                false, false, true, VALUE_CALL_ACCOUNT_TYPE, VALUE_UID, 2, VALUE_CALL_DURATION,
                VALUE_CALL_RAT, new int[]{VALUE_CALL_DURATION, VALUE_CALL_DURATION});
    }

    @Test
    public void testCallStatsLogDurationLimit() throws Exception {
        CallStats callStats = spy(new CallStats(mSpyContext, mLooper, false));

        for (int i = 0; i < 20; i++) {
            callStats.log(VALUE_CALL_DIRECTION, false, false, true, VALUE_CALL_ACCOUNT_TYPE,
                    VALUE_UID, 0, 0, false, VALUE_CALL_RAT, i);
            waitForHandlerAction(callStats, TEST_TIMEOUT);
        }

        assertEquals(callStats.mPulledAtoms.callStats.length, 1);
        assertEquals(callStats.mPulledAtoms.callStats[0].getCount(), 20);
        assertEquals(callStats.mPulledAtoms.callStats[0].repeatedIntDurations.length, 10);
        int[] expectedDurations = new int[10];
        for (int i = 0; i < 10; i++) {
            expectedDurations[i] = i;
        }
        assertArrayEquals(callStats.mPulledAtoms.callStats[0].repeatedIntDurations,
                expectedDurations);
    }

    @Test
    public void testCallStatsOnStartThenEnd() throws Exception {
        int duration = 1000;
        int fakeUid = 10010;
        PhoneAccount account = mock(PhoneAccount.class);
        Call.CallingPackageIdentity callingPackage = new Call.CallingPackageIdentity();
        PackageManager pm = mock(PackageManager.class);
        ApplicationInfo ai = new ApplicationInfo();
        ai.uid = fakeUid;
        doReturn(ai).when(pm).getApplicationInfo(any(), anyInt());
        doReturn(pm).when(mSpyContext).getPackageManager();
        Context fakeContext = spy(mContext);
        doReturn("").when(fakeContext).getPackageName();
        ComponentName cn = new ComponentName(fakeContext, this.getClass());
        PhoneAccountHandle handle = mock(PhoneAccountHandle.class);
        doReturn(cn).when(handle).getComponentName();
        Call call = mock(Call.class);
        doReturn(true).when(call).isIncoming();
        doReturn(new DisconnectCause(0)).when(call).getDisconnectCause();
        doReturn(0).when(call).getSimultaneousType();
        doReturn(false).when(call).hasVideoCall();
        doReturn(account).when(call).getPhoneAccountFromHandle();
        doReturn((long) duration).when(call).getAgeMillis();
        doReturn(false).when(account).hasCapabilities(eq(PhoneAccount.CAPABILITY_SELF_MANAGED));
        doReturn(true).when(account).hasCapabilities(eq(PhoneAccount.CAPABILITY_CALL_PROVIDER));
        doReturn(true).when(account).hasCapabilities(eq(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION));
        doReturn(callingPackage).when(call).getCallingPackageIdentity();
        doReturn(handle).when(call).getTargetPhoneAccount();
        CallStats callStats = spy(new CallStats(mSpyContext, mLooper, false));

        callStats.onCallEnd(call);
        waitForHandlerAction(callStats, TEST_TIMEOUT);

        assertEquals(callStats.mPulledAtoms.callStats.length, 1);
        verifyMessageForCallStats(callStats.mPulledAtoms.callStats[0],
                CALL_STATS__CALL_DIRECTION__DIR_INCOMING, false, false, false,
                CALL_STATS__ACCOUNT_TYPE__ACCOUNT_SIM, fakeUid, 1, duration,
                TelephonyManager.NETWORK_TYPE_UNKNOWN);
    }

    @Test
    public void testCallStatsOnMultipleAudioDevices() throws Exception {
        int duration = 1000;
        int fakeUid = 10010;
        PhoneAccount account = mock(PhoneAccount.class);
        Call.CallingPackageIdentity callingPackage = new Call.CallingPackageIdentity();
        PackageManager pm = mock(PackageManager.class);
        ApplicationInfo ai = new ApplicationInfo();
        ai.uid = fakeUid;
        doReturn(ai).when(pm).getApplicationInfo(any(), anyInt());
        doReturn(pm).when(mSpyContext).getPackageManager();
        Context fakeContext = spy(mContext);
        doReturn("").when(fakeContext).getPackageName();
        ComponentName cn = new ComponentName(fakeContext, this.getClass());
        PhoneAccountHandle handle = mock(PhoneAccountHandle.class);
        doReturn(cn).when(handle).getComponentName();
        Call call = mock(Call.class);
        doReturn(true).when(call).isIncoming();
        doReturn(new DisconnectCause(0)).when(call).getDisconnectCause();
        doReturn(0).when(call).getSimultaneousType();
        doReturn(false).when(call).hasVideoCall();
        doReturn(account).when(call).getPhoneAccountFromHandle();
        doReturn((long) duration).when(call).getAgeMillis();
        doReturn(false).when(account).hasCapabilities(eq(PhoneAccount.CAPABILITY_SELF_MANAGED));
        doReturn(true).when(account).hasCapabilities(eq(PhoneAccount.CAPABILITY_CALL_PROVIDER));
        doReturn(true).when(account).hasCapabilities(eq(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION));
        doReturn(callingPackage).when(call).getCallingPackageIdentity();
        doReturn(handle).when(call).getTargetPhoneAccount();
        CallStats callStats = spy(new CallStats(mSpyContext, mLooper, false));

        callStats.onCallStart(call);
        waitForHandlerAction(callStats, TEST_TIMEOUT);

        callStats.onAudioDevicesChange(true);
        waitForHandlerAction(callStats, TEST_TIMEOUT);

        callStats.onCallEnd(call);
        waitForHandlerAction(callStats, TEST_TIMEOUT);

        assertEquals(callStats.mPulledAtoms.callStats.length, 1);
        verifyMessageForCallStats(callStats.mPulledAtoms.callStats[0],
                CALL_STATS__CALL_DIRECTION__DIR_INCOMING, false, false, true,
                CALL_STATS__ACCOUNT_TYPE__ACCOUNT_SIM, fakeUid, 1, duration,
                TelephonyManager.NETWORK_TYPE_UNKNOWN);
    }

    @Test
    public void testCallStatsOnEndWithRat() throws Exception {
        int duration = 1000;
        int fakeUid = 10010;
        int voiceNetworkType = TelephonyManager.NETWORK_TYPE_LTE;
        PhoneAccount account = mock(PhoneAccount.class);
        Call.CallingPackageIdentity callingPackage = new Call.CallingPackageIdentity();
        PackageManager pm = mock(PackageManager.class);
        ApplicationInfo ai = new ApplicationInfo();
        ai.uid = fakeUid;
        doReturn(ai).when(pm).getApplicationInfo(any(), anyInt());
        doReturn(pm).when(mSpyContext).getPackageManager();
        doReturn(true).when(pm).hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS);
        Context fakeContext = spy(mContext);
        doReturn("").when(fakeContext).getPackageName();
        ComponentName cn = new ComponentName(fakeContext, this.getClass());
        PhoneAccountHandle handle = mock(PhoneAccountHandle.class);
        doReturn(cn).when(handle).getComponentName();
        Call call = mock(Call.class);
        doReturn(true).when(call).isIncoming();
        doReturn(new DisconnectCause(0)).when(call).getDisconnectCause();
        doReturn(0).when(call).getSimultaneousType();
        doReturn(false).when(call).hasVideoCall();
        doReturn(account).when(call).getPhoneAccountFromHandle();
        doReturn((long) duration).when(call).getAgeMillis();
        doReturn(false).when(account).hasCapabilities(eq(PhoneAccount.CAPABILITY_SELF_MANAGED));
        doReturn(true).when(account).hasCapabilities(eq(PhoneAccount.CAPABILITY_CALL_PROVIDER));
        doReturn(true).when(account).hasCapabilities(eq(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION));
        doReturn(callingPackage).when(call).getCallingPackageIdentity();
        doReturn(handle).when(call).getTargetPhoneAccount();

        TelephonyManager tm = mSpyContext.getSystemService(TelephonyManager.class);
        doReturn(voiceNetworkType).when(tm).getVoiceNetworkType();

        ConnectivityManager cm = mSpyContext.getSystemService(ConnectivityManager.class);
        Network network = mock(Network.class);
        NetworkCapabilities nc = mock(NetworkCapabilities.class);
        doReturn(network).when(cm).getActiveNetwork();
        doReturn(nc).when(cm).getNetworkCapabilities(network);
        doReturn(true).when(nc).hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        doReturn(true).when(nc).hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE);

        CallStats callStats = spy(new CallStats(mSpyContext, mLooper, false));

        callStats.onCallStart(call);
        waitForHandlerAction(callStats, TEST_TIMEOUT);

        callStats.onCallEnd(call);
        waitForHandlerAction(callStats, TEST_TIMEOUT);

        int expectedRat = voiceNetworkType | CALL_STATS__RAT_ON_END__NETWORK_TYPE_WIFI
                | CALL_STATS__RAT_ON_END__NETWORK_TYPE_SATELLITE;

        assertEquals(callStats.mPulledAtoms.callStats.length, 1);
        verifyMessageForCallStats(callStats.mPulledAtoms.callStats[0],
                CALL_STATS__CALL_DIRECTION__DIR_INCOMING, false, false, false,
                CALL_STATS__ACCOUNT_TYPE__ACCOUNT_SIM, fakeUid, 1, duration,
                expectedRat);
    }

    @Test
    public void testErrorStatsLogCount() throws Exception {
        ErrorStats errorStats = spy(new ErrorStats(mSpyContext, mLooper, false));
        for (int i = 0; i < 10; i++) {
            errorStats.log(VALUE_MODULE_ID, VALUE_ERROR_ID);
            waitForHandlerAction(errorStats, TEST_TIMEOUT);

            verify(errorStats, times(i + 1)).onAggregate();
            verify(errorStats, times(i + 1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
            assertEquals(errorStats.mPulledAtoms.telecomErrorStats.length, 1);
            verifyMessageForErrorStats(errorStats.mPulledAtoms.telecomErrorStats[0],
                    VALUE_MODULE_ID,
                    VALUE_ERROR_ID, i + 1);
        }
    }

    @Test
    public void testErrorStatsLogEvent() throws Exception {
        ErrorStats errorStats = spy(new ErrorStats(mSpyContext, mLooper, false));
        int[] modules = {
                ErrorStats.SUB_UNKNOWN,
                ErrorStats.SUB_CALL_AUDIO,
                ErrorStats.SUB_CALL_LOGS,
                ErrorStats.SUB_CALL_MANAGER,
                ErrorStats.SUB_CONNECTION_SERVICE,
                ErrorStats.SUB_EMERGENCY_CALL,
                ErrorStats.SUB_IN_CALL_SERVICE,
                ErrorStats.SUB_MISC,
                ErrorStats.SUB_PHONE_ACCOUNT,
                ErrorStats.SUB_SYSTEM_SERVICE,
                ErrorStats.SUB_TELEPHONY,
                ErrorStats.SUB_UI,
                ErrorStats.SUB_VOIP_CALL,
        };
        int[] errors = {
                ErrorStats.ERROR_UNKNOWN,
                ErrorStats.ERROR_EXTERNAL_EXCEPTION,
                ErrorStats.ERROR_INTERNAL_EXCEPTION,
                ErrorStats.ERROR_AUDIO_ROUTE_RETRY_REJECTED,
                ErrorStats.ERROR_BT_GET_SERVICE_FAILURE,
                ErrorStats.ERROR_BT_REGISTER_CALLBACK_FAILURE,
                ErrorStats.ERROR_AUDIO_ROUTE_UNAVAILABLE,
                ErrorStats.ERROR_EMERGENCY_NUMBER_DETERMINED_FAILURE,
                ErrorStats.ERROR_NOTIFY_CALL_STREAM_START_FAILURE,
                ErrorStats.ERROR_NOTIFY_CALL_STREAM_STATE_CHANGED_FAILURE,
                ErrorStats.ERROR_NOTIFY_CALL_STREAM_STOP_FAILURE,
                ErrorStats.ERROR_RTT_STREAM_CLOSE_FAILURE,
                ErrorStats.ERROR_RTT_STREAM_CREATE_FAILURE,
                ErrorStats.ERROR_SET_MUTED_FAILURE,
                ErrorStats.ERROR_VIDEO_PROVIDER_SET_FAILURE,
                ErrorStats.ERROR_WIRED_HEADSET_NOT_AVAILABLE,
                ErrorStats.ERROR_LOG_CALL_FAILURE,
                ErrorStats.ERROR_RETRIEVING_ACCOUNT_EMERGENCY,
                ErrorStats.ERROR_RETRIEVING_ACCOUNT,
                ErrorStats.ERROR_EMERGENCY_CALL_ABORTED_NO_ACCOUNT,
                ErrorStats.ERROR_DEFAULT_MO_ACCOUNT_MISMATCH,
                ErrorStats.ERROR_ESTABLISHING_CONNECTION,
                ErrorStats.ERROR_REMOVING_CALL,
                ErrorStats.ERROR_STUCK_CONNECTING_EMERGENCY,
                ErrorStats.ERROR_STUCK_CONNECTING,
        };
        Random rand = new Random();
        Map<Long, Integer> eventMap = new HashMap<>();

        for (int i = 0; i < 10; i++) {
            int module = modules[rand.nextInt(modules.length)];
            int error = errors[rand.nextInt(errors.length)];
            long key = (long) module << 32 | error;
            eventMap.put(key, eventMap.getOrDefault(key, 0) + 1);

            errorStats.log(module, error);
            waitForHandlerAction(errorStats, DELAY_TOLERANCE);

            verify(errorStats, times(i + 1)).onAggregate();
            verify(errorStats, times(i + 1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
            assertEquals(errorStats.mPulledAtoms.telecomErrorStats.length, eventMap.size());
            assertTrue(hasMessageForErrorStats(
                    errorStats.mPulledAtoms.telecomErrorStats, module, error, eventMap.get(key)));
        }
    }

    @Test
    public void testCallSequencingStatsLog() throws Exception {
        CallSequencingStats callSequencingStats =
                spy(new CallSequencingStats(mSpyContext, mLooper, false));
        Call call1 = mock(Call.class);
        doReturn(0).when(callSequencingStats).getCallType(any(Call.class));
        doReturn((long) VALUE_CALL_DURATION).when(call1).getAgeMillis();
        callSequencingStats.setCallSequencingMetrics(call1, null /* primaryCall */);
        callSequencingStats.onCallEnd(call1);
        waitForHandlerAction(callSequencingStats, TEST_TIMEOUT);

        verify(callSequencingStats, times(1))
                .log(any(CallSequencingStats.CallSequencingStatsKey.class), anyInt());
        verify(callSequencingStats, times(1)).onAggregate();
        verify(callSequencingStats, times(1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(callSequencingStats.mPulledAtoms.callSequencingStats.length, 1);
        verifyMessageForCallSequencingStats(callSequencingStats.mPulledAtoms.callSequencingStats[0],
                0, 0, false, false, false, false,
                VALUE_CALL_DURATION, VALUE_EVENT_COUNT);

        Call call2 = mock(Call.class);
        doReturn(false).when(call2).isSelfManaged();
        doReturn(false).when(call2).isTransactionalCall();
        doReturn((long) VALUE_CALL_DURATION).when(call2).getAgeMillis();
        callSequencingStats.setCallSequencingMetrics(call2, null /* primaryCall */);
        callSequencingStats.onCallEnd(call2);
        waitForHandlerAction(callSequencingStats, TEST_TIMEOUT);

        verify(callSequencingStats, times(2))
                .log(any(CallSequencingStats.CallSequencingStatsKey.class), anyInt());
        verify(callSequencingStats, times(2)).onAggregate();
        verify(callSequencingStats, times(2)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(callSequencingStats.mPulledAtoms.callSequencingStats.length, 1);
        verifyMessageForCallSequencingStats(callSequencingStats.mPulledAtoms.callSequencingStats[0],
                0, 0, false, false, false, false,
                VALUE_CALL_DURATION, 2);
    }

    @Test
    public void testCallEndpointStatsLog() throws Exception {
        CallEndpointStats callEndpointStats =
                spy(new CallEndpointStats(mSpyContext, mLooper, false));

        callEndpointStats.log(VALUE_UID, VALUE_ENDPOINT_TYPE1, VALUE_ENDPOINT_TYPE2,
                VALUE_ENDPOINT_RESULT, VALUE_ENDPOINT_TIMEOUT, VALUE_ENDPOINT_LATENCY);
        waitForHandlerAction(callEndpointStats, TEST_TIMEOUT);

        verify(callEndpointStats, times(1)).onAggregate();
        verify(callEndpointStats, times(1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(callEndpointStats.mPulledAtoms.callEndpointStats.length, 1);
        verifyMessageForCallEndpointStats(callEndpointStats.mPulledAtoms.callEndpointStats[0],
                VALUE_UID, VALUE_ENDPOINT_TYPE1, VALUE_ENDPOINT_TYPE2, VALUE_ENDPOINT_RESULT,
                VALUE_ENDPOINT_TIMEOUT, 1, VALUE_ENDPOINT_LATENCY);

        callEndpointStats.log(VALUE_UID, VALUE_ENDPOINT_TYPE1, VALUE_ENDPOINT_TYPE2,
                VALUE_ENDPOINT_RESULT, VALUE_ENDPOINT_TIMEOUT, VALUE_ENDPOINT_LATENCY);
        waitForHandlerAction(callEndpointStats, TEST_TIMEOUT);

        verify(callEndpointStats, times(2)).onAggregate();
        verify(callEndpointStats, times(2)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(callEndpointStats.mPulledAtoms.callEndpointStats.length, 1);
        verifyMessageForCallEndpointStats(callEndpointStats.mPulledAtoms.callEndpointStats[0],
                VALUE_UID, VALUE_ENDPOINT_TYPE1, VALUE_ENDPOINT_TYPE2, VALUE_ENDPOINT_RESULT,
                VALUE_ENDPOINT_TIMEOUT, 2, VALUE_ENDPOINT_LATENCY);
    }

    @Test
    public void testApiStatsWithTestModeOn() throws Exception {
        final List<StatsEvent> data = new ArrayList<>();
        ApiStats apiStats = spy(new ApiStats(mSpyContext, mLooper, true));
        apiStats.pull(data);
        apiStats.flush();

        verify(mSpyContext, never()).getFileStreamPath(anyString());
        verify(apiStats, times(1)).onPull(any());
        verify(mSpyContext, never()).openFileOutput(anyString(), anyInt());
    }

    @Test
    public void testAudioRouteStatsWithTestModeOn() throws Exception {
        final List<StatsEvent> data = new ArrayList<>();
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper, true));
        audioRouteStats.pull(data);
        audioRouteStats.flush();

        verify(mSpyContext, never()).getFileStreamPath(anyString());
        verify(audioRouteStats, times(1)).onPull(any());
        verify(mSpyContext, never()).openFileOutput(anyString(), anyInt());
    }

    @Test
    public void testCallStatsWithTestModeOn() throws Exception {
        final List<StatsEvent> data = new ArrayList<>();
        CallStats callStats = spy(new CallStats(mSpyContext, mLooper, true));
        callStats.pull(data);
        callStats.flush();

        verify(mSpyContext, never()).getFileStreamPath(anyString());
        verify(callStats, times(1)).onPull(any());
        verify(mSpyContext, never()).openFileOutput(anyString(), anyInt());
    }

    @Test
    public void testErrorStatsWithTestModeOn() throws Exception {
        final List<StatsEvent> data = new ArrayList<>();
        ErrorStats errorStats = spy(new ErrorStats(mSpyContext, mLooper, true));
        errorStats.pull(data);
        errorStats.flush();

        verify(mSpyContext, never()).getFileStreamPath(anyString());
        verify(errorStats, times(1)).onPull(any());
        verify(mSpyContext, never()).openFileOutput(anyString(), anyInt());
    }

    @Test
    public void testCallSequencingStatsWithTestModeOn() throws Exception {
        final List<StatsEvent> data = new ArrayList<>();
        CallSequencingStats callSequencingStats =
                spy(new CallSequencingStats(mSpyContext, mLooper, true));
        callSequencingStats.pull(data);
        callSequencingStats.flush();

        verify(mSpyContext, never()).getFileStreamPath(anyString());
        verify(callSequencingStats, times(1)).onPull(any());
        verify(mSpyContext, never()).openFileOutput(anyString(), anyInt());
    }

    @Test
    public void testCallEndpointStatsWithTestModeOn() throws Exception {
        final List<StatsEvent> data = new ArrayList<>();
        CallEndpointStats callEndpointStats =
                spy(new CallEndpointStats(mSpyContext, mLooper, true));
        callEndpointStats.pull(data);
        callEndpointStats.flush();

        verify(mSpyContext, never()).getFileStreamPath(anyString());
        verify(callEndpointStats, times(1)).onPull(any());
        verify(mSpyContext, never()).openFileOutput(anyString(), anyInt());
    }

    @Test
    public void testPullEventStatsLessThanMinPullIntervalShouldSkip() throws Exception {
        createTestFileForEventStats(System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS / 2);
        EventStats eventStats = spy(new EventStats(mSpyContext, mLooper, false));
        final List<StatsEvent> data = new ArrayList<>();

        int result = eventStats.pull(data);

        assertEquals(StatsManager.PULL_SUCCESS, result);
        verify(eventStats, never()).onPull(any());
        assertEquals(data.size(), 0);
    }

    @Test
    public void testPullEventStatsGreaterThanMinPullIntervalShouldNotSkip() throws Exception {
        createTestFileForEventStats(System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS - 1);
        EventStats eventStats = spy(new EventStats(mSpyContext, mLooper, false));
        final List<StatsEvent> data = new ArrayList<>();
        int sizePulled = eventStats.mPulledAtoms.telecomEventStats.length;

        int result = eventStats.pull(data);

        assertEquals(StatsManager.PULL_SUCCESS, result);
        verify(eventStats).onPull(eq(data));
        assertEquals(data.size(), sizePulled);
        assertEquals(eventStats.mPulledAtoms.telecomEventStats.length, 0);
    }

    @Test
    public void testEventStatsLogCount() throws Exception {
        EventStats eventStats = spy(new EventStats(mSpyContext, mLooper, false));
        EventStats.CriticalEvent event = new EventStats.CriticalEvent(
                VALUE_EVENT_ID, VALUE_UID, VALUE_CAUSE_ID);

        for (int i = 0; i < 10; i++) {
            eventStats.log(event);
            waitForHandlerAction(eventStats, TEST_TIMEOUT);

            verify(eventStats, times(i + 1)).onAggregate();
            verify(eventStats, times(i + 1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
            assertEquals(eventStats.mPulledAtoms.telecomEventStats.length, 1);
            verifyMessageForEventStats(eventStats.mPulledAtoms.telecomEventStats[0],
                    VALUE_EVENT_ID, VALUE_UID, VALUE_CAUSE_ID, i + 1);
        }
    }

    @Test
    public void testEventStatsLogEvent() throws Exception {
        EventStats eventStats = spy(new EventStats(mSpyContext, mLooper, false));
        int[] events = {
                EventStats.ID_UNKNOWN,
                EventStats.ID_INIT,
                EventStats.ID_DEFAULT_DIALER_CHANGED,
                EventStats.ID_ADD_CALL,
                EventStats.ID_LOG_VOIP_CALL_INCOMING_VOICE,
                EventStats.ID_LOG_VOIP_CALL_INCOMING_VIDEO,
                EventStats.ID_LOG_VOIP_CALL_OUTGOING_VOICE,
                EventStats.ID_LOG_VOIP_CALL_OUTGOING_VIDEO,
                EventStats.ID_CALL_BACK,
        };
        int[] causes = {
                EventStats.CAUSE_UNKNOWN,
                EventStats.CAUSE_GENERIC_SUCCESS,
                EventStats.CAUSE_GENERIC_FAILURE,
                EventStats.CAUSE_CALL_TRANSACTION_SUCCESS,
                EventStats.CAUSE_CALL_TRANSACTION_ERROR_UNKNOWN,
                EventStats.CAUSE_CALL_TRANSACTION_CALL_CANNOT_BE_SET_TO_ACTIVE,
                EventStats.CAUSE_CALL_TRANSACTION_CALL_IS_NOT_BEING_TRACKED,
                EventStats.CAUSE_CALL_TRANSACTION_CANNOT_HOLD_CURRENT_ACTIVE_CALL,
                EventStats.CAUSE_CALL_TRANSACTION_CALL_NOT_PERMITTED_AT_PRESENT_TIME,
                EventStats.CAUSE_CALL_TRANSACTION_OPERATION_TIMED_OUT,
                EventStats.CAUSE_CALL_LOG_OPT_OUT,
                EventStats.CAUSE_CALL_LOG_OPT_IN,
                EventStats.CAUSE_CALL_LOG_OPT_IN_EXCLUDED,
        };
        Random rand = new Random();
        Map<EventStats.CriticalEvent, Integer> eventMap = new HashMap<>();

        for (int i = 0; i < 10; i++) {
            int e = events[rand.nextInt(events.length)];
            int uid = rand.nextInt(65535);
            int cause = causes[rand.nextInt(causes.length)];
            EventStats.CriticalEvent ce = new EventStats.CriticalEvent(e, uid, cause);
            eventMap.put(ce, eventMap.getOrDefault(ce, 0) + 1);

            eventStats.log(ce);
            waitForHandlerAction(eventStats, TEST_TIMEOUT);

            verify(eventStats, times(i + 1)).onAggregate();
            verify(eventStats, times(i + 1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
            assertEquals(eventStats.mPulledAtoms.telecomEventStats.length, eventMap.size());
            assertTrue(hasMessageForEventStats(eventStats.mPulledAtoms.telecomEventStats,
                    e, uid, cause, eventMap.get(ce)));
        }
    }

    @Test
    public void testCriticalEventEquals() {
        //Test equal
        EventStats.CriticalEvent event1 = new EventStats.CriticalEvent(1, 100, 1);
        EventStats.CriticalEvent event2 = new EventStats.CriticalEvent(1, 100, 1);
        assertEquals(event1, event2);

        //Test not equal
        event1 = new EventStats.CriticalEvent(1, 100, 1);
        event2 = new EventStats.CriticalEvent(2, 100, 1);
        assertNotEquals(event1, event2);

        //Test hashCode
        event1 = new EventStats.CriticalEvent(1, 100, 1);
        event2 = new EventStats.CriticalEvent(1, 100, 1);
        assertEquals(event1.hashCode(), event2.hashCode());
    }

    /**
     * Verifies that loading a corrupt or malformed file does not cause a crash.
     * Instead, it should fall back to creating a new, empty atom instance. This tests
     * the IOException catch block in {@link TelecomPulledAtom#loadAtomsFromFile()}.
     */
    @Test
    public void testLoadAtomsFromFile_corruptFile() throws Exception {
        // Write invalid data to the file to simulate corruption, which should cause a
        // parsing IOException.
        try (FileOutputStream stream = new FileOutputStream(mTempFile)) {
            stream.write(new byte[] {0x01, 0x02, 0x03});
        }

        // Instantiate a subclass. The constructor calls loadAtomsFromFile().
        ApiStats apiStats = new ApiStats(mSpyContext, mLooper, false);

        // Verify that it falls back to creating a new, empty PulledAtoms object
        // instead of crashing.
        assertNotNull(apiStats.mPulledAtoms);
        assertEquals(0, apiStats.mPulledAtoms.telecomApiStats.length);
    }

    /**
     * Verifies that multiple rapid calls to save with a delay result in only one
     * scheduled file write. This tests the coalescing logic implemented with
     * {@code if (!hasMessages(EVENT_SAVE))}.
     */
    @Test
    public void testSave_coalescesMultipleDelayedRequests() {
        // Use a spy to monitor calls to sendMessageDelayed.
        ApiStats apiStats = spy(new ApiStats(mSpyContext, mLooper, false));

        // Call save with a delay multiple times in quick succession.
        apiStats.save(DELAY_FOR_PERSISTENT_MILLIS);
        apiStats.save(DELAY_FOR_PERSISTENT_MILLIS);

        // Verify that a delayed message was scheduled only once, because the second call
        // should see that a message is already pending.
        verify(apiStats, times(1))
          .sendMessageDelayed(any(), eq((long) DELAY_FOR_PERSISTENT_MILLIS));
    }

    /**
     * Verifies that calling save with a zero or negative delay triggers an immediate,
     * synchronous file write, bypassing the handler's message queue.
     */
    @Test
    public void testSave_immediateSaveWithZeroDelay() throws Exception {
        // Use a spy to monitor calls.
        ApiStats apiStats = spy(new ApiStats(mSpyContext, mLooper, false));

        // Call save with zero delay to trigger an immediate, synchronous save.
        apiStats.save(0);

        // Verify that the save operation (writing to a file) was performed immediately.
        // This is an indirect way of verifying the private onSave() method was called.
        verify(mFileOutputStream).write(any(byte[].class));

        // Verify that no delayed message was sent to the handler.
        verify(apiStats, never()).sendMessageDelayed(any(), anyLong());
    }

    private void createTestFileForApiStats(long timestamps) throws IOException {
        PulledAtomsClass.PulledAtoms atom = new PulledAtomsClass.PulledAtoms();
        atom.telecomApiStats =
                new PulledAtomsClass.TelecomApiStats[VALUE_ATOM_COUNT];
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            atom.telecomApiStats[i] = new PulledAtomsClass.TelecomApiStats();
            atom.telecomApiStats[i].setApiName(VALUE_API_ID + i);
            atom.telecomApiStats[i].setUid(VALUE_UID);
            atom.telecomApiStats[i].setApiResult(VALUE_API_RESULT);
            atom.telecomApiStats[i].setCount(VALUE_API_COUNT);
        }
        atom.setTelecomApiStatsPullTimestampMillis(timestamps);

        FileOutputStream stream = new FileOutputStream(mTempFile);
        stream.write(PulledAtomsClass.PulledAtoms.toByteArray(atom));
        stream.close();
    }

    private void verifyTestDataForApiStats(final PulledAtomsClass.PulledAtoms atom,
            long timestamps) {
        assertNotNull(atom);
        assertEquals(atom.getTelecomApiStatsPullTimestampMillis(), timestamps);
        assertNotNull(atom.telecomApiStats);
        assertEquals(atom.telecomApiStats.length, VALUE_ATOM_COUNT);
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            assertNotNull(atom.telecomApiStats[i]);
            verifyMessageForApiStats(atom.telecomApiStats[i], VALUE_API_ID + i, VALUE_UID,
                    VALUE_API_RESULT, VALUE_API_COUNT);
        }
    }

    private void verifyMessageForApiStats(final PulledAtomsClass.TelecomApiStats msg, int apiId,
            int uid, int result, int count) {
        assertEquals(msg.getApiName(), apiId);
        assertEquals(msg.getUid(), uid);
        assertEquals(msg.getApiResult(), result);
        assertEquals(msg.getCount(), count);
    }

    private boolean hasMessageForApiStats(final PulledAtomsClass.TelecomApiStats[] msgs, int apiId,
            int uid, int result, int count) {
        for (PulledAtomsClass.TelecomApiStats msg : msgs) {
            if (msg.getApiName() == apiId && msg.getUid() == uid && msg.getApiResult() == result
                    && msg.getCount() == count) {
                return true;
            }
        }
        return false;
    }

    private void createTestFileForAudioRouteStats(long timestamps) throws IOException {
        PulledAtomsClass.PulledAtoms atom = new PulledAtomsClass.PulledAtoms();
        atom.callAudioRouteStats =
                new PulledAtomsClass.CallAudioRouteStats[VALUE_ATOM_COUNT];
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            atom.callAudioRouteStats[i] = new PulledAtomsClass.CallAudioRouteStats();
            atom.callAudioRouteStats[i].setCallAudioRouteSource(VALUE_AUDIO_ROUTE_TYPE1);
            atom.callAudioRouteStats[i].setCallAudioRouteDest(VALUE_AUDIO_ROUTE_TYPE2);
            atom.callAudioRouteStats[i].setSuccess(true);
            atom.callAudioRouteStats[i].setRevert(false);
            atom.callAudioRouteStats[i].setCount(VALUE_AUDIO_ROUTE_COUNT);
            atom.callAudioRouteStats[i].setAverageLatencyMs(VALUE_AUDIO_ROUTE_LATENCY);
        }
        atom.setCallAudioRouteStatsPullTimestampMillis(timestamps);
        FileOutputStream stream = new FileOutputStream(mTempFile);
        stream.write(PulledAtomsClass.PulledAtoms.toByteArray(atom));
        stream.close();
    }

    private void verifyTestDataForAudioRouteStats(final PulledAtomsClass.PulledAtoms atom,
            long timestamps) {
        assertNotNull(atom);
        assertEquals(atom.getCallAudioRouteStatsPullTimestampMillis(), timestamps);
        assertNotNull(atom.callAudioRouteStats);
        assertEquals(atom.callAudioRouteStats.length, VALUE_ATOM_COUNT);
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            assertNotNull(atom.callAudioRouteStats[i]);
            verifyMessageForAudioRouteStats(atom.callAudioRouteStats[i], VALUE_AUDIO_ROUTE_TYPE1,
                    VALUE_AUDIO_ROUTE_TYPE2, true, false, VALUE_AUDIO_ROUTE_COUNT,
                    VALUE_AUDIO_ROUTE_LATENCY);
        }
    }

    private void verifyMessageForAudioRouteStats(
            final PulledAtomsClass.CallAudioRouteStats msg, int source, int dest, boolean success,
            boolean revert, int count, int latency) {
        assertEquals(msg.getCallAudioRouteSource(), source);
        assertEquals(msg.getCallAudioRouteDest(), dest);
        assertEquals(msg.getSuccess(), success);
        assertEquals(msg.getRevert(), revert);
        assertEquals(msg.getCount(), count);
        assertTrue(Math.abs(latency - msg.getAverageLatencyMs()) < DELAY_TOLERANCE);
    }

    private void createTestFileForCallStats(long timestamps) throws IOException {
        PulledAtomsClass.PulledAtoms atom = new PulledAtomsClass.PulledAtoms();
        atom.callStats =
                new PulledAtomsClass.CallStats[VALUE_ATOM_COUNT];
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            atom.callStats[i] = new PulledAtomsClass.CallStats();
            atom.callStats[i].setCallDirection(VALUE_CALL_DIRECTION);
            atom.callStats[i].setExternalCall(false);
            atom.callStats[i].setEmergencyCall(false);
            atom.callStats[i].setMultipleAudioAvailable(false);
            atom.callStats[i].setAccountType(VALUE_CALL_ACCOUNT_TYPE);
            atom.callStats[i].setUid(VALUE_UID);
            atom.callStats[i].setRatOnEnd(VALUE_CALL_RAT);
            atom.callStats[i].setCount(VALUE_CALL_COUNT);
            atom.callStats[i].setAverageDurationMs(VALUE_CALL_DURATION);
            atom.callStats[i].repeatedIntDurations = new int[]{VALUE_CALL_DURATION};
        }
        atom.setCallStatsPullTimestampMillis(timestamps);
        FileOutputStream stream = new FileOutputStream(mTempFile);
        stream.write(PulledAtomsClass.PulledAtoms.toByteArray(atom));
        stream.close();
    }

    private void verifyTestDataForCallStats(final PulledAtomsClass.PulledAtoms atom,
            long timestamps) {
        assertNotNull(atom);
        assertEquals(atom.getCallStatsPullTimestampMillis(), timestamps);
        assertNotNull(atom.callStats);
        assertEquals(atom.callStats.length, VALUE_ATOM_COUNT);
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            assertNotNull(atom.callStats[i]);
            verifyMessageForCallStats(atom.callStats[i], VALUE_CALL_DIRECTION, false, false,
                    false, VALUE_CALL_ACCOUNT_TYPE, VALUE_UID, VALUE_CALL_COUNT,
                    VALUE_CALL_DURATION, VALUE_CALL_RAT);
        }
    }

    private void verifyMessageForCallStats(final PulledAtomsClass.CallStats msg,
            int direction, boolean external, boolean emergency, boolean multipleAudio,
            int accountType, int uid, int count, int duration, int rat) {
        verifyMessageForCallStats(msg, direction, external, emergency, multipleAudio, accountType,
                uid, count, duration, rat, new int[]{duration});
    }

    private void verifyMessageForCallStats(final PulledAtomsClass.CallStats msg,
            int direction, boolean external, boolean emergency, boolean multipleAudio,
            int accountType, int uid, int count, int duration, int rat, int[] durations) {
        assertEquals(msg.getCallDirection(), direction);
        assertEquals(msg.getExternalCall(), external);
        assertEquals(msg.getEmergencyCall(), emergency);
        assertEquals(msg.getMultipleAudioAvailable(), multipleAudio);
        assertEquals(msg.getAccountType(), accountType);
        assertEquals(msg.getUid(), uid);
        assertEquals(msg.getCount(), count);
        assertEquals(msg.getAverageDurationMs(), duration);
        assertEquals(msg.getRatOnEnd(), rat);
        assertArrayEquals(msg.repeatedIntDurations, durations);
    }

    private void createTestFileForErrorStats(long timestamps) throws IOException {
        PulledAtomsClass.PulledAtoms atom = new PulledAtomsClass.PulledAtoms();
        atom.telecomErrorStats =
                new PulledAtomsClass.TelecomErrorStats[VALUE_ATOM_COUNT];
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            atom.telecomErrorStats[i] = new PulledAtomsClass.TelecomErrorStats();
            atom.telecomErrorStats[i].setSubmodule(VALUE_MODULE_ID);
            atom.telecomErrorStats[i].setError(VALUE_ERROR_ID);
            atom.telecomErrorStats[i].setCount(VALUE_ERROR_COUNT);
        }
        atom.setTelecomErrorStatsPullTimestampMillis(timestamps);
        FileOutputStream stream = new FileOutputStream(mTempFile);
        stream.write(PulledAtomsClass.PulledAtoms.toByteArray(atom));
        stream.close();
    }

    private void verifyTestDataForErrorStats(
            final PulledAtomsClass.PulledAtoms atom, long timestamps) {
        assertNotNull(atom);
        assertEquals(atom.getTelecomErrorStatsPullTimestampMillis(), timestamps);
        assertNotNull(atom.telecomErrorStats);
        assertEquals(atom.telecomErrorStats.length, VALUE_ATOM_COUNT);
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            assertNotNull(atom.telecomErrorStats[i]);
            verifyMessageForErrorStats(atom.telecomErrorStats[i], VALUE_MODULE_ID,
                    VALUE_ERROR_ID, VALUE_ERROR_COUNT);
        }
    }

    private void verifyMessageForErrorStats(final PulledAtomsClass.TelecomErrorStats msg,
            int moduleId, int errorId, int count) {
        assertEquals(msg.getSubmodule(), moduleId);
        assertEquals(msg.getError(), errorId);
        assertEquals(msg.getCount(), count);
    }

    private boolean hasMessageForErrorStats(final PulledAtomsClass.TelecomErrorStats[] msgs,
            int moduleId, int errorId, int count) {
        for (PulledAtomsClass.TelecomErrorStats msg : msgs) {
            if (msg.getSubmodule() == moduleId && msg.getError() == errorId
                    && msg.getCount() == count) {
                return true;
            }
        }
        return false;
    }

    private void createTestFileForEventStats(long timestamps) throws IOException {
        PulledAtomsClass.PulledAtoms atom = new PulledAtomsClass.PulledAtoms();
        atom.telecomEventStats =
                new PulledAtomsClass.TelecomEventStats[VALUE_ATOM_COUNT];
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            atom.telecomEventStats[i] = new PulledAtomsClass.TelecomEventStats();
            atom.telecomEventStats[i].setEvent(VALUE_EVENT_ID + i);
            atom.telecomEventStats[i].setUid(VALUE_UID);
            atom.telecomEventStats[i].setEventCause(VALUE_CAUSE_ID);
            atom.telecomEventStats[i].setCount(VALUE_EVENT_COUNT);
        }
        atom.setTelecomEventStatsPullTimestampMillis(timestamps);
        FileOutputStream stream = new FileOutputStream(mTempFile);
        stream.write(PulledAtomsClass.PulledAtoms.toByteArray(atom));
        stream.close();
    }

    private void verifyTestDataForEventStats(
            final PulledAtomsClass.PulledAtoms atom, long timestamps) {
        assertNotNull(atom);
        assertEquals(atom.getTelecomEventStatsPullTimestampMillis(), timestamps);
        assertNotNull(atom.telecomEventStats);
        assertEquals(atom.telecomEventStats.length, VALUE_ATOM_COUNT);
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            assertNotNull(atom.telecomEventStats[i]);
            verifyMessageForEventStats(atom.telecomEventStats[i], VALUE_EVENT_ID + i,
                    VALUE_UID, VALUE_CAUSE_ID, VALUE_EVENT_COUNT);
        }
    }

    private void verifyMessageForEventStats(final PulledAtomsClass.TelecomEventStats msg,
                                            int eventId, int uid, int causeId, int count) {
        assertEquals(msg.getEvent(), eventId);
        assertEquals(msg.getUid(), uid);
        assertEquals(msg.getEventCause(), causeId);
        assertEquals(msg.getCount(), count);
    }

    private boolean hasMessageForEventStats(final PulledAtomsClass.TelecomEventStats[] msgs,
                                            int eventId, int uid, int causeId, int count) {
        for (PulledAtomsClass.TelecomEventStats msg : msgs) {
            if (msg.getEvent() == eventId && msg.getUid() == uid
                    && msg.getEventCause() == causeId && msg.getCount() == count) {
                return true;
            }
        }
        return false;
    }

    private void createTestFileForCallEndpointStats(long timestamps) throws IOException {
        PulledAtomsClass.PulledAtoms atom = new PulledAtomsClass.PulledAtoms();
        atom.callEndpointStats =
                new PulledAtomsClass.CallEndPointStats[VALUE_ATOM_COUNT];
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            atom.callEndpointStats[i] = new PulledAtomsClass.CallEndPointStats();
            atom.callEndpointStats[i].setUid(VALUE_UID);
            atom.callEndpointStats[i].setEndpointRequested(VALUE_ENDPOINT_TYPE1);
            atom.callEndpointStats[i].setEndpointNotified(VALUE_ENDPOINT_TYPE2);
            atom.callEndpointStats[i].setResult(VALUE_ENDPOINT_RESULT);
            atom.callEndpointStats[i].setTimeout(VALUE_ENDPOINT_TIMEOUT);
            atom.callEndpointStats[i].setCount(VALUE_ENDPOINT_COUNT);
            atom.callEndpointStats[i].setAverageLatencyMs(VALUE_ENDPOINT_LATENCY);
        }
        atom.setCallEndpointStatsPullTimestampMillis(timestamps);
        FileOutputStream stream = new FileOutputStream(mTempFile);
        stream.write(PulledAtomsClass.PulledAtoms.toByteArray(atom));
        stream.close();
    }

    private void verifyTestDataForCallEndpointStats(
            final PulledAtomsClass.PulledAtoms atom, long timestamps) {
        assertNotNull(atom);
        assertEquals(atom.getCallEndpointStatsPullTimestampMillis(), timestamps);
        assertNotNull(atom.callEndpointStats);
        assertEquals(atom.callEndpointStats.length, VALUE_ATOM_COUNT);
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            assertNotNull(atom.callEndpointStats[i]);
            verifyMessageForCallEndpointStats(atom.callEndpointStats[i], VALUE_UID,
                    VALUE_ENDPOINT_TYPE1, VALUE_ENDPOINT_TYPE2, VALUE_ENDPOINT_RESULT,
                    VALUE_ENDPOINT_TIMEOUT, VALUE_ENDPOINT_COUNT, VALUE_ENDPOINT_LATENCY);
        }
    }

    private void verifyMessageForCallEndpointStats(
            final PulledAtomsClass.CallEndPointStats msg, int uid, int requested, int notified,
            int result, boolean isTimeout, int count, int latency) {
        assertEquals(msg.getUid(), uid);
        assertEquals(msg.getEndpointRequested(), requested);
        assertEquals(msg.getEndpointNotified(), notified);
        assertEquals(msg.getResult(), result);
        assertEquals(msg.getTimeout(), isTimeout);
        assertEquals(msg.getCount(), count);
        assertTrue(Math.abs(latency - msg.getAverageLatencyMs()) < DELAY_TOLERANCE);
    }

    private void createTestFileForCallSequencingStats(long timestamps) throws IOException {
        PulledAtomsClass.PulledAtoms atom = new PulledAtomsClass.PulledAtoms();
        atom.callSequencingStats =
                new PulledAtomsClass.CallSequencingStats[VALUE_ATOM_COUNT];
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            atom.callSequencingStats[i] = new PulledAtomsClass.CallSequencingStats();
            atom.callSequencingStats[i].setPrimaryCallType(0);
            atom.callSequencingStats[i].setSecondaryCallType(0);
            atom.callSequencingStats[i].setIsPrimaryCallEmergency(false);
            atom.callSequencingStats[i].setIsSecondaryCallEmergency(false);
            atom.callSequencingStats[i].setHasSecondary(false);
            atom.callSequencingStats[i].setIsSamePhoneAccount(false);
            atom.callSequencingStats[i].setCount(VALUE_EVENT_COUNT);
            atom.callSequencingStats[i].setAverageDurationMs(VALUE_CALL_DURATION);
        }
        atom.setCallSequencingStatsPullTimestampMillis(timestamps);
        FileOutputStream stream = new FileOutputStream(mTempFile);
        stream.write(PulledAtomsClass.PulledAtoms.toByteArray(atom));
        stream.close();
    }

    private void verifyTestDataForCallSequencingStats(
            final PulledAtomsClass.PulledAtoms atom, long timestamps) {
        assertNotNull(atom);
        assertEquals(atom.getCallSequencingStatsPullTimestampMillis(), timestamps);
        assertNotNull(atom.callSequencingStats);
        assertEquals(atom.callSequencingStats.length, VALUE_ATOM_COUNT);
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            assertNotNull(atom.callSequencingStats[i]);
            PulledAtomsClass.CallSequencingStats callSequencingStats = atom.callSequencingStats[i];
            verifyMessageForCallSequencingStats(atom.callSequencingStats[i], 0,
                    0, false, false, false, false, VALUE_CALL_DURATION,
                    VALUE_EVENT_COUNT);
        }
    }

    private void verifyMessageForCallSequencingStats(final PulledAtomsClass.CallSequencingStats msg,
            int primaryCallState, int secondaryCallState, boolean isPrimaryCallEmergency,
            boolean isSecondaryCallEmergency, boolean hasSecondary, boolean isSamePhoneAccount,
            int duration, int count) {
        assertEquals(msg.getPrimaryCallType(), primaryCallState);
        assertEquals(msg.getSecondaryCallType(), secondaryCallState);
        assertEquals(msg.getIsPrimaryCallEmergency(), isPrimaryCallEmergency);
        assertEquals(msg.getIsSecondaryCallEmergency(), isSecondaryCallEmergency);
        assertEquals(msg.getHasSecondary(), hasSecondary);
        assertEquals(msg.getIsSamePhoneAccount(), isSamePhoneAccount);
        assertEquals(msg.getAverageDurationMs(), duration);
        assertEquals(msg.getCount(), count);
    }
}
