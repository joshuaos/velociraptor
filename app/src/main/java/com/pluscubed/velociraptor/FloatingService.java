package com.pluscubed.velociraptor;

import android.Manifest;
import android.animation.AnimatorListenerAdapter;
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
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.animation.FastOutSlowInInterpolator;
import android.support.v4.view.animation.LinearOutSlowInInterpolator;
import android.support.v7.app.NotificationCompat;
import android.support.v7.view.ContextThemeWrapper;
import android.util.Pair;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.crashlytics.android.Crashlytics;
import com.gigamole.library.ArcProgressStackView;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.pluscubed.velociraptor.osmapi.OsmApiEndpoint;
import com.pluscubed.velociraptor.osmapi.Tags;
import com.pluscubed.velociraptor.utils.PrefUtils;

import java.util.ArrayList;

import rx.SingleSubscriber;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;

public class FloatingService extends Service {
    public static final int PENDING_SETTINGS = 5;
    public static final String EXTRA_NOTIF_START = "com.pluscubed.velociraptor.EXTRA_NOTIF_START";
    public static final String EXTRA_NOTIF_CLOSE = "com.pluscubed.velociraptor.EXTRA_NOTIF_CLOSE";
    public static final String EXTRA_CLOSE = "com.pluscubed.velociraptor.EXTRA_CLOSE";
    public static final String EXTRA_PREF_CHANGE = "com.pluscubed.velociraptor.EXTRA_PREF_CHANGE";

    private static final int NOTIFICATION_FLOATING_WINDOW = 303;
    private WindowManager mWindowManager;

    private View mFloatingView;
    private View mSpeedometer;
    private TextView mLimitText;
    private TextView mStreetText;
    private TextView mSpeedometerText;
    private TextView mSpeedometerUnits;
    private TextView mDebuggingText;
    private ArcProgressStackView mArcView;

    private String mDebuggingNetworkInfo;

    private GoogleApiClient mGoogleApiClient;

    private Subscription mLocationSubscription;
    private LocationListener mLocationListener;

    private int mLastSpeedLimit = -1;
    private Location mLastSpeedLocation;

    private SpeedLimitApi mSpeedLimitApi;

