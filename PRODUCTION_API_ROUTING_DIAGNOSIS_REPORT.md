# Production API Routing Diagnosis Report

## 1. Hosting Architecture — Actual Discovery

| Domain | Hosting Platform | What It Serves | Status |
|--------|-----------------|----------------|--------|
| `www.fundocareer.com` | Amazon S3 + CloudFront | Frontend SPA (Vite-built) | ✅ Working for frontend |
| `fundocareer.com` | Amazon S3 + CloudFront (redirects to www) | Frontend SPA | ✅ Working |
| `mainsite.fundocareer.com` | CloudFront → ??? (origin unreachable) | Should be backend | ❌ 504 Gateway Timeout |

### Evidence

**Frontend domain serves SPA from S3:**
```
$ curl -i https://www.fundocareer.com/api/does-not-exist
HTTP/1.1 200 OK
Content-Type: text/html
Server: AmazonS3
X-Cache: Error from cloudfront
→ Returns full SPA index.html (frontend fallback)
```

**Some API routes reach a backend:**
```
$ curl -i https://www.fundocareer.com/api/health
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8
X-Powered-By: Express
{"ok":true,"status":"healthy","module":"fundo-api","timestamp":"2026-05-21T17:50:31.066Z"}
→ Express is running behind CloudFront for some routes

$ curl -i https://www.fundocareer.com/api/user/profile
HTTP/1.1 401 Unauthorized
Content-Type: application/json; charset=utf-8
X-Powered-By: Express
{"success":false,"message":"Not authenticated"}
→ Express is definitely running
```

**Backend domain is dead:**
```
$ curl -i https://mainsite.fundocareer.com/api/auth/google
HTTP/1.1 504 Gateway Timeout
X-Cache: Error from cloudfront
→ CloudFront origin is unreachable (backend server is down)
```

### What the Backend Response Reveals

The `/api/health` response `{"ok":true,"status":"healthy","module":"fundo-api"}` does NOT match the code in `backend/server.js` which has `{"status":"healthy","service":"Resume Builder API","version":"2.0.0"}`. This means the **production backend is running a different version of the code** than what is in this repository.

Similarly, the `POST /api/auth/google` endpoint returns HTTP 400 with Express's built-in HTML error page — NOT the JSON errors from `auth.controller.js:exchangeAndroidIdToken`. This confirms the production backend does NOT have the `router.post('/google', exchangeAndroidIdToken)` route registered. It only has the old `GET /google` (Passport OAuth initiate) route.

## 2. Root Cause Analysis

### Root Cause #1: Production Backend is Outdated

The Express server running in production at `www.fundocareer.com` does NOT have:
- `POST /api/auth/google` → `exchangeAndroidIdToken` (missing from auth.routes.js)
- The updated response parsing with flat keys (accessToken, refreshToken, etc.)

Evidence: `/api/health` response shape differs from current `server.js` code.

### Root Cause #2: CloudFront Inconsistently Routes /api/*

CloudFront has a behavior for SOME `/api/*` paths but not all:
- `/api/health` → reaches Express ✅
- `/api/auth/google` → reaches Express (old version, returns HTML 400) ⚠️
- `/api/user/profile` → reaches Express ✅
- `/api/does-not-exist` → falls through to S3 SPA fallback ❌

This means CloudFront has partial API routing that doesn't cover all paths properly. The error from cloudfront (`X-Cache: Error from cloudfront`) on API routes suggests the origin is unstable.

### Root Cause #3: mainsite.fundocareer.com Backend is Down

The dedicated backend subdomain returns 504 Gateway Timeout. If this was the intended backend hosting, the server needs to be restarted/redeployed.

### Root Cause #4: vercel.json Has a Placeholder

```
C:\Users\Alexa\AndroidStudioProjects\FundoCareer\vercel.json
{"rewrites":[{"source":"/api/:path*","destination":"https://YOUR_BACKEND_DOMAIN/api/:path*"}]}
```

The `YOUR_BACKEND_DOMAIN` placeholder was never replaced. This config is unused since the frontend is on S3/CloudFront, not Vercel.

### Root Cause #5: Android App API_BASE_URL Points Only to Frontend Domain

`BuildConfig.API_BASE_URL = "https://www.fundocareer.com"` — this is the frontend domain. If the backend needs to be at a different URL (like `mainsite.fundocareer.com`), the Android app is configured wrong. But given that `mainsite.fundocareer.com` is down, using `www.fundocareer.com` was the correct fallback — it just doesn't have a properly configured API proxy.

## 3. Exact Endpoint Status Table

