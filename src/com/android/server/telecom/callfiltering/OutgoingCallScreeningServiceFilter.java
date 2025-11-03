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

package com.android.server.telecom.callfiltering;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telecom.CallScreeningService;
import android.telecom.Log;
import android.telecom.ParcelableCall;
import android.telecom.ParcelableCallResponse;
import android.telecom.TelecomManager;

import com.android.internal.telecom.ICallScreeningAdapter;
import com.android.internal.telecom.ICallScreeningService;
import com.android.server.telecom.CallScreeningServiceHelper; // For static bind method
import com.android.server.telecom.Call;
import com.android.server.telecom.LogUtils;
import com.android.server.telecom.Timeouts;
import com.android.server.telecom.flags.FeatureFlags;
import com.android.server.telecom.ParcelableCallUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A {@link CallFilter} that uses a {@link CallScreeningService} to screen outgoing calls.
 * This filter can operate in two modes:
 * 1.  **Blocking Screening**: Used for OEM-provided {@code CallScreeningService}s to potentially
 *     disallow an outgoing call.
 * 2.  **Informational Screening**: Used for the default {@code CallScreeningService} to provide
 *     additional information about the outgoing call, without blocking it.
 */
public class OutgoingCallScreeningServiceFilter extends CallFilter {
    private static final String TAG = OutgoingCallScreeningServiceFilter.class.getSimpleName();

    private final Call mCall;
    private final String mPackageName;
    private final Context mContext;
    private final Timeouts.Adapter mTimeoutsAdapter;
    private final FeatureFlags mFeatureFlags;
    private final ParcelableCallUtils.Converter mParcelableCallUtilsConverter;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final boolean mIsBlockingScreening;
    private final UserHandle mUserHandle;

    private OutgoingScreeningServiceConnection mConnection;
    private CompletableFuture<CallFilteringResult> mResultFuture;
    private Runnable mTimeoutRunnable;

    public OutgoingCallScreeningServiceFilter(
            Call call,
            String packageName,
            Context context,
            Timeouts.Adapter timeoutsAdapter,
            FeatureFlags featureFlags,
            boolean isBlockingScreening) {
        mCall = call;
        mPackageName = packageName;
        mContext = context;
        mTimeoutsAdapter = timeoutsAdapter;
        mFeatureFlags = featureFlags;
        mParcelableCallUtilsConverter = new ParcelableCallUtils.Converter();
        mIsBlockingScreening = isBlockingScreening;
        mUserHandle = call.getAssociatedUser();
    }

    @Override
    public CompletionStage<CallFilteringResult> startFilterLookup(
            CallFilteringResult priorStageResult) {
        mPriorStageResult = priorStageResult;
        if (mPackageName == null) {
            Log.w(this, "Package name is null");
            return CompletableFuture.completedFuture(priorStageResult);
        }
        // If the purpose is blocking and the call is already disallowed, skip.
        if (!mPriorStageResult.shouldAllowCall && mIsBlockingScreening) {
            return CompletableFuture.completedFuture(priorStageResult);
        }

        mResultFuture = new CompletableFuture<>();
        mConnection = new OutgoingScreeningServiceConnection(mResultFuture);

        Log.i(this, "Binding to "
                + (mIsBlockingScreening ? "Blocking" : "Default for Info")
                + " CSS: " + mPackageName);
        if (!CallScreeningServiceHelper.bindCallScreeningService(mContext,
                mUserHandle, mPackageName, mConnection, mFeatureFlags)) {
            Log.w(this, "CSS: Failed to bind for call " + mCall.getId());
            if (!mResultFuture.isDone()) {
                mResultFuture.complete(mPriorStageResult); // Allow on failure
            }
        } else {
            long timeoutMillis = mTimeoutsAdapter
                    .getCallScreeningTimeoutMillis(mContext, mFeatureFlags);
            mTimeoutRunnable = this::onTimeout;
            mHandler.postDelayed(mTimeoutRunnable, timeoutMillis);
            Log.addEvent(mCall, LogUtils.Events.BIND_SCREENING, mPackageName);
        }
        return mResultFuture;
    }

    private void onTimeout() {
        Log.i(this, "onTimeout");
        if (mResultFuture != null && !mResultFuture.isDone()) {
            String callId = (mCall != null) ? mCall.getId() : "null";
            Log.w(this, "CSS screening timed out for " + mPackageName
                    + " on call " + callId);
            mResultFuture.complete(mPriorStageResult); // Allow on timeout
        }
        unbindCallScreeningService();
    }

    private void unbindCallScreeningService() {
        if (mConnection != null) {
            if (mTimeoutRunnable != null) {
                mHandler.removeCallbacks(mTimeoutRunnable);
                mTimeoutRunnable = null;
            }
            try {
                mContext.unbindService(mConnection);
                Log.i(this, "Unbound from " + mPackageName);
            } catch (IllegalArgumentException e) {
                Log.i(TAG, "Service not registered: " + e);
            }
            mConnection = null;
        }
    }

