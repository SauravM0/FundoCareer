# MOBILE_LOGIN_REAL_VERIFICATION_REPORT

## A. REAL OBSERVED LOG EVIDENCE

**Instructions:** Build and install the APK, then run:
```
adb logcat -c
adb shell am force-stop com.fundocareer.app
adb shell monkey -p com.fundocareer.app 1
adb logcat | grep -E "FundoCareer|Google|Auth|profile|401|setAuthCookies|PluginLoadException"
```

Paste actual log lines below for each step.

### 1. App Config
```
(actual log lines here)
```

### 2. Tap Login (Intercept)
```
(actual log lines here)
```

### 3. Native Picker Launched
```
(actual log lines here)
```

### 4. Activity Result Received
```
(actual log lines here)
```

### 5. Token Exchange
```
(actual log lines here)
```

### 6. Cookie / localStorage Injection
```
(actual log lines here)
```

### 7. Profile Status
```
(actual log lines here)
```

---

## B. PASS/FAIL CHECKLIST

| # | Log Line | Status |
|---|----------|--------|
| 1 | No `PluginLoadException` | ❌ NOT VERIFIED |
| 2 | `Google OAuth intercepted from WebView` | ❌ NOT VERIFIED |
| 3 | `Native Google login requested: reason=...` | ❌ NOT VERIFIED |
| 4 | `Starting native Google Sign-In account picker` | ❌ NOT VERIFIED |
| 5 | `SignInHubActivity launched` | ❌ NOT VERIFIED |
| 6 | `Google sign-in result received` | ❌ NOT VERIFIED |
| 7 | `Google ID token received` | ❌ NOT VERIFIED |
| 8 | `Backend exchange succeeded: hasAccessToken=true` | ❌ NOT VERIFIED |
| 9 | `Auth cookie set: domain=... hasAccessToken=true` | ❌ NOT VERIFIED |
| 10 | `Auth localStorage injected: hasAccessToken=true hasUser=true` | ❌ NOT VERIFIED |
| 11 | `Profile verification dispatched to WebView` | ❌ NOT VERIFIED |
| 12 | `/api/user/profile status=200` | ❌ NOT VERIFIED |
| 13 | `Navigate to profile after successful login` | ❌ NOT VERIFIED |
| 14 | No `Google login already in progress, ignoring` during login attempt | ❌ NOT VERIFIED |
| 15 | No `setAuthCookies: no access token available` | ❌ NOT VERIFIED |

---

## C. ROOT CAUSE (Fixed)

**Double-guard bug:** `handleNavigation()` set `isGoogleLoginInProgress = true` then called `startGoogleSignIn()` which checked the same flag and returned immediately. The native Google account picker **never launched**.

**Fix applied:**
1. Removed ALL `isGoogleLoginInProgress` guards from `handleNavigation()` and `GOOGLE_AUTH` case — both now simply call `requestNativeGoogleLogin(reason)` which is the single entry point.
2. Added `requestNativeGoogleLogin(String reason)` — checks state, includes 15-second watchdog auto-reset, then delegates to `startGoogleSignIn()`.
3. `startGoogleSignIn()` always launches (no guard) — sets flag, calls `signOut().addOnCompleteListener()` then launches `getSignInIntent()`.
4. Added `loginWatchdog` Runnable — auto-resets state after 15s if no result arrives.
5. `ActivityResultLauncher` callback always resets `isGoogleLoginInProgress` and removes watchdog on any result (success or cancel).
6. Every failure path in `handleGoogleSignInResult` resets state and removes watchdog.

## D. FILES CHANGED

- `app/src/main/java/com/fundocareer/app/MainActivity.java`
  - Added `loginStartTime`, `LOGIN_WATCHDOG_MS`, `loginWatchdog` Runnable fields
  - Added `requestNativeGoogleLogin(String reason)` method
  - Replaced `startGoogleSignIn()` — no guard, always launches, sets watchdog
  - `handleNavigation()` Google intercept — removed all flag checks, calls `requestNativeGoogleLogin`
  - `GOOGLE_AUTH` case — removed all flag checks, calls `requestNativeGoogleLogin`
  - `AuthBridge.onSignInRequested` — calls `requestNativeGoogleLogin`
  - `googleSignInLauncher` callback — always resets `isGoogleLoginInProgress` and removes watchdog
  - All failure paths in `handleGoogleSignInResult` now reset `loginStartTime` and remove watchdog
  - `performLogout` resets login state before proceeding

## E. REMAINING FAILURE (to be determined after logcat run)

Check which line in section B is the first FAIL after running the app.