All tests run on 2026-05-21 from a machine outside the deployment network.

| Endpoint | Method | HTTP Status | Content-Type | Body Type | Backend Reachable |
|---|---|---|---|---|---|
| `www.fundocareer.com/` | GET | 200 | text/html | SPA HTML | N/A (frontend) |
| `www.fundocareer.com/api/health` | GET | 200 | application/json | JSON | ✅ (different code version) |
| `www.fundocareer.com/api/auth/google` | POST | 400 | text/html | Express HTML error | ⚠️ (old backend, missing route) |
| `www.fundocareer.com/api/user/profile` | GET | 401 | application/json | JSON | ✅ |
| `www.fundocareer.com/api/does-not-exist` | GET | 200 | text/html | SPA HTML | ❌ (S3 fallback) |
| `www.fundocareer.com/api/mobile/auth/google/android-token` | POST | 400 | text/html | Express HTML error | ⚠️ (old backend) |
| `mainsite.fundocareer.com/` | GET | 200 | text/html | SPA HTML | ❌ (CloudFront → S3) |
| `mainsite.fundocareer.com/health` | GET | 200 | text/html | SPA HTML | ❌ (CloudFront → S3) |
| `mainsite.fundocareer.com/api/auth/google` | POST | 504 | text/html | CloudFront timeout | ❌ (origin down) |

## 4. Which Files/Configs Are Responsible

| File | Role | Status |
|---|---|---|
| `backend/server.js` | Express server with all API routes | Needs redeployment |
| `backend/features/auth/routes/auth.routes.js` | Registers `POST /google` → `exchangeAndroidIdToken` | Correct in repo, not deployed |
| `backend/features/auth/controllers/auth.controller.js` | `exchangeAndroidIdToken` — returns flat JSON with tokens | Correct in repo, not deployed |
| `vercel.json` | Vercel rewrite config (placeholder never replaced) | Unused (frontend is on S3/CloudFront) |
| CloudFront distribution | Routes `/api/*` → backend origin | Partially configured, origin unstable |
| `mainsite.fundocareer.com` origin | Backend Express server | DOWN (504 Gateway Timeout) |

## 5. Required Fixes

### Fix A: Bring the Backend Express Server Online
- If the backend runs on a VPS/cloud server: restart the Node.js process, verify database connectivity
- If the backend runs in Docker: `docker compose up -d` on the server
- Ensure the latest code is deployed (run `git pull && npm install && npm start` or rebuild Docker)

### Fix B: Update CloudFront Behaviors
- Add a behavior for `/api/*` that routes to the backend ORIGIN (not mainsite.fundocareer.com if that returns SPA)
- Set origin to the actual backend server URL
- Cache policy: CachingDisabled
- Allowed HTTP methods: GET, HEAD, OPTIONS, PUT, POST, PATCH, DELETE
- Origin request policy: AllViewer (to forward Authorization headers)

### Fix C: Verify /api/auth/google Returns JSON After Deploy
After backend redeploy AND CloudFront fix:
```
curl -i -X POST https://www.fundocareer.com/api/auth/google \
  -H "Content-Type: application/json" -H "Accept: application/json" \
  -d '{"idToken":"test"}'
```
Expected: HTTP 401, Content-Type: application/json
`{"success":false,"code":"GOOGLE_TOKEN_INVALID","message":"Invalid Google ID token."}`

### Fix D: Add Missing API Routes
In `backend/server.js`, add:
- `GET /api/health` → health check endpoint
- Ensure `POST /api/auth/google` is mounted (currently missing from deployed version)

### Fix E: Ensure Express Always Returns JSON for /api/* Errors
Add to `backend/server.js` before any routes:
```javascript
// API error handling middleware — catches async errors in routes
app.use('/api', (err, req, res, next) => {
    console.error('[API Error]', err);
    res.status(err.status || 500).json({
        success: false,
        message: err.message || 'Internal server error'
    });
});
```

## 6. Files Changed in This Fix

| File | Change | Purpose |
|---|---|---|
| `backend/server.js` | Added `/api/health` endpoint at `GET /api/health` | Proves `/api/*` reaches backend |
| `backend/server.js` | Added JSON body-parsing error middleware | Catches malformed JSON before routes and returns JSON, not HTML |
| `backend/server.js` | Added `asyncHandler` wrapper | Catches rejected promises from async route handlers and forwards to error handler |
| `backend/server.js` | Updated global error handler | Always returns JSON even for API routes, includes path in response |
| `backend/server.js` | Updated API docs to include `POST /api/auth/google` | Documents the Android auth endpoint |
| `vercel.json` | Replaced placeholder with `www.fundocareer.com` | Makes Vercel rewrites functional if frontend is deployed there |

