package com.example.videoplayer;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        Log.d(TAG, "Received: " + action);

        boolean isBoot =
                Intent.ACTION_BOOT_COMPLETED.equals(action)
                        || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                        || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        && Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action));

        if (!isBoot) return;

        // Check if device is locked (just for logging; we still start activity)
        boolean locked = false;
        KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null) {
            locked = km.isKeyguardLocked();
        }
        Log.d(TAG, "Keyguard locked: " + locked);

        Intent i = new Intent(context, FullScreenPlayerActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        // For kiosk-style device we want it to start even if locked
        context.startActivity(i);

        // If you really wanted to avoid starting when locked, you could wrap:
        // if (!locked) { context.startActivity(i); }
    }
}