    private boolean mNotifStart;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.getBooleanExtra(EXTRA_NOTIF_START, false)) {
                mNotifStart = true;
            } else if (!mNotifStart && intent.getBooleanExtra(EXTRA_CLOSE, false) ||
                    intent.getBooleanExtra(EXTRA_NOTIF_CLOSE, false)) {
                stopSelf();
            } else if (intent.getBooleanExtra(EXTRA_PREF_CHANGE, false)) {
                removeWindowView(mFloatingView);
                inflateMonitor();

                updatePrefUnits();
                updatePrefDebugging();
                updatePrefSpeedometer();

                updateLimitText();
                updateSpeedometer(mLastSpeedLocation);
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @SuppressLint("InflateParams")
    @Override
    public void onCreate() {
        super.onCreate();

        if (prequisitesNotMet()) return;

        startNotification();

        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        inflateMonitor();

        mDebuggingNetworkInfo = "";
        mDebuggingText = (TextView) LayoutInflater.from(new ContextThemeWrapper(this, R.style.Theme_Velociraptor))
                .inflate(R.layout.floating_stats, null, false);
        WindowManager.LayoutParams debuggingParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        debuggingParams.gravity = Gravity.BOTTOM;
        mWindowManager.addView(mDebuggingText, debuggingParams);

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
                        locationRequest.setInterval(500);
                        locationRequest.setFastestInterval(200);
                        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
                        locationRequest.setSmallestDisplacement(50);
                        LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, locationRequest, mLocationListener);
                    }

                    @Override
                    public void onConnectionSuspended(int i) {
                        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                            LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, mLocationListener);
                        }
                    }
                })
                .addApi(LocationServices.API)
                .build();

        mGoogleApiClient.connect();
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
        mStreetText = (TextView) mFloatingView.findViewById(R.id.subtext);
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
            mWindowManager.addView(mFloatingView, params);
        mFloatingView.setOnTouchListener(new FloatingOnTouchListener());

        initMonitorPosition();

        final ArrayList<ArcProgressStackView.Model> models = new ArrayList<>();
        models.add(new ArcProgressStackView.Model("", 0, ContextCompat.getColor(this, R.color.colorPrimary), ContextCompat.getColor(this, R.color.colorAccent)));
        mArcView.setTextColor(ContextCompat.getColor(this, R.color.colorAccent));
        mArcView.setInterpolator(new FastOutSlowInInterpolator());
        mArcView.setModels(models);
    }

    private void updatePrefDebugging() {
        mDebuggingText.setVisibility(PrefUtils.isDebuggingEnabled(this) ? View.VISIBLE : View.GONE);
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

        mDebuggingText.setText("Location: " + location);
        mDebuggingText.append(mDebuggingNetworkInfo);

        if (mLocationSubscription == null) {

            mLocationSubscription = mSpeedLimitApi.getSpeedLimit(location)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new SingleSubscriber<Pair<Integer, Tags>>() {
                        @SuppressLint("SetTextI18n")
                        @Override
                        public void onSuccess(Pair<Integer, Tags> speedLimit) {
                            if (speedLimit.first != null) {
                                mLastSpeedLimit = speedLimit.first;
                            } else {
                                mLastSpeedLimit = -1;
                            }

                            updateLimitText();

                            if (PrefUtils.isDebuggingEnabled(FloatingService.this)) {
                                mDebuggingText.setText("Location: " + location);

                                mDebuggingNetworkInfo = ("\nEndpoints:\n");
                                for (OsmApiEndpoint endpoint : mSpeedLimitApi.getOsmOverpassApis()) {
                                    mDebuggingNetworkInfo += (endpoint.toString() + "\n");
                                }
                                if (speedLimit.second != null) {
                                    Tags tags = speedLimit.second;
                                    mDebuggingNetworkInfo += ("Name: " + tags.getName() + "\nRef: " + tags.getRef());
                                } else {
                                    mDebuggingNetworkInfo += ("Connected, no speed limit data");
                                }

                                mDebuggingText.append(mDebuggingNetworkInfo);
                            }

                            mLocationSubscription = null;
                        }

                        @Override
                        public void onError(Throwable error) {
                            error.printStackTrace();
                            if (!BuildConfig.DEBUG)
                                Crashlytics.logException(error);

                            mLastSpeedLimit = -1;
                            updateLimitText();

                            if (PrefUtils.isDebuggingEnabled(FloatingService.this)) {
                                mDebuggingText.setText("Location: " + location);
                                mDebuggingNetworkInfo = ("Error: " + error);
                                mDebuggingText.append("\n" + mDebuggingNetworkInfo);
                            }

                            mLocationSubscription = null;
                        }
                    });
        }
    }

    private void updateLimitText() {
        if (mLastSpeedLimit != -1) {
            mLimitText.setText(String.valueOf(mLastSpeedLimit));
        } else {
            mLimitText.setText("--");
        }
    }

    private void updateSpeedometer(Location location) {
        if (location != null && location.hasSpeed()) {
            float metersPerSeconds = location.getSpeed();

            final int speed;
            int percentage;
            if (PrefUtils.getUseMetric(FloatingService.this)) {
                speed = (int) ((metersPerSeconds * 60 * 60 / 1000) + 0.5f); //km/h
                percentage = (int) ((float) speed / 200 * 100 + 0.5f);
            } else {
                speed = (int) ((metersPerSeconds * 60 * 60 / 1000 / 1.609344) + 0.5f); //mph
                percentage = (int) ((float) speed / 120 * 100 + 0.5f);
            }

            mSpeedometerText.setText(String.valueOf(speed));

            mArcView.getModels().get(0).setProgress(percentage);
            mArcView.setAnimatorListener(new AnimatorListenerAdapter() {
            });
            mArcView.animateProgress();

            if (mLastSpeedLimit != -1 && mLastSpeedLimit * (1 + (double) PrefUtils.getOverspeedPercent(FloatingService.this) / 100) < speed) {
                mSpeedometerText.setTextColor(ContextCompat.getColor(FloatingService.this, R.color.red500));
            } else {
                mSpeedometerText.setTextColor(ContextCompat.getColor(FloatingService.this, R.color.primary_text_default_material_light));
            }

            mLastSpeedLocation = location;
        }
    }

    private void updatePrefSpeedometer() {
        mSpeedometer.setVisibility(PrefUtils.getShowSpeedometer(FloatingService.this) ? View.VISIBLE : View.GONE);
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
        if (PrefUtils.getUseMetric(FloatingService.this)) {
            mSpeedometerUnits.setText("km/h");
        } else {
            mSpeedometerUnits.setText("mph");
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
    public boolean stopService(Intent name) {
        onStop();
        return super.stopService(name);
    }

    @Override
    public void onDestroy() {
        onStop();
        super.onDestroy();
    }

    private void onStop() {
        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, mLocationListener);
            mGoogleApiClient.disconnect();
        }

        removeWindowView(mFloatingView);
        removeWindowView(mDebuggingText);

        if (mLocationSubscription != null) {
            mLocationSubscription.unsubscribe();
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

                        mWindowManager.updateViewLayout(mFloatingView, params);
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
