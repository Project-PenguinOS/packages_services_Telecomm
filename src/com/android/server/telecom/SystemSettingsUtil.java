/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioManager;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.Vibrator;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.telecom.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telecom.flags.Flags;

/**
 * Accesses the Global System settings for more control during testing.
 */
@VisibleForTesting
public class SystemSettingsUtil {
    /**
     * TODO(b/441480678): Cleanup to link to the proper API in system settings.
     */
    public static final String RING_VIBRATION_INTENSITY = "ring_vibration_intensity";

    /**
     * The lowest vibration intensity, which is 0 meaning that the vibration is disabled.
     */
    public static final int VIBRATION_INTENSITY_OFF = 0;

    /**
     * Abstracts away the static {@link Settings.System} calls so they can be mocked in tests.
     */
    @VisibleForTesting
    public interface SystemSettingsReader {
        int getInt(ContentResolver cr, String name, int def);
        int getIntForUser(Context context, String name, int def, int userHandle);
    }

    private static class DefaultSystemSettingsReader implements SystemSettingsReader {
        @Override
        public int getInt(ContentResolver cr, String name, int def) {
            return Settings.System.getInt(cr, name, def);
        }

        @Override
        public int getIntForUser(Context context, String name, int def, int userHandle) {
            return Settings.System.getInt(
                context.createContextAsUser(UserHandle.of(userHandle), 0).getContentResolver(),
                name, def);
        }
    }

    private final SystemSettingsReader mSystemSettingsReader;

    public SystemSettingsUtil() {
        this(new DefaultSystemSettingsReader());
    }

    @VisibleForTesting
    public SystemSettingsUtil(SystemSettingsReader systemSettingsReader) {
        mSystemSettingsReader = systemSettingsReader;
    }

    /** Flag for whether or not to support audio coupled haptics in ramping ringer. */
    private static final String RAMPING_RINGER_AUDIO_COUPLED_VIBRATION_ENABLED =
            "ramping_ringer_audio_coupled_vibration_enabled";


    /**
     * Determines if the primary "vibration" toggle is on in the system settings (see Settings -->
     * Sound and Vibration --> Vibration and Haptics --> Use Vibration and Haptics).
     * @param context the context to use for checking the settings.
     * @return {@code true} if the primary haptic toggle is on, {@code false} otherwise.
     */
    private boolean isVibrationEnabled(Context context) {
        if (!Flags.vibrationAccountsForMainSetting()) {
            return true;
        }
        // Note, there is no constant for the on/off.  0 is used elsewhere in the platform when this
        // key is referenced.
        return mSystemSettingsReader.getInt(context.getContentResolver(),
                Settings.System.VIBRATE_ON, 1) != 0;
    }

    public boolean isRingVibrationEnabled(Context context) {
        // Ramping ringer should only be applied when ring vibration is ON, otherwise the
        // ringtone sound should not be delayed as there will be no ring vibration.
        // Note: VIBRATE_WHEN_RINGING is deprecated but is currently the only system API that
        // the Haptics Framework team provides. For mainlining Telecom, using
        // VIBRATE_WHEN_RINGING is our only option currently until the request for a new system
        // API (b/441480678) has been met.

        int defaultIntensity = 2; // VIBRATION_INTENSITY_MEDIUM

        // There have been reported issues where the user has enabled vibrations but when we query
        // the deprecated Settings.System.VIBRATE_WHEN_RINGING it looks like vibration is disabled.
        // Settings is responsible for keeping the two in sync, so it looks like there are cases
        // where they get out of sync and vibration fails to play.
        int currentVibrationIntensity = mSystemSettingsReader.getInt
                (context.getContentResolver(), RING_VIBRATION_INTENSITY, defaultIntensity);
        boolean isVibrationEnabledDueToIntensity =
                currentVibrationIntensity != VIBRATION_INTENSITY_OFF;

        // We'll also check to see if the old setting said we should vibrate or not.
        boolean isVibrationEnabledDueToDeprecatedSetting = mSystemSettingsReader.getInt
                (context.getContentResolver(),
                        Settings.System.VIBRATE_WHEN_RINGING,
                         /*context.getSystemService(Vibrator.class)
                            .getDefaultVibrationIntensity(VibrationAttributes.USAGE_RINGTONE)*/
                        defaultIntensity) != 0;

        // If they're out of sync, log a warning so we can diagnose in a bug report easier.
        if (isVibrationEnabledDueToDeprecatedSetting != isVibrationEnabledDueToIntensity) {
            Log.w(this,
                    "isRingVibrationEnabled: currentVibrationIntensity=%d, "
                            + "isVibrationEnabledDueToIntensity=%b, "
                            + "isVibrationEnabledDueToDeprecatedSetting=%b",
                    currentVibrationIntensity, isVibrationEnabledDueToIntensity,
                    isVibrationEnabledDueToDeprecatedSetting);
        }
        return isVibrationEnabledDueToIntensity && isVibrationEnabled(context);
    }

    public boolean isRampingRingerEnabled(Context context) {
        return context.getSystemService(AudioManager.class).isRampingRingerEnabled();
    }

    public boolean isAudioCoupledVibrationForRampingRingerEnabled() {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_TELEPHONY,
                RAMPING_RINGER_AUDIO_COUPLED_VIBRATION_ENABLED, false);
    }

    public boolean isHapticPlaybackSupported(Context context) {
        return context.getSystemService(AudioManager.class).isHapticPlaybackSupported();
    }
}
