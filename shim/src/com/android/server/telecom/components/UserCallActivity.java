/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Activity that handles system CALL actions and forwards them to TelecomUi.
 */
public class UserCallActivity extends Activity {
    private static final String TAG = "UserCallActivity TelecomShim";
    private static final String ACTION_CALL_TRAMPOLINE =
            "com.android.internal.telecom.action.CALL_TRAMPOLINE";

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        Log.i(TAG, "Trampolining to TelecomUi");
        Intent intent = getIntent();
        Intent newIntent = new Intent(intent);
        newIntent.setAction(ACTION_CALL_TRAMPOLINE);
        newIntent.setComponent(new ComponentName("com.android.server.telecomui",
                "com.android.server.telecomui.components.UserCallActivity"));
        newIntent.setFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        startActivity(newIntent);
        finish();
    }
}
