/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.telecomui.settings;

import static org.mockito.Mockito.when;

import android.content.Context;
import android.database.Cursor;
import android.provider.BlockedNumberContract;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.telecomui.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class BlockedNumbersAdapterTest {

    private BlockedNumbersAdapter mAdapter;
    private Context mContext;
    @Mock private Cursor mCursor;

    @Rule
    public ActivityTestRule<TestActivity> mActivityRule =
            new ActivityTestRule<>(TestActivity.class);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = mActivityRule.getActivity();
        mAdapter = new BlockedNumbersAdapter(mContext, R.xml.layout_blocked_number, null,
                new String[]{}, new int[]{}, 0);
    }

    @Test
    public void testBindView_PhoneNumber() {
        View view = LayoutInflater.from(mContext).inflate(R.xml.layout_blocked_number, null);
        String number = "1234567890";

        int columnIndex = 1;
        when(mCursor.getColumnIndex(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER))
                .thenReturn(columnIndex);
        when(mCursor.getString(columnIndex)).thenReturn(number);

        mAdapter.bindView(view, mContext, mCursor);

        TextView textView = view.findViewById(R.id.blocked_number);
        // Verify text contains number (might be formatted)
        org.junit.Assert.assertTrue(textView.getText().toString().contains(number));
    }

    @Test
    public void testBindView_Email() {
        View view = LayoutInflater.from(mContext).inflate(R.xml.layout_blocked_number, null);
        String email = "test@example.com";

        int columnIndex = 1;
        when(mCursor.getColumnIndex(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER))
                .thenReturn(columnIndex);
        when(mCursor.getString(columnIndex)).thenReturn(email);

        mAdapter.bindView(view, mContext, mCursor);

        TextView textView = view.findViewById(R.id.blocked_number);
        org.junit.Assert.assertEquals(email, textView.getText().toString());
    }

    @Test
    public void testDeleteButtonClick() throws Throwable {
        View view = LayoutInflater.from(mContext).inflate(R.xml.layout_blocked_number, null);
        String number = "12345";
        int columnIndex = 1;
        when(mCursor.getColumnIndex(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER))
                .thenReturn(columnIndex);
        when(mCursor.getString(columnIndex)).thenReturn(number);

        mAdapter.bindView(view, mContext, mCursor);

        View deleteButton = view.findViewById(R.id.delete_blocked_number);
        mActivityRule.runOnUiThread(() -> {
            deleteButton.performClick();
        });
    }
}