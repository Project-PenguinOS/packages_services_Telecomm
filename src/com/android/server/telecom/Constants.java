/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.telecom;

/**
 * App-wide constants for the phone app.
 *
 * Any constants that need to be shared between two or more classes within
 * the com.android.phone package should be defined here.  (Constants that
 * are private to only one class can go in that class's .java file.)
 */
public class Constants {
    //
    // URI schemes
    //

    public static final String SCHEME_SMSTO = "smsto";
    // TODO(b/469327345): This is a hidden constant in Context.
    // Redefined locally to remove hidden API dependency.
    // Original value from android.content.Context: 0x00080000
    public static final int BIND_SCHEDULE_LIKE_TOP_APP = 0x00080000;

    public static final int STATUS_NOT_BLOCKED = 0;
    public static final int STATUS_BLOCKED_IN_LIST = 1;
    public static final int STATUS_BLOCKED_RESTRICTED = 2;
    public static final int STATUS_BLOCKED_UNKNOWN_NUMBER = 3;
    public static final int STATUS_BLOCKED_PAYPHONE = 4;
    public static final int STATUS_BLOCKED_NOT_IN_CONTACTS = 5;
    public static final int STATUS_BLOCKED_UNAVAILABLE = 6;

    public static final String EXTRA_CALL_PRESENTATION = "extra_call_presentation";
    public static final String EXTRA_CONTACT_EXIST = "extra_contact_exist";
}
