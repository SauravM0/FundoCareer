package com.fundocareer.app;

import android.util.Log;
import android.webkit.JavascriptInterface;

public class MicrophoneBridge {

    private static final String TAG = "FundoCareer-MicBridge";

    private final MicrophoneManager micManager;

    public MicrophoneBridge(MicrophoneManager micManager) {
        this.micManager = micManager;
    }

    @JavascriptInterface
    public void onMicStarted() {
        Log.i(TAG, "Website reported mic started");
        if (micManager != null) {
            micManager.onMicStarted();
        }
    }

    @JavascriptInterface
    public void onMicStopped() {
        Log.i(TAG, "Website reported mic stopped");
        if (micManager != null) {
            micManager.onMicStopped();
        }
    }
}
