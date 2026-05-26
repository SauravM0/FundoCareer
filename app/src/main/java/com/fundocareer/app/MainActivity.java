package com.fundocareer.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.fundocareer.app.core.logging.FcLog;
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
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.fundocareer.app.core.jobalerts.JobAlertLifecycle;
import com.fundocareer.app.core.jobs.JobsPageActivity;
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
    private boolean interviewNavHidden = false;
    private boolean jobsActivityVisible = false;

    private int activeNavId = R.id.nav_home;
    private final java.util.Map<Integer, View> navItemViews = new java.util.HashMap<>();
    private MaterialCardView navCapsule;
    private FrameLayout loginOverlay;

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
    private NavigationPolicy navPolicy;
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
            FcLog.INSTANCE.w(FcLog.TAG_WEBVIEW, "Page load timeout", new java.util.HashMap<String, Object>() {{
                put("url", timedOut);
                put("timeoutMs", PAGE_LOAD_TIMEOUT_MS);
            }});
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

    private PermissionCoordinator.PermissionCallback pendingCameraCallback;

    private final ActivityResultLauncher<String> cameraLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        PermissionCoordinator.PermissionCallback cb = pendingCameraCallback;
                        pendingCameraCallback = null;
                        if (cb != null) {
                            if (granted) {
                                cb.onGranted();
                            } else {
                                boolean neverAsk = PermissionCoordinator.isNeverAskAgain(
                                        MainActivity.this,
                                        android.Manifest.permission.CAMERA);
                                cb.onDenied(neverAsk);
                            }
                        }
                    }
            );

    private PermissionRequest pendingPermissionRequest;

    private final ActivityResultLauncher<String[]> micAndCameraLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        PermissionRequest req = pendingPermissionRequest;
                        pendingPermissionRequest = null;
                        if (req == null) return;
                        boolean audioGranted = Boolean.TRUE.equals(
                                result.get(android.Manifest.permission.RECORD_AUDIO));
                        boolean cameraGranted = Boolean.TRUE.equals(
                                result.get(android.Manifest.permission.CAMERA));
                        List<String> grantedResources = new ArrayList<>();
                        for (String resource : req.getResources()) {
                            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) && audioGranted) {
                                grantedResources.add(resource);
                            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) && cameraGranted) {
                                grantedResources.add(resource);
                            }
                        }
                        if (grantedResources.isEmpty()) {
                            Log.i(TAG, "WebView permission denied by user: audio=" + audioGranted + " camera=" + cameraGranted);
                            req.deny();
                            if (micManager != null) micManager.onMicStopped();
                        } else {
                            Log.i(TAG, "WebView permission granted: " + grantedResources);
                            req.grant(grantedResources.toArray(new String[0]));
                            if (audioGranted && micManager != null) micManager.onMicStarted();
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
        BackendDiagnostics.run();

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
                                Log.i(NET_LOG_TAG, "backend connectivity state: online");
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
        jobsActivityVisible = false;
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
        navPolicy = new NavigationPolicy(tokenStore);
        updateNavVisibility(navPolicy.isUserLoggedIn());
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

                    // Save previous user email before overwriting tokens
                    final String previousEmail = authManager.getTokenStore().getUserEmail();

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

                        // Resume any paused job alert preferences (pass previous email for same-account detection)
                        JobAlertLifecycle.onLogin(MainActivity.this, email, previousEmail);

                        // Phase 6: Show bottom navigation after successful login
                        showNav();
                        hideLoginOverlay();

                        // Phase 6: Navigate to home after successful login
                        wv.postDelayed(() -> {
                            WebView wv2 = getSafeWebView();
                            if (wv2 != null) {
                                String postLoginUrl = (navPolicy != null)
                                        ? navPolicy.getPostLoginRoute()
                                        : AppConfig.getDefaultUrl();
                                Log.i(TAG, "Navigate to home after successful login");
                                wv2.loadUrl(postLoginUrl);
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
        Log.i(TAG, "Logout requested");
        isGoogleLoginInProgress = false;
        loginStartTime = 0;
        timeoutHandler.removeCallbacks(loginWatchdog);

        String userEmail = tokenStore != null ? tokenStore.getUserEmail() : null;
        if (userEmail == null || userEmail.isEmpty()) {
            Log.i(TAG, "No user to logout, navigating home");
            WebView wv = getSafeWebView();
            if (wv != null) {
                wv.loadUrl(AppConfig.getDefaultUrl());
            }
            return;
        }

        JobAlertLifecycle.showLogoutWarning(MainActivity.this, userEmail, () -> {
            runOnUiThread(() -> performLogoutCleanup(userEmail));
            return null;
        });
    }

    private void performLogoutCleanup(String userEmail) {
        Log.i(TAG, "Proceeding with logout cleanup for " + userEmail);
        hideLoginOverlay();

        try {
            googleSignInClient.signOut();
            Log.i(TAG, "Google sign-out completed");
        } catch (Exception e) {
            Log.w(TAG, "Google sign-out failed", e);
        }

        WebView wv = getSafeWebView();
        if (wv == null) return;

        authManager.logout(wv, () -> {
            runOnUiThread(() -> {
                hideNav();
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
        createLoginOverlay();
        configureWebView(webView);
    }

    private void configureWebView(WebView webView) {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(false);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setTextZoom(100);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        ws.setSupportMultipleWindows(true);
        ws.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING);

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
                        || url.contains("/api/mobile/auth/google")
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
                if (originalClient != null) {
                    try {
                        return originalClient.shouldInterceptRequest(view, request);
                    } catch (Exception e) {
                        Log.w(TAG, "Bridge error: " + e.getMessage());
                    }
                }
                return null;
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
                hideLoginOverlay();
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
                // Hide bottom nav on interview pages for full-screen experience
                if (url != null && isInterviewRoute(url)) {
                    if (navCapsule != null && !interviewNavHidden && navCapsule.getVisibility() == View.VISIBLE) {
                        hideNav();
                        interviewNavHidden = true;
                        Log.i(TAG, "[Interview] Bottom nav hidden for full-screen interview page");
                    }
                } else if (interviewNavHidden) {
                    interviewNavHidden = false;
                    if (navPolicy != null && navPolicy.isUserLoggedIn()) {
                        showNav();
                        Log.i(TAG, "[Interview] Bottom nav restored after leaving interview page");
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
                        Log.i(TAG, "[WebView] API routing=PRODUCTION — page: " + (url != null && url.contains("/api/") ? url : "(" + (url != null ? "browsing" : "null") + ")"));
                        authManager.injectAuthState(view);
                        Log.i(TAG, "[WebView] auth injected: page start");
                    } catch (Exception e) {
                        Log.w("FundoCareerApp", "Auth injection error on page start", e);
                    }
                }
                if (url != null) {
                    FcLog.INSTANCE.i(FcLog.TAG_WEBVIEW, "Page load started", new java.util.HashMap<String, Object>() {{
                        put("url", url);
                    }});
                }
                if (BuildConfig.DEBUG) Log.i(TAG, "Loading: " + url);
                Log.i(NAV_LOG_TAG, "WebView URL load started: " + url);
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
                    injectPageSetup(view);
                    injectMobileAppStyles(view);
                    injectCssVariables(view);
                    if (authManager != null && authManager.isLoggedIn()) {
                        try {
                            authManager.injectAuthState(view);
                            Log.i(TAG, "[WebView] auth injected: page finished");
                            Log.i(TAG, "[WebView] Auth re-injected on page finished");
                        } catch (Exception e) {
                            Log.w("FundoCareerApp", "Auth injection error on page finished", e);
                        }
                    }
                }
                syncTabState(url);
                if (url != null) {
                    FcLog.INSTANCE.i(FcLog.TAG_WEBVIEW, "Page load finished", new java.util.HashMap<String, Object>() {{
                        put("url", url);
                    }});
                }
                if (url != null && navPolicy != null && navPolicy.shouldShowLoginGate(url)) {
                    showLoginOverlay();
                }
                if (url != null && (url.contains("/pricing") || url.contains("/plans")
                        || url.contains("/subscription") || url.contains("/billing"))) {
                    PaymentHandler.logPricingView();
                }
                if (url != null && BuildConfig.DEBUG) {
                    Log.i(TAG, "Loaded: " + url);
                }
                Log.i(NAV_LOG_TAG, "WebView URL load finished: " + url);
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
                FcLog.INSTANCE.e(FcLog.TAG_WEBVIEW, "WebView load error", null, new java.util.HashMap<String, Object>() {{
                    put("errorCode", code);
                    put("description", desc);
                    put("url", url);
                }});
                Log.e("FundoCareerApp", "WebView error [" + code + "] on " + url + " – " + desc);
                if (request != null && request.isForMainFrame()) {
                    String msg;
                    if (code == WebViewClient.ERROR_HOST_LOOKUP || code == WebViewClient.ERROR_CONNECT
                            || code == WebViewClient.ERROR_TIMEOUT) {
                        if (BuildConfig.DEBUG) {
                            String host = AppConfig.getAllowedHost();
                            msg = "Cannot reach " + host + ".\nCheck internet or tap to retry.";
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
                FcLog.INSTANCE.w(FcLog.TAG_WEBVIEW, "HTTP error", new java.util.HashMap<String, Object>() {{
                    put("httpCode", code);
                    put("url", url);
                }});
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
                if (topProgressBar != null && !isInitialLoad) {
                    topProgressBar.setIndeterminate(false);
                    topProgressBar.setMax(100);
                    topProgressBar.setProgress(newProgress);
                    topProgressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                    if (newProgress < 100) topProgressBar.bringToFront();
                }
                if (newProgress == 100 && loadingUrl != null) {
                    FcLog.INSTANCE.d(FcLog.TAG_WEBVIEW, "Page load progress complete", new java.util.HashMap<String, Object>() {{
                        put("url", loadingUrl);
                    }});
                }
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
                    String origin = request.getOrigin() != null ? request.getOrigin().toString() : "unknown";
                    String[] resources = request.getResources();
                    StringBuilder sb = new StringBuilder();
                    for (String r : resources) sb.append(r).append(",");
                    Log.i(TAG, "[WebView] Permission request from " + origin + ": resources=[" + sb + "]");

                    boolean hasAudio = false;
                    boolean hasVideo = false;
                    for (String resource : resources) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                            hasAudio = true;
                        } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                            hasVideo = true;
                        } else {
                            Log.i(TAG, "[WebView] Unrecognized resource in request: " + resource + " (will not be granted)");
                        }
                    }

                    if (hasAudio && hasVideo) {
                        Log.i(TAG, "[WebView] Combined audio+video permission request, dispatching to combined handler");
                        handleWebViewMicAndCamera(request);
                    } else if (hasAudio) {
                        Log.i(TAG, "[WebView] Audio-only permission request, dispatching to mic handler");
                        handleWebViewMicrophone(request);
                    } else if (hasVideo) {
                        Log.i(TAG, "[WebView] Video-only permission request, dispatching to camera handler");
                        handleWebViewCamera(request);
                    } else {
                        Log.w(TAG, "[WebView] No recognized resources in permission request, denying: [" + sb + "]");
                        request.deny();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[WebView] Permission request handler error", e);
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
                offlineOverlay.setClipToPadding(false);
                offlineOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));

                ScrollView scrollView = new ScrollView(this);
                scrollView.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
                scrollView.setClipToPadding(false);
                scrollView.setFillViewport(true);

                LinearLayout scrollContent = new LinearLayout(this);
                scrollContent.setOrientation(LinearLayout.VERTICAL);
                scrollContent.setGravity(Gravity.CENTER);
                scrollContent.setLayoutParams(new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                ));

                LinearLayout center = new LinearLayout(this);
                center.setOrientation(LinearLayout.VERTICAL);
                center.setGravity(Gravity.CENTER_HORIZONTAL);
                center.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                ));

                TextView icon = new TextView(this);
                icon.setText("\u26A0\uFE0F");
                icon.setTextSize(56);
                icon.setTextColor(0xFF999999);
                icon.setGravity(Gravity.CENTER_HORIZONTAL);
                center.addView(icon);

                TextView title = new TextView(this);
                title.setText("No internet connection");
                title.setTextSize(20);
                title.setTextColor(0xFF333333);
                title.setTypeface(null, Typeface.BOLD);
                title.setGravity(Gravity.CENTER);
                title.setPadding((int)(32 * d), (int)(16 * d), (int)(32 * d), 0);
                center.addView(title);

                TextView subtitle = new TextView(this);
                subtitle.setText("Please check your connection\nand tap retry to continue.");
                subtitle.setTextSize(14);
                subtitle.setTextColor(0xFF666666);
                subtitle.setGravity(Gravity.CENTER);
                subtitle.setLineSpacing(4, 1);
                subtitle.setPadding((int)(32 * d), (int)(8 * d), (int)(32 * d), (int)(24 * d));
                subtitle.setMaxWidth((int)(320 * d));
                center.addView(subtitle);

                Button retryBtn = new Button(this);
                retryBtn.setText("Retry");
                retryBtn.setTextSize(16);
                retryBtn.setAllCaps(false);
                retryBtn.setTypeface(null, Typeface.BOLD);
                retryBtn.setTextColor(0xFFFFFFFF);
                retryBtn.setBackgroundColor(0xFF1A73E8);
                retryBtn.setPadding((int) (32 * d), (int) (12 * d), (int) (32 * d), (int) (12 * d));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                blp.gravity = Gravity.CENTER_HORIZONTAL;
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

                scrollContent.addView(center);
                scrollView.addView(scrollContent);
                offlineOverlay.addView(scrollView);
                contentFrame.addView(offlineOverlay);
            }
            hideLoading();
            timeoutHandler.removeCallbacks(timeoutRunnable);
            offlineOverlay.setVisibility(View.VISIBLE);
            offlineOverlay.bringToFront();
            Log.i(NET_LOG_TAG, "Offline overlay shown");
            Log.i(NET_LOG_TAG, "backend connectivity state: offline");
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
                Log.w(TAG, "WebView null, denying mic request");
                request.deny();
                return;
            }

            if (!PermissionCoordinator.hasMicrophoneHardware(this)) {
                Log.w(TAG, "No mic hardware available, denying mic request");
                request.deny();
                Toast.makeText(this,
                        "No microphone available on this device",
                        Toast.LENGTH_LONG).show();
                return;
            }

            if (PermissionCoordinator.hasMicrophonePermission(this)) {
                Log.i(TAG, "Mic permission already granted, granting WebView RESOURCE_AUDIO_CAPTURE");
                request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                if (micManager != null) micManager.onMicStarted();
                return;
            }

            Log.i(TAG, "Mic permission not yet granted, requesting runtime RECORD_AUDIO");
            PermissionCoordinator.showExplanationDialog(this,
                    "Microphone Access",
                    "This feature needs access to your microphone.",
                    () -> requestMicrophonePermission(new PermissionCoordinator.PermissionCallback() {
                        @Override
                        public void onGranted() {
                            try {
                                Log.i(TAG, "Runtime mic permission granted, granting WebView RESOURCE_AUDIO_CAPTURE");
                                request.grant(
                                        new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                                if (micManager != null) micManager.onMicStarted();
                            } catch (Exception e) {
                                Log.w(TAG, "Grant after mic permission failed", e);
                            }
                        }

                        @Override
                        public void onDenied(boolean neverAskAgain) {
                            Log.w(TAG, "Runtime mic permission denied, denying WebView request");
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
                                        "Microphone access is needed for this feature",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    }),
                    () -> {
                        Log.i(TAG, "User cancelled mic explanation dialog, denying WebView request");
                        try { request.deny(); } catch (Exception ignored) {}
                    });
        } catch (Exception e) {
            Log.e(TAG, "Mic permission flow error", e);
            try { request.deny(); } catch (Exception ignored) {}
        }
    }

    private void handleWebViewCamera(PermissionRequest request) {
        try {
            if (!PermissionCoordinator.hasCameraHardware(this)) {
                Log.w(TAG, "[WebView] No camera hardware available, denying camera request");
                request.deny();
                Toast.makeText(this,
                        "No camera available on this device",
                        Toast.LENGTH_LONG).show();
                return;
            }

            if (PermissionCoordinator.hasCameraPermission(this)) {
                Log.i(TAG, "[WebView] Camera permission already granted, granting RESOURCE_VIDEO_CAPTURE");
                request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                return;
            }
            Log.i(TAG, "[WebView] Camera permission not yet granted, requesting runtime CAMERA");
            PermissionCoordinator.showExplanationDialog(this,
                    "Camera Access",
                    "This feature needs access to your camera.",
                    () -> requestCameraPermission(new PermissionCoordinator.PermissionCallback() {
                        @Override
                        public void onGranted() {
                            try {
                                Log.i(TAG, "[WebView] Runtime camera permission granted, granting RESOURCE_VIDEO_CAPTURE");
                                request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                            } catch (Exception e) {
                                Log.w(TAG, "[WebView] Grant after camera permission failed", e);
                            }
                        }

                        @Override
                        public void onDenied(boolean neverAskAgain) {
                            Log.w(TAG, "[WebView] Runtime camera permission denied, denying WebView request");
                            try { request.deny(); } catch (Exception ignored) {}
                            if (neverAskAgain) {
                                PermissionCoordinator.showPermissionDeniedDialog(
                                        MainActivity.this, "Camera",
                                        () -> PermissionCoordinator.openAppSettings(MainActivity.this),
                                        null);
                            } else {
                                Toast.makeText(MainActivity.this,
                                        "Camera access is needed for this feature",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    }),
                    () -> {
                        Log.i(TAG, "[WebView] User cancelled camera explanation dialog, denying request");
                        try { request.deny(); } catch (Exception ignored) {}
                    });
        } catch (Exception e) {
            Log.e(TAG, "[WebView] Camera permission flow error", e);
            try { request.deny(); } catch (Exception ignored) {}
        }
    }

    private void handleWebViewMicAndCamera(PermissionRequest request) {
        try {
            boolean hasMicHardware = PermissionCoordinator.hasMicrophoneHardware(this);
            boolean hasCamHardware = PermissionCoordinator.hasCameraHardware(this);

            if (!hasMicHardware && !hasCamHardware) {
                Log.w(TAG, "[WebView] No mic or camera hardware, denying combined request");
                request.deny();
                Toast.makeText(this,
                        "No microphone or camera available on this device",
                        Toast.LENGTH_LONG).show();
                return;
            }

            if (!hasMicHardware) {
                Log.i(TAG, "[WebView] No mic hardware, granting camera only from combined request");
                PermissionCoordinator.showExplanationDialog(this,
                        "Camera Access",
                        "This feature needs access to your camera.",
                        () -> handleWebViewCamera(request),
                        () -> {
                            Log.i(TAG, "[WebView] User cancelled camera-only dialog from combined request");
                            try { request.deny(); } catch (Exception ignored) {}
                        });
                return;
            }

            if (!hasCamHardware) {
                Log.i(TAG, "[WebView] No camera hardware, granting mic only from combined request");
                PermissionCoordinator.showExplanationDialog(this,
                        "Microphone Access",
                        "This feature needs access to your microphone.",
                        () -> handleWebViewMicrophone(request),
                        () -> {
                            Log.i(TAG, "[WebView] User cancelled mic-only dialog from combined request");
                            try { request.deny(); } catch (Exception ignored) {}
                        });
                return;
            }

            boolean hasMicPerm = PermissionCoordinator.hasMicrophonePermission(this);
            boolean hasCamPerm = PermissionCoordinator.hasCameraPermission(this);

            if (hasMicPerm && hasCamPerm) {
                Log.i(TAG, "[WebView] Both mic and camera already granted, granting combined request");
                request.grant(new String[]{
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE,
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE
                });
                if (micManager != null) micManager.onMicStarted();
                return;
            }

            Log.i(TAG, "[WebView] Combined mic+cam request, showing explanation then requesting: mic=" + hasMicPerm + " cam=" + hasCamPerm);
            PermissionCoordinator.showExplanationDialog(this,
                    "Microphone & Camera Access",
                    "This feature needs access to your microphone and camera.",
                    () -> {
                        pendingPermissionRequest = request;
                        micAndCameraLauncher.launch(new String[]{
                                android.Manifest.permission.RECORD_AUDIO,
                                android.Manifest.permission.CAMERA
                        });
                    },
                    () -> {
                        Log.i(TAG, "[WebView] User cancelled combined permission dialog, denying request");
                        try { request.deny(); } catch (Exception ignored) {}
                    });
        } catch (Exception e) {
            Log.e(TAG, "[WebView] Combined permission flow error", e);
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

    private void requestCameraPermission(PermissionCoordinator.PermissionCallback callback) {
        try {
            if (PermissionCoordinator.hasCameraPermission(this)) {
                if (callback != null) callback.onGranted();
                return;
            }
            if (PermissionCoordinator.isNeverAskAgain(this,
                    android.Manifest.permission.CAMERA)) {
                PermissionCoordinator.showPermissionDeniedDialog(
                        this, "Camera",
                        () -> PermissionCoordinator.openAppSettings(this),
                        () -> {
                            if (callback != null)
                                callback.onDenied(true);
                        });
                return;
            }
            pendingCameraCallback = callback;
            cameraLauncher.launch(android.Manifest.permission.CAMERA);
        } catch (Exception e) {
            Log.e(TAG, "Request camera error", e);
            if (callback != null) callback.onDenied(false);
        }
    }

    private boolean onNavItemClicked(int itemId) {
        if (itemId == R.id.nav_more) {
            showMoreSheet();
            return true;
        }
        if (itemId == R.id.nav_jobs) {
            launchJobsTab("bottom-nav");
            return true;
        }
        setActiveNavItem(itemId);
        jobsActivityVisible = false;
        String targetUrl = getUrlForTabId(itemId);
        WebView webView = getSafeWebView();
        if (webView == null) return false;
        String currentUrl = webView.getUrl();

        if (currentUrl != null && urlsMatch(currentUrl, targetUrl)) {
            Log.i(NAV_LOG_TAG, "tab change ignored: already on id=" + itemId + " url=" + targetUrl);
            return true;
        }

        if (networkMonitor != null && !networkMonitor.isOnline()) {
            showOfflineOverlay();
            return true;
        }

        isTabNavigation = true;
        saveActiveTab(itemId);
        Log.i(NAV_LOG_TAG, "tab change: id=" + itemId + " url=" + targetUrl);
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
        Log.i(NAV_LOG_TAG, "saved active tab: " + tabId);
    }

    private void launchJobsTab(String reason) {
        setActiveNavItem(R.id.nav_jobs);
        saveActiveTab(R.id.nav_jobs);
        Log.i(NAV_LOG_TAG, "tab change: id=" + R.id.nav_jobs + " native=true reason=" + reason);
        if (jobsActivityVisible) {
            Log.i(NAV_LOG_TAG, "JobsPageActivity already visible; not relaunching");
            return;
        }
        jobsActivityVisible = true;
        startActivity(new Intent(this, JobsPageActivity.class));
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
        this.navCapsule = capsule;

        FrameLayout.LayoutParams capsuleLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        capsuleLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        capsuleLp.setMargins((int)(16 * d), 0, (int)(16 * d), (int)(16 * d));
        root.addView(capsule, capsuleLp);
        capsule.setVisibility(View.GONE);

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

    private boolean isInterviewRoute(String url) {
        if (url == null) return false;
        try {
            String path = android.net.Uri.parse(url).getPath();
            return path != null && (path.contains("/mock-interview")
                    || path.contains("/interview")
                    || path.contains("/guided-journey"));
        } catch (Exception e) {
            return false;
        }
    }

    private void showNav() {
        if (navCapsule != null) {
            navCapsule.setVisibility(View.VISIBLE);
            updateWebViewBottomPadding(true);
            Log.i(TAG, "Bottom nav shown");
        }
    }

    private void hideNav() {
        if (navCapsule != null) {
            navCapsule.setVisibility(View.GONE);
            updateWebViewBottomPadding(false);
            Log.i(TAG, "Bottom nav hidden");
        }
    }

    private void updateNavVisibility(boolean visible) {
        if (visible) {
            showNav();
        } else {
            hideNav();
        }
    }

    private int getNavHeightPx() {
        if (navCapsule == null || navCapsule.getVisibility() != View.VISIBLE) return 0;
        float d = getResources().getDisplayMetrics().density;
        int navRowHeight = (int)(60 * d);
        int capsulePadTop = (int)(4 * d);
        int capsulePadBottom = (int)(4 * d);
        return navRowHeight + capsulePadTop + capsulePadBottom;
    }

    private void injectCssVariables(WebView wv) {
        int navPx = getNavHeightPx();
        String js = "(function(){try{" +
            "var r=document.documentElement;" +
            "r.style.setProperty('--native-bottom-nav-height','" + navPx + "px');" +
            "r.style.setProperty('--safe-area-top','env(safe-area-inset-top,0px)');" +
            "r.style.setProperty('--safe-area-bottom','env(safe-area-inset-bottom,0px)');" +
            "}catch(e){}})()";
        wv.evaluateJavascript(js, null);
    }

    private void updateWebViewBottomPadding(boolean navVisible) {
        WebView wv = getSafeWebView();
        if (wv == null) return;
        int navPx = navVisible ? getNavHeightPx() : 0;
        String js = "(function(){try{" +
            "var r=document.documentElement;" +
            "r.style.setProperty('--native-bottom-nav-height','" + navPx + "px');" +
            "}catch(e){}})()";
        wv.evaluateJavascript(js, null);
    }

    private void createLoginOverlay() {
        loginOverlay = new FrameLayout(this);
        loginOverlay.setBackgroundColor(0x99000000);
        loginOverlay.setVisibility(View.GONE);
        loginOverlay.setClipToPadding(false);

        float d = getResources().getDisplayMetrics().density;
        int horizontalMargin = (int)(24 * d);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        scrollView.setClipToPadding(false);
        scrollView.setFillViewport(true);

        LinearLayout scrollContent = new LinearLayout(this);
        scrollContent.setOrientation(LinearLayout.VERTICAL);
        scrollContent.setGravity(Gravity.CENTER);
        scrollContent.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(16 * d);
        card.setCardElevation(8 * d);
        card.setCardBackgroundColor(0xFFFFFFFF);
        card.setContentPadding((int)(24 * d), (int)(32 * d), (int)(24 * d), (int)(24 * d));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardLp.setMargins(horizontalMargin, (int)(16 * d), horizontalMargin, (int)(16 * d));
        card.setLayoutParams(cardLp);

        LinearLayout cardContent = new LinearLayout(this);
        cardContent.setOrientation(LinearLayout.VERTICAL);
        cardContent.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Login to continue");
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF1A1A1A);
        title.setGravity(Gravity.CENTER);
        cardContent.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Create your profile and access all FundoCareer features.");
        subtitle.setTextSize(14);
        subtitle.setTextColor(0xFF666666);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(0, 1.3f);
        subtitle.setPadding(0, (int)(12 * d), 0, (int)(24 * d));
        cardContent.addView(subtitle);

        Button loginBtn = new Button(this);
        loginBtn.setText("Login / Sign up");
        loginBtn.setTextSize(16);
        loginBtn.setAllCaps(false);
        loginBtn.setTypeface(null, Typeface.BOLD);
        loginBtn.setTextColor(0xFFFFFFFF);
        loginBtn.setPadding(0, (int)(14 * d), 0, (int)(14 * d));
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xFF1A73E8);
        btnBg.setCornerRadius(8 * d);
        loginBtn.setBackground(btnBg);
        loginBtn.setOnClickListener(v -> requestNativeGoogleLogin("login-gate"));
        LinearLayout.LayoutParams loginBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        loginBtn.setLayoutParams(loginBtnLp);
        cardContent.addView(loginBtn);

        Button homeBtn = new Button(this);
        homeBtn.setText("Back to Home");
        homeBtn.setTextSize(16);
        homeBtn.setAllCaps(false);
        homeBtn.setTypeface(null, Typeface.NORMAL);
        homeBtn.setTextColor(0xFF1A73E8);
        homeBtn.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        homeBtn.setPadding(0, (int)(12 * d), 0, (int)(12 * d));
        homeBtn.setOnClickListener(v -> {
            hideLoginOverlay();
            WebView wv = getSafeWebView();
            if (wv != null) {
                wv.loadUrl(AppConfig.getDefaultUrl());
            }
        });
        LinearLayout.LayoutParams homeBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        homeBtn.setLayoutParams(homeBtnLp);
        cardContent.addView(homeBtn);

        card.addView(cardContent);
        scrollContent.addView(card);
        scrollView.addView(scrollContent);
        loginOverlay.addView(scrollView);

        contentFrame.addView(loginOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        ViewCompat.setOnApplyWindowInsetsListener(loginOverlay, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(0, top, 0, bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void showLoginOverlay() {
        if (loginOverlay != null && loginOverlay.getVisibility() != View.VISIBLE) {
            loginOverlay.setVisibility(View.VISIBLE);
            loginOverlay.bringToFront();
            Log.i(TAG, "Login overlay shown");
        }
    }

    private void hideLoginOverlay() {
        if (loginOverlay != null) {
            loginOverlay.setVisibility(View.GONE);
        }
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
            TypedValue outValue = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
            row.setBackgroundResource(outValue.resourceId);

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
                if (fUrl != null && fUrl.startsWith("native:")) {
                    String screen = fUrl.substring(7);
                    return;
                }
                WebView wv = getSafeWebView();
                if (wv != null && wv.getUrl() != null && !wv.getUrl().equals("about:blank")) {
                    String fPath = Uri.parse(fUrl).getPath();
                    if (fPath == null || fPath.isEmpty()) fPath = "/";
                    String js = "window.__fundocareerNavigate && window.__fundocareerNavigate(" + org.json.JSONObject.quote(fPath) + ")";
                    wv.evaluateJavascript(js, result -> {
                        if ("false".equals(result)) {
                            Log.w(TAG, "JS bridge not available for More item, falling back to loadUrl: " + fUrl);
                            wv.loadUrl(fUrl);
                        }
                    });
                } else {
                    Log.w(TAG, "WebView not available for More item, opening in browser: " + fUrl);
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(fUrl));
                    startActivity(browserIntent);
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

            ScrollView scrollView = new ScrollView(MainActivity.this);
            scrollView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ));
            scrollView.setClipToPadding(false);
            scrollView.setFillViewport(true);

            LinearLayout scrollContent = new LinearLayout(MainActivity.this);
            scrollContent.setOrientation(LinearLayout.VERTICAL);
            scrollContent.setGravity(Gravity.CENTER);
            scrollContent.setLayoutParams(new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            LinearLayout center = new LinearLayout(MainActivity.this);
            center.setOrientation(LinearLayout.VERTICAL);
            center.setGravity(Gravity.CENTER_HORIZONTAL);
            center.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            TextView icon = new TextView(MainActivity.this);
            icon.setText("\u26A0\uFE0F");
            icon.setTextSize(48);
            icon.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
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
            errorText.setPadding((int)(32 * d), (int)(16 * d), (int)(32 * d), (int)(16 * d));
            errorText.setMaxWidth((int)(320 * d));
            errorText.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            center.addView(errorText);

            Button retryBtn = new Button(MainActivity.this);
            retryBtn.setText("Retry");
            retryBtn.setTextSize(16);
            retryBtn.setAllCaps(false);
            retryBtn.setTypeface(null, Typeface.BOLD);
            retryBtn.setTextColor(0xFFFFFFFF);
            retryBtn.setBackgroundColor(0xFF1A73E8);
            retryBtn.setPadding((int)(32 * d), (int)(12 * d), (int)(32 * d), (int)(12 * d));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            blp.gravity = Gravity.CENTER_HORIZONTAL;
            retryBtn.setLayoutParams(blp);
            retryBtn.setOnClickListener(v -> {
                loadingOverlay.removeAllViews();
                loadingOverlay.setVisibility(View.GONE);
                WebView wv = getSafeWebView();
                if (wv != null) {
                    String current = wv.getUrl();
                    if (current == null || "about:blank".equals(current)) {
                        current = getUrlForTabId(activeNavId);
                    }
                    Log.i(NET_LOG_TAG, "Retry tapped, loading " + current);
                    wv.loadUrl(current);
                }
            });
            center.addView(retryBtn);

            scrollContent.addView(center);
            scrollView.addView(scrollContent);
            loadingOverlay.addView(scrollView);
            loadingOverlay.setVisibility(View.VISIBLE);
        });
    }

    private void injectPageSetup(WebView view) {
        int navPx = getNavHeightPx();
        String js = "(function(){"
                + "if(window.__fundocareerPageSetupDone)return;"
                + "window.__fundocareerPageSetupDone=true;"
                // App CSS contract variables
                + "var r=document.documentElement;"
                + "r.dataset.fundocareerApp='android';"
                + "try{localStorage.setItem('FUNDOCareer_APP_MODE','android')}catch(e){}"
                + "window.__fundocareerApp=true;"
                + "r.style.setProperty('--native-bottom-nav-height','" + navPx + "px');"
                + "r.style.setProperty('--safe-area-top','env(safe-area-inset-top,0px)');"
                + "r.style.setProperty('--safe-area-bottom','env(safe-area-inset-bottom,0px)');"
                // Mic tracking
                + "if(!window.__fundocareer_mic){"
                + "window.__fundocareer_mic=true;"
                + "var origGUM=navigator.mediaDevices&&navigator.mediaDevices.getUserMedia;"
                + "if(origGUM){"
                + "navigator.mediaDevices.getUserMedia=function(c){"
                + "return origGUM.call(navigator.mediaDevices,c).then(function(s){"
                + "window.__micStream=s;"
                + "try{window.MicrophoneBridge&&window.MicrophoneBridge.onMicStarted&&window.MicrophoneBridge.onMicStarted()}catch(e){}"
                + "s.getTracks().forEach(function(t){"
                + "t.addEventListener('ended',function(){"
                + "window.__micStream=null;"
                + "try{window.MicrophoneBridge&&window.MicrophoneBridge.onMicStopped&&window.MicrophoneBridge.onMicStopped()}catch(e){}"
                + "})});return s})}}}"
                // Navigation bridge
                + "if(!window.__fundocareerNavigate){"
                + "window.__fundocareerNavigate=function(path){"
                + "if(window.__FUNDOCAREER_ROUTER__&&typeof window.__FUNDOCAREER_ROUTER__.navigate==='function'){"
                + "window.__FUNDOCAREER_ROUTER__.navigate(path);return true}"
                + "return false}}"
                + "})()";
        view.evaluateJavascript(js, null);
    }

    private void injectMobileAppStyles(WebView view) {
        String navHcss = "var(--native-bottom-nav-height,80px)";
        String sabCss = "var(--safe-area-bottom,0px)";
        String js = "(function(){try{" +
                "if(document.getElementById('__fc_fixes'))return;" +
                "window.fundocareerAndroidApp='1.0';" +
                "var a=document.createElement('style');" +
                "a.id='__fc_fixes';" +
                "a.textContent=[" +
                // General app-mode foundation
                "'html[data-fundocareer-app=android]{overscroll-behavior-y:contain!important;-webkit-text-size-adjust:100%!important;scroll-behavior:smooth!important}'" +
                ",'html[data-fundocareer-app=android] body{overflow-x:hidden!important;max-width:100vw!important;padding-bottom:calc(" + navHcss + " + " + sabCss + ")!important;font-size:16px;line-height:1.5;-webkit-font-smoothing:antialiased!important;margin:0!important;width:100%!important}'" +
                ",'::selection{background:var(--color-primary,#1a73e8);color:#fff}'" +
                // Hide website chrome (header, nav, footer)
                ",'html[data-fundocareer-app=android] header,html[data-fundocareer-app=android] .navbar,html[data-fundocareer-app=android] .site-header,html[data-fundocareer-app=android] .main-header,html[data-fundocareer-app=android] .top-nav,html[data-fundocareer-app=android] .header-inner{display:none!important}'" +
                ",'html[data-fundocareer-app=android] footer,html[data-fundocareer-app=android] .footer,html[data-fundocareer-app=android] .site-footer{display:none!important}'" +
                // Touch targets (48px minimum)
                ",'html[data-fundocareer-app=android] button,html[data-fundocareer-app=android] a,html[data-fundocareer-app=android] input,html[data-fundocareer-app=android] select,html[data-fundocareer-app=android] textarea,html[data-fundocareer-app=android] [role=button]{min-height:48px!important;font-size:16px!important}'" +
                ",'html[data-fundocareer-app=android] button,html[data-fundocareer-app=android] a,html[data-fundocareer-app=android] [role=button]{min-width:48px!important}'" +
                // Responsive typography
                ",'html[data-fundocareer-app=android] h1{font-size:1.5rem!important;line-height:1.3!important}'" +
                ",'html[data-fundocareer-app=android] h2{font-size:1.25rem!important;line-height:1.35!important}'" +
                ",'html[data-fundocareer-app=android] h3{font-size:1.125rem!important;line-height:1.4!important}'" +
                ",'html[data-fundocareer-app=android] h4{font-size:1rem!important;line-height:1.4!important}'" +
                ",'html[data-fundocareer-app=android] p{font-size:0.938rem!important;line-height:1.5!important}'" +
                ",'html[data-fundocareer-app=android] small,.text-sm{font-size:0.813rem!important}'" +
                // Cards fit mobile width
                ",'html[data-fundocareer-app=android] [class*=card],html[data-fundocareer-app=android] [class*=Card]{border-radius:12px!important;box-shadow:0 2px 12px rgba(0,0,0,0.08)!important;margin-bottom:12px!important;overflow:hidden!important;width:100%!important;box-sizing:border-box!important}'" +
                // Grid -> single column on mobile
                ",'html[data-fundocareer-app=android] [class*=grid]{grid-template-columns:1fr!important}'" +
                // Full-width buttons
                ",'html[data-fundocareer-app=android] .btn,html[data-fundocareer-app=android] button[type=submit],html[data-fundocareer-app=android] [class*=btn]{width:100%!important;border-radius:8px!important;padding:14px 20px!important;text-align:center!important;justify-content:center!important;box-sizing:border-box!important}'" +
                // Forms
                ",'html[data-fundocareer-app=android] form{width:100%!important}'" +
                ",'html[data-fundocareer-app=android] [class*=form-group],html[data-fundocareer-app=android] [class*=formGroup]{width:100%!important;margin-bottom:1rem!important}'" +
                ",'html[data-fundocareer-app=android] input:not([type=radio]):not([type=checkbox]),html[data-fundocareer-app=android] select,html[data-fundocareer-app=android] textarea{width:100%!important;box-sizing:border-box!important;min-height:48px!important;padding:12px 14px!important;border-radius:8px!important;font-size:16px!important;margin-bottom:0!important}'" +
                ",'html[data-fundocareer-app=android] label{font-size:0.875rem!important;margin-bottom:4px!important;display:block!important;font-weight:500!important}'" +
                // Modals & dialogs fit screen
                ",'html[data-fundocareer-app=android] [class*=modal],html[data-fundocareer-app=android] [class*=dialog],html[data-fundocareer-app=android] [class*=Modal],html[data-fundocareer-app=android] [class*=Dialog]{width:95vw!important;max-width:95vw!important;max-height:90vh!important;border-radius:16px!important}'" +
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
                ",'html[data-fundocareer-app=android] [class*=mic-button],html[data-fundocareer-app=android] [class*=micBtn]{width:64px!important;height:64px!important;border-radius:50%!important}'" +
                // Route-specific: Resume Builder
                ",'html[data-fundocareer-app=android] [class*=builder],html[data-fundocareer-app=android] [class*=resume-editor],html[data-fundocareer-app=android] [class*=editor]{padding:0!important}'" +
                ",'html[data-fundocareer-app=android] [class*=resume-list],html[data-fundocareer-app=android] [class*=templates]{padding:1rem!important}'" +
                // Route-specific: Profile
                ",'html[data-fundocareer-app=android] [class*=profile]{padding:1rem!important}'" +
                ",'html[data-fundocareer-app=android] [class*=avatar]{width:64px!important;height:64px!important}'" +
                // Route-specific: profile action buttons
                ",'html[data-fundocareer-app=android] [class*=profile] [class*=btn],html[data-fundocareer-app=android] [class*=profile] button[type=button]{width:auto!important;min-width:48px!important;padding:12px 16px!important}'" +
                // Bottom gutter using CSS variable
                ",'html[data-fundocareer-app=android] main,html[data-fundocareer-app=android] [role=main],html[data-fundocareer-app=android] #app,html[data-fundocareer-app=android] .app{padding-bottom:calc(" + navHcss + " + " + sabCss + ")!important}'" +
                // Fix bottom sticky bars to not overlap nav
                ",'html[data-fundocareer-app=android] [class*=action-bar],html[data-fundocareer-app=android] [class*=ActionBar],html[data-fundocareer-app=android] [class*=sticky-bottom],html[data-fundocareer-app=android] [class*=bottom-bar]{bottom:calc(" + navHcss + " + " + sabCss + " + 8px)!important}'" +
                // Category chips horizontal scroll
                ",'html[data-fundocareer-app=android] [class*=chips],html[data-fundocareer-app=android] [class*=categories],html[data-fundocareer-app=android] [class*=tags]{overflow-x:auto!important;-webkit-overflow-scrolling:touch!important;flex-wrap:nowrap!important}'" +
                "].join('');" +
                "if(document.head){document.head.appendChild(a);}" +
                // Ensure viewport meta
                "var vp=document.querySelector('meta[name=viewport]');" +
                "if(!vp){vp=document.createElement('meta');vp.name='viewport';vp.content='width=device-width,initial-scale=1.0,maximum-scale=1.0,viewport-fit=cover';if(document.head){document.head.appendChild(vp);}}" +
                // Auto-scroll into view on focus (keyboard avoidance)
                "document.addEventListener('focusin',function(e){" +
                "var t=e.target.tagName;" +
                "if(t==='INPUT'||t==='TEXTAREA'||t==='SELECT'){" +
                "setTimeout(function(){try{e.target.scrollIntoView({behavior:'smooth',block:'center',inline:'nearest'})}catch(ex){} },400)" +
                "}" +
                "});" +
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
        boolean validTab = false;
        for (NavItemData item : NAV_ITEMS) {
            if (item.id == savedTabId) { validTab = true; break; }
        }
        if (!validTab) savedTabId = R.id.nav_home;
        String url = getUrlForTabId(savedTabId);

        if (networkMonitor != null && !networkMonitor.isOnline()) {
            setActiveNavItem(savedTabId);
            showOfflineOverlay();
            return;
        }

        // Load home page immediately — don't block on token refresh
        setActiveNavItem(savedTabId);
        if (savedTabId == R.id.nav_jobs) {
            if (webView.getUrl() == null || "about:blank".equals(webView.getUrl())) {
                webView.loadUrl(AppConfig.getDefaultUrl());
            }
            webView.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    launchJobsTab("restore");
                }
            }, 250);
        } else {
            Log.i(NAV_LOG_TAG, "restore active tab: id=" + savedTabId + " url=" + url);
            webView.loadUrl(url);
        }

        // Defer token refresh to background — page already loading
        if (authManager != null && authManager.getTokenStore().hasRefreshToken()
                && (authManager.getTokenStore().isTokenExpired() || !authManager.isLoggedIn())) {
            Log.i(TAG, "Session expired, deferring token refresh to background");
            attemptStartupRefresh();
        } else if (navPolicy != null && navPolicy.isUserLoggedIn()) {
            authManager.injectAuthState(webView);
        }
    }

    private void attemptStartupRefresh() {
        authManager.refreshAccessToken(new AuthManager.AuthCallback() {
            @Override
            public void onSuccess(JSONObject authData) {
                Log.i(TAG, "Startup token refresh succeeded");
                runOnUiThread(() -> {
                    WebView wv = getSafeWebView();
                    if (wv != null) {
                        authManager.setAuthCookies();
                        authManager.injectAuthState(wv);
                    }
                });
            }

            @Override
            public void onError(String error) {
                Log.i(TAG, "Startup token refresh failed: " + error);
            }
        });
    }
}
