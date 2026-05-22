package com.fundocareer.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;



public class AuthManager {

    private static final String TAG = "FundoCareer-Auth";

    public interface AuthCallback {
        void onSuccess(JSONObject authData);
        void onError(String error);
    }

    // POST https://www.fundocareer.com/api/auth/google  { idToken }
    // POST https://www.fundocareer.com/api/auth/refresh  { refreshToken }
    // POST https://www.fundocareer.com/api/auth/logout  { refreshToken }
    // Relative paths are appended to getApiBaseUrl() which includes /api/auth
    private static final String ENDPOINT_GOOGLE = "/google";
    private static final String ENDPOINT_REFRESH = "/refresh";
    private static final String ENDPOINT_LOGOUT = "/logout";

    // For cookie/auth domain extraction, use www.fundocareer.com
    private static final String COOKIE_DOMAIN_URL = "https://www.fundocareer.com";

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

    private String getApiBaseUrl() {
        return BuildConfig.API_BASE_URL + "/api/auth";
    }

    public void exchangeGoogleToken(String googleIdToken, AuthCallback callback) {
        Log.i("FundoCareerApp", "Auth: exchanging Google token");
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("idToken", googleIdToken);

                Log.i(TAG, "POSTing to: " + getApiBaseUrl() + ENDPOINT_GOOGLE);
                JSONObject response = httpPost(getApiBaseUrl() + ENDPOINT_GOOGLE, body.toString());

                int statusCode = response.optInt("_statusCode", 0);
                Log.i(TAG, "Token exchange status=" + statusCode);

                // Handle rawResponse: try to parse it as inner JSON
                String raw = response.optString("rawResponse", null);
                if (raw != null && !raw.isEmpty()) {
                    Log.i(TAG, "rawResponse present length=" + raw.length());
                    // Detect HTML (frontend SPA fallback serving index.html)
                    String rawLower = raw.toLowerCase(java.util.Locale.ROOT);
                    if (rawLower.contains("<!doctype html") || rawLower.contains("<html")) {
                        Log.e(TAG, "FATAL: Backend auth endpoint returned frontend HTML. API route is hitting the frontend hosting, not the backend. Fix: add API rewrite/proxy on frontend hosting, or point Android API_BASE_URL to the backend directly.");
                        postError(callback, "Backend auth endpoint returned frontend HTML. Check API proxy/route order.");
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
            postError(callback, "No refresh token available");
            return;
        }

        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("refreshToken", refreshToken);

                JSONObject response = httpPost(getApiBaseUrl() + ENDPOINT_REFRESH, body.toString());
                int statusCode = response.optInt("_statusCode", 200);

                String raw = response.optString("rawResponse", null);
                if (raw != null && !raw.isEmpty()) {
                    String rawLower = raw.toLowerCase(java.util.Locale.ROOT);
                    if (rawLower.contains("<!doctype html") || rawLower.contains("<html")) {
                        Log.e(TAG, "FATAL: Refresh endpoint returned frontend HTML. API route is hitting the frontend hosting, not the backend.");
                        postError(callback, "Refresh endpoint returned frontend HTML. Check API proxy/route order.");
                        return;
                    }
                }

                if (statusCode >= 200 && statusCode < 300) {
                    String newAccessToken = response.optString("accessToken", null);
                    if (newAccessToken == null || newAccessToken.isEmpty()) {
                        Log.e(TAG, "Token refresh FAILED: HTTP=" + statusCode + " but no accessToken in response");
                        tokenStore.clearAll();
                        postError(callback, "Refresh returned no access token");
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
                    Log.i(TAG, "Token refreshed successfully");
                    postSuccess(callback, response);
                } else {
                    Log.w(TAG, "Token refresh failed, clearing session");
                    tokenStore.clearAll();
                    postError(callback, "Refresh failed, session cleared");
                }
            } catch (Exception e) {
                Log.e(TAG, "Token refresh exception", e);
                postError(callback, e.getMessage());
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
                JSONObject response = httpPost(getApiBaseUrl() + ENDPOINT_LOGOUT, body.toString(),
                        accessToken);
                Log.i(TAG, "Backend logout: " + response.optInt("_statusCode", 0));
            } catch (Exception e) {
                Log.w(TAG, "Backend logout call failed", e);
            } finally {
                mainHandler.post(() -> {
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
                // Set cookie on the API domain
                String cookieValue = "token=" + accessToken
                        + "; Path=/"
                        + (isSecure ? "; Secure" : "")
                        + "; Max-Age=" + (30 * 24 * 60 * 60);
                CookieManager.getInstance().setCookie(host, cookieValue);
                CookieManager.getInstance().flush();
                // Also set on www domain if different
                if (!host.startsWith("www.") && host.contains(".")) {
                    CookieManager.getInstance().setCookie("www." + host, cookieValue);
                }
                Log.i(TAG, "Auth cookie set for domain: " + host + " (secure=" + isSecure + ")");
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
            String cookieValue = "token=" + accessToken
                    + "; Path=/"
                    + (isSecure ? "; Secure" : "")
                    + "; Max-Age=" + (30 * 24 * 60 * 60);
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
                + "try{"
                // Phase 5: Set localStorage keys that frontend API client reads
                + "localStorage.setItem('fundocareer_access_token','" + safeAccessToken + "');"
                + (refreshToken != null && !refreshToken.isEmpty()
                    ? "localStorage.setItem('fundocareer_refresh_token','" + safeRefreshToken + "');"
                    : "")
                + "localStorage.setItem('fundocareer_user','" + safeJs(userJson) + "');"
                + "localStorage.setItem('fundocareer_auth_state','true');"
                // Phase 5: Backward compat — existing frontend keys
                + "localStorage.setItem('FUNDOCareer_ACCESS_TOKEN','" + safeAccessToken + "');"
                + "localStorage.setItem('FUNDOCareer_USER',JSON.stringify(" + userJson + "));"
                // Phase 5: Set global auth object
                + "window.__fundocareerAuthInjected=true;"
                + "window.FundoCareerAuthBridge=window.AndroidAuthBridge;"
                + "window.__FUNDOCAREER_AUTH__={"
                + "accessToken:'" + safeAccessToken + "',"
                + "email:'" + safeEmail + "',"
                + "name:'" + safeName + "',"
                + "userId:'" + safeUserId + "',"
                + "image:'" + safeImage + "',"
                + "role:'" + safeRole + "'"
                + "};"
                // Fetch interceptor with API URL rewriting and auth header injection
                + "var origFetch=window.fetch;"
                + "window.fetch=function(u,o){"
                + "if(!o)o={};"
                + "if(!o.headers)o.headers={};"
                + "var tkn=window.__FUNDOCAREER_AUTH__&&window.__FUNDOCAREER_AUTH__.accessToken;"
                + "var url=(typeof u==='string')?u:(u&&typeof u.url==='string')?u.url:null;"
                + "if(url&&url.indexOf('/api/')>=0){"
                + "var needRewrite=(url.indexOf('" + BuildConfig.FRONTEND_URL + "/api/')===0||url.indexOf('/api/')===0);"
                + "if(needRewrite){url='" + BuildConfig.API_BASE_URL + "'+url.substring(url.indexOf('/api/'));}"
                + "if(typeof u==='string'){"
                + "if(tkn&&!o.headers['Authorization']&&!o.headers['authorization']){o.headers['Authorization']='Bearer '+tkn;}"
                + "if(needRewrite)u=url;"
                + "return origFetch.call(window,u,o);"
                + "}else{"
                + "var h=new Headers(u.headers);"
                + "if(tkn&&!h.has('Authorization'))h.set('Authorization','Bearer '+tkn);"
                + "return origFetch.call(window,new Request(url,{method:u.method,headers:h,body:u.body,mode:u.mode,credentials:u.credentials,cache:u.cache,redirect:u.redirect,referrer:u.referrer,integrity:u.integrity}),o);"
                + "}"
                + "}"
                + "return origFetch.call(window,u,o);"
                + "};"
                // XMLHttpRequest interceptor with API URL rewriting
                + "var origOpen=XMLHttpRequest.prototype.open;"
                + "XMLHttpRequest.prototype.open=function(m,u,a){"
                + "this.__xhrUrl=u;"
                + "if(typeof u==='string'&&u.indexOf('/api/')>=0){"
                + "if(u.indexOf('" + BuildConfig.FRONTEND_URL + "/api/')===0||u.indexOf('/api/')===0){"
                + "u='" + BuildConfig.API_BASE_URL + "'+u.substring(u.indexOf('/api/'));"
                + "}"
                + "}"
                + "return origOpen.call(this,m,u,a);"
                + "};"
                + "var origSend=XMLHttpRequest.prototype.send;"
                + "XMLHttpRequest.prototype.send=function(b){"
                + "var tkn=window.__FUNDOCAREER_AUTH__&&window.__FUNDOCAREER_AUTH__.accessToken;"
                + "if(tkn&&typeof this.__xhrUrl==='string'&&this.__xhrUrl.indexOf('/api/')>=0){"
                + "this.setRequestHeader('Authorization','Bearer '+tkn);"
                + "}"
                + "return origSend.call(this,b);"
                + "};"
                // Phase 5: Dispatch both old and new custom events
                + "try{"
                + "document.dispatchEvent(new CustomEvent('fundocareer-auth-ready',{detail:window.__FUNDOCAREER_AUTH__}));"
                + "}catch(ex){}"
                + "try{"
                + "window.dispatchEvent(new CustomEvent('fundocareer:auth-updated',{detail:{source:'android-native',isAuthenticated:true,user:" + userJson + "}}));"
                + "}catch(ex){}"
                + "console.log('[FundoCareer] Auth fully injected');"
                + "}catch(e){console.warn('[FundoCareer] Auth inject error:',e);}"
                + "})()";
        webView.evaluateJavascript(js, null);
        Log.i(TAG, "Auth state injected into WebView [" + safeEmail + "]");
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
                + "try{"
                + "localStorage.setItem('fundocareer_access_token','" + safeAccessToken + "');"
                + (refreshToken != null && !refreshToken.isEmpty()
                    ? "localStorage.setItem('fundocareer_refresh_token','" + safeRefreshToken + "');"
                    : "")
                + "localStorage.setItem('fundocareer_user','" + safeJs(userJson) + "');"
                + "localStorage.setItem('fundocareer_auth_state','true');"
                + "localStorage.setItem('FUNDOCareer_ACCESS_TOKEN','" + safeAccessToken + "');"
                + "localStorage.setItem('FUNDOCareer_USER',JSON.stringify(" + userJson + "));"
                + "window.__fundocareerAuthInjected=true;"
                + "window.FundoCareerAuthBridge=window.AndroidAuthBridge;"
                + "window.__FUNDOCAREER_AUTH__={"
                + "accessToken:'" + safeAccessToken + "',"
                + "email:'" + safeEmail + "',"
                + "name:'" + safeName + "',"
                + "userId:'" + safeUserId + "',"
                + "image:'" + safeImage + "',"
                + "role:'" + safeRole + "'"
                + "};"
                + "var origFetch=window.fetch;"
                + "window.fetch=function(u,o){"
                + "if(!o)o={};"
                + "if(!o.headers)o.headers={};"
                + "var tkn=window.__FUNDOCAREER_AUTH__&&window.__FUNDOCAREER_AUTH__.accessToken;"
                + "var url=(typeof u==='string')?u:(u&&typeof u.url==='string')?u.url:null;"
                + "if(url&&url.indexOf('/api/')>=0){"
                + "var needRewrite=(url.indexOf('" + BuildConfig.FRONTEND_URL + "/api/')===0||url.indexOf('/api/')===0);"
                + "if(needRewrite){url='" + BuildConfig.API_BASE_URL + "'+url.substring(url.indexOf('/api/'));}"
                + "if(typeof u==='string'){"
                + "if(tkn&&!o.headers['Authorization']&&!o.headers['authorization']){o.headers['Authorization']='Bearer '+tkn;}"
                + "if(needRewrite)u=url;"
                + "return origFetch.call(window,u,o);"
                + "}else{"
                + "var h=new Headers(u.headers);"
                + "if(tkn&&!h.has('Authorization'))h.set('Authorization','Bearer '+tkn);"
                + "return origFetch.call(window,new Request(url,{method:u.method,headers:h,body:u.body,mode:u.mode,credentials:u.credentials,cache:u.cache,redirect:u.redirect,referrer:u.referrer,integrity:u.integrity}),o);"
                + "}"
                + "}"
                + "return origFetch.call(window,u,o);"
                + "};"
                // XMLHttpRequest interceptor with API URL rewriting
                + "var origOpen=XMLHttpRequest.prototype.open;"
                + "XMLHttpRequest.prototype.open=function(m,u,a){"
                + "this.__xhrUrl=u;"
                + "if(typeof u==='string'&&u.indexOf('/api/')>=0){"
                + "if(u.indexOf('" + BuildConfig.FRONTEND_URL + "/api/')===0||u.indexOf('/api/')===0){"
                + "u='" + BuildConfig.API_BASE_URL + "'+u.substring(u.indexOf('/api/'));"
                + "}"
                + "}"
                + "return origOpen.call(this,m,u,a);"
                + "};"
                + "var origSend=XMLHttpRequest.prototype.send;"
                + "XMLHttpRequest.prototype.send=function(b){"
                + "var tkn=window.__FUNDOCAREER_AUTH__&&window.__FUNDOCAREER_AUTH__.accessToken;"
                + "if(tkn&&typeof this.__xhrUrl==='string'&&this.__xhrUrl.indexOf('/api/')>=0){"
                + "this.setRequestHeader('Authorization','Bearer '+tkn);"
                + "}"
                + "return origSend.call(this,b);"
                + "};"
                + "document.dispatchEvent(new CustomEvent('fundocareer-auth-ready',{detail:window.__FUNDOCAREER_AUTH__}));"
                + "window.dispatchEvent(new CustomEvent('fundocareer:auth-updated',{detail:{source:'android-native',isAuthenticated:true,user:" + userJson + "}}));"
                + "console.log('[FundoCareer] Auth fully injected via explicit params');"
                + "}catch(e){console.warn('[FundoCareer] Auth inject error:',e);}"
                + "})()";
        webView.evaluateJavascript(js, null);
        Log.i(TAG, "Auth localStorage injected: hasAccessToken=" + (accessToken != null && !accessToken.isEmpty()) + " hasUser=" + (email != null && !email.isEmpty()));
    }

    public void verifyProfileAfterInjection(WebView webView) {
        if (webView == null) return;
        String js = "(function(){"
                + "try{"
                + "var tkn=localStorage.getItem('fundocareer_access_token');"
                + "console.log('[FundoCareer-Verify] localStorage token length='+(tkn?tkn.length:0));"
                + "if(tkn&&tkn.length>10){"
                + "fetch('/api/user/profile',{credentials:'include',headers:{Authorization:'Bearer '+tkn}})"
                + ".then(function(r){return r.status;})"
                + ".then(function(s){console.log('[FundoCareer-Verify] /api/user/profile status='+s);})"
                + ".catch(function(e){console.log('[FundoCareer-Verify] fetch error:',e);});"
                + "}"
                + "}catch(e){console.log('[FundoCareer-Verify] error:',e);}"
                + "})()";
        webView.evaluateJavascript(js, null);
        Log.i(TAG, "Profile verification dispatched to WebView");
    }

    public void handleHttpError(WebView webView, int statusCode, String url) {
        if (statusCode != 401) return;
        Log.w(TAG, "HTTP 401 for " + url);

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
                    Log.e(TAG, "Refresh failed after 401, force logout: " + error);
                    logout(webView, () -> {
                        if (webView != null) {
                            webView.post(() -> webView.loadUrl(BuildConfig.FRONTEND_URL + "/"));
                        }
                    });
                }
            });
        } else {
            Log.w(TAG, "No refresh token, force logout on 401");
            logout(webView, () -> {
                if (webView != null) {
                    webView.post(() -> webView.loadUrl(BuildConfig.FRONTEND_URL + "/"));
                }
            });
        }
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
