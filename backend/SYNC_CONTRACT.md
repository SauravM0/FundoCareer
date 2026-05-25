# Sync Contract — FundoCareer Scheduler Coordination

This document defines the backend contract used by Android and Electron devices to coordinate job alert scheduler work without duplication.

---

## 1. Device Identity

Each device must generate a stable, persistent `deviceId`:

| Platform | Strategy |
|----------|----------|
| **Android** | `Settings.Secure.ANDROID_ID` (64-bit hex, stable per app signing key) |
| **Electron** | `machineIdSync()` from `node-machine-id`, or stable UUID stored in `electron-store` |

`deviceType` is always the platform literal: `"android"` or `"electron"`.

---

## 2. Endpoints

All scheduler endpoints require **Bearer JWT auth** via `Authorization` header. All responses follow:

```json
{ "success": true/false, "data": {...}, "message": "..." }
```

### 2.1 Acquire Lock

`POST /api/scheduler/acquire-lock`

**Request:**
```json
{
  "preferenceSetId": "uuid-of-preference-set",
  "deviceId": "stable-device-uuid",
  "deviceType": "android|electron",
  "appVersion": "2.0.0"
}
```

**Response (granted):**
```json
{
  "success": true,
  "data": {
    "granted": true,
    "lockId": 1,
    "acquiredAt": "2026-05-23T10:00:00.000Z",
    "expiresAt": "2026-05-23T10:10:00.000Z"
  }
}
```

**Response (denied):**
```json
{
  "success": true,
  "data": {
    "granted": false,
    "heldBy": "other-device-uuid",
    "heldByDeviceType": "electron",
    "acquiredAt": "2026-05-23T10:00:00.000Z",
    "expiresAt": "2026-05-23T10:10:00.000Z"
  }
}
```

**Lock TTL:** 10 minutes. If a device crashes without releasing, the lock auto-expires and another device can claim it.

### 2.2 Release Lock

`POST /api/scheduler/release-lock`

**Request:**
```json
{
  "preferenceSetId": "uuid-of-preference-set",
  "deviceId": "stable-device-uuid"
}
```

**Response (released):**
```json
{ "success": true, "data": { "released": true } }
```

**Response (not holder):**
```json
{ "success": true, "data": { "released": false, "reason": "not_lock_holder", "heldBy": "actual-holder-uuid" } }
```

**Response (no lock):**
```json
{ "success": true, "data": { "released": false, "reason": "no_lock" } }
```

### 2.3 Check Emailed Jobs

`POST /api/scheduler/check-emailed-jobs`

**Request:**
```json
{
  "fingerprints": ["fp-linkedin-abc123", "fp-indeed-def456"],
  "preferenceId": "uuid-of-preference"
}
```

**Response:**
```json
{
  "success": true,
  "data": {
    "alreadyEmailed": ["fp-linkedin-abc123"],
    "new": ["fp-indeed-def456"]
  }
}
```

### 2.4 Mark Emailed Jobs

`POST /api/scheduler/mark-emailed-jobs`

**Request:**
```json
{
  "fingerprints": ["fp-indeed-def456"],
  "preferenceId": "uuid-of-preference",
  "emailRecordId": "<optional-message-id>"
}
```

**Response:**
```json
{
  "success": true,
  "data": {
    "marked": 1,
    "total": 1,
    "skippedDuplicates": 0
  }
}
```

### 2.5 Sync Device State

`POST /api/scheduler/sync-state`

**Request:**
```json
{
  "deviceId": "stable-device-uuid",
  "deviceType": "android|electron",
  "appVersion": "2.0.0",
  "preferences": { "role": "Android Dev", "location": "Remote" },
  "schedulerState": { "enabled": true, "lastRunAt": 1712345678000 },
  "recentRuns": [
    {
      "preferenceId": "uuid",
      "runId": "uuid",
      "status": "SUCCESS_EMAIL_SENT",
      "jobsFound": 25,
      "jobsNew": 3,
      "startedAt": "2026-05-23T10:00:00.000Z",
      "completedAt": "2026-05-23T10:01:00.000Z",
      "triggeredBy": "scheduler"
    }
  ]
}
```

**Response:**
```json
{
  "success": true,
  "data": { "syncedAt": "2026-05-23T10:02:00.000Z" }
}
```

### 2.6 Get Device State

`GET /api/scheduler/state?deviceId=stable-device-uuid`

