/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.telecom.components;

import android.app.Service;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.ToneGenerator;
import android.os.CombinedVibration;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.BlockedNumbersManager;
import android.telecom.Log;

import android.telecom.TelecomManager;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.telecom.ITelecomLoader;
import com.android.internal.telecom.ITelecomService;
import com.android.server.telecom.AsyncRingtonePlayer;
import com.android.server.telecom.CallAudioModeStateMachine;
import com.android.server.telecom.CallAudioRouteController;
import com.android.server.telecom.CallerInfoAsyncQueryFactory;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.ClockProxy;
import com.android.server.telecom.ConnectionServiceFocusManager;
import com.android.server.telecom.ContactsAsyncHelper;
import com.android.server.telecom.DefaultDialerCache;
import com.android.server.telecom.SystemBlockedNumberContract;
import com.android.server.telecom.flags.FeatureFlags;
import com.android.server.telecom.HeadsetMediaButton;
import com.android.server.telecom.HeadsetMediaButtonFactory;
import com.android.server.telecom.InCallWakeLockControllerFactory;
import com.android.server.telecom.CallAudioManager;
import com.android.server.telecom.PhoneAccountRegistrar;
import com.android.server.telecom.PhoneNumberUtilsAdapterImpl;
import com.android.server.telecom.ProximitySensorManagerFactory;
import com.android.server.telecom.InCallWakeLockController;
import com.android.server.telecom.ProximitySensorManager;
import com.android.server.telecom.Ringer;
import com.android.server.telecom.RoleManagerAdapterImpl;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.TelecomWakeLock;
import com.android.server.telecom.Timeouts;
import com.android.server.telecom.callfiltering.BlockedNumbersAdapter;
import com.android.server.telecom.flags.FeatureFlagsImpl;
import com.android.server.telecom.settings.BlockedNumbersUtil;
import com.android.server.telecom.ui.IncomingCallNotifier;
import com.android.server.telecom.ui.MissedCallNotifierImpl;
import com.android.server.telecom.ui.NotificationChannelManager;
import com.android.server.telecom.util.CallerInfoAsyncQuery;

import java.util.concurrent.Executors;

/**
 * Implementation of the ITelecom interface.
 */
