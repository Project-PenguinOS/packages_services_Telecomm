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
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.CallLog;
import android.telecom.Log;
import android.telecom.TelecomManager;
import android.text.TextUtils;

import androidx.annotation.VisibleForTesting;

import com.android.server.telecom.flags.FeatureFlags;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Adapter to handle VoIP call log integration.
 */
public class CallLogIntegrationAdapterImpl implements CallLogIntegrationAdapter {

    public static final String SHARED_PREFERENCES_NAME = "voip_call_log_integration_prefs";
    private static final String TAG = CallLogIntegrationAdapterImpl.class.getSimpleName();
    private static final String SHARED_PREFERENCES_KEY = "voip_call_log_integration_key";
    // TODO(b/469253692): This constant is hidden in IntentFilter.
    // Redefined locally to remove hidden API dependency.
    private static final String SCHEME_PACKAGE = "package";
    private static final Intent CALLBACK_INTENT = new Intent(TelecomManager.ACTION_CALL_BACK);
    // We may need to force load from the shared preferences, i.e. when performing restore from
    // backup. The local cache may need to be invalidated in this case.
    private static final AtomicBoolean sForceSharedPrefLoading = new AtomicBoolean(true);

    private final Context mContext;
    // Store the enabled state for each supported VoIP package per user. This is used keeping track
    // of updates in the shared preferences for each user.
    private final Map<UserHandle, Map<String, Boolean>> mEnabledPackageStates = new HashMap<>();
    // All the packages supported for VoIP call log integration as reported by querying the
    // broadcast receiver.
    private final Map<UserHandle, Set<String>> mSupportedPackages = new HashMap<>();
    // The following maps are used to keep track of package updates (whether a new package was
    // added or removed) for apps registering the ACTION_CALL_BACK intent. We use this caching
    // mechanism to lazily update the SharedPreferences for each user only when Settings queries
    // for the list via the overridden getter method.
    private final Map<UserHandle, Set<String>> mPackagesToAdd = new HashMap<>();
    private final Map<UserHandle, Set<String>> mPackagesToRemove = new HashMap<>();
    // Needed to synchronize access to the above maps. Note that using a concurrent DS like
    // ConcurrentHashMap does not guarantee the atomicity of the operations being performed in
    // getSupportedVoipCallLogIntegrationPackages and setVoipPackageCallLogIntegrationEnabled. We
    // also don't need to use a concurrent DS on top of a lock as it adds unnecessary overhead and
    // adds confusion as to why both practices are being implemented.
    private final Object mLock = new Object();
    private final Executor mExecutor = Executors.newSingleThreadExecutor();
    private final FeatureFlags mFeatureFlags;

