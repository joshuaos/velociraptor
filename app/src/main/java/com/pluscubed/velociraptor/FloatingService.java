package com.pluscubed.velociraptor;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.app.RemoteInput;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.animation.FastOutSlowInInterpolator;
import android.support.v4.view.animation.LinearOutSlowInInterpolator;
import android.support.v7.app.NotificationCompat;
import android.support.v7.view.ContextThemeWrapper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.gigamole.library.ArcProgressStackView;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.pluscubed.velociraptor.utils.PrefUtils;
import com.pluscubed.velociraptor.utils.Utils;

import java.util.ArrayList;

import rx.SingleSubscriber;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;

public class FloatingService extends Service {
    public static final int PENDING_SETTINGS = 5;
    public static final String EXTRA_NOTIF_START = "com.pluscubed.velociraptor.EXTRA_NOTIF_START";
    public static final String EXTRA_NOTIF_CLOSE = "com.pluscubed.velociraptor.EXTRA_NOTIF_CLOSE";
    public static final String EXTRA_CLOSE = "com.pluscubed.velociraptor.EXTRA_CLOSE";
    public static final String EXTRA_AUTO = "com.pluscubed.velociraptor.EXTRA_AUTO";
    public static final String EXTRA_PREF_CHANGE = "com.pluscubed.velociraptor.EXTRA_PREF_CHANGE";
    public static final int NOTIFICATION_AUTO = 42;
    public static final String NOTIFICATION_AUTO_TAG = "auto_tag";
    private static final int NOTIFICATION_FLOATING_WINDOW = 303;
    private WindowManager mWindowManager;

    private View mFloatingView;
    private View mSpeedometer;
    private TextView mLimitText;
    private TextView mSpeedometerText;
    private TextView mSpeedometerUnits;
    private TextView mDebuggingText;
    private ArcProgressStackView mArcView;

    private String mDebuggingRequestInfo;

    private GoogleApiClient mGoogleApiClient;

    private Subscription mLocationSubscription;
    private LocationListener mLocationListener;

    private int mLastSpeedLimit = -1;
    private Location mLastSpeedLocation;
    private Location mLastLimitLocation;
    private long mLastRequestTime;

    private long mSpeedingStart = -1;

    private SpeedLimitApi mSpeedLimitApi;

    private boolean mInitialized;
    private boolean mNotifStart;
    private boolean mAndroidAuto;
    private long mAutoTimestamp;
    private NotificationManagerCompat mNotificationManager;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressLint("InflateParams")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (!mNotifStart && intent.getBooleanExtra(EXTRA_CLOSE, false) ||
                    intent.getBooleanExtra(EXTRA_NOTIF_CLOSE, false)) {
                onStop();
                stopSelf();
                return super.onStartCommand(intent, flags, startId);
            } else if (intent.getBooleanExtra(EXTRA_NOTIF_START, false)) {
                mNotifStart = true;
            } else if (intent.getBooleanExtra(EXTRA_PREF_CHANGE, false)) {
                removeWindowView(mFloatingView);
                inflateMonitor();

                updatePrefUnits();
                updatePrefDebugging();
                updatePrefSpeedometer();

                updateLimitText(false);
                updateSpeedometer(mLastSpeedLocation);
            }

