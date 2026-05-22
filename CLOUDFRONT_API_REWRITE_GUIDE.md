# CloudFront API Rewrite Configuration Guide

## Problem
www.fundocareer.com serves the frontend SPA from an S3 bucket. 
API calls to www.fundocareer.com/api/* return index.html (SPA fallback) 
instead of backend JSON.

## Fix: CloudFront Distribution Behavior

If www.fundocareer.com uses CloudFront in front of the S3 bucket:

### Step 1: Add an Origin
1. Open CloudFront Console → Distributions → select the www.fundocareer.com distribution
2. Go to **Origins** tab → **Create Origin**
3. Origin domain: `mainsite.fundocareer.com` (or the actual backend load balancer URL)
4. Protocol: HTTPS only
5. Create

### Step 2: Add a Behavior for /api/*
1. Go to **Behaviors** tab → **Create Behavior**
2. Path pattern: `/api/*`
3. Origin: select the backend origin created above
4. Viewer protocol policy: HTTPS only
5. Allowed HTTP methods: GET, HEAD, OPTIONS, PUT, POST, PATCH, DELETE
6. Cache policy: CachingDisabled
7. Origin request policy: AllViewer (to forward Authorization headers)
8. Response headers policy: SimpleCORS (or create one that allows credentials)
9. Create behavior

### Step 3: Verify
```bash
curl -i -X POST https://www.fundocareer.com/api/mobile/auth/google/android-token \
  -H "Content-Type: application/json" \
  -d '{"idToken":"test"}'
```
Expected: JSON response with error (not HTML)

## Alternative: Deploy Backend at a Subdomain

Instead of rewriting /api/* on the frontend domain, deploy the Express backend
as a separate service and change the Android app's API_BASE_URL.

Current Android build.gradle.kts values:
```kotlin
val debugApiUrl = System.getenv("FUNDO_DEBUG_API_URL") ?: "https://www.fundocareer.com"
val debugWebUrl = System.getenv("FUNDO_DEBUG_WEB_URL") ?: "https://www.fundocareer.com"
```

Set FUNDO_DEBUG_API_URL to the backend URL, e.g.:
```
FUNDO_DEBUG_API_URL=https://mainsite.fundocareer.com
FUNDO_DEBUG_WEB_URL=https://www.fundocareer.com
```

## Test Backend Health
```bash
curl -i https://mainsite.fundocareer.com/health
curl -i -X POST https://mainsite.fundocareer.com/api/mobile/auth/google/android-token \
  -H "Content-Type: application/json" \
  -d '{"idToken":"test"}'
```
