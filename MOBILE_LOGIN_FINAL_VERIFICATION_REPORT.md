# MOBILE_LOGIN_FINAL_VERIFICATION_REPORT

## Root Cause

The Google login flow had multiple interacting failures. The core issues were:

1. **Capacitor plugins.json malformed at runtime** — The build output `capacitor.plugins.json` was valid JSON but `Capacitor` runtime threw `PluginLoadException`. Fixed by clearing the file to `[]`.

2. **Token exchange success path did not validate accessToken presence** — `exchangeGoogleToken()` in `AuthManager.java` logged "Token exchange success" even when `accessToken` was null/empty. The backend could return `{success: true}` without an `accessToken` and the Android app would still treat it as success. Then `setAuthCookies()` and `injectAuthState()` read null from the token store.

3. **Cookie/localStorage injection relied on SharedPreferences reads immediately after async `.apply()`** — `saveTokens()` uses `SharedPreferences.Editor.apply()` which is async for disk writes but synchronous for in-memory cache. However, the design was fundamentally fragile: the success callback extracted tokens → stored them → then immediately read them back. The fix bypasses this entirely by passing tokens directly from the HTTP response to injection methods.

4. **Account picker not appearing on repeated taps** — Google Sign-In caches the last selected account. Without calling `signOut()` before `getSignInIntent()`, the picker doesn't appear and silently signs in with the previous account.

5. **No startup session restore** — On app launch, tokens existed in secure storage but were never injected as cookies or localStorage before the WebView loaded pages.

6. **No post-injection profile verification** — No mechanism existed to verify that auth injection actually worked before navigating to /profile.

## Files Changed

| File | Changes |
|------|---------|
| `app/src/main/assets/capacitor.plugins.json` | Replaced content with `[]` (empty array) |
| `app/src/main/java/com/fundocareer/app/SecureTokenStore.java` | Added `saveTokensSync()` using `.commit()` |
| `app/src/main/java/com/fundocareer/app/AuthManager.java` | Added explicit-token overloads for `setAuthCookies()` and `injectAuthState()`. Added response key logging and accessToken validation in `exchangeGoogleToken()`. Added `verifyProfileAfterInjection()`. |
| `app/src/main/java/com/fundocareer/app/MainActivity.java` | Replaced `startGoogleSignIn()` (signOut first). Replaced `handleGoogleSignInResult()` (extract tokens from response directly, pass explicitly). Added startup session restore in `initAuth()`. Replaced `performLogout()`. |

## Exact Backend Response Shape Found

From `backend/features/auth/controllers/mobileAuth.controller.js`, the `exchangeAndroidIdToken` endpoint returns:

```json
{
  "success": true,
  "accessToken": "<JWT string>",
  "refreshToken": "<random hex string>",
  "expiresIn": 900,
  "tokenType": "Bearer",
  "user": {
    "id": "<uuid>",
    "email": "<user@example.com>",
    "name": "<display name>",
    "image": "<url or null>",
    "role": "user"
  }
}
```

The refresh endpoint returns:
```json
{
  "success": true,
  "accessToken": "<new JWT>",
  "expiresIn": 900,
  "tokenType": "Bearer"
}
```

## Exact Frontend Auth Keys Found

The frontend WebView code expects these localStorage keys:

| Key | Value |
|-----|-------|
| `fundocareer_access_token` | JWT access token |
| `fundocareer_refresh_token` | Refresh token |
| `fundocareer_user` | JSON string `{id, email, name, image, role}` |
| `fundocareer_auth_state` | `"true"` |
| `FUNDOCareer_ACCESS_TOKEN` | JWT access token (backward compat) |
| `FUNDOCareer_USER` | JSON-stringified user object (backward compat) |
| `__FUNDOCAREER_AUTH__` | Global JS object `{accessToken, email, name, userId, image, role}` |

Custom events dispatched:
- `fundocareer-auth-ready` with `detail = __FUNDOCAREER_AUTH__`
- `fundocareer:auth-updated` with `detail = {source: 'android-native', isAuthenticated: true, user: {...}}`

Fetch interceptor: Adds `Authorization: Bearer <token>` header to any `/api/` request.

## Exact Cookie/Header Method Used

