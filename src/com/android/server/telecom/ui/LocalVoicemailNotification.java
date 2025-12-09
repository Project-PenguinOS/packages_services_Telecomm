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

package com.android.server.telecom.ui;

import android.annotation.NonNull;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.UserHandle;
import android.telecom.Log;
import android.telecom.PhoneAccount;
import android.telecom.TelecomManager;
import android.text.TextUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.server.telecom.AppLabelProxy;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallState;
import com.android.server.telecom.CallsManagerListenerBase;
import com.android.server.telecom.LocalVoicemailController;
import com.android.server.telecom.R;
import com.android.server.telecom.TelecomBroadcastIntentProcessor;
import com.android.server.telecom.UserUtil;
import com.android.server.telecom.components.TelecomBroadcastReceiver;
import com.android.server.telecom.flags.FeatureFlags;

import java.util.concurrent.Executor;

/**
 * Class responsible for posting local VM notifications where:
 * 1. There is a call in LOCAL_VOICEMAIL state and posting a
 * notification to inform the user a call is undergoing local voicemail processing.
 */
public class LocalVoicemailNotification extends CallsManagerListenerBase {
    // URI scheme used for data related to the notification actions.
    public static final String CALL_ID_SCHEME = "callid";
    // The default voicemail notification ID.
    public static final int VOICEMAIL_NOTIFICATION_ID = 90211;
    // Tag for voicemail notification.
    private static final String NOTIFICATION_TAG =
            LocalVoicemailNotification.class.getSimpleName();

    private final Context mContext;
    // Used to get the app name for the notification.
    private final AppLabelProxy mAppLabelProxy;
    // An executor that can be used to fire off async tasks that do not block Telecom in any manner.
    private final Executor mAsyncTaskExecutor;
    private final FeatureFlags mFeatureFlags;
    private final LocalVoicemailController mLocalVoicemailController;

    // The call in local voicemail.
    private Call mVoicemailCall;
    // Lock for notification post/remove -- these happen outside the Telecom sync lock.
    private final Object mNotificationLock = new Object();

    // Whether the notification is showing.
    @GuardedBy("mNotificationLock")
    private boolean mIsNotificationShowing = false;
    @GuardedBy("mNotificationLock")
    private UserHandle mNotificationUserHandle;

    public LocalVoicemailNotification(@NonNull Context context,
            @NonNull AppLabelProxy appLabelProxy,
            @NonNull Executor asyncTaskExecutor,
            @NonNull FeatureFlags featureFlags,
            @NonNull LocalVoicemailController localVoicemailController) {
        mContext = context;
        mAppLabelProxy = appLabelProxy;
        mAsyncTaskExecutor = asyncTaskExecutor;
        mFeatureFlags = featureFlags;
        mLocalVoicemailController = localVoicemailController;
    }

