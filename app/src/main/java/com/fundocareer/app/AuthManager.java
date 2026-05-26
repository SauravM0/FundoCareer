package com.fundocareer.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;
import com.fundocareer.app.core.jobalerts.JobAlertLifecycle;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;



public class AuthManager {

    private static final String TAG = "FundoCareer-Auth";

    public interface AuthCallback {
        void onSuccess(JSONObject authData);
        void onError(String error);
    }

    // POST https://www.fundocareer.com/api/mobile/auth/google/android-token  { idToken, deviceId }
    // POST https://www.fundocareer.com/api/mobile/auth/refresh  { refreshToken }
    // POST https://www.fundocareer.com/api/mobile/auth/logout  { refreshToken }
    // Relative paths are appended to getApiBaseUrl() which includes /api/mobile/auth
    private static final String ENDPOINT_GOOGLE = "/google/android-token";
    private static final String ENDPOINT_REFRESH = "/refresh";
    private static final String ENDPOINT_LOGOUT = "/logout";

    // For cookie/auth domain extraction, use www.fundocareer.com
    private static final String COOKIE_DOMAIN_URL = "https://www.fundocareer.com";

    // Definite auth invalidation codes that should trigger token clearing
    private static final java.util.Set<String> CLEAR_TOKEN_CODES = new java.util.HashSet<>(java.util.Arrays.asList(
        "REFRESH_TOKEN_INVALID",
        "REFRESH_TOKEN_REVOKED",
        "REFRESH_TOKEN_EXPIRED",
        "USER_DISABLED",
        "USER_NOT_FOUND",
        "GOOGLE_TOKEN_INVALID"
    ));

    private final Context context;
    private final SecureTokenStore tokenStore;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean logoutInProgress = false;

    public AuthManager(Context context, SecureTokenStore tokenStore) {
        this.context = context.getApplicationContext();
        this.tokenStore = tokenStore;
    }

    public SecureTokenStore getTokenStore() {
        return tokenStore;
    }

    public boolean isLoggedIn() {
        return tokenStore.hasValidSession();
    }

    private String getDeviceId() {
        try {
            String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId != null && !androidId.isEmpty() && !"9774d56d682e549c".equals(androidId)) {
                return androidId;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get ANDROID_ID, using UUID fallback");
        }
        String uuid = tokenStore.getUserDeviceId();
        if (uuid == null || uuid.isEmpty()) {
            uuid = UUID.randomUUID().toString();
            tokenStore.saveDeviceId(uuid);
        }
        return uuid;
    }

    private String getApiBaseUrl() {
        return BuildConfig.API_BASE_URL + "/api/mobile/auth";
    }

