package com.fundocareer.app;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class BackendDiagnostics {

    private static final String TAG = "FundoCareer-Diag";

    private BackendDiagnostics() {}

    public static void run() {
        String baseUrl = BuildConfig.API_BASE_URL;
        String frontendUrl = BuildConfig.FRONTEND_URL;
        boolean isDebug = BuildConfig.DEBUG;

        Log.i(TAG, "╔══════════════════════════════════════════╗");
        Log.i(TAG, "║        FUNDOCAREER STARTUP DIAGNOSTICS  ║");
        Log.i(TAG, "╚══════════════════════════════════════════╝");
        Log.i(TAG, "Build:      " + (isDebug ? "DEBUG" : "RELEASE"));
        Log.i(TAG, "Env:        " + BuildConfig.ENV_NAME);
        Log.i(TAG, "Frontend:   " + frontendUrl);
        Log.i(TAG, "API URL:    " + baseUrl);

        boolean sameOrigin = frontendUrl.equals(baseUrl);
        Log.i(TAG, "Mode:       " + (sameOrigin ? "PRODUCTION (API=Frontend)" : "DEVELOPMENT (API!=Frontend)"));

        if (isDebug && !sameOrigin) {
            Log.i(TAG, "──────────────────────────────────────────");
            Log.i(TAG, "PHYSICAL DEVICE SETUP:");
            Log.i(TAG, "  ANDROID_API_URL must be your laptop LAN IP:");
            Log.i(TAG, "    Windows: ipconfig (look for IPv4 on your active adapter)");
            Log.i(TAG, "    macOS:   ifconfig | grep inet");
            Log.i(TAG, "    Linux:   ip addr show");
            Log.i(TAG, "  Example: ANDROID_API_URL=http://192.168.1.42:5000");
            Log.i(TAG, "  Set via environment variable or project-root/.env");
            Log.i(TAG, "  Fallback (emulator): ANDROID_API_URL=http://10.0.2.2:5000");
            Log.i(TAG, "──────────────────────────────────────────");
            Log.i(TAG, "Backend should be running on the laptop at port 5000.");
            Log.i(TAG, "Phone and laptop must be on the same WiFi network.");
            Log.i(TAG, "Firewall on port 5000 must allow inbound connections.");
        }

        pingBackend(baseUrl);
    }

    private static void pingBackend(final String apiBaseUrl) {
        new Thread(() -> {
            String[] endpoints = {
                apiBaseUrl + "/api/mobile/auth/ping",
                apiBaseUrl + "/api/health",
                apiBaseUrl + "/health"
            };

            boolean anyReachable = false;
            for (String endpoint : endpoints) {
                try {
                    URL url = new URL(endpoint);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);
                    int code = conn.getResponseCode();
                    StringBuilder body = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            body.append(line);
                        }
                    }
                    conn.disconnect();
                    Log.i(TAG, "Ping " + endpoint + " → HTTP " + code + (code == 200 ? " ✓" : ""));
                    if (code == 200) {
                        anyReachable = true;
                        break;
                    }
                } catch (java.net.ConnectException e) {
                    Log.w(TAG, "Ping " + endpoint + " → Connection refused");
                } catch (java.net.SocketTimeoutException e) {
                    Log.w(TAG, "Ping " + endpoint + " → Timed out (3s)");
                } catch (java.net.UnknownHostException e) {
                    Log.w(TAG, "Ping " + endpoint + " → Unknown host: " + e.getMessage());
                } catch (Exception e) {
                    Log.w(TAG, "Ping " + endpoint + " → " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }

            if (anyReachable) {
                Log.i(TAG, "Backend: REACHABLE ✓");
            } else if (BuildConfig.DEBUG) {
                Log.w(TAG, "Backend: UNREACHABLE. Check:");
                Log.w(TAG, "  1. Is the backend running? (cd backend && npm start)");
                Log.w(TAG, "  2. Is ANDROID_API_URL set to the correct LAN IP?");
                Log.w(TAG, "     Current: " + apiBaseUrl);
                Log.w(TAG, "  3. Are phone and laptop on the same WiFi?");
                Log.w(TAG, "  4. Is port 5000 open on the laptop firewall?");
                Log.w(TAG, "  The app will still start — native auth/scheduler will fail gracefully.");
            } else {
                Log.i(TAG, "Backend: production — assuming reachable via " + apiBaseUrl);
            }
        }).start();
    }
}