**Response:**
```json
{
  "success": true,
  "data": {
    "deviceState": {
      "deviceId": "stable-device-uuid",
      "deviceType": "android",
      "appVersion": "2.0.0",
      "lastSyncAt": "2026-05-23T10:02:00.000Z",
      "lastSeenAt": "2026-05-23T10:02:00.000Z",
      "preferences": { "role": "Android Dev" },
      "schedulerState": { "enabled": true }
    },
    "activeLocks": [
      {
        "preferenceSetId": "uuid",
        "heldBy": "other-device-uuid",
        "deviceType": "electron",
        "acquiredAt": "2026-05-23T10:00:00.000Z",
        "expiresAt": "2026-05-23T10:10:00.000Z"
      }
    ],
    "recentRuns": [
      {
        "runId": "uuid",
        "preferenceId": "uuid",
        "status": "SUCCESS_EMAIL_SENT",
        "jobsFound": 25,
        "jobsNew": 3,
        "startedAt": "2026-05-23T10:00:00.000Z",
        "completedAt": "2026-05-23T10:01:00.000Z"
      }
    ]
  }
}
```

### 2.7 History Summary

`POST /api/scheduler/history-summary`

**Request:**
```json
{ "preferenceId": "<optional-filter>" }
```

**Response:**
```json
{
  "success": true,
  "data": {
    "totalRuns": 42,
    "lastRunAt": "2026-05-23T10:01:00.000Z",
    "lastRunStatus": "SUCCESS_EMAIL_SENT",
    "totalJobsFound": 500,
    "totalJobsNew": 60,
    "totalEmailsSent": 15,
    "lastEmailSentAt": "2026-05-23T10:01:00.000Z"
  }
}
```

---

## 3. Device Priority Rule

The first device that acquires the backend lock runs the scheduler for that preference set. Other devices skip that cycle.

**Flow:**
```
1. Device A calls acquire-lock → granted: true
2. Device A fetches jobs, sends email, marks fingerprints
3. Device B calls acquire-lock → granted: false (heldBy: Device A)
4. Device B saves local run with status SKIPPED_LOCK_HELD_BY_OTHER_DEVICE
5. Device A calls release-lock
6. (10 min later, if A crashes, lock auto-expires)
7. Device B calls acquire-lock → granted: true (lock expired)
```

---

## 4. Duplicate Ledger Rules

- Fingerprints are stored in `EmailedJobFingerprint` table with unique constraint on `(userEmail, fingerprint)`.
- Before sending email, device calls `check-emailed-jobs` to filter out already-emailed jobs.
- After successful email, device calls `mark-emailed-jobs` to persist new fingerprints.
- This ensures the same job is never emailed twice across devices.

---

## 5. Status Codes

| Status Code | Meaning |
|-------------|---------|
| `SUCCESS_EMAIL_SENT` | Jobs found, emailed successfully |
| `SUCCESS_NO_NEW_JOBS` | No new jobs found |
| `SKIPPED_LOCK_HELD_BY_OTHER_DEVICE` | Another device held the lock |
| `FAILED_NETWORK` | Network unavailable on device |
| `FAILED_JOB_SOURCE` | Job source (LinkedIn) returned error |
| `FAILED_EMAIL_BACKEND` | Backend email sending failed |
| `FAILED_AUTH` | Auth token expired/invalid |
| `PAUSED_LOGOUT` | Scheduler paused due to logout |
| `NO_ACTIVE_PREFERENCE` | No active preferences configured |

---

## 6. Error Codes

| HTTP | Meaning |
|------|---------|
| 200 | Success (check `data.granted`/`data.released` for lock status) |
| 400 | Missing required fields |
| 401 | Missing/invalid JWT token |
| 403 | Unauthorized recipient mismatch |
| 500 | Server error (check `message` for details) |

---

## 7. Implementation Checklist for Electron

- [ ] Generate stable `deviceId` (use `node-machine-id` + `electron-store`)
- [ ] Implement `acquireLock(preferenceSetId, deviceId, "electron", appVersion)` before fetching
- [ ] If lock denied (`granted: false`), log skip with status `SKIPPED_LOCK_HELD_BY_OTHER_DEVICE`
- [ ] If lock granted, fetch jobs, call `check-emailed-jobs`, filter new, call `send-email`, call `mark-emailed-jobs`
- [ ] Call `releaseLock(preferenceSetId, deviceId)` in `finally` block
- [ ] Call `syncState(deviceId, "electron", ...)` after all preferences processed
- [ ] Call `releaseLock` for each preferenceSetId even on error
- [ ] If device crashes, lock auto-expires after 10 min (no explicit cleanup needed)
