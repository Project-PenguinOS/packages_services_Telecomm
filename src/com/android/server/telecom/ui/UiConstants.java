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

package com.android.server.telecom.ui;

/**
 * Constants used to create implicit intents that are handled by the UI module.
 */
public class UiConstants {
    /**
     * The package name for the Telecom UI module.
     */
    public static final String TELECOM_UI_PACKAGE = "com.android.server.telecomui";

    /**
     * Component names for explicit intents.
     * We use string constants because the classes are in a separate module and cannot be imported.
     */
    public static final String COMPONENT_ERROR_DIALOG =
        "com.android.server.telecomui.components.ErrorDialogActivity";

    public static final String COMPONENT_CALL_REDIRECTION_TIMEOUT_DIALOG =
        "com.android.server.telecomui.ui.CallRedirectionTimeoutDialogActivity";

    public static final String COMPONENT_CONFIRM_CALL_DIALOG =
        "com.android.server.telecomui.ui.ConfirmCallDialogActivity";

    public static final String COMPONENT_TELECOM_DEVELOPER_MENU =
        "com.android.server.telecomui.ui.TelecomDeveloperMenu";

    public static final String COMPONENT_CALL_BLOCK_DISABLED_DIALOG =
        "com.android.server.telecomui.settings.CallBlockDisabledActivity";

    /**
     * Extras and errors.
     */
    public static final String ERROR_MESSAGE_ID_EXTRA = "error_message_id";
    public static final String ERROR_MESSAGE_STRING_EXTRA = "error_message_string";
    public static final String EXTRA_ONGOING_APP_NAME = "android.telecom.extra.ONGOING_APP_NAME";
    public static final String EXTRA_OUTGOING_CALL_ID = "android.telecom.extra.OUTGOING_CALL_ID";
    public static final String EXTRA_REDIRECTION_APP_NAME =
        "android.telecom.extra.REDIRECTION_APP_NAME";
}