    /**
     * Receiver to detect when packages are added or removed. It is filtered to only packages that
     * have defined the ACTION_CALL_BACK intent.
     */
    private final BroadcastReceiver mPackageChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null || intent.getData() == null) {
                return;
            }

            String packageName = intent.getData().getSchemeSpecificPart();
            if (TextUtils.isEmpty(packageName)) {
                return;
            }

            final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
            if (uid == -1) {
                return;
            }
            final UserHandle userHandle = UserHandle.getUserHandleForUid(uid);

            // Check that the package received supports the callback intent before adding it to
            // corresponding maps to signal that an update is needed. We may also need to see if the
            // package is stored in our internal cache in the case that the application was
            // uninstalled.
            boolean containsCachedPackage = mEnabledPackageStates.containsKey(userHandle)
                    && mEnabledPackageStates.get(userHandle).containsKey(packageName);
            if (doesPackageSupportCallback(packageName, userHandle) || containsCachedPackage) {
                synchronized (mLock) {
                    Log.i(TAG, "VoIP package %s changed for user %s with intent %s", packageName,
                            userHandle, intent.getAction());
                    if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                        mPackagesToAdd.putIfAbsent(userHandle, new HashSet<>());
                        mPackagesToAdd.get(userHandle).add(packageName);
                    } else if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
                        mPackagesToRemove.putIfAbsent(userHandle, new HashSet<>());
                        mPackagesToRemove.get(userHandle).add(packageName);
                        removeLogEntries(mContext, packageName, userHandle, mExecutor);
                    }
                }
            } else {
                Log.d(TAG, "Ignoring package change for %s as it is not a relevant VoIP app.",
                        packageName);
            }
        }
    };

    /**
     * Checks if a given package has registered a broadcast receiver for
     * TelecomManager.ACTION_CALL_BACK for a specific user.
     *
     * @param packageName The package to check.
     * @param userHandle The user for which to check.
     * @return {@code true} if the package is relevant, {@code false} otherwise.
     */
    private boolean doesPackageSupportCallback(String packageName, UserHandle userHandle) {
        Context userContext = createUserContext(mContext, userHandle);
        if (userContext == null) {
            return false;
        }

        PackageManager packageManager = userContext.getPackageManager();
        Intent checkIntent = new Intent(TelecomManager.ACTION_CALL_BACK);
        checkIntent.setPackage(packageName);
        // Check if the package supports the callback
        List<ResolveInfo> resolveInfoList = packageManager.queryIntentActivities(checkIntent,
                PackageManager.MATCH_ALL);
        return !resolveInfoList.isEmpty();
    }

    /**
     * Register the package changed receiver to detect when packages are added or removed for all
     * users.
     */
    private void registerPackageChangeReceiver() {
        IntentFilter packageChangedFilter = new IntentFilter();
        packageChangedFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageChangedFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageChangedFilter.addDataScheme(SCHEME_PACKAGE);
        Context allUsersContext = createUserContext(mContext, UserHandle.ALL);
        if (allUsersContext != null) {
            allUsersContext.registerReceiver(mPackageChangedReceiver, packageChangedFilter,
                    null, null);
        }
    }

    public CallLogIntegrationAdapterImpl(Context context, FeatureFlags featureFlags) {
        mContext = context;
        mFeatureFlags = featureFlags;
        registerPackageChangeReceiver();
        initCallLogPreferencesForRunningUsers();
    }

    public void initCallLogPreferencesForRunningUsers() {
        // Populate the initial cache with all the running users
        UserManager userManager = mContext.getSystemService(UserManager.class);
        List<UserHandle> runningUsers = userManager.getUserHandles(true /* excludeDying */);
        for (UserHandle user: runningUsers) {
            // This will create the internal map from the SharedPreferences for the user and also
            // notify apps, upon init, for disabled prefs.
            getSupportedVoipCallLogIntegrationPackages(user);
        }
    }

    /**
     * Sets the enabled state for VoIP call log integration for a specific app and user. An enabled
     * app is allowed to integrate its calls into the system call log.
     *
     * @param userHandle The user for whom the setting is being changed.
     * @param packageName The package name to update.
     * @param isEnabled The new enabled state.
     */
    @Override
    public void setVoipPackageCallLogIntegrationEnabled(UserHandle userHandle, String packageName,
            boolean isEnabled) {
        Context userContext = createUserContext(mContext, userHandle);
        if (userContext == null || TextUtils.isEmpty(packageName)
                || !android.telecom.flags.Flags.integratedCallLogsStage2()) {
            return;
        }

        synchronized (mLock) {
            // This operates on the fact that if the user is toggling the enabled state from
            // settings (as they should), then getSupportedVoipPackages() will always run first and
            // populate the pkg enabled states into the map for the given user. We should never
            // receive an invocation to the setter before the getter is invoked.
            if (!mEnabledPackageStates.containsKey(userHandle)) {
                return;
            }
            Map<String, Boolean> enabledPackageStates = mEnabledPackageStates.get(userHandle);
            if (!enabledPackageStates.containsKey(packageName)
                    || enabledPackageStates.get(packageName) == isEnabled) {
                Log.w(TAG, "Package %s is not available for user %s", packageName, userHandle);
                return;
            }

            // Update the map with the new enabled state.
            mEnabledPackageStates.get(userHandle).put(packageName, isEnabled);
            // Update the corresponding SharedPreferences for the user.
            updateSharedPrefForUser(userContext, mEnabledPackageStates.get(userHandle));
            // Ensure that we remove log entries if app integration is disabled by the user.
            if (!isEnabled) {
                removeLogEntries(mContext, packageName, userHandle, mExecutor);
            }
            // Notify the app of the new preference change.
            notifyAppOfPreferenceChange(mContext, userHandle, packageName, isEnabled);
        }
    }

    /**
     * Retrieves a map of the app's package names that have registered a broadcast receiver
     * for the TelecomManager.ACTION_CALL_BACK intent to the enabled state of if user has allowed
     * the app to integrate its call logs into the system call log for a specific user.
     *
     * @param userHandle The user for which to query the packages and their enabled states.
     * @return A map containing the app package names to their enabled states.
     */
    @Override
    public Map<String, Boolean> getSupportedVoipCallLogIntegrationPackages(UserHandle userHandle) {
        if (!android.telecom.flags.Flags.integratedCallLogsStage2()) {
            return new HashMap<>();
        }
        Log.i(TAG, "getSupportedVoipCallLogIntegrationPackages: user %s",
                userHandle.getIdentifier());
        Context userContext = createUserContext(mContext, userHandle);
        if (userContext == null) {
            // Remove the user mapping if it exists.
            mEnabledPackageStates.remove(userHandle);
            return new HashMap<>();
        }

        synchronized (mLock) {
            // If we detect that the shared preferences mapping hasn't been defined yet or we
            // require a signal from the broadcast receiver that a package has been updated, then
            // update the mapping first.

            // Todo: Replace with `!mEnabledPackageStates.containsKey(userHandle)
            //  || doSupportedPackagesNeedUpdate(userHandle)` once apps have released versions of
            //  call log integration support. For testing purposes, this becomes difficult to verify
            // since we may need to overlay test apks with released versions. In the case of Meet,
            // we cannot uninstall the app so when we initialize the prefs upon reboot, the pref
            // will always be disabled and will not be changed if we don't force an update.
            boolean shouldUpdate = true;
            if (shouldUpdate) {
                boolean preferenceStateNotDefined = !mEnabledPackageStates.containsKey(userHandle);
                // Get all the packages for the user that have registered the ACTION_CALL_BACK
                // intent. This is queried from the broadcast receivers.
                Set<String> allSupportedPackages = getSupportedPackages(userContext, userHandle,
                        shouldUpdate);
                // Try to load the pkg enabled states set for the user from the shared preferences
                // if it doesn't already exist in the map.
                loadSharedPrefForUser(userContext, userHandle);
                Map<String, Boolean> enabledPkgStatesForUser = mEnabledPackageStates.get(
                        userHandle);
                // Update the existing shared preferences from the packages retrieved from the
                // broadcast query.
                pruneSharedPreferences(userContext, enabledPkgStatesForUser, allSupportedPackages);
                // If this is the first time loading from SharedPreferences, notify all apps what
                // whose preference is disabled by the user. By default, the preference is enabled
                // so we can notify the app only if it got disabled.
                if (preferenceStateNotDefined) {
                    // Filter by disabled package prefs.
                    Map<String, Boolean> disabledPkgPrefs = enabledPkgStatesForUser
                            .entrySet().stream().filter(entry -> !entry.getValue())
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                    notifyAppsOfPreferenceChangeForUser(mContext, userHandle, mExecutor,
                            disabledPkgPrefs);
                }
            }

            // Return a the map of package names to their enabled states. Return a copy to prevent
            // modification outside of the lock.
            return new HashMap<>(mEnabledPackageStates.get(userHandle));
        }
    }

    /**
     * Returns whether the given package is enabled (by the user) for integrating its logs into the
     * system call log.
     * @return {@code true} if the package can integrate its call logs, {@code false} otherwise.
     */
    @Override
    public boolean isCallLogPrefEnabledForPackage(UserHandle userHandle, String packageName) {
        Map<String, Boolean> supportedPackagesForUser =
                getSupportedVoipCallLogIntegrationPackages(userHandle);
        return supportedPackagesForUser.containsKey(packageName)
                && supportedPackagesForUser.get(packageName);
    }

    /**
     * Prunes the shared preferences by cross-checking against the broadcast query for the packages.
     * Updates the existing shared preferences if there was a change.
     */
    private static void pruneSharedPreferences(Context userContext,
            Map<String, Boolean> enabledPkgStatesForUser, Set<String> allSupportedPackages) {
        boolean isUpdated = false;
        // Prune the map and remove any old references (apps may have been uninstalled).
        isUpdated |= enabledPkgStatesForUser.entrySet().removeIf(
                entry -> !allSupportedPackages.contains(entry.getKey()));

        // Go through each supported package and add it into the map if it's not already
        // present in the set.
        for (String pkgName : allSupportedPackages) {
            // Add the entry if it doesn't exist and set the default enabled state to true.
            // Note here that PackageEnabledState only considers the package name when
            // comparing for equality.
            if (enabledPkgStatesForUser.putIfAbsent(pkgName, true) == null) {
                isUpdated = true;
            }
        }

        // Persist the data only if there was an update.
        if (isUpdated) {
            updateSharedPrefForUser(userContext, enabledPkgStatesForUser);
        }
    }

    private Set<String> getSupportedPackages(Context userContext,
            UserHandle userHandle, boolean shouldUpdate) {
        boolean performedQuery = false;
        Log.i(TAG, "Getting supported VoIP packages for user %s", userHandle);
        if (!mSupportedPackages.containsKey(userHandle) || shouldUpdate) {
            mSupportedPackages.put(userHandle, querySupportedPackages(userContext));
            performedQuery = true;
        }
        Set<String> supportedPackages = mSupportedPackages.get(userHandle);
        // If we already performed a manual query, no need to look at packages added/removed since
        // those should already be accounted for when querying the broadcast receivers.
        if (!performedQuery) {
            if (mPackagesToAdd.containsKey(userHandle)) {
                supportedPackages.addAll(mPackagesToAdd.get(userHandle));
            }
            if (mPackagesToRemove.containsKey(userHandle)) {
                supportedPackages.removeAll(mPackagesToRemove.get(userHandle));
            }
        }
        // Clear the maps to signal that we finished processing the updates.
        mPackagesToAdd.remove(userHandle);
        mPackagesToRemove.remove(userHandle);
        return supportedPackages;
    }

    /**
     * Manually query the supported packages for the user from the pkg manager.
     */
    private static Set<String> querySupportedPackages(Context userContext) {
        Log.i(TAG, "Querying supported VoIP packages");
        PackageManager packageManager = userContext.getPackageManager();
        List<ResolveInfo> resolveInfoList = packageManager
                .queryIntentActivities(CALLBACK_INTENT, PackageManager.MATCH_ALL);

        // Extract the package name from each ResolveInfo and return as a set.
        return resolveInfoList.stream()
                .map(resolveInfo -> {
                    if (resolveInfo.activityInfo != null) {
                        return resolveInfo.activityInfo.packageName;
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * Loads the map from the shared preferences if the key exists for the user. Otherwise, a new
     * {key, value} pair is inserted.
     */
    private void loadSharedPrefForUser(Context userContext, UserHandle userHandle) {
        // Skip if the map already contains a valid set for the user and a force update is not
        // required.
        if (mEnabledPackageStates.containsKey(userHandle)
                && !sForceSharedPrefLoading.compareAndSet(true, false)) {
            return;
        }
        Map<String, Boolean> pkgEnabledStatesMap = getSharedPrefsForUser(userContext, userHandle);
        mEnabledPackageStates.put(userHandle, pkgEnabledStatesMap);
    }

    /**
     * Static helper to retrieve the SharedPreferences for the user.
     */
    public static Map<String, Boolean> getSharedPrefsForUser(Context userContext,
            UserHandle userHandle ) {
        Log.i(TAG, "Loading shared preferences for user %s", userHandle);
        // Get the shared preference for the user if it exists. Otherwise, we can skip this process.
        SharedPreferences prefs = userContext.getSharedPreferences(
                SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        String pkgEnabledStatesFromPref = prefs.getString(SHARED_PREFERENCES_KEY, "");
        if (TextUtils.isEmpty(pkgEnabledStatesFromPref)) {
            return new HashMap<>();
        }

        // Deserialize the string into the resulting set which can be added to the map.
        return Arrays.stream(pkgEnabledStatesFromPref.split(","))
                .map(s -> s.split(":"))
                .filter(entry -> entry.length == 2 && !TextUtils.isEmpty(entry[0]))
                .collect(Collectors.toMap(
                        entry -> entry[0],
                        entry -> Boolean.parseBoolean(entry[1])));
    }

    /**
     * Update the SharedPreferences for the specified user by deserializing the map reference.
     * @param userContext The user context for which to update the SharedPreferences.
     * @param packageEnabledStates The VoIP package enabled states to store in SharedPreferences.
     */
    private static void updateSharedPrefForUser(Context userContext,
            Map<String, Boolean> packageEnabledStates) {
        Log.i(TAG, "Updating shared preferences.");
        // Get the SharedPreferences file for userHandle
        SharedPreferences prefs = userContext.getSharedPreferences(
                SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        // Serialize the map entries and apply the update asynchronously
        String updatedPackagesEnabledStates = packageEnabledStates
                .entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.joining(","));

        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(SHARED_PREFERENCES_KEY, updatedPackagesEnabledStates);
        editor.apply();
    }

    /**
     * Remove the existing system call log entries for the specified package and user.
     * @param packageName The package name for the VoIP application.
     * @param userHandle The {@link UserHandle} that we should remove the entries for .
     */
    private static void removeLogEntries(Context context, String packageName, UserHandle userHandle,
            Executor executor) {
        if (!android.telecom.flags.Flags.integratedCallLogsStage2()) {
            return;
        }
        Context userContext = createUserContext(context, userHandle);
        if (userContext == null) {
            userContext = context;
        }
        final String selection = CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME + " LIKE '"
                + packageName + "%'";
        Uri appendedUserUri = ContentProvider.createContentUriForUser(
                CallLog.Calls.CONTENT_URI_WITH_VOIP_CALLS, userHandle);
        Context finalUserContext = userContext;
                executor.execute(() -> {
            try {
                int rowsDeleted = finalUserContext.getContentResolver().delete(appendedUserUri,
                        selection, null);
                Log.d(TAG, "Deleted %d VoIP call log entries for %s", rowsDeleted, packageName);
            } catch (Exception e) {
                Log.e(TAG, e, "Error clearing VoIP call log entries for %s", packageName);
            }
        });
    }

    private boolean doSupportedPackagesNeedUpdate(UserHandle userHandle) {
        return mPackagesToAdd.containsKey(userHandle) || mPackagesToRemove.containsKey(userHandle);
    }

    private static void notifyAppsOfPreferenceChangeForUser(Context context, UserHandle userHandle,
            Executor executor, Map<String, Boolean> preferences) {
        if (!android.telecom.flags.Flags.integratedCallLogsStage2()) {
            return;
        }
        for (Map.Entry<String, Boolean> entry : preferences.entrySet()) {
            String packageName = entry.getKey();
            Boolean enabledState = entry.getValue();
            notifyAppOfPreferenceChange(context, userHandle, packageName, enabledState);
            // Ensure entries are deleted if we load in the shared prefs for the first
            // time and the preference is disabled.
            if (!enabledState) {
                removeLogEntries(context, packageName, userHandle, executor);
            }
        }
    }

    private static void notifyAppOfPreferenceChange(Context context, UserHandle userHandle,
            String pkgName, boolean enabled) {
        if (!android.telecom.flags.Flags.integratedCallLogsStage2()) {
            return;
        }
        Context userContext = createUserContext(context, userHandle);
        if (userContext == null) {
            userContext = context;
        }
        Log.d(TAG, "Notifying package %s of preference change(%b) for user %s", pkgName,
                enabled, userHandle);
        Intent intent = new Intent(TelecomManager.ACTION_VOIP_CALL_LOG_PREFERENCE);
        intent.setPackage(pkgName);
        intent.putExtra(TelecomManager.EXTRA_VOIP_CALL_LOG_PREFERENCE_STATUS, enabled);
        userContext.sendBroadcast(intent);
    }

    public static void handleNotifyAppsOfPreferenceOnRestore(Context context, UserHandle userHandle,
            Executor executor) {
        Context userContext = createUserContext(context, userHandle);
        if (userContext == null) {
            userContext = context;
        }
        Map<String, Boolean> sharedPrefsForUser = getSharedPrefsForUser(userContext, userHandle);
        // Next time the local cache is checked, make sure we invalidate it so that it's properly
        // updated with the potentially new shared preferences that were restored from the backup.
        if (!sharedPrefsForUser.isEmpty()) {
            sForceSharedPrefLoading.set(true);
        }
        Set<String> supportedPackages = querySupportedPackages(userContext);
        // This will modify the existing sharedPrefsForUser map.
        pruneSharedPreferences(userContext, sharedPrefsForUser, supportedPackages);
        // Now notify all apps for the preferences. We cannot ignore packages that have enabled
        // prefs since the device restoring may have different data from the backup.
        notifyAppsOfPreferenceChangeForUser(userContext, userHandle, executor, sharedPrefsForUser);
    }

    private static Context createUserContext(Context context, UserHandle userhandle) {
        try {
            return context.createContextAsUser(userhandle, 0);
        } catch (IllegalStateException e) {
            Log.e(TAG, e, "Error while creating context as user = %s", userhandle);
            return null;
        }
    }

    @VisibleForTesting
    public Map<UserHandle, Set<String>> getPackagesToAdd() {
        return mPackagesToAdd;
    }

    @VisibleForTesting
    public Map<UserHandle, Set<String>> getPackagesToRemove() {
        return mPackagesToRemove;
    }

    @VisibleForTesting
    public BroadcastReceiver getPackageChangedReceiver() {
        return mPackageChangedReceiver;
    }
}
