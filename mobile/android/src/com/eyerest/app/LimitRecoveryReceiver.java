package com.eyerest.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class LimitRecoveryReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        // A fresh registry in the service also recognises newly installed games.
        AppLimitService.start(context);
    }
}
