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
 * limitations under the License.
 */

package com.android.server.telecom;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.BlockedNumberContract;
import android.telecom.PhoneAccount;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

/**
 * Local copy of the hidden {@code BlockedNumberContract.SystemContract} class
 * and its internal dependencies.
 * <p>
 * This class provides access to the system-level functionality of the
 * {@link BlockedNumberContract}, such as managing the blocking behavior when the
 * user contacts emergency services. See
 * {@link #notifyEmergencyContact(Context)} for details.
 * <p>
 * This helper was created to decouple the Telecom module from hidden framework
 * APIs for Mainline modularization. The original provider methods that this
 * class calls are protected by permissions like
 * {@link android.Manifest.permission#READ_BLOCKED_NUMBERS}, which the Telecom
 * service holds.
 *
 * TODO(b/445997472): Formalize a public/system API for these features and remove
 * this helper class.
 */
public final class SystemBlockedNumberContract {

    private SystemBlockedNumberContract() {
    }

    private static final String LOG_TAG = "SystemBlockedNumber";
    private static final boolean VERBOSE = android.util.Log.isLoggable(LOG_TAG,
            android.util.Log.VERBOSE);
    private static final int NUM_DIALABLE_DIGITS_TO_LOG = "user".equals(Build.TYPE) ? 0 : 2;

    public static final String AUTHORITY = "com.android.blockednumber";
    public static final Uri AUTHORITY_URI = Uri.parse("content://" + AUTHORITY);

    // --- Copied Constants from BlockedNumberContract.SystemContract ---
    public static final String ACTION_BLOCK_SUPPRESSION_STATE_CHANGED =
            "android.provider.action.BLOCK_SUPPRESSION_STATE_CHANGED";
    public static final String METHOD_NOTIFY_EMERGENCY_CONTACT = "notify_emergency_contact";
    public static final String METHOD_END_BLOCK_SUPPRESSION = "end_block_suppression";
    public static final String METHOD_SHOULD_SYSTEM_BLOCK_NUMBER = "should_system_block_number";
    public static final String METHOD_SHOULD_SHOW_EMERGENCY_CALL_NOTIFICATION =
            "should_show_emergency_call_notification";
    public static final String RES_BLOCK_STATUS = "block_status";
    public static final String RES_SHOW_EMERGENCY_CALL_NOTIFICATION =
            "show_emergency_call_notification";

    /**
     * Notifies the provider that emergency services were contacted by the user.
     */
    public static void notifyEmergencyContact(Context context) {
        try {
            Log.i(LOG_TAG, "notifyEmergencyContact; caller=" + context.getOpPackageName());
            context.getContentResolver().call(
                    BlockedNumberContract.AUTHORITY_URI, METHOD_NOTIFY_EMERGENCY_CONTACT,
                    null, null);
        } catch (NullPointerException | IllegalArgumentException ex) {
            // The content resolver can throw an NPE or IAE; we don't want to crash Telecom if
            // either of these happen.
            Log.w(null, "notifyEmergencyContact: provider not ready.");
        }
    }

    /**
     * Notifies the provider to disable suppressing blocking.
     */
    public static void endBlockSuppression(Context context) {
        String caller = context.getOpPackageName();
        Log.i(LOG_TAG, "endBlockSuppression: caller=" + caller);
        context.getContentResolver().call(
                BlockedNumberContract.AUTHORITY_URI, METHOD_END_BLOCK_SUPPRESSION, null, null);
    }

    /**
     * Returns a code indicating if {@code phoneNumber} should be blocked.
     *
     * @return result code indicating if the number should be blocked, and if so why.
     *         Valid values are: {@link Constants#STATUS_NOT_BLOCKED},
     *         {@link Constants#STATUS_BLOCKED_IN_LIST},
     *         {@link Constants#STATUS_BLOCKED_NOT_IN_CONTACTS},
     *         {@link Constants#STATUS_BLOCKED_PAYPHONE},
     *         {@link Constants#STATUS_BLOCKED_RESTRICTED},
     *         {@link Constants#STATUS_BLOCKED_UNKNOWN_NUMBER},
     *         {@link Constants#STATUS_BLOCKED_UNAVAILABLE}.
     */
    public static int shouldSystemBlockNumber(Context context, String phoneNumber,
            Bundle extras) {
        try {
            String caller = context.getOpPackageName();
            final Bundle res = context.getContentResolver().call(
                    AUTHORITY_URI, METHOD_SHOULD_SYSTEM_BLOCK_NUMBER, phoneNumber, extras);
            int blockResult = res != null ? res.getInt(RES_BLOCK_STATUS,
                    Constants.STATUS_NOT_BLOCKED) : Constants.STATUS_NOT_BLOCKED;
            Log.d(LOG_TAG,
                    String.format("shouldSystemBlockNumber: number=%s, caller=%s, result=%s",
                            piiHandle(phoneNumber), caller, blockStatusToString(blockResult)));
            return blockResult;
        } catch (NullPointerException | IllegalArgumentException ex) {
            // The content resolver can throw an NPE or IAE; we don't want to crash Telecom if
            // either of these happen.
            Log.w(null, "shouldSystemBlockNumber: provider not ready.");
            return Constants.STATUS_NOT_BLOCKED;
        }
    }

