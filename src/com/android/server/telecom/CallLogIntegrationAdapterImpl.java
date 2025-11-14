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
import java.util.stream.Collectors;

/**
 * Adapter to handle VoIP call log integration.
 */
public class CallLogIntegrationAdapterImpl implements CallLogIntegrationAdapter {

    public static final String SHARED_PREFERENCES_NAME = "voip_call_log_integration_prefs";
    private static final String TAG = CallLogIntegrationAdapterImpl.class.getSimpleName();
    private static final String SHARED_PREFERENCES_KEY = "voip_call_log_integration_key";
    private static final Intent CALLBACK_INTENT = new Intent(TelecomManager.ACTION_CALL_BACK);

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
            // corresponding maps to signal that an update is needed.
            if (doesPackageSupportCallback(packageName, userHandle)) {
                synchronized (mLock) {
                    Log.i(TAG, "VoIP package %s changed for user %s with intent %s", packageName,
                            userHandle, intent.getAction());
                    if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                        mPackagesToAdd.putIfAbsent(userHandle, new HashSet<>());
                        mPackagesToAdd.get(userHandle).add(packageName);
                    } else if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
                        mPackagesToRemove.putIfAbsent(userHandle, new HashSet<>());
                        mPackagesToRemove.get(userHandle).add(packageName);
                        removeLogEntries(packageName, userHandle);
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
        Context userContext = createUserContext(userHandle);
        if (userContext == null) {
            return false;
        }

        PackageManager packageManager = userContext.getPackageManager();
        Intent checkIntent = new Intent(TelecomManager.ACTION_CALL_BACK);
        checkIntent.setPackage(packageName);
        // Check if the package supports the callback
        List<ResolveInfo> resolveInfoList = packageManager.queryBroadcastReceivers(checkIntent, 0);
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
        packageChangedFilter.addDataScheme(IntentFilter.SCHEME_PACKAGE);
        Context allUsersContext = createUserContext(UserHandle.ALL);
        if (allUsersContext != null) {
            allUsersContext.registerReceiver(mPackageChangedReceiver, packageChangedFilter,
                    null, null);
        }
    }

    public CallLogIntegrationAdapterImpl(Context context, FeatureFlags featureFlags) {
        mContext = context;
        mFeatureFlags = featureFlags;
        registerPackageChangeReceiver();
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
        Context userContext = createUserContext(userHandle);
        if (userContext == null || TextUtils.isEmpty(packageName)) {
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
                removeLogEntries(packageName, userHandle);
            }
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
        Log.i(TAG, "getSupportedVoipCallLogIntegrationPackages: user %s",
                userHandle.getIdentifier());
        Context userContext = createUserContext(userHandle);
        if (userContext == null) {
            // Remove the user mapping if it exists.
            mEnabledPackageStates.remove(userHandle);
            return new HashMap<>(Collections.emptyMap());
        }

        synchronized (mLock) {
            // If we detect that the shared preferences mapping hasn't been defined yet or we
            // require a signal from the broadcast receiver that a package has been updated, then
            // update the mapping first.
            if (!mEnabledPackageStates.containsKey(userHandle)
                    || doSupportedPackagesNeedUpdate(userHandle)) {
                // Get all the packages for the user that have registered the ACTION_CALL_BACK
                // intent. This is queried from the broadcast receivers.
                Set<String> allSupportedPackages = getSupportedPackages(userContext, userHandle);

                // Try to load the pkg enabled states set for the user from the shared preferences
                // if it doesn't already exist in the map.
                loadSharedPrefForUser(userContext, userHandle);
                mEnabledPackageStates.putIfAbsent(userHandle, new HashMap<>());
                Map<String, Boolean> enabledPkgStatesForUser = mEnabledPackageStates.get(
                        userHandle);

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

            // Return a the map of package names to their enabled states. Return a copy to prevent
            // modification outside of the lock.
            return new HashMap<>(mEnabledPackageStates.get(userHandle));
        }
    }

    private Set<String> getSupportedPackages(Context userContext,
            UserHandle userHandle) {
        boolean performedQuery = false;
        Log.i(TAG, "Getting supported VoIP packages for user %s", userHandle);
        if (!mSupportedPackages.containsKey(userHandle)) {
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
    private Set<String> querySupportedPackages(Context userContext) {
        Log.i(TAG, "Querying supported VoIP packages from broadcast receivers");
        PackageManager packageManager = userContext.getPackageManager();
        List<ResolveInfo> resolveInfoList = packageManager
                .queryBroadcastReceivers(CALLBACK_INTENT, 0);

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
     * Loads the map from the shared preferences if the key exists for the user.
     */
    private void loadSharedPrefForUser(Context userContext, UserHandle userHandle) {
        // Skip if the map already contains a valid set for the user
        if (mEnabledPackageStates.containsKey(userHandle)) {
            return;
        }

        Log.i(TAG, "Loading shared preferences for user %s", userHandle);
        // Get the shared preference for the user if it exists. Otherwise, we can skip this process.
        SharedPreferences prefs = userContext.getSharedPreferences(
                SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        String pkgEnabledStatesFromPref = prefs.getString(SHARED_PREFERENCES_KEY, "");
        if (TextUtils.isEmpty(pkgEnabledStatesFromPref)) {
            return;
        }

        // Deserialize the string into the resulting set which can be added to the map.
        Map<String, Boolean> pkgEnabledStatesMap = Arrays
                .stream(pkgEnabledStatesFromPref.split(","))
                .map(s -> s.split(":"))
                .filter(entry -> entry.length == 2 && !TextUtils.isEmpty(entry[0]))
                .collect(Collectors.toMap(
                        entry -> entry[0],
                        entry -> Boolean.parseBoolean(entry[1])));
        mEnabledPackageStates.put(userHandle, pkgEnabledStatesMap);
    }

    /**
     * Update the SharedPreferences for the specified user by deserializing the map reference.
     * @param userContext The user context for which to update the SharedPreferences.
     * @param packageEnabledStates The VoIP package enabled states to store in SharedPreferences.
     */
    private void updateSharedPrefForUser(Context userContext,
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
    private void removeLogEntries(String packageName, UserHandle userHandle) {
        if (!mFeatureFlags.integratedCallLogsStage2()) {
            return;
        }
        Context userContext = createUserContext(userHandle);
        if (userContext == null) {
            userContext = mContext;
        }
        final String selection = CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME + " LIKE '"
                + packageName + "%'";
        Uri appendedUserUri = ContentProvider.createContentUriForUser(
                CallLog.Calls.CONTENT_URI_WITH_VOIP_CALLS, userHandle);
        Context finalUserContext = userContext;
        mExecutor.execute(() -> {
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

    private Context createUserContext(UserHandle userhandle) {
        try {
            return mContext.createContextAsUser(userhandle, 0);
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
