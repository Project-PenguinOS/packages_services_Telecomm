/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.telecom.callsequencing.voip;

import static android.app.ForegroundServiceDelegationOptions.DELEGATION_SERVICE_PHONE_CALL;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ForegroundServiceDelegationOptions;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.os.UserHandle;
import android.app.NotificationManager;
import android.service.notification.StatusBarNotification;
import android.telecom.ConnectionService;
import android.telecom.Log;
import android.telecom.PhoneAccountHandle;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallsManagerListenerBase;
import com.android.server.telecom.LogUtils;
import com.android.server.telecom.TelecomSystem;
import com.android.internal.telecom.flags.Flags;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class VoipCallMonitor extends CallsManagerListenerBase {
    public static final long NOTIFICATION_NOT_POSTED_IN_TIME_TIMEOUT = 5000L;
    public static final long NOTIFICATION_REMOVED_BUT_CALL_IS_STILL_ONGOING_TIMEOUT = 5000L;
    private static final long BAL_BIND_TIMEOUT_MS = 5000L;
    private static final String TAG = VoipCallMonitor.class.getSimpleName();
    private static final String DElIMITER = "#";
    // This list caches calls that are added to the VoipCallMonitor and need an accompanying
    // Call-Style Notification!
    private final ConcurrentLinkedQueue<Call> mNewCallsMissingCallStyleNotification;
    private final ConcurrentHashMap<PhoneAccountHandle, Set<Call>> mAccountHandleToCallMap;
    private final ConcurrentHashMap<PhoneAccountHandle, ServiceConnection> mServices;
    private final ConcurrentHashMap<PhoneAccountHandle,
            NotificationManager.CallNotificationEventListener> mListeners;
    private ActivityManagerInternal mActivityManagerInternal;
    private final NotificationManager mNotificationManager;
    private final Handler mHandlerForClass;
    private final Context mContext;
    private final TelecomSystem.SyncRoot mSyncRoot;

    // Tracks apps we are currently bound to for the specific purpose of launching
    // a background activity. This prevents double-binding
    private final Set<PhoneAccountHandle> mBoundAppsForActivityLaunch =
            ConcurrentHashMap.newKeySet();

    private final Call.InCallServiceToVoipAppListener mInCallServiceActionListenerImpl =
            new Call.InCallServiceToVoipAppListener() {
        /**
         * Triggered when an onAnswer signal is received from an InCallService.
         */
        @Override
        public void onAnswerRequested(Call call, int videoState,
                OutcomeReceiver<Object, Exception> completionCallback) {
            bindToAppsConnectionServiceForBackgroundActivityStart(call, completionCallback);
        }
    };

    // Simple wrapper to hold the connection reference mutably for the lambdas
    private static class AtomicServiceConnection {
        private ServiceConnection mConnection;
        synchronized void setConnection(ServiceConnection c) { mConnection = c; }
        synchronized ServiceConnection getConnection() { return mConnection; }
        synchronized void clear() { mConnection = null; }
    }

    public VoipCallMonitor(Context context, Handler handler, TelecomSystem.SyncRoot lock) {
        mSyncRoot = lock;
        mContext = context;
        mHandlerForClass = handler;
        mNewCallsMissingCallStyleNotification = new ConcurrentLinkedQueue<>();
        mServices = new ConcurrentHashMap<>();
        mAccountHandleToCallMap = new ConcurrentHashMap<>();
        mListeners = new ConcurrentHashMap<>();
        mNotificationManager = mContext.getSystemService(NotificationManager.class);
    }

    @Override
    public void onCallAdded(Call call) {
        PhoneAccountHandle handle = getTargetPhoneAccount(call);
        if (!isTransactional(call) || handle == null) {
            return;
        }
        int callingPid = getCallingPackagePid(call);
        int callingUid = getCallingPackageUid(call);
        if (Flags.voipBackgroundActivityLaunchFix()) {
            call.addInCallServiceToVoipAppListener(mInCallServiceActionListenerImpl);
        }
        Set<Call> ongoingCalls = mAccountHandleToCallMap
                .computeIfAbsent(handle, k -> new HashSet<>());
        if (ongoingCalls.isEmpty()) {
            maybeRegisterListener(handle);
        }
        ongoingCalls.add(call);
        maybeStartFGSDelegation(callingPid, callingUid, handle, call);
    }

    @Override
    public void onCallRemoved(Call call) {
        PhoneAccountHandle handle = getTargetPhoneAccount(call);
        if (!isTransactional(call) || handle == null) {
            return;
        }
        if (Flags.voipBackgroundActivityLaunchFix()) {
            call.removeInCallServiceToVoipAppListener(mInCallServiceActionListenerImpl);
        }
        Set<Call> ongoingCalls = mAccountHandleToCallMap
                .computeIfAbsent(handle, k -> new HashSet<>());
        ongoingCalls.remove(call);
        Log.d(TAG, "onCallRemoved: callList.size=[%d]", ongoingCalls.size());
        if (ongoingCalls.isEmpty()) {
            maybeUnregisterListener(handle);
            stopFGSDelegation(call, handle);
        } else {
            Log.addEvent(call, LogUtils.Events.MAINTAINING_FGS_DELEGATION);
        }
    }

    private void maybeStartFGSDelegation(int pid, int uid, PhoneAccountHandle handle, Call call) {
        Log.i(TAG, "maybeStartFGSDelegation for call=[%s]", call);
        ActivityManagerInternal ami = getActivityManagerInternal();
        if (ami != null) {
            if (mServices.containsKey(handle)) {
                Log.addEvent(call, LogUtils.Events.ALREADY_HAS_FGS_DELEGATION);
                startMonitoringNotification(call, handle);
                return;
            }
            ForegroundServiceDelegationOptions options = new ForegroundServiceDelegationOptions(pid,
                    uid, handle.getComponentName().getPackageName(), null /* clientAppThread */,
                    false /* isSticky */, String.valueOf(handle.hashCode()),
                    FOREGROUND_SERVICE_TYPE_PHONE_CALL |
                            FOREGROUND_SERVICE_TYPE_MICROPHONE |
                            FOREGROUND_SERVICE_TYPE_CAMERA |
                            FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE /* foregroundServiceTypes */,
                    DELEGATION_SERVICE_PHONE_CALL /* delegationService */);
            ServiceConnection fgsConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    Log.addEvent(call, LogUtils.Events.GAINED_FGS_DELEGATION);
                    mServices.put(handle, this);
                    startMonitoringNotification(call, handle);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    Log.addEvent(call, LogUtils.Events.LOST_FGS_DELEGATION);
                    mServices.remove(handle);
                }
            };
            try {
                if (ami.startForegroundServiceDelegate(options, fgsConnection)) {
                    Log.i(TAG, "maybeStartFGSDelegation: startForegroundServiceDelegate success");
                } else {
                    Log.addEvent(call, LogUtils.Events.GAIN_FGS_DELEGATION_FAILED);
                }
            } catch (Exception e) {
                Log.i(TAG, "startForegroundServiceDelegate failed due to: " + e);
            }
        }
    }

    @VisibleForTesting
    public void stopFGSDelegation(Call call, PhoneAccountHandle handle) {
        Log.i(TAG, "stopFGSDelegation of call=[%s]", call);
        if (handle == null) {
            return;
        }

        // In the event this class is waiting for any new calls to post a notification, cleanup
        List<Call> toRemove = new ArrayList<>();
        for (Call callAwaitingNotification : mNewCallsMissingCallStyleNotification) {
            if (handle.equals(callAwaitingNotification.getTargetPhoneAccount())) {
                Log.d(TAG, "stopFGSDelegation: removing call from notification tracking c=[%s]",
                        callAwaitingNotification);
                toRemove.add(callAwaitingNotification);
            }
        }
        mNewCallsMissingCallStyleNotification.removeAll(toRemove);

        ActivityManagerInternal ami = getActivityManagerInternal();
        if (ami != null) {
            ServiceConnection fgsConnection = mServices.remove(handle);
            if (fgsConnection != null) {
                Log.i(TAG, "stopFGSDelegation: requesting stopForegroundServiceDelegate");
                ami.stopForegroundServiceDelegate(fgsConnection);
            }
        }
        mAccountHandleToCallMap.remove(handle);
    }

    private void startMonitoringNotification(Call call, PhoneAccountHandle handle) {
        String packageName = getPackageName(call);
        String callId = getCallId(call);
        // Wait 5 seconds for a CallStyle notification to be posted for the call.
        // If the Call-Style Notification is not posted, FGS delegation needs to be revoked!
        Log.i(TAG, "startMonitoringNotification: starting timeout for call.id=[%s]", callId);
        mNewCallsMissingCallStyleNotification.add(call);
        // If no notification is posted, stop foreground service delegation!
        mHandlerForClass.postDelayed(() -> {
            if (mNewCallsMissingCallStyleNotification.contains(call)) {
                Log.i(TAG, "startMonitoringNotification: A Call-Style-Notification"
                        + " for voip-call=[%s] hasn't posted in time,"
                        + " stopping delegation for app=[%s].", call, packageName);
                stopFGSDelegation(call, handle);
            } else {
                Log.i(TAG, "startMonitoringNotification: found a call-style"
                        + " notification for call.id[%s] at timeout", callId);
            }
        }, NOTIFICATION_NOT_POSTED_IN_TIME_TIMEOUT);
    }

    /**
     * Establishes a temporary service binding to the VoIP application to allow it to
     * launch a background activity (e.g., the incoming call UI) after answering.
     *
     * <p>This method utilizes the {@link Context#BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS} flag.
     * To prevent resource leaks, this binding includes a safety timeout (default 5 seconds),
     * after which the service will be automatically unbound if the call state hasn't changed.
     *
     * <p>If the application is already bound for this purpose, the {@code outcomeReceiver}
     * is triggered immediately without re-binding.
     *
     * @param call            The call triggering the answer request.
     * @param outcomeReceiver The callback to notify when the bind is complete (or immediately if
     *                       already bound).
     * Returns the {@link VoipCallMonitor} instance on success.
     */
    private void bindToAppsConnectionServiceForBackgroundActivityStart(
            Call call,
            OutcomeReceiver<Object, Exception> outcomeReceiver) {
        PhoneAccountHandle phoneAccountHandle = call.getTargetPhoneAccount();
        if (phoneAccountHandle == null) {
            Log.w(TAG, "bindToAppsConnectionServiceForBackgroundActivityStart: null handle"
                    + " for call=[%s]", call.getId());
            return;
        }

        // Check if we are already bound or if the call is effectively active/ringing
        // If we are already bound to this app for a launch intent, do not rebind.
        if (mBoundAppsForActivityLaunch.contains(phoneAccountHandle)) {
            Log.w(TAG, "bindToAppsConnectionServiceForBackgroundActivityStart: already"
                            + " bound to app=[%s], skipping rebind.",
                    phoneAccountHandle);
            outcomeReceiver.onResult(VoipCallMonitor.this);
            return;
        }

        final int bindingFlags = Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE
                | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS;
        UserHandle userHandle = phoneAccountHandle.getUserHandle();

        // We need a reference to the connection wrapper to unbind safely inside the runnables
        final AtomicServiceConnection connectionWrapper = new AtomicServiceConnection();

        ServiceConnection serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                synchronized (mSyncRoot) {
                    Log.i(TAG, "bindToAppsConnectionServiceForBackgroundActivityStart: "
                            + "onServiceConnected: [%s]", name);
                    outcomeReceiver.onResult(VoipCallMonitor.this);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                synchronized (mSyncRoot) {
                    Log.i(TAG, "bindToAppsConnectionServiceForBackgroundActivityStart: "
                            + "onServiceDisconnected: [%s]", name);
                    outcomeReceiver.onResult(VoipCallMonitor.this);
                    mBoundAppsForActivityLaunch.remove(phoneAccountHandle);
                }
            }
        };

        connectionWrapper.setConnection(serviceConnection);

        boolean wasBound = mContext.bindServiceAsUser(
                createJetpackServiceIntent(call),
                serviceConnection,
                bindingFlags,
                userHandle);

        if (!wasBound) {
            synchronized (mSyncRoot) {
                Log.w(TAG, "bindToAppsConnectionServiceForBackgroundActivityStart: unable to"
                                + " bind to app=[%s]",
                        phoneAccountHandle);
                outcomeReceiver.onResult(VoipCallMonitor.this);
            }
            return; // Failed to bind, nothing to clean up
        }

        // Track that we are bound
        mBoundAppsForActivityLaunch.add(phoneAccountHandle);

        // Safety Timeout. After 5 seconds, unbind to prevent leaks.
        Runnable timeoutRunnable = () -> {
            Log.i(TAG, "bindToAppsConnectionServiceForBackgroundActivityStart: Timeout hit,"
                            + " unbinding for call=[%s]",
                    call.getId());
            unbindHelper(connectionWrapper, phoneAccountHandle);
        };
        mHandlerForClass.postDelayed(timeoutRunnable, BAL_BIND_TIMEOUT_MS);
    }

    /**
     * Helpers
     */

    private PhoneAccountHandle getTargetPhoneAccount(Call call) {
        synchronized (mSyncRoot) {
            if (call == null) {
                return null;
            } else {
                return call.getTargetPhoneAccount();
            }
        }
    }

    private int getCallingPackageUid(Call call) {
        synchronized (mSyncRoot) {
            if (call == null) {
                return -1;
            } else {
                return call.getCallingPackageIdentity().mCallingPackageUid;
            }
        }
    }

    private int getCallingPackagePid(Call call) {
        synchronized (mSyncRoot) {
            if (call == null) {
                return -1;
            } else {
                return call.getCallingPackageIdentity().mCallingPackagePid;
            }
        }
    }

    private String getCallId(Call call) {
        synchronized (mSyncRoot) {
            if (call == null) {
                return "";
            } else {
                return call.getId();
            }
        }
    }

    private boolean isCallDisconnected(Call call) {
        synchronized (mSyncRoot) {
            if (call == null) {
                return true;
            } else {
                return call.isDisconnected();
            }
        }
    }

    private boolean isTransactional(Call call) {
        synchronized (mSyncRoot) {
            if (call == null) {
                return false;
            } else {
                return call.isTransactionalCall();
            }
        }
    }

    private String getPackageName(Call call) {
        String pn = "";
        try {
            pn = getTargetPhoneAccount(call).getComponentName().getPackageName();
        } catch (Exception e) {
            // fall through
        }
        return pn;
    }

    private ActivityManagerInternal getActivityManagerInternal() {
        if (mActivityManagerInternal == null) {
            mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        }
        return mActivityManagerInternal;
    }

    @VisibleForTesting
    public void setActivityManagerInternal(ActivityManagerInternal ami) {
        mActivityManagerInternal = ami;
    }

    /**
     * Helper to simulate a notification being posted for testing. This finds the registered
     * listener for the notification's package/user and dispatches the onCallNotificationPosted
     * event.
     */
    @VisibleForTesting
    public void postNotification(StatusBarNotification statusBarNotification) {
        for (Map.Entry<PhoneAccountHandle, NotificationManager.CallNotificationEventListener>
                entry : mListeners.entrySet()) {
            if (entry.getKey().getUserHandle().equals(statusBarNotification.getUser())
                    && entry.getKey().getComponentName().getPackageName()
                    .equals(statusBarNotification.getPackageName())) {
                entry.getValue().onCallNotificationPosted(
                        statusBarNotification.getPackageName(), statusBarNotification.getUser());
            }
        }
    }

    /**
     * Helper to simulate a notification being removed for testing. This finds the registered
     * listener for the notification's package/user and dispatches the onCallNotificationRemoved
     * event.
     */
    @VisibleForTesting
    public void removeNotification(StatusBarNotification statusBarNotification) {
        for (Map.Entry<PhoneAccountHandle, NotificationManager.CallNotificationEventListener>
                entry : mListeners.entrySet()) {
            if (entry.getKey().getUserHandle().equals(statusBarNotification.getUser())
                    && entry.getKey().getComponentName().getPackageName()
                    .equals(statusBarNotification.getPackageName())) {
                entry.getValue().onCallNotificationRemoved(
                        statusBarNotification.getPackageName(), statusBarNotification.getUser());
            }
        }
    }

    /**
     * Registers a CallNotificationEventListener for the given PhoneAccountHandle if one is not
     * already registered. This listener tracks call notifications for the specific package and
     * user associated with the handle.
     */
    private void maybeRegisterListener(PhoneAccountHandle handle) {
        if (mListeners.containsKey(handle)) {
            return;
        }
        NotificationManager.CallNotificationEventListener listener =
                new NotificationManager.CallNotificationEventListener() {
            @Override
            public void onCallNotificationPosted(String packageName, UserHandle userHandle) {
                Log.i(TAG, "onCallNotificationPosted: package=[%s], user=[%s]",
                        packageName, userHandle);
                Call newCallNoLongerAwaitingNotification = null;
                for (Call call : mNewCallsMissingCallStyleNotification) {
                    if (isNotificationForCall(packageName, userHandle, call)) {
                        Log.i(TAG, "onCallNotificationPosted: found a pending call=[%s]", call);
                        newCallNoLongerAwaitingNotification = call;
                        break;
                    }
                }
                if (newCallNoLongerAwaitingNotification != null) {
                    // --> remove the newly added call from
                    // mNewCallsMissingCallStyleNotification so FGS is not revoked when the
                    // timeout is hit in VoipCallMonitor#startMonitoringNotification(...). The
                    // timeout ensures the voip app posts a call-style notification within
                    // 5 seconds!
                    mNewCallsMissingCallStyleNotification
                            .remove(newCallNoLongerAwaitingNotification);
                }
            }

            @Override
            public void onCallNotificationRemoved(String packageName, UserHandle userHandle) {
                Log.i(TAG, "onCallNotificationRemoved: package=[%s], user=[%s]",
                        packageName, userHandle);
                // TODO: b/383403913 - We need the Notification ID/Tag to know WHICH notification
                // was removed. Without it, we cannot safely determine if the removed notification
                // corresponds to an active call, so we cannot revoke FGS here safely.
            }
        };
        mListeners.put(handle, listener);
        mNotificationManager.registerCallNotificationEventListener(
                handle.getComponentName().getPackageName(),
                handle.getUserHandle(),
                new java.util.concurrent.Executor() {
                    @Override
                    public void execute(Runnable command) {
                        mHandlerForClass.post(command);
                    }
                },
                listener);
    }

    /**
     * Unregisters the CallNotificationEventListener associated with the given PhoneAccountHandle.
     */
    private void maybeUnregisterListener(PhoneAccountHandle handle) {
        NotificationManager.CallNotificationEventListener listener = mListeners.remove(handle);
        if (listener != null) {
            mNotificationManager.unregisterCallNotificationEventListener(listener);
        }
    }

    private boolean isNotificationForCall(String packageName, UserHandle userHandle, Call call) {
        PhoneAccountHandle callHandle = getTargetPhoneAccount(call);
        if (callHandle == null) {
            return false;
        }
        String callPackageName = VoipCallMonitor.this.getPackageName(call);
        return Objects.equals(userHandle, callHandle.getUserHandle()) &&
                Objects.equals(packageName, callPackageName);
    }

    public boolean hasForegroundServiceDelegation(PhoneAccountHandle handle) {
        boolean hasFgs = mServices.containsKey(handle);
        Log.i(TAG, "hasForegroundServiceDelegation: handle=[%s], hasFgs=[%b]", handle, hasFgs);
        return hasFgs;
    }

    @VisibleForTesting
    public ConcurrentHashMap<PhoneAccountHandle, Set<Call>> getAccountToCallsMapping() {
        return mAccountHandleToCallMap;
    }

    @VisibleForTesting
    public ConcurrentLinkedQueue<Call> getNewCallsMissingCallStyleNotificationQueue() {
        return mNewCallsMissingCallStyleNotification;
    }

    /**
     * Constructs an Intent targeting a ConnectionService within the
     * VoIP application.
     *
     * @param call The transactional call for which we are generating the intent.
     * @return An explicit Intent targeting the VoIP app's JetpackConnectionService.
     */
    private Intent createJetpackServiceIntent(Call call) {
        PhoneAccountHandle phoneAccountHandle = call.getTargetPhoneAccount();
        Intent intent = new Intent(ConnectionService.SERVICE_INTERFACE);
        intent.setPackage(phoneAccountHandle.getComponentName().getPackageName());
        return intent;
    }

    // Helper helper to ensure we unbind safely and catch common ServiceConnection exceptions
    private void unbindHelper(AtomicServiceConnection connectionWrapper,
            PhoneAccountHandle handle) {
        ServiceConnection conn = connectionWrapper.getConnection();
        if (conn != null && mBoundAppsForActivityLaunch.contains(handle)) {
            try {
                mContext.unbindService(conn);
            } catch (IllegalArgumentException e) {
                // This happens if the service is already unbound or wasn't registered.
                // Safe to ignore in this race-condition heavy context.
                Log.w(TAG, "unbindHelper: Service not registered for handle=[%s]: " + e, handle);
            } finally {
                mBoundAppsForActivityLaunch.remove(handle);
                connectionWrapper.clear();
            }
        }
    }
}
