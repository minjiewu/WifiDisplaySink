package com.autoai.wifi_sink;

import android.app.Application;

public class SinkApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        LogUtils.setContext(getApplicationContext());
    }
}
