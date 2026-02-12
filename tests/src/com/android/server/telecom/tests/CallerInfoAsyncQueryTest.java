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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.database.Cursor;
import android.net.Uri;
import android.os.Looper;
import android.os.UserHandle;
import android.telephony.TelephonyManager;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.util.CallerInfo;
import com.android.server.telecom.util.CallerInfoAsyncQuery;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

@RunWith(JUnit4.class)
public class CallerInfoAsyncQueryTest extends TelecomTestCase {

    private static final int TOKEN = 1;
    private static final String NUMBER = "1234567890";
    private static final String EMERGENCY_NUMBER = "911";
    private static final Uri CONTACT_REF = Uri.parse("content://contacts/lookup/1");

    @Mock
    private CallerInfoAsyncQuery.OnQueryCompleteListener mListener;
    @Mock
    private TelephonyManager mTelephonyManager;

    @Override
    @Before
    public void setUp() throws Exception {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        super.setUp();
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        when(mTelephonyManager.isEmergencyNumber(anyString())).thenReturn(false);
    }

    @SmallTest
    @Test
    public void testStartQueryWithUri() {
        CallerInfoAsyncQuery.startQuery(TOKEN, mContext, CONTACT_REF, mListener, "cookie");
    }

    @SmallTest
    @Test
    public void testStartQueryWithNumber() {
        CallerInfoAsyncQuery.startQuery(TOKEN, mContext, NUMBER, mListener, "cookie");
    }

    @SmallTest
    @Test
    public void testStartQueryWithEmergencyNumber() {
        when(mTelephonyManager.isEmergencyNumber(EMERGENCY_NUMBER)).thenReturn(true);
        CallerInfoAsyncQuery.startQuery(TOKEN, mContext, EMERGENCY_NUMBER, mListener, "cookie");
    }

    @SmallTest
    @Test
    public void testAddQueryListener() {
        CallerInfoAsyncQuery query = CallerInfoAsyncQuery.startQuery(TOKEN, mContext, CONTACT_REF,
                mListener, "cookie");
        query.addQueryListener(TOKEN, mListener, "cookie2");
    }

    @SmallTest
    @Test(expected = CallerInfoAsyncQuery.QueryPoolException.class)
    public void testStartQueryWithNullContext() {
        CallerInfoAsyncQuery.startQuery(TOKEN, null, CONTACT_REF, mListener, "cookie");
    }

    @SmallTest
    @Test(expected = CallerInfoAsyncQuery.QueryPoolException.class)
    public void testStartQueryWithNullUri() {
        CallerInfoAsyncQuery.startQuery(TOKEN, mContext, (Uri) null, mListener, "cookie");
    }

    @SmallTest
    @Test
    public void testOnQueryCompleteWithNullCookie() throws Exception {
        CallerInfoAsyncQuery query = CallerInfoAsyncQuery.startQuery(TOKEN, mContext, CONTACT_REF,
                mListener, "cookie");
        Object handler = getHandler(query);
        invokeOnQueryComplete(handler, TOKEN, null, null);
    }

    @SmallTest
    @Test
    public void testOnQueryCompleteEndOfQueue() throws Exception {
        CallerInfoAsyncQuery query = CallerInfoAsyncQuery.startQuery(TOKEN, mContext, CONTACT_REF,
                mListener, "cookie");
        Object handler = getHandler(query);
        Object cookie = createCookieWrapper();
        setCookieEvent(cookie, 3); // EVENT_END_OF_QUEUE

        invokeOnQueryComplete(handler, TOKEN, cookie, null);
    }

    @SmallTest
    @Test
    public void testOnQueryCompleteEmergency() throws Exception {
        when(mTelephonyManager.isEmergencyNumber(EMERGENCY_NUMBER)).thenReturn(true);
        CallerInfoAsyncQuery query = CallerInfoAsyncQuery.startQuery(TOKEN, mContext,
                EMERGENCY_NUMBER, mListener, "cookie");
        Object handler = getHandler(query);
        Object cookie = createCookieWrapper();
        setCookieEvent(cookie, 4); // EVENT_EMERGENCY_NUMBER
        setCookieListener(cookie, mListener);
        setCookieCookie(cookie, "cookie");

        invokeOnQueryComplete(handler, TOKEN, cookie, null);

        // Trigger end of queue to run callbacks
        Object endCookie = createCookieWrapper();
        setCookieEvent(endCookie, 3); // EVENT_END_OF_QUEUE
        invokeOnQueryComplete(handler, TOKEN, endCookie, null);

        verify(mListener).onQueryComplete(eq(TOKEN), eq("cookie"), any(CallerInfo.class));
    }

    private Object getHandler(CallerInfoAsyncQuery query) throws Exception {
        Field handlerField = CallerInfoAsyncQuery.class.getDeclaredField("mHandler");
        handlerField.setAccessible(true);
        return handlerField.get(query);
    }

    private void invokeOnQueryComplete(Object handler, int token, Object cookie, Cursor cursor)
            throws Exception {
        Method onQueryCompleteMethod = handler.getClass().getSuperclass().getDeclaredMethod(
                "onQueryComplete", int.class, Object.class, Cursor.class);
        onQueryCompleteMethod.setAccessible(true);
        onQueryCompleteMethod.invoke(handler, token, cookie, cursor);
    }

    private Object createCookieWrapper() throws Exception {
        String className = "com.android.server.telecom.util.CallerInfoAsyncQuery$CookieWrapper";
        Class<?> cookieWrapperClass = Class.forName(className);
        Constructor<?> constructor = cookieWrapperClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private void setCookieEvent(Object cookie, int event) throws Exception {
        Field eventField = cookie.getClass().getDeclaredField("event");
        eventField.setAccessible(true);
        eventField.set(cookie, event);
    }

    private void setCookieListener(Object cookie, CallerInfoAsyncQuery.OnQueryCompleteListener l)
            throws Exception {
        Field listenerField = cookie.getClass().getDeclaredField("listener");
        listenerField.setAccessible(true);
        listenerField.set(cookie, l);
    }

    private void setCookieCookie(Object cookie, Object c) throws Exception {
        Field cookieField = cookie.getClass().getDeclaredField("cookie");
        cookieField.setAccessible(true);
        cookieField.set(cookie, c);
    }
}
