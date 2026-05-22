package com.fundocareer.app;

import android.app.Activity;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

public class MicrophoneManager {

    private static final String TAG = "FundoCareer-Mic";

    private boolean active = false;
    private View indicatorView;

    public View createIndicator(Activity activity) {
        if (indicatorView != null) return indicatorView;

        float density = activity.getResources().getDisplayMetrics().density;

        FrameLayout banner = new FrameLayout(activity);
        banner.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (44 * density)
        ));
        banner.setBackgroundColor(0xCCD32F2F);
        banner.setVisibility(View.GONE);
        banner.setClickable(false);
        banner.setFocusable(false);

        TextView label = new TextView(activity);
        label.setText("\u25CF Recording");
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(14);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding((int) (16 * density), 0, 0, 0);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        label.setLayoutParams(lp);
        banner.addView(label);
        indicatorView = banner;
        return banner;
    }

    public void onMicStarted() {
        if (active) return;
        active = true;
        if (indicatorView != null) {
            indicatorView.post(() -> indicatorView.setVisibility(View.VISIBLE));
        }
        Log.i(TAG, "Mic active");
    }

    public void onMicStopped() {
        if (!active) return;
        active = false;
        if (indicatorView != null) {
            indicatorView.post(() -> indicatorView.setVisibility(View.GONE));
        }
        Log.i(TAG, "Mic inactive");
    }

    public boolean isActive() {
        return active;
    }

    public void onPageChanged(android.webkit.WebView webView, String newUrl) {
        if (!active) return;
        if (newUrl == null || !newUrl.contains("/mock-interview")) {
            forceStop(webView);
        }
    }

    public void forceStop(android.webkit.WebView webView) {
        onMicStopped();
        if (webView != null) {
            try {
                webView.evaluateJavascript(
                    "(function(){try{var s=window.__micStream;" +
                    "if(s&&s.getTracks){s.getTracks().forEach(function(t){try{t.stop()}catch(e){}});}" +
                    "window.MicrophoneBridge&&window.MicrophoneBridge.onForceStop&&window.MicrophoneBridge.onForceStop();" +
                    "}catch(e){}})()",
                    null
                );
            } catch (Exception e) {
                Log.w(TAG, "forceStop JS error", e);
            }
        }
    }
}
