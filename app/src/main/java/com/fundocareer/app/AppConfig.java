package com.fundocareer.app;

import android.util.Log;

public final class AppConfig {

    private static final String TAG = "FundoCareer-Config";

    private AppConfig() {}

    public static String getWebAppBaseUrl() {
        return BuildConfig.FRONTEND_URL;
    }

    public static String getApiBaseUrl() {
        return BuildConfig.API_BASE_URL;
    }

    public static String getNavUrl(String path) {
        return getWebAppBaseUrl() + path;
    }

    public static String getDefaultUrl() {
        String url = getWebAppBaseUrl();
        if (!url.endsWith("/")) url += "/";
        return url;
    }

    public static String getAllowedHost() {
        String url = getWebAppBaseUrl();
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return "fundocareer.com";
        }
    }

    public static boolean isDebug() {
        return BuildConfig.DEBUG;
    }

    public static void logConfig() {
        Log.i(TAG, "Build: " + (isDebug() ? "DEBUG" : "RELEASE"));
        Log.i(TAG, "Env: " + BuildConfig.ENV_NAME);
        Log.i(TAG, "=== ROUTING CONFIGURATION ===");
        Log.i(TAG, "[WebView] Production Website URL: " + getWebAppBaseUrl());
        String webViewApiRouting = (isDebug() && !BuildConfig.FRONTEND_URL.equals(BuildConfig.API_BASE_URL)) ? "LOCAL (rewritten via JS interceptors)" : "PRODUCTION (direct)";
        Log.i(TAG, "[WebView] API calls go to: " + webViewApiRouting);
        Log.i(TAG, "[Native Scheduler] Backend API URL: " + getApiBaseUrl());
        Log.i(TAG, "[Native Scheduler] API calls go to: " + (BuildConfig.FRONTEND_URL.equals(BuildConfig.API_BASE_URL) ? "PRODUCTION" : "LOCAL BACKEND"));
        Log.i(TAG, "==============================");
    }
}
