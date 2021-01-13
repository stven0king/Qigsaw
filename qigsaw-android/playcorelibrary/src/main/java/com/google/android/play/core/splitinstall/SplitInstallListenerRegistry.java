package com.google.android.play.core.splitinstall;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.play.core.listener.StateUpdateListenerRegister;
import com.google.android.play.core.splitcompat.util.PlayCore;

final class SplitInstallListenerRegistry extends StateUpdateListenerRegister<SplitInstallSessionState> {
    private final static String TAG = "SplitInstallListenerRegistry";
    final Handler mMainHandler;

    private final SplitSessionLoader mLoader;

    SplitInstallListenerRegistry(Context context) {
        this(context, SplitSessionLoaderSingleton.get());
    }

    private SplitInstallListenerRegistry(Context context, SplitSessionLoader loader) {
        super(new PlayCore("SplitInstallListenerRegistry"), new IntentFilter("com.iqiyi.android.play.core.splitinstall.receiver.SplitInstallUpdateIntentService"), context);
        this.mMainHandler = new Handler(Looper.getMainLooper());
        this.mLoader = loader;
    }

    @Override
    protected void onReceived(Intent intent) {
        SplitInstallSessionState sessionState = SplitInstallSessionState.createFrom(intent.getBundleExtra("session_state"));
        this.playCore.info("ListenerRegistryBroadcastReceiver.onReceive: %s", sessionState);
        //SplitInstallInternalSessionStatus.POST_INSTALLED=10
        if (sessionState.status() == 10 && mLoader != null) {
            Log.d(TAG, "onReceived: mLoader.load");
            //mLoader.load(sessionState.splitFileIntents, new SplitSessionStatusChanger(this, sessionState));
        } else {
            notifyListeners(sessionState);
        }
    }
}
