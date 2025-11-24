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

package com.android.server.telecom.metrics;

import static com.android.server.telecom.AudioRoute.TYPE_BLUETOOTH_HA;
import static com.android.server.telecom.AudioRoute.TYPE_BLUETOOTH_LE;
import static com.android.server.telecom.TelecomStatsLog.CALL_END_POINT_STATS;
import static com.android.server.telecom.TelecomStatsLog.CALL_END_POINT_STATS__ENDPOINT_REQUESTED__CALL_AUDIO_BLUETOOTH;
import static com.android.server.telecom.TelecomStatsLog.CALL_END_POINT_STATS__ENDPOINT_REQUESTED__CALL_AUDIO_BLUETOOTH_LE;
import static com.android.server.telecom.TelecomStatsLog.CALL_END_POINT_STATS__ENDPOINT_REQUESTED__CALL_AUDIO_EARPIECE;
import static com.android.server.telecom.TelecomStatsLog.CALL_END_POINT_STATS__ENDPOINT_REQUESTED__CALL_AUDIO_HEARING_AID;
import static com.android.server.telecom.TelecomStatsLog.CALL_END_POINT_STATS__ENDPOINT_REQUESTED__CALL_AUDIO_PHONE_SPEAKER;
import static com.android.server.telecom.TelecomStatsLog.CALL_END_POINT_STATS__ENDPOINT_REQUESTED__CALL_AUDIO_UNSPECIFIED;
import static com.android.server.telecom.TelecomStatsLog.CALL_END_POINT_STATS__ENDPOINT_REQUESTED__CALL_AUDIO_WATCH_SPEAKER;
import static com.android.server.telecom.TelecomStatsLog.CALL_END_POINT_STATS__ENDPOINT_REQUESTED__CALL_AUDIO_WIRED_HEADSET;
import static com.android.server.telecom.TelecomStatsLog.CALL_END_POINT_STATS__RESULT__ENDPOINT_RESULT_DIFFERENT;
import static com.android.server.telecom.TelecomStatsLog.CALL_END_POINT_STATS__RESULT__ENDPOINT_RESULT_OVERRIDDEN;
import static com.android.server.telecom.TelecomStatsLog.CALL_END_POINT_STATS__RESULT__ENDPOINT_RESULT_SAME;
import static com.android.server.telecom.TelecomStatsLog.CALL_END_POINT_STATS__RESULT__ENDPOINT_RESULT_UNKNOWN;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.StatsManager;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.telecom.CallAudioState;
import android.telecom.Log;
import android.text.TextUtils;
import android.util.StatsEvent;

import androidx.annotation.VisibleForTesting;

