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
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.UserHandle;
import android.telecom.Log;
import android.telecom.PhoneAccount;
import android.telecom.TelecomManager;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;

import com.android.internal.annotations.GuardedBy;
import com.android.server.telecom.AppLabelProxy;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallState;
import com.android.server.telecom.CallsManagerListenerBase;
import com.android.server.telecom.LocalVoicemailController;
import com.android.server.telecom.TelecomBroadcastIntentProcessor;
import com.android.server.telecom.TelecomResourceId;
import com.android.server.telecom.UserUtil;
import com.android.server.telecom.flags.FeatureFlags;

import java.util.concurrent.Executor;

/**
 * Class responsible for posting local VM notifications where:
 * 1. There is a call in LOCAL_VOICEMAIL state and posting a
 * notification to inform the user a call is undergoing local voicemail processing.
 * 2. There is a ringing call which may get picked up by local voicemail and the user wants to
 * immediately sent it to local voicemail.
 */
public class LocalVoicemailNotification extends CallsManagerListenerBase
        implements LocalVoicemailController.LocalVoicemailListener {
    // URI scheme used for data related to the notification actions.
    public static final String CALL_ID_SCHEME = "callid";
    // The default voicemail notification ID.
    public static final int VOICEMAIL_NOTIFICATION_ID = 90211;
    // The send to voicemail voicemail notification ID.
    public static final int SEND_TO_VOICEMAIL_NOTIFICATION_ID = 90212;
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
    private boolean mIsSendToVoicemailNotificationShowing = false;
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
        mLocalVoicemailController.addListener(this);
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
            dequeueSendToVoicemailNotification();
            dequeueVoicemailNotification();
        }
    }

    @Override
    public void onCallStateChanged(Call call, int oldState, int newState) {
        Log.i(this, "onCallStateChanged: call=%s, newState=%d", call.getId(), newState);

        if (newState == CallState.LOCAL_VOICEMAIL) {
            trackVoicemailCall(call);
            dequeueSendToVoicemailNotification();
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
            Icon contactPhotoIcon = makePersonIcon();

            String packageName = mLocalVoicemailController.getActiveLocalVoicemailService();
            showLocalVoicemailProcessingNotification(call.getId(),
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
     * Dequeue the send to voicemail notification.
     */
    private void dequeueSendToVoicemailNotification() {
        mAsyncTaskExecutor.execute(() -> hideSendToVoicemailNotification());
    }

    private void enqueueSendToVoicemailNotification(Call call) {
        mAsyncTaskExecutor.execute(() -> {
            Icon contactPhotoIcon = makePersonIcon();
            String packageName = mLocalVoicemailController.getActiveLocalVoicemailService();
            showSendToVoicemailNotification(call.getId(),
                    call.getAssociatedUser(), call.getCallerDisplayName(),
                    call.getHandle(), call.getHandlePresentation(), contactPhotoIcon,
                    packageName);
        });
    }

    private void showSendToVoicemailNotification(String callId, UserHandle userHandle,
            String callerName, Uri callerAddress, int callerPresentation,
            Icon photoIcon, String appPackageName) {
        Log.i(this, "showSendToVoicemailNotification; callid=%s, hasPhoto=%b", callId,
                photoIcon != null);
        String appName = "";
        if (appPackageName != null) {
            appName = mAppLabelProxy.getAppLabel(appPackageName, userHandle).toString();
        }

        Intent sendToVoicemailIntent = new Intent(
                TelecomBroadcastIntentProcessor.ACTION_SEND_CALL_TO_LOCAL_VOICEMAIL);
        sendToVoicemailIntent.setPackage(mContext.getPackageName());
        sendToVoicemailIntent.putExtra(TelecomBroadcastIntentProcessor.EXTRA_DATA_URI,
                Uri.fromParts(CALL_ID_SCHEME, callId, null));
        PendingIntent sendToVoicemailPendingIntent = PendingIntent.getBroadcast(mContext, 0,
                sendToVoicemailIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        CharSequence title = TelecomResourceId.getString(mContext,
                "notification_local_voicemail_title");

        CharSequence contentText;
        if (TextUtils.isEmpty(callerName) ||
                callerPresentation != TelecomManager.PRESENTATION_ALLOWED) {
            contentText = mContext.getString(
                    TelecomResourceId.getIdentifier(mContext,
                            "notification_send_to_voicemail_unknown_details", "string"), appName);
        } else {
            contentText = mContext.getString(
                    TelecomResourceId.getIdentifier(mContext,
                            "notification_send_to_voicemail_details", "string"),
                    appName, callerName);
        }

        // Content of the public notification with the caller name removed.
        // Voicemail is being recorded by <xliff:g id="app_name">%1$s</xliff:g> for a call.
        CharSequence publicContentText = mContext.getString(
                        TelecomResourceId.getIdentifier(mContext,
                                "notification_send_to_voicemail_public_details", "string"),
                        appName);

        // Version of the notification that has private information removed so it is safe to show
        // on the lock screen when private information is hidden.
        Notification publicNotification = new Notification.Builder(mContext,
                NotificationChannelManager.CHANNEL_ID_LOCAL_VOICEMAIL)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_CALL)
                .setContentTitle(title)
                .setContentText(publicContentText)
                .setSmallIcon(
                        TelecomResourceId.getIdentifier(mContext, "ic_phone", "drawable"))
                .setColor(mContext.getResources().getColor(
                        TelecomResourceId.getIdentifier(mContext, "theme_color", "color")))
                .addAction(
                        new Notification.Action.Builder(
                                TelecomResourceId.getIdentifier(mContext, "voicemail_24px",
                                        "drawable"),
                                TelecomResourceId.getString(mContext, "send_to_voicemail"),
                                sendToVoicemailPendingIntent)
                                .build())
                .build();

        Notification notification = new Notification.Builder(mContext,
                NotificationChannelManager.CHANNEL_ID_LOCAL_VOICEMAIL)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicNotification)
                .setCategory(Notification.CATEGORY_CALL)
                .setContentTitle(title)
                .setContentText(contentText)
                .setSmallIcon(
                        TelecomResourceId.getIdentifier(mContext, "ic_phone", "drawable"))
                .setColor(mContext.getResources().getColor(
                        TelecomResourceId.getIdentifier(mContext, "theme_color", "color")))
                .addAction(
                        new Notification.Action.Builder(
                                TelecomResourceId.getIdentifier(mContext, "voicemail_24px",
                                        "drawable"),
                        TelecomResourceId.getString(mContext, "send_to_voicemail"),
                        sendToVoicemailPendingIntent)
                                .build())
                .build();

        synchronized(mNotificationLock) {
            mIsSendToVoicemailNotificationShowing = true;
            mNotificationUserHandle = userHandle;
            try {
                UserUtil.processNotification(mContext, userHandle, NOTIFICATION_TAG,
                        SEND_TO_VOICEMAIL_NOTIFICATION_ID, notification);
            } catch (Exception e) {
                Log.e(this, e, "Notification post failed.");
            }
        }
    }

    /**
     * Show the call voicemail notification.  This is intended to run outside the Telecom sync lock.
     */
    private void showLocalVoicemailProcessingNotification(String callId, UserHandle userHandle,
            String callerName, Uri callerAddress, int callerPresentation,
            Icon photoIcon, String appPackageName,
            long connectTimeMillis) {
        Log.i(this, "showLocalVoicemailProcessingNotification; callid=%s, hasPhoto=%b", callId,
                photoIcon != null);

        String appName = "";
        if (appPackageName != null) {
            appName = mAppLabelProxy.getAppLabel(appPackageName, userHandle).toString();
        }
        // Action to hangup
        Intent hangupIntent = new Intent(TelecomBroadcastIntentProcessor.ACTION_HANGUP_CALL);
        hangupIntent.setPackage(mContext.getPackageName());
        hangupIntent.putExtra(TelecomBroadcastIntentProcessor.EXTRA_DATA_URI,
                Uri.fromParts(CALL_ID_SCHEME, callId, null));
        PendingIntent hangupPendingIntent = PendingIntent.getBroadcast(mContext, 0, hangupIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Action to pick up the call
        // Apply a span to the string to colorize it using the "answer" color.
        Intent pickupIntent = new Intent(
                TelecomBroadcastIntentProcessor.ACTION_PICKUP_LOCAL_VOICEMAIL);
        pickupIntent.setPackage(mContext.getPackageName());
        pickupIntent.putExtra(TelecomBroadcastIntentProcessor.EXTRA_DATA_URI,
                Uri.fromParts(CALL_ID_SCHEME, callId, null));
        PendingIntent pickupPendingIntent = PendingIntent.getBroadcast(mContext, 0, pickupIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Person person = makePerson(callerName, callerAddress, callerPresentation, photoIcon);

        // Call Style notification requires a full screen intent, so we'll just link in a null
        // pending intent
        Intent nullIntent = new Intent();
        PendingIntent nullPendingIntent = PendingIntent.getBroadcast(mContext, 0, nullIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        CharSequence title = TelecomResourceId.getString(mContext,
                "notification_local_voicemail_title");
        CharSequence contentText;
        if (TextUtils.isEmpty(callerName) ||
                callerPresentation != TelecomManager.PRESENTATION_ALLOWED) {
            contentText = TelecomResourceId.getString(mContext,
                    "notification_local_voicemail_unknown_details", appName);
        } else {
            contentText = TelecomResourceId.getString(mContext,
                    "notification_local_voicemail_details", appName, callerName);
        }
        CharSequence publicContentText = TelecomResourceId.getString(mContext,
                "notification_local_voicemail_public_details", appName);

        // This little bit of ugliness is required to make sure that the "answer" button is colored
        // appropriately.
        Spannable answerSpannable = new SpannableString(
                TelecomResourceId.getString(mContext, "answer_incoming_call"));
        int resourceId = Resources.getSystem().getIdentifier(
                "call_notification_answer_color", "color", "android");
        int color = mContext.getResources().getColor(resourceId, null);
        answerSpannable.setSpan(new ForegroundColorSpan(color), 0,
                answerSpannable.length(),
                Spannable.SPAN_INCLUSIVE_EXCLUSIVE);

        Notification publicNotification = new Notification.Builder(mContext,
                NotificationChannelManager.CHANNEL_ID_LOCAL_VOICEMAIL)
                .setStyle(Notification.CallStyle.forOngoingCall(person, hangupPendingIntent))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(
                        TelecomResourceId.getIdentifier(mContext, "ic_call_answer", "drawable"),
                        answerSpannable, pickupPendingIntent).build())
                .setSmallIcon(TelecomResourceId.getIdentifier(mContext, "ic_phone", "drawable"))
                .setContentTitle(title)
                .setContentText(publicContentText)
                .setWhen(connectTimeMillis)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setFullScreenIntent(nullPendingIntent, true)
                .setColorized(true)
                .build();

        Notification.Builder builder = new Notification.Builder(mContext,
                NotificationChannelManager.CHANNEL_ID_LOCAL_VOICEMAIL)
                .setStyle(Notification.CallStyle.forOngoingCall(person, hangupPendingIntent))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicNotification)
                .addAction(new Notification.Action.Builder(
                        TelecomResourceId.getIdentifier(mContext, "ic_call_answer", "drawable"),
                        answerSpannable, pickupPendingIntent).build())
                .setSmallIcon(TelecomResourceId.getIdentifier(mContext, "ic_phone", "drawable"))
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

    private Icon makePersonIcon() {
        Icon contactPhotoIcon = null;
        try {
            // Re-using the same logic for default icon as CallStreamingNotification
            contactPhotoIcon = Icon.createWithResource(mContext,
                    TelecomResourceId.getIdentifier(mContext, "person_circle", "drawable"));
        } catch (Exception e) {
            Log.e(this, e, "enqueueVoicemailNotification: Couldn't build avatar icon");
        }
        if (contactPhotoIcon == null) {
            Log.e(this, new Exception(),
                    "enqueueVoicemailNotification: contactPhotoIcon is null");
        }
        return contactPhotoIcon;
    }

    private Person makePerson(String callerName, Uri callerAddress, int callerPresentation,
            Icon photoIcon) {
        // Notifications use a "person" entity to identify caller/callee.
        Person.Builder personBuilder = new Person.Builder();

        if (!TextUtils.isEmpty(callerName)
                && callerPresentation == TelecomManager.PRESENTATION_ALLOWED) {
            personBuilder.setName(callerName);
        } else {
            // Person builder REQUIRES a name, so use "unknown" presentation.
            personBuilder.setName(
                    TelecomResourceId.getString(mContext,
                            "phone_settings_unknown_txt"));
        }
        if (callerAddress != null && PhoneAccount.SCHEME_TEL.equals(callerAddress.getScheme())
                && callerPresentation == TelecomManager.PRESENTATION_ALLOWED) {
            personBuilder.setUri(callerAddress.toString());
        }
        if (photoIcon != null) {
            personBuilder.setIcon(photoIcon);
        }
        Person person = personBuilder.build();
        return person;
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

    private void hideSendToVoicemailNotification() {
        synchronized (mNotificationLock) {
            if (mIsSendToVoicemailNotificationShowing) {
                mIsSendToVoicemailNotificationShowing = false;
                UserUtil.processNotification(mContext, mNotificationUserHandle, NOTIFICATION_TAG,
                        SEND_TO_VOICEMAIL_NOTIFICATION_ID, null /* notification */);
            }
        }
    }

    /**
     * Called by the {@like LocalVoicemailController} when a call is scheduled for local voicemail.
     * @param call the call
     */
    @Override
    public void onLocalVoicemailScheduled(Call call) {
        enqueueSendToVoicemailNotification(call);
    }
}