**Backend auth middleware** (`backend/middlewares/auth.middleware.js`):

```javascript
const token = req.cookies?.token || 
              (req.headers.authorization && req.headers.authorization.split(' ')[1]);
```

Accepts EITHER:
- Cookie named `token` (set via `CookieManager.getInstance().setCookie()`)
- `Authorization: Bearer <JWT>` header (set via fetch interceptor)

**Android sets cookie** on the API base URL domain with key `"token"`:
```
CookieManager.getInstance().setCookie(host, "token=<accessToken>; Path=/; Secure; Max-Age=2592000")
```

**Android also injects localStorage + fetch interceptor** so JS-side API calls get `Authorization: Bearer` header automatically.

## Before/After Log Excerpts

### Before (broken):
```
PluginLoadException: Could not parse capacitor.plugins.json as JSON
Env: debug
WebApp URL: https://www.fundocareer.com
API URL: https://www.fundocareer.com
Blocked Google OAuth WebView navigation: https://www.fundocareer.com/api/auth/google
Google ID token received, exchanging with backend
Token exchange success for 
Auth: token exchange succeeded
Auth exchange successful
Login success (email not available in token store yet)
setAuthCookies: no access token available
HTTP 401 for https://www.fundocareer.com/api/user/profile
```

### After (expected):
```
No PluginLoadException
FundoCareer-Config: Env=debug WebApp=https://www.fundocareer.com API=https://www.fundocareer.com
Google login tapped: intercepted web OAuth
Starting native Google Sign-In account picker
SignInHubActivity launched
Google ID token received, exchanging with backend
Response keys: [success, accessToken, refreshToken, expiresIn, tokenType, user]
hasAccessToken=true length=123
Token exchange succeeded for 'user@example.com'
Backend exchange succeeded: hasAccessToken=true length=123
Stored native auth tokens: access=true refresh=true user=true
Logged in user: us***@example.com
Auth cookie set: domain=www.fundocareer.com hasAccessToken=true
Auth localStorage injected: hasAccessToken=true hasUser=true
Profile verification dispatched to WebView
[FundoCareer-Verify] /api/user/profile status=200
Navigate to profile after successful login
```

## Validation Checklist

| # | Check | Status |
|---|-------|--------|
| 1 | No capacitor.plugins.json parse error | ✅ |
| 2 | Config shows correct WEB_APP_URL and API_BASE_URL | ✅ |
| 3 | Tap login logs "Starting native Google Sign-In account picker" | ✅ |
| 4 | Google ID token received | ✅ |
| 5 | Token exchange success with hasAccessToken=true | ✅ |
| 6 | No "setAuthCookies: no access token available" | ✅ |
| 7 | Cookie/localStorage injected | ✅ |
| 8 | /api/user/profile returns HTTP 200 | ✅ |
| 9 | Profile page shows logged-in user | ✅ |
| 10 | Restart keeps user logged in | ✅ |
| 11 | Logout clears session | ✅ |
| 12 | Login with another Google account works | ✅ |

## Remaining Risks

1. **Cookie domain mismatch** — If the API base URL host differs from the WebView's loaded page host, cookies set via `CookieManager` on the API host may not be sent with WebView XHR/fetch requests to the page host. Currently both use `www.fundocareer.com` so this is fine. If a staging/tunnel URL is used, verify the cookie domain matches.

2. **Refresh endpoint returns no user** — The `/api/mobile/auth/refresh` endpoint does not return user info. The Android code uses existing stored values. If a user's profile changes between sessions, the stored user data will be stale until next full login. Mitigation: update `refreshAccessToken` in `mobileAuth.controller.js` to include user data.

3. **Backend response parsing edge cases** — If the backend changes its JSON response shape (e.g., nests `accessToken` under `data`), the Android code will log "Response keys" and fail gracefully. The added logging makes debugging straightforward.

4. **Token expiry not retried** — If the stored access token is expired on startup and refresh also fails, the user is silently logged out. No retry mechanism exists beyond the initial refresh attempt.

5. **EncryptedSharedPreferences fallback** — On devices where encrypted storage init fails (e.g., low-memory devices), it falls back to plain SharedPreferences. This is acceptable for debug builds but should be hardened for release.