import com.android.server.telecom.AudioRoute;
import com.android.server.telecom.TelecomStatsLog;
import com.android.server.telecom.bluetooth.BluetoothRouteManager;
import com.android.server.telecom.nano.PulledAtomsClass;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CallEndpointStats extends TelecomPulledAtom {
    public static final int ENDPOINT_RESULT_UNKNOWN =
            CALL_END_POINT_STATS__RESULT__ENDPOINT_RESULT_UNKNOWN;
    public static final int ENDPOINT_RESULT_SAME =
            CALL_END_POINT_STATS__RESULT__ENDPOINT_RESULT_SAME;
    public static final int ENDPOINT_RESULT_DIFFERENT =
            CALL_END_POINT_STATS__RESULT__ENDPOINT_RESULT_DIFFERENT;
    public static final int ENDPOINT_RESULT_OVERRIDDEN =
            CALL_END_POINT_STATS__RESULT__ENDPOINT_RESULT_OVERRIDDEN;
    public static final int THRESHOLD_TIMEOUT_MS = 2 * 1000;
    public static final int ENDPOINT_TYPE_UNSPECIFIED =
            CALL_END_POINT_STATS__ENDPOINT_REQUESTED__CALL_AUDIO_UNSPECIFIED;
    public static final int ENDPOINT_TYPE_EARPIECE =
            CALL_END_POINT_STATS__ENDPOINT_REQUESTED__CALL_AUDIO_EARPIECE;
    public static final int ENDPOINT_TYPE_SPEAKER =
            CALL_END_POINT_STATS__ENDPOINT_REQUESTED__CALL_AUDIO_PHONE_SPEAKER;
    public static final int ENDPOINT_TYPE_HEARING_AID =
            CALL_END_POINT_STATS__ENDPOINT_REQUESTED__CALL_AUDIO_HEARING_AID;
    public static final int ENDPOINT_TYPE_BLUETOOTH =
            CALL_END_POINT_STATS__ENDPOINT_REQUESTED__CALL_AUDIO_BLUETOOTH;
    public static final int ENDPOINT_TYPE_BLUETOOTH_LE =
            CALL_END_POINT_STATS__ENDPOINT_REQUESTED__CALL_AUDIO_BLUETOOTH_LE;
    public static final int ENDPOINT_TYPE_WATCH_SPEAKER =
            CALL_END_POINT_STATS__ENDPOINT_REQUESTED__CALL_AUDIO_WATCH_SPEAKER;
    public static final int ENDPOINT_TYPE_WIRED_HEADSET =
            CALL_END_POINT_STATS__ENDPOINT_REQUESTED__CALL_AUDIO_WIRED_HEADSET;
    private static final String TAG = CallEndpointStats.class.getSimpleName();
    private static final String FILE_NAME = "call_endpoint_stats";

    int mUid = -1;
    long mTimeRequested;
    private Map<CallEndpointStatsKey, CallEndpointStatsData> mCallEndpointStatsMap;
    private final Map<String, Integer> mBluetoothDevices = new HashMap<>();
    private int mTypeRequested;
    private String mBluetoothAddressRequested;
    public CallEndpointStats(@NonNull Context context, @NonNull Looper looper, boolean isTestMode) {
        super(context, looper, isTestMode);
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public int getTag() {
        return CALL_END_POINT_STATS;
    }

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public synchronized int onPull(final List<StatsEvent> data) {
        if (mPulledAtoms.callEndpointStats != null && mPulledAtoms.callEndpointStats.length != 0) {
            Arrays.stream(mPulledAtoms.callEndpointStats).forEach(v -> data.add(
                    TelecomStatsLog.buildStatsEvent(getTag(),
                            v.getEndpointRequested(), v.getEndpointNotified(), v.getUid(),
                            v.getResult(), v.getTimeout(), v.getAverageLatencyMs(), v.getCount())));
            mCallEndpointStatsMap.clear();
            onAggregate();
        }
        return StatsManager.PULL_SUCCESS;
    }

    @Override
    protected synchronized void onLoad() {
        mCallEndpointStatsMap = new HashMap<>();
        if (mPulledAtoms.callEndpointStats != null) {
            for (PulledAtomsClass.CallEndPointStats v : mPulledAtoms.callEndpointStats) {
                mCallEndpointStatsMap.put(new CallEndpointStatsKey(v.getUid(),
                                v.getEndpointRequested(), v.getEndpointNotified(),
                                v.getResult(), v.getTimeout()),
                        new CallEndpointStatsData(v.getCount(), v.getAverageLatencyMs()));
            }
            mLastPulledTimestamps = mPulledAtoms.getCallEndpointStatsPullTimestampMillis();
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public synchronized void onAggregate() {
        Log.d(TAG, "onAggregate: %s", mCallEndpointStatsMap);
        clearAtoms();
        if (mCallEndpointStatsMap.isEmpty()) {
            return;
        }

        mPulledAtoms.setCallEndpointStatsPullTimestampMillis(mLastPulledTimestamps);
        mPulledAtoms.callEndpointStats =
                new PulledAtomsClass.CallEndPointStats[mCallEndpointStatsMap.size()];

        int[] index = new int[1];
        mCallEndpointStatsMap.forEach((k, v) -> {
            mPulledAtoms.callEndpointStats[index[0]] = new PulledAtomsClass.CallEndPointStats();
            mPulledAtoms.callEndpointStats[index[0]].setUid(k.mUid);
            mPulledAtoms.callEndpointStats[index[0]].setEndpointRequested(k.mTypeRequested);
            mPulledAtoms.callEndpointStats[index[0]].setEndpointNotified(k.mTypeNotified);
            mPulledAtoms.callEndpointStats[index[0]].setResult(k.mResult);
            mPulledAtoms.callEndpointStats[index[0]].setTimeout(k.mIsTimeout);
            mPulledAtoms.callEndpointStats[index[0]].setCount(v.mCount);
            mPulledAtoms.callEndpointStats[index[0]].setAverageLatencyMs(v.mAverageLatency);
            index[0]++;
        });
        save(DELAY_FOR_PERSISTENT_MILLIS);
    }

    public void log(int uid, @EndpointType int requested, @EndpointType int notified,
                    int result, boolean isTimeout, int latency) {
        post(() -> {
            CallEndpointStatsKey key = new CallEndpointStatsKey(
                    uid, requested, notified, result, isTimeout);
            CallEndpointStatsData data = mCallEndpointStatsMap.computeIfAbsent(key,
                    k -> new CallEndpointStatsData(0, 0));
            data.add(latency);
            onAggregate();
        });
    }

    public void onRequested(int uid, int type, String bluetoothAddress) {
        long curTime = SystemClock.elapsedRealtime();
        post(() -> {
            if (mTypeRequested != ENDPOINT_TYPE_UNSPECIFIED) {
                int requested = getEndpointType(mTypeRequested, mBluetoothAddressRequested);
                int notified = getEndpointType(type, bluetoothAddress);
                int interval = (int) (curTime - mTimeRequested);
                boolean isTimeout = interval > THRESHOLD_TIMEOUT_MS;
                log(mUid, requested, notified, ENDPOINT_RESULT_OVERRIDDEN, isTimeout, interval);
            }
            mUid = uid;
            mTypeRequested = type;
            mBluetoothAddressRequested = bluetoothAddress;
            mTimeRequested = curTime;
        });
    }

    public void onNotified(int type, String bluetoothAddress) {
        int interval = (int) (SystemClock.elapsedRealtime() - mTimeRequested);
        post(() -> {
            if (mTypeRequested != ENDPOINT_TYPE_UNSPECIFIED) {
                int requested = getEndpointType(mTypeRequested, mBluetoothAddressRequested);
                int notified = getEndpointType(type, bluetoothAddress);
                boolean isTimeout = interval > THRESHOLD_TIMEOUT_MS;
                boolean isSame = (mTypeRequested & type) != 0
                        && TextUtils.equals(mBluetoothAddressRequested, bluetoothAddress);
                int result = isSame ? ENDPOINT_RESULT_SAME : ENDPOINT_RESULT_DIFFERENT;
                log(mUid, requested, notified, result, isTimeout, interval);
                //clear the request when receiving the same notification type, or timeout.
                if (isSame || isTimeout) {
                    mTypeRequested = 0;
                    mBluetoothAddressRequested = null;
                }
            }
        });
    }

    public void onException(boolean success) {
        long curTime = SystemClock.elapsedRealtime();
        post(() -> {
            if (mTypeRequested != ENDPOINT_TYPE_UNSPECIFIED) {
                int requested = getEndpointType(mTypeRequested, mBluetoothAddressRequested);
                int interval = (int) (curTime - mTimeRequested);

                log(mUid, requested, ENDPOINT_TYPE_UNSPECIFIED,
                        success ? ENDPOINT_RESULT_SAME : ENDPOINT_RESULT_UNKNOWN,
                        false, interval);
                mTypeRequested = 0;
                mBluetoothAddressRequested = null;
            }
        });
    }

    public @EndpointType int getEndpointType(int type, String bluetoothAddress) {
        if (!TextUtils.isEmpty(bluetoothAddress)) {
            return mBluetoothDevices.getOrDefault(bluetoothAddress, ENDPOINT_TYPE_BLUETOOTH);
        }
        return switch (type) {
            case CallAudioState.ROUTE_BLUETOOTH -> ENDPOINT_TYPE_BLUETOOTH;
            case CallAudioState.ROUTE_EARPIECE -> ENDPOINT_TYPE_EARPIECE;
            case CallAudioState.ROUTE_SPEAKER -> ENDPOINT_TYPE_SPEAKER;
            case CallAudioState.ROUTE_WIRED_HEADSET -> ENDPOINT_TYPE_WIRED_HEADSET;
            default -> ENDPOINT_TYPE_UNSPECIFIED;
        };
    }

    public void updateBluetoothDevices(Map<AudioRoute, BluetoothDevice> btRoute) {
        post(() -> {
            if (btRoute != null) {
                btRoute.forEach((route, device) -> {
                    int type = switch (route.getType()) {
                        case TYPE_BLUETOOTH_HA -> ENDPOINT_TYPE_HEARING_AID;
                        case TYPE_BLUETOOTH_LE -> ENDPOINT_TYPE_BLUETOOTH_LE;
                        default -> BluetoothRouteManager.isWatch(device)
                                ? ENDPOINT_TYPE_WATCH_SPEAKER : ENDPOINT_TYPE_BLUETOOTH;
                    };
                    mBluetoothDevices.put(device.getAddress(), type);
                });
            }
        });
    }

    @IntDef(prefix = "ENDPOINT_TYPE_", value = {
            ENDPOINT_TYPE_UNSPECIFIED,
            ENDPOINT_TYPE_EARPIECE,
            ENDPOINT_TYPE_SPEAKER,
            ENDPOINT_TYPE_WIRED_HEADSET,
            ENDPOINT_TYPE_HEARING_AID,
            ENDPOINT_TYPE_BLUETOOTH,
            ENDPOINT_TYPE_BLUETOOTH_LE,
            ENDPOINT_TYPE_WATCH_SPEAKER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EndpointType {
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    static class CallEndpointStatsKey {
        @EndpointType
        final int mTypeRequested;
        @EndpointType
        final int mTypeNotified;
        final int mResult;
        final boolean mIsTimeout;
        int mUid = -1;

        CallEndpointStatsKey(int uid, @EndpointType int requested, @EndpointType int notified,
                             int result, boolean isTimeout) {
            mUid = uid;
            mTypeRequested = requested;
            mTypeNotified = notified;
            mResult = result;
            mIsTimeout = isTimeout;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CallEndpointStatsKey obj)) {
                return false;
            }
            return this.mTypeRequested == obj.mTypeRequested
                    && this.mTypeNotified == obj.mTypeNotified
                    && this.mResult == obj.mResult
                    && this.mIsTimeout == obj.mIsTimeout;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mTypeRequested, mTypeNotified, mResult, mIsTimeout);
        }

        @Override
        public String toString() {
            return "[CallEndpointStatsKey: mEndpointRequested=" + mTypeRequested
                    + ", mEndpointNotified=" + mTypeNotified
                    + ", mResult=" + mResult + ", mIsTimeout=" + mIsTimeout + "]";
        }
    }

    static class CallEndpointStatsData {
        int mCount;
        int mAverageLatency;

        CallEndpointStatsData(int count, int averageLatency) {
            mCount = count;
            mAverageLatency = averageLatency;
        }

        void add(int latency) {
            mCount++;
            mAverageLatency += (latency - mAverageLatency) / mCount;
        }

        @Override
        public String toString() {
            return "[CallEndpointStatsData: mCount=" + mCount + ", mAverageLatency:"
                    + mAverageLatency + "]";
        }
    }
}
