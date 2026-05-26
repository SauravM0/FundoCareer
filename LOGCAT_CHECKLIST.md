# Logcat Verification Checklist

## Filter: `FC.`

### 1. Scheduler Lifecycle (`FC.Scheduler`)
- [x] `startSchedulerAfterSave` — preferenceId, intervalMinutes, runImmediately
- [x] `scheduleNextRun` — preferenceId, interval, delay
- [x] `Work enqueued` — uniqueWorkName, delay
- [x] `Scheduler state updated` — nextRunAt
- [x] `Immediate work enqueued` — reason, name
- [x] `cancelScheduler` — preferenceId
- [x] `Work cancelled` — preferenceId
- [x] `reconcileOnAppStart` — with miss/catch-up/future details
- [x] `reconcileAfterBoot` — preferenceId, reason
- [x] `Preference loaded` — hasActive, hasSchedulerState
- [x] `onLogin` / `onLogout` — masked email
- [x] `loadData failed` — exception stack trace
- [x] `savePreference` flow — validate → saveLocal → backend → setupEmail

### 2. Worker Execution (`FC.Worker`)
- [x] `doWorkStarted` / `doWorkFinished` — retryable flag
- [x] `Worker started` — triggerReason, inputPreferenceId
- [x] `Token expired` / `No refresh token` — masked email
- [x] `Active preferences loaded` — count
- [x] `Processing preference` — preferenceId, role, triggerReason
- [x] `Fetch started` / `Fetch completed` — jobs count, error
- [x] `Search failed` — errorCode
- [x] `No jobs found` / `All jobs already in local DB`
- [x] `Email request sent` — newJobs count
- [x] `Email response received` — success, emailSent, errorCode
- [x] `Email send failed` — error details
- [x] `SUCCESS_EMAIL_SENT` — newJobs, totalFound

### 3. LinkedIn Job Source (`FC.Worker`)
- [x] `LinkedIn search started` — role, location, experience
- [x] `LinkedIn search complete` — totalJobs
- [x] `LinkedIn fetch response` — httpCode, bodyLength
- [x] `LinkedIn rate limited (HTTP 429)`
- [x] `LinkedIn block/redirect page detected`

### 4. Active Device (`FC.ActiveDevice`)
- [x] `Backend connectivity state` — success, currentDevice, hasRemote
- [x] `getActiveDevice failed` — error message
- [x] `Pending activation succeeded/failed` — preferenceId
- [x] `Pending deactivation succeeded/failed` — preferenceId
- [x] `activateDevice failed during save` — error
- [x] `deactivateDevice failed` during stop
- [x] `takeoverDevice failed` — exception

### 5. Network / API (`FC.Network`)
- [x] `>>> endpoint` — API call start with masked params
- [x] `<<< endpoint OK` — API success with result params
- [x] `<<< endpoint FAIL` — errorCode, details
- [x] `POST/GET path` — httpCode, requestId, body preview
- [x] `API Base URL` — routing mode detection
- [x] `requestId` — present in every network log

### 6. Email (`FC.Email`)
- [x] `sendSetupConfirmationEmailForPreference` — preferenceId, role
- [x] `Setup email confirmed sent` — preferenceId
- [x] `Setup email NOT sent` — errorCode, error
- [x] `Send failed` — exception chain
- [x] `Notification shown` — count, preferenceName

### 7. Reliability (`FC.Reliability`)
- [x] `getReliabilityStatus` — manufacturer, overall status
- [x] `markReliabilityCompleted` — manufacturer
- [x] Each checklist item — enabled/confirmed/status
- [x] `startSaveWithReliabilityCheck` — canActivate, overall
- [x] `Reliability complete, proceeding with save`
- [x] `Reliability still incomplete after continue`

### 8. Permission Actions (`FC.Permission`)
- [x] `POST_NOTIFICATIONS result` — granted boolean
- [x] `Action: open settings` — type, label
- [x] `Action: request permission` — type, permission
- [x] `Notification channel created` — channelId
- [x] `Unable to open reliability settings` — error

### 9. History (`FC.History`)
- [x] `History loaded` — event count (when implemented)
- [x] `History empty` — no events found
- [x] `History error` — backend failure

### 10. App Lifecycle (`FC.App`)
- [x] `Scheduler reconciled after startup delay`
- [x] `Scheduler reconcile failed` — error
- [x] `JobsPageActivity created`
- [x] `Tab changed` — tab name

---

## Privacy Checklist
- [x] NO full email addresses in logs (masked to `a***@domain.com`)
- [x] NO auth tokens in logs (truncated to `abcd...wxyz`)
- [x] NO device IDs in logs (truncated to `abcd...wxyz`)
- [x] NO passwords or secrets in logs
- [x] NO full URLs containing tokens

## Debug vs Release
- [x] `FcLog.d()` calls are suppressed in release builds (gated on `BuildConfig.DEBUG`)
- [x] `FcLog.i/w/e()` calls produce output in both debug and release
- [x] No sensitive data in `i/w/e` level logs (data sanitized via `sanitizeData`)

## Request ID Trail
- [x] Every API call includes `requestId` in the log data
- [x] Failed API calls include `requestId` for backend correlation
- [x] Backend `X-Request-Id` header is captured and logged via `getLastRequestId()`
