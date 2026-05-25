package com.fundocareer.app;

import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class NavigationPolicy {

    private static final String TAG = "FundoCareer-NavPolicy";

    private static final Set<String> PROTECTED_ROUTES = new HashSet<>(Arrays.asList(
            "/profile",
            "/resumes",
            "/ats-checker",
            "/mock-interview",
            "/job-application",
            "/jobpage"
    ));

    private static final Set<String> PUBLIC_ASSET_EXTS = new HashSet<>(Arrays.asList(
            ".css", ".js", ".json", ".map", ".ts", ".tsx", ".jsx",
            ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".ico", ".bmp",
            ".woff", ".woff2", ".ttf", ".eot", ".otf",
            ".txt", ".xml", ".yaml", ".yml",
            ".pdf", ".doc", ".docx"
    ));

    private static final String[] AUTH_URL_PATTERNS = {
            "/api/auth/google",
            "accounts.google.com/o/oauth2",
            "accounts.google.com/signin",
            "googleapis.com/oauth",
            "/api/mobile/auth/",
    };

    private static final String[] PAYMENT_URL_PATTERNS = {
            "/api/payments/",
            "/checkout",
            "/stripe/",
    };

    private final SecureTokenStore tokenStore;

    public NavigationPolicy(SecureTokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    public boolean isUserLoggedIn() {
        return tokenStore != null && tokenStore.hasValidSession();
    }

    public boolean isHomeRoute(String url) {
        if (url == null) return false;
        String path = extractPath(url);
        return path.isEmpty() || "/".equals(path);
    }

    public boolean isPublicAsset(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = lower.substring(dot);
        return PUBLIC_ASSET_EXTS.contains(ext);
    }

    public boolean isAuthRoute(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        for (String pattern : AUTH_URL_PATTERNS) {
            if (lower.contains(pattern)) return true;
        }
        return false;
    }

    public boolean isProtectedRoute(String url) {
        if (url == null) return false;
        String path = extractPath(url);
        return PROTECTED_ROUTES.contains(path);
    }

    public boolean shouldShowLoginGate(String url) {
        return shouldShowLoginGate(url, isUserLoggedIn());
    }

    public boolean shouldShowLoginGate(String url, boolean isLoggedIn) {
        if (url == null || isLoggedIn) return false;
        if (isHomeRoute(url)) return false;
        if (isPublicAsset(url)) return false;
        if (isAuthRoute(url)) return false;
        if (!url.startsWith("http:") && !url.startsWith("https:")) return false;
        return true;
    }

    public String getPostLoginRoute() {
        return AppConfig.getDefaultUrl();
    }

    public String getHomeRoute() {
        return AppConfig.getDefaultUrl();
    }

    private String extractPath(String url) {
        if (url == null) return "";
        if (url.startsWith("/")) {
            return normalizePath(url);
        }
        try {
            return normalizePath(android.net.Uri.parse(url).getPath());
        } catch (Exception e) {
            return normalizePath(url);
        }
    }

    private String normalizePath(String path) {
        if (path == null) return "";
        if (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }
}