    private class OutgoingScreeningAdapter extends ICallScreeningAdapter.Stub {
        private final CompletableFuture<CallFilteringResult> mResultFuture;

        OutgoingScreeningAdapter(CompletableFuture<CallFilteringResult> resultFuture) {
            mResultFuture = resultFuture;
        }

        @Override
        public void onScreeningResponse(String callId, ComponentName componentName,
                ParcelableCallResponse parcelableResponse) {
            if (mResultFuture.isDone() || mCall == null || !mCall.getId().equals(callId)) {
                unbindCallScreeningService();
                return;
            }

            CallFilteringResult.Builder builder = new CallFilteringResult.Builder();
            builder.setShouldAddToCallLog(mPriorStageResult.shouldAddToCallLog);
            builder.setShouldShowNotification(mPriorStageResult.shouldShowNotification);
            builder.setContactExists(mPriorStageResult.contactExists);
            builder.setCallScreeningComponentName(componentName.flattenToString());

            if (mIsBlockingScreening) { // OEM Blocking Mode
                boolean blocked =
                        parcelableResponse != null
                        && parcelableResponse.toCallResponse().getDisallowCall();
                Log.i(TAG, "OEM CSS response: allowed=" + !blocked + " for call " + callId);
                builder.setShouldAllowCall(!blocked);
                builder.setShouldReject(blocked);
                mResultFuture.complete(mPriorStageResult.combine(builder.build()));
            } else { // Default CSS Informational Mode
                Log.i(TAG, "Default CSS response received for outgoing call " + callId);
                if (!mResultFuture.isDone()) {
                    mResultFuture.complete(mPriorStageResult);
                }
            }
            unbindCallScreeningService();
        }
    }

    private class OutgoingScreeningServiceConnection implements ServiceConnection {
        private final CompletableFuture<CallFilteringResult> mResultFuture;

        OutgoingScreeningServiceConnection(
                CompletableFuture<CallFilteringResult> resultFuture) {
            mResultFuture = resultFuture;
        }

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            if (mResultFuture.isDone()) {
                unbindCallScreeningService();
                return;
            }
            ICallScreeningService callScreeningService =
                    ICallScreeningService.Stub.asInterface(service);
            try {
                ParcelableCall parcelableCall;
                OutgoingScreeningAdapter adapter = new OutgoingScreeningAdapter(mResultFuture);

                if (mIsBlockingScreening) {
                    // OEM Blocking: Potentially more information can be shared with a trusted OEM
                    // service.
                    // We assume the OEM service component is privileged.
                    boolean isSystemDialer =
                            componentName.equals(
                                    ComponentName.unflattenFromString(
                                            mContext.getSystemService(TelecomManager.class)
                                    .getSystemDialerPackage()));
                    boolean isReadPrivilegedPhoneStatePermission =
                            hasReadPrivilegedPhoneStatePermission();
                    Log.i(TAG, "SystemDialer: " + isSystemDialer
                            + " ReadPrivilegedPhoneState: " + isReadPrivilegedPhoneStatePermission);
                    // Assuming OEM service has necessary permissions to receive more details.
                    parcelableCall = mParcelableCallUtilsConverter.toParcelableCallForScreening(
                            mCall,
                            isSystemDialer,
                            isReadPrivilegedPhoneStatePermission
                    );
                    Log.i(TAG, "Calling screenOutgoingCall for " + mCall.getId());
                    callScreeningService.screenOutgoingCall(adapter, parcelableCall);
                } else {
                    parcelableCall = mParcelableCallUtilsConverter.toParcelableCallForScreening(
                            mCall,
                            false /* areRestrictedExtrasIncluded */,
                            false /* includePhoneAccountHandle */);
                    Log.i(TAG, "Calling screenCall for " + mCall.getId());
                    callScreeningService.screenCall(adapter, parcelableCall);
                }
            } catch (RemoteException e) {
                Log.e(this, e, "Failed to call screening service");
                if (!mResultFuture.isDone()) { mResultFuture.complete(mPriorStageResult); }
                unbindCallScreeningService();
            }
        }

        private boolean hasReadPrivilegedPhoneStatePermission() {
            PackageManager packageManager = mContext.getPackageManager();
            return packageManager != null && packageManager
                    .checkPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE, mPackageName)
                    == PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.w(TAG, "CSS onServiceDisconnected: " + componentName);
            if (!mResultFuture.isDone()) { mResultFuture.complete(mPriorStageResult); }
            unbindCallScreeningService();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.w(TAG, "CSS onBindingDied: " + name);
            if (!mResultFuture.isDone()) { mResultFuture.complete(mPriorStageResult); }
            unbindCallScreeningService();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.w(TAG, "CSS onNullBinding: " + name);
            if (!mResultFuture.isDone()) { mResultFuture.complete(mPriorStageResult); }
            unbindCallScreeningService();
        }
    }
}
