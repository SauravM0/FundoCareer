package com.fundocareer.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class SecureTokenStore {

    private static final String TAG = "FundoCareer-Auth";
    private static final String STORE_NAME = "fundocareer_secure_store";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_ID_TOKEN = "id_token";
    private static final String KEY_USER_EMAIL = "user_email";
    private static final String KEY_USER_NAME = "user_name";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_USER_IMAGE = "user_image";
    private static final String KEY_USER_ROLE = "user_role";
    private static final String KEY_TOKEN_EXPIRY = "token_expiry";

    private final SharedPreferences store;

    public SecureTokenStore(Context context) {
        SharedPreferences prefs = null;
        try {
            String masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            prefs = EncryptedSharedPreferences.create(
                    STORE_NAME,
                    masterKey,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Failed to init encrypted storage, falling back to plain storage (INSECURE!)", e);
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "DEBUG build: plain-text fallback accepted. RELEASE builds should not reach here.");
            }
            prefs = context.getSharedPreferences(STORE_NAME + "_fallback", Context.MODE_PRIVATE);
        }
        store = prefs;
    }

    public void saveTokens(String accessToken, String refreshToken, String idToken,
                           String userEmail, String userName, String userId,
                           String userImage, String userRole, long expiryMs) {
        store.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putString(KEY_ID_TOKEN, idToken)
                .putString(KEY_USER_EMAIL, userEmail)
                .putString(KEY_USER_NAME, userName)
                .putString(KEY_USER_ID, userId)
                .putString(KEY_USER_IMAGE, userImage)
                .putString(KEY_USER_ROLE, userRole)
                .putLong(KEY_TOKEN_EXPIRY, expiryMs)
                .apply();
    }

    public void saveTokensSync(String accessToken, String refreshToken, String idToken,
                               String userEmail, String userName, String userId,
                               String userImage, String userRole, long expiryMs) {
        store.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putString(KEY_ID_TOKEN, idToken)
                .putString(KEY_USER_EMAIL, userEmail)
                .putString(KEY_USER_NAME, userName)
                .putString(KEY_USER_ID, userId)
                .putString(KEY_USER_IMAGE, userImage)
                .putString(KEY_USER_ROLE, userRole)
                .putLong(KEY_TOKEN_EXPIRY, expiryMs)
                .commit();
        Log.i(TAG, "Tokens saved synchronously (commit)");
    }

    public String getAccessToken() {
        return store.getString(KEY_ACCESS_TOKEN, null);
    }

    public String getRefreshToken() {
        return store.getString(KEY_REFRESH_TOKEN, null);
    }

    public String getIdToken() {
        return store.getString(KEY_ID_TOKEN, null);
    }

    public String getUserEmail() {
        return store.getString(KEY_USER_EMAIL, null);
    }

    public String getUserName() {
        return store.getString(KEY_USER_NAME, null);
    }

    public String getUserId() {
        return store.getString(KEY_USER_ID, null);
    }

    public String getUserImage() {
        return store.getString(KEY_USER_IMAGE, null);
    }

    public String getUserRole() {
        return store.getString(KEY_USER_ROLE, null);
    }

    public long getTokenExpiry() {
        return store.getLong(KEY_TOKEN_EXPIRY, 0);
    }

    public boolean hasValidSession() {
        String accessToken = getAccessToken();
        if (accessToken == null || accessToken.isEmpty()) return false;
        long expiry = getTokenExpiry();
        return expiry <= 0 || System.currentTimeMillis() < expiry;
    }

    public boolean hasRefreshToken() {
        String refresh = getRefreshToken();
        return refresh != null && !refresh.isEmpty();
    }

    public boolean isTokenExpired() {
        long expiry = getTokenExpiry();
        return expiry > 0 && System.currentTimeMillis() >= expiry;
    }

    public String getAuthStateJson() {
        return "{"
                + "\"isAuthenticated\":" + hasValidSession() + ","
                + "\"accessToken\":\"" + safe(getAccessToken()) + "\","
                + "\"refreshToken\":\"" + safe(getRefreshToken()) + "\","
                + "\"idToken\":\"" + safe(getIdToken()) + "\","
                + "\"email\":\"" + safe(getUserEmail()) + "\","
                + "\"name\":\"" + safe(getUserName()) + "\","
                + "\"userId\":\"" + safe(getUserId()) + "\","
                + "\"image\":\"" + safe(getUserImage()) + "\","
                + "\"role\":\"" + safe(getUserRole()) + "\""
                + "}";
    }

    public void clearAll() {
        store.edit().clear().apply();
        Log.i(TAG, "Secure token store cleared");
    }

    private String safe(String value) {
        return value != null ? value.replace("\"", "\\\"").replace("\n", "\\n") : "";
    }
}
