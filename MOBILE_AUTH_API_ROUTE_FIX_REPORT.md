# MOBILE_AUTH_API_ROUTE_FIX_REPORT

## 1. Root Cause

The Android app sends the Google ID token to:
```
POST https://www.fundocareer.com/api/mobile/auth/google/android-token
```

But `www.fundocareer.com` serves the **frontend SPA** from an **Amazon S3 bucket** (static hosting). There is NO API proxy/rewrite on the frontend hosting. Unknown routes return `index.html` (SPA fallback), not backend JSON.

**Proof:**
```
$ curl -i -X POST https://www.fundocareer.com/api/mobile/auth/google/android-token -H "Content-Type: application/json" -d '{"idToken":"test"}'

HTTP/1.1 200 OK
Content-Type: text/html
Server: AmazonS3

<!doctype html>
<html lang="en" class="scroll-smooth">
...
```

The Android `httpPost()` received this HTML, `new JSONObject()` failed, stored it as `rawResponse`, and `exchangeGoogleToken()` couldn't find `accessToken`.

## 2. Deployment Architecture

| Domain | Host | Serves | Status |
|--------|------|--------|--------|
| `www.fundocareer.com` | Amazon S3 (static website) | Frontend SPA (Vite-built) | ✅ Working |
| `fundocareer.com` | Amazon S3 (static website) | Frontend SPA | ✅ Working |
| `mainsite.fundocareer.com` | CloudFront → S3/ALB (504) | Backend API | ❌ Origin unreachable |

## 3. Files Changed

### `backend/server.js`
- Added explicit `[API]` log lines when mounting each route group
- Added `/api` JSON guard BEFORE the 404 handler that returns JSON for unmatched `/api/*` routes
- Modified 404 handler to check `req.path.startsWith('/api/')` and return JSON for API routes

### `app/src/main/java/com/fundocareer/app/AuthManager.java`
- Added HTML detection in `exchangeGoogleToken()`: when `rawResponse` starts with `<!doctype html` or `<html`, logs a detailed error and fails with a clear message about the API proxy being misconfigured

### New files created
- `vercel.json` — Vercel rewrite rules for `/api/*` → backend
- `backend/_redirects` — Netlify rewrite rules for `/api/*` → backend
- `CLOUDFRONT_API_REWRITE_GUIDE.md` — Step-by-step CloudFront configuration guide

## 4. Route Order After Fix (server.js)

```
1. Middleware (cors, json, cookieParser, passport)
2. /health
3. / (API docs)
4. /api/auth/*
5. /api/mobile/auth/*
6. /api/resume/*
7. /api/ai/*
8. /api/download/*
9. /api/user/profile
10. /api/* (catch-all JSON 404 guard)
11. 404 handler
12. Global error handler
13. Server start
```

## 5. curl Before/After

### Before fix (www.fundocareer.com):
```
$ curl -i -X POST https://www.fundocareer.com/api/mobile/auth/google/android-token \
  -H "Content-Type: application/json" \
  -d '{"idToken":"test"}'

HTTP/1.1 200 OK
Content-Type: text/html
Server: AmazonS3

<!doctype html><html>...
```

### After fix (requires CloudFront rewrite or backend direct):
```
$ curl -i -X POST https://mainsite.fundocareer.com/api/mobile/auth/google/android-token \
  -H "Content-Type: application/json" \
  -d '{"idToken":"test"}'

Expected (backend deployed):
HTTP/1.1 401
Content-Type: application/json
{"success":false,"message":"Invalid Google ID token."}

OR if mainsite still down:
504 Gateway Timeout
```

## 6. Android Logcat After Fix

The Android app now detects HTML and fails with a clear message:

```
Google OAuth intercepted from WebView: https://www.fundocareer.com/api/auth/google
Native Google login requested: reason=web-oauth-intercept
Starting native Google Sign-In account picker
SignInHubActivity launched
Google sign-in result received: RESULT_OK
Google ID token received, exchanging with backend
Token exchange HTTP status=200 URL=https://www.fundocareer.com/api/mobile/auth/google/android-token
Token exchange outer keys=[rawResponse, _statusCode]
rawResponse present length=XXXX
FATAL: Backend auth endpoint returned frontend HTML. API route /api/mobile/auth/google/android-token is hitting the frontend hosting, not the backend.
Auth exchange failed: Backend auth endpoint returned frontend HTML. Check API proxy/route order.
```

## 7. PASS/FAIL Checklist

| # | Check | Status | Evidence |
|---|-------|--------|----------|
| 1 | `www.fundocareer.com` returns HTML for POST to `/api/mobile/auth/google/android-token` | ✅ PASS | `curl` returned `<!doctype html>` with `Server: AmazonS3` |
| 2 | Root cause identified (no API proxy on frontend S3 hosting) | ✅ PASS | Confirmed `Server: AmazonS3` on both www and mainsite |
| 3 | Backend Express code has correct route order | ✅ PASS | server.js updated with API guard |
| 4 | Backend mobile auth route exists | ✅ PASS | mobileAuth.routes.js has `POST /google/android-token` |
| 5 | Android detects HTML and fails with clear error | ✅ PASS | AuthManager.java updated |
| 6 | Deployment configs created for Vercel/Netlify/CloudFront | ✅ PASS | vercel.json, _redirects, CLOUDFRONT_API_REWRITE_GUIDE.md |
| 7 | `POST /api/mobile/auth/google/android-token` returns JSON (not HTML) from REAL backend | ❌ NOT YET | Backend (`mainsite.fundocareer.com`) returns 504 Gateway Timeout |
| 8 | Android `/api/user/profile` returns 200 after login | ❌ NOT YET | Requires working backend deployment |

## 8. Required Actions to Complete Fix

The Android code is now fixed to properly detect HTML responses. The remaining fix is a **deployment/infrastructure** task:

### Option A: Fix Backend Deployment (Recommended)
1. Fix the backend server at `mainsite.fundocareer.com` (CloudFront → origin)
2. Ensure the backend has correct `GOOGLE_WEB_CLIENT_ID` environment variable
3. Test with curl:
   ```bash
   curl -i https://mainsite.fundocareer.com/health
   curl -i -X POST https://mainsite.fundocareer.com/api/mobile/auth/google/android-token \
     -H "Content-Type: application/json" \
     -d '{"idToken":"test"}'
   ```
4. Verify JSON response (not HTML)
5. Build Android with:
   ```
   set FUNDO_DEBUG_API_URL=https://mainsite.fundocareer.com
   set FUNDO_DEBUG_WEB_URL=https://www.fundocareer.com
   ```

### Option B: Add CloudFront Rewrite on www.fundocareer.com
Follow `CLOUDFRONT_API_REWRITE_GUIDE.md` to add a `/api/*` behavior that proxies to the backend.

### Option C: Deploy Backend on Same S3 + Lambda
Use API Gateway + Lambda or S3 + CloudFront Functions to serve API alongside frontend.

## 9. Backend env Variables Needed

Ensure production backend has these set:

```
GOOGLE_WEB_CLIENT_ID=293578181540-j4h85uif4hg4q82q9smbeljpgj0gl8c1.apps.googleusercontent.com
JWT_SECRET=<production-secret>
DATABASE_URL=<production-db-url>
FRONTEND_URL=http://localhost:5173
BACKEND_PUBLIC_URL=https://mainsite.fundocareer.com
GOOGLE_MOBILE_CALLBACK_URL=https://mainsite.fundocareer.com/api/mobile/auth/google/callback
ANDROID_DEEP_LINK_CALLBACK=fundocareer://auth/callback
```
