# FundoCareer - Android Hybrid App Context

## Project
FundoCareer is a Capacitor-based Android WebView app (Java) with a Node.js/Express backend. The app wraps `https://www.fundocareer.com` in a WebView with custom bottom navigation, native Google Sign-In, file bridges, and microphone support.

## Key Architecture
- **App**: Capacitor `BridgeActivity` subclass with custom `@JavascriptInterface` bridges
- **Auth**: Native Google Sign-In → backend token exchange → cookie + localStorage injection into WebView
- **Backend**: Express + Prisma + MySQL, JWT auth with Bearer token and cookie support

## Recent Fixes (May 2026)
### Mobile Login Bug Fixes
1. **capacitor.plugins.json** - Created missing file, fixed `PluginLoadException`
2. **Environment URLs** - Debug build now reads `FUNDO_DEBUG_API_URL` and `FUNDO_DEBUG_WEB_URL` env vars
3. **WebView OAuth interception** - `/api/auth/google` and `accounts.google.com` URLs are intercepted before WebView loads them; native Google Sign-In triggered instead
4. **Storage clearing bug** - Removed `clearWebViewStorage()` from login success handler (was wiping localStorage right after auth)
5. **Auth cookie injection** - `setAuthCookies()` sets JWT as cookie for API domain so backend auth middleware can read it
6. **Auth state injection** - `injectAuthState()` now sets `fundocareer_access_token`, `fundocareer_refresh_token`, `fundocareer_user` in localStorage + dispatches `fundocareer:auth-updated` event
7. **Fetch interceptor** - Injected JS intercepts `window.fetch` to add `Authorization: Bearer <token>` to API calls
8. **Session persistence** - `restoreActiveTab()` now attempts token refresh on startup if session expired
9. **Login debouncing** - Added `isGoogleLoginInProgress` flag to prevent double native login triggers
10. **Backend endpoint** - Added `GET /api/user/profile` alias for WebView frontend

## Build
```powershell
# Debug build (production URLs)
./gradlew assembleDebug

# Debug build with local backend
$env:FUNDO_DEBUG_API_URL="http://10.0.2.2:5000"
$env:FUNDO_DEBUG_WEB_URL="https://www.fundocareer.com"
./gradlew assembleDebug
```

## Test Steps
1. Install APK on device/emulator
2. Open app → home page loads
3. Tap Google login → native picker opens (NOT WebView OAuth)
4. Select account → native token exchange → `/profile` loads
5. `/api/user/profile` returns 200 (check via WebView console or backend logs)
6. Close & reopen app → user stays logged in
7. Logout → user fully cleared, home page shown
8. Login with different Google account → new user data shown

## Common Issues
- Missing `capacitor.plugins.json`: re-run `npx cap sync` after adding new Capacitor plugins
- WebView shows Google OAuth page: ensure `handleNavigation` intercepts `/api/auth/google` and `accounts.google.com`
- Profile returns 401 after login: check that `setAuthCookies()` ran and fetch interceptor is injecting Bearer token
- Login clears immediately: verify `clearWebViewStorage()` is NOT called in the login success path
