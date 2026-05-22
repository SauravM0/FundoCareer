package com.fundocareer.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.GeolocationPermissions;
import android.webkit.DownloadListener;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.ConsoleMessage;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.ViewCompat;

import com.getcapacitor.BridgeActivity;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends BridgeActivity {

    private static final String TAG = "FundoCareer";
    private static final String ALLOWED_HOST = AppConfig.getAllowedHost();
    private static final String PREFS_NAME = "fundocareer_nav";
    private static final String KEY_ACTIVE_TAB = "active_tab_id";
    private static final int BACK_PRESS_TIMEOUT = 2000;
    private static final int PAGE_LOAD_TIMEOUT_MS = 15000;
    private static final String NET_LOG_TAG = "FundoCareer-Network";
    private static final String NAV_LOG_TAG = "FundoCareer-Nav";

    private static final int[] TAB_IDS = {
            R.id.nav_home, R.id.nav_builder, R.id.nav_jobs,
            R.id.nav_ats, R.id.nav_profile
    };

    private FrameLayout contentFrame;
    private FrameLayout loadingOverlay;
    private ProgressBar topProgressBar;
    private boolean backPressedOnce = false;
    private boolean isTabNavigation = false;
    private boolean isInitialLoad = true;

    private int activeNavId = R.id.nav_home;
    private final java.util.Map<Integer, View> navItemViews = new java.util.HashMap<>();

    private static class NavItemData {
        final int id;
        final String label;
        final int iconRes;
        final String url;
        NavItemData(int id, String label, int iconRes, String url) {
            this.id = id; this.label = label; this.iconRes = iconRes; this.url = url;
        }
    }

    private static final NavItemData[] NAV_ITEMS = {
        new NavItemData(R.id.nav_home, "Home", R.drawable.ic_home, AppConfig.getNavUrl("/")),
        new NavItemData(R.id.nav_builder, "Resume", R.drawable.ic_builder, AppConfig.getNavUrl("/resumes")),
        new NavItemData(R.id.nav_jobs, "Jobs", R.drawable.ic_jobs, AppConfig.getNavUrl("/jobpage")),
        new NavItemData(R.id.nav_ats, "ATS", R.drawable.ic_ats, AppConfig.getNavUrl("/ats-checker")),
        new NavItemData(R.id.nav_profile, "Profile", R.drawable.ic_profile, AppConfig.getNavUrl("/profile")),
    };

    private SecureTokenStore tokenStore;
    private AuthManager authManager;
    private AuthBridge authBridge;
    private GoogleSignInClient googleSignInClient;
    private boolean authInitDone = false;
    private String webViewUserAgent;

    private ValueCallback<Uri[]> filePickerCallback;
    private boolean paymentFlowActive = false;
    private MicrophoneManager micManager;
    private boolean isGoogleLoginInProgress = false;
    private long loginStartTime = 0;
    private static final long LOGIN_WATCHDOG_MS = 15000;

    private NetworkStateMonitor networkMonitor;
    private FrameLayout offlineOverlay;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable loginWatchdog = () -> {
        if (isGoogleLoginInProgress) {
            long elapsed = System.currentTimeMillis() - loginStartTime;
            Log.w(TAG, "Login watchdog: no result after " + elapsed + "ms, resetting state");
            isGoogleLoginInProgress = false;
            loginStartTime = 0;
        }
    };
    private String loadingUrl;
    private final Runnable timeoutRunnable = () -> {
        if (loadingUrl != null) {
            String timedOut = loadingUrl;
            loadingUrl = null;
            logNetworkFailure("timeout", timedOut, "Page load exceeded " + (PAGE_LOAD_TIMEOUT_MS / 1000) + "s");
            showError("Request timed out. Tap to retry.");
        }
    };

    private PermissionCoordinator.PermissionCallback pendingMicCallback;

    private final ActivityResultLauncher<String[]> filePickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (filePickerCallback != null) {
                            filePickerCallback.onReceiveValue(
                                    uri != null ? new Uri[]{uri} : null);
                            filePickerCallback = null;
                        }
                    }
            );

    private final ActivityResultLauncher<String[]> filePickerMultipleLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.OpenMultipleDocuments(),
                    uris -> {
                        if (filePickerCallback != null) {
                            filePickerCallback.onReceiveValue(
                                    uris != null && !uris.isEmpty()
                                            ? uris.toArray(new Uri[0]) : null);
                            filePickerCallback = null;
                        }
                    }
            );

    private final ActivityResultLauncher<String> microphoneLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        PermissionCoordinator.PermissionCallback cb = pendingMicCallback;
                        pendingMicCallback = null;
                        if (cb != null) {
                            if (granted) {
                                cb.onGranted();
                            } else {
                                boolean neverAsk = PermissionCoordinator.isNeverAskAgain(
                                        MainActivity.this,
                                        android.Manifest.permission.RECORD_AUDIO);
                                cb.onDenied(neverAsk);
                            }
                        }
                    }
            );

    private final ActivityResultLauncher<Intent> googleSignInLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        isGoogleLoginInProgress = false;
                        loginStartTime = 0;
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            Log.i(TAG, "Google sign-in result received: RESULT_OK");
                            handleGoogleSignInResult(result.getData());
                        } else {
                            Log.w(TAG, "Google sign-in cancelled or failed: resultCode=" + result.getResultCode());
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        Log.i("FundoCareerApp", "onCreate started");

        AppConfig.logConfig();

        buildTabShell();
        createLoadingOverlay();
        loadingOverlay.post(() -> {
            if (loadingOverlay != null) {
                loadingOverlay.setVisibility(View.VISIBLE);
                loadingOverlay.bringToFront();
            }
        });
        setupBackButton();
        initAuth();
        handleDeepLink(getIntent());

        Log.i("FundoCareerApp", "onCreate complete");
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.i("FundoCareerApp", "onPause – stopping network monitor");
        if (networkMonitor != null) {
            networkMonitor.stopListening();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (networkMonitor != null) {
            networkMonitor.startListening((online, wasOffline) -> {
                try {
                    runOnUiThread(() -> {
                        try {
                            if (online) {
                                hideOfflineOverlay();
                                if (wasOffline) {
                                    WebView wv = getSafeWebView();
                                    if (wv != null) {
                                        String js = "window.dispatchEvent(new CustomEvent('fundocareer:connectivity',{detail:{online:true}}))";
                                        wv.evaluateJavascript(js, null);
                                        Log.i("FundoCareerApp", "Dispatched connectivity event to web app");
                                    }
                                }
                            } else {
                                showOfflineOverlay();
                            }
                        } catch (Exception e) {
                            Log.e("FundoCareerApp", "Error in network callback UI handler", e);
                        }
                    });
                } catch (Exception e) {
                    Log.e("FundoCareerApp", "Error in network callback dispatcher", e);
                }
            });
        }
        if (paymentFlowActive) {
            paymentFlowActive = false;
            Log.i(TAG, "Payment flow ended, refreshing WebView");
            WebView wv = getSafeWebView();
            if (wv != null) {
                wv.post(() -> {
                    WebView wv2 = getSafeWebView();
                    if (wv2 != null) wv2.reload();
                });
            }
        }
    }

    @Override
    protected void onUserLeaveHint() {
        try {
            super.onUserLeaveHint();
            if (micManager != null && micManager.isActive()) {
                WebView wv = getSafeWebView();
                if (wv != null) {
                    Log.i(TAG, "User leaving app, stopping mic");
                    micManager.forceStop(wv);
                }
            }
        } catch (Exception e) {
            Log.e("FundoCareerApp", "Error in onUserLeaveHint", e);
        }
    }

    @Override
    public void onDestroy() {
        Log.i("FundoCareerApp", "onDestroy – cleaning up");
        try {
            WebView wv = getSafeWebView();
            if (wv != null) {
                wv.removeJavascriptInterface("AndroidAuthBridge");
                wv.removeJavascriptInterface("FileBridge");
                wv.removeJavascriptInterface("PaymentBridge");
                wv.removeJavascriptInterface("MicrophoneBridge");
                wv.stopLoading();
                wv.loadUrl("about:blank");
                wv.onPause();
                wv.destroy();
                Log.i("FundoCareerApp", "WebView destroyed and JS bridges removed");
            }
        } catch (Exception e) {
            Log.e("FundoCareerApp", "Error destroying WebView", e);
        }
        timeoutHandler.removeCallbacksAndMessages(null);
        if (networkMonitor != null) {
            networkMonitor.stopListening();
        }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleDeepLink(intent);
    }

    private void initAuth() {
        if (authInitDone) return;
        authInitDone = true;

        tokenStore = new SecureTokenStore(this);
        authManager = new AuthManager(this, tokenStore);

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(
                GoogleSignInOptions.DEFAULT_SIGN_IN
        )
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .requestProfile()
                .build();
        googleSignInClient = GoogleSignIn.getClient(this, gso);

        authBridge = new AuthBridge(new AuthBridge.AuthBridgeListener() {
            @Override
            public void onSignInRequested() {
                requestNativeGoogleLogin("bridge-sign-in");
            }

            @Override
            public void onSignOutRequested() {
                performLogout();
            }

            @Override
            public void onTokenRefreshRequested() {
                authManager.refreshAccessToken(new AuthManager.AuthCallback() {
                    @Override
                    public void onSuccess(JSONObject data) {
                        WebView wv = getSafeWebView();
                        if (wv != null) {
                            authManager.injectAuthState(wv);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        Log.w(TAG, "Token refresh from bridge failed: " + error);
                    }
                });
            }

            @Override
            public String getAuthStateJson() {
                return tokenStore.getAuthStateJson();
            }
        });

        WebView webView = getSafeWebView();
        if (webView != null) {
            webView.addJavascriptInterface(authBridge, "AndroidAuthBridge");
            webView.addJavascriptInterface(new FileBridge(this, webView), "FileBridge");
            webView.addJavascriptInterface(new PaymentHandler.Bridge(this), "PaymentBridge");
            if (micManager != null) {
                webView.addJavascriptInterface(
                        new MicrophoneBridge(micManager), "MicrophoneBridge");
            } else {
                Log.w(TAG, "micManager null — MicrophoneBridge not registered (initAuth before buildTabShell?)");
            }
            restoreActiveTab();
        } else {
            Log.e("FundoCareerApp", "WebView null in initAuth – bridges not registered");
        }

        // Startup session restore
        if (tokenStore.hasValidSession()) {
            Log.i(TAG, "Startup: valid session found, restoring auth state");
            WebView wv2 = getSafeWebView();
            if (wv2 != null) {
                String at = tokenStore.getAccessToken();
                String rt = tokenStore.getRefreshToken();
                String em = tokenStore.getUserEmail();
                String nm = tokenStore.getUserName();
                String uid = tokenStore.getUserId();
                String img = tokenStore.getUserImage();
                String rl = tokenStore.getUserRole();
                if (at != null && !at.isEmpty()) {
                    authManager.setAuthCookies(at);
                    wv2.post(() -> {
                        WebView wv3 = getSafeWebView();
                        if (wv3 != null) {
                            authManager.injectAuthState(wv3, at, rt, em, nm, uid, img, rl);
                        }
                    });
                    Log.i(TAG, "Startup: auth cookies and state injected");
                } else {
                    Log.w(TAG, "Startup: session flag true but no access token");
                }
            }
        } else {
            Log.i(TAG, "Startup: no valid session found");
        }
    }

    private void requestNativeGoogleLogin(String reason) {
        if (isGoogleLoginInProgress) {
            long elapsed = System.currentTimeMillis() - loginStartTime;
            if (elapsed > LOGIN_WATCHDOG_MS) {
                Log.w(TAG, "Login watchdog reset: state stuck for " + elapsed + "ms (reason=" + reason + ")");
                isGoogleLoginInProgress = false;
                loginStartTime = 0;
            } else {
                Log.d(TAG, "Google login already in progress (elapsed=" + elapsed + "ms, reason=" + reason + "), ignoring");
                return;
            }
        }
        Log.i(TAG, "Native Google login requested: reason=" + reason);
        startGoogleSignIn();
    }

    private void startGoogleSignIn() {
        isGoogleLoginInProgress = true;
        loginStartTime = System.currentTimeMillis();
        Log.i(TAG, "Starting native Google Sign-In account picker");
        try {
            googleSignInClient.signOut().addOnCompleteListener(task -> {
                if (!isGoogleLoginInProgress) {
                    Log.d(TAG, "Login cancelled before signOut completed, aborting");
                    return;
                }
                Intent signInIntent = googleSignInClient.getSignInIntent();
                googleSignInLauncher.launch(signInIntent);
                Log.i(TAG, "SignInHubActivity launched");
                // Watchdog: auto-reset if no result in LOGIN_WATCHDOG_MS
                timeoutHandler.removeCallbacks(loginWatchdog);
                timeoutHandler.postDelayed(loginWatchdog, LOGIN_WATCHDOG_MS);
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Google Sign-In", e);
            Toast.makeText(this, "Sign-in unavailable", Toast.LENGTH_SHORT).show();
            isGoogleLoginInProgress = false;
            loginStartTime = 0;
        }
    }

    private void handleGoogleSignInResult(Intent data) {
        try {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            GoogleSignInAccount account = task.getResult(ApiException.class);
            String idToken = account.getIdToken();

            if (idToken == null) {
                Log.e(TAG, "Google Sign-In: ID token is null");
                Toast.makeText(this, "Sign-in failed: no ID token", Toast.LENGTH_SHORT).show();
                isGoogleLoginInProgress = false;
                loginStartTime = 0;
                timeoutHandler.removeCallbacks(loginWatchdog);
                return;
            }

            Log.i(TAG, "Google ID token received, exchanging with backend");
            showLoading();
            authManager.exchangeGoogleToken(idToken, new AuthManager.AuthCallback() {
                @Override
                public void onSuccess(JSONObject authData) {
                    // Extract tokens DIRECTLY from response, not from store
                    String accessToken = authData.optString("accessToken", null);
                    String refreshToken = authData.optString("refreshToken", null);
                    JSONObject userObj = authData.optJSONObject("user");
                    String email = "";
                    String name = "";
                    String userId = "";
                    String image = "";
                    String role = "";
                    if (userObj != null) {
                        email = userObj.optString("email", "");
                        name = userObj.optString("name", "");
                        userId = userObj.optString("id", "");
                        image = userObj.optString("image", "");
                        role = userObj.optString("role", "");
                    }

                    Log.i(TAG, "Backend exchange succeeded: hasAccessToken=" + (accessToken != null && !accessToken.isEmpty()) + " length=" + (accessToken != null ? accessToken.length() : 0));

                    // Store tokens natively
                    if (accessToken != null && !accessToken.isEmpty()) {
                        // Store tokens in native storage (async is fine for persistence)
                        authManager.getTokenStore().saveTokens(
                            accessToken,
                            refreshToken != null ? refreshToken : "",
                            idToken,
                            email, name, userId, image, role,
                            System.currentTimeMillis() + (authData.optLong("expiresIn", 3600) * 1000)
                        );
                        Log.i(TAG, "Stored native auth tokens: access=" + (accessToken != null && !accessToken.isEmpty()) + " refresh=" + (refreshToken != null && !refreshToken.isEmpty()) + " user=" + !email.isEmpty());

                        // Mask email for logging
                        String maskedEmail = email;
                        if (!email.isEmpty() && email.contains("@")) {
                            maskedEmail = email.substring(0, Math.min(2, email.indexOf('@'))) + "***@" + email.substring(email.indexOf('@') + 1);
                        }
                        Log.i(TAG, "Logged in user: " + maskedEmail);
                    } else {
                        Log.e(TAG, "Login failed: backend did not return accessToken");
                        Log.i(TAG, "Response keys: " + authData.names());
                        hideLoading();
                        isGoogleLoginInProgress = false;
                        loginStartTime = 0;
                        timeoutHandler.removeCallbacks(loginWatchdog);
                        Toast.makeText(MainActivity.this, "Sign-in failed: no access token from server", Toast.LENGTH_LONG).show();
                        return;
                    }

                    hideLoading();
                    isGoogleLoginInProgress = false;
                    loginStartTime = 0;
                    timeoutHandler.removeCallbacks(loginWatchdog);

                    WebView wv = getSafeWebView();
                    if (wv != null) {
                        // Phase 5: Set cookies USING THE EXACT TOKEN from response
                        authManager.setAuthCookies(accessToken);

                        // Phase 5: Inject auth state into WebView using explicit params
                        authManager.injectAuthState(wv, accessToken, refreshToken, email, name, userId, image, role);

                        // Phase 6: Verify profile from WebView context
                        authManager.verifyProfileAfterInjection(wv);

                        // Navigate to profile after a short delay for injection to complete
                        wv.postDelayed(() -> {
                            WebView wv2 = getSafeWebView();
                            if (wv2 != null) {
                                Log.i(TAG, "Navigate to profile after successful login");
                                wv2.loadUrl(BuildConfig.FRONTEND_URL + "/profile");
                            }
                        }, 500);
                    }
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "Auth exchange failed: " + error);
                    hideLoading();
                    isGoogleLoginInProgress = false;
                    loginStartTime = 0;
                    timeoutHandler.removeCallbacks(loginWatchdog);
                    Toast.makeText(MainActivity.this, "Sign-in failed: " + error, Toast.LENGTH_LONG).show();
                }
            });
        } catch (ApiException e) {
            Log.e(TAG, "Google Sign-In API error: status=" + e.getStatusCode());
            Toast.makeText(this, "Sign-in cancelled", Toast.LENGTH_SHORT).show();
            isGoogleLoginInProgress = false;
            loginStartTime = 0;
            timeoutHandler.removeCallbacks(loginWatchdog);
        } catch (Exception e) {
            Log.e(TAG, "Google Sign-In unexpected error", e);
            Toast.makeText(this, "Sign-in error", Toast.LENGTH_SHORT).show();
            isGoogleLoginInProgress = false;
            loginStartTime = 0;
            timeoutHandler.removeCallbacks(loginWatchdog);
        }
    }

    private void performLogout() {
        WebView wv = getSafeWebView();
        if (wv == null) return;

        Log.i(TAG, "Logout requested");
        isGoogleLoginInProgress = false;
        loginStartTime = 0;
        timeoutHandler.removeCallbacks(loginWatchdog);

        try {
            googleSignInClient.signOut();
            Log.i(TAG, "Google sign-out completed");
        } catch (Exception e) {
            Log.w(TAG, "Google sign-out failed", e);
        }

        authManager.logout(wv, () -> {
            runOnUiThread(() -> {
                setActiveNavItem(R.id.nav_home);
                WebView wv2 = getSafeWebView();
                if (wv2 != null) {
                    wv2.loadUrl(AppConfig.getDefaultUrl());
                    Log.i(TAG, "Logout complete, navigated to home");
                }
            });
        });
    }

    private void handleDeepLink(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri data = intent.getData();
        Log.i(TAG, "Deep link received: " + data.toString());

        String token = data.getQueryParameter("token");
        if (token != null && !token.isEmpty()) {
            Log.i(TAG, "Auth token from deep link");
            try {
                if (authManager != null && authManager.getTokenStore() != null) {
                    authManager.getTokenStore().saveTokens(
                            token, "", "", "", "", "", "", "", 0);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse deep link token", e);
            }
        }

        if (data.getHost() != null && data.getHost().endsWith(ALLOWED_HOST)) {
            String path = data.getPath();
            if (path != null && !path.isEmpty()) {
                String url = "https://" + data.getHost() + path;
                WebView wv = getSafeWebView();
                if (wv != null) {
                    wv.post(() -> {
                        WebView wv2 = getSafeWebView();
                        if (wv2 != null) wv2.loadUrl(url);
                    });
                }
            }
        }
    }

    private WebView getSafeWebView() {
        try {
            com.getcapacitor.Bridge bridge = getBridge();
            if (bridge != null) {
                return bridge.getWebView();
            }
        } catch (Exception e) {
            Log.e("FundoCareerApp", "Failed to get WebView from bridge", e);
        }
        return null;
    }

    private String getUrlForTabId(int tabId) {
        for (NavItemData item : NAV_ITEMS) {
            if (item.id == tabId) return item.url;
        }
        if (tabId == R.id.nav_more_smart_apply) return AppConfig.getNavUrl("/job-application");
        if (tabId == R.id.nav_more_mock) return AppConfig.getNavUrl("/mock-interview");
        if (tabId == R.id.nav_more_pricing) return AppConfig.getNavUrl("/pricing");
        return AppConfig.getDefaultUrl();
    }

    private void buildTabShell() {
        WebView webView = getSafeWebView();
        if (webView == null) {
            Log.e("FundoCareerApp", "WebView not available in buildTabShell – cannot build UI");
            return;
        }

        ViewGroup bridgeParent = (ViewGroup) webView.getParent();
        if (bridgeParent != null) {
            bridgeParent.removeView(webView);
        }

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        contentFrame = new FrameLayout(this);
        contentFrame.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        contentFrame.addView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        topProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        topProgressBar.setIndeterminate(true);
        topProgressBar.setVisibility(View.GONE);
        int barHeight = (int)(3 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight
        );
        barLp.gravity = Gravity.TOP;
        topProgressBar.setLayoutParams(barLp);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            topProgressBar.setIndeterminateTintList(
                    android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(this, R.color.primary)
                    )
            );
        }
        contentFrame.addView(topProgressBar);

        micManager = new MicrophoneManager();
        contentFrame.addView(micManager.createIndicator(this));

        networkMonitor = new NetworkStateMonitor(getApplicationContext());

        root.addView(contentFrame);

        createCapsuleNav(root);

        setContentView(root);
        configureWebView(webView);
    }

    private void configureWebView(WebView webView) {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setSupportZoom(false);
        webView.getSettings().setBuiltInZoomControls(false);
        webView.getSettings().setDisplayZoomControls(false);
        webView.getSettings().setTextZoom(100);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(true);
        webView.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        String ua = webView.getSettings().getUserAgentString();
        if (ua != null && !ua.contains("FundoCareerAndroidApp")) {
            ua = ua + " FundoCareerAndroidApp/1.0";
            webView.getSettings().setUserAgentString(ua);
        }
        webViewUserAgent = ua;

        webView.setLongClickable(false);
        webView.setOnLongClickListener(v -> true);
        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);

        webView.setOnTouchListener((v, event) -> {
            try {
                if (event.getAction() == MotionEvent.ACTION_MOVE && event.getHistorySize() > 0) {
                    WebView wv = (WebView) v;
                    float offsetY = event.getY() - event.getHistoricalY(event.getHistorySize() - 1);
                    if (wv.getScrollY() == 0 && offsetY > 0) return true;
                    float total = (float) (wv.getContentHeight() * wv.getScale());
                    float current = (float) (wv.getScrollY() + wv.getHeight());
                    if (current >= total && offsetY < 0) return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "Touch handler error", e);
            }
            return false;
        });

        WebViewClient originalClient = webView.getWebViewClient();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(view, request.getUrl().toString(), request.getUrl());
            }

            private boolean handleNavigation(WebView view, String url, Uri uri) {
                String path = uri.getPath();

                // Phase 3: Intercept all Google OAuth login URLs — never load in WebView
                if (url.contains("/api/auth/google")
                        || url.contains("accounts.google.com/o/oauth2")
                        || url.contains("accounts.google.com/signin")
                        || url.contains("googleapis.com/oauth")) {
                    Log.i(TAG, "Google OAuth intercepted from WebView: " + url);
                    requestNativeGoogleLogin("web-oauth-intercept");
                    return true;
                }

                PaymentHandler.UrlAction action = PaymentHandler.classifyUrl(url, ALLOWED_HOST);
                Log.i(NAV_LOG_TAG, "URL: " + url + " -> " + action);

                switch (action) {
                    case INTERNAL:
                        return false; // Let WebView load it

                    case PAYMENT:
                        Log.i(TAG, "Payment URL -> Custom Tab: " + url);
                        PaymentHandler.logEvent("checkout_detected", url);
                        paymentFlowActive = true;
                        PaymentHandler.openCheckout(MainActivity.this, url);
                        return true;

                    case GOOGLE_AUTH:
                        Log.i(TAG, "Google OAuth URL via PaymentHandler, intercepted");
                        requestNativeGoogleLogin("payment-handler-intercept");
                        return true;

                    case SPECIAL_SCHEME:
                        PaymentHandler.openSpecialScheme(MainActivity.this, uri);
                        return true;

                    case EXTERNAL:
                    default:
                        PaymentHandler.openExternal(MainActivity.this, url);
                        return true;
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    String reqUrl = request.getUrl().toString();

                    // Intercept API calls to production frontend and redirect to local backend
                    if (reqUrl.startsWith(BuildConfig.FRONTEND_URL + "/api/")) {
                        String localUrl = BuildConfig.API_BASE_URL
                                + reqUrl.substring(BuildConfig.FRONTEND_URL.length());
                        String method = request.getMethod();

                        // Forward GET/HEAD/OPTIONS (no body needed in shouldInterceptRequest)
                        if ("GET".equalsIgnoreCase(method)
                                || "HEAD".equalsIgnoreCase(method)
                                || "OPTIONS".equalsIgnoreCase(method)) {
                            WebResourceResponse forwarded = forwardApiRequest(
                                    localUrl, method, request.getRequestHeaders());
                            if (forwarded != null) return forwarded;
                        } else {
                            Log.d(TAG, "API body request not forwarded in shouldInterceptRequest: "
                                    + method + " " + reqUrl
                                    + " (JS interceptor handles this)");
                        }
                        // For body requests, fall through to let the JS interceptor handle it
                    }

                    boolean isMainHtml = request.isForMainFrame();
                    String url = isMainHtml ? reqUrl : null;

                    if (isMainHtml && (url.startsWith("http:") || url.startsWith("https:"))
                            && isLikelyHtmlPage(url)
                            && authManager != null && authManager.isLoggedIn()) {
                        WebResourceResponse injected = fetchAndInjectAuthState(url);
                        if (injected != null) return injected;
                    }
                }

                if (originalClient != null) {
                    try {
                        return originalClient.shouldInterceptRequest(view, request);
                    } catch (Exception e) {
                        Log.w(TAG, "Bridge error: " + e.getMessage());
                    }
                }
                return null;
            }

            private WebResourceResponse forwardApiRequest(String url, String method,
                                                           Map<String, String> headers) {
                HttpURLConnection conn = null;
                try {
                    URL urlObj = new URL(url);
                    conn = (HttpURLConnection) urlObj.openConnection();
                    conn.setRequestMethod(method);
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);

                    if (headers != null) {
                        for (Map.Entry<String, String> entry : headers.entrySet()) {
                            String key = entry.getKey();
                            if (key != null && !key.equalsIgnoreCase("Host")
                                    && !key.equalsIgnoreCase("Content-Length")
                                    && !key.equalsIgnoreCase("Transfer-Encoding")
                                    && !key.equalsIgnoreCase("Accept-Encoding")) {
                                conn.setRequestProperty(key, entry.getValue());
                            }
                        }
                    }

                    // Belt-and-suspenders: add Authorization from token store if not already present
                    // This catches API requests where the JS interceptor failed to add the header
                    // (e.g., fetch(Request) objects, timing races, etc.)
                    boolean hasAuth = false;
                    if (headers != null) {
                        for (String key : headers.keySet()) {
                            if ("authorization".equalsIgnoreCase(key)) {
                                hasAuth = true;
                                break;
                            }
                        }
                    }
                    if (!hasAuth && authManager != null && authManager.isLoggedIn()) {
                        String at = authManager.getTokenStore().getAccessToken();
                        if (at != null && !at.isEmpty()) {
                            conn.setRequestProperty("Authorization", "Bearer " + at);
                            Log.d(TAG, "forwardApiRequest: added auth header for " + method + " " + url);
                        }
                    }

                    int statusCode = conn.getResponseCode();
                    String contentType = conn.getContentType();
                    String mimeType = "application/octet-stream";
                    String encoding = "UTF-8";
                    if (contentType != null) {
                        String[] parts = contentType.split(";");
                        if (parts.length > 0) mimeType = parts[0].trim();
                        for (String part : parts) {
                            part = part.trim();
                            if (part.startsWith("charset=")) {
                                encoding = part.substring(8).trim();
                            }
                        }
                    }

                    InputStream inputStream;
                    try {
                        inputStream = conn.getInputStream();
                    } catch (Exception e) {
                        inputStream = conn.getErrorStream();
                    }
                    if (inputStream == null) return null;

                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = inputStream.read(buf)) != -1) {
                        buffer.write(buf, 0, len);
                    }
                    inputStream.close();

                    byte[] responseBytes = buffer.toByteArray();
                    InputStream responseStream = new ByteArrayInputStream(responseBytes);

                    WebResourceResponse response = new WebResourceResponse(mimeType, encoding, responseStream);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        response.setStatusCodeAndReasonPhrase(statusCode, conn.getResponseMessage());
                        Map<String, String> responseHeaders = new HashMap<>();
                        for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
                            String rKey = entry.getKey();
                            if (rKey != null && !rKey.equalsIgnoreCase("Content-Encoding")
                                    && !rKey.equalsIgnoreCase("Content-Length")
                                    && !rKey.equalsIgnoreCase("Transfer-Encoding")) {
                                responseHeaders.put(rKey, entry.getValue().get(0));
                            }
                        }
                        response.setResponseHeaders(responseHeaders);
                    }

                    Log.i(TAG, "API forwarded: " + method + " " + url + " -> " + statusCode);
                    return response;
                } catch (Exception e) {
                    Log.w(TAG, "Error forwarding API: " + url + " - " + e.getMessage());
                    return null;
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }

            private boolean isLikelyHtmlPage(String url) {
                String path = Uri.parse(url).getPath();
                if (path == null || path.equals("/") || path.isEmpty()) return true;
                int dot = path.lastIndexOf('.');
                if (dot < 0) return true;
                String ext = path.substring(dot).toLowerCase(Locale.ROOT);
                return ext.equals(".html") || ext.equals(".htm") || ext.equals(".php")
                        || ext.equals(".aspx") || ext.equals(".jsp");
            }

            private WebResourceResponse fetchAndInjectAuthState(String url) {
                HttpURLConnection conn = null;
                try {
                    URL urlObj = new URL(url);
                    conn = (HttpURLConnection) urlObj.openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                    if (webViewUserAgent != null) {
                        conn.setRequestProperty("User-Agent", webViewUserAgent);
                    }
                    String cookies = CookieManager.getInstance().getCookie(url);
                    if (cookies != null && !cookies.isEmpty()) {
                        conn.setRequestProperty("Cookie", cookies);
                    }
                    int statusCode = conn.getResponseCode();
                    String contentType = conn.getContentType();
                    if (contentType == null || !contentType.contains("text/html")) {
                        Log.i(TAG, "Non-HTML response for " + url + ": " + contentType);
                        return null;
                    }
                    String mimeType = "text/html";
                    String encoding = "UTF-8";
                    if (contentType != null) {
                        String[] parts = contentType.split(";");
                        if (parts.length > 0) mimeType = parts[0].trim();
                        for (String part : parts) {
                            part = part.trim();
                            if (part.startsWith("charset=")) {
                                encoding = part.substring(8).trim();
                            }
                        }
                    }
                    InputStream inputStream;
                    try {
                        inputStream = conn.getInputStream();
                    } catch (Exception e) {
                        inputStream = conn.getErrorStream();
                    }
                    if (inputStream == null) return null;
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = inputStream.read(buf)) != -1) {
                        buffer.write(buf, 0, len);
                    }
                    inputStream.close();
                    String html = buffer.toString(encoding);
                    String authScript = buildAuthScript();
                    String injectedHtml;
                    int headIdx = html.indexOf("<head");
                    if (headIdx >= 0) {
                        int headEnd = html.indexOf('>', headIdx);
                        if (headEnd >= 0) {
                            injectedHtml = html.substring(0, headEnd + 1) + authScript + html.substring(headEnd + 1);
                        } else {
                            injectedHtml = authScript + html;
                        }
                    } else {
                        int htmlIdx = html.indexOf("<html");
                        if (htmlIdx >= 0) {
                            int htmlEnd = html.indexOf('>', htmlIdx);
                            if (htmlEnd >= 0) {
                                injectedHtml = html.substring(0, htmlEnd + 1) + "<head>" + authScript + "</head>" + html.substring(htmlEnd + 1);
                            } else {
                                injectedHtml = "<head>" + authScript + "</head>" + html;
                            }
                        } else {
                            injectedHtml = "<head>" + authScript + "</head>" + html;
                        }
                    }
                    byte[] injectedBytes = injectedHtml.getBytes(encoding);
                    InputStream injectedStream = new ByteArrayInputStream(injectedBytes);
                    WebResourceResponse response = new WebResourceResponse(mimeType, encoding, injectedStream);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        response.setStatusCodeAndReasonPhrase(statusCode, conn.getResponseMessage());
                        Map<String, String> headers = new HashMap<>();
                        for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
                            String key = entry.getKey();
                            if (key != null && !key.equalsIgnoreCase("Content-Encoding")
                                    && !key.equalsIgnoreCase("Content-Length")
                                    && !key.equalsIgnoreCase("Transfer-Encoding")) {
                                headers.put(key, entry.getValue().get(0));
                            }
                        }
                        response.setResponseHeaders(headers);
                    }
                    Log.i(TAG, "Auth state injected into HTML response for " + url);
                    return response;
                } catch (Exception e) {
                    Log.w(TAG, "Error injecting auth into HTML: " + e.getMessage());
                    return null;
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }

            private String buildAuthScript() {
                String accessToken = authManager.getTokenStore().getAccessToken();
                String refreshToken = authManager.getTokenStore().getRefreshToken();
                String email = authManager.getTokenStore().getUserEmail();
                String name = authManager.getTokenStore().getUserName();
                String userId = authManager.getTokenStore().getUserId();
                String image = authManager.getTokenStore().getUserImage();
                String role = authManager.getTokenStore().getUserRole();
                String safeAccessToken = safeJs(accessToken);
                String safeRefreshToken = safeJs(refreshToken);
                String safeEmail = safeJs(email);
                String safeName = safeJs(name);
                String safeUserId = safeJs(userId);
                String safeImage = safeJs(image);
                String safeRole = safeJs(role);
                String userJson = "{id:'" + safeUserId + "',email:'" + safeEmail + "',name:'" + safeName + "',image:'" + safeImage + "',role:'" + safeRole + "'}";
                return "<script>"
                        + "(function(){"
                        // localStorage (may throw DOMException before page ready — isolate it)
                        + "try{"
                        + "localStorage.setItem('fundocareer_access_token','" + safeAccessToken + "');"
                        + (refreshToken != null && !refreshToken.isEmpty()
                            ? "localStorage.setItem('fundocareer_refresh_token','" + safeRefreshToken + "');"
                            : "")
                        + "localStorage.setItem('fundocareer_user','" + safeJs(userJson) + "');"
                        + "localStorage.setItem('fundocareer_auth_state','true');"
                        + "localStorage.setItem('FUNDOCareer_ACCESS_TOKEN','" + safeAccessToken + "');"
                        + "}catch(e){}"
                        // Global auth object (no localStorage dependency)
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
                        // Events
                        + "document.dispatchEvent(new CustomEvent('fundocareer-auth-ready',{detail:window.__FUNDOCAREER_AUTH__}));"
                        + "window.dispatchEvent(new CustomEvent('fundocareer:auth-updated',{detail:{source:'android-native',isAuthenticated:true,user:" + userJson + "}}));"
                        + "})();"
                        + "</script>";
            }

            private String safeJs(String s) {
                if (s == null) return "";
                return s.replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r");
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                try {
                    if (originalClient != null && view != null) {
                        originalClient.onPageStarted(view, url, favicon);
                    }
                } catch (Exception e) {
                    Log.w("FundoCareerApp", "originalClient.onPageStarted error", e);
                }
                if (micManager != null) {
                    micManager.onPageChanged(view, url);
                }
                hideOfflineOverlay();
                loadingUrl = url;
                timeoutHandler.removeCallbacks(timeoutRunnable);
                timeoutHandler.postDelayed(timeoutRunnable, PAGE_LOAD_TIMEOUT_MS);
                if (isInitialLoad) {
                    showLoading();
                } else {
                    if (topProgressBar != null) {
                        topProgressBar.setVisibility(View.VISIBLE);
                        topProgressBar.bringToFront();
                    }
                    if (loadingOverlay != null) {
                        loadingOverlay.setVisibility(View.GONE);
                    }
                }
                if (view != null) {
                    try {
                        view.evaluateJavascript(
                            "window.electron=true;window.__fundocareerApp=true;" +
                            "localStorage&&localStorage.setItem('FUNDOCareer_APP_MODE','android');" +
                            // Phase 8: Guard Capacitor triggerEvent in case plugins.json not ready
                            "if(!window.Capacitor){window.Capacitor={};}" +
                            "if(!window.Capacitor.triggerEvent){window.Capacitor.triggerEvent=function(){};}",
                            null
                        );
                    } catch (Exception e) {
                        Log.w("FundoCareerApp", "App mode injection error", e);
                    }
                }
                if (authManager != null && authManager.isLoggedIn() && view != null) {
                    try {
                        authManager.injectAuthState(view);
                    } catch (Exception e) {
                        Log.w("FundoCareerApp", "Auth injection error on page start", e);
                    }
                }
                Log.i(TAG, "Loading: " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                try {
                    if (originalClient != null && view != null) {
                        originalClient.onPageFinished(view, url);
                    }
                } catch (Exception e) {
                    Log.w("FundoCareerApp", "originalClient.onPageFinished error", e);
                }
                loadingUrl = null;
                timeoutHandler.removeCallbacks(timeoutRunnable);
                isInitialLoad = false;
                hideLoading();
                if (view != null) {
                    injectSafeAreaCss(view);
                    injectMobileAppStyles(view);
                    injectMicTracking(view);
                    injectNavigationBridge(view);
                }
                syncTabState(url);
                if (url != null && (url.contains("/pricing") || url.contains("/plans")
                        || url.contains("/subscription") || url.contains("/billing"))) {
                    PaymentHandler.logPricingView();
                }
                if (url != null) {
                    Log.i(TAG, "Loaded: " + url);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                try {
                    if (originalClient != null) {
                        originalClient.onReceivedError(view, request, error);
                    }
                } catch (Exception e) {
                    Log.w("FundoCareerApp", "originalClient.onReceivedError error", e);
                }
                String desc = error != null ? error.getDescription().toString() : "unknown";
                int code = error != null ? error.getErrorCode() : -1;
                loadingUrl = null;
                timeoutHandler.removeCallbacks(timeoutRunnable);
                String url = request != null && request.getUrl() != null ? request.getUrl().toString() : "unknown";
                logNetworkFailure("webview_error", url, "ErrorCode=" + code + " desc=" + desc);
                Log.e("FundoCareerApp", "WebView error [" + code + "] on " + url + " – " + desc);
                if (request != null && request.isForMainFrame()) {
                    String msg;
                    if (code == WebViewClient.ERROR_HOST_LOOKUP || code == WebViewClient.ERROR_CONNECT
                            || code == WebViewClient.ERROR_TIMEOUT) {
                        if (BuildConfig.DEBUG) {
                            msg = "Cannot reach www.fundocareer.com.\nCheck internet or tap to retry.";
                        } else {
                            msg = "Cannot reach server. Tap to retry.";
                        }
                    } else if (code == WebViewClient.ERROR_UNKNOWN) {
                        msg = "Something went wrong. Tap to retry.";
                    } else {
                        msg = "Error (" + code + "). Tap to retry.";
                    }
                    showError(msg);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                try {
                    if (originalClient != null) {
                        originalClient.onReceivedHttpError(view, request, response);
                    }
                } catch (Exception e) {
                    Log.w("FundoCareerApp", "originalClient.onReceivedHttpError error", e);
                }
                int code = response != null ? response.getStatusCode() : -1;
                String url = request != null && request.getUrl() != null ? request.getUrl().toString() : "unknown";
                Log.w(TAG, "HTTP " + code + " for " + url);
                Log.w("FundoCareerApp", "HTTP error " + code + " on " + url);
                if (request != null && request.isForMainFrame()) {
                    loadingUrl = null;
                    timeoutHandler.removeCallbacks(timeoutRunnable);
                    if (code >= 500) {
                        logNetworkFailure("http_5xx", url, "HTTP " + code);
                        showError("Server unavailable. Tap to retry.");
                    } else if (code == 404) {
                        showError("Page not found. Tap to retry.");
                    } else if (code == 403) {
                        showError("Access denied. Tap to retry.");
                    }
                    if (code == 401 && authManager != null) {
                        authManager.handleHttpError(view, code, url);
                    }
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                String msg = consoleMessage.message();
                if (msg.contains("[FundoCareer") || msg.contains("/api/")
                        || msg.contains("API") || msg.contains("auth")
                        || consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.i(TAG, "JS: " + msg);
                }
                return super.onConsoleMessage(consoleMessage);
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
            }

            @Override
            public boolean onShowFileChooser(WebView webView,
                                              ValueCallback<Uri[]> filePathCallback,
                                              FileChooserParams fileChooserParams) {
                try {
                    if (filePickerCallback != null) {
                        filePickerCallback.onReceiveValue(null);
                    }
                    filePickerCallback = filePathCallback;

                    String[] rawTypes = fileChooserParams != null
                            ? fileChooserParams.getAcceptTypes() : null;
                    String[] acceptTypes = normalizeAcceptTypes(rawTypes);
                    if (acceptTypes == null || acceptTypes.length == 0) {
                        acceptTypes = new String[]{"*/*"};
                    }

                    boolean isMultiple = fileChooserParams != null
                            && fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
                    if (isMultiple) {
                        filePickerMultipleLauncher.launch(acceptTypes);
                    } else {
                        filePickerLauncher.launch(acceptTypes);
                    }
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "File picker error", e);
                    if (filePathCallback != null) {
                        filePathCallback.onReceiveValue(null);
                    }
                    return false;
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                           boolean isUserGesture, android.os.Message resultMsg) {
                try {
                    WebView newWv = new WebView(view.getContext());
                    newWv.setWebViewClient(new WebViewClient() {
                        @Override
                        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                            String u = req.getUrl().toString();
                            PaymentHandler.UrlAction action = PaymentHandler.classifyUrl(u, ALLOWED_HOST);
                            Log.i(NAV_LOG_TAG, "Popup URL: " + u + " -> " + action);
                            switch (action) {
                                case INTERNAL:
                                    return false;
                                case PAYMENT:
                                    paymentFlowActive = true;
                                    PaymentHandler.openCheckout(MainActivity.this, u);
                                    return true;
                                case SPECIAL_SCHEME:
                                    PaymentHandler.openSpecialScheme(MainActivity.this, req.getUrl());
                                    return true;
                                case EXTERNAL:
                                case GOOGLE_AUTH:
                                default:
                                    PaymentHandler.openExternal(MainActivity.this, u);
                                    return true;
                            }
                        }
                    });
                    WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                    transport.setWebView(newWv);
                    resultMsg.sendToTarget();
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "onCreateWindow error", e);
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                try {
                    String[] resources = request.getResources();
                    boolean hasAudio = false;
                    boolean hasOther = false;
                    for (String resource : resources) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                            hasAudio = true;
                        } else {
                            hasOther = true;
                        }
                    }
                    // Only handle pure audio capture requests. Deny everything else.
                    if (hasAudio && !hasOther) {
                        handleWebViewMicrophone(request);
                    } else {
                        request.deny();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Permission request error", e);
                    try { request.deny(); } catch (Exception ignored) {}
                }
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin, GeolocationPermissions.Callback callback) {
                try {
                    callback.invoke(origin, false, false);
                } catch (Exception e) {
                    Log.w(TAG, "Geolocation prompt error", e);
                }
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                String fileName = null;
                if (contentDisposition != null) {
                    String disp = contentDisposition.toLowerCase(Locale.ROOT);
                    int fStarIdx = disp.indexOf("filename*=utf-8''");
                    if (fStarIdx >= 0) {
                        String raw = contentDisposition.substring(fStarIdx + 19);
                        if (raw.startsWith("\"")) raw = raw.substring(1);
                        int end = raw.indexOf("\"");
                        fileName = end > 0 ? raw.substring(0, end) : raw;
                        try { fileName = java.net.URLDecoder.decode(fileName, "UTF-8"); } catch (Exception ignored) {}
                    } else {
                        int fIdx = disp.indexOf("filename=");
                        if (fIdx >= 0) {
                            String raw = contentDisposition.substring(fIdx + 9);
                            if (raw.startsWith("\"")) raw = raw.substring(1);
                            int end = raw.indexOf("\"");
                            fileName = end > 0 ? raw.substring(0, end) : raw;
                        }
                    }
                }
                if (fileName == null || fileName.isEmpty()) {
                    fileName = Uri.parse(url).getLastPathSegment();
                }
                if (fileName == null || fileName.isEmpty()) {
                    fileName = "download_" + System.currentTimeMillis();
                }
                String ext = MimeTypeMap.getFileExtensionFromUrl(url);
                if (ext == null && mimeType != null) {
                    ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                }
                if (!fileName.contains(".") && ext != null && !ext.isEmpty()) {
                    fileName = fileName + "." + ext;
                }
                PermissionCoordinator.downloadFile(
                        MainActivity.this, url, fileName, "Downloading\u2026");
                Toast.makeText(MainActivity.this,
                        "Downloading: " + fileName, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Download listener error", e);
            }
        });
    }

    private void showOfflineOverlay() {
        try {
            if (offlineOverlay == null) {
                float d = getResources().getDisplayMetrics().density;
                offlineOverlay = new FrameLayout(this);
                offlineOverlay.setBackgroundColor(0xFFFAFAFA);
                offlineOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));

                FrameLayout center = new FrameLayout(this);
                FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                clp.gravity = Gravity.CENTER;
                center.setLayoutParams(clp);

                TextView icon = new TextView(this);
                icon.setText("\u26A0\uFE0F");
                icon.setTextSize(56);
                icon.setTextColor(0xFF999999);
                FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                ilp.gravity = Gravity.CENTER_HORIZONTAL;
                icon.setLayoutParams(ilp);
                center.addView(icon);

                TextView title = new TextView(this);
                title.setText("No internet connection");
                title.setTextSize(20);
                title.setTextColor(0xFF333333);
                title.setTypeface(null, Typeface.BOLD);
                title.setGravity(Gravity.CENTER);
                FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                tlp.gravity = Gravity.CENTER_HORIZONTAL;
                tlp.topMargin = (int) (80 * d);
                title.setLayoutParams(tlp);
                center.addView(title);

                TextView subtitle = new TextView(this);
                subtitle.setText("Please check your connection\nand tap retry to continue.");
                subtitle.setTextSize(14);
                subtitle.setTextColor(0xFF666666);
                subtitle.setGravity(Gravity.CENTER);
                subtitle.setLineSpacing(4, 1);
                FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                slp.gravity = Gravity.CENTER_HORIZONTAL;
                slp.topMargin = (int) (120 * d);
                subtitle.setLayoutParams(slp);
                center.addView(subtitle);

                Button retryBtn = new Button(this);
                retryBtn.setText("Retry");
                retryBtn.setTextSize(16);
                retryBtn.setAllCaps(false);
                retryBtn.setTypeface(null, Typeface.BOLD);
                retryBtn.setTextColor(0xFFFFFFFF);
                retryBtn.setBackgroundColor(0xFF1A73E8);
                retryBtn.setPadding((int) (32 * d), (int) (12 * d), (int) (32 * d), (int) (12 * d));
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                blp.gravity = Gravity.CENTER_HORIZONTAL;
                blp.topMargin = (int) (180 * d);
                retryBtn.setLayoutParams(blp);
                retryBtn.setOnClickListener(v -> {
                    WebView wv = getSafeWebView();
                    if (wv == null) return;
                    String url = wv.getUrl();
                    if (url == null || "about:blank".equals(url)) {
                        url = getUrlForTabId(activeNavId);
                    }
                    WebSettings settings = wv.getSettings();
                    settings.setCacheMode(WebSettings.LOAD_DEFAULT);
                    wv.loadUrl(url);
                    hideOfflineOverlay();
                    showLoading();
                });
                center.addView(retryBtn);

                offlineOverlay.addView(center);
                contentFrame.addView(offlineOverlay);
            }
            hideLoading();
            timeoutHandler.removeCallbacks(timeoutRunnable);
            offlineOverlay.setVisibility(View.VISIBLE);
            offlineOverlay.bringToFront();
            Log.i(NET_LOG_TAG, "Offline overlay shown");
        } catch (Exception e) {
            Log.e(TAG, "showOfflineOverlay error", e);
        }
    }

    private void hideOfflineOverlay() {
        try {
            if (offlineOverlay != null) {
                offlineOverlay.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            Log.w(TAG, "hideOfflineOverlay error", e);
        }
    }

    private void logNetworkFailure(String type, String url, String detail) {
        try {
            JSONObject log = new JSONObject();
            log.put("type", type);
            log.put("url", url != null ? url : "");
            log.put("detail", detail != null ? detail : "");
            log.put("ts", System.currentTimeMillis());
            log.put("online", networkMonitor != null && networkMonitor.isOnline());
            Log.w(NET_LOG_TAG, log.toString());
        } catch (Exception e) {
            Log.w(TAG, "Log failure error", e);
        }
    }

    private String[] normalizeAcceptTypes(String[] raw) {
        try {
            if (raw == null || raw.length == 0) return new String[]{"*/*"};
            ArrayList<String> result = new ArrayList<>();
            for (String type : raw) {
                if (type == null || type.isEmpty()) continue;
                String[] parts = type.split(",");
                for (String part : parts) {
                    String t = part.trim().toLowerCase(Locale.ROOT);
                    if (t.isEmpty()) continue;
                    if (t.equals(".pdf")) { result.add("application/pdf"); continue; }
                    if (t.equals(".doc")) { result.add("application/msword"); continue; }
                    if (t.equals(".docx")) { result.add("application/vnd.openxmlformats-officedocument.wordprocessingml.document"); continue; }
                    if (t.equals(".png")) { result.add("image/png"); continue; }
                    if (t.equals(".jpg") || t.equals(".jpeg")) { result.add("image/jpeg"); continue; }
                    result.add(t);
                }
            }
            return result.isEmpty() ? new String[]{"*/*"} : result.toArray(new String[0]);
        } catch (Exception e) {
            return new String[]{"*/*"};
        }
    }

    private void handleWebViewMicrophone(PermissionRequest request) {
        try {
            WebView wv = getSafeWebView();
            if (wv == null) {
                request.deny();
                return;
            }
            String currentUrl = wv.getUrl();
            if (currentUrl == null || (!currentUrl.contains("/mock-interview")
                    && !currentUrl.contains("/mock"))) {
                Log.w(TAG, "Mic requested outside mock interview, denying");
                request.deny();
                Toast.makeText(this,
                        "Microphone is only available during mock interviews",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            if (!PermissionCoordinator.hasMicrophoneHardware(this)) {
                Log.w(TAG, "No mic hardware available");
                request.deny();
                Toast.makeText(this,
                        "No microphone available on this device",
                        Toast.LENGTH_LONG).show();
                return;
            }

            if (PermissionCoordinator.hasMicrophonePermission(this)) {
                request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                if (micManager != null) micManager.onMicStarted();
                return;
            }
            PermissionCoordinator.showExplanationDialog(this,
                    "Prepare for Your Mock Interview",
                    "We'll use your microphone so the AI interviewer can hear your responses.\n\n" +
                            "Your audio is processed in real time and is not recorded or stored without your consent.",
                    () -> requestMicrophonePermission(new PermissionCoordinator.PermissionCallback() {
                        @Override
                        public void onGranted() {
                            try {
                                request.grant(
                                        new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                                if (micManager != null) micManager.onMicStarted();
                            } catch (Exception e) {
                                Log.w(TAG, "Grant after mic permission failed", e);
                            }
                        }

                        @Override
                        public void onDenied(boolean neverAskAgain) {
                            try { request.deny(); } catch (Exception ignored) {}
                            if (neverAskAgain) {
                                PermissionCoordinator.showPermissionDeniedDialog(
                                        MainActivity.this,
                                        "Microphone",
                                        () -> PermissionCoordinator.openAppSettings(
                                                MainActivity.this),
                                        null);
                            } else {
                                Toast.makeText(MainActivity.this,
                                        "Microphone access is needed for mock interviews",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    }),
                    () -> {
                        try { request.deny(); } catch (Exception ignored) {}
                    });
        } catch (Exception e) {
            Log.e(TAG, "Mic permission flow error", e);
            try { request.deny(); } catch (Exception ignored) {}
        }
    }

    private void requestMicrophonePermission(PermissionCoordinator.PermissionCallback callback) {
        try {
            if (PermissionCoordinator.hasMicrophonePermission(this)) {
                if (callback != null) callback.onGranted();
                return;
            }
            if (PermissionCoordinator.isNeverAskAgain(this,
                    android.Manifest.permission.RECORD_AUDIO)) {
                PermissionCoordinator.showPermissionDeniedDialog(
                        this, "Microphone",
                        () -> PermissionCoordinator.openAppSettings(this),
                        () -> {
                            if (callback != null)
                                callback.onDenied(true);
                        });
                return;
            }
            pendingMicCallback = callback;
            microphoneLauncher.launch(android.Manifest.permission.RECORD_AUDIO);
        } catch (Exception e) {
            Log.e(TAG, "Request mic error", e);
            if (callback != null) callback.onDenied(false);
        }
    }

    private boolean onNavItemClicked(int itemId) {
        if (itemId == R.id.nav_more) {
            showMoreSheet();
            return true;
        }
        setActiveNavItem(itemId);
        String targetUrl = getUrlForTabId(itemId);
        WebView webView = getSafeWebView();
        if (webView == null) return false;
        String currentUrl = webView.getUrl();

        if (currentUrl != null && urlsMatch(currentUrl, targetUrl)) {
            return true;
        }

        if (networkMonitor != null && !networkMonitor.isOnline()) {
            showOfflineOverlay();
            return true;
        }

        isTabNavigation = true;
        saveActiveTab(itemId);
        String targetPath = Uri.parse(targetUrl).getPath();
        if (targetPath == null || targetPath.isEmpty()) targetPath = "/";
        String js = "window.__fundocareerNavigate && window.__fundocareerNavigate(" + org.json.JSONObject.quote(targetPath) + ")";
        webView.evaluateJavascript(js, result -> {
            if ("false".equals(result)) {
                Log.w(TAG, "JS bridge not available, falling back to loadUrl for: " + targetUrl);
                webView.loadUrl(targetUrl);
            }
        });
        return true;
    }

    private void saveActiveTab(int tabId) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_ACTIVE_TAB, tabId)
                .apply();
    }

    private void syncTabState(String url) {
        if (isTabNavigation) {
            isTabNavigation = false;
            return;
        }
        for (int tabId : TAB_IDS) {
            if (urlsMatch(url, getUrlForTabId(tabId))) {
                if (activeNavId != tabId) {
                    setActiveNavItem(tabId);
                    saveActiveTab(tabId);
                }
                return;
            }
        }
    }

    private boolean urlsMatch(String url1, String url2) {
        if (url1 == null || url2 == null) return false;
        return normalizeUrl(url1).equals(normalizeUrl(url2));
    }

    private String normalizeUrl(String url) {
        url = url.replaceAll("#.*$", "");
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    // ──────────────────────────────────────────────
    // FLOATING CAPSULE NAVIGATION
    // ──────────────────────────────────────────────

    private void createCapsuleNav(FrameLayout root) {
        float d = getResources().getDisplayMetrics().density;

        MaterialCardView capsule = new MaterialCardView(this);
        capsule.setRadius(32 * d);
        capsule.setCardElevation(12 * d);
        capsule.setMaxCardElevation(12 * d);
        capsule.setStrokeWidth(0);
        capsule.setCardBackgroundColor(ContextCompat.getColor(this, R.color.nav_surface));
        capsule.setContentPadding((int)(6 * d), (int)(4 * d), (int)(6 * d), (int)(4 * d));

        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setLayoutParams(new MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int)(60 * d)
        ));

        for (NavItemData item : NAV_ITEMS) {
            View itemView = createNavItemView(item, d);
            navRow.addView(itemView);
            navItemViews.put(item.id, itemView);
        }

        View moreView = createMoreNavItem(d);
        navRow.addView(moreView);
        navItemViews.put(R.id.nav_more, moreView);

        capsule.addView(navRow);

        FrameLayout.LayoutParams capsuleLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        capsuleLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        capsuleLp.setMargins((int)(16 * d), 0, (int)(16 * d), (int)(16 * d));
        root.addView(capsule, capsuleLp);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            if (bottom > 0) {
                capsuleLp.bottomMargin = (int)(bottom + 12 * d);
                capsule.requestLayout();
            }
            return WindowInsetsCompat.CONSUMED;
        });

        setActiveNavItem(R.id.nav_home);
    }

    private View createNavItemView(NavItemData item, float d) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        container.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
        ));
        container.setClickable(true);
        container.setFocusable(true);
        container.setPadding(0, (int)(4 * d), 0, (int)(4 * d));

        FrameLayout iconWrap = new FrameLayout(this);
        iconWrap.setLayoutParams(new LinearLayout.LayoutParams(
                (int)(40 * d), (int)(32 * d)
        ));

        ImageView icon = new ImageView(this);
        icon.setImageResource(item.iconRes);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(
                (int)(24 * d), (int)(24 * d)
        );
        iconLp.gravity = Gravity.CENTER;
        icon.setLayoutParams(iconLp);
        iconWrap.addView(icon);
        container.addView(iconWrap);

        TextView label = new TextView(this);
        label.setText(item.label);
        label.setTextSize(10);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        llp.topMargin = (int)(2 * d);
        label.setLayoutParams(llp);
        container.addView(label);

        container.setTag(item.id);
        container.setOnClickListener(v -> onNavItemClicked(item.id));

        return container;
    }

    private View createMoreNavItem(float d) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        container.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
        ));
        container.setClickable(true);
        container.setFocusable(true);
        container.setPadding(0, (int)(4 * d), 0, (int)(4 * d));

        FrameLayout iconWrap = new FrameLayout(this);
        iconWrap.setLayoutParams(new LinearLayout.LayoutParams(
                (int)(40 * d), (int)(32 * d)
        ));

        TextView dots = new TextView(this);
        dots.setText("\u22EF");
        dots.setTextSize(20);
        dots.setTextColor(ContextCompat.getColor(this, R.color.nav_inactive_icon));
        dots.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams dotsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        dots.setLayoutParams(dotsLp);
        iconWrap.addView(dots);
        container.addView(iconWrap);

        TextView label = new TextView(this);
        label.setText("More");
        label.setTextSize(10);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(ContextCompat.getColor(this, R.color.nav_inactive_icon));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        llp.topMargin = (int)(2 * d);
        label.setLayoutParams(llp);
        container.addView(label);

        container.setOnClickListener(v -> showMoreSheet());
        return container;
    }

    private void setActiveNavItem(int itemId) {
        activeNavId = itemId;
        int primaryColor = ContextCompat.getColor(this, R.color.primary);
        int inactiveColor = ContextCompat.getColor(this, R.color.nav_inactive_icon);
        int pillColor = ContextCompat.getColor(this, R.color.nav_selected_pill);

        for (int id : navItemViews.keySet()) {
            View view = navItemViews.get(id);
            if (view == null) continue;
            boolean isActive = id == itemId;

            LinearLayout container = (LinearLayout) view;
            ImageView icon = null;
            TextView label = null;
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                if (child instanceof FrameLayout && icon == null) {
                    FrameLayout fw = (FrameLayout) child;
                    if (fw.getChildCount() > 0 && fw.getChildAt(0) instanceof ImageView) {
                        icon = (ImageView) fw.getChildAt(0);
                    }
                }
                if (child instanceof TextView) {
                    label = (TextView) child;
                }
            }

            if (icon != null) {
                icon.setColorFilter(isActive ? primaryColor : inactiveColor);
                ViewGroup parent = (ViewGroup) icon.getParent();
                GradientDrawable pill = new GradientDrawable();
                pill.setShape(GradientDrawable.RECTANGLE);
                pill.setCornerRadius(16 * getResources().getDisplayMetrics().density);
                if (isActive) {
                    pill.setColor(pillColor);
                    parent.setBackground(pill);
                } else {
                    parent.setBackground(null);
                }
            }
            if (label != null) {
                label.setTextColor(isActive ? primaryColor : inactiveColor);
                label.setTypeface(null, isActive ? Typeface.BOLD : Typeface.NORMAL);
            }
            container.setScaleX(isActive ? 1.0f : 0.92f);
            container.setScaleY(isActive ? 1.0f : 0.92f);
        }
    }

    private void showMoreSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int)(24 * d);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(pad, (int)(24 * d), pad, (int)(24 * d));

        TextView title = new TextView(this);
        title.setText("More");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        sheet.addView(title);

        View divider = new View(this);
        divider.setBackgroundColor(ContextCompat.getColor(this, R.color.divider));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
        );
        dlp.topMargin = (int)(12 * d);
        dlp.bottomMargin = (int)(8 * d);
        divider.setLayoutParams(dlp);
        sheet.addView(divider);

        MoreItem[] moreItems = {
            new MoreItem("Smart Apply", R.drawable.ic_apply, AppConfig.getNavUrl("/job-application")),
            new MoreItem("Mock Interview", R.drawable.ic_mock, AppConfig.getNavUrl("/mock-interview")),
            new MoreItem("Pricing / Plans", -1, AppConfig.getNavUrl("/pricing")),
        };

        for (MoreItem mi : moreItems) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setClickable(true);
            row.setFocusable(true);
            row.setPadding(0, (int)(14 * d), 0, (int)(14 * d));
            row.setBackground(null);

            if (mi.iconRes > 0) {
                ImageView iv = new ImageView(this);
                iv.setImageResource(mi.iconRes);
                iv.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary));
                LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
                        (int)(24 * d), (int)(24 * d)
                );
                ivLp.rightMargin = (int)(16 * d);
                iv.setLayoutParams(ivLp);
                row.addView(iv);
            } else {
                TextView ti = new TextView(this);
                ti.setText("$");
                ti.setTextSize(20);
                ti.setTypeface(null, Typeface.BOLD);
                ti.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                ti.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        (int)(24 * d), (int)(24 * d)
                );
                tlp.rightMargin = (int)(16 * d);
                ti.setLayoutParams(tlp);
                row.addView(ti);
            }

            TextView lv = new TextView(this);
            lv.setText(mi.label);
            lv.setTextSize(16);
            lv.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            row.addView(lv);

            final String fUrl = mi.url;
            row.setOnClickListener(v -> {
                dialog.dismiss();
                WebView wv = getSafeWebView();
                if (wv != null) {
                    String fPath = Uri.parse(fUrl).getPath();
                    if (fPath == null || fPath.isEmpty()) fPath = "/";
                    String js = "window.__fundocareerNavigate && window.__fundocareerNavigate(" + org.json.JSONObject.quote(fPath) + ")";
                    wv.evaluateJavascript(js, result -> {
                        if ("false".equals(result)) {
                            Log.w(TAG, "JS bridge not available for More item, falling back to loadUrl: " + fUrl);
                            wv.loadUrl(fUrl);
                        }
                    });
                }
            });

            sheet.addView(row);
        }

        dialog.setContentView(sheet);
        View bsView = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bsView != null) {
            bsView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        }
        dialog.show();
    }

    private static class MoreItem {
        final String label;
        final int iconRes;
        final String url;
        MoreItem(String label, int iconRes, String url) {
            this.label = label; this.iconRes = iconRes; this.url = url;
        }
    }

    private void createLoadingOverlay() {
        loadingOverlay = new FrameLayout(this);
        loadingOverlay.setBackgroundColor(0xFFFFFFFF);
        loadingOverlay.setVisibility(View.GONE);

        float d = getResources().getDisplayMetrics().density;

        LinearLayout centerLayout = new LinearLayout(this);
        centerLayout.setOrientation(LinearLayout.VERTICAL);
        centerLayout.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        centerLp.gravity = Gravity.CENTER;
        centerLayout.setLayoutParams(centerLp);

        TextView appName = new TextView(this);
        appName.setText("FundoCareer");
        appName.setTextColor(0xFF1A73E8);
        appName.setTextSize(28);
        appName.setTypeface(null, Typeface.BOLD);
        appName.setGravity(Gravity.CENTER);
        centerLayout.addView(appName);

        TextView subtitle = new TextView(this);
        subtitle.setText("Loading\u2026");
        subtitle.setTextColor(0xFF666666);
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, (int)(8 * d), 0, (int)(24 * d));
        centerLayout.addView(subtitle);

        ProgressBar spinner = new ProgressBar(
                this, null, android.R.attr.progressBarStyleLarge
        );
        spinner.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            spinner.setIndeterminateTintList(
                    android.content.res.ColorStateList.valueOf(0xFF1A73E8)
            );
        }
        centerLayout.addView(spinner);

        loadingOverlay.addView(centerLayout);
        contentFrame.addView(loadingOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    private void showLoading() {
        if (isInitialLoad) {
            if (loadingOverlay != null) {
                loadingOverlay.bringToFront();
                loadingOverlay.setVisibility(View.VISIBLE);
            }
            if (topProgressBar != null) {
                topProgressBar.setVisibility(View.GONE);
            }
        } else {
            if (topProgressBar != null) {
                topProgressBar.setVisibility(View.VISIBLE);
                topProgressBar.bringToFront();
            }
            if (loadingOverlay != null) {
                loadingOverlay.setVisibility(View.GONE);
            }
        }
    }

    private void hideLoading() {
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(View.GONE);
        }
        if (topProgressBar != null) {
            topProgressBar.setVisibility(View.GONE);
        }
    }

    private void showError(String message) {
        if (loadingOverlay == null) return;
        loadingOverlay.post(() -> {
            loadingOverlay.removeAllViews();
            float d = getResources().getDisplayMetrics().density;

            FrameLayout errorRoot = new FrameLayout(MainActivity.this);
            errorRoot.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            FrameLayout center = new FrameLayout(MainActivity.this);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            clp.gravity = Gravity.CENTER;
            center.setLayoutParams(clp);

            TextView icon = new TextView(MainActivity.this);
            icon.setText("\u26A0\uFE0F");
            icon.setTextSize(48);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            ilp.gravity = Gravity.CENTER_HORIZONTAL;
            icon.setLayoutParams(ilp);
            center.addView(icon);

            TextView errorText = new TextView(MainActivity.this);
            errorText.setText(message);
            errorText.setTextSize(16);
            errorText.setTextColor(0xFF333333);
            errorText.setGravity(Gravity.CENTER);
            errorText.setPadding((int)(32 * d), 0, (int)(32 * d), 0);
            FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            elp.gravity = Gravity.CENTER_HORIZONTAL;
            elp.topMargin = (int)(72 * d);
            errorText.setLayoutParams(elp);
            center.addView(errorText);

            Button retryBtn = new Button(MainActivity.this);
            retryBtn.setText("Retry");
            retryBtn.setTextSize(16);
            retryBtn.setAllCaps(false);
            retryBtn.setTypeface(null, Typeface.BOLD);
            retryBtn.setTextColor(0xFFFFFFFF);
            retryBtn.setBackgroundColor(0xFF1A73E8);
            retryBtn.setPadding((int)(32 * d), (int)(12 * d), (int)(32 * d), (int)(12 * d));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            blp.gravity = Gravity.CENTER_HORIZONTAL;
            blp.topMargin = (int)(120 * d);
            retryBtn.setLayoutParams(blp);
            retryBtn.setOnClickListener(v -> {
                loadingOverlay.removeAllViews();
                loadingOverlay.setVisibility(View.GONE);
                WebView wv = getSafeWebView();
                if (wv != null) wv.reload();
            });
            center.addView(retryBtn);

            errorRoot.addView(center);
            loadingOverlay.addView(errorRoot);
            loadingOverlay.setVisibility(View.VISIBLE);
        });
    }

    private void injectSafeAreaCss(WebView view) {
        String js = "(function(){" +
                "if(document.getElementById('__fundocareer_safe'))return;" +
                "var s=document.createElement('style');" +
                "s.id='__fundocareer_safe';" +
                "s.innerHTML=':root{--sat:env(safe-area-inset-top);" +
                "--sab:env(safe-area-inset-bottom);" +
                "--sal:env(safe-area-inset-left);" +
                "--sar:env(safe-area-inset-right)}';" +
                "if(document.head){document.head.appendChild(s);}" +
                "})()";
        view.evaluateJavascript(js, null);
    }

    private void injectMicTracking(WebView view) {
        String js = "(function(){" +
                "if(window.__fundocareer_mic)return;" +
                "window.__fundocareer_mic=true;" +
                "var origGUM=navigator.mediaDevices&&navigator.mediaDevices.getUserMedia;" +
                "if(!origGUM)return;" +
                "navigator.mediaDevices.getUserMedia=function(c){" +
                "return origGUM.call(navigator.mediaDevices,c).then(function(s){" +
                "window.__micStream=s;" +
                "try{window.MicrophoneBridge&&window.MicrophoneBridge.onMicStarted&&window.MicrophoneBridge.onMicStarted()}catch(e){}" +
                "s.getTracks().forEach(function(t){" +
                "t.addEventListener('ended',function(){" +
                "window.__micStream=null;" +
                "try{window.MicrophoneBridge&&window.MicrophoneBridge.onMicStopped&&window.MicrophoneBridge.onMicStopped()}catch(e){}" +
                "})" +
                "});" +
                "return s" +
                "})" +
                "}" +
                "})()";
        view.evaluateJavascript(js, null);
    }

    private void injectNavigationBridge(WebView view) {
        String js = "(function(){" +
                "if(window.__fundocareerNavigate)return;" +
                "window.__fundocareerNavigate=function(path){" +
                "if(window.__FUNDOCAREER_ROUTER__&&typeof window.__FUNDOCAREER_ROUTER__.navigate==='function'){" +
                "window.__FUNDOCAREER_ROUTER__.navigate(path);return true}" +
                "return false}})()";
        view.evaluateJavascript(js, null);
    }

    private void injectMobileAppStyles(WebView view) {
        String js = "(function(){try{" +
                "if(document.getElementById('__fc_fixes'))return;" +
                "document.documentElement.dataset.fundocareerApp='android';" +
                "try{localStorage.setItem('FUNDOCareer_APP_MODE','android')}catch(e){}" +
                "window.__fundocareerApp=true;" +
                "window.electron=true;" +
                "var a=document.createElement('style');" +
                "a.id='__fc_fixes';" +
                "a.textContent=[" +
                // General app-mode foundation
                "'html[data-fundocareer-app=android]{overscroll-behavior-y:contain!important;-webkit-text-size-adjust:100%!important;scroll-behavior:smooth!important}'" +
                ",'html[data-fundocareer-app=android] body{overflow-x:hidden!important;max-width:100vw!important;padding-bottom:80px!important;font-size:16px;line-height:1.5;-webkit-font-smoothing:antialiased!important;margin:0!important}'" +
                ",'::selection{background:var(--color-primary,#1a73e8);color:#fff}'" +
                // Hide website chrome (header, nav, footer) + padding resets
                ",'html[data-fundocareer-app=android] header,html[data-fundocareer-app=android] nav,html[data-fundocareer-app=android] .navbar,html[data-fundocareer-app=android] .site-header,html[data-fundocareer-app=android] .main-header,html[data-fundocareer-app=android] .top-nav,html[data-fundocareer-app=android] .nav-header,html[data-fundocareer-app=android] .app-header,html[data-fundocareer-app=android] .header-inner,html[data-fundocareer-app=android] [class*=navbar],html[data-fundocareer-app=android] [class*=topbar],html[data-fundocareer-app=android] [class*=header_],html[data-fundocareer-app=android] [class*=Header],html[data-fundocareer-app=android] [class*=nav_],html[data-fundocareer-app=android] [class*=Nav]{display:none!important}'" +
                ",'html[data-fundocareer-app=android] .pt-20,html[data-fundocareer-app=android] .lg\\\\:pt-24,html[data-fundocareer-app=android] .pt-24,html[data-fundocareer-app=android] .md\\\\:pt-20{display:none!important}'" +
                // Footer
                ",'html[data-fundocareer-app=android] footer,html[data-fundocareer-app=android] .footer,html[data-fundocareer-app=android] .site-footer,html[data-fundocareer-app=android] .main-footer,html[data-fundocareer-app=android] [class*=footer],html[data-fundocareer-app=android] [class*=Footer]{display:none!important}'" +
                // Touch targets (48px minimum)
                ",'html[data-fundocareer-app=android] button,html[data-fundocareer-app=android] a,html[data-fundocareer-app=android] input,html[data-fundocareer-app=android] select,html[data-fundocareer-app=android] textarea,html[data-fundocareer-app=android] [role=button],html[data-fundocareer-app=android] [tabindex]:not([tabindex=\\'\\-1\\']){min-height:48px!important;font-size:16px!important}'" +
                ",'html[data-fundocareer-app=android] button,html[data-fundocareer-app=android] a,html[data-fundocareer-app=android] [role=button]{min-width:48px!important}'" +
                // Responsive typography
                ",'html[data-fundocareer-app=android] h1{font-size:1.5rem!important;line-height:1.3!important;letter-spacing:-0.02em!important}'" +
                ",'html[data-fundocareer-app=android] h2{font-size:1.25rem!important;line-height:1.35!important}'" +
                ",'html[data-fundocareer-app=android] h3{font-size:1.125rem!important;line-height:1.4!important}'" +
                ",'html[data-fundocareer-app=android] h4{font-size:1rem!important;line-height:1.4!important}'" +
                ",'html[data-fundocareer-app=android] p{font-size:0.938rem!important;line-height:1.5!important}'" +
                ",'html[data-fundocareer-app=android] small,.text-sm{font-size:0.813rem!important}'" +
                // Modern card styling
                ",'html[data-fundocareer-app=android] [class*=card],html[data-fundocareer-app=android] [class*=Card]{border-radius:12px!important;box-shadow:0 2px 12px rgba(0,0,0,0.08)!important;margin-bottom:12px!important;overflow:hidden!important}'" +
                // Grid -> single column on mobile
                ",'html[data-fundocareer-app=android] [class*=grid]{grid-template-columns:1fr!important}'" +
                // Full-width buttons
                ",'html[data-fundocareer-app=android] .btn,html[data-fundocareer-app=android] button[type=submit],html[data-fundocareer-app=android] [class*=btn]{width:100%!important;border-radius:8px!important;padding:14px 20px!important;text-align:center!important;justify-content:center!important}'" +
                // Forms
                ",'html[data-fundocareer-app=android] form{width:100%!important}'" +
                ",'html[data-fundocareer-app=android] [class*=form-group],html[data-fundocareer-app=android] [class*=formGroup]{width:100%!important;margin-bottom:1rem!important}'" +
                ",'html[data-fundocareer-app=android] input:not([type=radio]):not([type=checkbox]),html[data-fundocareer-app=android] select,html[data-fundocareer-app=android] textarea{width:100%!important;box-sizing:border-box!important;min-height:48px!important;padding:12px 14px!important;border-radius:8px!important;font-size:16px!important;margin-bottom:0!important}'" +
                ",'html[data-fundocareer-app=android] label{font-size:0.875rem!important;margin-bottom:4px!important;display:block!important;font-weight:500!important}'" +
                // Modals & dialogs
                ",'html[data-fundocareer-app=android] [class*=modal],html[data-fundocareer-app=android] [class*=dialog],html[data-fundocareer-app=android] [class*=Modal],html[data-fundocareer-app=android] [class*=Dialog]{width:95vw!important;max-width:95vw!important;max-height:90vh!important;border-radius:16px!important}'" +
                ",'html[data-fundocareer-app=android] [class*=modal-content],html[data-fundocareer-app=android] [class*=modalContent]{padding:1.25rem!important}'" +
                // Tables -> scrollable
                ",'html[data-fundocareer-app=android] table,html[data-fundocareer-app=android] [class*=table],html[data-fundocareer-app=android] [class*=dataTable]{display:block;overflow-x:auto;-webkit-overflow-scrolling:touch;width:100%!important}'" +
                // Images
                ",'html[data-fundocareer-app=android] img{max-width:100%!important;height:auto!important}'" +
                // Route-specific: ATS Checker
                ",'html[data-fundocareer-app=android] [class*=ats],html[data-fundocareer-app=android] [class*=checker]{padding:1rem!important}'" +
                ",'html[data-fundocareer-app=android] [class*=dropzone],html[data-fundocareer-app=android] [class*=upload-area]{min-height:120px!important;padding:2rem 1rem!important;border-radius:12px!important}'" +
                ",'html[data-fundocareer-app=android] [class*=upload-icon]{width:48px!important;height:48px!important}'" +
                ",'html[data-fundocareer-app=android] [class*=score],html[data-fundocareer-app=android] [class*=circle]{max-width:80px!important;max-height:80px!important}'" +
                // Route-specific: Mock Interview
                ",'html[data-fundocareer-app=android] [class*=mock],html[data-fundocareer-app=android] [class*=interview]{padding:1rem!important}'" +
                ",'html[data-fundocareer-app=android] .ai-sphere{min-height:35vh!important}'" +
                ",'html[data-fundocareer-app=android] .user-sphere{height:25vh!important;min-height:140px!important}'" +
                ",'html[data-fundocareer-app=android] [class*=mic-button],html[data-fundocareer-app=android] [class*=micBtn]{width:64px!important;height:64px!important;border-radius:50%!important;min-width:64px!important;min-height:64px!important}'" +
                // Route-specific: Pricing
                ",'html[data-fundocareer-app=android] [class*=pricing]{padding:1rem!important}'" +
                ",'html[data-fundocareer-app=android] [class*=pricing-grid],html[data-fundocareer-app=android] [class*=plans-grid]{grid-template-columns:1fr!important;gap:1rem!important}'" +
                ",'html[data-fundocareer-app=android] [class*=plan-card]{margin-bottom:1rem!important;border-radius:12px!important}'" +
                ",'html[data-fundocareer-app=android] [class*=plan-card] [class*=price]{font-size:1.75rem!important}'" +
                // Route-specific: Resume Builder
                ",'html[data-fundocareer-app=android] [class*=builder],html[data-fundocareer-app=android] [class*=resume-editor],html[data-fundocareer-app=android] [class*=editor]{padding:0!important}'" +
                ",'html[data-fundocareer-app=android] [class*=resume-list],html[data-fundocareer-app=android] [class*=templates]{padding:1rem!important}'" +
                // Route-specific: Profile
                ",'html[data-fundocareer-app=android] [class*=profile]{padding:1rem!important}'" +
                ",'html[data-fundocareer-app=android] [class*=avatar]{width:64px!important;height:64px!important}'" +
                // Route-specific: Job Application
                ",'html[data-fundocareer-app=android] [class*=application],html[data-fundocareer-app=android] [class*=apply]{padding:1rem!important}'" +
                ",'html[data-fundocareer-app=android] [class*=file-upload],html[data-fundocareer-app=android] [class*=fileUpload],html[data-fundocareer-app=android] [class*=upload]{padding:2rem 1rem!important;min-height:100px!important;border-radius:12px!important}'" +
                // Route-specific: Jobs list
                ",'html[data-fundocareer-app=android] [class*=job-list],html[data-fundocareer-app=android] [class*=jobs]{padding:1rem!important}'" +
                ",'html[data-fundocareer-app=android] [class*=job-card],html[data-fundocareer-app=android] [class*=JobCard]{padding:1rem!important;margin-bottom:12px!important;border-radius:12px!important}'" +
                // Container spacing
                ",'html[data-fundocareer-app=android] .container,html[data-fundocareer-app=android] [class*=container]{padding-left:1rem!important;padding-right:1rem!important}'" +
                // Marketing / hero compact
                ",'html[data-fundocareer-app=android] [class*=hero]{min-height:auto!important;padding:2rem 1rem!important}'" +
                ",'html[data-fundocareer-app=android] [class*=marketing]{padding-block:1.5rem!important}'" +
                // Bottom gutter for native nav on all content containers
                ",'html[data-fundocareer-app=android] main,html[data-fundocareer-app=android] [role=main],html[data-fundocareer-app=android] #app,html[data-fundocareer-app=android] .app{padding-bottom:80px!important}'" +
                // Fix bottom action bars to not overlap nav
                ",'html[data-fundocareer-app=android] [class*=action-bar],html[data-fundocareer-app=android] [class*=ActionBar],html[data-fundocareer-app=android] [class*=sticky-bottom],html[data-fundocareer-app=android] [class*=bottom-bar]{bottom:84px!important}'" +
                // Ensure no horizontal scroll
                ",'html[data-fundocareer-app=android] .row,html[data-fundocareer-app=android] [class*=row]{overflow-x:hidden!important}'" +
                "].join('');" +
                // Phase 8: Guard appendChild
                "if(document.head){document.head.appendChild(a);}" +
                // Viewport meta
                "var vp=document.querySelector('meta[name=viewport]');" +
                "if(!vp){vp=document.createElement('meta');vp.name='viewport';vp.content='width=device-width,initial-scale=1.0,maximum-scale=1.0,viewport-fit=cover';if(document.head){document.head.appendChild(vp);}}" +
                // Auto-scroll into view on focus (keyboard avoidance)
                "document.addEventListener('focusin',function(e){" +
                "var t=e.target.tagName;" +
                "if(t==='INPUT'||t==='TEXTAREA'||t==='SELECT'){" +
                "setTimeout(function(){try{e.target.scrollIntoView({behavior:'smooth',block:'center',inline:'nearest'})}catch(ex){} },400)" +
                "}" +
                "});" +
                // Ensure main content fills viewport
                "var main=document.querySelector('main,#app,.app,[role=main]');" +
                "if(main){var h=window.innerHeight;main.style.minHeight=h+'px';window.addEventListener('resize',function(){if(window.innerHeight>h){h=window.innerHeight;main.style.minHeight=h+'px'}})}" +
                "}catch(e){}})()";
        view.evaluateJavascript(js, null);
    }

    private void setupBackButton() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView webView = getSafeWebView();
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                    backPressedOnce = false;
                } else {
                    if (backPressedOnce) {
                        finishAffinity();
                    } else {
                        backPressedOnce = true;
                        Toast.makeText(
                                MainActivity.this,
                                "Press back again to exit",
                                Toast.LENGTH_SHORT
                        ).show();
                        timeoutHandler.removeCallbacksAndMessages(null);
                        timeoutHandler.postDelayed(
                                () -> backPressedOnce = false,
                                BACK_PRESS_TIMEOUT
                        );
                    }
                }
            }
        });
    }

    private void restoreActiveTab() {
        WebView webView = getSafeWebView();
        if (webView == null) {
            Log.e("FundoCareerApp", "WebView null in restoreActiveTab");
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int savedTabId = prefs.getInt(KEY_ACTIVE_TAB, R.id.nav_home);
        String url = getUrlForTabId(savedTabId);

        boolean validTab = false;
        for (NavItemData item : NAV_ITEMS) {
            if (item.id == savedTabId) { validTab = true; break; }
        }
        if (!validTab) savedTabId = R.id.nav_home;

        if (networkMonitor != null && !networkMonitor.isOnline()) {
            setActiveNavItem(R.id.nav_home);
            showOfflineOverlay();
            return;
        }

        // Phase 9: On startup, if we have a stored session, try token refresh
        if (authManager != null && authManager.getTokenStore().hasRefreshToken()
                && (authManager.getTokenStore().isTokenExpired() || !authManager.isLoggedIn())) {
            Log.i(TAG, "Session present but expired, attempting refresh on startup");
            attemptStartupRefresh(webView, savedTabId, url);
            return;
        }

        if (authManager != null && authManager.isLoggedIn()) {
            // Phase 5: Re-inject auth into WebView on startup
            authManager.injectAuthState(webView);
            setActiveNavItem(savedTabId);
            webView.loadUrl(url);
        } else {
            setActiveNavItem(R.id.nav_home);
            webView.loadUrl(AppConfig.getDefaultUrl());
        }
    }

    private void attemptStartupRefresh(WebView webView, int savedTabId, String url) {
        authManager.refreshAccessToken(new AuthManager.AuthCallback() {
            @Override
            public void onSuccess(JSONObject authData) {
                Log.i(TAG, "Startup token refresh succeeded, restoring session");
                runOnUiThread(() -> {
                    WebView wv = getSafeWebView();
                    if (wv != null) {
                        authManager.setAuthCookies();
                        authManager.injectAuthState(wv);
                    }
                    setActiveNavItem(savedTabId);
                    WebView wv2 = getSafeWebView();
                    if (wv2 != null) wv2.loadUrl(url);
                });
            }

            @Override
            public void onError(String error) {
                Log.i(TAG, "Startup token refresh failed, starting fresh: " + error);
                runOnUiThread(() -> {
                    setActiveNavItem(R.id.nav_home);
                    WebView wv = getSafeWebView();
                    if (wv != null) wv.loadUrl(AppConfig.getDefaultUrl());
                });
            }
        });
    }
}
