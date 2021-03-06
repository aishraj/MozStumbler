package org.mozilla.mozstumbler.service.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import java.lang.ref.WeakReference;


public class BatteryCheckReceiver extends BroadcastReceiver {
    public interface BatteryCheckCallback {
        void batteryCheckCallback(BatteryCheckReceiver receiver);
    }

    private final BatteryInfo mCurrentInfo = new BatteryInfo();
    private final WeakReference<BatteryCheckCallback> mCallback;
    private final Context mContext;

    public static class BatteryInfo {
        public int level;
        public boolean isCharging;
    }

    public BatteryCheckReceiver(Context context, BatteryCheckCallback callback) {
        mCallback = new WeakReference<BatteryCheckCallback>(callback);
        mContext = context;
    }

    public void start() {
        mContext.registerReceiver(this, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    public void stop() {
        try {
            mContext.unregisterReceiver(this);
        } catch (Exception ex) {}
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        int rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        mCurrentInfo.isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING);
        mCurrentInfo.level = Math.round(rawLevel * scale / 100.0f);
        if (mCallback.get() != null) {
            mCallback.get().batteryCheckCallback(this);
        }
    }

    public boolean isBatteryNotChargingAndLessThan(int percent) {
        if (mCurrentInfo.level < 1)
            return false;
        return !mCurrentInfo.isCharging && mCurrentInfo.level < percent;
    }

    public boolean isBatteryNotChargingAndGreaterThan(int percent) {
        if (mCurrentInfo.level < 1)
            return false;
        return !mCurrentInfo.isCharging && mCurrentInfo.level < percent;
    }
}
