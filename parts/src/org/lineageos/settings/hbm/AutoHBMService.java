package org.lineageos.settings.hbm;

import android.app.KeyguardManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;

import org.lineageos.settings.utils.FileUtils;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class AutoHBMService extends Service {
    private static final String ACTION_SCREEN_ON = "android.intent.action.SCREEN_ON";
    private static final String ACTION_SCREEN_OFF = "android.intent.action.SCREEN_OFF";
    private static final String HBM = "/sys/class/drm/card0/card0-DSI-1/disp_param";
    private static final String BACKLIGHT = "/sys/class/backlight/panel0-backlight/brightness";
    private float lux;
    private float threshold;
    private float lower_threshold;
    private ExecutorService mExecutorService;
    private static final String TAG = "AutoHBM";
    private static final int MAX_BACKLIGHT = 2047;
    private static final int DEFAULT_SCREEN_BRIGHTNESS = 255;
    private static final int MIN_SCREEN_BRIGHTNESS_THRESHOLD = 230;

    private SensorManager mSensorManager;
    Sensor mLightSensor;
    private int currentBrightness;

    private SharedPreferences mSharedPrefs;

    public void activateLightSensorRead() {
        threshold = Float.parseFloat(mSharedPrefs.getString(HBMFragment.KEY_AUTO_HBM_THRESHOLD, "6000"));
        lower_threshold = (threshold >= 6000) ? (threshold - 2000) : 3000;
        submit(() -> {
            ContentResolver contentResolver = getContentResolver();
            mSensorManager = (SensorManager) getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
            mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            mSensorManager.registerListener(mSensorEventListener, mLightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        });
    }

    public void deactivateLightSensorRead() {
        submit(() -> {
            mSensorManager.unregisterListener(mSensorEventListener);
            FileUtils.writeLine(HBM, "0xF0000");
        });
    }

    private void enableHBM(boolean enable) {
        if (enable) {
            FileUtils.writeLine(HBM, "0x10000");
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, String.valueOf(DEFAULT_SCREEN_BRIGHTNESS));
        } else {
            FileUtils.writeLine(HBM, "0xF0000");
            FileUtils.writeLine(BACKLIGHT, String.valueOf(MAX_BACKLIGHT));
        }
    }

    private boolean isCurrentlyEnabled() {
        return FileUtils.getFileValueAsBoolean(HBM, false);
    }

    private void writeBacklight(float currentAmbient) {
        final float BASE_BACKLIGHT = 1f;
        final float BACKLIGHT_INCREMENT = MAX_BACKLIGHT/(10000f-lower_threshold);
        float backlightOffset = 0f;
        if(isCurrentlyEnabled()) {
            backlightOffset = (currentAmbient-lower_threshold)*BACKLIGHT_INCREMENT;
            int intBacklight = (int) BASE_BACKLIGHT + backlightOffset;
            intBacklight = (intBacklight > MAX_BACKLIGHT) ? MAX_BACKLIGHT : intBacklight;
            Log.i(TAG,"Set HBM brightness of "+(intBacklight)+" nits at "+lux+" lux");
        }
    }

    private SensorEventListener mSensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            lux = event.values[0];
            KeyguardManager km =
                (KeyguardManager) getSystemService(getApplicationContext().KEYGUARD_SERVICE);
            boolean keyguardShowing = km.inKeyguardRestrictedInputMode();
            ContentResolver contentResolver = getContentResolver();
            try {
                currentBrightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS);
            } catch (Settings.SettingNotFoundException e) {
                currentBrightness = DEFAULT_SCREEN_BRIGHTNESS;
                Log.d(TAG,"System does not support changing screen brightness");
                return;
            }
            float threshold = Float.parseFloat(mSharedPrefs.getString(HBMFragment.KEY_AUTO_HBM_THRESHOLD, "6000"));
            if (lux > threshold && currentBrightness>=MIN_SCREEN_BRIGHTNESS_THRESHOLD && !isCurrentlyEnabled()) {
                if (!keyguardShowing) {
                    enableHBM(true);
                }
            } else if (lux < threshold | (currentBrightness<=MIN_SCREEN_BRIGHTNESS_THRESHOLD && isCurrentlyEnabled())) {
                mExecutorService.submit(() -> {
                    if (lux < threshold) {
                        enableHBM(false);
                    }
                });
            } else {
                writeBacklight(lux);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // do nothing
        }
    };

    private BroadcastReceiver mScreenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                activateLightSensorRead();
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                deactivateLightSensorRead();
            }
        }
    };

    @Override
    public void onCreate() {
        mExecutorService = Executors.newSingleThreadExecutor();
        IntentFilter screenStateFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mScreenStateReceiver, screenStateFilter);
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            try {
                if (pm.isInteractive()) {
                    activateLightSensorRead();
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied to check interactive state", e);
            }
        }
    }

    private Future < ? > submit(Runnable runnable) {
        return mExecutorService.submit(runnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mScreenStateReceiver);
        deactivateLightSensorRead();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
