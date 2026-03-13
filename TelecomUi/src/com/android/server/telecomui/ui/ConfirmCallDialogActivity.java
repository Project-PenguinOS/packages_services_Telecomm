/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.telecomui.ui;

import com.android.server.telecomui.R;
import com.android.server.telecom.ui.Constants;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Dialog activity used when there is an ongoing self-managed call and the user initiates a new
 * outgoing managed call.  The dialog prompts the user to see if they want to disconnect the ongoing
 * self-managed call in order to place the new managed call.
 */
public class ConfirmCallDialogActivity extends Activity {
    private static final String TAG = ConfirmCallDialogActivity.class.getSimpleName();
    public static final String EXTRA_OUTGOING_CALL_ID = "android.telecom.extra.OUTGOING_CALL_ID";
    public static final String EXTRA_ONGOING_APP_NAME = "android.telecom.extra.ONGOING_APP_NAME";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String callId = getIntent().getStringExtra(EXTRA_OUTGOING_CALL_ID);
        final CharSequence ongoingAppName = getIntent().getCharSequenceExtra(
                EXTRA_ONGOING_APP_NAME);
        showDialog(callId, ongoingAppName);
    }

    private void showDialog(final String callId, CharSequence ongoingAppName) {
        Log.i(TAG, String.format("showDialog: confirming callId=%s, ongoing=%s", callId,
                ongoingAppName));
        CharSequence message;
        if (ongoingAppName == null) {
            message = getString(R.string.alert_outgoing_call_null_app_name);
        } else {
            message = getString(R.string.alert_outgoing_call, ongoingAppName);
        }
        final AlertDialog errorDialog = new AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent proceedWithCall = new Intent(Constants.ACTION_PROCEED_WITH_CALL);
                        proceedWithCall.setPackage(Constants.TELECOM_PACKAGE);
                        proceedWithCall.putExtra(EXTRA_OUTGOING_CALL_ID, callId);
                        sendBroadcast(proceedWithCall, Constants.TELECOM_UI_ACCESS_PERMISSION);
                        dialog.dismiss();
                        finish();
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent cancelCall = new Intent(Constants.ACTION_CANCEL_CALL);
                        cancelCall.setPackage(Constants.TELECOM_PACKAGE);
                        cancelCall.putExtra(EXTRA_OUTGOING_CALL_ID, callId);
                        sendBroadcast(cancelCall, Constants.TELECOM_UI_ACCESS_PERMISSION);
                        dialog.dismiss();
                        finish();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        Intent cancelCall = new Intent(Constants.ACTION_CANCEL_CALL);
                        cancelCall.setPackage(Constants.TELECOM_PACKAGE);
                        cancelCall.putExtra(EXTRA_OUTGOING_CALL_ID, callId);
                        sendBroadcast(cancelCall, Constants.TELECOM_UI_ACCESS_PERMISSION);
                        dialog.dismiss();
                        finish();
                    }
                })
                .create();

        errorDialog.show();
    }
}
