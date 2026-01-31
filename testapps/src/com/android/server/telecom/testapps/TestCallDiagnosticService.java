/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.telecom.testapps;

import android.telecom.BluetoothCallQualityReport;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.CallDiagnosticService;
import android.telecom.CallDiagnostics;
import android.telephony.CallQuality;
import android.telephony.ims.ImsReasonInfo;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class TestCallDiagnosticService extends CallDiagnosticService {
    private static final String TAG = TestCallDiagnosticService.class.getSimpleName();

    public static final class TestCallDiagnostics extends CallDiagnostics {
        public Call.Details details;

        TestCallDiagnostics(Call.Details details) {
            this.details = details;
        }

        @Override
        public void onCallDetailsChanged(@NonNull Call.Details details) {
            Log.i(TAG, String.format("onCallDetailsChanged; %s", details));
        }

        @Override
        public void onReceiveDeviceToDeviceMessage(int message, int value) {
            Log.i(TAG, String.format("onReceiveDeviceToDeviceMessage; %d/%d", message, value));
        }

        @Nullable
        @Override
        public CharSequence onCallDisconnected(int disconnectCause, int preciseDisconnectCause) {
            Log.i(TAG, "onCallDisconnected");
            return "GSM/CDMA call dropped because " + disconnectCause;
        }

        @Nullable
        @Override
        public CharSequence onCallDisconnected(@NonNull ImsReasonInfo disconnectReason) {
            Log.i(TAG, "onCallDisconnected");
            return "ImsCall dropped because something happened " + disconnectReason.mExtraMessage;
        }

        @Override
        public void onCallQualityReceived(@NonNull CallQuality callQuality) {
            Log.i(TAG, String.format("onCallQualityReceived %s", callQuality));
        }
    }

    @NonNull
    @Override
    public CallDiagnostics onInitializeCallDiagnostics(@NonNull Call.Details call) {
        Log.i(TAG, String.format("onInitiatlizeDiagnosticCall %s", call));
        return new TestCallDiagnostics(call);
    }

    @Override
    public void onRemoveCallDiagnostics(@NonNull CallDiagnostics call) {
        Log.i(TAG, String.format("onRemoveDiagnosticCall %s", call));
    }

    @Override
    public void onCallAudioStateChanged(@NonNull CallAudioState audioState) {
        Log.i(TAG, String.format("onCallAudioStateChanged %s", audioState));
    }

    @Override
    public void onBluetoothCallQualityReportReceived(
            @NonNull BluetoothCallQualityReport qualityReport) {
        Log.i(TAG, String.format("onBluetoothCallQualityReportReceived %s", qualityReport));
    }
}
