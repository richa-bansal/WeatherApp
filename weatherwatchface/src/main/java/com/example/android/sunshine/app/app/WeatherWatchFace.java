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

package com.example.android.sunshine.app.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;


import com.example.android.sunshine.app.R;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WeatherWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    public final String LOG_TAG = WeatherWatchFace.class.getSimpleName();

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
   // public static final int GOOGLE_API_CLIENT_TIMEOUT_S = 10; // 10 seconds

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WeatherWatchFace.Engine> mWeakReference;

        public EngineHandler(WeatherWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WeatherWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        boolean mAmbient;
        String mTempHigh;
        String mTempLow;
        Asset mImageAsset;
        Bitmap mWeatherIcon;
        float mLineHeight;
        Calendar mCalendar;
        Date mDate;
        Paint mDatePaint;
        Paint mTempHighPaint;
        Paint mTempLowPaint;
        String mAmString;
        String mPmString;
        SimpleDateFormat mDayOfWeekFormat;

        // Request code to use when launching the resolution activity
        //private static final int REQUEST_RESOLVE_ERROR = 1001;
        // Unique tag for the error dialog fragment
        //private static final String DIALOG_ERROR = "dialog_error";
        // Bool to track whether the app is already resolving an error
        //private boolean mResolvingError = false;


        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {

                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();

            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;


        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        GoogleApiClient mGoogleApiClient;



        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WeatherWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = WeatherWatchFace.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_date));
            mTempHighPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mTempLowPaint=createTextPaint(resources.getColor(R.color.digital_date));
            mCalendar = Calendar.getInstance();
            mDate = new Date();
            mAmString = resources.getString(R.string.digital_am);
            mPmString = resources.getString(R.string.digital_pm);


            mLineHeight = resources.getDimension(R.dimen.digital_line_height);
            initFormats();

            mGoogleApiClient = new GoogleApiClient.Builder(WeatherWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();



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

        private void initFormats() {
            mDayOfWeekFormat = new SimpleDateFormat("EEE, MMM d yyyy", Locale.getDefault());
            mDayOfWeekFormat.setCalendar(mCalendar);

        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone and date formats, in case they changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
            } else {
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
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
            WeatherWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WeatherWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WeatherWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            mYOffset = resources.getDimension(isRound
                    ? R.dimen.digital_y_offset_round : R.dimen.digital_y_offset_square);


            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            float dateTextSize = resources.getDimension(R.dimen.digital_date_text_size);

            mTextPaint.setTextSize(textSize);
            mDatePaint.setTextSize(dateTextSize);
            mTempHighPaint.setTextSize(resources.getDimension(R.dimen.weather_text_size));
            mTempLowPaint.setTextSize(R.dimen.weather_text_size);
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
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mTempLowPaint.setAntiAlias(!inAmbientMode);
                    mTempHighPaint.setAntiAlias(!inAmbientMode);

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
            Resources resources = WeatherWatchFace.this.getResources();
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
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }

        private String getAmPmString(int amPm) {
            return amPm == Calendar.AM ? mAmString : mPmString;
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);
            boolean is24Hour = DateFormat.is24HourFormat(WeatherWatchFace.this);

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }


            // get hours.
            float x = mXOffset;
            String hourString;
            if (is24Hour) {
                hourString = formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY));
            } else {
                int hour = mCalendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }
                hourString = String.valueOf(hour);
            }


            // get minutes.
            String minuteString = formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE));
            String timeString=null;


            // In unmuted interactive mode, draw a second colon followed by the seconds.
            // Otherwise, if we're in 12-hour mode, draw AM/PM
           // if (!isInAmbientMode() && !mMute) {
            if (!isInAmbientMode()) {

               String secondString = formatTwoDigitNumber(mCalendar.get(Calendar.SECOND));
                timeString = hourString+":"+minuteString+":"+secondString;
            } else if (!is24Hour) {
                String ampmString = getAmPmString(mCalendar.get(Calendar.AM_PM));
                timeString = hourString+":"+minuteString+" "+ampmString;

            }else{
                timeString = hourString+":"+minuteString;

            }

            canvas.drawText(timeString, mXOffset, mYOffset, mTextPaint);



            // Only render the day of week and date if there is no peek card, so they do not bleed
            // into each other in ambient mode.
            if (getPeekCardPosition().isEmpty()) {

                String dayAndDate = mDayOfWeekFormat.format(mDate);
                // Day of week and Date
                canvas.drawText(
                        dayAndDate,
                        mXOffset, mYOffset + mLineHeight, mDatePaint);
                float length = mDatePaint.measureText(dayAndDate);

                canvas.drawLine(mXOffset+(length/4),mYOffset + (3 * mLineHeight/2),mXOffset+(3*length/4),mYOffset + (3* mLineHeight/2),mDatePaint);

                //Draw weather
                if (mWeatherIcon !=null){
                    x = mXOffset+20;
                    float y = mYOffset + (2 * mLineHeight);
                    RectF rectF = new RectF(x,y,x+40,y+40);
                    canvas.drawBitmap(mWeatherIcon,null,rectF,null);
                }

                if ((mTempHigh !=null)&&(mTempLow !=null)) {
                    x = x+50;
                    canvas.drawText(mTempHigh, x, mYOffset + (3 * mLineHeight), mTempHighPaint);

                    x += mTempHighPaint.measureText(mTempHigh);
                    canvas.drawText(mTempLow,x, mYOffset + (3 * mLineHeight), mTempHighPaint);
                }

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

        @Override
        public void onConnected(Bundle bundle) {

            Log.d(LOG_TAG, "Inside googleApiClient onConnected.");
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            getWeatherData();

        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(LOG_TAG, "Inside googleApiClient onConnectionSuspended.");
            Wearable.DataApi.removeListener(mGoogleApiClient,Engine.this);

        }


        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(LOG_TAG, "Inside onDataChanged");
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo("/weather") == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        mTempHigh = dataMap.getString("high_temp");
                        mTempLow = dataMap.getString("low_temp");
                        mImageAsset = dataMap.getAsset("weather_icon");
                        GetWeatherImageTask getWeatherImageTask = new GetWeatherImageTask();
                        getWeatherImageTask.execute();
                    }
                }
                Log.d(LOG_TAG,"High:"+mTempHigh + "  Low:"+mTempLow);
            }

            invalidate();

        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            Log.d(LOG_TAG,"Inside googleApiClient onConnectionFailed."+ GoogleApiAvailability.getInstance().getErrorString(connectionResult.getErrorCode()));

        }

       public void getWeatherData(){
           GetWeatherDataTask getWeatherDataTask= new GetWeatherDataTask();
           getWeatherDataTask.execute();
           GetWeatherImageTask getWeatherImageTask = new GetWeatherImageTask();
           getWeatherImageTask.execute();
       }


        private class GetWeatherDataTask extends AsyncTask<Void,Void,Void>{
            @Override
            protected Void doInBackground(Void... params) {
                Uri uri = getUriForDataItem("/weather");
                DataApi.DataItemResult result = Wearable.DataApi.getDataItem(mGoogleApiClient,uri).await();
                DataMapItem item = DataMapItem.fromDataItem(result.getDataItem());
                DataMap dataMap = item.getDataMap();
                mTempHigh = dataMap.getString("high_temp");
                mTempLow = dataMap.getString("low_temp");
                mImageAsset = dataMap.getAsset("weather_icon");
                Log.d(LOG_TAG, "High:" + mTempHigh + "  Low:" + mTempLow);
                return null;
            }


        }

        private class GetWeatherImageTask extends AsyncTask<Void,Void,Void>{
            @Override
            protected Void doInBackground(Void... params) {
                mWeatherIcon = loadBitmapFromAsset(mImageAsset);
                return null;
            }

            @Override
            protected void onPostExecute(Void aVoid) {
                super.onPostExecute(aVoid);
                invalidate();
            }
        }

        public Bitmap loadBitmapFromAsset(Asset asset) {
            if (asset == null) {
                throw new IllegalArgumentException("Asset must be non-null");
            }
            /*ConnectionResult result =
                    mGoogleApiClient.blockingConnect(GOOGLE_API_CLIENT_TIMEOUT_S, TimeUnit.MILLISECONDS);
            if (!result.isSuccess()) {
                return null;
            }*/
            // convert asset into a file descriptor and block until it's ready
            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                    mGoogleApiClient, asset).await().getInputStream();
            // mGoogleApiClient.disconnect();

            if (assetInputStream == null) {
                return null;
            }
            // decode the stream into a bitmap
            return BitmapFactory.decodeStream(assetInputStream);
        }

        private Uri getUriForDataItem(String path) {

            String nodeId = getNodeId();

            return new Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).authority(nodeId).path(path).build();
        }


        private String getNodeId() {
            NodeApi.GetConnectedNodesResult nodesResult = Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();
            List<Node> nodes = nodesResult.getNodes();
            if (nodes.size() > 0) {
                return nodes.get(0).getId();
            } else {
                Log.d(LOG_TAG,"No nodes available");
            }
            return null;
        }
    }
}