     /**
     * Check whether should show the emergency call notification.
     *
     * @param context the context of the caller.
     * @return {@code true} if should show emergency call notification. {@code false} otherwise.
     */
    public static boolean shouldShowEmergencyCallNotification(Context context) {
        try {
            final Bundle res = context.getContentResolver().call(
                    AUTHORITY_URI, METHOD_SHOULD_SHOW_EMERGENCY_CALL_NOTIFICATION, null, null);
            return res != null && res.getBoolean(RES_SHOW_EMERGENCY_CALL_NOTIFICATION, false);
        } catch (NullPointerException | IllegalArgumentException ex) {
            // The content resolver can throw an NPE or IAE; we don't want to crash Telecom if
            // either of these happen.
            Log.w(null, "shouldShowEmergencyCallNotification: provider not ready.");
            return false;
        }
    }

    /**
     * Converts a block status constant to a string equivalent for logging.
     */
    public static String blockStatusToString(int blockStatus) {
        return switch (blockStatus) {
            case Constants.STATUS_NOT_BLOCKED -> "not blocked";
            case Constants.STATUS_BLOCKED_IN_LIST -> "blocked - in list";
            case Constants.STATUS_BLOCKED_RESTRICTED -> "blocked - restricted";
            case Constants.STATUS_BLOCKED_UNKNOWN_NUMBER -> "blocked - unknown";
            case Constants.STATUS_BLOCKED_PAYPHONE -> "blocked - payphone";
            case Constants.STATUS_BLOCKED_NOT_IN_CONTACTS -> "blocked - not in contacts";
            case Constants.STATUS_BLOCKED_UNAVAILABLE -> "blocked - unavailable";
            default -> "unknown";
        };
    }

    private static String piiHandle(Object pii) {
        if (pii == null || VERBOSE) {
            return String.valueOf(pii);
        }

        StringBuilder sb = new StringBuilder();
        if (pii instanceof Uri) {
            Uri uri = (Uri) pii;
            String scheme = uri.getScheme();

            if (!TextUtils.isEmpty(scheme)) {
                sb.append(scheme).append(":");
            }

            String textToObfuscate = uri.getSchemeSpecificPart();
            if (PhoneAccount.SCHEME_TEL.equals(scheme)) {
                obfuscatePhoneNumber(sb, textToObfuscate);
            } else if (PhoneAccount.SCHEME_SIP.equals(scheme)) {
                for (int i = 0; i < textToObfuscate.length(); i++) {
                    char c = textToObfuscate.charAt(i);
                    if (c != '@' && c != '.') {
                        c = '*';
                    }
                    sb.append(c);
                }
            } else {
                sb.append(pii(pii));
            }
        } else if (pii instanceof String) {
            String number = (String) pii;
            obfuscatePhoneNumber(sb, number);
        }

        return sb.toString();
    }

    private static void obfuscatePhoneNumber(StringBuilder sb, String phoneNumber) {
        int numDigitsToObfuscate = getDialableCount(phoneNumber)
                - NUM_DIALABLE_DIGITS_TO_LOG;
        for (int i = 0; i < phoneNumber.length(); i++) {
            char c = phoneNumber.charAt(i);
            boolean isDialable = PhoneNumberUtils.isDialable(c);
            if (isDialable) {
                numDigitsToObfuscate--;
            }
            sb.append(isDialable && numDigitsToObfuscate >= 0 ? "*" : c);
        }
    }

    private static String pii(Object pii) {
        if (pii == null || VERBOSE) {
            return String.valueOf(pii);
        }
        return "***";
    }

    private static int getDialableCount(String toCount) {
        int numDialable = 0;
        for (int i = 0; i < toCount.length(); i++) {
            char c = toCount.charAt(i);
            if (PhoneNumberUtils.isDialable(c)) {
                numDialable++;
            }
        }
        return numDialable;
    }
}
