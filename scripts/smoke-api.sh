#!/usr/bin/env bash
# =============================================================================
# FundoCareer API Smoke Test
# =============================================================================
# Tests all critical API endpoints and reports pass/fail status.
# Run: bash scripts/smoke-api.sh
# =============================================================================

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass=0
fail=0

BASE_URL="${1:-https://www.fundocareer.com}"
API_BASE="${BASE_URL}/api"

check() {
    local label="$1"
    local method="$2"
    local url="$3"
    local expected_status="$4"
    local expect_json="$5"
    local extra_args="${6:-}"

    printf "${YELLOW}[TEST]${NC} %-50s " "${label}"

    local response
    local http_code
    local content_type

    if [ "$method" = "GET" ]; then
        response=$(curl -s -i -o /tmp/smoke-response.txt -w "%{http_code}" $extra_args "$url" 2>/dev/null)
    else
        response=$(curl -s -i -o /tmp/smoke-response.txt -w "%{http_code}" -X "$method" $extra_args "$url" 2>/dev/null)
    fi

    http_code="$response"
    content_type=$(head -1 /tmp/smoke-response.txt | grep -i content-type | tr -d '\r' | awk '{print $2}')

    if [ "$http_code" -ne "$expected_status" ]; then
        echo -e "${RED}FAIL${NC} (expected HTTP ${expected_status}, got ${http_code})"
        fail=$((fail + 1))
        return
    fi

    if [ "$expect_json" = "yes" ]; then
        local body
        body=$(tail -n +6 /tmp/smoke-response.txt 2>/dev/null || cat /tmp/smoke-response.txt)
        if ! echo "$body" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
            if ! echo "$body" | python -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
                echo -e "${RED}FAIL${NC} (expected JSON body, got non-JSON)"
                fail=$((fail + 1))
                return
            fi
        fi
    fi

    echo -e "${GREEN}PASS${NC} (HTTP ${http_code})"
    pass=$((pass + 1))
}

echo ""
echo "============================================================"
echo "  FundoCareer API Smoke Test"
echo "  Base URL: ${BASE_URL}"
echo "  $(date)"
echo "============================================================"
echo ""

# -------------------------------------------------------------------------
# Health
# -------------------------------------------------------------------------
check "GET /api/health" GET "${API_BASE}/health" 200 "yes"

# -------------------------------------------------------------------------
# Auth — without token
# -------------------------------------------------------------------------
check "GET /api/auth/me (no auth)" GET "${API_BASE}/auth/me" 401 "yes"
check "GET /api/user/profile (no auth)" GET "${API_BASE}/user/profile" 401 "yes"

# -------------------------------------------------------------------------
# Auth — POST with missing body (should get 400)
# -------------------------------------------------------------------------
check "POST /api/auth/google (empty body)" POST "${API_BASE}/auth/google" 400 "yes" "-H 'Content-Type: application/json' -d '{}'"
check "POST /api/auth/login (empty body)" POST "${API_BASE}/auth/login" 400 "yes" "-H 'Content-Type: application/json' -d '{}'"

# -------------------------------------------------------------------------
# Non-existent API route (must return JSON 404, NOT HTML)
# -------------------------------------------------------------------------
check "GET /api/does-not-exist" GET "${API_BASE}/does-not-exist" 404 "yes"

# -------------------------------------------------------------------------
# api.fundocareer.com tests (when configured)
# -------------------------------------------------------------------------
if [ -n "${ALT_BASE:-}" ]; then
    echo ""
    echo "--- Alternate origin: ${ALT_BASE} ---"
    ALT_API="${ALT_BASE}/api"
    check "ALT GET /api/health" GET "${ALT_API}/health" 200 "yes"
    check "ALT GET /api/auth/me (no auth)" GET "${ALT_API}/auth/me" 401 "yes"
    check "ALT GET /api/does-not-exist" GET "${ALT_API}/does-not-exist" 404 "yes"
fi

# -------------------------------------------------------------------------
# Summary
# -------------------------------------------------------------------------
echo ""
echo "============================================================"
echo "  Results: ${pass} passed, ${fail} failed"
echo "============================================================"
echo ""

if [ "$fail" -gt 0 ]; then
    exit 1
fi
exit 0
