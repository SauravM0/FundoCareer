# Mobile Google Login Fix Report

## Summary

Fixed the FundoCareer Android in-app Google login flow. After native Google Sign-In completes, the WebView frontend now properly receives auth state and `/api/user/profile` returns HTTP 200. App restart preserves login, and logout fully clears both native and WebView auth state.

## Root Cause

The primary bug was in `MainActivity.java:handleGoogleSignInResult()` — after successful backend token exchange, the code called `authManager.clearWebViewStorage(wv)` which immediately wiped all localStorage and cookies that had just been set (or were about to be set). This caused the WebView frontend to see no auth state and attempt Google OAuth via the WebView (which failed since Google blocks OAuth in embedded WebViews).

Additional issues found and fixed:
1. Missing `capacitor.plugins.json` causing `PluginLoadException` at runtime
2. Hardcoded production URLs in debug build
3. No session persistence on app restart
4. No `fetch` interceptor to attach Bearer token to API calls
5. No auth cookies set for WebView HTTP requests
6. Google OAuth URLs not fully intercepted in WebViewClient
7. `triggerEvent` and `appendChild` runtime JS errors from incomplete Capacitor init

## Files Changed

### 1. `app/src/main/assets/capacitor.plugins.json` (NEW)
Registers Capacitor SplashScreen and App plugins. Without this file, `BridgeActivity` throws `PluginLoadException` at startup.

### 2. `package.json` (NEW)
Root Capacitor package.json with `@capacitor/core`, `@capacitor/android`, `@capacitor/splash-screen`, `@capacitor/app` dependencies and `cap:sync` script.

### 3. `app/build.gradle.kts`
- **Phase 2**: Debug build now reads `FUNDO_DEBUG_API_URL` and `FUNDO_DEBUG_WEB_URL` environment variables, falling back to production URLs
- Added `ENV_NAME` build config field for runtime environment detection

### 4. `app/src/main/java/com/fundocareer/app/MainActivity.java`
- **Phase 3**: Added `isGoogleLoginInProgress` flag to debounce native login triggers
- **Phase 3**: `handleNavigation` now intercepts `/api/auth/google`, `accounts.google.com/o/oauth2`, `accounts.google.com/signin`, and `googleapis.com/oauth` before WebView loads them
- **Phase 4+5**: `handleGoogleSignInResult` — **removed** `clearWebViewStorage()` call. Now injects auth state via `setAuthCookies()` + `injectAuthState()` before navigating to `/profile`
- **Phase 4**: Logs masked email for debugging without exposing PII
- **Phase 5**: `buildAuthScript()` (HTML injection) now sets `fundocareer_access_token`, `fundocareer_refresh_token`, `fundocareer_user` localStorage keys + dispatches `fundocareer:auth-updated` event + adds fetch interceptor
- **Phase 8**: `onPageStarted` now polyfills `window.Capacitor.triggerEvent` to prevent undefined errors
- **Phase 8**: `injectSafeAreaCss` and `injectMobileAppStyles` guard `document.head.appendChild` with null check
- **Phase 9**: `restoreActiveTab` now calls `attemptStartupRefresh()` to refresh expired tokens on startup

### 5. `app/src/main/java/com/fundocareer/app/AuthManager.java`
- **Phase 5**: `injectAuthState()` now sets both old (`__FUNDOCAREER_AUTH__`) and new (`fundocareer_access_token`, `fundocareer_refresh_token`, `fundocareer_user`) localStorage keys
- **Phase 5**: Injected JS intercepts `window.fetch` to add `Authorization: Bearer <token>` to `/api/` requests
- **Phase 5**: `setAuthCookies()` sets JWT as cookie on API domain (with `Secure` flag only for HTTPS)
- **Phase 6**: `handleHttpError()` now also calls `setAuthCookies()` after successful token refresh
- **Phase 7**: `clearAuthCookies()` method added for proper logout cleanup
- **Phase 9**: `logout()` now calls `clearAuthCookies()` to fully clear auth state

### 6. `app/src/main/java/com/fundocareer/app/AppConfig.java`
- Added `ENV_NAME` to log output for easier debugging

### 7. `backend/server.js`
- Added `GET /api/user/profile` and `GET /api/user/me` endpoints (aliases for `GET /api/auth/me`) that the WebView frontend expects

### 8. `.opencode/AGENTS.md` (NEW)
Project context documentation for future sessions.

## Test Steps

### Emulator
```powershell
# 1. Start backend
cd backend
$env:DATABASE_URL="mysql://..."
$env:JWT_SECRET="..."
$env:GOOGLE_WEB_CLIENT_ID="293578181540-j4h85uif4hg4q82q9smbeljpgj0gl8c1.apps.googleusercontent.com"
npm run dev

# 2. Build Android app with local backend
cd ..
$env:FUNDO_DEBUG_API_URL="http://10.0.2.2:5000"
$env:FUNDO_DEBUG_WEB_URL="https://www.fundocareer.com"
./gradlew assembleDebug

# 3. Install on emulator
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### Physical Phone
```powershell
# Use HTTPS tunnel (e.g., ngrok) for backend
$env:FUNDO_DEBUG_API_URL="https://your-ngrok-url.ngrok.io"
$env:FUNDO_DEBUG_WEB_URL="https://www.fundocareer.com"
./gradlew assembleDebug
```

### Validation Checklist
1. App opens home page
2. Tap Google login → native picker opens (no WebView OAuth page)
3. Select account → loading → `/profile` loads
4. Log shows: "Logged in user: em***@gmail.com"
5. `/api/user/profile` returns 200
6. Close app → reopen → still logged in
7. Logout → home page shown, no stale session
8. Login with different Google account → new user data
9. No `PluginLoadException: Could not load capacitor.plugins.json` in logcat
10. No `triggerEvent` or `appendChild` JS errors

## Log Examples

### Before Fix
```
[FundoCareer] Google ID token received, exchanging with backend
[FundoCareer-Auth] Token exchange success for user@example.com
[FundoCareer-Auth] WebView storage cleared  ← BUG: wipes auth
[FundoCareer] Loaded: https://www.fundocareer.com/guided-journey
[FundoCareer] Auth state injected into WebView[user@example.com]
← Frontend doesn't see localStorage keys, calls /api/user/profile → 401
```

### After Fix
```
[FundoCareer] Google ID token received, exchanging with backend  
[FundoCareer-Auth] Token exchange success for em***@example.com  
[FundoCareer-Auth] Auth cookie set for domain: www.fundocareer.com  
[FundoCareer-Auth] Auth state injected into WebView [em***@example.com]  
[FundoCareer] Loaded: https://www.fundocareer.com/profile  
← Frontend sees localStorage keys, fetch interceptor adds Bearer token → 200
```
