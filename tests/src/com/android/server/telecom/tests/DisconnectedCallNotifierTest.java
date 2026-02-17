package com.android.server.telecom.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.net.Uri;
import android.os.UserHandle;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telephony.TelephonyManager;

import android.util.DisplayMetrics;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallState;
import com.android.server.telecom.CallerInfoLookupHelper;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.TelecomResourceId;
import com.android.server.telecom.ui.DisconnectedCallNotifier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.Collections;

public class DisconnectedCallNotifierTest extends TelecomTestCase {

    private static final PhoneAccountHandle PHONE_ACCOUNT_HANDLE = new PhoneAccountHandle(
            new ComponentName("com.android.server.telecom.tests", "DisconnectedCallNotifierTest"),
            "testId");
    private static final Uri TEL_CALL_HANDLE = Uri.parse("tel:+11915552620");

    @Mock private CallsManager mCallsManager;
    @Mock private CallerInfoLookupHelper mCallerInfoLookupHelper;
    @Mock private Context mUserContext;
    @Mock private Resources mResources;
    @Mock private Resources.Theme mTheme;
    @Mock private DisplayMetrics mDisplayMetrics;

    private NotificationManager mNotificationManager;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        mContext = mComponentContextFixture.getTestDouble().getApplicationContext();
        TelecomResourceId.setTelecomContext(mContext);
        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getIdentifier(anyString(), anyString(), anyString())).thenReturn(1);
        when(mContext.getTheme()).thenReturn(mTheme);
        when(mResources.newTheme()).thenReturn(mTheme);
        when(mResources.getDisplayMetrics()).thenReturn(mDisplayMetrics);
        mDisplayMetrics.density = 1.0f;

        mNotificationManager = (NotificationManager) mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);
        when(mContext.createContextAsUser(any(UserHandle.class), eq(0)))
                .thenReturn(mUserContext);
        when(mUserContext.getSystemService(eq(NotificationManager.class)))
                .thenReturn(mNotificationManager);
        when(mUserContext.getPackageName()).thenReturn("com.android.server.telecom.tests");
        TelephonyManager fakeTelephonyManager = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);
        when(fakeTelephonyManager.getNetworkCountryIso()).thenReturn("US");
        doReturn(mCallerInfoLookupHelper).when(mCallsManager).getCallerInfoLookupHelper();
    }

    @After
    @Override
    public void tearDown() throws Exception {
        TelecomResourceId.setTelecomContext(null);
        super.tearDown();
    }

    @Test
    @SmallTest
    public void testNotificationShownAfterEmergencyCall() {
        Call call = createCall(new DisconnectCause(DisconnectCause.LOCAL,
                DisconnectCause.REASON_EMERGENCY_CALL_PLACED));

        DisconnectedCallNotifier notifier = new DisconnectedCallNotifier(mContext, mCallsManager,
                mFeatureFlags);
        notifier.onCallStateChanged(call, CallState.NEW, CallState.DIALING);
        notifier.onCallStateChanged(call, CallState.DIALING, CallState.DISCONNECTED);
        verify(mNotificationManager, never()).notify(anyString(), anyInt(),
                any(Notification.class));

        doReturn(Collections.EMPTY_LIST).when(mCallsManager).getCalls();
        notifier.onCallRemoved(call);
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(mNotificationManager).notify(anyString(), anyInt(),
                captor.capture());
        Notification notification = captor.getValue();
        assertNotNull(notification.contentIntent);
        assertEquals(2, notification.actions.length);
    }

    @Test
    @SmallTest
    public void testNotificationShownForDisconnectedEmergencyCall() {
        Call call = createCall(new DisconnectCause(DisconnectCause.LOCAL,
                DisconnectCause.REASON_EMERGENCY_CALL_PLACED));
        when(call.isEmergencyCall()).thenReturn(true);

        DisconnectedCallNotifier notifier = new DisconnectedCallNotifier(mContext, mCallsManager,
                mFeatureFlags);
        notifier.onCallStateChanged(call, CallState.NEW, CallState.DIALING);
        notifier.onCallStateChanged(call, CallState.DIALING, CallState.DISCONNECTED);
        verify(mNotificationManager, never()).notify(anyString(), anyInt(),
                any(Notification.class));

        doReturn(Collections.EMPTY_LIST).when(mCallsManager).getCalls();
        notifier.onCallRemoved(call);
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(mNotificationManager).notify(anyString(), anyInt(),
                captor.capture());
        Notification notification = captor.getValue();
        assertNull(notification.contentIntent);
        if (notification.actions != null) {
            assertEquals(0, notification.actions.length);
        }
    }

    @Test
    @SmallTest
    public void testNotificationNotShownAfterCall() {
        Call call = createCall(new DisconnectCause(DisconnectCause.LOCAL));

        DisconnectedCallNotifier notifier = new DisconnectedCallNotifier(mContext, mCallsManager,
                mFeatureFlags);
        notifier.onCallStateChanged(call, CallState.DIALING, CallState.DISCONNECTED);
        verify(mNotificationManager, never()).notify(anyString(), anyInt(),
                any(Notification.class));

        doReturn(Collections.EMPTY_LIST).when(mCallsManager).getCalls();
        notifier.onCallRemoved(call);
        verify(mNotificationManager, never()).notify(anyString(), anyInt(),
                any(Notification.class));
    }

    @Test
    @SmallTest
    public void testNotificationClearedForEmergencyCall() {
        Call call = createCall(new DisconnectCause(DisconnectCause.LOCAL,
                DisconnectCause.REASON_EMERGENCY_CALL_PLACED));

        DisconnectedCallNotifier notifier = new DisconnectedCallNotifier(mContext, mCallsManager,
                mFeatureFlags);
        notifier.onCallStateChanged(call, CallState.DIALING, CallState.DISCONNECTED);
        verify(mNotificationManager).cancel(anyString(), anyInt());
    }

    /**
     * Verifies when there is no telephony available, that we'll still be able to determine a
     * country iso.
     */
    @Test
    @SmallTest
    public void testGetCountryIsoWithNoTelephony() {
        DisconnectedCallNotifier notifier = new DisconnectedCallNotifier(mContext, mCallsManager,
                mFeatureFlags);
        when(mComponentContextFixture.getTelephonyManager().getNetworkCountryIso())
                .thenThrow(new UnsupportedOperationException("Bee boop"));
        assertNotNull(notifier.getCurrentCountryIso(mContext));
    }

    private Call createCall(DisconnectCause cause) {
        Call call = mock(Call.class);
        when(call.getDisconnectCause()).thenReturn(cause);
        when(call.getTargetPhoneAccount()).thenReturn(PHONE_ACCOUNT_HANDLE);
        when(call.getHandle()).thenReturn(TEL_CALL_HANDLE);
        when(call.getAssociatedUser()).thenReturn(PHONE_ACCOUNT_HANDLE.getUserHandle());
        return call;
    }
}
