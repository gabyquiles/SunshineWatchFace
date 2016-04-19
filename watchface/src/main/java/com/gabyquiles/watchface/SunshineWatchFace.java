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

package com.gabyquiles.watchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        boolean mAmbient;
        Calendar mCalendar;
        Date mDate;
        int mHighTemp;
        int mLowTemp;
        final BroadcastReceiver mDataReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getTimeZone(intent.getStringExtra("time-zone")));
            }
        };
        int mTapCount;

        float mHighlightSize;
        float mStandardSize;

        private int mSpecW;
        private int mSpecH;
        private final Point displaySize = new Point();

        View mRootView;
        TextView mTimeView;
        TextView mDateView;
        TextView mHighView;
        TextView mLowView;


        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        SimpleDateFormat mDateFormat;

        private void initFormats() {
            mDateFormat = new SimpleDateFormat(getResources().getString(R.string.date_format), Locale.getDefault());
            mDateFormat.setCalendar(mCalendar);
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.primary));

            LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mRootView = inflater.inflate(R.layout.watchface, null);

            mTimeView = (TextView) mRootView.findViewById(R.id.timeTextView);
            mDateView = (TextView) mRootView.findViewById(R.id.dateTextView);
            mHighView = (TextView) mRootView.findViewById(R.id.maxTempTextView);
            mLowView = (TextView) mRootView.findViewById(R.id.minTempTextView);


            mTimeView.setLayerPaint(mTextPaint);
            mDateView.setLayerPaint(mTextPaint);
            mHighView.setLayerPaint(mTextPaint);
            mLowView.setLayerPaint(mTextPaint);

            Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay();
            display.getSize(displaySize);

            mSpecW = View.MeasureSpec.makeMeasureSpec(displaySize.x,
                    View.MeasureSpec.EXACTLY);
            mSpecH = View.MeasureSpec.makeMeasureSpec(displaySize.y,
                    View.MeasureSpec.EXACTLY);

            mCalendar = Calendar.getInstance();
            mDate = new Date();
            initFormats();

//            TODO: Remove these
            mHighTemp = 25;
            mLowTemp = 16;

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                long now = System.currentTimeMillis();
                mCalendar.setTimeInMillis(now);
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mDataReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mDataReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mHighlightSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            mStandardSize = resources.getDimension(isRound
                    ? R.dimen.digital_date_size_round : R.dimen.digital_date_size);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunshineWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.primary));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
                int ambientText = getResources().getColor(R.color.digital_text);
                mTimeView.setTextColor(ambientText);
                mHighView.setTextColor(ambientText);
                mDateView.setTextColor(ambientText);
                mLowView.setTextColor(ambientText);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
                int primaryText = getResources().getColor(R.color.primary_text);
                int secondaryText = getResources().getColor(R.color.secondary_text);
                mTimeView.setTextColor(primaryText);
                mHighView.setTextColor(primaryText);
                mDateView.setTextColor(secondaryText);
                mLowView.setTextColor(secondaryText);
            }

            //Set Time to now
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            int hour = mCalendar.get(Calendar.HOUR);
            int minutes = mCalendar.get(Calendar.MINUTE);
            int seconds = mCalendar.get(Calendar.SECOND);
            String dateText = mDateFormat.format(mDate);

            String suffix = "\u00B0";
            // If in ambient mode do not show seconds
            String time = mAmbient?String.format("%d:%02d", hour, minutes):String.format("%d:%02d:%02d", hour, minutes, seconds);
            String highString = String.format("%d"+suffix, mHighTemp);
            String lowString = String.format("%d"+suffix, mLowTemp);

            mTimeView.setText(time);
            mTimeView.setTextSize(mHighlightSize);
            mDateView.setText(dateText);
            mDateView.setTextSize(mStandardSize);
            mHighView.setText(highString);
            mHighView.setTextSize(mStandardSize);
            mLowView.setText(lowString);
            mLowView.setTextSize(mStandardSize);


            //This is necessary to be able to use xml layout file
            mRootView.measure(mSpecW, mSpecH);
            mRootView.layout(0, 0, mRootView.getMeasuredWidth(),
                    mRootView.getMeasuredHeight());
            mRootView.draw(canvas);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