public class TelecomService extends Service {

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(this, "onBind");
        return new ITelecomLoader.Stub() {
            @Override
            public ITelecomService createTelecomService(String sysUiPackageName) {
                initTelecomUi(TelecomService.this);
                initializeTelecomSystem(TelecomService.this, sysUiPackageName);
                synchronized (getTelecomSystem().getLock()) {
                    getTelecomSystem().getTelecomServiceImpl().setInitPath("legacy");
                    return getTelecomSystem().getTelecomServiceImpl().getBinder();
                }
            }
        };
    }

    /**
     * This method is to be called by components (Activitys, Services, ...) to initialize the
     * Telecom singleton. It should only be called on the main thread. As such, it is atomic
     * and needs no synchronization -- it will either perform its initialization, after which
     * the {@link TelecomSystem#getInstance()} will be initialized, or some other invocation of
     * this method on the main thread will have happened strictly prior to it, and this method
     * will be a benign no-op.
     *
     * @param context
     */
    static void initializeTelecomSystem(Context context, String sysUiPackageName) {
        if (TelecomSystem.getInstance() == null) {
            FeatureFlags featureFlags = new FeatureFlagsImpl();
            NotificationChannelManager notificationChannelManager =
                    new NotificationChannelManager();
            notificationChannelManager.createChannels(context);

            HandlerThread handlerThread = new HandlerThread("TelecomSystem");
            handlerThread.start();

            final VibratorManager vibratorManager =
                    context.getSystemService(VibratorManager.class);
            Ringer.VibratorAdapter vibratorAdapter = new Ringer.VibratorAdapter() {
                @Override
                public boolean hasVibrator() {
                    int[] vibratorIds = vibratorManager.getVibratorIds();
                    return vibratorIds != null && vibratorIds.length > 0;
                }

                @Override
                public void vibrate(VibrationEffect vibe, VibrationAttributes attributes) {
                    // This is what SystemVibrator does.
                    CombinedVibration combinedEffect = CombinedVibration.createParallel(vibe);
                    vibratorManager.vibrate(combinedEffect, attributes);
                }

                @Override
                public void cancel() {
                    vibratorManager.cancel();
                }

                @Override
                public Vibrator getVibrator() {
                    return vibratorManager.getDefaultVibrator();
                }
            };

            TelecomSystem.setInstance(
                    new TelecomSystem(
                            context,
                            new MissedCallNotifierImpl.MissedCallNotifierImplFactory() {
                                @Override
                                public MissedCallNotifierImpl makeMissedCallNotifierImpl(
                                        Context context,
                                        PhoneAccountRegistrar phoneAccountRegistrar,
                                        DefaultDialerCache defaultDialerCache,
                                        FeatureFlags featureFlags) {
                                    return new MissedCallNotifierImpl(context,
                                            phoneAccountRegistrar, defaultDialerCache,
                                            featureFlags);
                                }
                            },
                            new CallerInfoAsyncQueryFactory() {
                                @Override
                                public CallerInfoAsyncQuery startQuery(
                                        int token,
                                        Context context,
                                        String number,
                                        CallerInfoAsyncQuery.OnQueryCompleteListener listener,
                                        Object cookie) {
                                    Log.i(TelecomSystem.getInstance(),
                                            "CallerInfoAsyncQuery.startQuery number=%s cookie=%s",
                                            Log.pii(number), cookie);
                                    return CallerInfoAsyncQuery.startQuery(
                                            token, context, number, listener, cookie);
                                }
                            },
                            new HeadsetMediaButtonFactory() {
                                @Override
                                public HeadsetMediaButton create(
                                        Context context,
                                        CallsManager callsManager,
                                        TelecomSystem.SyncRoot lock) {
                                    return new HeadsetMediaButton(context, callsManager, lock);
                                }
                            },
                            new ProximitySensorManagerFactory() {
                                @Override
                                public ProximitySensorManager create(
                                        Context context,
                                        CallsManager callsManager) {
                                    return new ProximitySensorManager(
                                            new TelecomWakeLock(
                                                    context,
                                                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                                                    ProximitySensorManager.class.getSimpleName()),
                                            callsManager);
                                }
                            },
                            new InCallWakeLockControllerFactory() {
                                @Override
                                public InCallWakeLockController create(Context context,
                                        CallsManager callsManager) {
                                    return new InCallWakeLockController(
                                            new TelecomWakeLock(context,
                                                    PowerManager.FULL_WAKE_LOCK,
                                                    InCallWakeLockController.class.getSimpleName()),
                                            callsManager);
                                }
                            },
                            ConnectionServiceFocusManager::new,
                            new Timeouts.Adapter(),
                            new AsyncRingtonePlayer(featureFlags),
                            new PhoneNumberUtilsAdapterImpl(context),
                            new IncomingCallNotifier(context, featureFlags),
                            ToneGenerator::new,
                            new CallAudioRouteController.Factory(),
                            new CallAudioModeStateMachine.Factory(),
                            new ClockProxy() {
                                @Override
                                public long currentTimeMillis() {
                                    return System.currentTimeMillis();
                                }

                                @Override
                                public long elapsedRealtime() {
                                    return SystemClock.elapsedRealtime();
                                }
                            },
                            new RoleManagerAdapterImpl(context,
                                    (RoleManager) context.getSystemService(Context.ROLE_SERVICE)),
                            new ContactsAsyncHelper.Factory(),
                            sysUiPackageName,
                            TelecomManager.DEFAULT_TELECOM_UI_PACKAGE,
                            new Ringer.AccessibilityManagerAdapter() {
                                @Override
                                public boolean startFlashNotificationSequence(
                                        @androidx.annotation.NonNull Context context, int reason) {
                                    return context.getSystemService(AccessibilityManager.class)
                                            .startFlashNotificationSequence(context, reason);
                                }

                                @Override
                                public boolean stopFlashNotificationSequence(
                                        @androidx.annotation.NonNull Context context) {
                                    return context.getSystemService(AccessibilityManager.class)
                                            .stopFlashNotificationSequence(context);
                                }
                            },
                            Executors.newCachedThreadPool(),
                            Executors.newSingleThreadExecutor(),
                            new BlockedNumbersAdapter() {
                                @Override
                                public boolean shouldShowEmergencyCallNotification(Context
                                        context) {
                                    return featureFlags.telecomMainlineBlockedNumbersManager()
                                            ? context.getSystemService(BlockedNumbersManager.class)
                                            .shouldShowEmergencyCallNotification()
                                            : SystemBlockedNumberContract
                                                    .shouldShowEmergencyCallNotification(context);
                                }

                                @Override
                                public void updateEmergencyCallNotification(Context context,
                                        boolean showNotification) {
                                    BlockedNumbersUtil.updateEmergencyCallNotification(context,
                                            showNotification,
                                            TelecomManager.DEFAULT_TELECOM_UI_PACKAGE);
                                }
                            },
                            featureFlags,
                            new android.telecom.flags.FeatureFlagsImpl(),
                            new com.android.internal.telephony.flags.FeatureFlagsImpl(),
                            handlerThread.getLooper(),
                            vibratorAdapter));
        }
    }

    private void initTelecomUi(Context context) {
        final String telecomUiPackage = getTelecomUiPackageName();
        UserManager userManager = context.getSystemService(UserManager.class);
        if (userManager != null) {
            for (UserHandle userHandle : userManager.getUserHandles(true)) {
                enableTelecomUiForUser(context, userHandle, telecomUiPackage);
            }
        } else {
            Log.w(this, "UserManager is null, can't enable TelecomUi.");
        }

        BroadcastReceiver userAddedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (Intent.ACTION_USER_ADDED.equals(intent.getAction())) {
                    UserHandle userHandle = intent.getParcelableExtra(Intent.EXTRA_USER,
                        UserHandle.class);
                    enableTelecomUiForUser(receiverContext, userHandle, telecomUiPackage);
                }
            }
        };

        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_ADDED);
        context.getApplicationContext().registerReceiver(userAddedReceiver, filter,
                Context.RECEIVER_NOT_EXPORTED);
    }

    private void enableTelecomUiForUser(Context context, UserHandle userHandle,
            String telecomUiPackage) {
        if (userHandle == null) return;
        // Enable TelecomUi since the mainline module is active. Ensure that we create the
        // context for the calling user since we'll always be running under user 0 in Telecom.
        // This will account for other profiles and auto (where we run as user 10).
        Context userContext = context;
        try {
            userContext = context.createContextAsUser(userHandle, 0 /* flags */);
        } catch (Exception e) {
            Log.e(this, e, "Exception while creating context for user " + userHandle);
            return;
        }
        try {
            userContext.getPackageManager().setApplicationEnabledSetting(
                    telecomUiPackage,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            Log.i(this, "Successfully enabled TelecomUi for user " + userHandle);
        } catch (IllegalArgumentException e) {
            Log.e(this, e, "Failed to enable TelecomUi for user " + userHandle);
        }
    }

    private String getTelecomUiPackageName() {
        return "com.android.server.telecomui";
    }

    public TelecomSystem getTelecomSystem() {
        return TelecomSystem.getInstance();
    }
}
