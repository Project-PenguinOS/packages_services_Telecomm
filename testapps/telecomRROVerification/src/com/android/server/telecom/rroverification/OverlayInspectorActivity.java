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

package com.android.server.telecom.rroverification;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OverlayInspectorActivity extends AppCompatActivity {

    private static final String TAG = "OverlayInspectorActivity";
    private LinearLayout mContainer;
    private static final String GOOGLE_OVERLAY_PREFIX = "com." + "google.pixel.telecom.overlay";
    private static final String PKG_UI_CUSTOM = GOOGLE_OVERLAY_PREFIX + ".ui";
    private static final String PKG_UI_LIB    = GOOGLE_OVERLAY_PREFIX + ".uilib";
    private static final String PKG_SERVICE   = GOOGLE_OVERLAY_PREFIX + ".service";

    private static final String[] OVERLAY_PACKAGES = {
            PKG_UI_CUSTOM,
            PKG_UI_LIB,
            PKG_SERVICE
    };

    private static final String TARGET_UI_PKG = "com.android.server.telecomui";
    private static final String TARGET_SERVICE_PKG = "com.android.server.telecom";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        mContainer = new LinearLayout(this);
        mContainer.setOrientation(LinearLayout.VERTICAL);
        mContainer.setPadding(32, 300, 32, 32);
        scrollView.addView(mContainer);
        setContentView(scrollView);

        new Thread(() -> {
            for (String overlayPkg : OVERLAY_PACKAGES) {
                checkOverlayPackage(overlayPkg);
            }
        }).start();
    }

    private void checkOverlayPackage(String overlayPkg) {
        String displayName = getDisplayName(overlayPkg);
        runOnUiThread(() -> addHeader(displayName));

        List<String> mappedResources = getOverlayMappings(overlayPkg);

        if (mappedResources.isEmpty()) {
            runOnUiThread(() -> addErrorItem("Status", "No resources mapped (Check [x] state)"));
            return;
        }

        String targetPkg = overlayPkg.endsWith("service") ? TARGET_SERVICE_PKG : TARGET_UI_PKG;

        try {
            Context targetContext = createPackageContext(targetPkg, 0);
            Resources targetRes = targetContext.getResources();

            for (String resName : mappedResources) {
                String[] parts = resName.split("/");
                String type = parts[0];
                String name = parts[1];

                int id = targetRes.getIdentifier(name, type, targetPkg);
                if (id != 0) {
                    String valueStr = getValueAsString(targetRes, id, type);
                    runOnUiThread(() -> {
                        addResultItem(resName, valueStr);
                        if ("color".equals(type)) {
                            try {
                                addColorPreview(targetRes.getColor(id, null));
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to add color preview", e);
                            }
                        }
                    });
                } else {
                    runOnUiThread(() -> addErrorItem(resName, "ID Not Found"));
                }
            }

        } catch (Exception e) {
            runOnUiThread(() -> addErrorItem("Target Context", "Failed to load: " + targetPkg));
        }
    }

    private List<String> getOverlayMappings(String overlayPkg) {
        List<String> results = new ArrayList<>();
        try {
            Process process = Runtime.getRuntime().exec("cmd overlay dump " + overlayPkg);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream()));
            String line;

            Pattern pattern = Pattern.compile("\\((.*?)\\s->\\s(.*?)\\)");
            while ((line = reader.readLine()) != null) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    results.add(matcher.group(1));
                }
            }
            reader.close();
            process.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "Failed to dump overlay mappings", e);
        }
        return results;
    }

    private String getValueAsString(Resources res, int id, String type) {
        try {
            return switch (type) {
                case "string" -> res.getString(id);
                case "color" -> String.format("#%06X", (0xFFFFFF & res.getColor(id, null)));
                case "bool" -> String.valueOf(res.getBoolean(id));
                case "dimen" -> {
                    try {
                        yield res.getDimension(id) + " px";
                    } catch (Exception e) {
                        yield String.valueOf(res.getFloat(id));
                    }
                }
                case "integer" -> String.valueOf(res.getInteger(id));
                default -> res.getString(id);
            };
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // --- UI Helpers ---
    private void addHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText("\n[" + title + "]");
        tv.setTextSize(16);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(Color.DKGRAY);
        mContainer.addView(tv);
    }

    private void addResultItem(String key, String value) {
        TextView tv = new TextView(this);
        SpannableStringBuilder builder = new SpannableStringBuilder();

        String keyPart = "  " + key + ":\n";
        builder.append(keyPart);
        builder.setSpan(new ForegroundColorSpan(Color.BLACK), 0, keyPart.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        String valuePart = "   " + value;
        int start = builder.length();
        builder.append(valuePart);

        builder.setSpan(new ForegroundColorSpan(Color.BLUE), start, builder.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new StyleSpan(Typeface.BOLD), start, builder.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        tv.setText(builder);
        tv.setTextSize(16);
        tv.setPadding(0, 16, 0, 16);
        mContainer.addView(tv);
    }

    private void addErrorItem(String key, String msg) {
        TextView tv = new TextView(this);
        tv.setText("  " + key + " : " + msg);
        tv.setTextSize(14);
        tv.setTextColor(Color.RED);
        mContainer.addView(tv);
    }

    private void addColorPreview(int color) {
        View v = new View(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(100, 50);
        params.setMargins(40, 0, 0, 16);
        v.setLayoutParams(params);
        v.setBackgroundColor(color);
        mContainer.addView(v);
    }

    private String getDisplayName(String pkgName) {
        if (pkgName.equals(PKG_UI_CUSTOM)) {
            return "Overlay: TelecomUi";
        } else if (pkgName.equals(PKG_UI_LIB)) {
            return "Overlay: TelecomUitoLib(String.xml)";
        } else if (pkgName.equals(PKG_SERVICE)) {
            return "Overlay: TelecomLib";
        }
        return "Overlay: " + pkgName;
    }
}
