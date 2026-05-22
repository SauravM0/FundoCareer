package com.fundocareer.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import android.widget.Toast;

import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PaymentHandler {

    public static class Bridge {
        private final Activity activity;

        public Bridge(Activity activity) {
            this.activity = activity;
        }

        @android.webkit.JavascriptInterface
        public void openCheckout(String url) {
            PaymentHandler.openCheckout(activity, url);
        }
    }

    private static final String ANALYTICS_TAG = "FundoCareer-Analytics";
    private static final String TAG = "FundoCareer-Payment";

    // Internal app route paths that should stay in WebView
    private static final Set<String> INTERNAL_PATHS = new HashSet<>(Arrays.asList(
            "", "/", "/home",
            "/resumes", "/resume", "/resume/",
            "/jobpage", "/jobs", "/jobs/",
            "/job-application", "/apply", "/apply/",
            "/ats-checker", "/ats", "/checker",
            "/mock-interview", "/mock", "/interview",
            "/profile", "/profile/", "/account",
            "/pricing", "/plans", "/plans/",
            "/settings", "/notifications",
            "/auth", "/auth/",
            "/terms", "/privacy", "/about",
            "/contact", "/faq", "/help"
    ));

    // Payment-only paths: these always open externally
    private static final Set<String> PAYMENT_PATHS = new HashSet<>(Arrays.asList(
            "/checkout", "/checkout/",
            "/subscribe", "/subscribe/",
            "/purchase", "/purchase/",
            "/payment", "/payment/",
            "/billing", "/billing/"
    ));

    // Known payment provider domains
    private static final Set<String> EXTERNAL_PAYMENT_HOSTS = new HashSet<>(Arrays.asList(
            // Stripe
            "checkout.stripe.com",
            "js.stripe.com",
            "api.stripe.com",
            "m.stripe.com",
            "hooks.stripe.com",
            // Razorpay
            "razorpay.com",
            "www.razorpay.com",
            "api.razorpay.com",
            "checkout.razorpay.com",
            // PayPal
            "paypal.com",
            "www.paypal.com",
            "sandbox.paypal.com",
            "api.paypal.com",
            "www.sandbox.paypal.com",
            // General secure payment gateways
            "checkout.payu.com",
            "secure.payu.com",
            "payu.in",
            "www.payu.in",
            "ccavenue.com",
            "secure.ccavenue.com",
            "billdesk.com",
            "www.billdesk.com",
            // Google Pay / Apple Pay (web fallback)
            "pay.google.com",
            "applepay.apple.com"
    ));

    // Special URI schemes that need Intent dispatch
    private static final Set<String> SPECIAL_SCHEMES = new HashSet<>(Arrays.asList(
            "tel", "mailto", "sms", "geo", "whatsapp",
            "tg", "viber", "skype", "facetime",
            "market", "maps", "intent"
    ));

    public static boolean isInternalPath(String path) {
        if (path == null) return false;
        // Check exact match first
        if (INTERNAL_PATHS.contains(path)) return true;
        // Check parent path matches (e.g. /resumes/123 matches /resumes)
        for (String internal : INTERNAL_PATHS) {
            if (!internal.isEmpty() && path.startsWith(internal + "/")) return true;
            if (!internal.isEmpty() && internal.endsWith("/") && path.startsWith(internal)) return true;
        }
        return false;
    }

    public static boolean isPaymentPath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        for (String p : PAYMENT_PATHS) {
            if (lower.equals(p) || lower.startsWith(p)) return true;
        }
        return false;
    }

    public static boolean hasPaymentParams(String query) {
        if (query == null) return false;
        String q = query.toLowerCase();
        return q.contains("checkout_session_id=")
                || q.contains("payment_intent=")
                || q.contains("setup_intent=")
                || q.contains("payment_id=")
                || q.contains("razorpay_payment_id=")
                || q.contains("order_id=");
    }

    public static boolean isExternalPaymentHost(String host) {
        if (host == null) return false;
        for (String h : EXTERNAL_PAYMENT_HOSTS) {
            if (host.equals(h) || host.endsWith("." + h)) return true;
        }
        return false;
    }

    public static boolean isSpecialScheme(String scheme) {
        if (scheme == null) return false;
        return SPECIAL_SCHEMES.contains(scheme.toLowerCase());
    }

    /**
     * Comprehensive URL type classification.
     * Returns the routing action to take for the given URL.
     */
    public enum UrlAction {
        INTERNAL,       // Load in WebView
        PAYMENT,        // Open in Custom Tab (payment flow)
        EXTERNAL,       // Open in Custom Tab (non-payment external)
        SPECIAL_SCHEME, // Open via Intent for tel/mailto/etc
        GOOGLE_AUTH     // Trigger native Google Sign-In
    }

    public static UrlAction classifyUrl(String urlString, String allowedHost) {
        try {
            Uri uri = Uri.parse(urlString);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();

            // Log for debugging
            Log.d(TAG, "Classifying: " + urlString + " (host=" + host + ", path=" + path + ")");

            // 1. Handle special URI schemes first
            if (scheme != null && isSpecialScheme(scheme)) {
                return UrlAction.SPECIAL_SCHEME;
            }

            if (host == null) return UrlAction.EXTERNAL;

            // 2. Google OAuth — trigger native sign-in
            if (host.contains("accounts.google.com")
                    || host.contains("googleapis.com/oauth")
                    || host.equals("google.com")
                    || host.equals("www.google.com")) {
                return UrlAction.GOOGLE_AUTH;
            }

            // 3. Known external payment provider hosts → always external
            if (isExternalPaymentHost(host)) {
                return UrlAction.PAYMENT;
            }

            // 4. Internal app host
            if (host.equals(allowedHost) || host.endsWith("." + allowedHost)) {
                // Check if the path is a payment path
                if (isPaymentPath(path)) {
                    return UrlAction.PAYMENT;
                }
                // Check if query has payment params (e.g. redirect back from payment)
                if (hasPaymentParams(uri.getQuery())) {
                    return UrlAction.PAYMENT;
                }
                // Everything else on our host is internal
                return UrlAction.INTERNAL;
            }

            // 5. Everything else is external
            return UrlAction.EXTERNAL;

        } catch (Exception e) {
            Log.w(TAG, "classifyUrl error for: " + urlString, e);
            return UrlAction.EXTERNAL;
        }
    }

    public static void openCheckout(Activity activity, String urlString) {
        try {
            int color = ContextCompat.getColor(activity, R.color.primary);

            CustomTabsIntent tabs = new CustomTabsIntent.Builder()
                    .setToolbarColor(color)
                    .setShowTitle(true)
                    .setUrlBarHidingEnabled(true)
                    .addDefaultShareMenuItem()
                    .build();

            tabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            tabs.launchUrl(activity, Uri.parse(urlString));

            logEvent("checkout_opened", urlString);
        } catch (Exception e) {
            Log.e(TAG, "CCT failed, falling back to browser: " + urlString, e);
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(urlString));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(fallback);
                logEvent("checkout_opened_fallback", urlString);
            } catch (Exception ex) {
                Log.e(TAG, "Fallback also failed", ex);
            }
        }
    }

    public static void openExternal(Activity activity, String urlString) {
        try {
            int color = ContextCompat.getColor(activity, R.color.primary);

            CustomTabsIntent tabs = new CustomTabsIntent.Builder()
                    .setToolbarColor(color)
                    .setShowTitle(true)
                    .setUrlBarHidingEnabled(true)
                    .build();

            tabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            tabs.launchUrl(activity, Uri.parse(urlString));

            logEvent("external_opened", urlString);
        } catch (Exception e) {
            Log.e(TAG, "CCT failed, falling back to browser: " + urlString, e);
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(urlString));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(fallback);
            } catch (Exception ex) {
                Log.e(TAG, "Fallback also failed", ex);
            }
        }
    }

    public static void openSpecialScheme(Activity activity, Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            Log.i(TAG, "Special scheme: " + uri.getScheme() + "://");
        } catch (Exception e) {
            Log.e(TAG, "Failed to open special scheme: " + uri, e);
            Toast.makeText(activity, "Cannot open this link", Toast.LENGTH_SHORT).show();
        }
    }

    public static void logPricingView() {
        logEvent("pricing_viewed", "");
    }

    static void logEvent(String event, String url) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("event", event);
            obj.put("url", url != null ? url : "");
            obj.put("ts", System.currentTimeMillis());
            Log.i(ANALYTICS_TAG, obj.toString());
        } catch (Exception e) {
            Log.w(TAG, "Analytics error", e);
        }
    }
}
