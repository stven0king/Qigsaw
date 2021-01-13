package com.iqiyi.qigsaw.sample.java;

import android.app.Application;
import android.util.Log;

public class JavaSampleApplication extends Application {
    private static final String TAG = "Split:JavaSampleApplication";
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate: ");
    }
}
