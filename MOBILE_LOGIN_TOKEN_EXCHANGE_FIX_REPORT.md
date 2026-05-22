# MOBILE_LOGIN_TOKEN_EXCHANGE_FIX_REPORT

## Root Cause

The backend HTTP POST response from `POST /api/mobile/auth/google/android-token` was NOT valid JSON, so `httpPost()` caught the `JSONException` and stored the raw body as `rawResponse` string. Then `exchangeGoogleToken()` only checked top-level keys `accessToken`, `refreshToken`, `user` — which didn't exist on the `{rawResponse, _statusCode}` wrapper object. The token was silently discarded without logging the real response.

## Fix Applied

### `AuthManager.httpPost()` — enhanced logging
Before returning, now logs:
- `Token exchange HTTP status=<status> URL=<url>`
- `Token exchange outer keys=<keys>`
- If JSON parse fails: `Response not valid JSON, storing as rawResponse. length=<n> preview=<first 200 chars>`
- If HTML: `Response appears to be HTML (not JSON)`

### `AuthManager.exchangeGoogleToken()` — rawResponse parsing

New logic flow:
1. Call `httpPost()` as before
2. If `rawResponse` key exists, attempt `new JSONObject(rawResponse)` to parse it
3. If inner parse succeeds, merge all inner keys into the response object (keeping `_statusCode`)
4. If inner parse fails, log the raw content preview and return error
5. Now check `accessToken`, `refreshToken`, `user` from the merged response
6. Log `hasAccessToken`, `hasRefreshToken`, `hasUser` with details
7. On failure, log: `HTTP=<code> code=<backendCode> message=<backendMessage>`
8. On success, pass callback with merged response

## Instructions to Test

1. Build and install APK
2. Run:
```
adb logcat -c
adb shell am force-stop com.fundocareer.app
adb shell monkey -p com.fundocareer.app 1
adb logcat | grep -E "FundoCareer-Auth|FundoCareerApp|Token exchange|rawResponse|hasAccess|hasRefresh|hasUser|profile|401|setAuthCookies|Auth localStorage|Auth cookie"
```

3. Tap Google login
4. Select account in picker
5. Look for these log lines in order:

```
Google OAuth intercepted from WebView: https://www.fundocareer.com/api/auth/google
Native Google login requested: reason=web-oauth-intercept
Starting native Google Sign-In account picker
SignInHubActivity launched
Google sign-in result received: RESULT_OK
Google ID token received, exchanging with backend
POSTing to: https://www.fundocareer.com/api/mobile/auth/google/android-token
Token exchange HTTP status=XXX URL=https://www.fundocareer.com/api/mobile/auth/google/android-token
Token exchange outer keys=[...]
rawResponse present length=N
rawResponse parsed as JSON, inner keys=[...]    (OR)  rawResponse not JSON, content preview: ...
hasAccessToken=true length=XXX
hasRefreshToken=true length=XXX
hasUser=true email=XX***
Token exchange succeeded for 'XX***@...'
```

## What to Check if Still Failing

Read the `rawResponse` log line to find the real backend error:

**If rawResponse parsed as JSON but no accessToken:**
- The backend returned `{success: false, code: "...", message: "..."}`
- Fix: Check production backend env vars (GOOGLE_WEB_CLIENT_ID, JWT_SECRET, etc.)
- Common: audience mismatch → Google ID token's audience doesn't match the backend's `GOOGLE_WEB_CLIENT_ID`

**If rawResponse is HTML:**
- The endpoint doesn't exist on production, or returns a 404/500 page
- Fix: Deploy the mobile auth routes to production, or test against a backend that has them

**If rawResponse is empty/blank:**
- Network error or timeout
- Fix: Check connectivity, firewall, or use `adb reverse` or tunnel

## Current Log Evidence

| Step | Expected Log | Status |
|------|-------------|--------|
| Intercept | `Google OAuth intercepted from WebView` | ❌ NOT YET TESTED |
| Picker | `Starting native Google Sign-In account picker` | ❌ NOT YET TESTED |
| Result | `Google sign-in result received: RESULT_OK` | ❌ NOT YET TESTED |
| Token | `Google ID token received, exchanging with backend` | ❌ NOT YET TESTED |
| HTTP status | `Token exchange HTTP status=` | ❌ NOT YET TESTED |
| Outer keys | `Token exchange outer keys=` | ❌ NOT YET TESTED |
| Raw parse | `rawResponse parsed as JSON` OR `rawResponse not JSON` | ❌ NOT YET TESTED |
| Has token | `hasAccessToken=true` | ❌ NOT YET TESTED |
| Cookie | `Auth cookie set:` | ❌ NOT YET TESTED |
| localStorage | `Auth localStorage injected:` | ❌ NOT YET TESTED |
| Profile | `/api/user/profile status=200` | ❌ NOT YET TESTED |

**All items remain NOT VERIFIED until logcat output from the fixed APK is collected.**