    public void exchangeGoogleToken(String googleIdToken, AuthCallback callback) {
        Log.i("FundoCareerApp", "Auth: exchanging Google token via mobile route");
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("idToken", googleIdToken);
                body.put("deviceId", getDeviceId());
                body.put("platform", "android");

                String routeUrl = getApiBaseUrl() + ENDPOINT_GOOGLE;
                Log.i(TAG, "POST route=" + routeUrl + " hasDeviceId=" + (getDeviceId() != null));
                JSONObject response = httpPost(routeUrl, body.toString());

                int statusCode = response.optInt("_statusCode", 0);
                Log.i(TAG, "Token exchange status=" + statusCode);

                // Handle rawResponse: try to parse it as inner JSON
                String raw = response.optString("rawResponse", null);
                if (raw != null && !raw.isEmpty()) {
                    Log.i(TAG, "rawResponse present length=" + raw.length());
                    // Detect HTML (frontend SPA fallback serving index.html)
                    String rawLower = raw.toLowerCase(java.util.Locale.ROOT);
                    if (rawLower.contains("<!doctype html") || rawLower.contains("<html")) {
                        String msg;
                        if (BuildConfig.DEBUG && !BuildConfig.API_BASE_URL.contains(BuildConfig.FRONTEND_URL)) {
                            msg = "API URL \"" + BuildConfig.API_BASE_URL + "\" returned HTML, not JSON. "
                                + "Likely pointing to the frontend instead of the Node backend. "
                                + "Set ANDROID_API_URL to the LAN IP of the machine running the backend (e.g. http://192.168.1.42:5000). "
                                + "Ensure backend is running on port 5000 and firewall allows inbound.";
                        } else {
                            msg = "Backend auth endpoint returned frontend HTML. Check API proxy/route order.";
                        }
                        Log.e(TAG, msg);
                        postError(callback, msg);
                        return;
                    }
                    try {
                        JSONObject parsed = new JSONObject(raw);
                        Log.i(TAG, "rawResponse parsed as JSON, inner keys=" + parsed.names());
                        // Merge parsed fields into response, but keep _statusCode
                        for (java.util.Iterator<String> it = parsed.keys(); it.hasNext();) {
                            String key = it.next();
                            if (!response.has(key)) {
                                response.put(key, parsed.get(key));
                            }
                        }
                    } catch (Exception parseErr) {
                        Log.w(TAG, "rawResponse not JSON, content preview: " + raw.substring(0, Math.min(200, raw.length())));
                        postError(callback, "Backend returned non-JSON response (HTTP " + statusCode + ")");
                        return;
                    }
                }

                // Now check top-level keys
                boolean hasSuccess = response.optBoolean("success", false);
                String accessToken = response.optString("accessToken", null);
                String refreshToken = response.optString("refreshToken", null);

                Log.i(TAG, "hasAccessToken=" + (accessToken != null && !accessToken.isEmpty()) + " length=" + (accessToken != null ? accessToken.length() : 0));
                Log.i(TAG, "hasRefreshToken=" + (refreshToken != null && !refreshToken.isEmpty()) + " length=" + (refreshToken != null ? refreshToken.length() : 0));

                if (statusCode >= 200 && statusCode < 300 && accessToken != null && !accessToken.isEmpty()) {
                    long expiresIn = response.optLong("expiresIn", 3600);
                    long expiryMs = System.currentTimeMillis() + (expiresIn * 1000);

                    JSONObject user = response.optJSONObject("user");
                    String userId = user != null ? user.optString("id", "") : response.optString("userId", "");
                    String email = user != null ? user.optString("email", "") : response.optString("email", "");
                    String name = user != null ? user.optString("name", "") : response.optString("name", "");
                    String image = user != null ? user.optString("image", "") : "";
                    String role = user != null ? user.optString("role", "") : response.optString("role", "");

                    Log.i(TAG, "hasUser=" + (user != null) + " email=" + (email != null && !email.isEmpty() ? email.substring(0, Math.min(2, email.length())) + "***" : "missing"));

                    tokenStore.saveTokens(accessToken, refreshToken, googleIdToken,
                            email, name, userId, image, role, expiryMs);
                    Log.i(TAG, "Token exchange succeeded for '" + email + "'");
                    postSuccess(callback, response);
                } else {
                    String errMsg = response.optString("message", response.optString("error", "Token exchange failed"));
                    String errCode = response.optString("code", "");
                    Log.e(TAG, "Token exchange FAILED: HTTP=" + statusCode + " code=" + errCode + " message=" + errMsg);
                    postError(callback, errCode + ": " + errMsg);
                }
            } catch (Exception e) {
                Log.e(TAG, "Token exchange exception", e);
                postError(callback, e.getMessage());
            }
        });
    }

    public void refreshAccessToken(AuthCallback callback) {
        String refreshToken = tokenStore.getRefreshToken();
        if (refreshToken == null || refreshToken.isEmpty()) {
            Log.w(TAG, "Refresh: no refresh token available — keeping session (may be first login)");
            postError(callback, "No refresh token available");
            return;
        }

        executor.execute(() -> {
            String routeUrl = getApiBaseUrl() + ENDPOINT_REFRESH;
            try {
                JSONObject body = new JSONObject();
                body.put("refreshToken", refreshToken);

                Log.i(TAG, "Refresh: POST route=" + routeUrl);
                JSONObject response = httpPost(routeUrl, body.toString());
                int statusCode = response.optInt("_statusCode", 200);

                String raw = response.optString("rawResponse", null);
                if (raw != null && !raw.isEmpty()) {
                    String rawLower = raw.toLowerCase(java.util.Locale.ROOT);
                    if (rawLower.contains("<!doctype html") || rawLower.contains("<html")) {
                        Log.e(TAG, "Refresh: endpoint returned frontend HTML — backend unreachable, keeping session");
                        postError(callback, "Refresh: backend unreachable (HTML response), session preserved");
                        return;
                    }
                }

                String errorCode = response.optString("code", "");

                if (statusCode >= 200 && statusCode < 300) {
                    String newAccessToken = response.optString("accessToken", null);
                    if (newAccessToken == null || newAccessToken.isEmpty()) {
                        Log.e(TAG, "Refresh: HTTP=" + statusCode + " but no accessToken — clearing tokens as protocol violation");
                        tokenStore.clearAll();
                        postError(callback, "Refresh returned no access token — tokens cleared");
                        return;
                    }

                    long expiresIn = response.optLong("expiresIn", 3600);
                    long expiryMs = System.currentTimeMillis() + (expiresIn * 1000);

                    JSONObject user = response.optJSONObject("user");
                    String email = user != null ? user.optString("email", "") : tokenStore.getUserEmail();
                    String name = user != null ? user.optString("name", "") : tokenStore.getUserName();
                    String image = user != null ? user.optString("image", "") : tokenStore.getUserImage();
                    String role = user != null ? user.optString("role", "") : tokenStore.getUserRole();
                    String userId = user != null ? user.optString("id", "") : tokenStore.getUserId();

                    tokenStore.saveTokens(
                            newAccessToken,
                            refreshToken,
                            tokenStore.getIdToken(),
                            email,
                            name,
                            userId,
                            image,
                            role,
                            expiryMs
                    );
                    Log.i(TAG, "Refresh: success — tokens kept");
                    postSuccess(callback, response);
                } else {
                    boolean shouldClear = CLEAR_TOKEN_CODES.contains(errorCode);
                    if (shouldClear) {
                        Log.w(TAG, "Refresh: FAILED HTTP=" + statusCode + " code=" + errorCode + " — clearing session");
                        tokenStore.clearAll();
                        postError(callback, "Refresh failed: " + errorCode + " — session cleared");
                    } else {
                        Log.w(TAG, "Refresh: FAILED HTTP=" + statusCode + " code=" + errorCode + " — keeping session (transient)");
                        postError(callback, "Refresh failed: " + errorCode + " — session preserved");
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Refresh: exception — keeping session: " + e.getMessage());
                postError(callback, "Refresh exception: " + e.getMessage() + " — session preserved");
            }
        });
    }

    public void logout(WebView webView, Runnable onComplete) {
        if (logoutInProgress) return;
        logoutInProgress = true;

        String accessToken = tokenStore.getAccessToken();
        String refreshToken = tokenStore.getRefreshToken();

        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                if (refreshToken != null) body.put("refreshToken", refreshToken);
                body.put("deviceId", getDeviceId());
                String routeUrl = getApiBaseUrl() + ENDPOINT_LOGOUT;
                Log.i(TAG, "Logout: POST route=" + routeUrl);
                JSONObject response = httpPost(routeUrl, body.toString(), accessToken);
                Log.i(TAG, "Backend logout: HTTP=" + response.optInt("_statusCode", 0));
            } catch (Exception e) {
                Log.w(TAG, "Backend logout call failed", e);
            } finally {
                mainHandler.post(() -> {
                    String pausedEmail = tokenStore.getUserEmail();
                    JobAlertLifecycle.onLogout(context, pausedEmail);

                    try {
                        clearAuthCookies();
                    } catch (Exception e) {
                        Log.w(TAG, "Auth cookie clear error", e);
                    }
                    try {
                        if (webView != null) {
                            clearWebViewStorage(webView);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "WebView clear error", e);
                    }

                    tokenStore.clearAll();
                    logoutInProgress = false;
                    Log.i(TAG, "Full logout complete");

                    if (onComplete != null) {
                        onComplete.run();
                    }
                });
            }
        });
    }

    /**
     * Remove auth cookies so WebView requests are no longer authenticated.
     */
    public void clearAuthCookies() {
        try {
            String apiBase = getApiBaseUrl();
            URL url = new URL(apiBase);
            String host = url.getHost();
            String clearCookie = "token=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 UTC";
            CookieManager.getInstance().setCookie(host, clearCookie);
            if (!host.startsWith("www.") && host.contains(".")) {
                CookieManager.getInstance().setCookie("www." + host, clearCookie);
            }
            CookieManager.getInstance().flush();
            Log.i(TAG, "Auth cookies cleared");
        } catch (Exception e) {
            Log.w(TAG, "clearAuthCookies error", e);
        }
    }

    public void clearWebViewStorage(WebView webView) {
        if (webView == null) return;
        try {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            webView.clearCache(true);
            webView.clearHistory();
            webView.clearFormData();
            webView.evaluateJavascript(
                    "(function(){"
                    + "try{localStorage.clear();}catch(e){}"
                    + "try{sessionStorage.clear();}catch(e){}"
                    + "try{var c=document.cookie.split(';');"
                    + "for(var i=0;i<c.length;i++){"
                    + "document.cookie=c[i].split('=')[0]+'=;expires=Thu,01 Jan 1970 00:00:00 UTC;path=/';"
                    + "}}catch(e){}"
                    + "window.__fundocareerAuthInjected=false;"
                    + "window.__FUNDOCAREER_AUTH__=null;"
                    + "window.FundoCareerAuthBridge=null;"
                    + "console.log('[FundoCareer] WebView storage cleared');"
                    + "})()", null
            );
            Log.i(TAG, "WebView storage cleared");
        } catch (Exception e) {
            Log.w(TAG, "Error clearing WebView storage", e);
        }
    }

    /**
     * Set auth cookies on the API domain so WebView HTTP requests include the JWT.
     */
    public void setAuthCookies() {
        try {
            String accessToken = tokenStore.getAccessToken();
            if (accessToken == null || accessToken.isEmpty()) {
                Log.w(TAG, "setAuthCookies: no access token available");
                return;
            }
            String apiBase = getApiBaseUrl();
            try {
                URL url = new URL(apiBase);
                String host = url.getHost();
                boolean isSecure = "https".equalsIgnoreCase(url.getProtocol());
                // Use actual token expiry instead of hardcoded 30 days
                long expiresIn = tokenStore.getTokenExpiry();
                long maxAgeSec = Math.max(1, (expiresIn - System.currentTimeMillis()) / 1000);
                // Set cookie on the API domain
                String cookieValue = "token=" + accessToken
                        + "; Path=/"
                        + (isSecure ? "; Secure" : "")
                        + "; Max-Age=" + maxAgeSec;
                CookieManager.getInstance().setCookie(host, cookieValue);
                CookieManager.getInstance().flush();
                // Also set on www domain if different
                if (!host.startsWith("www.") && host.contains(".")) {
                    CookieManager.getInstance().setCookie("www." + host, cookieValue);
                }
                CookieManager.getInstance().flush();
                Log.i(TAG, "Auth cookie set for domain: " + host + " (secure=" + isSecure + " maxAge=" + maxAgeSec + "s)");
            } catch (Exception e) {
                Log.w(TAG, "Failed to set auth cookie: " + e.getMessage());
            }
        } catch (Exception e) {
            Log.w(TAG, "setAuthCookies error", e);
        }
    }

    public void setAuthCookies(String accessToken) {
        try {
            if (accessToken == null || accessToken.isEmpty()) {
                Log.w(TAG, "setAuthCookies: no access token provided");
                return;
            }
            String apiBase = getApiBaseUrl();
            URL url = new URL(apiBase);
            String host = url.getHost();
            boolean isSecure = "https".equalsIgnoreCase(url.getProtocol());
            long expiresIn = tokenStore.getTokenExpiry();
            long maxAgeSec = Math.max(1, (expiresIn - System.currentTimeMillis()) / 1000);
            String cookieValue = "token=" + accessToken
                    + "; Path=/"
                    + (isSecure ? "; Secure" : "")
                    + "; Max-Age=" + maxAgeSec;
            CookieManager.getInstance().setCookie(host, cookieValue);
            CookieManager.getInstance().flush();
            if (!host.startsWith("www.") && host.contains(".")) {
                CookieManager.getInstance().setCookie("www." + host, cookieValue);
            }
            CookieManager.getInstance().flush();
            Log.i(TAG, "Auth cookie set: domain=" + host + " hasAccessToken=" + (accessToken != null && !accessToken.isEmpty()));
        } catch (Exception e) {
            Log.w(TAG, "setAuthCookies error: " + e.getMessage());
        }
    }

    public void injectAuthState(WebView webView) {
        if (webView == null || !isLoggedIn()) return;

        String accessToken = tokenStore.getAccessToken();
        String refreshToken = tokenStore.getRefreshToken();
        String email = tokenStore.getUserEmail();
        String name = tokenStore.getUserName();
        String userId = tokenStore.getUserId();
        String image = tokenStore.getUserImage();
        String role = tokenStore.getUserRole();

        String safeAccessToken = safeJs(accessToken);
        String safeRefreshToken = safeJs(refreshToken);
        String safeEmail = safeJs(email);
        String safeName = safeJs(name);
        String safeUserId = safeJs(userId);
        String safeImage = safeJs(image);
        String safeRole = safeJs(role);

        // Build user JSON object string safely
        String userJson = "{"
                + "\"id\":\"" + safeUserId + "\","
                + "\"email\":\"" + safeEmail + "\","
                + "\"name\":\"" + safeName + "\","
                + "\"image\":\"" + safeImage + "\","
                + "\"role\":\"" + safeRole + "\""
                + "}";

        String js = "(function(){"
                // Always update auth data (tokens may have changed between calls)
                + "try{"
                + "localStorage.setItem('fundocareer_access_token','" + safeAccessToken + "');"
                + (refreshToken != null && !refreshToken.isEmpty()
                    ? "localStorage.setItem('fundocareer_refresh_token','" + safeRefreshToken + "');"
                    : "")
                + "localStorage.setItem('fundocareer_user','" + safeJs(userJson) + "');"
                + "localStorage.setItem('fundocareer_auth_state','true');"
                + "localStorage.setItem('FUNDOCareer_ACCESS_TOKEN','" + safeAccessToken + "');"
                + "localStorage.setItem('FUNDOCareer_USER',JSON.stringify(" + userJson + "));"
                + "}catch(e){}"
                + "window.FundoCareerAuthBridge=window.AndroidAuthBridge;"
                + "window.__FUNDOCAREER_AUTH__={"
                + "accessToken:'" + safeAccessToken + "',"
                + "email:'" + safeEmail + "',"
                + "name:'" + safeName + "',"
                + "userId:'" + safeUserId + "',"
                + "image:'" + safeImage + "',"
                + "role:'" + safeRole + "'"
                + "};"
                // Only set up interceptors once per page load
                + "if(window.__fundocareerAuthInjected)return;"
                + "window.__fundocareerAuthInjected=true;"
                + getFetchInterceptorJs()
                + getXhrInterceptorJs()
                // Dispatch events
                + "try{"
                + "document.dispatchEvent(new CustomEvent('fundocareer-auth-ready',{detail:window.__FUNDOCAREER_AUTH__}));"
                + "}catch(ex){}"
                + "try{"
                + "window.dispatchEvent(new CustomEvent('fundocareer:auth-updated',{detail:{source:'android-native',isAuthenticated:true,user:" + userJson + "}}));"
                + "}catch(ex){}"
                + "console.log('[FundoCareer] Auth fully injected');"
                + "})()";
        webView.evaluateJavascript(js, null);
        String routingMode = (BuildConfig.DEBUG && !BuildConfig.FRONTEND_URL.equals(BuildConfig.API_BASE_URL)) ? "LOCAL (rewritten)" : "PRODUCTION (no rewriting)";
        Log.i(TAG, "Auth injected: WebView API routing=" + routingMode + " [" + safeEmail + "]");
    }

    public void injectAuthState(WebView webView, String accessToken, String refreshToken, String email, String name, String userId, String image, String role) {
        if (webView == null) return;
        if (accessToken == null || accessToken.isEmpty()) {
            Log.w(TAG, "injectAuthState: no access token provided, skipping");
            return;
        }

        String safeAccessToken = safeJs(accessToken);
        String safeRefreshToken = safeJs(refreshToken);
        String safeEmail = safeJs(email);
        String safeName = safeJs(name);
        String safeUserId = safeJs(userId);
        String safeImage = safeJs(image);
        String safeRole = safeJs(role);

        String userJson = "{"
                + "\"id\":\"" + safeUserId + "\","
                + "\"email\":\"" + safeEmail + "\","
                + "\"name\":\"" + safeName + "\","
                + "\"image\":\"" + safeImage + "\","
                + "\"role\":\"" + safeRole + "\""
                + "}";

        String js = "(function(){"
                // Always update auth data (tokens may have changed)
                + "try{"
                + "localStorage.setItem('fundocareer_access_token','" + safeAccessToken + "');"
                + (refreshToken != null && !refreshToken.isEmpty()
                    ? "localStorage.setItem('fundocareer_refresh_token','" + safeRefreshToken + "');"
                    : "")
                + "localStorage.setItem('fundocareer_user','" + safeJs(userJson) + "');"
                + "localStorage.setItem('fundocareer_auth_state','true');"
                + "localStorage.setItem('FUNDOCareer_ACCESS_TOKEN','" + safeAccessToken + "');"
                + "localStorage.setItem('FUNDOCareer_USER',JSON.stringify(" + userJson + "));"
                + "}catch(e){}"
                + "window.FundoCareerAuthBridge=window.AndroidAuthBridge;"
                + "window.__FUNDOCAREER_AUTH__={"
                + "accessToken:'" + safeAccessToken + "',"
                + "email:'" + safeEmail + "',"
                + "name:'" + safeName + "',"
                + "userId:'" + safeUserId + "',"
                + "image:'" + safeImage + "',"
                + "role:'" + safeRole + "'"
                + "};"
                // Only set up interceptors once per page load
                + "if(window.__fundocareerAuthInjected)return;"
                + "window.__fundocareerAuthInjected=true;"
                + getFetchInterceptorJs()
                + getXhrInterceptorJs()
                + "document.dispatchEvent(new CustomEvent('fundocareer-auth-ready',{detail:window.__FUNDOCAREER_AUTH__}));"
                + "window.dispatchEvent(new CustomEvent('fundocareer:auth-updated',{detail:{source:'android-native',isAuthenticated:true,user:" + userJson + "}}));"
                + "console.log('[FundoCareer] Auth fully injected via explicit params');"
                + "})()";
        webView.evaluateJavascript(js, null);
        String routingMode = (BuildConfig.DEBUG && !BuildConfig.FRONTEND_URL.equals(BuildConfig.API_BASE_URL)) ? "LOCAL (rewritten)" : "PRODUCTION (no rewriting)";
        Log.i(TAG, "Auth injected (explicit params): WebView API routing=" + routingMode + " hasAccessToken=" + (accessToken != null && !accessToken.isEmpty()) + " hasUser=" + (email != null && !email.isEmpty()));
    }

    private String getFetchInterceptorJs() {
        if (BuildConfig.DEBUG && !BuildConfig.FRONTEND_URL.equals(BuildConfig.API_BASE_URL)) {
            String apiBaseUrl = safeJs(BuildConfig.API_BASE_URL);
            String frontendUrl = safeJs(BuildConfig.FRONTEND_URL);
            return "var origFetch=window.fetch;window.fetch=function(u,o){if(!o)o={};if(!o.headers)o.headers={};var tkn=window.__FUNDOCAREER_AUTH__&&window.__FUNDOCAREER_AUTH__.accessToken;if(typeof u==='string'){if(u.indexOf('/api/')===0){u='" + apiBaseUrl + "'+u;}else if(u.indexOf('" + frontendUrl + "/api/')>=0){u=u.replace('" + frontendUrl + "','" + apiBaseUrl + "');}if(tkn&&!o.headers['Authorization']&&!o.headers['authorization']){o.headers['Authorization']='Bearer '+tkn;}return origFetch.call(window,u,o);}else if(u&&typeof u.url==='string'&&u.url.indexOf('/api/')>=0){var nu=u.url;if(nu.indexOf('/api/')===0){nu='" + apiBaseUrl + "'+nu;}else if(nu.indexOf('" + frontendUrl + "/api/')>=0){nu=nu.replace('" + frontendUrl + "','" + apiBaseUrl + "');}var h=new Headers(u.headers);if(tkn&&!h.has('Authorization'))h.set('Authorization','Bearer '+tkn);return origFetch.call(window,new Request(nu,{method:u.method,headers:h,body:u.body,mode:u.mode,credentials:u.credentials,cache:u.cache,redirect:u.redirect,referrer:u.referrer,integrity:u.integrity}),o);}return origFetch.call(window,u,o);};";
        }
        return "var origFetch=window.fetch;window.fetch=function(u,o){if(!o)o={};if(!o.headers)o.headers={};var tkn=window.__FUNDOCAREER_AUTH__&&window.__FUNDOCAREER_AUTH__.accessToken;if(typeof u==='string'){if(tkn&&!o.headers['Authorization']&&!o.headers['authorization']){o.headers['Authorization']='Bearer '+tkn;}return origFetch.call(window,u,o);}else if(u&&typeof u.url==='string'&&u.url.indexOf('/api/')>=0){var h=new Headers(u.headers);if(tkn&&!h.has('Authorization'))h.set('Authorization','Bearer '+tkn);return origFetch.call(window,new Request(u.url,{method:u.method,headers:h,body:u.body,mode:u.mode,credentials:u.credentials,cache:u.cache,redirect:u.redirect,referrer:u.referrer,integrity:u.integrity}),o);}return origFetch.call(window,u,o);};";
    }

    private String getXhrInterceptorJs() {
        if (BuildConfig.DEBUG && !BuildConfig.FRONTEND_URL.equals(BuildConfig.API_BASE_URL)) {
            String apiBaseUrl = safeJs(BuildConfig.API_BASE_URL);
            String frontendUrl = safeJs(BuildConfig.FRONTEND_URL);
            return "var origOpen=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u,a){if(typeof u==='string'&&u.indexOf('/api/')>=0){if(u.indexOf('/api/')===0){u='" + apiBaseUrl + "'+u;}else if(u.indexOf('" + frontendUrl + "/api/')>=0){u=u.replace('" + frontendUrl + "','" + apiBaseUrl + "');}}this.__xhrUrl=u;return origOpen.call(this,m,u,a);};var origSend=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.send=function(b){var tkn=window.__FUNDOCAREER_AUTH__&&window.__FUNDOCAREER_AUTH__.accessToken;if(tkn&&typeof this.__xhrUrl==='string'&&this.__xhrUrl.indexOf('/api/')>=0){this.setRequestHeader('Authorization','Bearer '+tkn);}return origSend.call(this,b);};";
        }
        return "var origOpen=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u,a){this.__xhrUrl=u;return origOpen.call(this,m,u,a);};var origSend=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.send=function(b){var tkn=window.__FUNDOCAREER_AUTH__&&window.__FUNDOCAREER_AUTH__.accessToken;if(tkn&&typeof this.__xhrUrl==='string'&&this.__xhrUrl.indexOf('/api/')>=0){this.setRequestHeader('Authorization','Bearer '+tkn);}return origSend.call(this,b);};";
    }

    public void verifyProfileAfterInjection(WebView webView) {
        if (webView == null) return;
        String js = "(function(){"
                + "try{"
                + "var tkn=localStorage.getItem('fundocareer_access_token');"
                + "console.log('[FundoCareer-Verify] localStorage token length='+(tkn?tkn.length:0));"
                + "if(tkn&&tkn.length>10){"
                + "fetch('/api/user/profile',{credentials:'include',headers:{Authorization:'Bearer '+tkn}})"
                + ".then(function(r){console.log('[FundoCareer-Verify] /api/user/profile status='+r.status);})"
                + ".catch(function(e){console.error('[FundoCareer-Verify] fetch error:',e);});"
                + "}"
                + "}catch(e){console.error('[FundoCareer-Verify] error:',e);}"
                + "})()";
        webView.evaluateJavascript(js, null);
        Log.i(TAG, "Profile verification dispatched to WebView");
    }

    public void handleHttpError(WebView webView, int statusCode, String url) {
        if (statusCode != 401) return;
        Log.w(TAG, "HTTP 401 for " + maskUrl(url));

        if (tokenStore.hasRefreshToken()) {
            refreshAccessToken(new AuthCallback() {
                @Override
                public void onSuccess(JSONObject authData) {
                    Log.i(TAG, "Token refreshed after 401, re-injecting");
                    setAuthCookies();
                    injectAuthState(webView);
                    if (url != null && webView != null) {
                        webView.post(() -> webView.loadUrl(url));
                    }
                }

                @Override
                public void onError(String error) {
                    boolean isDefiniteAuthError = error.contains("REFRESH_TOKEN_INVALID")
                            || error.contains("REFRESH_TOKEN_REVOKED")
                            || error.contains("REFRESH_TOKEN_EXPIRED")
                            || error.contains("USER_DISABLED")
                            || error.contains("session cleared");
                    if (isDefiniteAuthError) {
                        Log.w(TAG, "Refresh failed after 401 (auth error), force logout: " + error);
                        logout(webView, () -> {
                            if (webView != null) {
                                webView.post(() -> webView.loadUrl(BuildConfig.FRONTEND_URL + "/"));
                            }
                        });
                    } else {
                        Log.w(TAG, "Refresh failed after 401 (transient), keeping session: " + error);
                    }
                }
            });
        } else {
            Log.w(TAG, "No refresh token on 401; preserving local scheduler identity and leaving explicit logout to user action");
        }
    }

    private String maskUrl(String url) {
        if (url == null) return "null";
        return url.replaceAll("token=[^&]+", "token=***")
                  .replaceAll("access_token=[^&]+", "access_token=***");
    }

    private void postSuccess(AuthCallback callback, JSONObject data) {
        if (callback != null) {
            mainHandler.post(() -> callback.onSuccess(data));
        }
    }

    private void postError(AuthCallback callback, String error) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(error));
        }
    }

    private JSONObject httpPost(String urlString, String jsonBody) throws Exception {
        return httpPost(urlString, jsonBody, null);
    }

    private JSONObject httpPost(String urlString, String jsonBody,
                                 String bearerToken) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);

            if (bearerToken != null && !bearerToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }

            if (jsonBody != null) {
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes("UTF-8"));
                }
            }

            int statusCode = conn.getResponseCode();
            Log.i(TAG, "Token exchange HTTP status=" + statusCode + " URL=" + urlString);
            StringBuilder response = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                            "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
            }

            JSONObject result = new JSONObject();
            if (response.length() > 0) {
                try {
                    result = new JSONObject(response.toString());
                } catch (Exception e) {
                    String raw = response.toString();
                    Log.w(TAG, "Response not valid JSON, storing as rawResponse. length=" + raw.length() + " preview=" + raw.substring(0, Math.min(200, raw.length())));
                    result.put("rawResponse", raw);
                    // Try to detect if it's HTML
                    if (raw.toLowerCase(java.util.Locale.ROOT).contains("<html") || raw.contains("<!DOCTYPE")) {
                        Log.w(TAG, "Response appears to be HTML (not JSON)");
                    }
                }
            }
            result.put("_statusCode", statusCode);
            Log.i(TAG, "Token exchange outer keys=" + result.names());
            return result;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String safeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
