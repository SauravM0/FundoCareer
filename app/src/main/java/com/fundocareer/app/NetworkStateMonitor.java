package com.fundocareer.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

public class NetworkStateMonitor {

    public interface Listener {
        void onConnectivityChanged(boolean isOnline, boolean wasOffline);
    }

    private static final String TAG = "FundoCareer-Network";

    private final ConnectivityManager cm;
    private Listener listener;
    private ConnectivityManager.NetworkCallback callback;
    private boolean wasOffline = false;
    private long lastNotificationTime = 0;
    private static final long NOTIFICATION_DEBOUNCE_MS = 1500;

    public NetworkStateMonitor(Context context) {
        this.cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        wasOffline = !isOnline();
    }

    public void startListening(Listener listener) {
        this.listener = listener;
        if (callback != null) stopListening();

        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                boolean was = wasOffline;
                wasOffline = false;
                Log.i(TAG, "Network available");
                if (was) {
                    debouncedNotify(true, true);
                }
            }

            @Override
            public void onLost(Network network) {
                wasOffline = true;
                Log.w(TAG, "Network lost");
                debouncedNotify(false, true);
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                boolean connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                if (connected && wasOffline) {
                    wasOffline = false;
                    Log.i(TAG, "Network restored via capabilities");
                    debouncedNotify(true, true);
                } else if (!connected && !wasOffline) {
                    wasOffline = true;
                    Log.w(TAG, "Network capabilities lost");
                    debouncedNotify(false, true);
                }
            }
        };

        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cm.registerNetworkCallback(request, callback);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register network callback", e);
        }
    }

    private void debouncedNotify(boolean online, boolean wasOffline) {
        long now = System.currentTimeMillis();
        if (now - lastNotificationTime < NOTIFICATION_DEBOUNCE_MS) {
            Log.d(TAG, "Notification debounced (within " + NOTIFICATION_DEBOUNCE_MS + "ms)");
            return;
        }
        lastNotificationTime = now;
        notifyListener(online, wasOffline);
    }

    public void stopListening() {
        if (callback != null) {
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (Exception e) {
                Log.w(TAG, "Unregister error", e);
            }
            callback = null;
        }
    }

    public boolean isOnline() {
        try {
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            Log.w(TAG, "isOnline check error", e);
            return true;
        }
    }

    private void notifyListener(boolean online, boolean wasOffline) {
        if (listener != null) {
            listener.onConnectivityChanged(online, wasOffline);
        }
    }
}
