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
package com.android.server.telecom;

import static com.android.server.telecom.Call.RINGTONE_SOURCE_NETWORK_IN_CALL_MODE;
import static com.android.server.telecom.Call.RINGTONE_SOURCE_NETWORK_RING_MODE;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.telecom.CallAudioState;
import android.telecom.Log;
import android.telecom.VideoProfile;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Helper class for handling Customized Ringing Signal (CRS) related audio operations.
 */
public class CrsAudioController {
    private static final String TAG = "CrsAudioController";
    private static final int CRS_SPEAKER_READY_TIMEOUT_MS = 2000;
    private final Context mContext;
    private final AudioManager mAudioManager;
    private int mSavedSpeakerInCallVolume = -1;
    private final Map<Runnable, CommunicationDeviceChangedListener>
            mCommunicationDeviceChangedListeners = new ConcurrentHashMap<>();

    // Use a SingleThreadScheduledExecutor to handle both the listener callback
    // and the timeout scheduling off the main thread.
    private final ScheduledExecutorService mExecutor;
    private boolean mIsCrsModeSet = false;
    private boolean mIsCrsMuteSet = false;
    private CallAudioManager mCallAudioManager;

    /**
     * Creates a new CrsAudioController.
     */
    public CrsAudioController(Context context, AudioManager audioManager) {
        this(context, audioManager, Executors.newSingleThreadScheduledExecutor());
    }

    @VisibleForTesting
    public CrsAudioController(Context context, AudioManager audioManager,
            ScheduledExecutorService executor) {
        mContext = context;
        mAudioManager = audioManager;
        mExecutor = executor;
    }

    public void setCallAudioManager(CallAudioManager callAudioManager) {
        mCallAudioManager = callAudioManager;
    }


