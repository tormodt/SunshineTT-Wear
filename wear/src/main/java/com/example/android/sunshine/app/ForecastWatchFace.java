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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class ForecastWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

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
        private final WeakReference<ForecastWatchFace.Engine> mWeakReference;

        public EngineHandler(ForecastWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            ForecastWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, MessageApi.MessageListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTimePaint, mDatePaint, mWeatherPaint, mMinTempPaint, mMaxTempPaint;
        Bitmap mWeatherBitmap;
        boolean mAmbient;
        Calendar mCalendar;
        GoogleApiClient mGoogleApiClient;
        float mTimeSize, mDateSize, mTempSize;
        int mWeatherResource;
        double mMinTemp, mMaxTemp;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(ForecastWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setHotwordIndicatorGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL)
                    .setStatusBarGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mWeatherPaint = new Paint();
            mTimePaint = createTextPaint(resources.getColor(R.color.white_text));
            mDatePaint = createTextPaint(resources.getColor(R.color.date_text));
            mMinTempPaint = createTextPaint(resources.getColor(R.color.white_text));
            mMaxTempPaint = createTextPaint(resources.getColor(R.color.white_text));

            mTimePaint.setTextAlign(Paint.Align.CENTER);
            mDatePaint.setTextAlign(Paint.Align.CENTER);
            mMinTempPaint.setTextAlign(Paint.Align.CENTER);
            mMaxTempPaint.setTextAlign(Paint.Align.CENTER);

            mCalendar = Calendar.getInstance();

            mGoogleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
            mGoogleApiClient.connect();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            if (BuildConfig.DEBUG) {
                Toast.makeText(getApplicationContext(), "onConnected", Toast.LENGTH_SHORT).show();
            }
            Wearable.MessageApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            if (BuildConfig.DEBUG) {
                Toast.makeText(getApplicationContext(), "onConnectionSuspended", Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            if (BuildConfig.DEBUG) {
                Toast.makeText(getApplicationContext(), "onConnectionFailed", Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onMessageReceived(MessageEvent messageEvent) {
            if (BuildConfig.DEBUG) {
                Toast.makeText(getApplicationContext(), "onMessageReceived: " + new String(messageEvent.getData()), Toast.LENGTH_SHORT).show();
            }

            if ("/weather".equals(messageEvent.getPath()) && messageEvent.getData() != null) {
                List<String> messageDataList = Arrays.asList(new String(messageEvent.getData()).split(";"));

                if (messageDataList.size() == 3) {
                    mWeatherResource = getIconResourceForWeatherCondition(Integer.parseInt(messageDataList.get(0)));
                    mWeatherBitmap = Bitmap.createBitmap(BitmapFactory.decodeResource(getResources(), mWeatherResource));

                    mMaxTemp = Double.parseDouble(messageDataList.get(2));
                    mMinTemp = Double.parseDouble(messageDataList.get(1));

                    if (BuildConfig.DEBUG) {
                        Toast.makeText(getApplicationContext(), "Weather received: " + mWeatherResource + " / " + mMinTemp + " / " + mMaxTemp, Toast.LENGTH_SHORT).show();
                    }
                } else {
                    if (BuildConfig.DEBUG) {
                        Toast.makeText(getApplicationContext(), "List size is " + messageDataList.size(), Toast.LENGTH_SHORT).show();
                    }
                }
            } else {
                if (BuildConfig.DEBUG) {
                    Toast.makeText(getApplicationContext(), "Path is " + messageEvent.getPath() + ", data might be null", Toast.LENGTH_SHORT).show();
                }
            }
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            ForecastWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            ForecastWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = getResources();
            boolean isRound = insets.isRound();
            mTimeSize = resources.getDimension(isRound ? R.dimen.time_size_round : R.dimen.time_size);
            mDateSize = resources.getDimension(isRound ? R.dimen.date_size_round : R.dimen.date_size);
            mTempSize = resources.getDimension(isRound ? R.dimen.temp_size_round : R.dimen.temp_size);

            mTimePaint.setTextSize(mTimeSize);
            mDatePaint.setTextSize(mDateSize);
            mMinTempPaint.setTextSize(mTempSize);
            mMaxTempPaint.setTextSize(mTempSize);
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
                    mTimePaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mWeatherPaint.setAntiAlias(!inAmbientMode);
                    mMinTempPaint.setAntiAlias(!inAmbientMode);
                    mMaxTempPaint.setAntiAlias(!inAmbientMode);
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
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            // Draw the time and date
            final String time = String.format("%02d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY), mCalendar.get(Calendar.MINUTE));
            final String date = DateUtils.formatDateTime(
                    getApplicationContext(),
                    mCalendar.getTimeInMillis(),
                    DateUtils.FORMAT_SHOW_WEEKDAY |
                            DateUtils.FORMAT_SHOW_DATE |
                            DateUtils.FORMAT_SHOW_YEAR |
                            DateUtils.FORMAT_ABBREV_ALL);

            // Draw the weather and temp if not in ambient mode and if the watch face has received a weather update from the handheld
            if (!isInAmbientMode()) {
                canvas.drawText(time, bounds.exactCenterX(), bounds.exactCenterY() - mTimeSize, mTimePaint);
                canvas.drawText(date, bounds.exactCenterX(), bounds.exactCenterY(), mDatePaint);

                if (mWeatherBitmap != null) {
                    final String minTemp = (int) mMinTemp + "\u00b0";
                    final String maxTemp = (int) mMaxTemp + "\u00b0";

                    canvas.drawText(minTemp, bounds.exactCenterX(), bounds.exactCenterY() + 40, mMinTempPaint);
                    canvas.drawText(maxTemp, bounds.exactCenterX() + bounds.width() / 3, bounds.exactCenterY() + 40, mMaxTempPaint);
                    canvas.drawBitmap(mWeatherBitmap, bounds.exactCenterX() - bounds.width() / 3, bounds.exactCenterY() + 40, mWeatherPaint);
                }
            } else {
                canvas.drawText(time, bounds.exactCenterX(), bounds.exactCenterY(), mTimePaint);
                canvas.drawText(date, bounds.exactCenterX(), bounds.exactCenterY() + mTimeSize, mDatePaint);
            }

            if (BuildConfig.DEBUG) {
                canvas.drawText("1", bounds.exactCenterX(), bounds.height() - 20, mDatePaint); // Just a watchface visible debug code version to be sure that my last code changes are active
            }
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

        private int getIconResourceForWeatherCondition(final int weatherId) {
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.ic_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.ic_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.ic_rain;
            } else if (weatherId == 511) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.ic_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.ic_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                return R.drawable.ic_storm;
            } else if (weatherId == 800) {
                return R.drawable.ic_clear;
            } else if (weatherId == 801) {
                return R.drawable.ic_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.ic_cloudy;
            }
            return -1;
        }
    }
}