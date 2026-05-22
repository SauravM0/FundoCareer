# Permanent API Routing & Login Fix — Report

## Architecture (Target)

```
┌──────────────┐      ┌───────────────┐      ┌─────────────────┐
│   Android    │ ───> │ api.fundocareer│ ──> │ DigitalOcean VPS│
│   App        │      │ .com          │      │ 161.35.203.36   │
└──────────────┘      └───────────────┘      │ Express :3000    │
                                             │ PM2 managed     │
┌──────────────┐      ┌───────────────┐      └─────────────────┘
│   Browser    │ ───> │ www.fundocareer│ ──> Amazon S3 (SPA)
│   (SPA)      │      │ .com          │
└──────────────┘      └───────┬───────┘
                              │
                     ┌────────┴────────┐
                     │  CloudFront     │
                     │  /api/* → api.  │  (compat proxy)
                     └─────────────────┘
```

- **Frontend**: Amazon S3 + CloudFront at `www.fundocareer.com` — serves Vite-built SPA
- **Backend**: Express on DigitalOcean VPS (`161.35.203.36:3000`) — managed by PM2 behind nginx
- **API domain**: `api.fundocareer.com` → CNAME → `161.35.203.36` (dedicated origin)
- **Compatibility**: `www.fundocareer.com/api/*` → CloudFront behavior → same backend (optional, for SPA)

---

## Current State (Before)

| Endpoint | HTTP | Content-Type | Body | Notes |
|---|---|---|---|---|
| `www.fundocareer.com/api/health` | 200 | JSON | `{"module":"fundo-api"}` | Old backend code |
| `www.fundocareer.com/api/auth/google` POST | 400 | HTML | Express HTML error | Missing route in old code |
| `www.fundocareer.com/api/user/profile` | 401 | JSON | `{"success":false,"message":"Not authenticated"}` | ✅ Working |
| `www.fundocareer.com/api/does-not-exist` | 200 | HTML | SPA index.html | ❌ S3 fallback, not JSON 404 |
| `mainsite.fundocareer.com` | 504 | HTML | CloudFront timeout | ❌ Origin down |
| `161.35.203.36:3000/api/health` | 200 | JSON | `{"module":"fundo-api"}` | Old backend code running |

---

## Target State (After Deployment)

| Endpoint | HTTP | Content-Type | Body |
|---|---|---|---|
| `api.fundocareer.com/api/health` | 200 | JSON | `{"module":"fundocareer-backend"}` |
| `api.fundocareer.com/api/auth/google` POST | 400/401 | JSON | `{"success":false,"code":"GOOGLE_TOKEN_INVALID",...}` |
| `api.fundocareer.com/api/user/profile` | 401 | JSON | `{"success":false,"message":"Not authenticated"}` |
| `api.fundocareer.com/api/does-not-exist` | 404 | JSON | `{"success":false,"message":"API endpoint not found"}` |
| `www.fundocareer.com/api/*` | varies | JSON | Same as `api.fundocareer.com` (via CloudFront compat proxy) |

---

## Root Causes

1. **Production backend is outdated** — The Express server running on the VPS (`module: "fundo-api"`) predates the `POST /auth/google` route and the improved error handling. The repo code (`module: "fundocareer-backend"`) has the correct routes.

2. **CloudFront inconsistent `/api/*` routing** — Some paths reach the VPS backend, others fall through to S3 SPA fallback. No unified behavior for all `/api/*` paths.

3. **`mainsite.fundocareer.com` unreachable** — 504 Gateway Timeout; the dedicated backend subdomain is dead.

4. **No dedicated API subdomain** — `api.fundocareer.com` does not exist yet as a DNS record.

---

## Deployment Runbook

### Phase 1: Deploy New Backend Code (requires VPS access)

```bash
# SSH into VPS
ssh root@161.35.203.36

# Navigate to repo
cd /root/FundoCareer/backend

# Pull latest code
git pull origin main

# Install dependencies
npm install

# Generate Prisma client
npx prisma generate

# Update .env if needed
# Ensure DATABASE_URL, JWT_SECRET, GOOGLE_WEB_CLIENT_ID are set correctly

# Restart PM2
pm2 restart fundo-api --update-env

# Verify
curl http://localhost:3000/api/health
# Expected: {"ok":true,"module":"fundocareer-backend",...}
```

### Phase 2: DNS — Create `api.fundocareer.com`

In your DNS provider (Namecheap, Cloudflare, or wherever `fundocareer.com` is managed):

| Type | Name | Value | TTL |
|---|---|---|---|
| CNAME | api | `161.35.203.36` | 300 |

Or if using a load balancer / CloudFront:
| Type | Name | Value | TTL |
|---|---|---|---|
| CNAME | api | `<cloudfront-distribution>.cloudfront.net` | 300 |

**Note**: If using CloudFront for `api.fundocareer.com`, create a new CloudFront distribution with origin `161.35.203.36:3000` (HTTP only, since nginx on the VPS handles HTTPS). Set the origin protocol policy to "HTTP only" or set up HTTPS with a certificate on the VPS.

### Phase 3: Update Backend `.env`

On the VPS, in `/root/FundoCareer/backend/.env`:

```ini
BACKEND_PUBLIC_URL='https://api.fundocareer.com'
FRONTEND_PUBLIC_URL='https://www.fundocareer.com'
FRONTEND_URL='https://www.fundocareer.com'
GOOGLE_CALLBACK_URL='https://api.fundocareer.com/api/auth/google/callback'
GOOGLE_MOBILE_CALLBACK_URL='https://api.fundocareer.com/api/mobile/auth/google/callback'
```