    @Override
    public void onCallAdded(Call call) {
        if (call.getState() == CallState.LOCAL_VOICEMAIL) {
            trackVoicemailCall(call);
            enqueueVoicemailNotification(call);
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        if (call == mVoicemailCall) {
            trackVoicemailCall(null);
            dequeueVoicemailNotification();
        }
    }

    @Override
    public void onCallStateChanged(Call call, int oldState, int newState) {
        Log.i(this, "onCallStateChanged: call=%s, newState=%d", call.getId(), newState);

        if (newState == CallState.LOCAL_VOICEMAIL) {
            trackVoicemailCall(call);
            enqueueVoicemailNotification(call);
        } else if (oldState == CallState.LOCAL_VOICEMAIL && call == mVoicemailCall) {
            trackVoicemailCall(null);
            dequeueVoicemailNotification();
        }
    }

    /**
     * Change the voicemail call we are tracking.
     * @param call the call.
     */
    private void trackVoicemailCall(Call call) {
        mVoicemailCall = call;
    }

    /**
     * Enqueue an async task to post/repost the voicemail notification.
     * Note: This happens INSIDE the telecom lock.
     * @param call the call to post notification for.
     */
    private void enqueueVoicemailNotification(Call call) {
        mAsyncTaskExecutor.execute(() -> {
            Icon contactPhotoIcon = null;
            try {
                // Re-using the same logic for default icon as CallStreamingNotification
                contactPhotoIcon = Icon.createWithResource(mContext, R.drawable.person_circle);
            } catch (Exception e) {
                Log.e(this, e, "enqueueVoicemailNotification: Couldn't build avatar icon");
            }
            if (contactPhotoIcon == null) {
                Log.e(this, new Exception(),
                        "enqueueVoicemailNotification: contactPhotoIcon is null");
            }

            String packageName = mLocalVoicemailController.getActiveLocalVoicemailService();
            showVoicemailNotification(call.getId(),
                    call.getAssociatedUser(), call.getCallerDisplayName(),
                    call.getHandle(), call.getHandlePresentation(), contactPhotoIcon,
                    packageName,
                    call.getConnectTimeMillis());
        });
    }

    /**
     * Dequeues the call voicemail notification.
     */
    private void dequeueVoicemailNotification() {
        mAsyncTaskExecutor.execute(() -> hideVoicemailNotification());
    }

    /**
     * Show the call voicemail notification.  This is intended to run outside the Telecom sync lock.
     */
    private void showVoicemailNotification(String callId, UserHandle userHandle,
            String callerName, Uri callerAddress, int callerPresentation,
            Icon photoIcon, String appPackageName,
            long connectTimeMillis) {
        Log.i(this, "showVoicemailNotification; callid=%s, hasPhoto=%b", callId, photoIcon != null);

        String appName = "";
        if (appPackageName != null) {
            appName = mAppLabelProxy.getAppLabel(appPackageName, userHandle).toString();
        }
        // Action to hangup
        Intent hangupIntent = new Intent(TelecomBroadcastIntentProcessor.ACTION_HANGUP_CALL,
                Uri.fromParts(CALL_ID_SCHEME, callId, null),
                mContext, TelecomBroadcastReceiver.class);
        PendingIntent hangupPendingIntent = PendingIntent.getBroadcast(mContext, 0, hangupIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Notifications use a "person" entity to identify caller/callee.
        Person.Builder personBuilder = new Person.Builder();

        if (!TextUtils.isEmpty(callerName)
                && callerPresentation == TelecomManager.PRESENTATION_ALLOWED) {
            personBuilder.setName(callerName);
        } else {
            // Person builder REQUIRES a name, so use "unknown" presentation.
            personBuilder.setName(mContext.getString(R.string.phone_settings_unknown_txt));
        }
        if (callerAddress != null && PhoneAccount.SCHEME_TEL.equals(callerAddress.getScheme())
                && callerPresentation == TelecomManager.PRESENTATION_ALLOWED) {
            personBuilder.setUri(callerAddress.toString());
        }
        if (photoIcon != null) {
            personBuilder.setIcon(photoIcon);
        }
        Person person = personBuilder.build();

        // Call Style notification requires a full screen intent, so we'll just link in a null
        // pending intent
        Intent nullIntent = new Intent();
        PendingIntent nullPendingIntent = PendingIntent.getBroadcast(mContext, 0, nullIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        CharSequence title = mContext.getString(R.string.notification_local_voicemail_title);
        CharSequence contentText;
        if (TextUtils.isEmpty(callerName) ||
                callerPresentation != TelecomManager.PRESENTATION_ALLOWED) {
            contentText = mContext.getString(R.string.notification_local_voicemail_unknown_details,
                    appName);
        } else {
            contentText = mContext.getString(R.string.notification_local_voicemail_details,
                    appName, callerName);
        }

        Notification.Builder builder = new Notification.Builder(mContext,
                NotificationChannelManager.CHANNEL_ID_AUDIO_PROCESSING)
                .setStyle(Notification.CallStyle.forOngoingCall(person, hangupPendingIntent))
                .setSmallIcon(R.drawable.ic_phone)
                .setContentTitle(title)
                .setContentText(contentText)
                .setWhen(connectTimeMillis)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setFullScreenIntent(nullPendingIntent, true)
                .setColorized(true);
        Notification notification = builder.build();

        synchronized(mNotificationLock) {
            mIsNotificationShowing = true;
            mNotificationUserHandle = userHandle;
            try {
                UserUtil.processNotification(mContext, userHandle, NOTIFICATION_TAG,
                        VOICEMAIL_NOTIFICATION_ID, notification);
            } catch (Exception e) {
                Log.e(this, e, "Notification post failed.");
            }
        }
    }

    /**
     * Removes the posted voicemail notification.
     */
    private void hideVoicemailNotification() {
        Log.i(this, "hideVoicemailNotification");
        synchronized(mNotificationLock) {
            if (mIsNotificationShowing) {
                mIsNotificationShowing = false;
                UserUtil.processNotification(mContext, mNotificationUserHandle, NOTIFICATION_TAG,
                        VOICEMAIL_NOTIFICATION_ID, null /* notification */);
            }
        }
    }
}
