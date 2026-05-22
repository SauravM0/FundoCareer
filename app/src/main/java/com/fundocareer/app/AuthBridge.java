package com.fundocareer.app;

import android.util.Log;
import android.webkit.JavascriptInterface;

public class AuthBridge {

    private static final String TAG = "FundoCareer-AuthBridge";

    public interface AuthBridgeListener {
        void onSignInRequested();
        void onSignOutRequested();
        void onTokenRefreshRequested();
        String getAuthStateJson();
    }

    private final AuthBridgeListener listener;

    public AuthBridge(AuthBridgeListener listener) {
        this.listener = listener;
    }

    @JavascriptInterface
    public void requestSignIn() {
        Log.i(TAG, "WebView requested sign-in");
        if (listener != null) {
            listener.onSignInRequested();
        }
    }

    @JavascriptInterface
    public void requestSignOut() {
        Log.i(TAG, "WebView requested sign-out");
        if (listener != null) {
            listener.onSignOutRequested();
        }
    }

    @JavascriptInterface
    public void requestTokenRefresh() {
        Log.i(TAG, "WebView requested token refresh");
        if (listener != null) {
            listener.onTokenRefreshRequested();
        }
    }

    @JavascriptInterface
    public String getAuthState() {
        if (listener == null) return "{}";
        return listener.getAuthStateJson();
    }

    @JavascriptInterface
    public void onPageReady() {
        Log.i(TAG, "WebView page ready for auth injection");
    }
}