### Phase 4: (Optional) CloudFront Compat Proxy at `www.fundocareer.com`

If the frontend SPA needs `/api/*` to work from `www.fundocareer.com`:

1. Go to AWS CloudFront Console → `www.fundocareer.com` distribution
2. **Origins** → Add origin:
   - Origin domain: `api.fundocareer.com` (or `161.35.203.36`)
   - Protocol: HTTPS only (or HTTP if not configured)
3. **Behaviors** → Create behavior:
   - Path pattern: `/api/*`
   - Origin: the new API origin
   - Viewer protocol: HTTPS only
   - Allowed methods: GET, HEAD, OPTIONS, PUT, POST, PATCH, DELETE
   - Cache policy: CachingDisabled
   - Origin request policy: AllViewer (forward all headers including `Authorization`)
   - Response headers policy: CORS-with-preflight (or create custom)
4. Save and wait for deployment (~5 min)

### Phase 5: Update Android `build.gradle.kts`

Change the release build type:

```kotlin
release {
    buildConfigField("String", "API_BASE_URL", "\"https://api.fundocareer.com\"")
    buildConfigField("String", "FRONTEND_URL", "\"https://www.fundocareer.com\"")
}
```

Then build release APK:
```bash
./gradlew assembleRelease
```

### Phase 6: Smoke Test

```bash
bash scripts/smoke-api.sh https://api.fundocareer.com
```

Expected output:
```
[TEST] GET /api/health ...................................... PASS (HTTP 200)
[TEST] GET /api/auth/me (no auth) .......................... PASS (HTTP 401)
[TEST] GET /api/user/profile (no auth) ..................... PASS (HTTP 401)
[TEST] POST /api/auth/google (empty body) .................. PASS (HTTP 400)
[TEST] POST /api/auth/login (empty body) ................... PASS (HTTP 400)
[TEST] GET /api/does-not-exist .............................. PASS (HTTP 404)
```

### Phase 7: End-to-End Login Test

```bash
# Install APK on device
adb install -r app/build/outputs/apk/release/app-release.apk

# Clear logs and launch
adb logcat -c
adb shell am force-stop com.fundocareer.app
adb shell monkey -p com.fundocareer.app 1

# Monitor auth flow
adb logcat | grep -E "FundoCareer|Auth|Google|api/|HTML|JSON|accessToken|401|200"
```

Expected log sequence:
```
POSTing to: https://api.fundocareer.com/api/auth/google
Token exchange HTTP status=200
hasAccessToken=true
Auth state injected into WebView
```

Must NOT see:
- `Response not valid JSON`
- `FATAL: Backend auth endpoint returned frontend HTML`
- Any HTML content in response body

---

## Files Changed in This Repo

| File | Change | Status |
|---|---|---|
| `app/src/main/java/com/fundocareer/app/AuthManager.java` | Fixed endpoint constants, removed `PATH_PREFIX`, uses `getApiBaseUrl()` + relative path | ✅ Deployed in APK |
| `backend/server.js` | Added `/api/health`, body-parser error middleware, `asyncHandler` wrapper, improved error handler | ✅ On VPS at `/root/FundoCareer/backend` (not yet running) |
| `backend/features/auth/routes/auth.routes.js` | Registers `POST /google` → `exchangeAndroidIdToken` | ✅ In repo (not yet running) |
| `backend/features/auth/controllers/auth.controller.js` | Returns flat JSON with `accessToken`, `refreshToken`, `email`, `name`, `userId` | ✅ In repo |
| `backend/features/auth/controllers/mobileAuth.controller.js` | Backward-compat `POST /mobile/auth/google/android-token` | ✅ In repo |
| `scripts/smoke-api.sh` | Smoke test script for all critical endpoints | ✅ This repo |
| `app/build.gradle.kts` | `API_BASE_URL` build config field for debug and release | 🔧 Needs update for release (`api.fundocareer.com`) |

---

## Remaining Work

| Task | Dependency | Who |
|---|---|---|
| Deploy backend code to VPS | SSH access to `161.35.203.36` | Developer |
| Create `api.fundocareer.com` DNS record | DNS provider access | DNS Admin |
| Update CloudFront behaviors at `www.fundocareer.com` | AWS Console access | DevOps / Developer |
| Update `backend/.env` on VPS | After deployment | Developer |
| Update `build.gradle.kts` release `API_BASE_URL` | After `api.fundocareer.com` is live | Developer |
| Build and sign release APK | After config update | Developer |
| Run smoke tests | After DNS/CloudFront propagate | QA / Developer |
| E2E login test on device | After APK install | QA / Developer |

---

## Rollback Plan

If the new backend causes issues:

```bash
# On VPS — switch back to old backend
cd /root/backend
pm2 restart fundo-api --update-env

# DNS — change api.fundocareer.com CNAME back to previous target
# CloudFront — revert /api/* behavior or point to old origin
```

---

## Acceptance Criteria

- [ ] `curl https://api.fundocareer.com/api/health` returns JSON 200 with `module: "fundocareer-backend"`
- [ ] `curl -X POST https://api.fundocareer.com/api/auth/google -H "Content-Type: application/json" -d '{"idToken":"test"}'` returns JSON 401 with `GOOGLE_TOKEN_INVALID`
- [ ] `curl https://api.fundocareer.com/api/does-not-exist` returns JSON 404, NOT HTML
- [ ] All `/api/*` endpoints consistently return `Content-Type: application/json`
- [ ] Android app successfully logs in via Google and receives `accessToken` in JSON response
- [ ] No endpoint returns `<!doctype html>` for any `/api/*` request
