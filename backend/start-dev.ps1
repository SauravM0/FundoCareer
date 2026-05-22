param(
    [switch]$SkipFrontendCheck
)

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  FundoCareer Local Dev Server Launcher" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# ---- Check prerequisites ----
$nodeVer = node --version 2>$null
if (-not $nodeVer) {
    Write-Host "ERROR: Node.js is not installed or not in PATH." -ForegroundColor Red
    exit 1
}
Write-Host "Node.js: $nodeVer" -ForegroundColor Green

# ---- Check npm dependencies ----
if (-not (Test-Path "node_modules/.package-lock.json")) {
    Write-Host "node_modules missing or incomplete. Running npm install..." -ForegroundColor Yellow
    $env:PUPPETEER_SKIP_DOWNLOAD = "true"
    npm install
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: npm install failed." -ForegroundColor Red
        exit 1
    }
}

# ---- Check Prisma client ----
if (-not (Test-Path "node_modules/.prisma/client")) {
    Write-Host "Prisma client not generated. Running prisma generate..." -ForegroundColor Yellow
    npx prisma generate
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: prisma generate failed." -ForegroundColor Red
        exit 1
    }
}

# ---- Check database ----
Write-Host "Checking database connection..." -ForegroundColor Yellow
npx prisma db push --skip-generate 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "WARNING: Database push failed. Check your DATABASE_URL in .env" -ForegroundColor Yellow
    Write-Host "  Current DATABASE_URL: $((Get-Content .env | Select-String 'DATABASE_URL').Line)" -ForegroundColor Yellow
    Write-Host "  Make sure MySQL is running and the database exists." -ForegroundColor Yellow
    Write-Host "  Use: docker-compose up -d  (if using Docker)" -ForegroundColor Yellow
    Write-Host ""
}

# ---- Check frontend dev server (optional) ----
if (-not $SkipFrontendCheck) {
    try {
        $fe = Invoke-WebRequest -Uri "http://localhost:5173" -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop
        Write-Host "Frontend (Vite): Running on http://localhost:5173" -ForegroundColor Green
    } catch {
        Write-Host ""
        Write-Host "WARNING: Frontend Vite dev server not detected on port 5173." -ForegroundColor Yellow
        Write-Host "  The Android WebView loads from http://10.0.2.2:5173 (debug) or https://www.fundocareer.com (release)." -ForegroundColor Yellow
        Write-Host ""
        Write-Host "  To fix:" -ForegroundColor Yellow
        Write-Host "  1. Start Vite dev server in the frontend project:" -ForegroundColor Yellow
        Write-Host "     cd ../frontend && npm run dev" -ForegroundColor White
        Write-Host "  2. OR use 'adb reverse' to forward the emulator port:" -ForegroundColor Yellow
        Write-Host "     adb reverse tcp:5173 tcp:5173" -ForegroundColor White
        Write-Host "  3. OR build the app in RELEASE mode to use production URL" -ForegroundColor Yellow
        Write-Host ""
    }
}

# ---- Start backend ----
Write-Host ""
Write-Host "Starting backend on http://localhost:5000 ..." -ForegroundColor Green
Write-Host "Press Ctrl+C to stop." -ForegroundColor Gray
Write-Host ""
node server.js