## 7. Deployment Instructions

To fix production, the backend must be redeployed with the latest code:

### Step 1: Deploy backend

```bash
# SSH into the backend server, then:
cd /path/to/backend
git pull origin main
npm install --legacy-peer-deps
npx prisma generate
npm start
# OR if using Docker:
docker compose up -d --build backend
```

### Step 2: Verify backend starts correctly

```bash
# Test directly on backend server (or via CloudFront/load balancer):
curl -i http://localhost:5000/api/health
# Expected: {"ok":true,"status":"healthy","module":"fundocareer-backend",...}
```

### Step 3: Fix CloudFront behavior for /api/*

In AWS CloudFront Console for the `www.fundocareer.com` distribution:

1. **Origins** → Add origin → Backend server URL (or ALB/load balancer URL)
2. **Behaviors** → Create behavior:
   - Path pattern: `/api/*`
   - Origin: select the backend origin
   - Viewer protocol: HTTPS only
   - Allowed HTTP methods: GET, HEAD, OPTIONS, PUT, POST, PATCH, DELETE
   - Cache policy: CachingDisabled
   - Origin request policy: AllViewer (forward all headers including Authorization)
3. **Save** and wait for deployment (~5 min)

### Step 4: Verify public endpoints

```bash
# All /api/* endpoints must return JSON:
curl -i https://www.fundocareer.com/api/health
# → HTTP 200, Content-Type: application/json

curl -i -X POST https://www.fundocareer.com/api/auth/google \
  -H "Content-Type: application/json" \
  -d '{"idToken":"test"}'
# → HTTP 401, Content-Type: application/json
# → {"success":false,"code":"GOOGLE_TOKEN_INVALID",...}

curl -i https://www.fundocareer.com/api/user/profile
# → HTTP 401, Content-Type: application/json

curl -i https://www.fundocareer.com/api/does-not-exist
# → MUST return JSON, NOT SPA HTML
# → {"success":false,"message":"API endpoint not found"}
```

### Step 5: Test Android login

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am force-stop com.fundocareer.app
adb shell monkey -p com.fundocareer.app 1
adb logcat | grep -E "FundoCareer|Auth|Google|api/auth|HTML|JSON|profile|accessToken|401|200"
```

## 8. Before/After Endpoint Status (Expected)

| Endpoint | Before (Current) | After (After Deploy + CloudFront Fix) |
|---|---|---|
| `www.fundocareer.com/api/health` | JSON 200 (old `fundo-api` backend) | JSON 200 (`fundocareer-backend`) |
| `www.fundocareer.com/api/auth/google` POST | HTML 400 (Express old route) | JSON 401 (`GOOGLE_TOKEN_INVALID`) |
| `www.fundocareer.com/api/user/profile` | JSON 401 ✅ | JSON 401 ✅ (no change needed) |
| `www.fundocareer.com/api/does-not-exist` | HTML 200 (SPA fallback) | JSON 404 (`API endpoint not found`) |
| `mainsite.fundocareer.com` | 504 Gateway Timeout | Fix or decommission |

## 9. Final Acceptance Checklist

| Check | Current Status | Required |
|---|---|---|
| `POST /api/auth/google` returns JSON not HTML | ❌ HTML 400 | ✅ JSON |
| `GET /api/health` returns JSON | ✅ JSON 200 | ✅ JSON |
| `GET /api/user/profile` returns JSON 401 | ✅ JSON 401 | ✅ JSON 401 |
| `GET /api/does-not-exist` returns JSON 404 | ❌ HTML 200 | ✅ JSON 404 |
| Android POST to `/api/auth/google` receives JSON | ❌ receives HTML | ✅ receives JSON |
| Android logs `hasAccessToken=true` | ❌ | ✅ |
| Android/WebView profile is logged in | ❌ | ✅ |
| No token exchange response contains `<!doctype html>` | ❌ contains HTML | ✅ no HTML |

## 10. What Blocks Acceptance

The fix requires **production deployment actions** that cannot be done from this development environment:

1. **Backend code redeployment** — The updated `server.js` and `auth.routes.js` must be deployed to the production server
2. **CloudFront behavior configuration** — AWS Console access needed to add `/api/*` → backend behavior
3. **Backend server uptime** — `mainsite.fundocareer.com` returns 504, need to bring the server online

Without these, the Android app will continue to receive frontend HTML when POSTing to `/api/auth/google`, regardless of any Android code changes.