    /**
     * Listens for communication device changes.
     */
    public static class CommunicationDeviceChangedListener implements
            AudioManager.OnCommunicationDeviceChangedListener {

        private final Runnable mOnBuiltInSpeakerConnected;
        private final CompletableFuture<Boolean> mFuture;

        /**
         * Creates a new CommunicationDeviceChangedListener.
         */
        public CommunicationDeviceChangedListener(Runnable onBuiltInSpeakerConnected,
                CompletableFuture<Boolean> future) {
            mOnBuiltInSpeakerConnected = onBuiltInSpeakerConnected;
            mFuture = future;
        }

        /**
         * Called when the communication device has changed.
         */
        @Override
        public void onCommunicationDeviceChanged(AudioDeviceInfo device) {
            if (device == null) {
                return;
            }
            Log.i(TAG, "onCommunicationDeviceChanged, Device type is: " + device.getType());
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                if (mOnBuiltInSpeakerConnected != null) {
                    mOnBuiltInSpeakerConnected.run();
                }
                if (mFuture != null) {
                    mFuture.complete(true);
                }
            }
        }
    }

    /**
     * Runs a given action when the built-in speaker is ready.
     */
    public void runActionWhenSpeakerIsReady(Runnable action) {
        if (mAudioManager.isSpeakerphoneOn()) {
            action.run();
        } else {
            registerCommunicationDeviceChangedListener(action);
        }
    }

    private void registerCommunicationDeviceChangedListener(Runnable onBuiltInSpeakerConnected) {
        CompletableFuture<Boolean> future = getTimeoutFuture();
        future.thenAcceptAsync(result -> {
            if (!result) {
                Log.e(TAG, null, "Speaker ready timeout occurred.");
                unregisterCommunicationDeviceChangedListener(onBuiltInSpeakerConnected);
            }
        }, mExecutor);

        CommunicationDeviceChangedListener listener = new CommunicationDeviceChangedListener(
                onBuiltInSpeakerConnected, future);
        try {
            // Use mExecutor to run the listener off the main thread
            mAudioManager.addOnCommunicationDeviceChangedListener(mExecutor, listener);
            mCommunicationDeviceChangedListeners.put(onBuiltInSpeakerConnected, listener);
        } catch (Exception e) {
            Log.e(TAG, e, "addOnCommunicationDeviceChangedListener failed with exception: ");
        }
    }

    @VisibleForTesting
    protected CompletableFuture<Boolean> getTimeoutFuture() {
        return new CompletableFuture<Boolean>().completeOnTimeout(false,
                CRS_SPEAKER_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Unregisters a CommunicationDeviceChangedListener.
     */
    public void unregisterCommunicationDeviceChangedListener(Runnable onBuiltInSpeakerConnected) {
        if (mCommunicationDeviceChangedListeners.containsKey(onBuiltInSpeakerConnected)) {
            CommunicationDeviceChangedListener listener = mCommunicationDeviceChangedListeners.get(
                    onBuiltInSpeakerConnected);
            try {
                mAudioManager.removeOnCommunicationDeviceChangedListener(listener);
            } catch (Exception e) {
                Log.e(TAG, e, "removeOnCommunicationDeviceChangedListener failed with exception: ");
            }
            mCommunicationDeviceChangedListeners.remove(onBuiltInSpeakerConnected);
        }
    }

    /**
     * Converts a ring volume level to a CRS (voice call) volume level.
     */
    public int convertVolumeLevelFromRingToCrs(int ringVolume) {
        // CRS volume is same as voice call volume per design and telephony should
        // adjust voice volume according to ring volume when playing CRS audio,
        // however the range of local ring volume and voice call volume are different
        // for different devices, telephony needs to align volume level between local
        // ring and CRS(voice call volume) according to device audio configuration.
        final int maxVoiceCallVolume = mAudioManager.getStreamMaxVolume(
                AudioManager.STREAM_VOICE_CALL);
        final int maxRingVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_RING);
        final int minVoiceCallVolume = mAudioManager.getStreamMinVolume(
                AudioManager.STREAM_VOICE_CALL);
        final int minRingVolume = mAudioManager.getStreamMinVolume(AudioManager.STREAM_RING);
        if (ringVolume >= maxRingVolume) {
            return maxVoiceCallVolume;
        }
        final float ratio =
                (float) (maxVoiceCallVolume - minVoiceCallVolume) / (maxRingVolume - minRingVolume);
        int calculatedCrsVolume = minVoiceCallVolume + (int) Math.round(
                ratio * (ringVolume - minRingVolume));
        if (calculatedCrsVolume >= maxVoiceCallVolume) {
            calculatedCrsVolume = maxVoiceCallVolume;
        }
        Log.i(TAG, "CRS Volume Conversion: maxVoice=%d, maxRing=%d, minVoice=%d, "
                        + "minRing=%d, result=%d, ", maxVoiceCallVolume, maxRingVolume,
                minVoiceCallVolume,
                minRingVolume, calculatedCrsVolume);
        return calculatedCrsVolume;
    }

    /**
     * Sets the AudioManager mode to MODE_IN_CALL.
     */
    public void setAudioManagerInCallMode() {
        mExecutor.execute(() -> {
            Log.i(TAG, "Setting audio mode to MODE_IN_CALL");
            if (!com.android.internal.telecom.flags.Flags.callAudioRouteRf()) {
                mAudioManager.setMode(AudioManager.MODE_IN_CALL);
            } else if (mCallAudioManager != null) {
                mCallAudioManager.setAudioMode(AudioManager.MODE_IN_CALL);
            }
        });
    }

    /**
     * Sets the audio mode for CRS, ensuring the speaker is ready.
     */
    public void setAudioModeForCrs() {
        if(shouldControlCrsWithParameters()) {
            setCrsModeParams(true);
            setAudioManagerInCallMode();
            return;
        }
        runActionWhenSpeakerIsReady(this::setAudioManagerInCallMode);
    }

    /**
     * Removes the CommunicationDeviceChangedListener.
     */
    public void removeListener() {
        unregisterCommunicationDeviceChangedListener(this::setAudioManagerInCallMode);
    }

    /**
     * In CRS call case , this method backups the existing call volume and set the Ring volume to
     * the Call volume.
     */
    public void setSystemSpeakerVolume() {
        int ringVolumeLevel = mAudioManager.getStreamVolume(AudioManager.STREAM_RING);
        if (ringVolumeLevel > 0) {
            Log.i(TAG, "Initiating CRS playback at volume: " + ringVolumeLevel);
            // Set the CRS volume with local ring volume  and save the old volume setting.
            mSavedSpeakerInCallVolume = mAudioManager.getStreamVolume(
                    AudioManager.STREAM_VOICE_CALL);
            Log.i(TAG, "Stored in-call volume: " + mSavedSpeakerInCallVolume);
            mAudioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL,
                    convertVolumeLevelFromRingToCrs(ringVolumeLevel), 0);
        }
    }

    /**
     * Once the CRS call is move out of Ringing state this method will restore the saved call
     * volume back in the AudioManager.
     */
    public void restoreSystemSpeakerVolume() {
        boolean speakerOn = mAudioManager.isSpeakerphoneOn();
        Log.i(TAG, "Restoring speaker volume: speaker ON =  " + speakerOn
                + ", mSavedSpeakerInCallVolume = " + mSavedSpeakerInCallVolume);
        silenceInCallModeCrs(false);
        if (speakerOn && (mSavedSpeakerInCallVolume != -1)) {
            // Restore inCall volume after getting ACTIVE/DISCONNECTED state as
            // CRS volume used the system ringing volume level.
            // And set volume level for speaker only.
            mAudioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, mSavedSpeakerInCallVolume,
                    0);
            Log.i(TAG, "Speaker volume restoration complete.");
        }
    }

    /**
     * Mutes or unmutes the CRS audio in in-call mode.
     */
    public void silenceInCallModeCrs(boolean mute) {
        Log.i(TAG, "Setting CRS mute state to: " + mute);
        mAudioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL,
                mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
    }

    /**
     * Sets the volume level for CRS when in ringtone mode.
     */
    public void setVolumeLevelForCrsInRingtoneMode(int volume) {
        String crsVolumeKeyPrefix = TelecomResourceId.getString(mContext,
                "config_audio_parameter_key_crs_volume");
        Log.d(TAG, "CRS volume parameter key: " + crsVolumeKeyPrefix);
        if (!TextUtils.isEmpty(crsVolumeKeyPrefix)) {
            Log.i(TAG, "Applying CRS volume: " + crsVolumeKeyPrefix + volume);
            mAudioManager.setParameters(crsVolumeKeyPrefix + volume);
        }
    }

    /**
     * Configures the CRS ring volume based on the ringer attributes.
     */
    public void configureCrsRingVolume(RingerAttributes ringerAttributes) {
        if (ringerAttributes.getRingtoneType() == RINGTONE_SOURCE_NETWORK_RING_MODE) {
            //CRS has no haptics channel
            Log.i(TAG, "Playing CRS in RINGTONE Mode");
            setVolumeLevelForCrsInRingtoneMode(
                    mAudioManager.getStreamVolume(AudioManager.STREAM_RING));
        } else if (ringerAttributes.getRingtoneType() == RINGTONE_SOURCE_NETWORK_IN_CALL_MODE) {
            Log.i(TAG, "CRS ringtone playing in IN-Call Mode");
            if (shouldControlCrsWithParameters()) {
                // Pass the mute parameter only if the ringer is not audible (e.g., volume is 0
                // or Do Not Disturb is active).
                if (!ringerAttributes.shouldAcquireAudioFocus()
                        && !ringerAttributes.isRingerAudible()) {
                    setCrsSpeechMuted(true);
                }
                return;
            }
            runActionWhenSpeakerIsReady(this::setSystemSpeakerVolume);
        }
    }

    /**
     * Reset the Ringer volume upon CRS call mute or call terminated.
     *
     * @param call             Current CRS call
     * @param ringerAttributes RingerAttributes of the CRS call.
     */
    public void resetCrsAudioVolume(Call call, RingerAttributes ringerAttributes) {
        if (ringerAttributes == null) {
            Log.w(TAG, "resetCrsAudioVolume: ringerAttributes is absent");
            return;
        }
        if (ringerAttributes.getRingtoneType() == RINGTONE_SOURCE_NETWORK_RING_MODE) {
            //set CRS_volume as 0 when CRS is stopped or silence the call.
            setVolumeLevelForCrsInRingtoneMode(0);
            Log.addEvent(call, LogUtils.Events.STOP_CRS_RINGER_IN_MODE_RINGTONE);
        } else if (ringerAttributes.getRingtoneType() == RINGTONE_SOURCE_NETWORK_IN_CALL_MODE) {
            if (shouldControlCrsWithParameters()) {
                setCrsModeParams(false);
            } else {
                unregisterCommunicationDeviceChangedListener(this::setSystemSpeakerVolume);
                silenceInCallModeCrs(true);
            }
            Log.addEvent(call, LogUtils.Events.STOP_CRS_RINGER_IN_MODE_IN_CALL);
        }
    }

    /**
     * Resets the audio devices after CRS ringing.
     */
    public void resetAudioDevices(CallAudioManager callAudioManager, CallsManager callsManager,
            Call call, int newState) {
        if (shouldControlCrsWithParameters()) {
            Log.i(TAG, "Calling setCrsSpeechUnmuteParams");
            // When user explicitly silenced the ring, we are passing the Speech Mute to modem,
            // so it is required to pass unmute upon call state change only in this case.
            setCrsSpeechMuted(false);
            return;
        }
        restoreSystemSpeakerVolume();
        // For voice calls or VT calls accepted as voice, the audio path should default to earpiece.
        // TODO b/463698834 check if we can send "SWITCH_BASELINE_ROUTE" instead below routing.
        if (newState == CallState.ACTIVE) {
            if (callsManager.isBtAvailable()) {
                callAudioManager.setAudioRoute(CallAudioState.ROUTE_BLUETOOTH, null);
            } else if (callsManager.isWiredHandsetIn()) {
                callAudioManager.setAudioRoute(CallAudioState.ROUTE_WIRED_HEADSET, null);
            } else if (call.getVideoState() != VideoProfile.STATE_BIDIRECTIONAL) {
                callAudioManager.setAudioRoute(CallAudioState.ROUTE_EARPIECE, null);
            }
        }
    }

    /**
     * Checks if the given call is a CRS call in in-call mode.
     */
    public boolean isCrsInCallMode(Call call) {
        return (call != null && call.getCrsMode() == AudioManager.MODE_IN_CALL && call.isCrsCall());
    }

    /**
     * Gets the CRS ringtone type for a given call.
     */
    public int getCrsRingToneType(Call call) {
        return call.getCrsMode() == AudioManager.MODE_RINGTONE
                ? RINGTONE_SOURCE_NETWORK_RING_MODE
                : RINGTONE_SOURCE_NETWORK_IN_CALL_MODE;
    }

    /**
     * Sets the audio route for CRS to speaker.
     */
    public void setCrsAudioRoute(CallAudioManager callAudioManager) {
        if(shouldControlCrsWithParameters()) {
            Log.w(TAG, "setCrsAudioRoute to Speaker is not required");
            return;
        }
        callAudioManager.setAudioRoute(CallAudioState.ROUTE_SPEAKER, null);
    }

    public void setCrsModeParams(boolean enable) {
        mExecutor.execute(() -> {
            if (enable == mIsCrsModeSet) {
                return;
            }
            String modeToken;
            if (enable) {
                modeToken = TelecomResourceId.getString(mContext, "config_crs_mode_on_param");
                mIsCrsModeSet = true;
            } else {
                modeToken = TelecomResourceId.getString(mContext, "config_crs_mode_off_param");
                mIsCrsModeSet = false;
            }
            if (!TextUtils.isEmpty(modeToken)) {
                Log.d(TAG, "setCrsModeParams: " + modeToken);
                mAudioManager.setParameters(modeToken);
            }
        });
    }

    public void setCrsSpeechMuted(boolean mute) {
        mExecutor.execute(() -> {
            if (mute == mIsCrsMuteSet) {
                return;
            }
            String token;
            if (mute) {
                token = TelecomResourceId.getString(mContext, "config_crs_speech_mute_param");
                mIsCrsMuteSet = true;
            } else {
                token = TelecomResourceId.getString(mContext, "config_crs_speech_unmute_param");
                mIsCrsMuteSet = false;
            }
            if (!TextUtils.isEmpty(token)) {
                Log.d(TAG, "setCrsSpeechMuted: " + token);
                mAudioManager.setParameters(token);
            }
        });
    }

    /**
     * It checks whether the Audio routing is controlled by the Audio HAL or not.
     * @return {@code true} if Audio HAL handling the audio routing else {@code false}.
     */
    public boolean shouldControlCrsWithParameters() {
        if (mContext == null) {
            return false;
        }
        return TextUtils.isEmpty(TelecomResourceId.getString(mContext,
                "config_audio_parameter_key_crs_volume"))
                && !TextUtils.isEmpty(TelecomResourceId.getString(mContext,
                "config_crs_speech_mute_param"));
    }
}