            if (intent.getBooleanExtra(EXTRA_AUTO, false)) {
                mAndroidAuto = true;
            }
        }


        if (mInitialized || prequisitesNotMet())
            return super.onStartCommand(intent, flags, startId);

        startNotification();

        mNotificationManager = NotificationManagerCompat.from(this);
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        inflateMonitor();

        mDebuggingRequestInfo = "";
        mDebuggingText = (TextView) LayoutInflater.from(new ContextThemeWrapper(this, R.style.Theme_Velociraptor))
                .inflate(R.layout.floating_stats, null, false);
        WindowManager.LayoutParams debuggingParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        debuggingParams.gravity = Gravity.BOTTOM;
        try {
            mWindowManager.addView(mDebuggingText, debuggingParams);
        } catch (Exception e) {
            showToast("Velociraptor error: " + e.getMessage());
        }

        updatePrefSpeedometer();
        updatePrefDebugging();
        updatePrefUnits();

        mLocationListener = new LocationListener() {
            @Override
            public void onLocationChanged(final Location location) {
                FloatingService.this.onLocationChanged(location);
            }
        };

        mSpeedLimitApi = new SpeedLimitApi(this);

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    @SuppressWarnings("MissingPermission")
                    public void onConnected(@Nullable Bundle bundle) {
                        LocationRequest locationRequest = new LocationRequest();
                        locationRequest.setInterval(1000);
                        locationRequest.setFastestInterval(0);
                        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
                        LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, locationRequest, mLocationListener);
                    }

                    @Override
                    public void onConnectionSuspended(int i) {
                        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                            LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, mLocationListener).setResultCallback(new ResultCallback<Status>() {
                                @Override
                                public void onResult(@NonNull Status status) {
                                }
                            });
                        }
                    }
                })
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
                        showToast("Velociraptor error: " + connectionResult.getErrorMessage());
                    }
                })
                .addApi(LocationServices.API)
                .build();

        mGoogleApiClient.connect();

        mInitialized = true;

        return super.onStartCommand(intent, flags, startId);
    }

    private void startNotification() {
        Intent notificationIntent = new Intent(this, SettingsActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, PENDING_SETTINGS, notificationIntent, PendingIntent.FLAG_CANCEL_CURRENT);

        Notification notification = new NotificationCompat.Builder(this)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_content))
                .setPriority(Notification.PRIORITY_MIN)
                .setSmallIcon(R.drawable.ic_speedometer)
                .setContentIntent(pendingIntent)
                .build();
        startForeground(NOTIFICATION_FLOATING_WINDOW, notification);
    }

    private void inflateMonitor() {
        int layout;
        switch (PrefUtils.getSignStyle(this)) {
            case PrefUtils.STYLE_US:
                layout = R.layout.floating_us;
                break;
            case PrefUtils.STYLE_INTERNATIONAL:
            default:
                layout = R.layout.floating_international;
                break;
        }

        mFloatingView = LayoutInflater.from(new ContextThemeWrapper(this, R.style.Theme_Velociraptor))
                .inflate(layout, null, false);

        mLimitText = (TextView) mFloatingView.findViewById(R.id.text);
        mArcView = (ArcProgressStackView) mFloatingView.findViewById(R.id.arcview);
        mSpeedometerText = (TextView) mFloatingView.findViewById(R.id.speed);
        mSpeedometerUnits = (TextView) mFloatingView.findViewById(R.id.speedUnits);
        mSpeedometer = mFloatingView.findViewById(R.id.speedometer);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        if (mWindowManager != null)
            try {
                mWindowManager.addView(mFloatingView, params);
            } catch (Exception e) {
                showToast("Velociraptor error: " + e);
            }
        mFloatingView.setOnTouchListener(new FloatingOnTouchListener());

        initMonitorPosition();

        final ArrayList<ArcProgressStackView.Model> models = new ArrayList<>();
        models.add(new ArcProgressStackView.Model("", 0, ContextCompat.getColor(this, R.color.colorPrimary800),
                new int[]{ContextCompat.getColor(this, R.color.colorPrimaryA200),
                        ContextCompat.getColor(this, R.color.colorPrimaryA200),
                        ContextCompat.getColor(this, R.color.red500),
                        ContextCompat.getColor(this, R.color.red500)}));
        mArcView.setTextColor(ContextCompat.getColor(this, android.R.color.transparent));
        mArcView.setInterpolator(new FastOutSlowInInterpolator());
        mArcView.setModels(models);
    }

    private void updatePrefDebugging() {
        if (mDebuggingText != null) {
            mDebuggingText.setVisibility(PrefUtils.isDebuggingEnabled(this) ? View.VISIBLE : View.GONE);
        }
    }

    private boolean prequisitesNotMet() {
        if (ContextCompat.checkSelfPermission(FloatingService.this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this))) {
            showToast(getString(R.string.permissions_warning));
            stopSelf();
            return true;
        }

        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            showToast(getString(R.string.location_settings_warning));
            stopSelf();
            return true;
        }

        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        if (!isConnected) {
            showToast(getString(R.string.network_warning));
            stopSelf();
            return true;
        }
        return false;
    }

    private void onLocationChanged(final Location location) {
        updateSpeedometer(location);
        updateDebuggingText(location, null, null);

        if (mLocationSubscription == null &&
                (mLastLimitLocation == null || location.distanceTo(mLastLimitLocation) > 100) &&
                System.currentTimeMillis() > mLastRequestTime + 5000) {

            mLastRequestTime = System.currentTimeMillis();
            mLocationSubscription = mSpeedLimitApi.getSpeedLimit(location)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new SingleSubscriber<SpeedLimitApi.ApiResponse>() {
                        @SuppressLint("SetTextI18n")
                        @Override
                        public void onSuccess(SpeedLimitApi.ApiResponse apiResponse) {
                            mLastLimitLocation = location;

                            mLastSpeedLimit = apiResponse.speedLimit;

                            updateLimitText(true);

                            updateDebuggingText(location, apiResponse, null);

                            mLocationSubscription = null;
                        }

                        @Override
                        public void onError(Throwable error) {
                            updateLimitText(false);

                            updateDebuggingText(location, null, error);

                            mLocationSubscription = null;
                        }
                    });
        }
    }

    private void updateDebuggingText(Location location, SpeedLimitApi.ApiResponse apiResponse, Throwable error) {
        String text = "Location: " + location +
                "\nEndpoints:\n" + mSpeedLimitApi.getApiInformation();

        if (error == null && apiResponse != null) {
            if (apiResponse.roadNames != null) {
                mDebuggingRequestInfo = ("Name(s): " + TextUtils.join(", ", apiResponse.roadNames));
            } else {
                mDebuggingRequestInfo = ("Success, no road data");
            }
            mDebuggingRequestInfo += "\nHERE Maps: " + apiResponse.useHere;
        } else if (error != null) {
            mDebuggingRequestInfo = ("Last Error: " + error);
        }

        text += mDebuggingRequestInfo;
        mDebuggingText.setText(text);
    }

    private void updateLimitText(boolean connected) {
        if (mLimitText != null) {
            String text = "--";
            if (mLastSpeedLimit != -1) {
                text = String.valueOf(mLastSpeedLimit);
                if (!connected) {
                    text = String.format("(%s)", text);
                }
            }

            if (mAndroidAuto) {
                String notificationMessage = getString(R.string.notif_android_auto_limit, Utils.getUnitText(this, text));

                if (mAutoTimestamp == 0) {
                    mAutoTimestamp = System.currentTimeMillis();
                }

                RemoteInput input = new RemoteInput.Builder("key").build();
                PendingIntent emptyPendingIntent = PendingIntent.getActivity(this, 42, new Intent(), PendingIntent.FLAG_CANCEL_CURRENT);
                NotificationCompat.CarExtender.UnreadConversation conv =
                        new NotificationCompat.CarExtender.UnreadConversation.Builder(notificationMessage)
                                .setLatestTimestamp(mAutoTimestamp)
                                .setReadPendingIntent(emptyPendingIntent)
                                .setReplyAction(emptyPendingIntent, input)
                                .addMessage(notificationMessage)
                                .build();
                Notification notification = new NotificationCompat.Builder(this)
                        .extend(new NotificationCompat.CarExtender().setUnreadConversation(conv))
                        .setContentTitle(getString(R.string.notif_android_auto_title))
                        .setContentText(notificationMessage)
                        .setSmallIcon(R.drawable.ic_speedometer)
                        .build();
                mNotificationManager.notify(NOTIFICATION_AUTO_TAG, NOTIFICATION_AUTO, notification);
            }

            mLimitText.setText(text);
        }
    }

    private void updateSpeedometer(Location location) {
        if (location != null && location.hasSpeed() && mSpeedometerText != null) {
            float metersPerSeconds = location.getSpeed();

            final int speed;
            int percentage;
            if (PrefUtils.getUseMetric(this)) {
                speed = (int) Math.round((double) metersPerSeconds * 60 * 60 / 1000); //km/h
                percentage = Math.round((float) speed / 200 * 100);
            } else {
                speed = (int) Math.round((double) metersPerSeconds * 60 * 60 / 1000 / 1.609344); //mph
                percentage = Math.round((float) speed / 120 * 100);
            }

            float percentToleranceFactor = 1 + (float) PrefUtils.getSpeedingPercent(this) / 100;
            int constantTolerance = PrefUtils.getSpeedingConstant(this);

            int limitAndPercentTolerance = (int) (mLastSpeedLimit * percentToleranceFactor);
            int speedingLimitWarning;
            if (PrefUtils.getToleranceMode(this)) {
                speedingLimitWarning = limitAndPercentTolerance + constantTolerance;
            } else {
                speedingLimitWarning = Math.min(limitAndPercentTolerance, mLastSpeedLimit + constantTolerance);
            }

            if (mLastSpeedLimit != -1 && speed > speedingLimitWarning) {
                mSpeedometerText.setTextColor(ContextCompat.getColor(this, R.color.red500));
                mSpeedometerUnits.setTextColor(ContextCompat.getColor(this, R.color.red500));
                if (mSpeedingStart == -1) {
                    mSpeedingStart = System.currentTimeMillis();
                } else if (System.currentTimeMillis() > mSpeedingStart + 2000L && PrefUtils.isBeepAlertEnabled(this)) {
                    Utils.playBeep();
                    mSpeedingStart = Long.MAX_VALUE - 2000L;
                }
            } else {
                mSpeedometerText.setTextColor(ContextCompat.getColor(this, R.color.primary_text_default_material_light));
                mSpeedometerUnits.setTextColor(ContextCompat.getColor(this, R.color.primary_text_default_material_light));
                mSpeedingStart = -1;
            }

            if (PrefUtils.getShowSpeedometer(this)) {
                mSpeedometerText.setText(String.valueOf(speed));

                if (mLastSpeedLimit != -1) {
                    percentage = Math.round((float) speed / speedingLimitWarning * 100);
                }

                mArcView.getModels().get(0).setProgress(percentage);
                mArcView.animateProgress();
            }

            mLastSpeedLocation = location;
        }
    }

    private void updatePrefSpeedometer() {
        if (mSpeedometer != null) {
            mSpeedometer.setVisibility(PrefUtils.getShowSpeedometer(FloatingService.this) ? View.VISIBLE : View.GONE);
        }
    }

    private void showToast(final String string) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(FloatingService.this.getApplicationContext(), string, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void updatePrefUnits() {
        if (mSpeedometerUnits != null) {
            mSpeedometerUnits.setText(Utils.getUnitText(this));
        }
    }

    private void initMonitorPosition() {
        if (mFloatingView == null) {
            return;
        }
        mFloatingView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) mFloatingView.getLayoutParams();

                String[] split = PrefUtils.getFloatingLocation(FloatingService.this).split(",");
                boolean left = Boolean.parseBoolean(split[0]);
                float yRatio = Float.parseFloat(split[1]);

                Point screenSize = new Point();
                mWindowManager.getDefaultDisplay().getSize(screenSize);
                params.x = left ? 0 : screenSize.x - mFloatingView.getWidth();
                params.y = (int) (yRatio * screenSize.y + 0.5f);

                try {
                    mWindowManager.updateViewLayout(mFloatingView, params);
                } catch (IllegalArgumentException ignore) {
                }

                mFloatingView.setVisibility(View.VISIBLE);

                mFloatingView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            }
        });
    }

    @Override
    public void onDestroy() {
        onStop();
        super.onDestroy();
    }

    private void onStop() {
        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, mLocationListener).setResultCallback(new ResultCallback<Status>() {
                @Override
                public void onResult(@NonNull Status status) {
                    if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                        mGoogleApiClient.disconnect();
                    }
                }
            });
        } else if (mGoogleApiClient != null) {
            mGoogleApiClient.disconnect();
        }

        removeWindowView(mFloatingView);
        removeWindowView(mDebuggingText);

        if (mLocationSubscription != null) {
            mLocationSubscription.unsubscribe();
        }

        if (mNotificationManager != null) {
            mNotificationManager.cancel(NOTIFICATION_AUTO_TAG, NOTIFICATION_AUTO);
        }
    }

    private void removeWindowView(View view) {
        if (view != null && mWindowManager != null)
            try {
                mWindowManager.removeView(view);
            } catch (IllegalArgumentException ignore) {
            }
    }

    void animateViewToSideSlot() {
        Point screenSize = new Point();
        mWindowManager.getDefaultDisplay().getSize(screenSize);

        WindowManager.LayoutParams params = (WindowManager.LayoutParams) mFloatingView.getLayoutParams();
        int endX;
        if (params.x + mFloatingView.getWidth() / 2 >= screenSize.x / 2) {
            endX = screenSize.x - mFloatingView.getWidth();
        } else {
            endX = 0;
        }

        PrefUtils.setFloatingLocation(FloatingService.this, (float) params.y / screenSize.y, endX == 0);

        ValueAnimator valueAnimator = ValueAnimator.ofInt(params.x, endX)
                .setDuration(300);
        valueAnimator.setInterpolator(new LinearOutSlowInInterpolator());
        valueAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) mFloatingView.getLayoutParams();
                params.x = (int) animation.getAnimatedValue();
                try {
                    mWindowManager.updateViewLayout(mFloatingView, params);
                } catch (IllegalArgumentException ignore) {
                }
            }
        });

        valueAnimator.start();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        initMonitorPosition();
    }

    private class FloatingOnTouchListener implements View.OnTouchListener {

        private float mInitialTouchX;
        private float mInitialTouchY;
        private int mInitialX;
        private int mInitialY;
        private long mStartClickTime;
        private boolean mIsClick;

        public FloatingOnTouchListener() {
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) mFloatingView.getLayoutParams();

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mInitialTouchX = event.getRawX();
                    mInitialTouchY = event.getRawY();

                    mInitialX = params.x;
                    mInitialY = params.y;

                    mStartClickTime = System.currentTimeMillis();

                    mIsClick = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dX = event.getRawX() - mInitialTouchX;
                    float dY = event.getRawY() - mInitialTouchY;
                    if ((mIsClick && (Math.abs(dX) > 10 || Math.abs(dY) > 10))
                            || System.currentTimeMillis() - mStartClickTime > ViewConfiguration.getLongPressTimeout()) {
                        mIsClick = false;
                    }

                    if (!mIsClick) {
                        params.x = (int) (dX + mInitialX);
                        params.y = (int) (dY + mInitialY);

                        try {
                            mWindowManager.updateViewLayout(mFloatingView, params);
                        } catch (IllegalArgumentException ignore) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:

                    if (mIsClick && System.currentTimeMillis() - mStartClickTime <= ViewConfiguration.getLongPressTimeout()) {
                        //TODO: On Click
                    } else {
                        animateViewToSideSlot();
                    }
                    return true;
            }
            return false;
        }
    }

}
