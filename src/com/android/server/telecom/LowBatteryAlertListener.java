
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.telecom.Log;
import android.telecom.PhoneAccount;
import android.telecom.VideoProfile;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class LowBatteryAlertListener extends CallsManagerListenerBase {

    private Context mContext;
    private InCallTonePlayer.Factory mToneGeneratorFactory;
    private ScheduledExecutorService mScheduledExecutorService;
    private InCallTonePlayer mBatteryTonePlayer;
    private int mAlterInterval; //how frequently the tone should be played (interval in seconds)
    private int mBatteryLevelThreshold; //Battery level value to alert low battery
    private BroadcastReceiver mBatteryListener = null;
    private Call mCall;
    private ScheduledFuture<?> mToneFuture = null;

    public LowBatteryAlertListener(Context context,
            ScheduledExecutorService scheduledExecutorService) {
        mContext = context;
        mScheduledExecutorService = scheduledExecutorService;
    }

    public void registerForLowBatteryListener(InCallTonePlayer.Factory toneGeneratorFactory) {
        mToneGeneratorFactory = toneGeneratorFactory;
    }

    @Override
    public void onCallStateChanged(Call call, int oldState, int newState) {
        Log.i(this, "onCallStateChanged new state: " + CallState.toString(newState) + " old state: "
                + CallState.toString(oldState));
        if (call == mCall) {
            if (newState != CallState.ACTIVE) {
                Log.i(this, "Tracked call left ACTIVE -> stop tracking: " + call);
                unregisterBatteryChangeReceiver();
                mCall = null;
            }
            return;
        }
        if (newState == CallState.ACTIVE && isEligibleForLowBatteryAlert(call)) {
            if (mCall != null) {
                Log.i(this, "Replacing previously tracked call: " + mCall + " with " + call);
                unregisterBatteryChangeReceiver();
            }
            mCall = call;
            registerBatteryChangeReceiver();
        }
    }

    private void playLowBatteryAlertTone() {
        if (mToneFuture == null) {
            Log.i(this, "Scheduling low battery alert tone repetition");
            // Play immediately, then repeat every mAlterInterval seconds
            mToneFuture = mScheduledExecutorService.scheduleAtFixedRate(
                    mToneRunnable, 0, mAlterInterval, TimeUnit.SECONDS);
        }
    }

    private void stopTone() {
        Log.i(this, "stop low battery alert tone");
        if (mToneFuture != null) {
            mToneFuture.cancel(false);
            mToneFuture = null;
        }
        if (mBatteryTonePlayer != null) {
            mBatteryTonePlayer.stopTone();
            mBatteryTonePlayer = null;
        }
    }

    private void registerBatteryChangeReceiver() {
        if (mBatteryListener != null || mContext == null) {
            // already registered..
            return;
        }
        Log.i(this, "registerBatteryChangeReceiver");
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);

        mBatteryListener = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.startSession("LBAL.oR");
                if (intent == null) {
                    Log.i(this, "No battery changed intent received");
                    return;
                }
                final String action = intent.getAction();
                if (Intent.ACTION_BATTERY_CHANGED.equals(action) && mCall != null) {
                    int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
                        stopTone();
                    } else if (isBatteryLow(intent)) {
                        playLowBatteryAlertTone();
                    }
                }
                Log.endSession();
            }
        };
        mContext.registerReceiver(mBatteryListener, intentFilter,
                Context.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterBatteryChangeReceiver() {
        Log.i(this, "unregisterBatteryChangeReceiver");
        if (mBatteryListener != null && mContext != null) {
            mContext.unregisterReceiver(mBatteryListener);
            mBatteryListener = null;
        }
        stopTone();
    }

    private final Runnable mToneRunnable = new Runnable() {
        @Override
        public void run() {
            Log.i(this, "Playing low battery alert tone via ScheduledExecutorService");
            try {
                if (mBatteryTonePlayer != null) {
                    mBatteryTonePlayer.stopTone(); // Stop previous if still playing
                }
                mBatteryTonePlayer = mToneGeneratorFactory.createPlayer(mCall,
                        InCallTonePlayer.TONE_LOW_BATTERY);
                if (mBatteryTonePlayer != null) {
                    mBatteryTonePlayer.startTone();
                }
            } catch (Exception e) {
                Log.i(this, "Failed to create or play tone: " + e);
            }
        }
    };

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        if (mCall == call) {
            mCall = null;
            unregisterBatteryChangeReceiver();
            Log.i(this, "clearing config values");
        }
    }

    private boolean isEligibleForLowBatteryAlert(Call call) {
        if (call == null
                || call.isSelfManaged()
                || call.isExternalCall()
                || !VideoProfile.isAudioOnly(call.getVideoState())
                || call.isRttCall()) {
            return false;
        }
        PhoneAccount phoneAccount = call.getPhoneAccountFromHandle();
        if (phoneAccount == null) {
            Log.i(this, "No PhoneAccount found for this Call.");
            return false;
        }
        Bundle extras = phoneAccount.getExtras();
        if (extras == null) {
            Log.i(this, "No extras found for this PhoneAccount.");
            return false;
        }

        int lowBatteryLevel = getLowBatteryLevel(extras);
        int alertInterval = getLowBatteryAlertInterval(extras);

        if (lowBatteryLevel != PhoneAccount.LOW_BATTERY_ALERT_DISABLED
                && alertInterval != PhoneAccount.LOW_BATTERY_ALERT_DISABLED) {
            mBatteryLevelThreshold = lowBatteryLevel;
            mAlterInterval = alertInterval;
            Log.i(this, "Low battery alert enabled. Threshold: " + mBatteryLevelThreshold +
                    ", Interval: " + mAlterInterval + "s");
            return true;
        }

        return false;
    }

    private boolean isBatteryLow(Intent intent) {
        if (intent == null) {
            return false;
        }
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return level > 0 && scale > 0 && (level * 100 / scale <= mBatteryLevelThreshold);
    }

    private int getLowBatteryLevel(Bundle extras) {
        int lowBatteryLevel = extras.getInt(PhoneAccount.EXTRA_LOW_BATTERY_ALERT_LEVEL_THRESHOLD,
                PhoneAccount.LOW_BATTERY_ALERT_DISABLED);
        Log.i(this, "lowBatteryLevel = " + lowBatteryLevel);
        return lowBatteryLevel;
    }

    private int getLowBatteryAlertInterval(Bundle extras) {
        int alertInterval = extras.getInt(PhoneAccount.EXTRA_LOW_BATTERY_ALERT_INTERVAL_SECONDS,
                PhoneAccount.LOW_BATTERY_ALERT_DISABLED);
        Log.i(this, "alertInterval = " + alertInterval);
        return alertInterval;
    }
}